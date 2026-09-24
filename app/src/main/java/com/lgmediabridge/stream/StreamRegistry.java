package com.lgmediabridge.stream;

import com.lgmediabridge.core.LogBus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tracks every active transfer and notifies the UI.
 *
 * Also the single place that answers "is anything playing right now", which the
 * foreground service uses to decide whether to hold a partial wake lock and how
 * to word its notification.
 */
public final class StreamRegistry {

    public interface Listener {
        void onStreamsChanged(List<StreamSession> sessions);
    }

    private static final long STALE_IDLE_MS = 45_000;

    private final Map<String, StreamSession> sessions = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicInteger sequence = new AtomicInteger();

    public StreamSession create(String clientAddress, String clientLabel, String mediaId,
                                String title, String mimeType, long totalBytes, boolean converted) {
        String id = "s" + sequence.incrementAndGet() + "-" + System.currentTimeMillis() % 100000;
        StreamSession session = new StreamSession(id, clientAddress, clientLabel, mediaId,
                title, mimeType, totalBytes, converted);
        sessions.put(id, session);
        LogBus.get().i("Stream", "start " + id + " " + title + " (" + session.clientLabel + ")");
        notifyListeners();
        return session;
    }

    public void finish(StreamSession session, int status) {
        if (session == null) {
            return;
        }
        session.markFinished(status);
        sessions.remove(session.id);
        LogBus.get().i("Stream", "end " + session.id + " " + session.title
                + " · " + session.bytesSent() + " bytes in " + session.elapsedMillis() + " ms");
        notifyListeners();
    }

    public void touch(StreamSession session) {
        sessions.putIfAbsent(session.id, session);
        notifyListeners();
    }

    public StreamSession byId(String id) {
        return sessions.get(id);
    }

    public List<StreamSession> active() {
        return new ArrayList<>(sessions.values());
    }

    /** Sorted newest first, for stable UI ordering. */
    public List<StreamSession> activeSorted() {
        List<StreamSession> list = active();
        Collections.sort(list, (a, b) -> Long.compare(b.startedAt, a.startedAt));
        return list;
    }

    public boolean hasActiveStreams() {
        return !sessions.isEmpty();
    }

    public int activeCount() {
        return sessions.size();
    }

    public long totalBytesPerSecond() {
        long total = 0;
        for (StreamSession session : sessions.values()) {
            total += session.bytesPerSecond();
        }
        return total;
    }

    public void stopAll() {
        for (StreamSession session : new ArrayList<>(sessions.values())) {
            sessions.remove(session.id);
            session.setState("stopped");
        }
        LogBus.get().i("Stream", "all streams stopped by user");
        notifyListeners();
    }

    /** Drops sessions that have not moved any bytes for a while. */
    public void evictStale() {
        boolean changed = false;
        for (StreamSession session : new ArrayList<>(sessions.values())) {
            if (session.isStale(STALE_IDLE_MS)) {
                sessions.remove(session.id);
                session.setState("idle");
                LogBus.get().d("Stream", "evicted idle session " + session.id);
                changed = true;
            }
        }
        if (changed) {
            notifyListeners();
        }
    }

    /** Recently finished transfers, newest first - shown as history in the UI. */
    public List<StreamSession> recentFinished() {
        List<StreamSession> list = new ArrayList<>();
        for (StreamSession session : sessions.values()) {
            if (session.isFinished()) {
                list.add(session);
            }
        }
        Collections.sort(list, (a, b) -> Long.compare(b.lastActivity(), a.lastActivity()));
        if (list.size() > 20) {
            return new ArrayList<>(list.subList(0, 20));
        }
        return list;
    }

    public void clearFinished() {
        boolean changed = false;
        for (StreamSession session : new ArrayList<>(sessions.values())) {
            if (session.isFinished()) {
                sessions.remove(session.id);
                changed = true;
            }
        }
        if (changed) {
            notifyListeners();
        }
    }

    public void addListener(Listener listener) {
        listeners.addIfAbsent(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public void notifyListeners() {
        List<StreamSession> snapshot = activeSorted();
        for (Listener listener : listeners) {
            try {
                listener.onStreamsChanged(snapshot);
            } catch (RuntimeException e) {
                LogBus.get().e("Stream", "listener failed", e);
            }
        }
    }
}
