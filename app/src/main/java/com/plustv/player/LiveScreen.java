package com.plustv.player;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.InputType;
import android.util.Base64;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.AspectRatioFrameLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Canais ao vivo: categorias, lista, prévia tocando ao lado (com guia agora/a seguir) e tela cheia com troca de canal. */
public class LiveScreen extends Screen {
    private LinearLayout root;
    private ListView catList, chList;
    private Views.Cats cats;
    private Views.Chans chans;
    private TextView status, title, epgNow, epgNext, prevMsg;
    private TextureView tex;
    private AspectRatioFrameLayout arf;
    private final List<String> catIds = new ArrayList<>();
    private final Map<String, List<Item>> cache = new HashMap<>();
    private List<Item> allLive;
    private int catSel = -1;
    private String loadingKey = "";
    private Item previewItem;
    private ExoPlayer exo;
    private final List<String> catRaw = new ArrayList<>();
    private final java.util.Set<String> blocked = new java.util.HashSet<>(); // pastas ocultas/travadas: não aparecem em "Todos" nem na busca
    private List<String> pvUrls = new ArrayList<>();
    private int pvIdx = 0;
    private boolean visible = false;
    private boolean loadedCats = false;

    private final Runnable loadCatRun = () -> { int p = catList.getSelectedItemPosition(); if (p >= 0 && p != catSel) pickCat(p); };
    private final Runnable previewRun = () -> { int p = chList.getSelectedItemPosition(); if (p >= 0 && p < chans.getCount() && chList.hasFocus()) startPreview(chans.items.get(p)); };

