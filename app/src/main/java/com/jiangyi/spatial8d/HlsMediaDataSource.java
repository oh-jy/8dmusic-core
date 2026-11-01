package com.jiangyi.spatial8d;

import android.media.MediaDataSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Live HLS reader: refreshes media playlists and exposes joined TS segments. */
public final class HlsMediaDataSource extends MediaDataSource {
    private static final int CACHE_BYTES = 2 * 1024 * 1024;
    private static final int CONNECT_TIMEOUT_MS = 2500;
    private static final int READ_TIMEOUT_MS = 5000;

    private final byte[] cache = new byte[CACHE_BYTES];
    private final ArrayDeque<String> pending = new ArrayDeque<>();
    private final LinkedHashSet<String> seen = new LinkedHashSet<>();

    private String playlistUrl;
    private HttpURLConnection connection;
    private byte[] segment;
    private int segmentOffset;
    private long cacheStart;
    private int cacheLength;
    private long targetDurationMs = 5000;
    private boolean started;
    private boolean eof;
    private boolean closed;

    public HlsMediaDataSource(String url) {
        this.playlistUrl = url;
    }

    @Override
    public synchronized int readAt(long position, byte[] buffer, int offset, int size)
            throws IOException {
        if (closed) throw new IOException("data source closed");
        if (size == 0) return 0;
        if (!started || position < cacheStart) restart();
        fillUntil(position);
        long relative = position - cacheStart;
        if (relative < 0 || relative >= cacheLength) return eof ? -1 : 0;
        int count = Math.min(size, cacheLength - (int) relative);
        System.arraycopy(cache, (int) relative, buffer, offset, count);
        return count;
    }

    @Override
    public synchronized long getSize() {
        return -1;
    }

    @Override
    public synchronized void close() {
        closed = true;
        closeConnection();
    }

    private void restart() throws IOException {
        closeConnection();
        cacheStart = 0;
        cacheLength = 0;
        segment = null;
        segmentOffset = 0;
        pending.clear();
        seen.clear();
        eof = false;
        started = true;
    }

    private void fillUntil(long target) throws IOException {
        while (!closed && !eof && cacheStart + cacheLength <= target) {
            if (cacheLength == cache.length) {
                compact();
            }
            if (segment != null && segmentOffset < segment.length) {
                int count = Math.min(segment.length - segmentOffset, cache.length - cacheLength);
                System.arraycopy(segment, segmentOffset, cache, cacheLength, count);
                cacheLength += count;
                segmentOffset += count;
                if (segmentOffset == segment.length) {
                    segment = null;
                    segmentOffset = 0;
                }
                continue;
            }
            loadNextSegment();
        }
    }

    private void loadNextSegment() throws IOException {
        android.util.Log.i("8DStream", "HLS next pending=" + pending.size());
        if (pending.isEmpty()) refreshPlaylist(0);
        String url = pending.pollFirst();
        if (url == null) {
            if (eof) return;
            sleep(targetDurationMs);
            return;
        }
        segment = download(url);
        if (segment.length == 0) throw new IOException("empty HLS segment");
        segmentOffset = 0;
    }

    private void refreshPlaylist(int depth) throws IOException {
        if (depth > 2) throw new IOException("too many HLS playlist redirects");
        String text = new String(download(playlistUrl), StandardCharsets.UTF_8);
        String[] lines = text.replace("\r", "").split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.startsWith("#EXT-X-TARGETDURATION:")) {
                try {
                    targetDurationMs = Math.max(1000,
                            Long.parseLong(line.substring(line.indexOf(':') + 1).trim()) * 1000);
                } catch (NumberFormatException ignored) {
                }
            }
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                for (int j = i + 1; j < lines.length; j++) {
                    String variant = lines[j].trim();
                    if (!variant.isEmpty() && !variant.startsWith("#")) {
                        playlistUrl = resolve(playlistUrl, variant);
                        pending.clear();
                        seen.clear();
                        refreshPlaylist(depth + 1);
                        return;
                    }
                }
            }
        }

        List<String> urls = new ArrayList<>();
        for (String raw : lines) {
            String line = raw.trim();
            if (!line.isEmpty() && !line.startsWith("#")) urls.add(resolve(playlistUrl, line));
        }
        int from = Math.max(0, urls.size() - 2);
        for (int i = from; i < urls.size(); i++) {
            String url = urls.get(i);
            if (seen.add(url)) pending.addLast(url);
        }
        while (seen.size() > 512) seen.remove(seen.iterator().next());
        if (text.contains("#EXT-X-ENDLIST") && pending.isEmpty()) eof = true;
    }

    private byte[] download(String url) throws IOException {
        android.util.Log.i("8DStream", "GET " + url);
        closeConnection();
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        connection = c;
        c.setConnectTimeout(CONNECT_TIMEOUT_MS);
        c.setReadTimeout(READ_TIMEOUT_MS);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "8D Spatial Audio/1.0");
        c.setRequestProperty("Accept", "*/*");
        try {
            int code = c.getResponseCode();
            android.util.Log.i("8DStream", "HTTP " + code + " " + url);
            if (code != HttpURLConnection.HTTP_OK) throw new IOException("HTTP " + code);
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16384];
            while (!closed) {
                int n = in.read(buffer);
                if (n < 0) break;
                out.write(buffer, 0, n);
            }
            in.close();
            byte[] bytes = out.toByteArray();
            android.util.Log.i("8DStream", "bytes=" + bytes.length + " " + url);
            return bytes;
        } finally {
            closeConnection();
        }
    }

    private void compact() {
        int keep = cacheLength / 2;
        int shift = cacheLength - keep;
        if (keep > 0) System.arraycopy(cache, shift, cache, 0, keep);
        cacheStart += shift;
        cacheLength = keep;
    }

    private void closeConnection() {
        if (connection != null) {
            connection.disconnect();
            connection = null;
        }
    }

    private static String resolve(String base, String child) throws IOException {
        try {
            return URI.create(base).resolve(child).toString();
        } catch (IllegalArgumentException e) {
            throw new IOException("invalid HLS URL", e);
        }
    }

    private static void sleep(long ms) throws IOException {
        try {
            Thread.sleep(Math.min(ms, 5000));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("HLS wait interrupted", e);
        }
    }
}
