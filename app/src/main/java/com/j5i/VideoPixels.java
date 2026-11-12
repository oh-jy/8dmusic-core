package com.j5i.spatial8d;

import java.nio.ByteBuffer;

public final class VideoPixels {
    static { System.loadLibrary("spatialmp3"); }

    private VideoPixels() {}

    public static native void toYuv(int[] pixels, int width, int height,
        ByteBuffer y, int yBase, int yRow, int yPixel,
        ByteBuffer u, int uBase, int uRow, int uPixel,
        ByteBuffer v, int vBase, int vRow, int vPixel);
}
