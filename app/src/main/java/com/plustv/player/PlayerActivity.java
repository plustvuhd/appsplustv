package com.plustv.player;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.SeekParameters;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.VLCVideoLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * Player nativo em tela cheia: ExoPlayer (Media3) e VLC (libVLC) embutidos.
 * "Automático": tenta um e, se falhar, troca sozinho para o outro (e para o próximo formato do link).
 */
public class PlayerActivity extends Activity {
    private static final String UA = "VLC/3.0.20 LibVLC/3.0.20";

    /** Lista de canais para trocar com cima/baixo (app nativo). */
    public static List<Item> zap = null;
    public static int zapIdx = 0;
    private boolean nativeUi = false;
    private String[] engineOrder;
    private Net.Sess nsess;
    private String devId = "";
    private String curTitle = "";
    private final List<String[]> combos = new ArrayList<>(); // {motor, url}
    /** Canais cujo vídeo (ex.: H.265/HEVC FHD) o decodificador do aparelho não aguenta no ExoPlayer: abrem direto no VLC. */
    public static final java.util.Set<String> BAD_EXO = java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
    public static String badKey(String url) { if (url == null) return ""; int q = url.indexOf('?'); String u = q > 0 ? url.substring(0, q) : url; int d = u.lastIndexOf('.'); int sl = u.lastIndexOf('/'); return d > sl ? u.substring(0, d) : u; }
    private final Runnable noVideoCheck = new Runnable() { @Override public void run() {
        if (finished || !"exo".equals(curEngine) || exo == null) return;
        androidx.media3.common.VideoSize vs = exo.getVideoSize();
        if ((vs == null || vs.width <= 0) && exo.getPlaybackState() == Player.STATE_READY && exo.getCurrentTracks().containsType(androidx.media3.common.C.TRACK_TYPE_VIDEO)) codecFallback();
    } };
    private String curUrl = "";
    /** Nível de reforço por canal para vídeo pesado (FHD / H.265): 0 normal, 1 buffer maior + menos filtro, 2 buffer bem maior + decodificação mais leve. */
    public static final java.util.Map<String, Integer> VLC_LEVEL = new java.util.concurrent.ConcurrentHashMap<>();
    private long rebufWinStart = 0; private int rebufCount = 0; private boolean heavyChecked = false; private float lastBuf = 100f;

    /** Reabre o mesmo endereço no VLC com mais reforço (quando o canal é FHD/pesado ou fica engasgando). */
    private void boostVlc(int level, String why) {
        if (finished || !"vlc".equals(curEngine) || idx < 0) return;
        String k = badKey(curUrl);
        Integer cur = VLC_LEVEL.get(k);
        if (cur != null && cur >= level) return;
        VLC_LEVEL.put(k, level);
        status.setText(why);
        status.setVisibility(View.VISIBLE);
        tuning(false);
        final int again = idx - 1;
        h.post(() -> { if (finished) return; idx = again; tryNext(); });
    }

    private void vlcBuffering(float pct) {
        if (!live && !playedOnce) return;
        boolean low = pct < 100f;
        if (low && lastBuf >= 100f && playedOnce) {
            long now = android.os.SystemClock.elapsedRealtime();
            if (now - rebufWinStart > 40000) { rebufWinStart = now; rebufCount = 0; }
            rebufCount++;
            if (rebufCount >= 3) {
                Integer cur = VLC_LEVEL.get(badKey(curUrl));
                rebufCount = 0; rebufWinStart = now;
                boostVlc((cur == null ? 0 : cur) + 1, "Canal pesado: aumentando o buffer…");
            }
        }
        lastBuf = pct;
    }

    /** Depois que o vídeo começa: se for FHD (1080+) ou H.265, já abre reforçado nas próximas vezes e agora. */
    private void checkHeavy() {
        if (heavyChecked || finished || vlc == null) return;
        try {
            org.videolan.libvlc.interfaces.IMedia.VideoTrack vt = vlc.getCurrentVideoTrack();
            if (vt == null) return;
            heavyChecked = true;
            boolean hevc = vt.codec != null && (vt.codec.toLowerCase().contains("hevc") || vt.codec.toLowerCase().contains("h265") || vt.codec.toLowerCase().contains("hvc"));
            if ((vt.height >= 1000 || hevc) && VLC_LEVEL.get(badKey(curUrl)) == null) boostVlc(1, "Canal FHD: ajustando o buffer…");
        } catch (Throwable ignored) { heavyChecked = true; }
    }

    /** Vídeo que o ExoPlayer não decodifica (H.265 sem decodificador de hardware, perfil/nível alto): descarta o ExoPlayer para esse canal e vai para o VLC (decodificador de software). */
    private void codecFallback() {
        if (finished || !"exo".equals(curEngine)) return;
        BAD_EXO.add(badKey(curUrl));
        status.setText("Vídeo H.265: trocando para o VLC…");
        status.setVisibility(View.VISIBLE);
        tuning(false);
        tryNext();
    }

    private int idx = -1;
    private boolean live;
    // "Pular abertura" (séries): janela em ms e para onde pular
    private long introFrom = -1, introTo = -1, introEnd = 0;
    private boolean introDone = false;
    private TextView skipBtn;
    private long startMs;
    private boolean finished = false;
    private boolean playedOnce = false;
    private boolean seekedStart = false;
    private long lastPos = 0, lastDur = 0;

