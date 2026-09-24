package com.lgmediabridge.net;

import java.util.Locale;

/**
 * Byte-range and DLNA time-seek parsing.
 *
 * Seeking on a DLNA renderer works one of two ways:
 *   * {@code Range: bytes=start-end} - byte offsets, the primary mechanism;
 *   * {@code TimeSeekRange.dlna.org: npt=start-end} - time offsets, used by
 *     players that want "jump to 12:34" on a format they cannot index.
 * Both are answered with the correct 206/Content-Range response, which is what
 * makes the seek bar usable on LG TVs (a server that ignores Range is treated as
 * non-seekable by webOS, so the TV hides the seek UI entirely).
 */
public final class HttpRange {

    private HttpRange() {
    }

    public static final class ByteRange {
        public final long start;
        public final long end;

        public ByteRange(long start, long end) {
            this.start = start;
            this.end = end;
        }

        public long length() {
            return end - start + 1;
        }
    }

    /**
     * Parses {@code bytes=start-end} / {@code bytes=start-} / {@code bytes=-suffix}.
     *
     * Multi-range requests ({@code bytes=0-99,200-299}) return null on purpose:
     * answering any single part of a multi-range request with a plain 206 would
     * lie about what was sent. The caller then serves the whole resource, which
     * is a legal answer and the one every renderer handles.
     *
     * @return the effective range, or null when the header is absent, is not a
     *         single byte range, or cannot be satisfied for {@code totalLength}.
     */
    public static ByteRange parseBytes(String header, long totalLength) {
        long[] range = parseSingle(header, totalLength);
        return range == null ? null : new ByteRange(range[0], range[1]);
    }

    /** True when a Range header of any shape was sent. */
    public static boolean hasRangeHeader(String header) {
        return header != null && !header.trim().isEmpty();
    }

