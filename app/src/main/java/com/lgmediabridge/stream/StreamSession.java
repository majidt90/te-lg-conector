package com.lgmediabridge.stream;

import java.util.concurrent.atomic.AtomicLong;

/**
 * One live transfer: a single HTTP response the TV is currently reading.
 *
 * Metrics are gathered per session so the UI can show what is really happening
 * (who is playing what, how fast, whether seeking is being used) and so
 * diagnostics can answer "why did playback stall" without guesswork.
 */
public final class StreamSession {

    public final String id;
    public final String clientAddress;
    public final String clientLabel;
    public final String mediaId;
    public final String title;
    public final String mimeType;
    public final boolean converted;
    public final long totalBytes;
    public final long startedAt = System.currentTimeMillis();

    private final AtomicLong bytesSent = new AtomicLong();
    private final AtomicLong lastActivity = new AtomicLong(startedAt);
    private final AtomicLong rangeRequests = new AtomicLong();
    private volatile long contentStart;
    private volatile boolean finished;
    private volatile String state = "starting";

    public StreamSession(String id, String clientAddress, String clientLabel, String mediaId,
                         String title, String mimeType, long totalBytes, boolean converted) {
        this.id = id;
        this.clientAddress = clientAddress == null ? "unknown" : clientAddress;
        this.clientLabel = clientLabel == null || clientLabel.isEmpty()
                ? this.clientAddress : clientLabel;
        this.mediaId = mediaId;
        this.title = title == null ? "Unknown media" : title;
        this.mimeType = mimeType == null ? "application/octet-stream" : mimeType;
        this.totalBytes = totalBytes;
        this.converted = converted;
    }

    public void onBytes(long total, int chunk) {
        bytesSent.set(total);
        lastActivity.set(System.currentTimeMillis());
        state = "streaming";
    }

    public void onRangeRequest(long start) {
        contentStart = start;
        rangeRequests.incrementAndGet();
    }

    public void markFinished(int status) {
        finished = true;
        state = status >= 400 ? "failed" : "finished";
        lastActivity.set(System.currentTimeMillis());
    }

    public void setState(String state) {
        this.state = state;
    }

    public String state() {
        return state;
    }

    public long bytesSent() {
        return bytesSent.get();
    }

    public long totalBytes() {
        return totalBytes;
    }

    public long contentStart() {
        return contentStart;
    }

    public long rangeRequests() {
        return rangeRequests.get();
    }

    public boolean isFinished() {
        return finished;
    }

    public long lastActivity() {
        return lastActivity.get();
    }

    public long elapsedMillis() {
        return System.currentTimeMillis() - startedAt;
    }

    public int progressPercent() {
        if (totalBytes <= 0) {
            return 0;
        }
        long sent = bytesSent.get();
        if (sent >= totalBytes) {
            return 100;
        }
        return (int) Math.min(100, sent * 100 / totalBytes);
    }

    public long bytesPerSecond() {
        long elapsed = Math.max(1, elapsedMillis());
        return bytesSent.get() * 1000L / elapsed;
    }

    /** A session with no traffic for a while is stale (TV standby, network drop). */
    public boolean isStale(long idleMillis) {
        return System.currentTimeMillis() - lastActivity.get() > idleMillis;
    }
}
