package org.videolan.libvlc.interfaces;

public interface IMedia {
    class VideoTrack {
        public String codec;
        public int width, height, sarNum, sarDen;
    }
}
