package com.plustv.player;

import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.AdapterView;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Favoritos (os mesmos do Web Player): Canais, Filmes e Séries. */
public class FavScreen extends Screen {
    private LinearLayout root;
    private ListView kinds, chList;
    private GridView grid;
    private Views.Cats cats;
    private Views.Chans chans;
    private Views.Posters posters;
    private TextView status;
    private int sel = 0;
    private final String[] K = {"live", "movie", "series"};

    public FavScreen(NHome h) {
        super(h);
        Context c = h;
        root = new LinearLayout(c);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setPadding(UI.dp(c, 16), UI.dp(c, 4), UI.dp(c, 16), UI.dp(c, 8));
        cats = new Views.Cats(c);
        cats.names.add("Canais"); cats.names.add("Filmes"); cats.names.add("Séries");
        kinds = Views.list(c);
        kinds.setAdapter(cats);
        root.addView(kinds, new LinearLayout.LayoutParams(UI.wdp(c, 0.2f, 120, 230), -1));
        LinearLayout right = new LinearLayout(c);
        right.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(0, -1, 1f);
        rl.leftMargin = UI.dp(c, 12);
        root.addView(right, rl);
        status = UI.text(c, "", 14, UI.MUTED, false);
        status.setPadding(UI.dp(c, 8), UI.dp(c, 4), 0, UI.dp(c, 6));
        right.addView(status, new LinearLayout.LayoutParams(-1, -2));
        FrameLayout box = new FrameLayout(c);
        right.addView(box, new LinearLayout.LayoutParams(-1, 0, 1f));
        chans = new Views.Chans(c, h);
        chList = Views.list(c);
        chList.setAdapter(chans);
        box.addView(chList, new FrameLayout.LayoutParams(-1, -1));
        posters = new Views.Posters(c, h);
        grid = Views.grid(c);
        grid.setAdapter(posters);
        box.addView(grid, new FrameLayout.LayoutParams(-1, -1));

        kinds.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { pick(pos); }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });
        kinds.setOnItemClickListener((p, v, pos, id) -> { pick(pos); (pos == 0 ? chList : grid).requestFocus(); });
        chList.setOnItemClickListener((p, v, pos, id) -> playLive(pos));
        chList.setOnItemLongClickListener((p, v, pos, id) -> { toggleSel(); return true; });
        grid.setOnItemClickListener((p, v, pos, id) -> {
            Item it = posters.items.get(pos);
            Intent i = new Intent(home, NDetail.class);
            i.putExtra("kind", it.kind).putExtra("id", it.id).putExtra("name", it.name).putExtra("icon", it.icon).putExtra("ext", it.ext);
            home.startActivity(i);
        });
        grid.setOnItemLongClickListener((p, v, pos, id) -> { toggleSel(); return true; });
    }

    @Override public View view() { return root; }
    @Override public View firstFocus() { return kinds; }

    @Override public void onShow() {
        home.loadFavs();
        pick(sel);
        home.ui.postDelayed(() -> pick(sel), 900); // depois de o painel responder com a lista atualizada
    }

    private void pick(int pos) {
        sel = pos;
        cats.sel = pos;
        cats.notifyDataSetChanged();
        List<Item> l = new ArrayList<>();
        List<Item> f = home.favs.get(K[pos]);
        if (f != null) l.addAll(f);
        if (pos == 0) { chans.set(l); chList.setVisibility(View.VISIBLE); grid.setVisibility(View.GONE); }
        else { posters.set(l); grid.setVisibility(View.VISIBLE); chList.setVisibility(View.GONE); }
        status.setText(l.isEmpty() ? "Nenhum favorito aqui. Segure OK em um item para favoritar." : l.size() + " favoritos");
    }

    private void toggleSel() {
        if (sel == 0) { int p = chList.getSelectedItemPosition(); if (p >= 0 && p < chans.getCount()) { home.toggleFav(chans.items.get(p)); pick(0); } }
        else { int p = grid.getSelectedItemPosition(); if (p >= 0 && p < posters.getCount()) { home.toggleFav(posters.items.get(p)); pick(sel); } }
    }

    @Override public boolean onMenu() { toggleSel(); return true; }

    private void playLive(int pos) {
        if (pos < 0 || pos >= chans.getCount()) return;
        Item it = chans.items.get(pos);
        home.addRecent(it);
        PlayerActivity.zap = new ArrayList<>(chans.items);
        PlayerActivity.zapIdx = pos;
        Intent i = new Intent(home, PlayerActivity.class);
        i.putExtra("json", home.playJson(home.liveUrls(it.id), it.name, 0, true, "contain"));
        i.putExtra("native_ui", true);
        home.startActivity(i);
    }
}
