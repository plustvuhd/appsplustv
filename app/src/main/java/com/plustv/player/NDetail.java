package com.plustv.player;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import android.view.ViewGroup;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Detalhe de filme ou série: capa, sinopse, Assistir/Continuar, favoritar e (séries) temporadas e episódios. */
public class NDetail extends Activity {
    private static final int REQ = 91;
    private Net.Sess sess;
    private String kind, id, name, icon, ext;
    private TextView meta, plot, bPlay, bStart, bFav;
    private ListView seasonList, epList;
    private SimpleAdapter seasonAd, epAd;
    private final List<Ep> eps = new ArrayList<>();
    private final List<Integer> seasons = new ArrayList<>();
    private int curSeason = -1;
    private Ep playing;
    private boolean fav = false;

    static class Ep { String id, title, ext; int season, num; }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        sess = Net.Sess.load(this);
        Intent in = getIntent();
        kind = in.getStringExtra("kind"); id = in.getStringExtra("id"); name = in.getStringExtra("name");
        icon = in.getStringExtra("icon"); ext = in.getStringExtra("ext");
        if (sess == null || kind == null || id == null) { finish(); return; }
        if (name == null) name = "";
        if (ext == null) ext = "";
        build();
        load();
    }

    private void build() {
        Context c = this;
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(UI.BG);
        root.setPadding(UI.dp(c, 24), UI.dp(c, 16), UI.dp(c, 24), UI.dp(c, 12));
        setContentView(root);

        ImageView poster = new ImageView(c);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        poster.setBackground(UI.round(UI.CARD, UI.dp(c, 10)));
        int pw = UI.wdp(c, 0.22f, 120, 230);
        root.addView(poster, new LinearLayout.LayoutParams(pw, pw * 3 / 2));
        Net.image(sess, icon, poster, 420);

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, -1, 1f);
        cl.leftMargin = UI.dp(c, 28);
        root.addView(col, cl);

        TextView t = UI.text(c, name, 28, UI.TXT, true);
        t.setMaxLines(2);
        col.addView(t, new LinearLayout.LayoutParams(-1, -2));
        meta = UI.text(c, "", 15, UI.MUTED, false);
        meta.setPadding(0, UI.dp(c, 6), 0, UI.dp(c, 8));
        col.addView(meta, new LinearLayout.LayoutParams(-1, -2));
        plot = UI.text(c, "Carregando…", 15, 0xFFDAD6F2, false);
        plot.setMaxLines(5);
        plot.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(plot, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout btns = new LinearLayout(c);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setPadding(0, UI.dp(c, 14), 0, UI.dp(c, 10));
        bPlay = UI.button(c, "Assistir", this::playMain);
        bStart = UI.button(c, "Do início", () -> playMainFrom(0));
        bStart.setVisibility(View.GONE);
        bFav = UI.button(c, "☆ Favoritar", this::toggleFav);
        for (TextView v : new TextView[]{bPlay, bStart, bFav}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = UI.dp(c, 10);
            btns.addView(v, lp);
        }
        col.addView(btns, new LinearLayout.LayoutParams(-1, -2));

        if ("series".equals(kind)) {
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            seasonList = Views.list(c);
            seasonAd = new SimpleAdapter(c);
            seasonList.setAdapter(seasonAd);
            row.addView(seasonList, new LinearLayout.LayoutParams(UI.dp(c, 170), -1));
            epList = Views.list(c);
            epAd = new SimpleAdapter(c);
            epList.setAdapter(epAd);
            LinearLayout.LayoutParams el = new LinearLayout.LayoutParams(0, -1, 1f);
            el.leftMargin = UI.dp(c, 10);
            row.addView(epList, el);
            col.addView(row, new LinearLayout.LayoutParams(-1, 0, 1f));
            seasonList.setOnItemClickListener((p, v, pos, i) -> { showSeason(pos); epList.requestFocus(); });
            seasonList.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long i) { if (pos != curSeason) showSeason(pos); }
                @Override public void onNothingSelected(android.widget.AdapterView<?> p) {}
            });
            epList.setOnItemClickListener((p, v, pos, i) -> { List<Ep> l = epsOf(curSeason); if (pos >= 0 && pos < l.size()) playEp(l.get(pos), true); });
        }
        bPlay.requestFocus();
        refreshFav();
    }

    // ---------- dados ----------
    private void load() {
        Net.POOL.execute(() -> {
            try {
                if ("movie".equals(kind)) {
                    Object o = Net.iptv(sess, "get_vod_info", "vod_id=" + id, 25000);
                    final JSONObject j = (JSONObject) o;
                    final JSONObject info = j.optJSONObject("info");
                    final JSONObject md = j.optJSONObject("movie_data");
                    final String e = md != null ? Net.str(md, "container_extension") : "";
                    runOnUiThread(() -> {
                        if (!e.isEmpty()) ext = e;
                        fillInfo(info);
                        updatePlayLabel();
                    });
                } else {
                    Object o = Net.iptv(sess, "get_series_info", "series_id=" + id, 40000);
                    final JSONObject j = (JSONObject) o;
                    final JSONObject info = j.optJSONObject("info");
                    final List<Ep> list = new ArrayList<>();
                    JSONObject ej = j.optJSONObject("episodes");
                    if (ej != null) {
                        java.util.Iterator<String> it = ej.keys();
                        while (it.hasNext()) {
                            String sk = it.next();
                            JSONArray a = ej.optJSONArray(sk);
                            if (a == null) continue;
                            for (int i = 0; i < a.length(); i++) {
                                JSONObject e = a.getJSONObject(i);
                                Ep ep = new Ep();
                                ep.id = Net.str(e, "id"); ep.title = Net.str(e, "title"); ep.ext = Net.str(e, "container_extension");
                                try { ep.season = Integer.parseInt(Net.str(e, "season").isEmpty() ? sk : Net.str(e, "season")); } catch (Exception x) { ep.season = 1; }
                                try { ep.num = Integer.parseInt(Net.str(e, "episode_num")); } catch (Exception x) { ep.num = i + 1; }
                                if (!ep.id.isEmpty()) list.add(ep);
                            }
                        }
                    }
                    Collections.sort(list, new Comparator<Ep>() {
                        @Override public int compare(Ep a, Ep b) { return a.season != b.season ? a.season - b.season : a.num - b.num; }
                    });
                    runOnUiThread(() -> {
                        fillInfo(info);
                        eps.clear(); eps.addAll(list);
                        seasons.clear();
                        for (Ep e : list) if (!seasons.contains(e.season)) seasons.add(e.season);
                        seasonAd.names.clear();
                        for (int sn : seasons) seasonAd.names.add("Temporada " + sn);
                        seasonAd.sel = 0;
                        seasonAd.notifyDataSetChanged();
                        if (!seasons.isEmpty()) showSeason(0);
                        updatePlayLabel();
                    });
                }
            } catch (Exception e) {
                runOnUiThread(() -> plot.setText("Não foi possível carregar os detalhes, mas você pode assistir."));
            }
        });
    }

    private void fillInfo(JSONObject info) {
        String p = "", m = "";
        if (info != null) {
            p = Net.str(info, "plot");
            if (p.isEmpty()) p = Net.str(info, "description");
            List<String> bits = new ArrayList<>();
            String y = Net.str(info, "releasedate");
            if (y.isEmpty()) y = Net.str(info, "releaseDate");
            if (y.length() >= 4) bits.add(y.substring(0, 4));
            String g = Net.str(info, "genre");
            if (!g.isEmpty()) bits.add(g);
            String r = Net.str(info, "rating");
            if (r.isEmpty()) r = Net.str(info, "rating_5based");
            if (!r.isEmpty() && !"0".equals(r)) bits.add("★ " + r);
            String d = Net.str(info, "duration");
            if (!d.isEmpty() && "movie".equals(kind)) bits.add(d);
            m = android.text.TextUtils.join("   ·   ", bits);
        }
        plot.setText(p.isEmpty() ? "Sem sinopse." : p);
        meta.setText(m);
    }

    // ---------- séries ----------
    private List<Ep> epsOf(int seasonIdx) {
        List<Ep> l = new ArrayList<>();
        if (seasonIdx < 0 || seasonIdx >= seasons.size()) return l;
        int sn = seasons.get(seasonIdx);
        for (Ep e : eps) if (e.season == sn) l.add(e);
        return l;
    }

    private void showSeason(int idx) {
        curSeason = idx;
        seasonAd.sel = idx;
        seasonAd.notifyDataSetChanged();
        epAd.names.clear();
        for (Ep e : epsOf(idx)) epAd.names.add("E" + e.num + "  " + (e.title.isEmpty() ? "Episódio " + e.num : e.title) + progMark(e.id));
        epAd.notifyDataSetChanged();
    }

    private String progMark(String epId) {
        long[] p = prog("series", epId);
        return p[0] > 30000 && (p[1] <= 0 || p[0] < p[1] - 45000) ? "   ▶ " + fmt(p[0]) : (p[2] == 1 ? "   ✓" : "");
    }

    // ---------- reprodução ----------
    private void updatePlayLabel() {
        if ("movie".equals(kind)) {
            long[] p = prog("movie", id);
            if (p[0] > 30000 && (p[1] <= 0 || p[0] < p[1] - 45000)) { bPlay.setText("Continuar (" + fmt(p[0]) + ")"); bStart.setVisibility(View.VISIBLE); }
            else { bPlay.setText("Assistir"); bStart.setVisibility(View.GONE); }
        } else {
            Ep last = lastEp();
            bPlay.setText(last != null ? "Continuar: T" + last.season + " E" + last.num : eps.isEmpty() ? "Carregando…" : "Assistir T1 E1");
        }
    }

    private Ep lastEp() {
        String id0 = getSharedPreferences("plustv", MODE_PRIVATE).getString("n_last_" + id, "");
        for (Ep e : eps) if (e.id.equals(id0)) return e;
        return null;
    }

    private void playMain() {
        if ("movie".equals(kind)) { long[] p = prog("movie", id); playMovie(p[0] > 30000 && (p[1] <= 0 || p[0] < p[1] - 45000) ? p[0] : 0); }
        else {
            if (eps.isEmpty()) { Toast.makeText(this, "Aguarde carregar os episódios", Toast.LENGTH_SHORT).show(); return; }
            Ep e = lastEp();
            playEp(e != null ? e : eps.get(0), true);
        }
    }

    private void playMainFrom(long ms) { if ("movie".equals(kind)) playMovie(ms); }

    private void playMovie(long startMs) {
        launch(sess.movieUrl(id, ext), name, startMs);
    }

    private void playEp(Ep e, boolean resume) {
        playing = e;
        getSharedPreferences("plustv", MODE_PRIVATE).edit().putString("n_last_" + id, e.id).apply();
        long[] p = prog("series", e.id);
        long start = resume && p[0] > 30000 && (p[1] <= 0 || p[0] < p[1] - 45000) ? p[0] : 0;
        launch(sess.seriesUrl(e.id, e.ext), name + "  T" + e.season + " E" + e.num, start);
    }

    private void launch(String url, String title, long startMs) {
        NHome h = NHome.cur_;
        String engine = getSharedPreferences("plustv", MODE_PRIVATE).getString("n_engine", "exo");
        try {
            JSONObject j = new JSONObject();
            j.put("urls", new JSONArray().put(url));
            j.put("title", title);
            j.put("startMs", startMs);
            j.put("engine", "vlc".equals(engine) ? "vlc" : "exo");
            j.put("live", false);
            j.put("fit", "contain");
            Intent i = new Intent(this, PlayerActivity.class);
            i.putExtra("json", j.toString());
            i.putExtra("native_ui", true);
            startActivityForResult(i, REQ);
        } catch (Exception ignored) {}
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ) return;
        long pos = data != null ? data.getLongExtra("pos", 0) : 0;
        long dur = data != null ? data.getLongExtra("dur", 0) : 0;
        boolean ended = data != null && data.getBooleanExtra("ended", false);
        boolean err = data == null || data.getBooleanExtra("err", false);
        String key = "series".equals(kind) ? (playing != null ? playing.id : "") : id;
        if (err) { Toast.makeText(this, "Não foi possível reproduzir agora. Tente de novo.", Toast.LENGTH_LONG).show(); return; }
        if (!key.isEmpty()) saveProg(kind, key, ended ? 0 : pos, dur, ended);
        if ("series".equals(kind)) {
            if (ended && playing != null) {
                Ep nx = nextOf(playing);
                if (nx != null) { playEp(nx, false); return; }
            }
            if (curSeason >= 0) showSeason(curSeason);
        }
        updatePlayLabel();
    }

    private Ep nextOf(Ep e) {
        int i = eps.indexOf(e);
        return i >= 0 && i + 1 < eps.size() ? eps.get(i + 1) : null;
    }

    // ---------- progresso (no aparelho) ----------
    private long[] prog(String k, String key) {
        String v = getSharedPreferences("plustv", MODE_PRIVATE).getString("n_prog_" + k + "_" + key, "");
        long[] r = {0, 0, 0};
        try { String[] a = v.split("\\|"); if (a.length >= 2) { r[0] = Long.parseLong(a[0]); r[1] = Long.parseLong(a[1]); } if (a.length >= 3) r[2] = Long.parseLong(a[2]); } catch (Exception ignored) {}
        return r;
    }

    private void saveProg(String k, String key, long pos, long dur, boolean done) {
        getSharedPreferences("plustv", MODE_PRIVATE).edit().putString("n_prog_" + k + "_" + key, pos + "|" + dur + "|" + (done ? 1 : 0)).apply();
    }

    private static String fmt(long ms) {
        long s = Math.max(0, ms / 1000);
        long h = s / 3600, m = (s % 3600) / 60, ss = s % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, ss) : String.format("%d:%02d", m, ss);
    }

    // ---------- favoritos ----------
    private void refreshFav() {
        NHome h = NHome.cur_;
        fav = h != null && h.isFav(kind, id);
        bFav.setText(fav ? "★ Nos favoritos" : "☆ Favoritar");
    }

    private void toggleFav() {
        NHome h = NHome.cur_;
        if (h != null) { h.toggleFav(new Item(kind, id, name, icon, ext)); refreshFav(); }
    }

    static class SimpleAdapter extends BaseAdapter {
        final Context c;
        final List<String> names = new ArrayList<>();
        int sel = -1;
        SimpleAdapter(Context c) { this.c = c; }
        @Override public int getCount() { return names.size(); }
        @Override public Object getItem(int i) { return names.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public View getView(int i, View v, ViewGroup p) {
            TextView t = v instanceof TextView ? (TextView) v : new TextView(c);
            t.setPadding(UI.dp(c, 14), UI.dp(c, 10), UI.dp(c, 10), UI.dp(c, 10));
            t.setSingleLine(true);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            t.setTextSize(15);
            t.setText(names.get(i));
            t.setTextColor(i == sel ? 0xFFC9B3FF : UI.TXT);
            return t;
        }
    }
}
