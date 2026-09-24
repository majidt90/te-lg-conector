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
     * @return the effective range, or null when the header is absent/invalid or
     *         the range cannot be satisfied for {@code totalLength}.
     */
    public static ByteRange parseBytes(String header, long totalLength) {
        if (header == null || totalLength <= 0) {
            return null;
        }
        String value = header.trim().toLowerCase(Locale.US);
        if (value.startsWith("bytes=")) {
            value = value.substring("bytes=".length());
        }
        int comma = value.indexOf(',');
        if (comma >= 0) {
            // Multi-range requests are legal but no DLNA renderer needs them;
            // serve the first range, which is a valid partial response.
            value = value.substring(0, comma);
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
            return new ByteRange(start, end);
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
     * Parses {@code TimeSeekRange.dlna.org: npt=SS.mmm-EE.mmm} (or {@code npt=SS}).
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
                return new TimeRange(parseSeconds(npt), -1);
            }
            String startText = npt.substring(0, dash).trim();
            String endText = npt.substring(dash + 1).trim();
            long start = startText.isEmpty() ? 0 : parseSeconds(startText);
            long end = endText.isEmpty() ? -1 : parseSeconds(endText);
            return new TimeRange(start, end);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** npt times are seconds with optional milliseconds: {@code 123.456}. */
    private static long parseSeconds(String text) {
        int dot = text.indexOf('.');
        if (dot < 0) {
            return (long) (Double.parseDouble(text) * 1000.0);
        }
        long seconds = Long.parseLong(text.substring(0, dot));
        String fraction = text.substring(dot + 1);
        if (fraction.length() > 3) {
            fraction = fraction.substring(0, 3);
        }
        while (fraction.length() < 3) {
            fraction = fraction + "0";
        }
        return seconds * 1000L + Long.parseLong(fraction);
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
