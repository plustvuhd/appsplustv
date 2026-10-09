package com.plustv.player;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Tela principal nativa (TV box / Fire Stick): abas Canais, Filmes, Séries, Favoritos e Ajustes. */
public class NHome extends Activity {
    public Net.Sess sess;
    public static NHome cur_;
    public final Handler ui = new Handler(Looper.getMainLooper());
    public final Map<String, List<Item>> favs = new HashMap<>();
    public final Set<String> favSet = new HashSet<>();

    private FrameLayout content;
    private final List<TextView> tabs = new ArrayList<>();
    private final Screen[] screens = new Screen[5];
    private int cur = -1;
    private long backAt = 0;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN | WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
        cur_ = this;
        sess = Net.Sess.load(this);
        if (sess == null) { startActivity(new Intent(this, NLogin.class)); finish(); return; }
        favs.put("live", new ArrayList<>());
        favs.put("movie", new ArrayList<>());
        favs.put("series", new ArrayList<>());

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(UI.BG);
        setContentView(root);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(UI.dp(this, 24), UI.dp(this, 8), UI.dp(this, 24), UI.dp(this, 6));
        TextView title = UI.text(this, sess.title.isEmpty() ? "PlusTV" : sess.title, 22, UI.TXT, true);
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        String[] names = {"Canais", "Filmes", "Séries", "Favoritos", "Ajustes"};
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            TextView t = UI.button(this, names[i], () -> select(idx));
            t.setTextSize(16);
            t.setPadding(UI.dp(this, 18), UI.dp(this, 8), UI.dp(this, 18), UI.dp(this, 8));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.leftMargin = UI.dp(this, 6);
            t.setOnFocusChangeListener((v, f) -> { if (f && cur != idx) select(idx); });
            bar.addView(t, lp);
            tabs.add(t);
        }
        root.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1f));

        loadFavs();
        select(0);
        tabs.get(0).requestFocus();
    }

    private Screen make(int i) {
        switch (i) {
            case 0: return new LiveScreen(this);
            case 1: return new VodScreen(this, "movie");
            case 2: return new VodScreen(this, "series");
            case 3: return new FavScreen(this);
            default: return new SettingsScreen(this);
        }
    }

    public void select(int i) {
        if (i == cur) return;
        if (cur >= 0 && screens[cur] != null) screens[cur].onHide();
        cur = i;
        for (int k = 0; k < tabs.size(); k++) tabs.get(k).setSelected(k == i);
        if (screens[i] == null) screens[i] = make(i);
        content.removeAllViews();
        content.addView(screens[i].view(), new FrameLayout.LayoutParams(-1, -1));
        screens[i].onShow();
    }

    /** Leva o cursor para dentro da aba (usado quando aperta para baixo na barra de abas). */
    public void focusContent() { if (cur >= 0 && screens[cur] != null) screens[cur].firstFocus().requestFocus(); }

    // ---------- preferências ----------
    public SharedPreferences prefs() { return getSharedPreferences("plustv", MODE_PRIVATE); }
    public String fmt() { return "hls".equals(prefs().getString("n_fmt", "ts")) ? "hls" : "ts"; }
    public String engine() { String e = prefs().getString("n_engine", "exo"); return "vlc".equals(e) ? "vlc" : "exo"; }
    public boolean showAdult() { return prefs().getBoolean("n_adult", false); }

    // ---------- PIN, pastas ocultas e travadas (mesma ideia do Web Player) ----------
    private final Set<String> unlocked = new HashSet<>();
    public String pin() { return prefs().getString("n_pin_code", "0000"); }
    private Set<String> set(String k) { return new HashSet<>(prefs().getStringSet(k, new HashSet<String>())); }
    public boolean hidden(String kind, String name) { return set("n_hide").contains(kind + "|" + name) || (!showAdult() && UI.adult(name)); }
    public boolean isLockedFolder(String kind, String name) { return set("n_lock").contains(kind + "|" + name) || (showAdult() && UI.adult(name)); }
    public boolean needsPin(String kind, String name) { return isLockedFolder(kind, name) && !unlocked.contains(kind + "|" + name); }
    public void clearHiddenLocked() { prefs().edit().remove("n_hide").remove("n_lock").apply(); unlocked.clear(); }
    public int hiddenCount() { return set("n_hide").size() + set("n_lock").size(); }

    /** Pede o PIN (4+ dígitos) e chama ok se acertar. */
    public void askPin(String title, final Runnable ok) {
        final android.widget.EditText et = new android.widget.EditText(this);
        et.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        et.setHint("PIN");
        new android.app.AlertDialog.Builder(this).setTitle(title).setView(et)
            .setPositiveButton("OK", (d, w) -> {
                if (et.getText().toString().trim().equals(pin())) ok.run();
                else Toast.makeText(this, "PIN incorreto", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("Cancelar", null).show();
    }

    /** Abre a pasta se não estiver travada; senão pede o PIN. */
    public void gate(final String kind, final String name, final Runnable ok) {
        if (!needsPin(kind, name)) { ok.run(); return; }
        askPin("Pasta travada", () -> { unlocked.add(kind + "|" + name); ok.run(); });
    }

    /** Segurar OK numa pasta: ocultar ou travar/destravar com PIN. */
    public void folderMenu(final String kind, final String name, final Runnable reload) {
        final boolean lk = set("n_lock").contains(kind + "|" + name);
        final String[] opts = { "Ocultar esta pasta", lk ? "Tirar a trava de PIN" : "Travar com PIN" };
        new android.app.AlertDialog.Builder(this).setTitle(name).setItems(opts, (d, w) -> {
            if (w == 0) askPin("PIN para ocultar", () -> { Set<String> h = set("n_hide"); h.add(kind + "|" + name); prefs().edit().putStringSet("n_hide", h).apply(); reload.run(); });
            else askPin("PIN", () -> { Set<String> l = set("n_lock"); if (lk) l.remove(kind + "|" + name); else l.add(kind + "|" + name); prefs().edit().putStringSet("n_lock", l).apply(); unlocked.remove(kind + "|" + name); reload.run(); });
        }).show();
    }

    /** JSON no formato que o PlayerActivity já entende. */
    public String playJson(List<String> urls, String title, long startMs, boolean live, String fit) {
        try {
            JSONObject j = new JSONObject();
            j.put("urls", new JSONArray(urls));
            j.put("title", title);
            j.put("startMs", startMs);
            j.put("engine", engine());
            j.put("live", live);
            j.put("fit", fit);
            return j.toString();
        } catch (Exception e) { return "{}"; }
    }

    public List<String> liveUrls(String id) {
        List<String> u = new ArrayList<>();
        if ("hls".equals(fmt())) { u.add(sess.liveUrl(id, "m3u8")); u.add(sess.liveUrl(id, "ts")); }
        else { u.add(sess.liveUrl(id, "ts")); u.add(sess.liveUrl(id, "m3u8")); }
        return u;
    }

    // ---------- favoritos (a mesma lista do Web Player) ----------
    public boolean isFav(String kind, String id) { return favSet.contains(kind + ":" + id); }

    public void loadFavs() {
        Net.POOL.execute(() -> {
            try {
                JSONObject b = sess.authBody();
                b.put("action", "list");
                JSONObject r = Net.post(sess.panel + "/api/public/roku/favorites", b, 15000);
                if (r.optInt("_code") != 200) return;
                final Map<String, List<Item>> tmp = new HashMap<>();
                final Set<String> set = new HashSet<>();
                for (String k : new String[]{"live", "movie", "series"}) {
                    List<Item> l = new ArrayList<>();
                    JSONArray a = r.optJSONArray(k);
                    if (a != null) for (int i = 0; i < a.length(); i++) {
                        JSONObject o = a.getJSONObject(i);
                        Item it = new Item(k, o.optString("sid"), o.optString("name"), o.optString("logo"), "");
                        l.add(it);
                        set.add(k + ":" + it.id);
                    }
                    tmp.put(k, l);
                }
                ui.post(() -> { favs.clear(); favs.putAll(tmp); favSet.clear(); favSet.addAll(set); });
            } catch (Exception ignored) {}
        });
    }

    /** Favorita ou desfavorita (muda na hora e avisa o painel). */
    public boolean toggleFav(final Item it) {
        final String key = it.kind + ":" + it.id;
        final boolean now;
        List<Item> list = favs.get(it.kind);
        if (list == null) { list = new ArrayList<>(); favs.put(it.kind, list); }
        if (favSet.contains(key)) {
            favSet.remove(key);
            for (int i = list.size() - 1; i >= 0; i--) if (list.get(i).id.equals(it.id)) list.remove(i);
            now = false;
        } else {
            favSet.add(key);
            list.add(0, new Item(it.kind, it.id, it.name, it.icon, it.ext));
            now = true;
        }
        Toast.makeText(this, now ? "Adicionado aos favoritos" : "Removido dos favoritos", Toast.LENGTH_SHORT).show();
        Net.POOL.execute(() -> {
            try {
                JSONObject b = sess.authBody();
                b.put("action", "toggle"); b.put("kind", it.kind); b.put("sid", it.id); b.put("name", it.name); b.put("logo", it.icon);
                Net.post(sess.panel + "/api/public/roku/favorites", b, 15000);
            } catch (Exception ignored) {}
        });
        return now;
    }

    // ---------- recentes de canais (no aparelho) ----------
    public List<Item> recents(String kind) {
        List<Item> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs().getString("n_rec_" + kind, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new Item(kind, o.optString("i"), o.optString("n"), o.optString("l"), o.optString("e")));
            }
        } catch (Exception ignored) {}
        return out;
    }

    public void addRecent(Item it) {
        try {
            List<Item> cur = recents(it.kind);
            JSONArray a = new JSONArray();
            JSONObject f = new JSONObject();
            f.put("i", it.id); f.put("n", it.name); f.put("l", it.icon); f.put("e", it.ext);
            a.put(f);
            for (Item o : cur) {
                if (o.id.equals(it.id)) continue;
                if (a.length() >= 40) break;
                JSONObject j = new JSONObject();
                j.put("i", o.id); j.put("n", o.name); j.put("l", o.icon); j.put("e", o.ext);
                a.put(j);
            }
            prefs().edit().putString("n_rec_" + it.kind, a.toString()).apply();
        } catch (Exception ignored) {}
    }

    // ---------- teclas ----------
    @Override public boolean dispatchKeyEvent(KeyEvent ev) {
        int k = ev.getKeyCode();
        if (ev.getAction() == KeyEvent.ACTION_DOWN) {
            if (k == KeyEvent.KEYCODE_MENU || k == KeyEvent.KEYCODE_BUTTON_Y || k == KeyEvent.KEYCODE_STAR || k == KeyEvent.KEYCODE_PROG_YELLOW) {
                if (cur >= 0 && screens[cur] != null && screens[cur].onMenu()) return true;
            }
            if (k == KeyEvent.KEYCODE_DPAD_DOWN && tabs.contains(getCurrentFocus())) { focusContent(); return true; }
            if (k == KeyEvent.KEYCODE_BACK) {
                if (cur >= 0 && screens[cur] != null && screens[cur].onBack()) return true;
                if (cur != 0) { select(0); tabs.get(0).requestFocus(); return true; }
                long n = System.currentTimeMillis();
                if (n - backAt < 2000) { finishAffinity(); return true; }
                backAt = n;
                Toast.makeText(this, "Aperte voltar de novo para sair", Toast.LENGTH_SHORT).show();
                return true;
            }
        }
        return super.dispatchKeyEvent(ev);
    }

    @Override protected void onPause() { super.onPause(); if (cur >= 0 && screens[cur] != null) screens[cur].onHide(); }
    @Override protected void onResume() { super.onResume(); if (sess != null) loadFavs(); if (cur >= 0 && screens[cur] != null) screens[cur].onShow(); }
    @Override protected void onDestroy() { for (Screen s : screens) if (s != null) s.onHide(); if (cur_ == this) cur_ = null; super.onDestroy(); }
}
