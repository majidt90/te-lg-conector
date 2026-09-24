package com.lgmediabridge.core;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** One structured event in the in-app diagnostics log. */
public final class LogEntry {

    public enum Level {
        DEBUG, INFO, WARN, ERROR;

        public String shortName() {
            switch (this) {
                case DEBUG: return "DBG";
                case WARN: return "WRN";
                case ERROR: return "ERR";
                default: return "INF";
            }
        }
    }

    public final long timestamp;
    public final Level level;
    public final String tag;
    public final String message;

    public LogEntry(long timestamp, Level level, String tag, String message) {
        this.timestamp = timestamp;
        this.level = level;
        this.tag = tag;
        this.message = message;
    }

    public boolean isError() {
        return level == Level.ERROR || level == Level.WARN;
    }

    public String time() {
        return new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date(timestamp));
    }

    public String line() {
        return time() + "  " + level.shortName() + "  " + tag + "  " + message;
    }

    @Override public String toString() {
        return line();
    }
}
