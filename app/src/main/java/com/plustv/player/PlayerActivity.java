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
import androidx.media3.exoplayer.ExoPlayer;
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

    private final List<String[]> combos = new ArrayList<>(); // {motor, url}
    private int idx = -1;
    private boolean live;
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
    private ProgressBar bar;
    private View overlay;
    private String curEngine = "";

    private final Handler h = new Handler(Looper.getMainLooper());
    private long pending = -1; // posição escolhida com as setas; só aplica quando para de apertar
    private final Runnable commitSeek = () -> { if (pending >= 0) { seekToMs(pending); pending = -1; } };
    private final Runnable hideOverlay = () -> overlay.setVisibility(View.GONE);
    private final Runnable timeout = () -> { if (!playedOnce && !finished) tryNext(); };
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (finished) return;
            long p = pos(), d = dur();
            if (p > 0) lastPos = p;
            if (d > 0) lastDur = d;
            if (overlay.getVisibility() == View.VISIBLE) {
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
        for (String en : order) for (String u : urls) combos.add(new String[]{en, u});

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
        status.setText("Sintonizando…");
        root.addView(status, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout ov = new LinearLayout(this);
        ov.setOrientation(LinearLayout.VERTICAL);
        ov.setBackgroundColor(Color.parseColor("#B3000000"));
        ov.setPadding(48, 24, 48, 32);
        titleTv = new TextView(this);
        titleTv.setTextColor(Color.WHITE);
        titleTv.setTextSize(22);
        titleTv.setText(title);
        ov.addView(titleTv);
        SeekBar sb = new SeekBar(this);
        bar = sb;
        bar.setMax(1000);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar x, int v, boolean fromUser) {
                if (fromUser && !live) { long d = dur(); if (d > 0) seekToMs(d * v / 1000); showOverlay(); }
            }
            @Override public void onStartTrackingTouch(SeekBar x) { h.removeCallbacks(hideOverlay); }
            @Override public void onStopTrackingTouch(SeekBar x) { showOverlay(); }
        });
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, 64);
        bp.topMargin = 16; bp.bottomMargin = 8;
        ov.addView(bar, bp);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        String[] lb = {"⏪ 10s", "⏯", "10s ⏩", "Proporção"};
        for (int i = 0; i < lb.length; i++) {
            final int idx = i;
            TextView t = new TextView(this);
            t.setText(lb[i]);
            t.setTextColor(Color.WHITE);
            t.setTextSize(20);
            t.setGravity(Gravity.CENTER);
            t.setPadding(36, 20, 36, 20);
            t.setBackgroundColor(Color.parseColor("#33FFFFFF"));
            t.setOnClickListener(v -> {
                if (idx == 0 && !live) seekBy(-10000);
                else if (idx == 1) toggle();
                else if (idx == 2 && !live) seekBy(10000);
                else if (idx == 3) cycleFit();
                showOverlay();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.leftMargin = 12; lp.rightMargin = 12;
            row.addView(t, lp);
        }
        ov.addView(row, new LinearLayout.LayoutParams(-1, -2));
        timeTv = new TextView(this);
        timeTv.setTextColor(Color.parseColor("#DDDDDD"));
        timeTv.setTextSize(18);
        ov.addView(timeTv);
        root.setOnClickListener(v -> { if (overlay.getVisibility() == View.VISIBLE) overlay.setVisibility(View.GONE); else showOverlay(); });
        FrameLayout.LayoutParams op = new FrameLayout.LayoutParams(-1, -2);
        op.gravity = Gravity.BOTTOM;
        root.addView(ov, op);
        overlay = ov;
        overlay.setVisibility(View.GONE);

        h.post(ticker);
        h.post(dotsAnim);
        tryNext();
    }

    // ===== motores =====
    private void tryNext() {
        if (finished) return;
        idx++;
        if (idx >= combos.size()) { finishWith(false, true); return; }
        String[] c = combos.get(idx);
        releaseEngines();
        curEngine = c[0];
        playedOnce = false;
        seekedStart = false;
        status.setText("Sintonizando…");
        status.setVisibility(View.VISIBLE);
        h.removeCallbacks(timeout);
        h.postDelayed(timeout, 25000);
        try {
            if ("exo".equals(c[0])) startExo(c[1]); else startVlc(c[1]);
        } catch (Throwable t) { h.post(this::tryNext); }
    }

    private void onPlaying() {
        if (!playedOnce) h.post(this::showOverlay);
        playedOnce = true;
        h.removeCallbacks(timeout);
        status.setVisibility(View.GONE);
    }

    private void startExo(String url) {
        vlcView.setVisibility(View.GONE);
        exoView.setVisibility(View.VISIBLE);
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
            .setUserAgent(UA).setAllowCrossProtocolRedirects(true).setConnectTimeoutMs(15000).setReadTimeoutMs(20000);
        exo = new ExoPlayer.Builder(this).setMediaSourceFactory(new DefaultMediaSourceFactory(http)).build();
        exoView.setPlayer(exo);
        exo.setMediaItem(MediaItem.fromUri(Uri.parse(url)));
        if (startMs > 0 && !live) exo.seekTo(startMs);
        exo.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int st) {
                if (st == Player.STATE_READY) onPlaying();
                else if (st == Player.STATE_ENDED) {
                    if (live) h.post(PlayerActivity.this::tryNext); else finishWith(true, false);
                }
            }
            @Override public void onPlayerError(PlaybackException e) { h.post(PlayerActivity.this::tryNext); }
        });
        exo.prepare();
        exo.setPlayWhenReady(true);
        applyFit();
    }

    private void startVlc(String url) {
        exoView.setVisibility(View.GONE);
        vlcView.setVisibility(View.VISIBLE);
        ArrayList<String> opts = new ArrayList<>();
        opts.add("--network-caching=2000");
        opts.add("--http-reconnect");
        opts.add("--http-user-agent=" + UA);
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
                case MediaPlayer.Event.Playing:
                    onPlaying();
                    if (startMs > 0 && !live && !seekedStart && vlc != null) { seekedStart = true; vlc.setTime(startMs); }
                    break;
                case MediaPlayer.Event.EncounteredError:
                    h.post(this::tryNext);
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
        h.postDelayed(commitSeek, 800);
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
        try { if (vlc != null) vlc.setVideoScale(fitMode == 0 ? MediaPlayer.ScaleType.SURFACE_BEST_FIT : fitMode == 1 ? MediaPlayer.ScaleType.SURFACE_FIT_SCREEN : MediaPlayer.ScaleType.SURFACE_FILL); } catch (Throwable ignored) {}
    }

    private void cycleFit() {
        fitMode = (fitMode + 1) % 3;
        applyFit();
        status.setText(fitMode == 0 ? "Proporção: Ajustar" : fitMode == 1 ? "Proporção: Preencher" : "Proporção: Esticar");
        status.setVisibility(View.VISIBLE);
        h.removeCallbacks(hideStatus);
        h.postDelayed(hideStatus, 1500);
    }
    private int dots = 0;
    private final Runnable dotsAnim = new Runnable() {
        @Override public void run() {
            if (finished || playedOnce) return;
            dots = (dots + 1) % 4;
            status.setText("Sintonizando" + "...".substring(0, dots));
            h.postDelayed(this, 450);
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
            || k == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD || k == KeyEvent.KEYCODE_MEDIA_REWIND || k == KeyEvent.KEYCODE_SPACE;
        if (!mine) return super.dispatchKeyEvent(ev);
        if (ev.getAction() != KeyEvent.ACTION_DOWN) return true;
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
            case KeyEvent.KEYCODE_DPAD_UP: showOverlay(); return true; // cima: só mostra a barra
            default: // baixo: barra escondida mostra; visível alterna a proporção da imagem
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
        finished = true;
        h.removeCallbacksAndMessages(null);
        releaseEngines();
        super.onDestroy();
    }
}
