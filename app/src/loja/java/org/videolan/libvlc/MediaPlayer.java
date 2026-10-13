package org.videolan.libvlc;

import org.videolan.libvlc.interfaces.IMedia;
import org.videolan.libvlc.util.VLCVideoLayout;

public class MediaPlayer {
    public enum ScaleType { SURFACE_BEST_FIT }

    public static class Event {
        public static final int Buffering = 259;
        public static final int Playing = 260;
        public static final int Vout = 274;
        public static final int EncounteredError = 266;
        public static final int EndReached = 265;
        public final int type;
        public Event(int type) { this.type = type; }
        public float getBuffering() { return 0f; }
    }

    public interface EventListener { void onEvent(Event event); }

    public MediaPlayer(LibVLC lib) { throw new UnsupportedOperationException("VLC indisponível nesta versão"); }
    public void attachViews(VLCVideoLayout layout, Object displayManager, boolean subtitles, boolean textureView) {}
    public void detachViews() {}
    public void setMedia(Media m) {}
    public void setEventListener(EventListener l) {}
    public void play() {}
    public void pause() {}
    public void stop() {}
    public void release() {}
    public boolean isPlaying() { return false; }
    public long getTime() { return 0; }
    public void setTime(long t) {}
    public long getLength() { return 0; }
    public void setVideoScale(ScaleType t) {}
    public void setAspectRatio(String s) {}
    public void setScale(float s) {}
    public IMedia.VideoTrack getCurrentVideoTrack() { return null; }
}
