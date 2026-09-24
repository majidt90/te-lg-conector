package com.lgmediabridge.dlna;

import com.lgmediabridge.catalog.MediaItem;
import com.lgmediabridge.compat.CompatResult;

import java.util.Locale;

/**
 * DLNA constants and the header values that make seeking work.
 *
 * Background, because these strings are the difference between "the TV plays it
 * but the seek bar is greyed out" and a normal player experience:
 *
 *   DLNA.ORG_OP   bit a = server supports {@code TimeSeekRange.dlna.org},
 *                 bit b = server supports plain {@code Range: bytes=…}.
 *                 {@code 01} tells the renderer it may seek by bytes.
 *   DLNA.ORG_CI   conversion indicator: 0 = the bits sent are the bits stored,
 *                 1 = the resource was converted.
 *   DLNA.ORG_FLAGS 8 hex digits of streaming capability bits followed by reserved
 *                 zeros (per the DLNA guidelines and Emby/Jellyfin's published
 *                 protocol documentation).
 *
 * LG TVs in particular hide seeking when a server does not advertise random
 * access, so every media response here sets {@code Accept-Ranges: bytes}, honours
 * {@code Range}, and answers {@code getcontentFeatures.dlna.org} with the same
 * tokens that appear in the browse response.
 */
public final class Dlna {

    public static final String CONTENT_FEATURES = "contentFeatures.dlna.org";
    public static final String GET_CONTENT_FEATURES = "getcontentFeatures.dlna.org";
    public static final String TRANSFER_MODE = "transferMode.dlna.org";
    public static final String GET_TRANSFER_MODE = "gettransferMode.dlna.org";
    public static final String TIME_SEEK_RANGE = "TimeSeekRange.dlna.org";
    public static final String PLAY_SPEED = "PlaySpeed.dlna.org";
    public static final String AVAILABLE_SEEK_RANGE = "AvailableSeekRange.dlna.org";
    public static final String DLNA_DEVICE_NAME = "DLNADeviceName.lge.com";

    /** Bitmap of DLNA capability flags: streaming mode, background transfer, etc. */
    public static final String FLAGS_STREAMING = "01700000000000000000000000000000";

    public static final String MIME_JPEG = "image/jpeg";
    public static final String MIME_PNG = "image/png";
    public static final String MIME_AAC_MP4 = "audio/mp4";

    private Dlna() {
    }

    /** Builds the fourth field of {@code http-get:*:<mime>:<here>}. */
    public static String additionalInfo(String mime, boolean seekable, boolean converted, String profile) {
        StringBuilder sb = new StringBuilder();
        if (profile != null && !profile.isEmpty()) {
            sb.append("DLNA.ORG_PN=").append(profile).append(';');
        }
        sb.append("DLNA.ORG_OP=").append(seekable ? "01" : "00").append(';');
        sb.append("DLNA.ORG_CI=").append(converted ? "1" : "0").append(';');
        sb.append("DLNA.ORG_FLAGS=").append(FLAGS_STREAMING);
        return sb.toString();
    }

    public static String protocolInfo(String mime, boolean seekable, boolean converted, String profile) {
        return "http-get:*:" + mime + ":" + additionalInfo(mime, seekable, converted, profile);
    }

    /** Content feature tokens sent in media responses, mirroring the browse metadata. */
    public static String contentFeatures(String mime, boolean seekable, boolean converted, String profile) {
        return additionalInfo(mime, seekable, converted, profile);
    }

    /**
     * A DLNA profile name only when the guidelines define an unambiguous one for
     * the media type. For video no PN is claimed: an incorrect PN makes strict
     * renderers reject the item, while a missing PN is valid and universally
     * accepted (renderers fall back to MIME type support).
     */
    public static String profileFor(String mime, MediaItem.Kind kind, boolean thumbnail, boolean converted) {
        if (kind == MediaItem.Kind.PHOTO) {
            if (thumbnail) {
                return "JPEG_TN";
            }
            if (MIME_JPEG.equals(mime)) {
                return "JPEG_LRG";
            }
            if (MIME_PNG.equals(mime)) {
                return "PNG_LRG";
            }
            if ("image/gif".equals(mime)) {
                return "GIF_LRG";
            }
            return converted ? "JPEG_LRG" : null;
        }
        if (kind == MediaItem.Kind.AUDIO) {
            if ("audio/mpeg".equals(mime)) {
                return "MP3";
            }
            if (MIME_AAC_MP4.equals(mime) || "audio/mp4a-latm".equals(mime)) {
                return "AAC_ISO_320";
            }
            if ("audio/ac3".equals(mime)) {
                return "AC3";
            }
            if ("audio/flac".equals(mime) || "audio/x-flac".equals(mime)) {
                return "FLAC";
            }
            if ("audio/raw".equals(mime) || "audio/wav".equals(mime) || "audio/x-wav".equals(mime)) {
                return "LPCM";
            }
            return null;
        }
        return null;
    }

    public static String upnpClass(MediaItem.Kind kind) {
        switch (kind) {
            case PHOTO: return "object.item.imageItem.photo";
            case AUDIO: return "object.item.audioItem.musicTrack";
            default: return "object.item.videoItem.movie";
        }
    }

    public static String transferMode(MediaItem.Kind kind) {
        return kind == MediaItem.Kind.PHOTO ? "Interactive" : "Streaming";
    }

    /** UPnP duration format: {@code H+:MM:SS.mmm}. */
    public static String duration(long millis) {
        if (millis <= 0) {
            return null;
        }
        long totalSeconds = millis / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        long ms = millis % 1000;
        return String.format(Locale.US, "%d:%02d:%02d.%03d", hours, minutes, seconds, ms);
    }

    /**
     * The size to publish in DIDL-Lite, or 0 when it is not what will be served.
     *
     * A converted resource is a different file: the JPEG of a HEIC photo and the
     * AAC of an Opus track have their own sizes, known only once the conversion
     * has run. Publishing the original size would be a lie the player may act on
     * (planning a buffer, drawing progress), so the attribute is left out and the
     * response's own Content-Length is the single source of truth.
     */
    public static long advertisedSize(MediaItem item, CompatResult result) {
        if (item == null || result == null || item.sizeBytes <= 0) {
            return 0;
        }
        return result.verdict == CompatResult.Verdict.DIRECT ? item.sizeBytes : 0;
    }

    /** True when the app can offer byte-range seeking for this resource. */
    public static boolean seekable(CompatResult result) {
        return result.verdict == CompatResult.Verdict.DIRECT
                || result.verdict == CompatResult.Verdict.PHOTO_CONVERT
                || result.verdict == CompatResult.Verdict.AUDIO_CONVERT;
    }
}
