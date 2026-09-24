package com.lgmediabridge.server;

import com.lgmediabridge.catalog.MediaItem;

import java.util.Locale;

/**
 * The media URL scheme, in one place.
 *
 * {@code ContentDirectory} builds {@code /media/<objectId>/<name>.<ext>} for
 * playable resources and {@code /thumb/<objectId>.jpg} for artwork; the server
 * has to read those back to know which file the television asked for. Both ends
 * living in tested code is what keeps them from drifting apart - a mismatch
 * shows up on the TV as "cannot play this file" for every item in the list.
 */
public final class MediaPath {

    public static final String MEDIA_PREFIX = "/media/";
    public static final String THUMB_PREFIX = "/thumb/";

    private MediaPath() {
    }

    public static boolean isMedia(String path) {
        return path != null && path.startsWith(MEDIA_PREFIX);
    }

    public static boolean isThumbnail(String path) {
        return path != null && path.startsWith(THUMB_PREFIX);
    }

    /**
     * The URL the television is told to fetch for this item - the one string that
     * decides whether playback, seeking and artwork work at all.
     *
     * The extension is cosmetic but not meaningless: media players sniff it, so a
     * converted photo is advertised as {@code .jpg} even though its object id
     * still points at the original {@code .heic} file.
     */
    public static String mediaUrl(String baseUrl, MediaItem item, String mime) {
        return baseUrl + MEDIA_PREFIX + item.objectId() + "/"
                + urlEncode(safeName(item.title)) + "." + extensionFor(mime, item);
    }

    /** Artwork for an item; photos are their own artwork, so they have none. */
    public static String thumbnailUrl(String baseUrl, MediaItem item) {
        return baseUrl + THUMB_PREFIX + item.objectId() + ".jpg";
    }

    /** A file name that is safe inside a URL path and still recognisable. */
    public static String safeName(String title) {
        if (title == null || title.trim().isEmpty()) {
            return "media";
        }
        String cleaned = title.trim().replace('/', '-').replace('\\', '-');
        if (cleaned.length() > 80) {
            cleaned = cleaned.substring(0, 80);
        }
        return cleaned;
    }

    private static String extensionFor(String mime, MediaItem item) {
        if (mime == null) {
            return item.extension().isEmpty() ? "bin" : item.extension();
        }
        switch (mime.toLowerCase(Locale.US)) {
            case "image/jpeg": case "image/jpg": return "jpg";
            case "image/png": return "png";
            case "image/gif": return "gif";
            case "image/bmp": case "image/x-ms-bmp": return "bmp";
            case "image/webp": return "webp";
            case "video/mp4": return "mp4";
            case "video/x-matroska": return "mkv";
            case "video/webm": return "webm";
            case "video/mp2t": return "ts";
            case "video/x-msvideo": case "video/avi": return "avi";
            case "audio/mpeg": return "mp3";
            case "audio/mp4": case "audio/mp4a-latm": return "m4a";
            case "audio/flac": return "flac";
            case "audio/wav": case "audio/x-wav": return "wav";
            default: {
                String fromMime = mime.contains("/") ? mime.substring(mime.indexOf('/') + 1) : "";
                int plus = fromMime.indexOf('+');
                if (plus > 0) {
                    fromMime = fromMime.substring(0, plus);
                }
                if (fromMime.length() >= 2 && fromMime.length() <= 5
                        && fromMime.matches("[a-z0-9]+")) {
                    return fromMime;
                }
                return item.extension().isEmpty() ? "bin" : item.extension();
            }
        }
    }

    /** Percent-encodes a path segment: spaces and non-ASCII characters in titles. */
    private static String urlEncode(String value) {
        try {
            return java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return value;
        }
    }

    /**
     * The object id in a media or thumbnail path, or null when the path is not
     * one of ours.
     *
     * Object ids are one letter plus the MediaStore row: {@code v42}, {@code p7},
     * {@code a3} (see {@code MediaItem.objectId()}). Anything else - a container
     * id from a browse, a path a scanner guessed - is rejected here rather than
     * being looked up in the catalogue.
     */
    public static String objectId(String path) {
        if (path == null) {
            return null;
        }
        String[] parts = path.split("/");
        // "/media/<id>/<name>" and "/thumb/<id>.jpg" both put the id at index 2.
        if (parts.length < 3 || parts[2].isEmpty()) {
            return null;
        }
        String candidate = parts[2];
        if (isThumbnail(path)) {
            int dot = candidate.indexOf('.');
            if (dot > 0) {
                candidate = candidate.substring(0, dot);
            }
        }
        return candidate.matches("[vpa]\\d+") ? candidate : null;
    }
}
