package com.j5i.spatial8d;

import java.io.IOException;

/** JNI 签名需与已验证的原生 LAME 编码器保持一致。 */
public final class Mp3Export {
    static {
        System.loadLibrary("mp3lame");
        System.loadLibrary("spatialmp3");
    }

    private Mp3Export() {}

    public static native void nativeEncode(String wavPath, String mp3Path) throws IOException;
}