    private ExoPlayer exo;
    private LibVLC libVlc;
    private MediaPlayer vlc;
    private int fitMode = 2; // 0 = ajustar (barras), 1 = preencher (corta), 2 = esticar
    private PlayerView exoView;
    private VLCVideoLayout vlcView;
    private TextView status, titleTv, timeTv;
    private Ring ring;
    /** Círculo de carregamento estilo Netflix (arco vermelho girando). */
    private static class Ring extends View {
        private final android.graphics.Paint pt = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF rc = new android.graphics.RectF();
        private android.animation.ObjectAnimator an;
        Ring(android.content.Context c) {
            super(c);
            pt.setStyle(android.graphics.Paint.Style.STROKE);
            pt.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            setVisibility(View.GONE);
        }
        void spin(boolean on) {
            if (on) {
                setVisibility(View.VISIBLE);
                if (an == null) { an = android.animation.ObjectAnimator.ofFloat(this, "rotation", 0f, 360f); an.setDuration(900); an.setRepeatCount(android.animation.ValueAnimator.INFINITE); an.setInterpolator(new android.view.animation.LinearInterpolator()); }
                if (!an.isRunning()) an.start();
            } else {
                if (an != null) an.cancel();
                setVisibility(View.GONE);
            }
        }
        @Override protected void onDraw(android.graphics.Canvas cv) {
            float w = Math.min(getWidth(), getHeight()), sw = w * 0.1f;
            pt.setStrokeWidth(sw);
            rc.set(sw, sw, w - sw, w - sw);
            pt.setColor(0x33FFFFFF);
            cv.drawArc(rc, 0, 360, false, pt);
            pt.setColor(0xFFE50914);
            cv.drawArc(rc, -90, 100, false, pt);
        }
    }
    private void tuning(boolean on) { if (ring != null) ring.spin(on); }
    private ProgressBar bar;
    private View overlay;
    private String curEngine = "";
    private long bufSince = 0, okMs = 0;
    private int retries = 0; // tentativas de reabrir no mesmo ponto (filme/série) depois de um erro de rede
    private TextView fitBtn;
    private IconView playIcon;
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    /** Ícones desenhados (voltar 10s / play-pause / avançar 10s), sem depender de fonte de emoji da TV. */
    private static class IconView extends View {
        final int kind;
        boolean playing = true;
        final android.graphics.Paint pt = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        IconView(android.content.Context c, int kind) {
            super(c);
            this.kind = kind;
            pt.setColor(Color.WHITE);
            pt.setStyle(android.graphics.Paint.Style.FILL);
            pt.setTextAlign(android.graphics.Paint.Align.CENTER);
            pt.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        }
        void setPlaying(boolean p) { if (p != playing) { playing = p; invalidate(); } }
        @Override protected void onDraw(android.graphics.Canvas cv) {
            float w = getWidth(), h = getHeight(), cx = w / 2, cy = h / 2, u = Math.min(w, h) / 2f;
            android.graphics.Path pa = new android.graphics.Path();
            if (kind == 1) {
                if (playing) {
                    float bw = u * 0.20f, bh = u * 0.46f;
                    cv.drawRoundRect(new android.graphics.RectF(cx - bw * 1.7f, cy - bh, cx - bw * 0.5f, cy + bh), 4, 4, pt);
                    cv.drawRoundRect(new android.graphics.RectF(cx + bw * 0.5f, cy - bh, cx + bw * 1.7f, cy + bh), 4, 4, pt);
                } else {
                    pa.moveTo(cx - u * 0.26f, cy - u * 0.42f);
                    pa.lineTo(cx + u * 0.46f, cy);
                    pa.lineTo(cx - u * 0.26f, cy + u * 0.42f);
                    pa.close();
                    cv.drawPath(pa, pt);
                }
            } else {
                float d = kind == 0 ? -1f : 1f;
                float tipX = cx + d * u * 0.30f, backX = cx - d * u * 0.22f, cyy = cy - u * 0.12f, hh = u * 0.30f;
                pa.moveTo(tipX, cyy);
                pa.lineTo(backX, cyy - hh);
                pa.lineTo(backX, cyy + hh);
                pa.close();
                cv.drawPath(pa, pt);
                pt.setTextSize(u * 0.46f);
                cv.drawText("10", cx, cy + u * 0.58f, pt);
            }
        }
    }

