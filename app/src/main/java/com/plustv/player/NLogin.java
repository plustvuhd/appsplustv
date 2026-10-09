package com.plustv.player;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Login nativo: o teclado abre direto ao tocar/apertar OK nos campos (EditText do Android). */
public class NLogin extends Activity {
    private EditText fOwner, fUser, fPass;
    private TextView msg, btn;
    private boolean busy = false;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN | WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
        SharedPreferences sp = getSharedPreferences("plustv", MODE_PRIVATE);

        LinearLayout root = new LinearLayout(this);
        root.setBackgroundColor(UI.BG);
        root.setGravity(Gravity.CENTER);
        setContentView(root);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(UI.dp(this, 36), UI.dp(this, 28), UI.dp(this, 36), UI.dp(this, 28));
        card.setBackground(UI.round(UI.CARD, UI.dp(this, 18)));
        root.addView(card, new LinearLayout.LayoutParams(UI.dp(this, 460), -2));

        TextView t = UI.text(this, sp.getString("n_title", "").isEmpty() ? "Entrar" : sp.getString("n_title", ""), 26, UI.TXT, true);
        t.setGravity(Gravity.CENTER);
        card.addView(t, new LinearLayout.LayoutParams(-1, -2));
        TextView sub = UI.text(this, "Digite seu usuário e senha", 15, UI.MUTED, false);
        sub.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2);
        sl.bottomMargin = UI.dp(this, 18);
        card.addView(sub, sl);

        fOwner = UI.field(this, "Código do provedor (se tiver)", false);
        fOwner.setText(sp.getString("n_owner", ""));
        fUser = UI.field(this, "Usuário", false);
        fUser.setText(sp.getString("n_uin", ""));
        fPass = UI.field(this, "Senha", false);
        fPass.setText(sp.getString("n_pin", ""));
        fOwner.setImeOptions(EditorInfo.IME_ACTION_NEXT);
        fUser.setImeOptions(EditorInfo.IME_ACTION_NEXT);
        fPass.setImeOptions(EditorInfo.IME_ACTION_DONE);
        fPass.setOnEditorActionListener((v, a, e) -> { go(); return true; });
        for (EditText e : new EditText[]{fUser, fPass, fOwner}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = UI.dp(this, 10);
            card.addView(e, lp);
        }
        msg = UI.text(this, "", 15, 0xFFFF8A8A, false);
        msg.setGravity(Gravity.CENTER);
        card.addView(msg, new LinearLayout.LayoutParams(-1, -2));
        btn = UI.button(this, "Entrar", this::go);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, -2);
        bl.topMargin = UI.dp(this, 8);
        card.addView(btn, bl);
        TextView web = UI.button(this, "Usar a versão web", () -> {
            sp.edit().putString("ui_mode", "web").apply();
            startActivity(new Intent(this, MainActivity.class));
            finish();
        });
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(-1, -2);
        wl.topMargin = UI.dp(this, 8);
        card.addView(web, wl);
        fUser.requestFocus();
    }

    private void go() {
        if (busy) return;
        final String u = fUser.getText().toString().trim();
        final String p = fPass.getText().toString().trim();
        final String o = fOwner.getText().toString().trim();
        if (u.isEmpty() || p.isEmpty()) { msg.setText("Informe usuário e senha."); return; }
        busy = true;
        btn.setText("Entrando…");
        msg.setText("");
        new Thread(() -> {
            SharedPreferences sp = getSharedPreferences("plustv", MODE_PRIVATE);
            List<String> panels = new ArrayList<>();
            String last = sp.getString("panel", "");
            if (last.startsWith("https://")) panels.add(last);
            if (!panels.contains(MainActivity.PANEL)) panels.add(MainActivity.PANEL);
            String err = "Sem conexão. Confira a internet e tente de novo.";
            Net.Sess found = null;
            boolean discovered = false;
            for (int i = 0; i < panels.size() && found == null; i++) {
                String panel = panels.get(i);
                try {
                    JSONObject body = new JSONObject();
                    body.put("usuario", u); body.put("senha", p); body.put("owner", o);
                    JSONObject r = Net.post(panel + "/api/public/app/login", body, 15000);
                    int code = r.optInt("_code", 0);
                    if (code == 200 && r.optBoolean("ok")) {
                        Net.Sess s = new Net.Sess();
                        s.panel = panel; s.owner = o; s.uIn = u; s.pIn = p;
                        s.user = r.optString("user"); s.pass = r.optString("pass"); s.host = r.optString("host");
                        s.https = r.optBoolean("https"); s.imgKey = r.optString("imgKey"); s.name = r.optString("name");
                        s.title = r.optString("title"); s.due = r.optString("due");
                        found = s;
                    } else if (code > 0) {
                        err = r.optString("error", "Não foi possível entrar (erro " + code + ").");
                        break; // o painel respondeu: o problema é o login, não a conexão
                    }
                } catch (Exception e) { /* tenta o próximo endereço */ }
                if (i == panels.size() - 1 && !discovered && found == null) {
                    discovered = true;
                    try {
                        Matcher m = Pattern.compile("\"panel\"\\s*:\\s*\"([^\"]+)\"").matcher(Net.get(MainActivity.DISCOVERY, false, 6000));
                        if (m.find()) { String f = m.group(1).trim().replaceAll("/+$", ""); if (f.startsWith("https://") && !panels.contains(f)) panels.add(f); }
                    } catch (Exception ignored) {}
                }
            }
            final Net.Sess ok = found;
            final String er = err;
            runOnUiThread(() -> {
                busy = false;
                btn.setText("Entrar");
                if (ok == null) { msg.setText(er); return; }
                ok.save(this);
                sp.edit().putString("panel", ok.panel).putString("ui_mode", "native").apply();
                Intent i = new Intent(this, NHome.class);
                i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(i);
                finish();
            });
        }).start();
    }

    @Override public boolean onKeyDown(int k, KeyEvent e) {
        if (k == KeyEvent.KEYCODE_BACK) { finishAffinity(); return true; }
        return super.onKeyDown(k, e);
    }
}
