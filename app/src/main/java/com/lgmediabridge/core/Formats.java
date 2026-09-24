package com.lgmediabridge.core;

import android.content.Context;

import com.lgmediabridge.R;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Central place for formatting (the UI never builds strings itself) and for the
 * small set of shared thread pools. Streaming I/O has its own unbounded-per-client
 * pool inside the HTTP server; these pools are for control-plane work only.
 */
public final class Formats {

    private Formats() {
    }

    public static String bytes(long value) {
        if (value < 0) {
            return "—";
        }
        if (value < 1024) {
            return value + " B";
        }
        double kb = value / 1024.0;
        if (kb < 1024) {
            return String.format(Locale.US, "%.1f KB", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return String.format(Locale.US, "%.1f MB", mb);
        }
        return String.format(Locale.US, "%.2f GB", mb / 1024.0);
    }

    /** Throughput: always per second, scaled. */
    public static String rate(long bytesPerSecond) {
        if (bytesPerSecond < 0) {
            return "—";
        }
        if (bytesPerSecond < 1024) {
            return bytesPerSecond + " B/s";
        }
        double kb = bytesPerSecond / 1024.0;
        if (kb < 1024) {
            return String.format(Locale.US, "%.0f KB/s", kb);
        }
        return String.format(Locale.US, "%.1f MB/s", kb / 1024.0);
    }

    /** Bits per second, the unit that matters for video bitrates. */
    public static String bitrate(long bitsPerSecond) {
        if (bitsPerSecond <= 0) {
            return "—";
        }
        double mbps = bitsPerSecond / 1_000_000.0;
        if (mbps < 1.0) {
            return String.format(Locale.US, "%.0f kbps", bitsPerSecond / 1000.0);
        }
        return String.format(Locale.US, "%.1f Mbps", mbps);
    }

    public static String duration(long millis) {
        if (millis <= 0) {
            return "—";
        }
        long totalSeconds = millis / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        if (hours > 0) {
            return String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(Locale.US, "%d:%02d", minutes, seconds);
    }

    /** Compact elapsed time for "sharing for …" labels. */
    public static String elapsed(long millis) {
        long seconds = millis / 1000;
        if (seconds < 60) {
            return seconds + "s";
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return minutes + " min";
        }
        long hours = minutes / 60;
        long remMinutes = minutes % 60;
        return hours + " h " + remMinutes + " min";
    }

    /**
     * Relative time, in the user's language resources rather than in code.
     *
     * {@code context} is used for the wording; without one (a log line, for
     * example) a neutral placeholder is returned instead of guessing.
     */
    public static String timeAgo(Context context, long timestamp) {
        Context resources = context == null ? null : context.getApplicationContext();
        if (timestamp <= 0) {
            return resources == null ? "—" : resources.getString(R.string.time_never);
        }
        long delta = System.currentTimeMillis() - timestamp;
        if (resources == null) {
            return elapsed(delta);
        }
        if (delta < 45_000) {
            return resources.getString(R.string.time_just_now);
        }
        if (delta < 3_600_000) {
            return resources.getString(R.string.time_minutes_ago, delta / 60_000);
        }
        if (delta < 86_400_000) {
            return resources.getString(R.string.time_hours_ago, delta / 3_600_000);
        }
        return resources.getString(R.string.time_days_ago, delta / 86_400_000);
    }

    /** Wall-clock time of a log entry, in the phone's own time zone. */
    public static String clock(long timestamp) {
        return new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(timestamp));
    }

    public static String percent(int value) {
        return Math.max(0, Math.min(100, value)) + "%";
    }

    /** Threads: name them so a stack dump is readable. */
    public static ExecutorService newPool(String name, int size) {
        return Executors.newFixedThreadPool(size, factory(name));
    }

    public static ScheduledExecutorService newScheduler(String name, int size) {
        return Executors.newScheduledThreadPool(size, factory(name));
    }

    private static ThreadFactory factory(String name) {
        AtomicInteger counter = new AtomicInteger(1);
        return runnable -> {
            Thread thread = new Thread(runnable, name + "-" + counter.getAndIncrement());
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler((t, e) ->
                    LogBus.get().e("Thread", "uncaught in " + t.getName(), e));
            return thread;
        };
    }

    public static void schedule(ScheduledExecutorService executor, Runnable task, long millis) {
        try {
            executor.schedule(task, millis, TimeUnit.MILLISECONDS);
        } catch (RuntimeException ignored) {
            // executor already shut down
        }
    }

    public static void cancel(Future<?> future) {
        if (future != null) {
            future.cancel(true);
        }
    }

    public static void shutdown(ExecutorService executor) {
        if (executor == null) {
            return;
        }
        executor.shutdownNow();
        try {
            executor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