    private final Handler h = new Handler(Looper.getMainLooper());
    private long pending = -1; // posição escolhida com as setas; só aplica quando para de apertar
    private final Runnable commitSeek = () -> { if (pending >= 0) { seekToMs(pending); pending = -1; } };
    private final Runnable hideOverlay = () -> overlay.setVisibility(View.GONE);
    private final Runnable timeout = () -> { if (!playedOnce && !finished) { if (!live && startMs > 0 && retries < 4) onEngineErrorFromStart(); else tryNext(); } };
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (finished) return;
            long p = pos(), d = dur();
            if (p > 0) lastPos = p;
            if (d > 0) lastDur = d;
            updateSkip(p, d);
            try {
                if (exo != null && playedOnce) {
                    int st = exo.getPlaybackState();
                    if (st == Player.STATE_BUFFERING && exo.getPlayWhenReady()) {
                        if (bufSince == 0) bufSince = System.currentTimeMillis();
                        else if (System.currentTimeMillis() - bufSince > (live ? 12000 : 15000)) { bufSince = 0; if (live) { final int again = idx - 1; h.post(() -> { if (!finished) { idx = again; tryNext(); } }); } else onEngineError(); }
                    } else {
                        bufSince = 0;
                        if (st == Player.STATE_READY && exo.getPlayWhenReady()) { okMs += 500; if (okMs > 20000) { retries = 0; okMs = 0; } }
                    }
                }
            } catch (Throwable ignored) {}
            if (overlay.getVisibility() == View.VISIBLE) {
                try { if (playIcon != null) playIcon.setPlaying(exo != null ? exo.getPlayWhenReady() : vlc == null || vlc.isPlaying()); } catch (Throwable ignored) {}
                long sh = pending >= 0 ? pending : p;
                timeTv.setText(live ? "AO VIVO" : fmt(sh) + " / " + fmt(d));
                bar.setProgress(d > 0 ? (int) (sh * 1000 / d) : 0);
            }
            h.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        String title = "";
        String engine = "auto";
        List<String> urls = new ArrayList<>();
        try {
            JSONObject j = new JSONObject(getIntent().getStringExtra("json"));
            JSONArray a = j.getJSONArray("urls");
            for (int i = 0; i < a.length(); i++) urls.add(a.getString(i));
            title = j.optString("title", "");
            engine = j.optString("engine", "auto");
            startMs = j.optLong("startMs", 0);
            live = j.optBoolean("live", false);
            JSONObject io = j.optJSONObject("intro");
            if (io != null && !live) { introFrom = io.optLong("from", 3) * 1000; introTo = io.optLong("to", 150) * 1000; introEnd = io.optLong("end", 90) * 1000; }
            String f = j.optString("fit", "fill");
            fitMode = "contain".equals(f) ? 0 : "fill".equals(f) ? 2 : 1;
        } catch (Exception e) { finishWith(false, true); return; }

