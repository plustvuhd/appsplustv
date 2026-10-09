package com.plustv.player;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

/** Estilo visual (tema escuro roxo) e peças reutilizáveis do app nativo. */
public final class UI {
    private UI() {}

    public static final int BG = 0xFF0B0A14, CARD = 0xFF17152A, CARD2 = 0xFF211E3A, ACC = 0xFF8B5CF6, TXT = 0xFFFFFFFF, MUTED = 0xFFA9A3C9, RED = 0xFFE5383B;

    /** Largura útil da tela em dp (TV ~960, celular deitado ~650). */
    public static int widthDp(Context c) { android.util.DisplayMetrics m = c.getResources().getDisplayMetrics(); return Math.round(m.widthPixels / m.density); }
    /** Largura proporcional à tela, limitada entre min e max (dp). */
    public static int wdp(Context c, float frac, int min, int max) { int w = Math.round(widthDp(c) * frac); return dp(c, Math.max(min, Math.min(max, w))); }

    public static int dp(Context c, int v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }

    public static GradientDrawable round(int color, int radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    /** Fundo que acende em roxo quando o controle remoto está em cima. */
    public static StateListDrawable focusBg(Context c, int normal, int radiusDp) {
        int r = dp(c, radiusDp);
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, round(ACC, r));
        s.addState(new int[]{android.R.attr.state_pressed}, round(ACC, r));
        s.addState(new int[]{android.R.attr.state_selected}, round(0x558B5CF6, r));
        s.addState(new int[]{}, round(normal, r));
        return s;
    }

    public static ColorDrawable selector() { return new ColorDrawable(0x00000000); }

    public static TextView text(Context c, String t, int sp, int color, boolean bold) {
        TextView v = new TextView(c);
        v.setText(t);
        v.setTextSize(sp);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT_BOLD);
        return v;
    }

    public static TextView button(Context c, String label, final Runnable onClick) {
        TextView v = text(c, label, 17, TXT, true);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(c, 22), dp(c, 12), dp(c, 22), dp(c, 12));
        v.setFocusable(true);
        v.setClickable(true);
        v.setBackground(focusBg(c, CARD2, 10));
        v.setOnClickListener(x -> { if (onClick != null) onClick.run(); });
        return v;
    }

    public static EditText field(Context c, String hint, boolean password) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setHintTextColor(0xFF7F7AA3);
        e.setTextColor(TXT);
        e.setTextSize(18);
        e.setSingleLine(true);
        e.setPadding(dp(c, 16), dp(c, 12), dp(c, 16), dp(c, 12));
        e.setBackground(focusFieldBg(c));
        if (password) e.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        else e.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        return e;
    }

    private static StateListDrawable focusFieldBg(Context c) {
        int r = dp(c, 10);
        GradientDrawable on = round(CARD2, r);
        on.setStroke(dp(c, 2), ACC);
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, on);
        s.addState(new int[]{}, round(CARD, r));
        return s;
    }

    public static View space(Context c, int h) {
        View v = new View(c);
        v.setLayoutParams(new android.widget.LinearLayout.LayoutParams(1, dp(c, h)));
        return v;
    }

    /** Nome parece categoria adulta? */
    public static boolean adult(String n) {
        if (n == null) return false;
        String l = n.toLowerCase();
        return l.contains("adult") || l.contains("xxx") || l.contains("+18") || l.contains("18+") || l.contains("porn") || l.contains("sex") || l.contains("hot");
    }
}
