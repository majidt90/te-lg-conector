package com.lgmediabridge.core;

import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingDeque;

/**
 * Small in-memory event bus for diagnostics.
 *
 * Everything that matters for "why is my TV not playing" ends up here: discovery,
 * pairing, HTTP requests, DLNA actions, conversions, lock state and errors. The
 * buffer is bounded (no growth over long sessions) and every sink is optional, so
 * logging never blocks the streaming path.
 */
public final class LogBus {

    private static final String TAG = "MediaBridge";
    private static final int CAPACITY = 600;

    public interface Listener {
        void onLogEntry(LogEntry entry);
    }

    private static final LogBus INSTANCE = new LogBus();

    public static LogBus get() {
        return INSTANCE;
    }

    private final LinkedBlockingDeque<LogEntry> buffer = new LinkedBlockingDeque<>(CAPACITY);
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private volatile long errorCount;
    private volatile long dropped;

    private LogBus() {
    }

    public void d(String tag, String message) {
        add(LogEntry.Level.DEBUG, tag, message);
    }

    public void i(String tag, String message) {
        add(LogEntry.Level.INFO, tag, message);
    }

    public void w(String tag, String message) {
        add(LogEntry.Level.WARN, tag, message);
    }

    public void e(String tag, String message) {
        add(LogEntry.Level.ERROR, tag, message);
    }

    public void e(String tag, String message, Throwable error) {
        String detail = error == null ? "" :
                " (" + error.getClass().getSimpleName() + ": " + error.getMessage() + ")";
        add(LogEntry.Level.ERROR, tag, message + detail);
    }

    private void add(LogEntry.Level level, String tag, String message) {
        LogEntry entry = new LogEntry(System.currentTimeMillis(), level, tag, message);
        if (!buffer.offerFirst(entry)) {
            buffer.pollLast();
            buffer.offerFirst(entry);
            dropped++;
        }
        if (entry.isError()) {
            errorCount++;
        }
        switch (level) {
            case DEBUG: Log.d(TAG, tag + " | " + message); break;
            case INFO: Log.i(TAG, tag + " | " + message); break;
            case WARN: Log.w(TAG, tag + " | " + message); break;
            default: Log.e(TAG, tag + " | " + message); break;
        }
        for (Listener listener : listeners) {
            try {
                listener.onLogEntry(entry);
            } catch (RuntimeException ignored) {
                // A broken UI listener must never break the server.
            }
        }
    }

    public void addListener(Listener listener) {
        listeners.addIfAbsent(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    /** Newest first. */
    public List<LogEntry> snapshot(boolean errorsOnly, int limit) {
        List<LogEntry> out = new ArrayList<>(Math.min(limit, buffer.size()));
        for (LogEntry entry : buffer) {
            if (errorsOnly && !entry.isError()) {
                continue;
            }
            out.add(entry);
            if (out.size() >= limit) {
                break;
            }
        }
        return out;
    }

    public long errorCount() {
        return errorCount;
    }

    public long droppedCount() {
        return dropped;
    }

    public void clear() {
        buffer.clear();
        errorCount = 0;
        dropped = 0;
    }

    public String exportText() {
        StringBuilder sb = new StringBuilder();
        sb.append("MediaBridge diagnostics export\n");
        sb.append("Exported: ").append(new java.util.Date().toString()).append('\n');
        sb.append("Events: ").append(buffer.size())
          .append("  errors/warnings: ").append(errorCount)
          .append("  dropped: ").append(dropped).append('\n');
        sb.append("----------------------------------------------------------------\n");
        List<LogEntry> entries = new ArrayList<>(buffer);
        for (int i = entries.size() - 1; i >= 0; i--) {
            sb.append(entries.get(i).line()).append('\n');
        }
        return sb.toString();
    }
}
