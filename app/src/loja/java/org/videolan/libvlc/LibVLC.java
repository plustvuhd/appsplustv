package org.videolan.libvlc;

import android.content.Context;
import java.util.ArrayList;

/** Substituto vazio (versão da Google Play): o VLC não vai no app da loja. O código nunca chega aqui, pois a loja usa só ExoPlayer. */
public class LibVLC {
    public LibVLC(Context c, ArrayList<String> options) { throw new UnsupportedOperationException("VLC indisponível nesta versão"); }
    public void release() {}
}
