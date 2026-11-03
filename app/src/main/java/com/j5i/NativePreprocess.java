package com.j5i.spatial8d;

public final class NativePreprocess {
    static { System.loadLibrary("spatialmp3"); }
    private NativePreprocess() {}
    public static native byte[] run(byte[] data, int bpf, int channels, boolean floating, long frames,
        double speed, double semis, boolean tape);
}
