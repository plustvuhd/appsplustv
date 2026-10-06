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
    private static final int REQ_PLAY = 77;

    /** Ponte para o site: o Web Player chama PlusTVNative.play(json) para tocar com ExoPlayer/VLC embutidos. */
    private class Bridge {
        @JavascriptInterface public boolean ok() { return true; }
        @JavascriptInterface public void showKeyboard() {
            runOnUiThread(() -> {
                try {
                    web.requestFocus();
                    android.view.inputmethod.InputMethodManager im = (android.view.inputmethod.InputMethodManager) getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
                    if (im != null) im.showSoftInput(web, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
                } catch (Throwable ignored) {}
            });
        }
        @JavascriptInterface public void play(final String json) {
            runOnUiThread(() -> {
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

    @Override protected void onPause() { super.onPause(); CookieManager.getInstance().flush(); if (!nativePlaying) web.onPause(); }
    @Override protected void onResume() { super.onResume(); web.onResume(); }
    @Override protected void onDestroy() { web.destroy(); super.onDestroy(); }
}
