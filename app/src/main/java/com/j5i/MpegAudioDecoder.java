package com.j5i.spatial8d;

public final class MpegAudioDecoder {
    static { System.loadLibrary("spatialmp3"); }
    private MpegAudioDecoder() {}
    public static native long create();
    public static native byte[] decode(long handle, byte[] packet, int channels);
    public static native void release(long handle);
}
