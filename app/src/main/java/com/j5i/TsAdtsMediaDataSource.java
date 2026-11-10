package com.j5i.spatial8d;

import android.media.MediaDataSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Arrays;

/** Removes TS/PES framing and exposes the AAC elementary stream. */
public final class TsAdtsMediaDataSource extends MediaDataSource {
    private static final int PACKET = 188;
    private static final int BUFFER = 4 * 1024 * 1024;

    private final HlsMediaDataSource hls;
    private final ArrayDeque<byte[]> frames = new ArrayDeque<>();
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream pes = new ByteArrayOutputStream();
    private final ByteArrayOutputStream adts = new ByteArrayOutputStream();
    private final byte[] packet = new byte[PACKET];

    private long outputPosition;
    private long inputPosition;
    private int audioPid = -1;
    private int pmtPid = -1;
    private int streamType;
    private int sampleRate;
    private int channels;
    private boolean closed;
    private boolean rawAdts;

    public TsAdtsMediaDataSource(String url) {
        hls = new HlsMediaDataSource(url);
    }

    public TsAdtsMediaDataSource(String url, boolean providerSafe, boolean allowHttp) {
        hls = new HlsMediaDataSource(url, providerSafe, allowHttp);
    }

    @Override
    public synchronized int readAt(long position, byte[] buffer, int offset, int size)
            throws IOException {
        if (closed) throw new IOException("data source closed");
        if (position < outputPosition) throw new IOException("TS backward seek unsupported");
        long target = position + size - 1L;
        while (outputPosition + out.size() <= target) {
            byte[] frame = nextFrame();
            if (frame == null) break;
            out.write(frame);
            compact();
        }
        int relative = (int) (position - outputPosition);
        byte[] bytes = out.toByteArray();
        if (relative >= bytes.length) return out.size() == 0 ? 0 : -1;
        int count = Math.min(size, bytes.length - relative);
        System.arraycopy(bytes, relative, buffer, offset, count);
        return count;
    }

    @Override public synchronized long getSize() { return -1; }

    @Override public synchronized void close() {
        closed = true;
        hls.close();
    }

    /** Returns one complete ADTS frame for direct MediaCodec decoding. */
    public synchronized byte[] nextFrame() throws IOException {
        while (!closed) {
            if (rawAdts) {
                byte[] frame = takeAdtsFrame();
                if (frame != null) return frame;
            } else if (!frames.isEmpty()) {
                if (streamType != 0x0f) throw new IOException("AAC stream is not ADTS");
                byte[] frame = frames.removeFirst();
                sampleRate = readSampleRate(frame);
                channels = readChannels(frame);
                return frame;
            }
            if (!readPacket()) return null;
        }
        return null;
    }

    public synchronized int getSampleRate() { return sampleRate; }
    public synchronized int getChannels() { return channels; }

    private byte[] takeAdtsFrame() {
        byte[] data = adts.toByteArray();
        if (data.length < 7) return null;
        if (!isAdtsAt(data, 0)) {
            int sync = 1;
            while (sync + 1 < data.length && !isAdtsAt(data, sync)) sync++;
            adts.reset();
            if (sync + 1 < data.length) adts.write(data, sync, data.length - sync);
            return null;
        }
        int length = ((data[3] & 3) << 11) | ((data[4] & 0xff) << 3)
                | ((data[5] >> 5) & 7);
        if (length < 7 || data.length < length) return null;
        sampleRate = readSampleRate(data);
        channels = readChannels(data);
        adts.reset();
        adts.write(data, length, data.length - length);
        return Arrays.copyOf(data, length);
    }

