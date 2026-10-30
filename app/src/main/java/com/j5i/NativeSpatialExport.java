package com.j5i.spatial8d;

public final class NativeSpatialExport {
    static { System.loadLibrary("spatialmp3"); }
    private NativeSpatialExport() {}
    public static native void render(byte[] data, int bpf, int channels, boolean floating, long frames,
        double sr, boolean reverse, boolean raw, double period, double elev, double depth, double phase,
        double direction, double width, double room, double rt60, double wet, int denoise, int clarity, String path);
}
