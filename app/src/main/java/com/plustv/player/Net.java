package com.plustv.player;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import android.widget.ImageView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/** Rede, sessão e carregador de capas do app nativo (TV box / Fire Stick). */
public final class Net {
    private Net() {}

    public static final String UA = "VLC/3.0.20 LibVLC/3.0.20";
    public static final ExecutorService POOL = Executors.newFixedThreadPool(6);

    public static String enc(String s) {
        try { return URLEncoder.encode(s == null ? "" : s, "UTF-8"); } catch (Exception e) { return ""; }
    }

    // ---------- sessão ----------
    public static class Sess {
        public String panel = "", owner = "", uIn = "", pIn = "", user = "", pass = "", host = "", imgKey = "", name = "", title = "", due = "";
        public boolean https;

        public static Sess load(Context c) {
            SharedPreferences sp = c.getSharedPreferences("plustv", Context.MODE_PRIVATE);
            String u = sp.getString("n_user", "");
            if (u.isEmpty() || sp.getString("n_host", "").isEmpty()) return null;
            Sess s = new Sess();
            s.panel = sp.getString("panel", "https://konnex.business");
            s.owner = sp.getString("n_owner", "");
            s.uIn = sp.getString("n_uin", "");
            s.pIn = sp.getString("n_pin", "");
            s.user = u;
            s.pass = sp.getString("n_pass", "");
            s.host = sp.getString("n_host", "");
            s.https = sp.getBoolean("n_https", false);
            s.imgKey = sp.getString("n_img", "");
            s.name = sp.getString("n_name", "");
            s.title = sp.getString("n_title", "");
            s.due = sp.getString("n_due", "");
            return s;
        }

        public void save(Context c) {
            c.getSharedPreferences("plustv", Context.MODE_PRIVATE).edit()
                .putString("n_owner", owner).putString("n_uin", uIn).putString("n_pin", pIn)
                .putString("n_user", user).putString("n_pass", pass).putString("n_host", host)
                .putBoolean("n_https", https).putString("n_img", imgKey).putString("n_name", name)
                .putString("n_title", title).putString("n_due", due).apply();
        }

        public static void clear(Context c) {
            c.getSharedPreferences("plustv", Context.MODE_PRIVATE).edit()
                .remove("n_owner").remove("n_uin").remove("n_pin").remove("n_user").remove("n_pass").remove("n_host")
                .remove("n_https").remove("n_img").remove("n_name").remove("n_title").remove("n_due").apply();
        }

        public String base() { return (https ? "https://" : "http://") + host; }
        public String api(String action, String extra) {
            return base() + "/player_api.php?username=" + enc(user) + "&password=" + enc(pass) + "&action=" + action + (extra == null || extra.isEmpty() ? "" : "&" + extra);
        }
        public String liveUrl(String id, String ext) { return base() + "/live/" + enc(user) + "/" + enc(pass) + "/" + id + "." + ext; }
        public String movieUrl(String id, String ext) { return base() + "/movie/" + enc(user) + "/" + enc(pass) + "/" + id + "." + (ext == null || ext.isEmpty() ? "mp4" : ext); }
        public String seriesUrl(String id, String ext) { return base() + "/series/" + enc(user) + "/" + enc(pass) + "/" + id + "." + (ext == null || ext.isEmpty() ? "mp4" : ext); }
        /** Corpo padrão das rotas do painel que pedem usuário/senha digitados. */
        public JSONObject authBody() {
            JSONObject o = new JSONObject();
            try { o.put("owner", owner); o.put("usuario", uIn); o.put("senha", pIn); } catch (Exception ignored) {}
            return o;
        }
    }

    // ---------- HTTP ----------
    private static SSLSocketFactory laxFactory;
    private static final HostnameVerifier ANY_HOST = (h, sess) -> true;

    private static synchronized SSLSocketFactory lax() throws Exception {
        if (laxFactory == null) {
            TrustManager[] tm = new TrustManager[]{new X509TrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] c, String a) {}
                @Override public void checkServerTrusted(X509Certificate[] c, String a) {}
                @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }};
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, tm, new SecureRandom());
            laxFactory = ctx.getSocketFactory();
        }
        return laxFactory;
    }

    /** lax = servidor IPTV com https de certificado vencido/autoassinado (só para o servidor da lista, nunca para o painel). */
    public static HttpURLConnection open(String url, boolean lax, int ms) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        if (lax && c instanceof HttpsURLConnection) {
            ((HttpsURLConnection) c).setSSLSocketFactory(lax());
            ((HttpsURLConnection) c).setHostnameVerifier(ANY_HOST);
        }
        c.setConnectTimeout(ms);
        c.setReadTimeout(ms);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept", "*/*");
        return c;
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream(32 * 1024);
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toByteArray();
    }

    /** GET que devolve texto. Servidor IPTV (lax=true) tolera certificado inválido. */
    public static String get(String url, boolean lax, int ms) throws Exception {
        HttpURLConnection c = open(url, lax, ms);
        try {
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String body = in == null ? "" : new String(readAll(in), "UTF-8");
            if (code >= 400) throw new Exception("HTTP " + code);
            return body;
        } finally { c.disconnect(); }
    }

    /** Consulta ao servidor IPTV (player_api). Devolve JSONArray ou JSONObject (Object). */
    public static Object iptv(Sess s, String action, String extra, int ms) throws Exception {
        String t = get(s.api(action, extra), s.https, ms).trim();
        if (t.startsWith("[")) return new JSONArray(t);
        return new JSONObject(t);
    }

    /** POST JSON para o painel. */
    public static JSONObject post(String url, JSONObject body, int ms) throws Exception {
        HttpURLConnection c = open(url, false, ms);
        try {
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            byte[] data = body.toString().getBytes("UTF-8");
            OutputStream os = c.getOutputStream();
            os.write(data);
            os.close();
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String txt = in == null ? "{}" : new String(readAll(in), "UTF-8");
            JSONObject j;
            try { j = new JSONObject(txt); } catch (Exception e) { j = new JSONObject(); }
            j.put("_code", code);
            return j;
        } finally { c.disconnect(); }
    }

    // ---------- capas ----------
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(22 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };

    /** Carrega a capa já reduzida pelo painel (rápido em aparelho fraco). Reaproveita a view de forma segura em lista. */
    public static void image(final Sess s, final String url, final ImageView iv, final int w) {
        if (url == null || url.isEmpty() || !url.startsWith("http")) { iv.setTag(null); iv.setImageDrawable(null); return; }
        final String full = s.panel + "/api/public/roku/img?w=" + w + "&k=" + enc(s.imgKey) + "&u=" + enc(url);
        iv.setTag(full);
        Bitmap b = CACHE.get(full);
        if (b != null) { iv.setImageBitmap(b); return; }
        iv.setImageDrawable(null);
        POOL.execute(() -> {
            try {
                HttpURLConnection c = open(full, false, 12000);
                try {
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inPreferredConfig = Bitmap.Config.RGB_565;
                    Bitmap bm = BitmapFactory.decodeStream(c.getInputStream(), null, o);
                    if (bm == null) return;
                    CACHE.put(full, bm);
                    iv.post(() -> { if (full.equals(iv.getTag())) iv.setImageBitmap(bm); });
                } finally { c.disconnect(); }
            } catch (Throwable ignored) {}
        });
    }

    public static String str(JSONObject o, String k) {
        if (o == null || !o.has(k) || o.isNull(k)) return "";
        return String.valueOf(o.opt(k));
    }
}
