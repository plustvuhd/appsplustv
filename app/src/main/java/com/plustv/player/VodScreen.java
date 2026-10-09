package com.plustv.player;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.text.InputType;
import android.view.View;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Filmes e Séries: abre em "Recentemente adicionados" com as capas, categorias ao lado, busca em todas as pastas. */
public class VodScreen extends Screen {
    private final String kind;
    private LinearLayout root;
    private ListView catList;
    private GridView grid;
    private Views.Cats cats;
    private Views.Posters posters;
    private TextView status;
    private final List<String> catIds = new ArrayList<>();
    private final Map<String, List<Item>> cache = new HashMap<>();
    private boolean loaded = false;
    private String key = "";
    private String mode = "", query = "";
    private int selCat = -1;

    private final List<String> catRaw = new ArrayList<>();
    private final Runnable catRun = () -> { int p = catList.getSelectedItemPosition(); if (p >= 0 && p != selCat) pick(p); };

    public VodScreen(NHome h, String kind) {
        super(h);
        this.kind = kind;
        Context c = h;
        root = new LinearLayout(c);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setPadding(UI.dp(c, 16), UI.dp(c, 4), UI.dp(c, 16), UI.dp(c, 8));
        cats = new Views.Cats(c);
        catList = Views.list(c);
        catList.setAdapter(cats);
        root.addView(catList, new LinearLayout.LayoutParams(UI.wdp(c, 0.22f, 140, 250), -1));

        LinearLayout right = new LinearLayout(c);
        right.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(0, -1, 1f);
        rl.leftMargin = UI.dp(c, 12);
        root.addView(right, rl);
        LinearLayout top = new LinearLayout(c);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView search = UI.button(c, "movie".equals(kind) ? "Buscar filme" : "Buscar série", this::askSearch);
        search.setTextSize(15);
        top.addView(search, new LinearLayout.LayoutParams(-2, -2));
        status = UI.text(c, "", 14, UI.MUTED, false);
        status.setPadding(UI.dp(c, 14), 0, 0, 0);
        top.addView(status, new LinearLayout.LayoutParams(0, -2, 1f));
        right.addView(top, new LinearLayout.LayoutParams(-1, -2));
        posters = new Views.Posters(c, h);
        grid = Views.grid(c);
        grid.setAdapter(posters);
        right.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1f));

        catList.setOnItemClickListener((p, v, pos, id) -> { home.ui.removeCallbacks(catRun); final String rn = pos < catRaw.size() ? catRaw.get(pos) : ""; home.gate(kind, rn, () -> { pick(pos); grid.requestFocus(); }); });
        catList.setOnItemLongClickListener((p, v, pos, id) -> { String rn = pos < catRaw.size() ? catRaw.get(pos) : ""; if (rn.isEmpty()) return false; home.folderMenu(kind, rn, this::loadCats); return true; });
        catList.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { home.ui.removeCallbacks(catRun); home.ui.postDelayed(catRun, 400); }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });
        grid.setOnItemClickListener((p, v, pos, id) -> open(pos));
        grid.setOnItemLongClickListener((p, v, pos, id) -> { toggle(pos); return true; });
    }

    @Override public View view() { return root; }
    @Override public View firstFocus() { return posters.getCount() > 0 ? grid : catList; }

    @Override public void onShow() {
        if (!loaded) { loaded = true; loadCats(); }
        else posters.notifyDataSetChanged();
    }

    @Override public boolean onMenu() {
        if (grid.hasFocus()) { int p = grid.getSelectedItemPosition(); if (p >= 0) { toggle(p); return true; } }
        return false;
    }

    private void toggle(int pos) {
        if (pos < 0 || pos >= posters.getCount()) return;
        home.toggleFav(posters.items.get(pos));
        posters.notifyDataSetChanged();
    }

    private void open(int pos) {
        if (pos < 0 || pos >= posters.getCount()) return;
        Item it = posters.items.get(pos);
        Intent i = new Intent(home, NDetail.class);
        i.putExtra("kind", kind).putExtra("id", it.id).putExtra("name", it.name).putExtra("icon", it.icon).putExtra("ext", it.ext);
        home.startActivity(i);
    }

    private void loadCats() {
        status.setText("Carregando…");
        Net.POOL.execute(() -> {
            try {
                Object o = Net.iptv(s, "movie".equals(kind) ? "get_vod_categories" : "get_series_categories", null, 25000);
                final List<String> names = new ArrayList<>(), ids = new ArrayList<>(), raw = new ArrayList<>();
                names.add("Recentemente adicionados"); ids.add("__recent"); raw.add("");
                names.add("★ Favoritos"); ids.add("__fav"); raw.add("");
                if (o instanceof JSONArray) {
                    JSONArray a = (JSONArray) o;
                    for (int i = 0; i < a.length(); i++) {
                        JSONObject j = a.getJSONObject(i);
                        String n = Net.str(j, "category_name");
                        if (n.isEmpty()) continue;
                        if (home.hidden(kind, n)) continue;
                        names.add(n); ids.add(Net.str(j, "category_id")); raw.add(n);
                    }
                }
                home.ui.post(() -> {
                    cats.names.clear(); cats.names.addAll(names);
                    catIds.clear(); catIds.addAll(ids); catRaw.clear(); catRaw.addAll(raw);
                    for (int k = 0; k < names.size(); k++) if (!raw.get(k).isEmpty() && home.isLockedFolder(kind, raw.get(k))) cats.names.set(k, "🔒 " + names.get(k));
                    cats.notifyDataSetChanged();
                    pick(0);
                });
            } catch (Exception e) {
                loaded = false;
                home.ui.post(() -> status.setText("Não foi possível carregar. Confira a internet."));
            }
        });
    }

    private void pick(int pos) {
        if (pos < 0 || pos >= catIds.size()) return;
        selCat = pos;
        cats.sel = pos;
        cats.notifyDataSetChanged();
        final String id = catIds.get(pos);
        if (pos < catRaw.size() && !catRaw.get(pos).isEmpty() && home.needsPin(kind, catRaw.get(pos))) { key = "__lock"; posters.set(new ArrayList<Item>()); status.setText("Pasta travada. Aperte OK e digite o PIN."); return; }
        key = id;
        mode = ""; query = "";
        posters.needMore = null; posters.done = true; posters.busy = false;
        if ("__fav".equals(id)) {
            List<Item> l = new ArrayList<>();
            List<Item> f = home.favs.get(kind);
            if (f != null) l.addAll(f);
            posters.set(l);
            status.setText(l.isEmpty() ? "Nenhum favorito ainda. Segure OK em uma capa para favoritar." : l.size() + " favoritos");
            return;
        }
        if ("__recent".equals(id)) { startBrowse("recent", ""); return; }
        List<Item> hit = cache.get(id);
        if (hit != null) { posters.set(hit); status.setText(hit.size() + " títulos"); grid.setSelection(0); return; }
        status.setText("Carregando…");
        posters.set(new ArrayList<Item>());
        Net.POOL.execute(() -> {
            try {
                boolean mv = "movie".equals(kind);
                Object o = Net.iptv(s, mv ? "get_vod_streams" : "get_series", "category_id=" + id, 60000);
                JSONArray a = (JSONArray) o;
                final List<Item> l = new ArrayList<>(a.length());
                for (int i = 0; i < a.length(); i++) {
                    JSONObject j = a.getJSONObject(i);
                    String iid = Net.str(j, mv ? "stream_id" : "series_id"), n = Net.str(j, "name");
                    if (iid.isEmpty() || n.isEmpty()) continue;
                    l.add(new Item(kind, iid, n, Net.str(j, mv ? "stream_icon" : "cover"), Net.str(j, "container_extension")));
                }
                cache.put(id, l);
                home.ui.post(() -> { if (id.equals(key)) { posters.set(l); status.setText(l.size() + " títulos"); grid.setSelection(0); } });
            } catch (Exception e) {
                home.ui.post(() -> { if (id.equals(key)) status.setText("Não foi possível carregar esta pasta."); });
            }
        });
    }

    // ---------- listas paginadas pelo painel (recentes e busca em todas as pastas) ----------
    private void startBrowse(String m, String q) {
        mode = m; query = q;
        posters.set(new ArrayList<Item>());
        posters.done = false; posters.busy = false;
        posters.needMore = this::fetchPage;
        status.setText("search".equals(m) ? "Buscando…" : "Carregando…");
        fetchPage();
    }

    private void fetchPage() {
        final String m = mode, q = query, kk = key;
        final int off = posters.items.size();
        posters.busy = true;
        Net.POOL.execute(() -> {
            try {
                JSONObject b = s.authBody();
                b.put("kind", kind); b.put("mode", m); b.put("q", q); b.put("offset", off); b.put("limit", 60);
                JSONObject r = Net.post(s.panel + "/api/public/roku/browse", b, 60000);
                if (r.optInt("_code") != 200) throw new Exception(r.optString("error", "erro"));
                JSONArray a = r.optJSONArray("items");
                final int total = r.optInt("total", 0);
                final List<Item> l = new ArrayList<>();
                if (a != null) for (int i = 0; i < a.length(); i++) {
                    JSONObject j = a.getJSONObject(i);
                    l.add(new Item(kind, j.optString("id"), j.optString("name"), j.optString("icon"), j.optString("ext")));
                }
                home.ui.post(() -> {
                    if (!m.equals(mode) || !q.equals(query) || !kk.equals(key) && !"__search".equals(key)) return;
                    posters.items.addAll(l);
                    posters.busy = false;
                    posters.done = posters.items.size() >= total || l.isEmpty();
                    posters.notifyDataSetChanged();
                    status.setText("search".equals(m) ? total + " resultados" : (total > 0 ? "Mais recentes primeiro" : "Nada encontrado"));
                });
            } catch (Exception e) {
                home.ui.post(() -> { posters.busy = false; posters.done = true; status.setText("Não foi possível carregar. Tente de novo."); });
            }
        });
    }

    private void askSearch() {
        final EditText et = new EditText(home);
        et.setSingleLine(true);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setHint("Nome");
        AlertDialog dlg = new AlertDialog.Builder(home, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("movie".equals(kind) ? "Buscar filme em todas as pastas" : "Buscar série em todas as pastas").setView(et)
            .setPositiveButton("Buscar", (d, w) -> doSearch(et.getText().toString()))
            .setNegativeButton("Cancelar", null).create();
        et.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        et.setOnEditorActionListener((v, a, e) -> { dlg.dismiss(); doSearch(et.getText().toString()); return true; });
        dlg.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        dlg.show();
        et.requestFocus();
    }

    private void doSearch(String q) {
        q = q == null ? "" : q.trim();
        if (q.isEmpty()) return;
        key = "__search";
        cats.sel = -1;
        selCat = -1;
        cats.notifyDataSetChanged();
        startBrowse("search", q);
        grid.requestFocus();
    }
}
