package com.j5i.spatial8d;

public final class NativeSpatialPlayer {
    static { System.loadLibrary("spatialmp3"); }
    private NativeSpatialPlayer() {}
    /** 每个返回的 handle 对应独立渲染器；同一 handle 的 render/release 必须串行。 */
    public static native long create(double sr, boolean raw, double period, double elev, double depth,
        double phase, double direction, double width, double room, double rt60, double wet, int denoise, int clarity, int trajectory);
    public static native byte[] render(long handle, byte[] data, int bpf, int channels, boolean floating,
        long start, int frames, boolean reverse, long total, long cacheStart, int before);
    /** 直播输入只含当前块；streamPos 单独提供连续的空间运动时间轴。 */
    public static native byte[] renderStream(long handle, byte[] data, int bpf, int channels,
        boolean floating, int frames, long streamPos);
    public static native void release(long handle);
}
