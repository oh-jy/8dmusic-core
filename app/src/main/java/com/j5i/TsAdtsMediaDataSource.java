package com.j5i.spatial8d;

import android.media.MediaDataSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Arrays;

/** Removes TS/PES framing and exposes the AAC elementary stream to MediaExtractor. */
public final class TsAdtsMediaDataSource extends MediaDataSource {
    private static final int PACKET = 188;
    private static final int BUFFER = 4 * 1024 * 1024;
    private static final int SNIFF_BUFFER = 16 * 1024;

    private final HlsMediaDataSource hls;
    private final ArrayDeque<byte[]> frames = new ArrayDeque<>();
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream pes = new ByteArrayOutputStream();
    private final byte[] packet = new byte[PACKET];
    private final byte[] carry = new byte[9];

    private long outputPosition;
    private long inputPosition;
    private long ptsUs;
    private int carryLength;
    private int audioPid = -1;
    private int pmtPid = -1;
    private int streamType;
    private boolean closed;
    private boolean sniffing = true;
    private boolean rawAdts;

    public TsAdtsMediaDataSource(String url) {
        hls = new HlsMediaDataSource(url);
    }

    @Override
    public synchronized int readAt(long position, byte[] buffer, int offset, int size)
            throws IOException {
        if (closed) throw new IOException("data source closed");
        if (sniffing && position >= SNIFF_BUFFER) return -1;
        if (position < outputPosition) throw new IOException("TS backward seek unsupported");
        while (outputPosition + out.size() <= position) {
            if (!readFrame()) {
                if (out.size() == 0) return 0;
                break;
            }
        }
        int relative = (int) (position - outputPosition);
        byte[] bytes = out.toByteArray();
        if (relative >= bytes.length) return 0;
        int count = Math.min(size, bytes.length - relative);
        System.arraycopy(bytes, relative, buffer, offset, count);
        if (sniffing && position + count >= SNIFF_BUFFER) sniffing = false;
        return count;
    }

    @Override public synchronized long getSize() { return -1; }

    @Override public synchronized void close() {
        closed = true;
        hls.close();
    }

    private boolean readFrame() throws IOException {
        while (!closed) {
            if (rawAdts) {
                int n = hls.readAt(inputPosition, packet, 0, PACKET);
                if (n < 0) return false;
                if (n == 0) continue;
                inputPosition += n;
                out.write(packet, 0, n);
                compact();
                return true;
            }
            if (!frames.isEmpty()) {
                byte[] frame = frames.removeFirst();
                if (streamType == 0x0f) {
                    // Keep the complete ADTS header, including its optional CRC.
                    out.write(frame);
                } else {
                    out.write(frame);
                }
                compact();
                return true;
            }
            if (!readPacket()) return false;
        }
        return false;
    }

    private boolean readPacket() throws IOException {
        int got = readFully(packet, 0, PACKET);
        if (got < PACKET) return false;
        if (isAdts(packet, got)) {
            rawAdts = true;
            out.write(packet, 0, got);
            return true;
        }
        if ((packet[0] & 0xff) != 0x47) return true;
        boolean start = (packet[1] & 0x40) != 0;
        int pid = ((packet[1] & 0x1f) << 8) | (packet[2] & 0xff);
        int adaptation = (packet[3] >> 4) & 3;
        boolean payload = (adaptation & 1) != 0;
        int index = 4;
        if ((adaptation & 2) != 0) index += 1 + (packet[4] & 0xff);
        if (!payload || index >= PACKET) return true;
        if (pid == 0) {
            int section = index + 1 + (packet[index] & 0xff);
            if (section + 12 < PACKET) {
                pmtPid = ((packet[section + 10] & 0x1f) << 8)
                    | (packet[section + 11] & 0xff);
                android.util.Log.i("8DStream", "TS PAT pmtPid=" + pmtPid);
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
                if (type == 0x0f || type == 0x11 || type == 0x03 || type == 0x04) {
                    streamType = type;
                    audioPid = elementaryPid;
                    android.util.Log.i("8DStream", "TS PMT audioPid=" + elementaryPid
                            + " streamType=" + type);
                }
            }
            return true;
        }
        if (pid != audioPid) return true;
        if (start) {
            flushPes();
            int headerLength = packet[index + 8] & 0xff;
            long pts = readPts(packet, index + 9);
            if (pts >= 0) ptsUs = pts * 1000 / 90;
            index += 9 + headerLength;
        }
        if (index < PACKET) pes.write(packet, index, PACKET - index);
        return true;
    }

    private void flushPes() {
        byte[] data = pes.toByteArray();
        pes.reset();
            if (streamType == 0x0f) {
                splitAdts(data);
                if (!frames.isEmpty()) {
                    android.util.Log.i("8DStream", "TS first AAC frames=" + frames.size()
                            + " bytes=" + frames.peekFirst().length);
                }
            }
        else if (streamType == 0x11 && data.length >= 7) {
            int length = ((data[4] & 3) << 11) | ((data[5] & 0xff) << 3)
                    | ((data[6] & 0xff) >> 5);
            if (length > 0 && 7 + length <= data.length) {
                frames.addLast(Arrays.copyOfRange(data, 7, 7 + length));
            }
        }
    }

    private void splitAdts(byte[] data) {
        int p = 0;
        while (p + 7 <= data.length) {
            if ((data[p] & 0xff) != 0xff || (data[p + 1] & 0xf6) != 0xf0) {
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

    private static long readPts(byte[] p, int i) {
        if (i + 5 > p.length || (p[i] & 1) == 0) return -1;
        return ((long) (p[i] >> 1 & 7) << 30) | ((long) (p[i + 1] & 0xff) << 22)
                | ((long) (p[i + 2] >> 1 & 0x7f) << 15) | ((long) (p[i + 3] & 0xff) << 7)
                | (p[i + 4] >> 1 & 0x7f);
    }

    public static boolean isAdts(byte[] data, int length) {
        return length >= 2 && (data[0] & 0xff) == 0xff && (data[1] & 0xf6) == 0xf0;
    }
}
