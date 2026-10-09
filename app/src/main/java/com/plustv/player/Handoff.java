package com.plustv.player;

import androidx.media3.exoplayer.ExoPlayer;

/** Passa o ExoPlayer da prévia ao vivo para a tela cheia sem recarregar o canal. */
final class Handoff {
    static ExoPlayer exo;
    static String url;

    static void release() {
        try { if (exo != null) exo.release(); } catch (Throwable ignored) {}
        exo = null;
        url = null;
    }
}
