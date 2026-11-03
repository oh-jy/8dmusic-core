package com.j5i.spatial8d;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Process;

/** Microphone recorder that writes 16-bit PCM WAV through PcmWavRecorder. */
public final class MicWavRecorder {
    private AudioRecord record;
    private PcmWavRecorder writer;
    private Thread thread;
    private volatile boolean recording;

    public synchronized boolean start(String path, int sampleRate, int channels) {
        if (recording) {
            return false;
        }
        int channelMask = channels == 1
                ? AudioFormat.CHANNEL_IN_MONO
                : AudioFormat.CHANNEL_IN_STEREO;
        int minBuffer = AudioRecord.getMinBufferSize(
                sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuffer <= 0) {
            return false;
        }
        try {
            AudioRecord r = new AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(channelMask)
                            .build())
                    .setBufferSizeInBytes(Math.max(minBuffer * 2, 8192))
                    .build();
            if (r.getState() != AudioRecord.STATE_INITIALIZED) {
                r.release();
                return false;
            }
            PcmWavRecorder w = new PcmWavRecorder();
            if (!w.start(path, sampleRate, channels, 16)) {
                r.release();
                return false;
            }
            record = r;
            writer = w;
            recording = true;
            r.startRecording();
            thread = new Thread(this::readLoop, "Spatial8D-Mic");
            thread.start();
            return true;
        } catch (Throwable ignored) {
            stopInternal();
            return false;
        }
    }

    public synchronized void stop() {
        if (!recording && record == null) {
            return;
        }
        recording = false;
        AudioRecord r = record;
        if (r != null) {
            try {
                r.stop();
            } catch (IllegalStateException ignored) {
            }
        }
        Thread t = thread;
        if (t != null) {
            try {
                t.join(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        stopInternal();
    }

    public boolean isRecording() {
        return recording;
    }

    private void readLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        byte[] buffer = new byte[4096];
        while (recording) {
            int n = record.read(buffer, 0, buffer.length);
            if (n > 0) {
                writer.write(buffer, 0, n);
            } else {
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private void stopInternal() {
        AudioRecord r = record;
        record = null;
        if (r != null) {
            try {
                r.release();
            } catch (Throwable ignored) {
            }
        }
        PcmWavRecorder w = writer;
        writer = null;
        if (w != null) {
            w.stop();
        }
        thread = null;
        recording = false;
    }
}
