package com.j5i.spatial8d;

import android.os.Process;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Writes already-rendered stereo PCM to a WAV file on a background thread.
 * The audio thread only copies one small byte[] and offers it to the queue.
 */
public final class PcmWavRecorder {
    private static final byte[] END = new byte[0];

    private final ArrayBlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(256);
    private final AtomicLong droppedBytes = new AtomicLong();

    private volatile boolean recording;
    private volatile long dataBytes;
    private RandomAccessFile file;
    private Thread writer;
    private int sampleRate;
    private int channels;
    private int bitsPerSample;

    public synchronized boolean start(String path, int sampleRate, int channels, int bitsPerSample) {
        if (recording) {
            return false;
        }
        if (sampleRate <= 0 || channels <= 0 || bitsPerSample != 16) {
            return false;
        }
        try {
            File target = new File(path);
            File parent = target.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                return false;
            }
            file = new RandomAccessFile(target, "rw");
            file.setLength(0);
            this.sampleRate = sampleRate;
            this.channels = channels;
            this.bitsPerSample = bitsPerSample;
            dataBytes = 0;
            droppedBytes.set(0);
            writeHeader(0);
            recording = true;
            writer = new Thread(this::writeLoop, "Spatial8D-WavWriter");
            writer.start();
            return true;
        } catch (IOException e) {
            closeQuietly();
            return false;
        }
    }

    public void write(byte[] data, int offset, int length) {
        if (!recording || length <= 0) {
            return;
        }
        byte[] copy = new byte[length];
        System.arraycopy(data, offset, copy, 0, length);
        if (!queue.offer(copy)) {
            droppedBytes.addAndGet(length);
        }
    }

    public synchronized void stop() {
        if (!recording && writer == null) {
            return;
        }
        recording = false;
        try {
            queue.offer(END, 3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Thread t = writer;
        if (t != null) {
            try {
                t.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        writer = null;
        closeQuietly();
    }

    public boolean isRecording() {
        return recording;
    }

    public long getDataBytes() {
        return dataBytes;
    }

    public long getDroppedBytes() {
        return droppedBytes.get();
    }

    private void writeLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        try {
            while (true) {
                byte[] chunk = queue.take();
                if (chunk == END) {
                    break;
                }
                if (chunk.length > 0) {
                    file.write(chunk);
                    dataBytes += chunk.length;
                }
            }
            writeHeader(dataBytes);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
            recording = false;
        }
    }

    private void writeHeader(long pcmBytes) throws IOException {
        if (file == null) {
            return;
        }
        long byteRate = (long) sampleRate * channels * bitsPerSample / 8;
        int blockAlign = channels * bitsPerSample / 8;
        file.seek(0);
        writeAscii("RIFF");
        writeLeInt(36 + pcmBytes);
        writeAscii("WAVE");
        writeAscii("fmt ");
        writeLeInt(16);
        writeLeShort(1);
        writeLeShort(channels);
        writeLeInt(sampleRate);
        writeLeInt(byteRate);
        writeLeShort(blockAlign);
        writeLeShort(bitsPerSample);
        writeAscii("data");
        writeLeInt(pcmBytes);
    }

    private void writeAscii(String value) throws IOException {
        file.writeBytes(value);
    }

    private void writeLeInt(long value) throws IOException {
        file.write((int) (value & 0xff));
        file.write((int) ((value >> 8) & 0xff));
        file.write((int) ((value >> 16) & 0xff));
        file.write((int) ((value >> 24) & 0xff));
    }

    private void writeLeShort(int value) throws IOException {
        file.write(value & 0xff);
        file.write((value >> 8) & 0xff);
    }

    private void closeQuietly() {
        RandomAccessFile f = file;
        file = null;
        if (f != null) {
            try {
                f.close();
            } catch (IOException ignored) {
            }
        }
    }
}