    /**
     * True only when the client asked for a syntactically valid single range
     * that lies beyond the end of the resource - the one case that deserves
     * HTTP 416. Malformed or multi-range requests are not "unsatisfiable"; they
     * are answered with the complete resource.
     */
    public static boolean isUnsatisfiable(String header, long totalLength) {
        if (!hasRangeHeader(header) || totalLength <= 0) {
            return false;
        }
        String value = header.trim().toLowerCase(Locale.US);
        if (!value.startsWith("bytes=")) {
            return false;
        }
        value = value.substring("bytes=".length()).trim();
        if (value.isEmpty() || value.indexOf(',') >= 0) {
            return false;
        }
        int dash = value.indexOf('-');
        if (dash < 0) {
            return false;
        }
        String startText = value.substring(0, dash).trim();
        if (startText.isEmpty()) {
            // Suffix requests are always satisfiable with at least one byte.
            return false;
        }
        try {
            return Long.parseLong(startText) >= totalLength;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** Parses one range into a {@code [start, end]} pair, or returns null. */
    private static long[] parseSingle(String header, long totalLength) {
        if (header == null || totalLength <= 0) {
            return null;
        }
        String value = header.trim().toLowerCase(Locale.US);
        if (value.startsWith("bytes=")) {
            value = value.substring("bytes=".length());
        }
        if (value.indexOf(',') >= 0) {
            return null;
        }
        int dash = value.indexOf('-');
        if (dash < 0) {
            return null;
        }
        String startText = value.substring(0, dash).trim();
        String endText = value.substring(dash + 1).trim();
        try {
            long start;
            long end;
            if (startText.isEmpty()) {
                if (endText.isEmpty()) {
                    return null;
                }
                long suffix = Long.parseLong(endText);
                if (suffix <= 0) {
                    return null;
                }
                start = Math.max(0, totalLength - suffix);
                end = totalLength - 1;
            } else {
                start = Long.parseLong(startText);
                end = endText.isEmpty() ? totalLength - 1 : Long.parseLong(endText);
            }
            if (start < 0 || start >= totalLength) {
                return null;
            }
            end = Math.min(end, totalLength - 1);
            if (end < start) {
                return null;
            }
            return new long[]{start, end};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static String contentRange(long start, long end, long total) {
        return "bytes " + start + "-" + end + "/" + total;
    }

    public static final class TimeRange {
        public final long startMillis;
        public final long endMillis; // -1 = until end of file

        public TimeRange(long startMillis, long endMillis) {
            this.startMillis = startMillis;
            this.endMillis = endMillis;
        }
    }

    /**
     * Parses {@code TimeSeekRange.dlna.org: npt=<start>-<end>}.
     *
     * Both notations the specification allows are accepted: seconds
     * ({@code npt=123.456-}) and the clock form ({@code npt=0:02:03.500-}), and
     * a bare {@code npt=<start>} means "from there to the end". An end of
     * {@link #UNSPECIFIED_END} means the client did not bound the range.
     */
    public static TimeRange parseTimeSeek(String header) {
        if (header == null) {
            return null;
        }
        String value = header.trim();
        int equals = value.indexOf('=');
        if (equals < 0) {
            return null;
        }
        String npt = value.substring(equals + 1).trim();
        int dash = npt.indexOf('-');
        try {
            if (dash < 0) {
                return new TimeRange(parseSeconds(npt), UNSPECIFIED_END);
            }
            String startText = npt.substring(0, dash).trim();
            String endText = npt.substring(dash + 1).trim();
            long start = startText.isEmpty() ? 0 : parseSeconds(startText);
            long end = endText.isEmpty() ? UNSPECIFIED_END : parseSeconds(endText);
            return new TimeRange(start, end);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** No end bound was given by the client. */
    public static final long UNSPECIFIED_END = -1L;

    /**
     * npt times, in either notation:
     * {@code 123.456} / {@code 123} (seconds) or {@code 0:02:03.500} (clock).
     */
    private static long parseSeconds(String text) {
        String value = text.trim();
        String[] parts = value.split(":");
        long secondsValue;
        String fraction;
        if (parts.length == 1) {
            int dot = value.indexOf('.');
            secondsValue = Long.parseLong(dot < 0 ? value : value.substring(0, dot));
            fraction = dot < 0 ? "" : value.substring(dot + 1);
        } else {
            // H:MM:SS(.mmm) or MM:SS(.mmm)
            long hours = 0;
            long minutes;
            int last = parts.length - 1;
            int dot = parts[last].indexOf('.');
            long seconds = Long.parseLong(dot < 0 ? parts[last] : parts[last].substring(0, dot));
            fraction = dot < 0 ? "" : parts[last].substring(dot + 1);
            if (parts.length == 3) {
                hours = Long.parseLong(parts[0].trim());
                minutes = Long.parseLong(parts[1].trim());
            } else {
                minutes = Long.parseLong(parts[0].trim());
            }
            if (minutes >= 60 || seconds >= 60) {
                throw new NumberFormatException("invalid npt clock value: " + value);
            }
            secondsValue = hours * 3600 + minutes * 60 + seconds;
        }
        if (fraction.length() > 3) {
            fraction = fraction.substring(0, 3);
        }
        while (fraction.length() < 3) {
            fraction = fraction + "0";
        }
        return secondsValue * 1000L + Long.parseLong(fraction);
    }

    /** Builds the DLNA response header for a time seek answer. */
    public static String timeSeekRange(long startMillis, long endMillis, long totalMillis) {
        StringBuilder sb = new StringBuilder("npt=");
        sb.append(formatSeconds(startMillis));
        sb.append('-').append(formatSeconds(endMillis));
        if (totalMillis > 0) {
            sb.append('/').append(formatSeconds(totalMillis));
        }
        return sb.toString();
    }

    private static String formatSeconds(long millis) {
        if (millis < 0) {
            return "";
        }
        long seconds = millis / 1000;
        long ms = millis % 1000;
        if (ms == 0) {
            return String.valueOf(seconds);
        }
        return String.format(Locale.US, "%d.%03d", seconds, ms);
    }
}
