package com.plustv.player;

import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Ajustes do app nativo. */
public class SettingsScreen extends Screen {
    private ScrollView scroll;
    private LinearLayout col;
    private TextView bFmt, bEng, bAdult;

    public SettingsScreen(NHome h) {
        super(h);
        Context c = h;
        scroll = new ScrollView(c);
        scroll.setFillViewport(true);
        col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(UI.dp(c, 60), UI.dp(c, 10), UI.dp(c, 60), UI.dp(c, 20));
        scroll.addView(col, new ScrollView.LayoutParams(-1, -2));

        String due = s.due.isEmpty() ? "" : "   |   Vencimento: " + s.due;
        TextView info = UI.text(c, "Usuário: " + s.uIn + due, 16, UI.MUTED, false);
        info.setPadding(UI.dp(c, 4), 0, 0, UI.dp(c, 12));
        col.addView(info);

        bFmt = row(() -> {
            home.prefs().edit().putString("n_fmt", "hls".equals(home.fmt()) ? "ts" : "hls").apply();
            refresh();
        });
        bEng = row(() -> {
            String e = home.engine();
            String n = "exo".equals(e) ? "vlc" : "exo";
            home.prefs().edit().putString("n_engine", n).apply();
            refresh();
        });
        bAdult = row(() -> {
            home.prefs().edit().putBoolean("n_adult", !home.showAdult()).apply();
            refresh();
            Toast.makeText(home, "Reabra Canais/Filmes/Séries para atualizar as pastas", Toast.LENGTH_SHORT).show();
        });
        row2("Alterar PIN das pastas (padrão 0000)", () -> home.askPin("PIN atual", () -> {
            final android.widget.EditText et = new android.widget.EditText(home);
            et.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
            et.setHint("Novo PIN (4 a 8 dígitos)");
            new android.app.AlertDialog.Builder(home).setTitle("Novo PIN").setView(et).setPositiveButton("Salvar", (d, w) -> {
                String np = et.getText().toString().trim();
                if (np.length() < 4 || np.length() > 8) { Toast.makeText(home, "Use de 4 a 8 dígitos", Toast.LENGTH_SHORT).show(); return; }
                home.prefs().edit().putString("n_pin_code", np).apply();
                Toast.makeText(home, "PIN alterado", Toast.LENGTH_SHORT).show();
            }).setNegativeButton("Cancelar", null).show();
        }));
        row2("Mostrar de novo as pastas ocultas e tirar travas", () -> home.askPin("PIN", () -> {
            home.clearHiddenLocked();
            Toast.makeText(home, "Pronto. Reabra Canais/Filmes/Séries.", Toast.LENGTH_SHORT).show();
        }));
        row2("Usar a versão web", () -> {
            home.prefs().edit().putString("ui_mode", "web").apply();
            Intent i = new Intent(home, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            home.startActivity(i);
            home.finish();
        });
        row2("Sair desta conta", () -> {
            Net.Sess.clear(home);
            Intent i = new Intent(home, NLogin.class);
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            home.startActivity(i);
            home.finish();
        });
        TextView v = UI.text(c, "Versão do app: 2.4 (nativo)", 14, UI.MUTED, false);
        v.setPadding(UI.dp(c, 4), UI.dp(c, 16), 0, 0);
        col.addView(v);
        refresh();
    }

    private TextView row(Runnable r) {
        TextView t = UI.button(home, "", r);
        t.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = UI.dp(home, 8);
        col.addView(t, lp);
        return t;
    }

    private void row2(String label, Runnable r) {
        TextView t = row(r);
        t.setText(label);
    }

    private void refresh() {
        bFmt.setText("Formato do fluxo:  " + ("hls".equals(home.fmt()) ? "HLS (m3u8)" : "TS (recomendado)"));
        String e = home.engine();
        bEng.setText("Player:  " + ("vlc".equals(e) ? "VLC" : "ExoPlayer (recomendado)"));
        bAdult.setText("Pastas adultas:  " + (home.showAdult() ? "mostrar (travadas com PIN)" : "ocultas"));
    }

    @Override public View view() { return scroll; }
    @Override public View firstFocus() { return bFmt; }
}
