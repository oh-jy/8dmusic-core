package com.j5i.spatial8d;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;

/** Reads complete MPEG audio frames so live MP3 bypasses MediaExtractor probing. */
public final class Mp3MediaDataSource extends HttpMediaDataSource {
    private static final int[] RATES = {44100, 48000, 32000};
    private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
    private long position;
    private int sampleRate;
    private int channels;

    public Mp3MediaDataSource(String url) { super(url); }

    public Mp3MediaDataSource(String url, boolean providerSafe, boolean allowHttp) { super(url, providerSafe, allowHttp); }

    public synchronized byte[] nextFrame() throws IOException {
        while (true) {
            byte[] data = pending.toByteArray();
            if (data.length >= 4) {
                int keepFrom = Math.max(0, data.length - 3);
                for (int p = 0; p + 4 <= data.length; p++) {
                    int header = ((data[p] & 255) << 24) | ((data[p + 1] & 255) << 16)
                            | ((data[p + 2] & 255) << 8) | (data[p + 3] & 255);
                    int length = frameLength(header);
                    if (length < 0) continue;
                    int version = (header >>> 19) & 3;
                    sampleRate = RATES[(header >>> 10) & 3] >>> (version == 3 ? 0 : version == 2 ? 1 : 2);
                    channels = ((header >>> 6) & 3) == 3 ? 1 : 2;
                    if (p + length > data.length) {
                        keepFrom = p;
                        break;
                    }
                    pending.reset();
                    pending.write(data, p + length, data.length - p - length);
                    return Arrays.copyOfRange(data, p, p + length);
                }
                if (keepFrom < data.length) {
                    pending.reset();
                    pending.write(data, keepFrom, data.length - keepFrom);
                }
            }
            byte[] buffer = new byte[4096];
            int count = readAt(position, buffer, 0, buffer.length);
            if (count <= 0) return null;
            position += count;
            pending.write(buffer, 0, count);
        }
    }

    public synchronized int getSampleRate() { return sampleRate; }
    public synchronized int getChannels() { return channels; }

    static int frameLength(int header) {
        int version = (header >>> 19) & 3;
        int layer = (header >>> 17) & 3;
        int bitrateIndex = (header >>> 12) & 15;
        int rateIndex = (header >>> 10) & 3;
        if ((header & 0xffe00000) != 0xffe00000 || version == 1 || layer != 1
                || bitrateIndex == 0 || bitrateIndex == 15 || rateIndex == 3) return -1;
        int[] v1 = {0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320};
        int[] v2 = {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160};
        int rate = RATES[rateIndex];
        if (version == 3) return 144000 * v1[bitrateIndex] / rate + ((header >>> 9) & 1);
        rate >>>= version == 2 ? 1 : 2;
        return 72000 * v2[bitrateIndex] / rate + ((header >>> 9) & 1);
    }
}
