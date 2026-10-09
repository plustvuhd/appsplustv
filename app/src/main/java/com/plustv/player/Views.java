package com.plustv.player;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Listas e grades do app nativo (adaptadores leves, com reaproveitamento de linhas). */
final class Views {
    private Views() {}

    static ListView list(Context c) {
        ListView l = new ListView(c);
        l.setDivider(null);
        l.setSelector(new ColorDrawable(0x448B5CF6));
        l.setCacheColorHint(0);
        l.setVerticalScrollBarEnabled(false);
        l.setFocusable(true);
        l.setOverScrollMode(View.OVER_SCROLL_NEVER);
        return l;
    }

    static GridView grid(Context c) {
        GridView g = new GridView(c);
        g.setNumColumns(GridView.AUTO_FIT);
        g.setColumnWidth(UI.dp(c, 150));
        g.setHorizontalSpacing(UI.dp(c, 10));
        g.setVerticalSpacing(UI.dp(c, 10));
        g.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        g.setSelector(UI.round(0x668B5CF6, UI.dp(c, 10)));
        g.setCacheColorHint(0);
        g.setVerticalScrollBarEnabled(false);
        g.setFocusable(true);
        g.setOverScrollMode(View.OVER_SCROLL_NEVER);
        g.setPadding(UI.dp(c, 6), UI.dp(c, 6), UI.dp(c, 6), UI.dp(c, 6));
        g.setClipToPadding(false);
        return g;
    }

    /** Lista de textos (categorias). */
    static class Cats extends BaseAdapter {
        final Context c;
        final List<String> names = new ArrayList<>();
        int sel = 0;
        Cats(Context c) { this.c = c; }
        @Override public int getCount() { return names.size(); }
        @Override public Object getItem(int i) { return names.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public View getView(int i, View v, ViewGroup p) {
            TextView t;
            if (v instanceof TextView) t = (TextView) v;
            else {
                t = new TextView(c);
                t.setPadding(UI.dp(c, 16), UI.dp(c, 11), UI.dp(c, 10), UI.dp(c, 11));
                t.setSingleLine(true);
                t.setEllipsize(TextUtils.TruncateAt.END);
                t.setTextSize(16);
            }
            t.setText(names.get(i));
            boolean on = i == sel;
            t.setTextColor(on ? 0xFFC9B3FF : UI.TXT);
            t.setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            return t;
        }
    }

    /** Canais: logo + nome + estrela de favorito. Cresce por páginas conforme rola. */
    static class Chans extends BaseAdapter {
        final Context c;
        final NHome home;
        final List<Item> items = new ArrayList<>();
        int limit = 80;
        Chans(Context c, NHome home) { this.c = c; this.home = home; }
        void set(List<Item> l) { items.clear(); items.addAll(l); limit = 80; notifyDataSetChanged(); }
        @Override public int getCount() { return Math.min(items.size(), limit); }
        @Override public Object getItem(int i) { return items.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public View getView(int i, View v, ViewGroup p) {
            LinearLayout row; ImageView iv; TextView name, star;
            if (v instanceof LinearLayout && v.getTag() != null) { row = (LinearLayout) v; }
            else {
                row = new LinearLayout(c);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(UI.dp(c, 10), UI.dp(c, 6), UI.dp(c, 10), UI.dp(c, 6));
                iv = new ImageView(c);
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                row.addView(iv, new LinearLayout.LayoutParams(UI.dp(c, 56), UI.dp(c, 40)));
                name = new TextView(c);
                name.setTextColor(UI.TXT);
                name.setTextSize(16);
                name.setSingleLine(true);
                name.setEllipsize(TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(0, -2, 1f);
                nl.leftMargin = UI.dp(c, 10);
                row.addView(name, nl);
                star = new TextView(c);
                star.setText("★");
                star.setTextColor(0xFFF5B301);
                star.setTextSize(18);
                row.addView(star, new LinearLayout.LayoutParams(-2, -2));
                row.setTag(new View[]{iv, name, star});
            }
            View[] h = (View[]) row.getTag();
            Item it = items.get(i);
            ((TextView) h[1]).setText(it.name);
            h[2].setVisibility(home.isFav("live", it.id) ? View.VISIBLE : View.GONE);
            Net.image(home.sess, it.icon, (ImageView) h[0], 120);
            if (i >= limit - 8 && limit < items.size()) { limit += 80; p.post(this::notifyDataSetChanged); }
            return row;
        }
    }

    /** Capas de filmes/séries. Pode pedir mais itens ao servidor (listas paginadas). */
    static class Posters extends BaseAdapter {
        final Context c;
        final NHome home;
        final List<Item> items = new ArrayList<>();
        int limit = 60;
        Runnable needMore; // listas paginadas pelo painel (recentes/busca)
        boolean busy = false, done = true;
        Posters(Context c, NHome home) { this.c = c; this.home = home; }
        void set(List<Item> l) { items.clear(); items.addAll(l); limit = 60; notifyDataSetChanged(); }
        @Override public int getCount() { return Math.min(items.size(), limit); }
        @Override public Object getItem(int i) { return items.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public View getView(int i, View v, ViewGroup p) {
            LinearLayout cell; 
            if (v instanceof LinearLayout && v.getTag() != null) { cell = (LinearLayout) v; }
            else {
                cell = new LinearLayout(c);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setPadding(UI.dp(c, 4), UI.dp(c, 4), UI.dp(c, 4), UI.dp(c, 4));
                ImageView iv = new ImageView(c);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setBackground(UI.round(UI.CARD, UI.dp(c, 8)));
                cell.addView(iv, new LinearLayout.LayoutParams(-1, UI.dp(c, 200)));
                TextView t = new TextView(c);
                t.setTextColor(UI.TXT);
                t.setTextSize(13);
                t.setMaxLines(2);
                t.setEllipsize(TextUtils.TruncateAt.END);
                t.setPadding(UI.dp(c, 2), UI.dp(c, 4), UI.dp(c, 2), 0);
                cell.addView(t, new LinearLayout.LayoutParams(-1, UI.dp(c, 40)));
                cell.setTag(new View[]{iv, t});
                cell.setLayoutParams(new AbsListView.LayoutParams(-1, UI.dp(c, 256)));
            }
            View[] h = (View[]) cell.getTag();
            Item it = items.get(i);
            ((TextView) h[1]).setText((home.isFav(it.kind, it.id) ? "★ " : "") + it.name);
            Net.image(home.sess, it.icon, (ImageView) h[0], 270);
            if (i >= limit - 12) {
                if (limit < items.size()) { limit += 60; p.post(this::notifyDataSetChanged); }
                else if (needMore != null && !busy && !done) { busy = true; p.post(needMore); }
            }
            return cell;
        }
    }
}