    public LiveScreen(NHome h) {
        super(h);
        Context c = h;
        root = new LinearLayout(c);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setPadding(UI.dp(c, 16), UI.dp(c, 4), UI.dp(c, 16), UI.dp(c, 10));

        cats = new Views.Cats(c);
        catList = Views.list(c);
        catList.setAdapter(cats);
        root.addView(catList, new LinearLayout.LayoutParams(UI.wdp(c, 0.2f, 130, 230), -1));

        LinearLayout mid = new LinearLayout(c);
        mid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(0, -1, 1f);
        ml.leftMargin = UI.dp(c, 10);
        root.addView(mid, ml);
        TextView search = UI.button(c, "Buscar canal", this::askSearch);
        search.setTextSize(15);
        mid.addView(search, new LinearLayout.LayoutParams(-1, -2));
        status = UI.text(c, "", 14, UI.MUTED, false);
        status.setPadding(UI.dp(c, 8), UI.dp(c, 6), 0, 0);
        mid.addView(status, new LinearLayout.LayoutParams(-1, -2));
        chans = new Views.Chans(c, h);
        chList = Views.list(c);
        chList.setAdapter(chans);
        mid.addView(chList, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout right = new LinearLayout(c);
        right.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(UI.wdp(c, 0.32f, 220, 430), -1);
        rl.leftMargin = UI.dp(c, 12);
        root.addView(right, rl);
        FrameLayout box = new FrameLayout(c);
        box.setBackground(UI.round(0xFF000000, UI.dp(c, 10)));
        right.addView(box, new LinearLayout.LayoutParams(-1, UI.wdp(c, 0.32f, 220, 430) * 9 / 16));
        arf = new AspectRatioFrameLayout(c);
        arf.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        tex = new TextureView(c);
        arf.addView(tex, new FrameLayout.LayoutParams(-1, -1));
        box.addView(arf, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        prevMsg = UI.text(c, "Escolha um canal", 15, 0xFFCFC8F0, false);
        prevMsg.setGravity(Gravity.CENTER);
        box.addView(prevMsg, new FrameLayout.LayoutParams(-1, -1));
        title = UI.text(c, "", 20, UI.TXT, true);
        title.setSingleLine(true);
        title.setPadding(UI.dp(c, 2), UI.dp(c, 10), 0, UI.dp(c, 4));
        right.addView(title, new LinearLayout.LayoutParams(-1, -2));
        epgNow = UI.text(c, "", 15, UI.TXT, false);
        epgNow.setMaxLines(3);
        right.addView(epgNow, new LinearLayout.LayoutParams(-1, -2));
        epgNext = UI.text(c, "", 14, UI.MUTED, false);
        epgNext.setMaxLines(2);
        epgNext.setPadding(0, UI.dp(c, 6), 0, 0);
        right.addView(epgNext, new LinearLayout.LayoutParams(-1, -2));
        TextView hint = UI.text(c, "OK: tela cheia   |   Segure OK ou Menu: favoritar", 13, UI.MUTED, false);
        hint.setPadding(0, UI.dp(c, 10), 0, 0);
        right.addView(hint, new LinearLayout.LayoutParams(-1, -2));

        catList.setOnItemClickListener((p, v, pos, id) -> { home.ui.removeCallbacks(loadCatRun); final String rn = pos < catRaw.size() ? catRaw.get(pos) : ""; home.gate("live", rn, () -> { pickCat(pos); chList.requestFocus(); }); });
        catList.setOnItemLongClickListener((p, v, pos, id) -> { String rn = pos < catRaw.size() ? catRaw.get(pos) : ""; if (rn.isEmpty()) return false; home.folderMenu("live", rn, this::loadCats); return true; });
        catList.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { home.ui.removeCallbacks(loadCatRun); home.ui.postDelayed(loadCatRun, 350); }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });
        chList.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { home.ui.removeCallbacks(previewRun); home.ui.postDelayed(previewRun, 650); }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });
        chList.setOnItemClickListener((p, v, pos, id) -> openFull(pos));
        chList.setOnItemLongClickListener((p, v, pos, id) -> { toggle(pos); return true; });
    }

    @Override public View view() { return root; }
    @Override public View firstFocus() { return chans.getCount() > 0 ? chList : catList; }

    @Override public void onShow() {
        visible = true;
        if (!loadedCats) { loadedCats = true; loadCats(); }
        else { chans.notifyDataSetChanged(); }
    }

    @Override public void onHide() {
        visible = false;
        home.ui.removeCallbacks(previewRun);
        releasePreview();
    }

    @Override public boolean onMenu() {
        if (chList.hasFocus()) { int p = chList.getSelectedItemPosition(); if (p >= 0 && p < chans.getCount()) { toggle(p); return true; } }
        return false;
    }

    private void toggle(int pos) {
        if (pos < 0 || pos >= chans.getCount()) return;
        home.toggleFav(chans.items.get(pos));
        chans.notifyDataSetChanged();
    }

    // ---------- dados ----------
    private void loadCats() {
        status.setText("Carregando categorias…");
        allLive = null; cache.clear(); blocked.clear();
        Net.POOL.execute(() -> {
            try {
                Object o = Net.iptv(s, "get_live_categories", null, 25000);
                final List<String> names = new ArrayList<>(), ids = new ArrayList<>(), raw = new ArrayList<>();
                names.add("★ Favoritos"); ids.add("__fav"); raw.add("");
                names.add("Recentes"); ids.add("__rec"); raw.add("");
                names.add("Todos os canais"); ids.add("__all"); raw.add("");
                if (o instanceof JSONArray) {
                    JSONArray a = (JSONArray) o;
                    for (int i = 0; i < a.length(); i++) {
                        JSONObject j = a.getJSONObject(i);
                        String n = Net.str(j, "category_name");
                        if (n.isEmpty()) continue;
                        if (home.hidden("live", n) || home.isLockedFolder("live", n)) blocked.add(Net.str(j, "category_id"));
                        if (home.hidden("live", n)) continue;
                        names.add(n); ids.add(Net.str(j, "category_id")); raw.add(n);
                    }
                }
                home.ui.post(() -> {
                    cats.names.clear(); cats.names.addAll(names);
                    catIds.clear(); catIds.addAll(ids); catRaw.clear(); catRaw.addAll(raw);
                    for (int k = 0; k < names.size(); k++) if (!raw.get(k).isEmpty() && home.isLockedFolder("live", raw.get(k))) cats.names.set(k, "🔒 " + names.get(k));
                    cats.notifyDataSetChanged();
                    status.setText("");
                    int first = home.favs.get("live") != null && !home.favs.get("live").isEmpty() ? 0 : 3 < ids.size() ? 3 : 2;
                    pickCat(first);
                    catList.setSelection(first);
                });
            } catch (Exception e) {
                home.ui.post(() -> status.setText("Não foi possível carregar os canais. Confira a internet e tente de novo."));
                loadedCats = false;
            }
        });
    }

    private void pickCat(int pos) {
        if (pos < 0 || pos >= catIds.size()) return;
        catSel = pos;
        cats.sel = pos;
        cats.notifyDataSetChanged();
        final String id = catIds.get(pos);
        if (pos < catRaw.size() && !catRaw.get(pos).isEmpty() && home.needsPin("live", catRaw.get(pos))) { loadingKey = "__lock"; chans.set(new ArrayList<Item>()); status.setText("Pasta travada. Aperte OK e digite o PIN."); return; }
        loadingKey = id;
        if ("__fav".equals(id)) { show(id, copy(home.favs.get("live"))); return; }
        if ("__rec".equals(id)) { show(id, home.recents("live")); return; }
        List<Item> hit = cache.get(id);
        if (hit != null) { show(id, hit); return; }
        status.setText("Carregando…");
        chans.set(new ArrayList<Item>());
        Net.POOL.execute(() -> {
            try {
                Object o = "__all".equals(id) ? allChannels() : Net.iptv(s, "get_live_streams", "category_id=" + id, 40000);
                final List<Item> l = o instanceof List ? castList(o) : parse((JSONArray) o);
                cache.put(id, l);
                home.ui.post(() -> { if (id.equals(loadingKey)) show(id, l); });
            } catch (Exception e) {
                home.ui.post(() -> { if (id.equals(loadingKey)) status.setText("Não foi possível carregar esta pasta."); });
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static List<Item> castList(Object o) { return (List<Item>) o; }
    private static List<Item> copy(List<Item> l) { return l == null ? new ArrayList<Item>() : new ArrayList<>(l); }

    private List<Item> allChannels() throws Exception {
        if (allLive != null) return allLive;
        Object o = Net.iptv(s, "get_live_streams", null, 60000);
        allLive = parse((JSONArray) o, true);
        return allLive;
    }

    private List<Item> parse(JSONArray a) throws Exception { return parse(a, false); }

    private List<Item> parse(JSONArray a, boolean skipBlocked) throws Exception {
        List<Item> l = new ArrayList<>(a.length());
        for (int i = 0; i < a.length(); i++) {
            JSONObject j = a.getJSONObject(i);
            if (skipBlocked && blocked.contains(Net.str(j, "category_id"))) continue;
            String id = Net.str(j, "stream_id"), n = Net.str(j, "name");
            if (id.isEmpty() || n.isEmpty()) continue;
            l.add(new Item("live", id, n, Net.str(j, "stream_icon"), ""));
        }
        return l;
    }

    private void show(String key, List<Item> l) {
        chans.set(l);
        status.setText(l.isEmpty() ? ("__fav".equals(key) ? "Nenhum favorito ainda. Segure OK em um canal para favoritar." : "Nenhum canal aqui.") : l.size() + " canais");
        chList.setSelection(0);
    }

    // ---------- busca ----------
    private void askSearch() {
        final EditText et = new EditText(home);
        et.setSingleLine(true);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setHint("Nome do canal");
        AlertDialog dlg = new AlertDialog.Builder(home, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Buscar canal").setView(et)
            .setPositiveButton("Buscar", (d, w) -> search(et.getText().toString()))
            .setNegativeButton("Cancelar", null).create();
        et.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        et.setOnEditorActionListener((v, a, e) -> { dlg.dismiss(); search(et.getText().toString()); return true; });
        dlg.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        dlg.show();
        et.requestFocus();
    }

    private void search(final String q) {
        final String[] toks = norm(q).split("\\s+");
        if (q.trim().isEmpty()) return;
        status.setText("Buscando…");
        chans.set(new ArrayList<Item>());
        loadingKey = "__search";
        Net.POOL.execute(() -> {
            try {
                List<Item> all = allChannels();
                final List<Item> out = new ArrayList<>();
                for (Item it : all) {
                    String n = norm(it.name);
                    boolean ok = true;
                    for (String t : toks) if (!t.isEmpty() && !n.contains(t)) { ok = false; break; }
                    if (ok) out.add(it);
                    if (out.size() >= 400) break;
                }
                home.ui.post(() -> { if ("__search".equals(loadingKey)) { cats.sel = -1; cats.notifyDataSetChanged(); show("__search", out); chList.requestFocus(); } });
            } catch (Exception e) {
                home.ui.post(() -> status.setText("Não foi possível buscar agora."));
            }
        });
    }

    private static String norm(String s) {
        return java.text.Normalizer.normalize(s == null ? "" : s, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase().trim();
    }

    // ---------- tela cheia ----------
    private void openFull(int pos) {
        if (pos < 0 || pos >= chans.getCount()) return;
        Item it = chans.items.get(pos);
        home.addRecent(it);
        releasePreview();
        PlayerActivity.zap = new ArrayList<>(chans.items);
        PlayerActivity.zapIdx = pos;
        Intent i = new Intent(home, PlayerActivity.class);
        i.putExtra("json", home.playJson(home.liveUrls(it.id), it.name, 0, true, "contain"));
        i.putExtra("native_ui", true);
        home.startActivity(i);
    }

    // ---------- prévia ----------
    private void startPreview(Item it) {
        if (!visible) return;
        previewItem = it;
        title.setText(it.name);
        epgNow.setText("");
        epgNext.setText("");
        prevMsg.setText("Sintonizando…");
        prevMsg.setVisibility(View.VISIBLE);
        pvUrls = home.liveUrls(it.id);
        pvIdx = 0;
        playNext();
        loadEpg(it);
    }

    private void ensurePlayer() {
        if (exo != null) return;
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
            .setUserAgent(Net.UA).setAllowCrossProtocolRedirects(true).setConnectTimeoutMs(12000).setReadTimeoutMs(15000);
        DefaultLoadControl lc = new DefaultLoadControl.Builder().setBufferDurationsMs(10000, 30000, 1000, 2500).build();
        exo = new ExoPlayer.Builder(home).setMediaSourceFactory(new DefaultMediaSourceFactory(http)).setLoadControl(lc).build();
        exo.setVideoTextureView(tex);
        exo.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int st) { if (st == Player.STATE_READY) prevMsg.setVisibility(View.GONE); }
            @Override public void onTracksChanged(androidx.media3.common.Tracks t) {
                if (t.containsType(androidx.media3.common.C.TRACK_TYPE_VIDEO) && !t.isTypeSupported(androidx.media3.common.C.TRACK_TYPE_VIDEO, false)) {
                    if (pvIdx > 0 && pvIdx <= pvUrls.size()) PlayerActivity.BAD_EXO.add(PlayerActivity.badKey(pvUrls.get(pvIdx - 1)));
                    try { exo.stop(); } catch (Throwable ignored) {}
                    prevMsg.setText("Vídeo H.265: aperte OK para ver em tela cheia (VLC).");
                    prevMsg.setVisibility(View.VISIBLE);
                }
            }
            @Override public void onVideoSizeChanged(VideoSize vs) { if (vs.width > 0 && vs.height > 0) arf.setAspectRatio(vs.width * vs.pixelWidthHeightRatio / vs.height); }
            @Override public void onPlayerError(PlaybackException e) {
                if (pvIdx < pvUrls.size()) home.ui.post(LiveScreen.this::playNext);
                else { prevMsg.setText("Não abriu aqui. Aperte OK para tela cheia."); prevMsg.setVisibility(View.VISIBLE); }
            }
        });
    }

    private void playNext() {
        if (pvIdx >= pvUrls.size()) return;
        String u = pvUrls.get(pvIdx++);
        try {
            ensurePlayer();
            MediaItem.Builder b = new MediaItem.Builder().setUri(Uri.parse(u));
            String l = u.toLowerCase();
            if (l.contains(".m3u8")) b.setMimeType(MimeTypes.APPLICATION_M3U8); else if (l.contains(".ts")) b.setMimeType(MimeTypes.VIDEO_MP2T);
            exo.setMediaItem(b.build());
            exo.prepare();
            exo.setPlayWhenReady(true);
        } catch (Throwable t) { if (pvIdx < pvUrls.size()) playNext(); }
    }

    private void releasePreview() {
        try { if (exo != null) { exo.release(); } } catch (Throwable ignored) {}
        exo = null;
        previewItem = null;
        prevMsg.setText("Escolha um canal");
        prevMsg.setVisibility(View.VISIBLE);
    }

    private void loadEpg(final Item it) {
        Net.POOL.execute(() -> {
            try {
                Object o = Net.iptv(s, "get_short_epg", "stream_id=" + it.id + "&limit=2", 10000);
                if (!(o instanceof JSONObject)) return;
                JSONArray a = ((JSONObject) o).optJSONArray("epg_listings");
                if (a == null || a.length() == 0) return;
                final String now = dec(a.getJSONObject(0).optString("title"));
                final String next = a.length() > 1 ? dec(a.getJSONObject(1).optString("title")) : "";
                home.ui.post(() -> {
                    if (previewItem != it) return;
                    epgNow.setText(now.isEmpty() ? "" : "Agora: " + now);
                    epgNext.setText(next.isEmpty() ? "" : "A seguir: " + next);
                });
            } catch (Exception ignored) {}
        });
    }

    private static String dec(String b64) {
        try { return new String(Base64.decode(b64, Base64.DEFAULT), "UTF-8").trim(); } catch (Exception e) { return b64 == null ? "" : b64; }
    }
}
