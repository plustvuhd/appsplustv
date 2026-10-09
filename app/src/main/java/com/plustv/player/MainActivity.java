package com.plustv.player;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.webkit.JavascriptInterface;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.net.Uri;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.PlaybackException;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    // ===== CONFIGURAÇÃO (edite aqui antes de gerar o APK) =====
    // Endereço padrão do painel (usado se a descoberta e o último endereço salvo falharem).
    static final String PANEL = "https://konnex.business";
    // Arquivo JSON {"panel":"https://..."} num lugar fixo (GitHub raw). Mudou o domínio? Edite só esse arquivo. "" desativa.
    static final String DISCOVERY = "https://raw.githubusercontent.com/plustvuhd/plustv-config/main/plustv.json";
    // (Opcional) link do player de um servidor, ex.: "meu-nome". Vazio = player padrão do painel.
    static final String OWNER = "";
    // ==========================================================

    private FrameLayout root;
    private WebView web;
    private LinearLayout splash;
    private android.widget.ImageView splashImg;
    private LinearLayout err;
    private View customView;
    private WebChromeClient.CustomViewCallback customCb;
    private boolean pageOk = false;
    private boolean nativePlaying = false;
    // Prévia do canal dentro da lista: player nativo desenhado por cima do WebView (o vídeo do WebView ficava preto, só com som, em TV box/Fire Stick)
    private ExoPlayer pvExo;
    private FrameLayout pvView;
    private androidx.media3.ui.AspectRatioFrameLayout pvArf;
    private android.view.TextureView pvTex;
    private String pvKey = "";
    private java.util.List<String> pvUrls = new ArrayList<>();
    private int pvIdx = 0;
    private static final int REQ_PLAY = 77;

    private void showPrompt(String hint, String initial, final String id) {
        try {
            final android.widget.EditText et = new android.widget.EditText(this);
            et.setSingleLine(true);
            et.setText(initial == null ? "" : initial);
            et.setSelection(et.getText().length());
            et.setHint(hint == null ? "" : hint);
            et.setTextSize(22);
            et.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
            et.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
            final boolean[] sent = {false};
            // Sem título nem botões: só a caixa de texto. O teclado abre sozinho, sem precisar clicar de novo; Enter/Buscar confirma.
            final android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setView(et)
                .setOnCancelListener(d -> { if (!sent[0]) { sent[0] = true; promptDone(id, null); } })
                .create();
            et.setOnEditorActionListener((v, a, e) -> { if (!sent[0]) { sent[0] = true; promptDone(id, et.getText().toString()); } dlg.dismiss(); return true; });
            et.setOnKeyListener((v, code, ev) -> {
                if (ev.getAction() == android.view.KeyEvent.ACTION_DOWN && (code == android.view.KeyEvent.KEYCODE_ENTER) && !sent[0]) { sent[0] = true; promptDone(id, et.getText().toString()); dlg.dismiss(); return true; }
                return false;
            });
            dlg.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN);
            dlg.show();
            et.requestFocus();
            final android.view.inputmethod.InputMethodManager im = (android.view.inputmethod.InputMethodManager) getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
            final Runnable force = () -> { try { if (dlg.isShowing()) { et.requestFocus(); if (im != null) im.showSoftInput(et, android.view.inputmethod.InputMethodManager.SHOW_FORCED); } } catch (Throwable ignored) {} };
            et.postDelayed(force, 100);
            et.postDelayed(force, 400);
            et.postDelayed(force, 900);
            // TV box que só abre o teclado com o OK no campo: se ainda não abriu, aperta o OK por nós (uma vez)
            et.postDelayed(() -> {
                try {
                    if (!dlg.isShowing()) return;
                    boolean vis = false;
                    if (android.os.Build.VERSION.SDK_INT >= 30) { android.view.WindowInsets wi = et.getRootWindowInsets(); vis = wi != null && wi.isVisible(android.view.WindowInsets.Type.ime()); }
                    if (!vis) { et.dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_CENTER)); et.dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DPAD_CENTER)); force.run(); }
                } catch (Throwable ignored) {}
            }, 1300);
        } catch (Throwable t) { promptDone(id, null); }
    }

    private void promptDone(String id, String text) {
        final String js = "window.__ptDone&&window.__ptDone(" + org.json.JSONObject.quote(id) + "," + (text == null ? "null" : org.json.JSONObject.quote(text)) + ")";
        web.post(() -> web.evaluateJavascript(js, null));
    }

    private void pvState(String st) {
        final String js = "window.__pvState&&window.__pvState('" + st + "')";
        web.post(() -> web.evaluateJavascript(js, null));
    }

    private Boolean boxCache;
    private boolean detectBox() {
        if (boxCache != null) return boxCache;
        boolean box = false;
        try {
            String man = (android.os.Build.MANUFACTURER + "").toLowerCase(java.util.Locale.ROOT);
            String brand = (android.os.Build.BRAND + "").toLowerCase(java.util.Locale.ROOT);
            String model = (android.os.Build.MODEL + "").toLowerCase(java.util.Locale.ROOT);
            String dev = (android.os.Build.DEVICE + "").toLowerCase(java.util.Locale.ROOT);
            String hw = ((android.os.Build.HARDWARE + " " + android.os.Build.BOARD) + "").toLowerCase(java.util.Locale.ROOT);
            String all = man + " " + brand + " " + model + " " + dev;
            android.app.UiModeManager um = (android.app.UiModeManager) getSystemService(android.content.Context.UI_MODE_SERVICE);
            boolean tv = um != null && um.getCurrentModeType() == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION;
            boolean leanback = getPackageManager().hasSystemFeature("android.software.leanback");
            if (!tv && !leanback) { boxCache = false; return false; } // celular / tablet
            // Fire Stick / Fire TV
            if (all.contains("amazon") || model.startsWith("aft")) box = true;
            // Smart TVs conhecidas (marcas que fabricam televisor): nunca são box
            else if (all.matches(".*(philco|tcl|sony|samsung|lg|hisense|xiaomi|semp|aoc|panasonic|toshiba|sharp|philips|jvc|vizio|sanyo|multilaser tv|britania|ptv|vidaa).*")) box = false;
            else {
                // Nomes e chips típicos de TV box genérica
                if (all.matches(".*(box|x96|mxq|t95|h96|tx3|tx6|tx9|mecool|transpeed|ugoos|beelink|a95|m8s|hk1|magicsee|vontar|q96|h20|s905|s912|rk3|h616|h618|h313).*")
                        || hw.matches(".*(amlogic|meson|rk3|rockchip|sun50|allwinner|s905|s922|gxl|g12).*")) box = true;
                android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(android.content.Context.ACTIVITY_SERVICE);
                if (am != null) {
                    android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
                    am.getMemoryInfo(mi);
                    if (am.isLowRamDevice() || mi.totalMem < 1700L * 1024 * 1024) box = true;
                }
            }
        } catch (Throwable ignored) {}
        boxCache = box;
        return box;
    }

    /** Ponte para o site: o Web Player chama PlusTVNative.play(json) para tocar com ExoPlayer/VLC embutidos. */
    private class Bridge {
        @JavascriptInterface public boolean ok() { return true; }
        /** true = TV box / Fire Stick (aparelho fraco); false = smart TV, celular e tablet. */
        @JavascriptInterface public boolean isBox() { return detectBox(); }
        @JavascriptInterface public String version() { return "2.6"; }
        /** Teclado garantido na TV/box: caixa de texto nativa. O resultado volta para a página em window.__ptDone(id, texto|null). */
        @JavascriptInterface public void promptText(final String hint, final String initial, final String id) {
            runOnUiThread(() -> showPrompt(hint, initial, id));
        }
        @JavascriptInterface public void showKeyboard() {
            runOnUiThread(() -> {
                try {
                    web.requestFocus();
                    final android.view.inputmethod.InputMethodManager im = (android.view.inputmethod.InputMethodManager) getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
                    if (im != null) {
                        im.showSoftInput(web, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
                        // TV box / Fire Stick: o pedido implícito costuma ser ignorado; reforça forçando logo depois
                        web.postDelayed(() -> { try { web.requestFocus(); im.showSoftInput(web, android.view.inputmethod.InputMethodManager.SHOW_FORCED); } catch (Throwable ignored) {} }, 180);
                    }
                } catch (Throwable ignored) {}
            });
        }
        @JavascriptInterface public void previewPlay(final String json, final float l, final float t, final float w, final float h) {
            runOnUiThread(() -> startPreview(json, l, t, w, h));
        }
        @JavascriptInterface public void previewRect(final float l, final float t, final float w, final float h) {
            runOnUiThread(() -> placePreview(l, t, w, h));
        }
        @JavascriptInterface public void previewStop() {
            runOnUiThread(() -> stopPreview());
        }
        @JavascriptInterface public void play(final String json) {
            runOnUiThread(() -> {
                stopPreview();
                Intent i = new Intent(MainActivity.this, PlayerActivity.class);
                i.putExtra("json", json);
                nativePlaying = true;
                startActivityForResult(i, REQ_PLAY);
            });
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PLAY) return;
        nativePlaying = false;
        long pos = data != null ? data.getLongExtra("pos", 0) : 0;
        long dur = data != null ? data.getLongExtra("dur", 0) : 0;
        boolean ended = data != null && data.getBooleanExtra("ended", false);
        boolean err = data == null || data.getBooleanExtra("err", false);
        String fit = data != null && data.getStringExtra("fit") != null ? data.getStringExtra("fit") : "cover";
        web.evaluateJavascript("window.__wpNativeEnd&&window.__wpNativeEnd({pos:" + pos + ",dur:" + dur + ",ended:" + ended + ",err:" + err + ",fit:'" + fit + "'})", null);
        web.requestFocus();
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        // Padrão: versão web do painel (visual clássico ou Netflix, escolhido nas configurações). O player de vídeo continua sendo o ExoPlayer nativo.
        {
            SharedPreferences sp0 = getSharedPreferences("plustv", MODE_PRIVATE);
            String mode = sp0.getString("ui_mode", "");
            if ("native_on".equals(mode)) { // o app nativo fica desligado por padrão: abre a versão web (clássica / Netflix) em todos os aparelhos
                startActivity(new Intent(this, Net.Sess.load(this) != null ? NHome.class : NLogin.class));
                finish();
                return;
            }
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#0b0a14"));
        setContentView(root);

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#0b0a14"));
        web.setVisibility(View.INVISIBLE);
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setUserAgentString(s.getUserAgentString() + " PlusTVApp/1.0");
        web.addJavascriptInterface(new Bridge(), "PlusTVNative");
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView v, String url) {
                if (!pageOk) { pageOk = true; splash.setVisibility(View.GONE); web.setVisibility(View.VISIBLE); web.requestFocus(); }
            }
            @Override public void onReceivedError(WebView v, android.webkit.WebResourceRequest r, android.webkit.WebResourceError e) {
                if (r.isForMainFrame() && !pageOk) showError();
            }
            @Override public boolean shouldOverrideUrlLoading(WebView v, android.webkit.WebResourceRequest r) {
                String u = r.getUrl().toString();
                if (u.startsWith("http://") || u.startsWith("https://")) return false;
                try {
                    Intent it = u.startsWith("intent:") ? Intent.parseUri(u, Intent.URI_INTENT_SCHEME) : new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(u));
                    startActivity(it);
                } catch (Exception ignored) {}
                return true; // nunca deixa o WebView mostrar a tela preta de esquema desconhecido
            }
            // Vídeo http do fornecedor: o app busca direto no servidor deles (pela internet do aparelho, sem passar pelo painel)
            // e entrega ao player da página já com a liberação de CORS. Assim o modo "web" dentro do app também usa só a hospedagem do fornecedor.
            @Override public android.webkit.WebResourceResponse shouldInterceptRequest(WebView v, android.webkit.WebResourceRequest r) {
                try {
                    android.net.Uri u = r.getUrl();
                    if (u == null || !"http".equals(u.getScheme()) || !"GET".equalsIgnoreCase(r.getMethod())) return null;
                    HttpURLConnection c = (HttpURLConnection) new URL(u.toString()).openConnection();
                    c.setInstanceFollowRedirects(true);
                    c.setConnectTimeout(15000);
                    c.setReadTimeout(30000);
                    java.util.Map<String, String> rh = r.getRequestHeaders();
                    if (rh != null) for (java.util.Map.Entry<String, String> e : rh.entrySet()) {
                        String k = e.getKey();
                        if (k == null || k.equalsIgnoreCase("Origin") || k.equalsIgnoreCase("Referer") || k.equalsIgnoreCase("Host")) continue;
                        c.setRequestProperty(k, e.getValue());
                    }
                    int code = c.getResponseCode();
                    if (code < 200 || code > 299) { c.disconnect(); return null; }
                    String ct = c.getContentType();
                    String mime = ct == null ? "application/octet-stream" : ct.split(";")[0].trim();
                    java.util.HashMap<String, String> hs = new java.util.HashMap<>();
                    for (String k : new String[]{"Content-Length", "Content-Range", "Accept-Ranges", "Content-Type"}) {
                        String val = c.getHeaderField(k);
                        if (val != null) hs.put(k, val);
                    }
                    hs.put("Access-Control-Allow-Origin", "*");
                    hs.put("Access-Control-Expose-Headers", "Content-Length, Content-Range, Accept-Ranges, Content-Type");
                    String msg = c.getResponseMessage();
                    return new android.webkit.WebResourceResponse(mime, null, code, msg == null || msg.isEmpty() ? "OK" : msg, hs, c.getInputStream());
                } catch (Throwable t) {
                    return null; // qualquer falha: segue o caminho normal do WebView
                }
            }
            @Override public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail d) { recreate(); return true; }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onShowCustomView(View v, CustomViewCallback cb) {
                if (customView != null) { cb.onCustomViewHidden(); return; }
                customView = v; customCb = cb;
                root.addView(v, new FrameLayout.LayoutParams(-1, -1));
            }
            @Override public void onHideCustomView() { hideCustom(); }
        });

        splash = new LinearLayout(this);
        splash.setOrientation(LinearLayout.VERTICAL);
        splash.setGravity(Gravity.CENTER);
        splashImg = new android.widget.ImageView(this);
        splashImg.setAdjustViewBounds(true);
        splashImg.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        splash.addView(splashImg, new LinearLayout.LayoutParams(360, 360));
        TextView st = new TextView(this);
        st.setText("Carregando...");
        st.setTextColor(Color.parseColor("#cfc8f0"));
        st.setTextSize(24);
        st.setGravity(Gravity.CENTER);
        st.setPadding(0, 24, 0, 0);
        splash.addView(st);
        root.addView(splash, new FrameLayout.LayoutParams(-1, -1));
        // Mostra na hora a logo da última abertura (guardada no aparelho)
        try {
            android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeFile(new java.io.File(getCacheDir(), "logo.png").getAbsolutePath());
            if (bmp != null) splashImg.setImageBitmap(bmp);
        } catch (Exception ignored) {}

        err = new LinearLayout(this);
        err.setOrientation(LinearLayout.VERTICAL);
        err.setGravity(Gravity.CENTER);
        err.setVisibility(View.GONE);
        TextView t = new TextView(this);
        t.setText("Sem conexão\nNão consegui abrir o " + getString(R.string.app_name) + ". Verifique a internet.");
        t.setTextColor(Color.WHITE); t.setTextSize(26); t.setGravity(Gravity.CENTER);
        err.addView(t);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        row.setPadding(0, 40, 0, 0);
        Button retry = new Button(this); retry.setText("Tentar de novo");
        Button exit = new Button(this); exit.setText("Sair");
        retry.setOnClickListener(v -> start());
        exit.setOnClickListener(v -> finish());
        row.addView(retry); row.addView(exit);
        err.addView(row);
        root.addView(err, new FrameLayout.LayoutParams(-1, -1));

        start();
    }

    private void showError() {
        splash.setVisibility(View.GONE);
        web.setVisibility(View.INVISIBLE);
        err.setVisibility(View.VISIBLE);
        err.getChildAt(1).requestFocus();
        ((ViewGroup) err.getChildAt(1)).getChildAt(0).requestFocus();
    }

    private static String clean(String u) {
        if (u == null) return "";
        u = u.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u.startsWith("https://") ? u : "";
    }

    private static String http(String u, int ms) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setConnectTimeout(ms); c.setReadTimeout(ms);
        c.setRequestProperty("Cache-Control", "no-cache");
        c.setRequestProperty("User-Agent", "PlusTVApp/1.0");
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()));
            StringBuilder sb = new StringBuilder(); String l; int n = 0;
            while ((l = r.readLine()) != null && n++ < 200) sb.append(l);
            return sb.toString();
        } finally { c.disconnect(); }
    }

    private boolean alive(String panel) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(panel + "/api/public/player-config" + (OWNER.isEmpty() ? "" : "?owner=" + OWNER)).openConnection();
            c.setConnectTimeout(8000); c.setReadTimeout(8000);
            c.getResponseCode(); // qualquer resposta = servidor no ar
            c.disconnect();
            return true;
        } catch (Exception e) { return false; }
    }

    private void start() {
        pageOk = false;
        err.setVisibility(View.GONE);
        splash.setVisibility(View.VISIBLE);
        new Thread(() -> {
            SharedPreferences sp = getSharedPreferences("plustv", MODE_PRIVATE);
            List<String> list = new ArrayList<>();
            if (!DISCOVERY.isEmpty()) {
                try {
                    Matcher m = Pattern.compile("\"panel\"\\s*:\\s*\"([^\"]+)\"").matcher(http(DISCOVERY, 6000));
                    if (m.find()) { String f = clean(m.group(1)); if (!f.isEmpty()) list.add(f); }
                } catch (Exception ignored) {}
            }
            String last = clean(sp.getString("panel", ""));
            if (!last.isEmpty() && !list.contains(last)) list.add(last);
            String def = clean(PANEL);
            if (!def.isEmpty() && !list.contains(def)) list.add(def);
            for (String p : list) {
                if (alive(p)) {
                    sp.edit().putString("panel", p).apply();
                    fetchLogo(p);
                    final String url = p + "/player" + (OWNER.isEmpty() ? "" : "/" + OWNER) + "?tv=android";
                    runOnUiThread(() -> web.loadUrl(url));
                    return;
                }
            }
            runOnUiThread(this::showError);
        }).start();
    }

    /** Baixa a logo do app instalado (definida no admin do Web Player) e guarda para as próximas aberturas. */
    private void fetchLogo(String panel) {
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(panel + "/api/public/player-icon/512" + (OWNER.isEmpty() ? "" : "?owner=" + OWNER)).openConnection();
                c.setConnectTimeout(6000); c.setReadTimeout(8000);
                final android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeStream(c.getInputStream());
                c.disconnect();
                if (bmp == null) return;
                try (java.io.FileOutputStream fo = new java.io.FileOutputStream(new java.io.File(getCacheDir(), "logo.png"))) {
                    bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, fo);
                }
                runOnUiThread(() -> splashImg.setImageBitmap(bmp));
            } catch (Exception ignored) {}
        }).start();
    }

    private void hideCustom() {
        if (customView == null) return;
        root.removeView(customView);
        customView = null;
        if (customCb != null) customCb.onCustomViewHidden();
        customCb = null;
        web.requestFocus();
    }

    private void sendKey(int code) {
        web.evaluateJavascript("(function(){var e=new KeyboardEvent('keydown',{keyCode:" + code + ",which:" + code + ",bubbles:true,cancelable:true});(document.activeElement||document.body).dispatchEvent(e);})()", null);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent ev) {
        int k = ev.getKeyCode();
        if (ev.getAction() == KeyEvent.ACTION_DOWN && pageOk) {
            if (k == KeyEvent.KEYCODE_BACK) {
                if (customView != null) { hideCustom(); return true; }
                web.evaluateJavascript("(function(){var e=new CustomEvent('wp-back',{detail:{handled:false}});window.dispatchEvent(e);return e.detail.handled})()",
                    v -> { if (!"true".equals(v)) finish(); });
                return true;
            }
            // Teclas de mídia do controle: viram os códigos que o player da web já entende
            if (k == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) { sendKey(417); return true; }
            if (k == KeyEvent.KEYCODE_MEDIA_REWIND) { sendKey(412); return true; }
            if (k == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) { sendKey(10252); return true; }
            if (k == KeyEvent.KEYCODE_MEDIA_PLAY) { sendKey(415); return true; }
            if (k == KeyEvent.KEYCODE_MEDIA_PAUSE) { sendKey(19); return true; }
        } else if (k == KeyEvent.KEYCODE_BACK && ev.getAction() == KeyEvent.ACTION_DOWN) {
            if (err.getVisibility() == View.VISIBLE) { finish(); return true; }
        }
        return super.dispatchKeyEvent(ev);
    }

    private void placePreview(float l, float t, float w, float h) {
        if (pvView == null) return;
        int W = root.getWidth(), H = root.getHeight();
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.max(2, Math.round(w * W)), Math.max(2, Math.round(h * H)));
        lp.leftMargin = Math.round(l * W);
        lp.topMargin = Math.round(t * H);
        pvView.setLayoutParams(lp);
    }

    private void startPreview(String json, float l, float t, float w, float h) {
        try {
            org.json.JSONObject j = new org.json.JSONObject(json);
            org.json.JSONArray a = j.getJSONArray("urls");
            String key = j.optString("key", "");
            if (pvView != null && key.equals(pvKey)) { placePreview(l, t, w, h); return; }
            stopPreview();
            pvKey = key;
            pvUrls = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) pvUrls.add(a.getString(i));
            pvIdx = 0;
            pvView = new FrameLayout(this);
            pvView.setBackgroundColor(Color.BLACK);
            pvView.setFocusable(false);
            pvView.setClickable(false);
            pvArf = new androidx.media3.ui.AspectRatioFrameLayout(this);
            pvArf.setResizeMode(androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT);
            pvTex = new android.view.TextureView(this);
            pvArf.addView(pvTex, new FrameLayout.LayoutParams(-1, -1));
            pvView.addView(pvArf, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
            root.addView(pvView, new FrameLayout.LayoutParams(2, 2));
            placePreview(l, t, w, h);
            pvNext();
        } catch (Throwable ignored) {}
    }

    private void pvNext() {
        if (pvView == null) return;
        if (pvIdx >= pvUrls.size()) return;
        String u = pvUrls.get(pvIdx++);
        try {
            if (pvExo != null) { pvExo.release(); pvExo = null; }
            DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent("VLC/3.0.20 LibVLC/3.0.20").setAllowCrossProtocolRedirects(true).setConnectTimeoutMs(12000).setReadTimeoutMs(15000);
            DefaultLoadControl lc = new DefaultLoadControl.Builder().build();
            pvExo = new ExoPlayer.Builder(this).setMediaSourceFactory(new DefaultMediaSourceFactory(http)).setLoadControl(lc).build();
            pvExo.setVideoTextureView(pvTex);
            MediaItem.Builder pmb = new MediaItem.Builder().setUri(Uri.parse(u));
            String lu = u.toLowerCase();
            if (lu.contains(".m3u8")) pmb.setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8); else if (lu.matches(".*\\.ts(\\?.*)?$")) pmb.setMimeType(androidx.media3.common.MimeTypes.VIDEO_MP2T);
            pvExo.setMediaItem(pmb.build());
            pvExo.addListener(new Player.Listener() {
                @Override public void onPlaybackStateChanged(int st) { if (st == Player.STATE_READY) pvState("ok"); }
                @Override public void onPlayerError(PlaybackException e) { if (pvIdx >= pvUrls.size()) pvState("fail"); runOnUiThread(MainActivity.this::pvNext); }
                @Override public void onVideoSizeChanged(androidx.media3.common.VideoSize vs) { if (pvArf != null && vs.width > 0 && vs.height > 0) pvArf.setAspectRatio(vs.width * vs.pixelWidthHeightRatio / vs.height); }
            });
            pvExo.prepare();
            pvExo.setPlayWhenReady(true);
        } catch (Throwable t) { pvNext(); }
    }

    private void stopPreview() {
        try { if (pvExo != null) pvExo.release(); } catch (Throwable ignored) {}
        pvExo = null;
        try { if (pvView != null) root.removeView(pvView); } catch (Throwable ignored) {}
        pvView = null; pvArf = null; pvTex = null;
        pvKey = "";
    }

    @Override protected void onPause() { super.onPause(); if (web == null) return; stopPreview(); CookieManager.getInstance().flush(); if (!nativePlaying) web.onPause(); }
    @Override protected void onResume() { super.onResume(); if (web != null) web.onResume(); }
    @Override protected void onDestroy() { if (web != null) { stopPreview(); web.destroy(); } super.onDestroy(); }
}