        String[] order;
        if ("vlc".equals(engine)) order = new String[]{"vlc", "exo"};
        else if ("exo".equals(engine)) order = new String[]{"exo", "vlc"};
        else {
            // Automático: formatos que o ExoPlayer não toca vão direto para o VLC
            boolean vlcFirst = !urls.isEmpty() && urls.get(0).toLowerCase().matches(".*\\.(avi|wmv|flv|rmvb|mpg|mpeg|divx|vob)(\\?.*)?$");
            order = vlcFirst ? new String[]{"vlc", "exo"} : new String[]{"exo", "vlc"};
        }
        engineOrder = order;
        for (String en : order) for (String u : urls) combos.add(new String[]{en, u});
        curTitle = title;
        nativeUi = getIntent().getBooleanExtra("native_ui", false);
        if (nativeUi) {
            nsess = Net.Sess.load(this);
            devId = android.provider.Settings.Secure.getString(getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
            if (devId == null) devId = "native";
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);

        exoView = new PlayerView(this);
        exoView.setUseController(false);
        exoView.setKeepScreenOn(true);
        root.addView(exoView, new FrameLayout.LayoutParams(-1, -1));
        vlcView = new VLCVideoLayout(this);
        root.addView(vlcView, new FrameLayout.LayoutParams(-1, -1));
        vlcView.setVisibility(View.GONE);

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(24);
        status.setGravity(Gravity.CENTER);
        status.setText("");
        root.addView(status, new FrameLayout.LayoutParams(-1, -1));
        ring = new Ring(this);
        FrameLayout.LayoutParams rlp = new FrameLayout.LayoutParams(dp(56), dp(56));
        rlp.gravity = Gravity.CENTER;
        root.addView(ring, rlp);

        final int ACC = Color.parseColor("#8B5CF6");
        LinearLayout ov = new LinearLayout(this);
        ov.setOrientation(LinearLayout.VERTICAL);
        ov.setBackground(new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x00000000, 0xD9000000, 0xF2000000}));
        ov.setPadding(dp(56), dp(90), dp(56), dp(30));
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        titleTv = new TextView(this);
        titleTv.setTextColor(Color.WHITE);
        titleTv.setTextSize(26);
        titleTv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        titleTv.setSingleLine(true);
        titleTv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleTv.setShadowLayer(6, 0, 2, 0xAA000000);
        titleTv.setText(title);
        top.addView(titleTv, new LinearLayout.LayoutParams(0, -2, 1f));
        timeTv = new TextView(this);
        timeTv.setTextColor(Color.WHITE);
        timeTv.setTextSize(19);
        timeTv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        if (live) {
            timeTv.setPadding(dp(14), dp(5), dp(14), dp(5));
            android.graphics.drawable.GradientDrawable lg = new android.graphics.drawable.GradientDrawable();
            lg.setColor(Color.parseColor("#E5383B"));
            lg.setCornerRadius(dp(20));
            timeTv.setBackground(lg);
        }
        top.addView(timeTv, new LinearLayout.LayoutParams(-2, -2));
        ov.addView(top, new LinearLayout.LayoutParams(-1, -2));
        SeekBar sb = new SeekBar(this);
        bar = sb;
        bar.setMax(1000);
        android.graphics.drawable.GradientDrawable trk = new android.graphics.drawable.GradientDrawable();
        trk.setColor(0x40FFFFFF); trk.setCornerRadius(dp(4)); trk.setSize(1, dp(6));
        android.graphics.drawable.GradientDrawable prg = new android.graphics.drawable.GradientDrawable();
        prg.setColor(ACC); prg.setCornerRadius(dp(4)); prg.setSize(1, dp(6));
        android.graphics.drawable.LayerDrawable ld = new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{trk, new android.graphics.drawable.ClipDrawable(prg, Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL)});
        ld.setId(0, android.R.id.background);
        ld.setId(1, android.R.id.progress);
        sb.setProgressDrawable(ld);
        android.graphics.drawable.GradientDrawable th = new android.graphics.drawable.GradientDrawable();
        th.setShape(android.graphics.drawable.GradientDrawable.OVAL); th.setColor(Color.WHITE); th.setSize(dp(16), dp(16));
        sb.setThumb(th);
        sb.setThumbOffset(0);
        sb.setPadding(dp(8), 0, dp(8), 0);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar x, int v, boolean fromUser) {
                if (fromUser && !live) { long d = dur(); if (d > 0) seekToMs(d * v / 1000); showOverlay(); }
            }
            @Override public void onStartTrackingTouch(SeekBar x) { h.removeCallbacks(hideOverlay); }
            @Override public void onStopTrackingTouch(SeekBar x) { showOverlay(); }
        });
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, dp(22));
        bp.topMargin = dp(14); bp.bottomMargin = dp(10);
        if (live) bar.setVisibility(View.GONE);
        ov.addView(bar, bp);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            View t;
            if (i == 3) {
                TextView pill = new TextView(this);
                pill.setText(fitLabel());
                pill.setTextColor(Color.WHITE);
                pill.setTextSize(17);
                pill.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                pill.setGravity(Gravity.CENTER);
                pill.setPadding(dp(22), dp(10), dp(22), dp(10));
                android.graphics.drawable.GradientDrawable pg = new android.graphics.drawable.GradientDrawable();
                pg.setColor(0x33FFFFFF); pg.setCornerRadius(dp(30));
                pill.setBackground(pg);
                fitBtn = pill;
                t = pill;
            } else {
                IconView iv = new IconView(this, i);
                android.graphics.drawable.GradientDrawable cg = new android.graphics.drawable.GradientDrawable();
                cg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                cg.setColor(i == 1 ? ACC : 0x33FFFFFF);
                iv.setBackground(cg);
                if (i == 1) playIcon = iv;
                if (live && i != 1) iv.setVisibility(View.GONE);
                t = iv;
            }
            t.setOnClickListener(v -> {
                if (idx == 0 && !live) seekBy(-10000);
                else if (idx == 1) toggle();
                else if (idx == 2 && !live) seekBy(10000);
                else if (idx == 3) cycleFit();
                showOverlay();
            });
            int sz = i == 1 ? dp(66) : dp(54);
            LinearLayout.LayoutParams lp = i == 3 ? new LinearLayout.LayoutParams(-2, -2) : new LinearLayout.LayoutParams(sz, sz);
            lp.leftMargin = dp(12); lp.rightMargin = dp(12);
            row.addView(t, lp);
        }
        ov.addView(row, new LinearLayout.LayoutParams(-1, -2));
        root.setOnClickListener(v -> { if (overlay.getVisibility() == View.VISIBLE) overlay.setVisibility(View.GONE); else showOverlay(); });
        FrameLayout.LayoutParams op = new FrameLayout.LayoutParams(-1, -2);
        op.gravity = Gravity.BOTTOM;
        root.addView(ov, op);
        overlay = ov;
        overlay.setVisibility(View.GONE);

        if (introFrom >= 0) {
            skipBtn = new TextView(this);
            skipBtn.setText("Pular abertura  (OK)");
            skipBtn.setTextColor(Color.WHITE);
            skipBtn.setTextSize(18);
            skipBtn.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            skipBtn.setGravity(Gravity.CENTER);
            skipBtn.setPadding(dp(26), dp(14), dp(26), dp(14));
            android.graphics.drawable.GradientDrawable sg = new android.graphics.drawable.GradientDrawable();
            sg.setColor(0xE6000000); sg.setStroke(dp(2), 0xFFFFFFFF); sg.setCornerRadius(dp(10));
            skipBtn.setBackground(sg);
            skipBtn.setVisibility(View.GONE);
            skipBtn.setOnClickListener(v -> skipIntro());
            FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(-2, -2);
            sp.gravity = Gravity.BOTTOM | Gravity.END;
            sp.rightMargin = dp(40); sp.bottomMargin = dp(120);
            root.addView(skipBtn, sp);
        }
        h.post(ticker);
        tuning(true);
        h.post(dotsAnim);
        if (nativeUi) h.postDelayed(beat, 1500);
        tryNext();
    }

    // ===== app nativo: sinal de "assistindo" (aba Ao vivo do gerenciador) e troca de canal =====
    private final Runnable beat = new Runnable() {
        @Override public void run() {
            if (finished) return;
            sendWatch(false);
            h.postDelayed(this, 20000);
        }
    };

    private boolean watchStarted = false;
    private void sendWatch(final boolean stop) { sendWatch(stop, false); }

    /** Sinal de "assistindo" + trava de telas. start=true na 1ª vez; takeover=true desconecta o outro aparelho. */
    private void sendWatch(final boolean stop, final boolean takeover) {
        if (!nativeUi || nsess == null) return;
        final String t = curTitle;
        final boolean start = !watchStarted && !stop;
        if (!stop) watchStarted = true;
        Net.POOL.execute(() -> {
            try {
                org.json.JSONObject b = nsess.authBody();
                b.put("deviceId", devId); b.put("title", t); b.put("stop", stop);
                if (start || takeover) b.put("start", true);
                if (takeover) b.put("takeover", true);
                b.put("label", "App · " + deviceKind());
                org.json.JSONObject r = Net.post(nsess.panel + "/api/public/app/watch", b, 8000);
                if (stop || finished) return;
                if (r.optBoolean("conflict", false)) {
                    final int lim = r.optInt("limit", 1);
                    org.json.JSONArray oa = r.optJSONArray("others");
                    final String who = oa != null && oa.length() > 0 ? oa.optJSONObject(0).optString("label", "outro aparelho") : "outro aparelho";
                    h.post(() -> screenConflict(lim, who));
                } else if (r.optBoolean("kicked", false)) {
                    h.post(() -> { android.widget.Toast.makeText(this, "Outro aparelho assumiu a sua tela.", android.widget.Toast.LENGTH_LONG).show(); finishWith(false, false); });
                } else if (r.optInt("_code", 200) == 403) {
                    h.post(() -> { android.widget.Toast.makeText(this, r.optString("error", "Aparelho bloqueado."), android.widget.Toast.LENGTH_LONG).show(); finishWith(false, false); });
                }
            } catch (Exception ignored) {}
        });
    }

    private String deviceKind() {
        String m = android.os.Build.MANUFACTURER == null ? "" : android.os.Build.MANUFACTURER.toLowerCase();
        if (m.contains("amazon")) return "Fire Stick";
        android.content.res.Configuration cf = getResources().getConfiguration();
        if ((cf.uiMode & android.content.res.Configuration.UI_MODE_TYPE_MASK) == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION) return "TV";
        return Math.min(cf.screenWidthDp, cf.screenHeightDp) >= 600 ? "Tablet" : (getPackageManager().hasSystemFeature("android.hardware.touchscreen") && !getPackageManager().hasSystemFeature("android.software.leanback") ? "Celular" : "TV box");
    }

    private boolean conflictOpen = false;
    private void screenConflict(int limit, String who) {
        if (finished || conflictOpen) return;
        conflictOpen = true;
        try { if (exo != null) exo.setPlayWhenReady(false); else if (vlc != null) vlc.pause(); } catch (Throwable ignored) {}
        new android.app.AlertDialog.Builder(this)
            .setTitle("Limite de telas atingido")
            .setMessage("Seu plano permite " + limit + " tela(s) ao mesmo tempo e " + who + " já está assistindo. Quer desconectar o outro e assistir aqui?")
            .setCancelable(false)
            .setPositiveButton("Assistir aqui", (d, w) -> { conflictOpen = false; sendWatch(false, true); try { if (exo != null) exo.setPlayWhenReady(true); else if (vlc != null) vlc.play(); } catch (Throwable ignored) {} })
            .setNegativeButton("Voltar", (d, w) -> { conflictOpen = false; finishWith(false, false); })
            .show();
    }

    private void zapBy(int delta) {
        if (zap == null || zap.isEmpty() || nsess == null) return;
        zapIdx = ((zapIdx + delta) % zap.size() + zap.size()) % zap.size();
        Item it = zap.get(zapIdx);
        boolean hls = "hls".equals(getSharedPreferences("plustv", MODE_PRIVATE).getString("n_fmt", "ts"));
        String[] u = hls ? new String[]{nsess.liveUrl(it.id, "m3u8"), nsess.liveUrl(it.id, "ts")} : new String[]{nsess.liveUrl(it.id, "ts"), nsess.liveUrl(it.id, "m3u8")};
        combos.clear();
        for (String en : engineOrder) for (String x : u) combos.add(new String[]{en, x});
        idx = -1; retries = 0; bufSince = 0; pending = -1;
        curTitle = it.name;
        titleTv.setText(it.name);
        showOverlay();
        h.removeCallbacks(hideOverlay);
        h.postDelayed(hideOverlay, 2500);
        sendWatch(false);
        tryNext();
    }

    // ===== motores =====
    private void tryNext() {
        if (finished) return;
        idx++;
        if (idx >= combos.size()) { finishWith(false, true); return; }
        String[] c = combos.get(idx);
        while ("exo".equals(c[0]) && BAD_EXO.contains(badKey(c[1])) && idx + 1 < combos.size()) { idx++; c = combos.get(idx); }
        curUrl = c[1];
        releaseEngines();
        curEngine = c[0];
        playedOnce = false;
        seekedStart = false;
        status.setText("");
        tuning(true);
        h.removeCallbacks(dotsAnim);
        h.post(dotsAnim);
        h.removeCallbacks(timeout);
        h.postDelayed(timeout, 25000);
        try {
            if ("exo".equals(c[0])) startExo(c[1]); else startVlc(c[1]);
        } catch (Throwable t) { h.post(this::tryNext); }
    }

    /** Erro depois de já ter tocado (filme/série, ex.: ao avançar o servidor recusa a nova conexão na hora): reabre o MESMO endereço na posição em que estava, antes de trocar de motor/formato. */
    private void onEngineError() {
        if (finished) return;
        if (!live && playedOnce && retries < 4 && idx >= 0 && idx < combos.size()) {
            retries++;
            long at = pending >= 0 ? pending : Math.max(pos(), lastPos);
            pending = -1;
            startMs = at;
            final int again = idx - 1; // tryNext incrementa
            status.setText("");
            tuning(true);
            h.removeCallbacks(timeout);
            h.postDelayed(() -> { if (finished) return; idx = again; tryNext(); }, 900L * retries);
            return;
        }
        tryNext();
    }

    private void onEngineErrorFromStart() { retries++; final int again = idx - 1; h.postDelayed(() -> { if (finished) return; idx = again; tryNext(); }, 600L); }

    private void onPlaying() {
        if (!playedOnce) h.post(this::showOverlay);
        playedOnce = true;
        h.removeCallbacks(timeout);
        status.setVisibility(View.GONE);
        tuning(false);
    }

    private void startExo(String url) {
        vlcView.setVisibility(View.GONE);
        exoView.setVisibility(View.VISIBLE);
        // Prévia ao vivo -> tela cheia: reaproveita o ExoPlayer que já está tocando (sem recarregar nem recomeçar)
        if (live && Handoff.exo != null && url.equals(Handoff.url)) {
            exo = Handoff.exo;
            Handoff.exo = null;
            Handoff.url = null;
            exoView.setPlayer(exo);
            exo.addListener(newExoListener());
            exo.setPlayWhenReady(true);
            if (exo.getPlaybackState() == Player.STATE_READY) onPlaying();
            applyFit();
            return;
        }
        Handoff.release();
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
            .setUserAgent(UA).setAllowCrossProtocolRedirects(true).setConnectTimeoutMs(15000).setReadTimeoutMs(20000);
        // Mesma configuração padrão do ExoPlayer externo (que não trava): carregamento, buffer, extratores e decodificador no padrão da biblioteca.
        DefaultLoadControl lc = new DefaultLoadControl.Builder().build();
        androidx.media3.exoplayer.DefaultRenderersFactory rf = new androidx.media3.exoplayer.DefaultRenderersFactory(this).setEnableDecoderFallback(true);
        exo = new ExoPlayer.Builder(this, rf).setMediaSourceFactory(new DefaultMediaSourceFactory(http)).setLoadControl(lc).build();
        exo.setSeekParameters(SeekParameters.CLOSEST_SYNC); // avançar/voltar vai para o quadro-chave mais perto (bem mais rápido)
        exoView.setPlayer(exo);
        MediaItem.Builder mib = new MediaItem.Builder().setUri(Uri.parse(url));
        String lu = url.toLowerCase();
        if (lu.contains(".m3u8")) mib.setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8); // sem "adivinhar" o formato: abre mais rápido
        exo.setMediaItem(mib.build());
        if (startMs > 0 && !live) exo.seekTo(startMs);
        exo.addListener(newExoListener());
        exo.prepare();
        exo.setPlayWhenReady(true);
        applyFit();
    }

    private Player.Listener newExoListener() {
        return new Player.Listener() {
            @Override public void onPlaybackStateChanged(int st) {
                if (st == Player.STATE_READY) { onPlaying(); applyFit(); }
                else if (st == Player.STATE_ENDED) {
                    if (live) h.post(PlayerActivity.this::tryNext); else finishWith(true, false);
                }
            }
            @Override public void onVideoSizeChanged(androidx.media3.common.VideoSize vs) { applyFit(); }
            @Override public void onTracksChanged(androidx.media3.common.Tracks t) {
                if (t.containsType(androidx.media3.common.C.TRACK_TYPE_VIDEO) && !t.isTypeSupported(androidx.media3.common.C.TRACK_TYPE_VIDEO, false)) { h.post(PlayerActivity.this::codecFallback); return; }
                if (t.containsType(androidx.media3.common.C.TRACK_TYPE_VIDEO)) { h.removeCallbacks(noVideoCheck); h.postDelayed(noVideoCheck, 5000); }
            }
            @Override public void onPlayerError(PlaybackException e) {
                if (e.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED || e.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED || e.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES || e.errorCode == PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED) { codecFallback(); return; }
                if (live && e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW && exo != null) { exo.seekToDefaultPosition(); exo.prepare(); return; }
                onEngineError();
            }
        };
    }

    private void startVlc(String url) {
        exoView.setVisibility(View.GONE);
        vlcView.setVisibility(View.VISIBLE);
        ArrayList<String> opts = new ArrayList<>();
        Integer lv0 = VLC_LEVEL.get(badKey(url));
        int lv = lv0 == null ? 0 : lv0;
        int cache = 1000 + (lv == 1 ? 3500 : lv >= 2 ? 8000 : 0);
        opts.add("--network-caching=" + cache);
        if (live) opts.add("--live-caching=" + cache);
        opts.add("--avcodec-hw=any");
        opts.add("--avcodec-skiploopfilter=" + (lv == 0 ? 0 : 4));
        if (lv >= 2) { opts.add("--avcodec-fast"); opts.add("--avcodec-skip-frame=1"); }
        opts.add("--file-caching=" + cache);
        opts.add("--no-stats");
        opts.add("--no-osd");
        opts.add("--no-sub-autodetect-file");
        opts.add("--http-reconnect");
        opts.add("--http-user-agent=" + UA);
        rebufCount = 0; lastBuf = 100f; heavyChecked = false;
        libVlc = new LibVLC(this, opts);
        vlc = new MediaPlayer(libVlc);
        vlc.attachViews(vlcView, null, false, false);
        Media m = new Media(libVlc, Uri.parse(url));
        m.setHWDecoderEnabled(true, false);
        vlc.setMedia(m);
        m.release();
        applyFit();
        vlc.setEventListener(ev -> {
            switch (ev.type) {
                case MediaPlayer.Event.Buffering:
                    final float bp = ev.getBuffering();
                    h.post(() -> vlcBuffering(bp));
                    break;
                case MediaPlayer.Event.Playing:
                    onPlaying();
                    applyFit();
                    h.postDelayed(this::checkHeavy, 2500);
                    h.postDelayed(this::applyFit, 700);
                    if (startMs > 0 && !live && !seekedStart && vlc != null) { seekedStart = true; vlc.setTime(startMs); }
                    break;
                case MediaPlayer.Event.Vout:
                    applyFit();
                    break;
                case MediaPlayer.Event.EncounteredError:
                    h.post(this::onEngineError);
                    break;
                case MediaPlayer.Event.EndReached:
                    if (live) h.post(this::tryNext); else finishWith(true, false);
                    break;
                default: break;
            }
        });
        vlc.play();
    }

    private void releaseEngines() {
        try { if (exo != null) { exoView.setPlayer(null); exo.release(); } } catch (Throwable ignored) {}
        exo = null;
        try { if (vlc != null) { vlc.setEventListener(null); vlc.stop(); vlc.detachViews(); vlc.release(); } } catch (Throwable ignored) {}
        vlc = null;
        try { if (libVlc != null) libVlc.release(); } catch (Throwable ignored) {}
        libVlc = null;
    }

    private long pos() {
        try {
            if (exo != null) return Math.max(0, exo.getCurrentPosition());
            if (vlc != null) return Math.max(0, vlc.getTime());
        } catch (Throwable ignored) {}
        return 0;
    }

    private long dur() {
        try {
            if (exo != null) { long d = exo.getDuration(); return d > 0 ? d : 0; }
            if (vlc != null) return Math.max(0, vlc.getLength());
        } catch (Throwable ignored) {}
        return 0;
    }

    private void toggle() {
        try {
            if (exo != null) exo.setPlayWhenReady(!exo.getPlayWhenReady());
            else if (vlc != null) { if (vlc.isPlaying()) vlc.pause(); else vlc.play(); }
        } catch (Throwable ignored) {}
    }

    private boolean skipVisible() { return skipBtn != null && skipBtn.getVisibility() == View.VISIBLE; }
    private void updateSkip(long p, long d) {
        if (skipBtn == null) return;
        boolean show = !introDone && !live && d > 300000 && p >= introFrom && p <= introTo;
        if (show != skipVisible()) skipBtn.setVisibility(show ? View.VISIBLE : View.GONE);
    }
    private void skipIntro() {
        introDone = true;
        if (skipBtn != null) skipBtn.setVisibility(View.GONE);
        seekToMs(Math.max(introEnd, pos() + 10000));
    }
    private void seekToMs(long t) {
        try { if (exo != null) exo.seekTo(t); else if (vlc != null) vlc.setTime(t); } catch (Throwable ignored) {}
    }

    private void nudge(long delta) {
        if (live) return;
        long d = dur();
        long base = pending >= 0 ? pending : pos();
        long t = Math.max(0, base + delta);
        if (d > 0) t = Math.min(t, d - 1000);
        pending = t;
        h.removeCallbacks(commitSeek);
        h.postDelayed(commitSeek, 450);
        long p = d > 0 ? t * 1000 / d : 0;
        bar.setProgress((int) p);
        timeTv.setText(fmt(t) + " / " + fmt(d));
    }

    private void seekBy(long delta) {
        if (live) return;
        long d = dur();
        long t = Math.max(0, pos() + delta);
        if (d > 0) t = Math.min(t, d - 1000);
        try {
            if (exo != null) exo.seekTo(t);
            else if (vlc != null) vlc.setTime(t);
        } catch (Throwable ignored) {}
    }

    /** Proporção da imagem: Ajustar / Preencher / Esticar (cima no controle alterna). */
    private void applyFit() {
        try { if (exoView != null) exoView.setResizeMode(fitMode == 0 ? androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT : fitMode == 1 ? androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM : androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL); } catch (Throwable ignored) {}
        try {
            if (vlc != null) {
                int vw = vlcView.getWidth(), vh = vlcView.getHeight();
                vlc.setVideoScale(MediaPlayer.ScaleType.SURFACE_BEST_FIT);
                if (fitMode == 0) {
                    vlc.setAspectRatio(null);
                } else if (fitMode == 2 && vw > 0 && vh > 0) {
                    vlc.setAspectRatio(vw + ":" + vh); // esticar: usa a proporção da própria tela
                } else if (fitMode == 1) {
                    // preencher: aproxima até cobrir a tela toda (corta as sobras), sem deformar
                    vlc.setAspectRatio(null);
                    org.videolan.libvlc.interfaces.IMedia.VideoTrack tr = vlc.getCurrentVideoTrack();
                    if (tr != null && tr.width > 0 && tr.height > 0 && vw > 0 && vh > 0) {
                        float sar = tr.sarDen > 0 && tr.sarNum > 0 ? (float) tr.sarNum / tr.sarDen : 1f;
                        float sc = Math.max(vw / (tr.width * sar), vh / (float) tr.height);
                        vlc.setScale(sc);
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    private String fitLabel() { return fitMode == 0 ? "Proporção: Ajustar" : fitMode == 1 ? "Proporção: Preencher" : "Proporção: Esticar"; }

    private void cycleFit() {
        fitMode = (fitMode + 1) % 3;
        applyFit();
        if (fitBtn != null) fitBtn.setText(fitLabel());
        status.setText(fitLabel());
        status.setVisibility(View.VISIBLE);
        h.removeCallbacks(hideStatus);
        h.postDelayed(hideStatus, 1500);
    }
    private int dots = 0;
    private final Runnable dotsAnim = new Runnable() {
        @Override public void run() {
            if (finished || playedOnce) return;
            // só o círculo girando (sem texto "Sintonizando")
        }
    };
    private final Runnable hideStatus = () -> { if (status != null) status.setVisibility(View.GONE); };

    private void showOverlay() {
        overlay.setVisibility(View.VISIBLE);
        h.removeCallbacks(hideOverlay);
        h.postDelayed(hideOverlay, 10000);
    }

    private static String fmt(long ms) {
        long s = Math.max(0, ms / 1000);
        long hh = s / 3600, mm = (s % 3600) / 60, ss = s % 60;
        return hh > 0 ? String.format("%d:%02d:%02d", hh, mm, ss) : String.format("%d:%02d", mm, ss);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent ev) {
        int k = ev.getKeyCode();
        boolean mine = k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
            || k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT || k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN
            || k == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || k == KeyEvent.KEYCODE_MEDIA_PLAY || k == KeyEvent.KEYCODE_MEDIA_PAUSE
            || k == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD || k == KeyEvent.KEYCODE_MEDIA_REWIND || k == KeyEvent.KEYCODE_SPACE
            || k == KeyEvent.KEYCODE_CHANNEL_UP || k == KeyEvent.KEYCODE_CHANNEL_DOWN;
        if (!mine) return super.dispatchKeyEvent(ev);
        if (ev.getAction() != KeyEvent.ACTION_DOWN) return true;
        if ((k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER) && skipVisible()) { skipIntro(); return true; }
        switch (k) {
            case KeyEvent.KEYCODE_BACK: finishWith(false, false); return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                // Barra escondida: OK só mostra a barra (não pausa). Barra visível: OK pausa/continua.
                if (overlay.getVisibility() != View.VISIBLE) showOverlay(); else { toggle(); showOverlay(); }
                return true;
            case KeyEvent.KEYCODE_SPACE:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: toggle(); showOverlay(); return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                try { if (exo != null) exo.setPlayWhenReady(true); else if (vlc != null) vlc.play(); } catch (Throwable ignored) {}
                showOverlay(); return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                try { if (exo != null) exo.setPlayWhenReady(false); else if (vlc != null) vlc.pause(); } catch (Throwable ignored) {}
                showOverlay(); return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (overlay.getVisibility() != View.VISIBLE) { showOverlay(); return true; }
                nudge(ev.getRepeatCount() > 3 ? -30000 : -10000); showOverlay(); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (overlay.getVisibility() != View.VISIBLE) { showOverlay(); return true; }
                nudge(ev.getRepeatCount() > 3 ? 30000 : 10000); showOverlay(); return true;
            case KeyEvent.KEYCODE_MEDIA_REWIND: nudge(-30000); showOverlay(); return true;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD: nudge(30000); showOverlay(); return true;
            case KeyEvent.KEYCODE_CHANNEL_UP: if (live && zap != null) { zapBy(-1); return true; } return true;
            case KeyEvent.KEYCODE_CHANNEL_DOWN: if (live && zap != null) { zapBy(1); return true; } return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (live && zap != null && overlay.getVisibility() != View.VISIBLE) { zapBy(-1); return true; }
                showOverlay(); return true; // cima: só mostra a barra
            default: // baixo: barra escondida mostra; visível alterna a proporção da imagem
                if (live && zap != null && overlay.getVisibility() != View.VISIBLE) { zapBy(1); return true; }
                if (overlay.getVisibility() != View.VISIBLE) showOverlay(); else { cycleFit(); showOverlay(); }
                return true;
        }
    }

    private void finishWith(boolean ended, boolean err) {
        if (finished) return;
        long p = pos(), d = dur();
        if (p > 0) lastPos = p;
        if (d > 0) lastDur = d;
        finished = true;
        h.removeCallbacksAndMessages(null);
        sendWatch(true);
        Intent r = new Intent();
        r.putExtra("pos", err ? 0 : lastPos);
        r.putExtra("dur", err ? 0 : lastDur);
        r.putExtra("ended", ended);
        r.putExtra("err", err);
        r.putExtra("fit", fitMode == 0 ? "contain" : fitMode == 2 ? "fill" : "cover");
        setResult(RESULT_OK, r);
        releaseEngines();
        finish();
    }

    @Override protected void onPause() {
        super.onPause();
        try { if (exo != null) exo.setPlayWhenReady(false); else if (vlc != null && vlc.isPlaying()) vlc.pause(); } catch (Throwable ignored) {}
    }

    @Override protected void onDestroy() {
        Handoff.release();
        finished = true;
        h.removeCallbacksAndMessages(null);
        releaseEngines();
        super.onDestroy();
    }
}