    private boolean readPacket() throws IOException {
        int got;
        if (rawAdts) {
            got = hls.readAt(inputPosition, packet, 0, PACKET);
            if (got <= 0) return false;
            inputPosition += got;
            adts.write(packet, 0, got);
            return true;
        }
        got = readFully(packet, 0, PACKET);
        if (got == 0) return false;
        if (isAdts(packet, got)) {
            rawAdts = true;
            adts.write(packet, 0, got);
            return true;
        }
        if (got < PACKET) return false;
        if ((packet[0] & 0xff) != 0x47) return true;
        boolean start = (packet[1] & 0x40) != 0;
        int pid = ((packet[1] & 0x1f) << 8) | (packet[2] & 0xff);
        int adaptation = (packet[3] >> 4) & 3;
        int index = 4;
        if ((adaptation & 2) != 0) index += 1 + (packet[4] & 0xff);
        if ((adaptation & 1) == 0 || index >= PACKET) return true;
        if (pid == 0) {
            int section = index + 1 + (packet[index] & 0xff);
            if (section + 12 < PACKET) {
                pmtPid = ((packet[section + 10] & 0x1f) << 8)
                        | (packet[section + 11] & 0xff);
            }
            return true;
        }
        if (pid == pmtPid) {
            int section = index + 1 + (packet[index] & 0xff);
            if (section + 12 >= PACKET || (packet[section] & 0xff) != 2) return true;
            int end = section + 3 + (((packet[section + 2] & 0x0f) << 8)
                    | (packet[section + 3] & 0xff));
            int programInfoLength = ((packet[section + 10] & 0x0f) << 8)
                    | (packet[section + 11] & 0xff);
            int p = section + 12 + programInfoLength;
            while (p + 5 <= end) {
                int type = packet[p] & 0xff;
                int elementaryPid = ((packet[p + 1] & 0x1f) << 8) | (packet[p + 2] & 0xff);
                int length = ((packet[p + 3] & 0x0f) << 8) | (packet[p + 4] & 0xff);
                p += 5 + length;
                if (type == 0x0f) {
                    streamType = type;
                    audioPid = elementaryPid;
                }
            }
            return true;
        }
        if (pid != audioPid) return true;
        if (start) {
            if (index + 9 >= PACKET) return true;
            splitAdts(pes.toByteArray());
            pes.reset();
            index += 9 + (packet[index + 8] & 0xff);
        }
        if (index < PACKET) pes.write(packet, index, PACKET - index);
        return true;
    }

    private void splitAdts(byte[] data) {
        int p = 0;
        while (p + 7 <= data.length) {
            if (!isAdtsAt(data, p)) {
                p++;
                continue;
            }
            int length = ((data[p + 3] & 3) << 11) | ((data[p + 4] & 0xff) << 3)
                    | ((data[p + 5] >> 5) & 7);
            if (length < 7 || p + length > data.length) return;
            frames.addLast(Arrays.copyOfRange(data, p, p + length));
            p += length;
        }
    }

    private int readFully(byte[] target, int offset, int length) throws IOException {
        int total = 0;
        while (total < length && !closed) {
            int n = hls.readAt(inputPosition, target, offset + total, length - total);
            if (n < 0) return total;
            if (n == 0) continue;
            inputPosition += n;
            total += n;
        }
        return total;
    }

    private void compact() {
        if (out.size() < BUFFER) return;
        byte[] bytes = out.toByteArray();
        int keep = BUFFER / 2;
        outputPosition += bytes.length - keep;
        out.reset();
        out.write(bytes, bytes.length - keep, keep);
    }

    private static int readSampleRate(byte[] data) {
        int[] rates = {96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050,
                16000, 12000, 11025, 8000, 7350};
        int index = (data[2] & 0xff) >> 2 & 0x0f;
        if (index >= rates.length) throw new IllegalArgumentException("invalid AAC sample rate");
        return rates[index];
    }

    private static int readChannels(byte[] data) {
        int count = ((data[2] & 1) << 2) | ((data[3] >> 6) & 3);
        if (count == 0) throw new IllegalArgumentException("unsupported AAC channel config");
        return count;
    }

    private static boolean isAdtsAt(byte[] data, int offset) {
        return offset + 1 < data.length && (data[offset] & 0xff) == 0xff
                && (data[offset + 1] & 0xf6) == 0xf0;
    }

    public static boolean isAdts(byte[] data, int length) {
        return length >= 2 && isAdtsAt(data, 0);
    }
}
