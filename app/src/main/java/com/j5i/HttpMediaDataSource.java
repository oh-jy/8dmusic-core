package com.j5i.spatial8d;

import android.media.MediaDataSource;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Progressive HTTP source for MediaExtractor.
 *
 * Radio streams are endless, so this keeps a sliding byte cache instead of
 * downloading the whole response. Icecast/Shoutcast metadata is stripped
 * before the bytes reach MediaExtractor.
 */
public class HttpMediaDataSource extends MediaDataSource {
    private static final int CACHE_BYTES = 512 * 1024;
    private static final int CONNECT_TIMEOUT_MS = 2500;
    private static final int READ_TIMEOUT_MS = 5000;

    private final String url;
    private final byte[] cache = new byte[CACHE_BYTES];

    private HttpURLConnection connection;
    private InputStream input;
    private long cacheStart;
    private int cacheLength;
    private int icyMetaInt;
    private int audioRemaining;
    private boolean eof;
    private boolean closed;
    private long contentLength = -1;

    public HttpMediaDataSource(String url) {
        this.url = url;
    }

    @Override
    public synchronized int readAt(long position, byte[] buffer, int offset, int size)
            throws IOException {
        if (closed) {
            throw new IOException("data source closed");
        }
        if (size == 0) {
            return 0;
        }
        if (input == null || position < cacheStart) {
            reopen(position);
        }
        fillUntil(position);
        long relative = position - cacheStart;
        if (relative < 0) {
            reopen(position);
            fillUntil(position);
            relative = position - cacheStart;
        }
        if (relative >= cacheLength) {
            return eof ? -1 : 0;
        }
        int available = cacheLength - (int) relative;
        int count = Math.min(size, available);
        System.arraycopy(cache, (int) relative, buffer, offset, count);
        return count;
    }

    @Override
    public synchronized long getSize() {
        return contentLength;
    }

    @Override
    public synchronized void close() {
        closed = true;
        closeConnection();
    }

    private void reopen() throws IOException {
        reopen(0);
    }

    private void reopen(long position) throws IOException {
        closeConnection();
        cacheStart = position;
        cacheLength = 0;
        eof = false;

        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(CONNECT_TIMEOUT_MS);
        c.setReadTimeout(READ_TIMEOUT_MS);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "8D Spatial Audio/1.0");
        c.setRequestProperty("Accept", "*/*");
        c.setRequestProperty("Icy-MetaData", "1");
        if (position > 0) c.setRequestProperty("Range", "bytes=" + position + "-");
        c.connect();

        int code = c.getResponseCode();
        if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
            c.disconnect();
            throw new IOException("HTTP " + code);
        }

        connection = c;
        input = c.getInputStream();
        if (position == 0) {
            contentLength = c.getHeaderFieldLong("Content-Length", -1);
        } else if (code == HttpURLConnection.HTTP_OK) {
            skipNetwork(position);
            contentLength = -1;
        } else {
            String range = c.getHeaderField("Content-Range");
            if (range != null && range.contains("/")) {
                try { contentLength = Long.parseLong(range.substring(range.lastIndexOf('/') + 1)); }
                catch (NumberFormatException ignored) {}
            }
        }
        icyMetaInt = parsePositiveInt(c.getHeaderField("icy-metaint"));
        audioRemaining = icyMetaInt;
    }

    private void fillUntil(long target) throws IOException {
        while (!eof && cacheStart + cacheLength <= target) {
            if (cacheLength == cache.length) {
                compact();
            }
            int n = readNetwork(cache, cacheLength, cache.length - cacheLength);
            if (n < 0) {
                eof = true;
                return;
            }
            if (n == 0) {
                continue;
            }
            cacheLength += n;
        }
    }

    private int readNetwork(byte[] buffer, int offset, int length) throws IOException {
        if (length <= 0) {
            return 0;
        }
        while (true) {
            if (icyMetaInt > 0) {
                if (audioRemaining <= 0) {
                    int lengthByte = input.read();
                    if (lengthByte < 0) {
                        return -1;
                    }
                    int metadataBytes = lengthByte * 16;
                    if (metadataBytes > 0) {
                        skipFully(metadataBytes);
                    }
                    audioRemaining = icyMetaInt;
                    continue;
                }
                int count = input.read(buffer, offset, Math.min(length, audioRemaining));
                if (count > 0) {
                    audioRemaining -= count;
                }
                return count;
            }
            return input.read(buffer, offset, length);
        }
    }

    private void skipFully(int count) throws IOException {
        byte[] scratch = new byte[Math.min(1024, Math.max(1, count))];
        int left = count;
        while (left > 0) {
            int n = input.read(scratch, 0, Math.min(scratch.length, left));
            if (n < 0) {
                throw new IOException("stream ended in metadata");
            }
            left -= n;
        }
    }

    private void skipNetwork(long count) throws IOException {
        long left = count;
        byte[] scratch = new byte[16384];
        while (left > 0) {
            int n = input.read(scratch, 0, (int) Math.min(scratch.length, left));
            if (n < 0) throw new IOException("stream ended before requested range");
            left -= n;
        }
    }

    private void compact() {
        int keep = cacheLength / 2;
        int shift = cacheLength - keep;
        if (keep > 0) {
            System.arraycopy(cache, shift, cache, 0, keep);
        }
        cacheStart += shift;
        cacheLength = keep;
    }

    private void closeConnection() {
        if (input != null) {
            try {
                input.close();
            } catch (IOException ignored) {
            }
            input = null;
        }
        if (connection != null) {
            connection.disconnect();
            connection = null;
        }
    }

    private static int parsePositiveInt(String value) {
        if (value == null || value.length() == 0) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(value.trim()));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
