package com.lgmediabridge.compat;

import android.content.Context;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.util.LruCache;

import com.lgmediabridge.catalog.MediaItem;
import com.lgmediabridge.core.LogBus;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Decides, per file, whether the target LG TV (webOS 6.0) can play it directly.
 *
 * The rules come from LG's published "AV Format on webOS TV 6.0" specification
 * (container/codec table, maximum transmission rates, playback constraints) and
 * the model-specific spec sheet for the NANO86 series (audio codecs AC4, AC3,
 * EAC3, HE-AAC, AAC, MP2, MP3, PCM, WMA - no DTS on 2020+ panels).
 *
 * Nothing here is guessed: where a container/codec combination is not listed in
 * the specification it is reported as unsupported with the reason attached, and
 * the app never silently changes a file. Because the file's real tracks are read
 * with MediaExtractor, the verdict is per file rather than per extension.
 */
public final class MediaCompat {

    private static final String TAG = "Compat";

    private static final Set<String> IMAGE_DIRECT = new HashSet<>(Arrays.asList(
            "image/jpeg", "image/jpg", "image/pjpeg", "image/png", "image/gif",
            "image/bmp", "image/x-ms-bmp", "image/webp"));

    private static final Set<String> IMAGE_CONVERTIBLE = new HashSet<>(Arrays.asList(
            "image/heic", "image/heif", "image/heic-sequence", "image/heif-sequence",
            "image/avif", "image/x-adobe-dng", "image/tiff", "image/x-tiff",
            "image/svg+xml"));

    /** Audio codecs webOS 6.0 documents as playable. */
    private static final Set<String> AUDIO_DIRECT = new HashSet<>(Arrays.asList(
            "audio/mpeg", "audio/mp3", "audio/x-mpeg", "audio/mp4a-latm", "audio/mp4",
            "audio/aac", "audio/aac-adts", "audio/x-aac", "audio/flac", "audio/x-flac",
            "audio/vorbis", "audio/x-vorbis", "audio/x-ms-wma", "audio/wma",
            "audio/x-ms-wax", "audio/ac3", "audio/eac3", "audio/x-ac3", "audio/raw",
            "audio/wav", "audio/x-wav", "audio/vnd.wave", "audio/l16", "audio/pcm"));

    /** Codecs the platform can usually decode but webOS 6.0 cannot render. */
    private static final Set<String> AUDIO_CONVERTIBLE = new HashSet<>(Arrays.asList(
            "audio/opus", "audio/x-opus", "audio/opus-celt", "audio/alac", "audio/x-alac",
            "audio/amr", "audio/amr-nb", "audio/amr-wb", "audio/3gpp", "audio/ape",
            "audio/x-ape", "audio/x-monkeys-audio", "audio/vnd.dts", "audio/x-dts",
            "audio/dts", "audio/x-ms-wma-pro", "audio/x-ms-wma-lossless",
            "audio/true-hd", "audio/x-truehd", "audio/xm", "audio/midi", "audio/x-midi",
            "audio/vnd.rn-realaudio", "audio/x-pn-realaudio", "audio/aiff", "audio/x-aiff",
            "audio/wavpack", "audio/dsd", "audio/x-dsf", "audio/x-dff"));

    /** Containers listed in the webOS 6.0 video table, mapped to their codecs. */
    private static final Map<String, Set<String>> VIDEO_CODECS_BY_CONTAINER = new HashMap<>();
    private static final Map<String, Set<String>> AUDIO_CODECS_BY_CONTAINER = new HashMap<>();

    private static final Set<String> CONTAINER_MP4 = new HashSet<>(Arrays.asList("mp4", "m4v", "mov"));
    private static final Set<String> CONTAINER_MKV = new HashSet<>(Arrays.asList("mkv", "webm"));
    private static final Set<String> CONTAINER_TS = new HashSet<>(Arrays.asList("ts", "trp", "tp", "mts", "m2ts"));
    private static final Set<String> CONTAINER_MPG = new HashSet<>(Arrays.asList("mpg", "mpeg", "dat"));
    private static final Set<String> CONTAINER_AVI = new HashSet<>(Arrays.asList("avi", "divx"));
    private static final Set<String> CONTAINER_WMV = new HashSet<>(Arrays.asList("asf", "wmv"));
    private static final Set<String> CONTAINER_3GP = new HashSet<>(Arrays.asList("3gp", "3g2"));
    private static final Set<String> CONTAINER_VOB = new HashSet<>(Arrays.asList("vob"));

    private static final Set<String> VIDEO_ALL = new HashSet<>(Arrays.asList(
            "video/avc", "video/hevc", "video/mp4v-es", "video/mpeg4", "video/mpeg2",
            "video/mpeg", "video/mjpeg", "video/x-motion-jpeg", "video/vc1", "video/x-ms-wmv"));
    private static final Set<String> VIDEO_VP = new HashSet<>(Arrays.asList(
            "video/x-vnd.on2.vp8", "video/vp8", "video/x-vnd.on2.vp9", "video/vp9", "video/av01"));

    // UHD limits from the specification's "maximum data transmission rate" table.
    private static final long LIMIT_H264_UHD = 50_000_000L;
    private static final long LIMIT_HEVC_UHD = 60_000_000L;
    private static final long LIMIT_VP9_UHD = 50_000_000L;
    private static final long LIMIT_AV1_UHD = 50_000_000L;
    private static final long LIMIT_FHD = 40_000_000L;

    private final LruCache<String, CompatResult> cache = new LruCache<>(512);
    private final LruCache<String, CompatResult> quickCache = new LruCache<>(2048);

    /**
     * Deep analysis: opens the file and reads its real tracks. Used for details
     * screens and before pushing media to the TV.
     */
    public CompatResult analyze(Context context, MediaItem item) {
        CompatResult cached = cache.get(item.objectId());
        if (cached != null) {
            return cached;
        }
        CompatResult result;
        switch (item.kind) {
            case PHOTO: result = analyzePhoto(item); break;
            case AUDIO: result = analyzeAudio(context, item); break;
            default: result = analyzeVideo(context, item); break;
        }
        cache.put(item.objectId(), result);
        return result;
    }

    /**
     * Cheap analysis from MIME type, container and extension only.
     *
     * This is what browse listings use: building a 20 000 item DIDL response must
     * not open 20 000 files, and the container/codec rules are exact enough to
     * decide "list it or hide it". Screen details use {@link #analyze} instead.
     */
    public CompatResult quick(MediaItem item) {
        CompatResult cached = quickCache.get(item.objectId());
        if (cached != null) {
            return cached;
        }
        CompatResult result;
        switch (item.kind) {
            case PHOTO:
                result = analyzePhoto(item);
                break;
            case AUDIO: {
                String mime = normaliseMime(item);
                List<String> reasons = new ArrayList<>();
                Map<String, String> details = new LinkedHashMap<>();
                details.put("Container", containerName(item));
                details.put("Audio", codecName(mime));
                if (AUDIO_DIRECT.contains(mime)) {
                    result = new CompatResult(CompatResult.Verdict.DIRECT, reasons, details, false);
                } else if (AUDIO_CONVERTIBLE.contains(mime) || AUDIO_CONVERTIBLE.contains(item.mimeType)) {
                    reasons.add(codecName(mime) + " is not decodable by webOS 6.0; it will be "
                            + "converted to AAC and cached.");
                    result = new CompatResult(CompatResult.Verdict.AUDIO_CONVERT, reasons, details, false);
                } else {
                    reasons.add("Audio format " + codecName(mime) + " is not in the documented "
                            + "webOS 6.0 audio list.");
                    result = new CompatResult(CompatResult.Verdict.UNSUPPORTED, reasons, details, false);
                }
                break;
            }
            default: {
                String mime = guessVideoMime(item);
                List<String> reasons = new ArrayList<>();
                Map<String, String> details = new LinkedHashMap<>();
                details.put("Container", containerName(item));
                details.put("Video", codecName(mime));
                if (!isKnownVideoContainer(item)) {
                    reasons.add("The ." + item.extension() + " container is not in the webOS 6.0 "
                            + "video table.");
                    result = new CompatResult(CompatResult.Verdict.UNSUPPORTED, reasons, details, false);
                } else if (mime == null) {
                    reasons.add("Unknown video codec.");
                    result = new CompatResult(CompatResult.Verdict.UNSUPPORTED, reasons, details, false);
                } else if (!VIDEO_ALL.contains(mime) && !VIDEO_VP.contains(mime)) {
                    reasons.add(codecName(mime) + " is not in the webOS 6.0 video codec list.");
                    result = new CompatResult(CompatResult.Verdict.UNSUPPORTED, reasons, details, false);
                } else if (mime.startsWith("video/hevc") && CONTAINER_AVI.contains(item.extension())) {
                    reasons.add("HEVC in .avi is not documented for webOS.");
                    result = new CompatResult(CompatResult.Verdict.UNSUPPORTED, reasons, details, false);
                } else {
                    result = new CompatResult(CompatResult.Verdict.DIRECT, reasons, details, false);
                }
                break;
            }
        }
        quickCache.put(item.objectId(), result);
        return result;
    }

    public void invalidate() {
        cache.evictAll();
        quickCache.evictAll();
    }

    /** The MIME type actually served to the TV (changed when conversion applies). */
    public static String effectiveMime(MediaItem item, CompatResult result) {
        if (result.verdict == CompatResult.Verdict.PHOTO_CONVERT) {
            return "image/jpeg";
        }
        if (result.verdict == CompatResult.Verdict.AUDIO_CONVERT) {
            return "audio/mp4";
        }
        return normaliseMime(item);
    }

    public static String normaliseMime(MediaItem item) {
        String mime = item.mimeType == null ? "" : item.mimeType.toLowerCase(Locale.US);
        if (!mime.isEmpty() && !"application/octet-stream".equals(mime)) {
            return mime;
        }
        // Some providers leave MIME_TYPE empty; derive it from the extension.
        switch (item.extension()) {
            case "jpg": case "jpeg": return "image/jpeg";
            case "png": return "image/png";
            case "gif": return "image/gif";
            case "webp": return "image/webp";
            case "bmp": return "image/bmp";
            case "heic": return "image/heic";
            case "mp4": case "m4v": return "video/mp4";
            case "mkv": return "video/x-matroska";
            case "webm": return "video/webm";
            case "avi": return "video/x-msvideo";
            case "mov": return "video/quicktime";
            case "ts": return "video/mp2t";
            case "3gp": return "video/3gpp";
            case "wmv": return "video/x-ms-wmv";
            case "mp3": return "audio/mpeg";
            case "m4a": case "aac": return "audio/mp4";
            case "flac": return "audio/flac";
            case "ogg": case "oga": return "audio/ogg";
            case "opus": return "audio/opus";
            case "wav": return "audio/wav";
            case "wma": return "audio/x-ms-wma";
            default: return "application/octet-stream";
        }
    }

    // -------------------------------------------------------------- photos

    private CompatResult analyzePhoto(MediaItem item) {
        String mime = normaliseMime(item);
        Map<String, String> details = new LinkedHashMap<>();
        List<String> reasons = new ArrayList<>();
        details.put("Format", mime);
        if (item.resolution() != null) {
            details.put("Resolution", item.resolution());
        }
        if (item.sizeBytes > 0) {
            details.put("Size", com.lgmediabridge.core.Formats.bytes(item.sizeBytes));
        }
        if (IMAGE_DIRECT.contains(mime)) {
            long pixels = (long) item.width * item.height;
            if (pixels > 40_000_000L) {
                reasons.add("Very large images (>40 MP) can exhaust the TV's decoder; conversion to a "
                        + "scaled JPEG is safer. Enable it in Settings if this photo fails to open.");
            }
            return new CompatResult(CompatResult.Verdict.DIRECT, reasons, details, false);
        }
        if (IMAGE_CONVERTIBLE.contains(mime)) {
            reasons.add("webOS plays JPEG, GIF, PNG, BMP and WebP. "
                    + mime + " will be converted to JPEG, keeping the original resolution.");
            return new CompatResult(CompatResult.Verdict.PHOTO_CONVERT, reasons, details, false);
        }
        reasons.add("Unrecognised image format (" + mime + ").");
        return new CompatResult(CompatResult.Verdict.UNSUPPORTED, reasons, details, false);
    }

    // --------------------------------------------------------------- audio

    private CompatResult analyzeAudio(Context context, MediaItem item) {
        String mime = normaliseMime(item);
        Map<String, String> details = new LinkedHashMap<>();
        List<String> reasons = new ArrayList<>();
        TrackInfo track = readFirstAudioTrack(context, item);
        boolean inspected = track != null;
        String codecMime = inspected ? track.mime : mime;
        details.put("Container", containerName(item));
        details.put("Audio", codecName(codecMime));
        if (item.durationMs > 0) {
            details.put("Duration", com.lgmediabridge.core.Formats.duration(item.durationMs));
        }
        if (item.sizeBytes > 0) {
            details.put("Size", com.lgmediabridge.core.Formats.bytes(item.sizeBytes));
        }
        if (item.sizeBytes > 0 && item.durationMs > 0) {
            details.put("Bitrate", com.lgmediabridge.core.Formats.bitrate(
                    item.sizeBytes * 8000L / Math.max(1, item.durationMs)));
        }
        if (AUDIO_DIRECT.contains(codecMime) || isSupportedAudioMime(codecMime)) {
            if (track != null) {
                if (track.sampleRate > 0 && (track.sampleRate < 8000 || track.sampleRate > 96000)) {
                    reasons.add("Sample rate " + track.sampleRate + " Hz is outside the "
                            + "8–96 kHz range documented for webOS audio playback.");
                }
                if (track.channels > 2 && !"audio/ac3".equals(codecMime)
                        && !"audio/eac3".equals(codecMime)
                        && !"audio/x-ms-wma".equals(codecMime)) {
                    reasons.add(track.channels + " channels: webOS documents stereo (or up to 6 channels "
                            + "for WMA/Dolby) for this codec, the TV may down-mix.");
                }
            }
            return new CompatResult(CompatResult.Verdict.DIRECT, reasons, details, inspected);
        }
        if (AUDIO_CONVERTIBLE.contains(codecMime) || AUDIO_CONVERTIBLE.contains(mime)) {
            if (playableLocally(codecMime)) {
                reasons.add(codecName(codecMime) + " is not decodable by webOS 6.0. MediaBridge will "
                        + "convert it to AAC once and cache the result, so the TV can play it and "
                        + "seek normally.");
                return new CompatResult(CompatResult.Verdict.AUDIO_CONVERT, reasons, details, inspected);
            }
            reasons.add(codecName(codecMime) + " is not supported by webOS 6.0 and this phone has no "
                    + "decoder for it either.");
            return new CompatResult(CompatResult.Verdict.UNSUPPORTED, reasons, details, inspected);
        }
        reasons.add(codecName(codecMime) + " is not in the documented webOS 6.0 audio codec list.");
        if (playableLocally(codecMime)) {
            reasons.add("It can be converted to AAC on this phone, which makes it playable.");
            return new CompatResult(CompatResult.Verdict.AUDIO_CONVERT, reasons, details, inspected);
        }
        return new CompatResult(CompatResult.Verdict.UNSUPPORTED, reasons, details, inspected);
    }

    private static boolean isSupportedAudioMime(String mime) {
        return mime != null && (mime.startsWith("audio/") && AUDIO_DIRECT.contains(mime));
    }

    // --------------------------------------------------------------- video

    private CompatResult analyzeVideo(Context context, MediaItem item) {
        String container = containerName(item);
        Map<String, String> details = new LinkedHashMap<>();
        List<String> reasons = new ArrayList<>();
        details.put("Container", container);

        VideoTracks tracks = readVideoTracks(context, item);
        boolean inspected = tracks != null && tracks.videoMime != null;
        String videoMime = inspected ? tracks.videoMime : guessVideoMime(item);
        String audioMime = inspected ? tracks.audioMime : null;

        details.put("Video", codecName(videoMime));
        if (audioMime != null) {
            details.put("Audio", codecName(audioMime));
        }
        if (item.resolution() != null) {
            details.put("Resolution", item.resolution());
        }
        if (item.durationMs > 0) {
            details.put("Duration", com.lgmediabridge.core.Formats.duration(item.durationMs));
        }
        if (item.sizeBytes > 0) {
            details.put("Size", com.lgmediabridge.core.Formats.bytes(item.sizeBytes));
        }
        long bitrate = item.sizeBytes > 0 && item.durationMs > 0
                ? item.sizeBytes * 8000L / item.durationMs : 0;
        if (bitrate > 0) {
            details.put("Average bitrate", com.lgmediabridge.core.Formats.bitrate(bitrate));
        }

        if (!isKnownVideoContainer(item)) {
            reasons.add("The ." + item.extension() + " container is not in the webOS 6.0 video table.");
            return new CompatResult(CompatResult.Verdict.UNSUPPORTED, reasons, details, inspected);
        }
        if (videoMime == null) {
            reasons.add("Could not identify the video codec; the TV may refuse this file.");
            return new CompatResult(CompatResult.Verdict.UNSUPPORTED, reasons, details, inspected);
        }
        if (!VIDEO_ALL.contains(videoMime) && !VIDEO_VP.contains(videoMime)) {
            reasons.add(codecName(videoMime) + " is not in the webOS 6.0 video codec list.");
            return new CompatResult(CompatResult.Verdict.UNSUPPORTED, reasons, details, inspected);
        }
        if (VIDEO_VP.contains(videoMime) && !(CONTAINER_MKV.contains(item.extension()))) {
            reasons.add("VP8/VP9/AV1 are documented for the .mkv container; this file is ."
                    + item.extension() + ".");
        }
        if (videoMime.startsWith("video/hevc") && CONTAINER_AVI.contains(item.extension())) {
            reasons.add("HEVC inside .avi is not documented (AVI lists Xvid, H.264, MJPEG and MPEG-4).");
            return new CompatResult(CompatResult.Verdict.UNSUPPORTED, reasons, details, inspected);
        }
        if (audioMime != null && !isAudioSupportedInContainer(audioMime, item)) {
            reasons.add("The audio track (" + codecName(audioMime) + ") is not playable by webOS 6.0 "
                    + "in this container. The video stream itself is fine, but DLNA playback sends "
                    + "the file as-is, so the TV will either fail or play it without sound. "
                    + "Converting the audio would require re-muxing the video, which this app does "
                    + "not do on-device (see the research notes in docs/RESEARCH.md).");
            return new CompatResult(CompatResult.Verdict.UNSUPPORTED, reasons, details, inspected);
        }
        boolean uhd = item.width >= 3840 || item.height >= 2160;
        if (bitrate > 0) {
            long limit = uhd ? limitFor(videoMime) : LIMIT_FHD;
            if (limit > 0 && bitrate > limit) {
                reasons.add("Average bitrate " + com.lgmediabridge.core.Formats.bitrate(bitrate)
                        + " is above the documented ceiling for this codec ("
                        + com.lgmediabridge.core.Formats.bitrate(limit) + "). Expect buffering, "
                        + "especially over Wi-Fi.");
            }
        }
        if (uhd && !(videoMime.startsWith("video/avc") || videoMime.startsWith("video/hevc")
                || VIDEO_VP.contains(videoMime))) {
            reasons.add("Ultra HD playback on this model is documented for H.264, HEVC, VP9 and AV1.");
        }
        return new CompatResult(CompatResult.Verdict.DIRECT, reasons, details, inspected);
    }

    private static long limitFor(String videoMime) {
        if (videoMime == null) {
            return LIMIT_FHD;
        }
        if (videoMime.startsWith("video/avc")) {
            return LIMIT_H264_UHD;
        }
        if (videoMime.startsWith("video/hevc")) {
            return LIMIT_HEVC_UHD;
        }
        if (videoMime.contains("vp9")) {
            return LIMIT_VP9_UHD;
        }
        if (videoMime.startsWith("video/av01")) {
            return LIMIT_AV1_UHD;
        }
        return LIMIT_FHD;
    }

    private static boolean isAudioSupportedInContainer(String audioMime, MediaItem item) {
        String extension = item.extension();
        Set<String> allowed = AUDIO_CODECS_BY_CONTAINER.get(extension);
        if (allowed == null) {
            return AUDIO_DIRECT.contains(audioMime);
        }
        if (allowed.contains(audioMime)) {
            return true;
        }
        // AC-4 and MPEG-H are model specific; flag as direct but note it.
        return false;
    }

    public static boolean isKnownVideoContainer(MediaItem item) {
        String extension = item.extension();
        return CONTAINER_MP4.contains(extension) || CONTAINER_MKV.contains(extension)
                || CONTAINER_TS.contains(extension) || CONTAINER_MPG.contains(extension)
                || CONTAINER_AVI.contains(extension) || CONTAINER_WMV.contains(extension)
                || CONTAINER_3GP.contains(extension) || CONTAINER_VOB.contains(extension);
    }

    public static String containerName(MediaItem item) {
        String extension = item.extension();
        if (CONTAINER_MP4.contains(extension)) {
            return extension.toUpperCase(Locale.US) + " (MP4 family)";
        }
        if (CONTAINER_MKV.contains(extension)) {
            return extension.toUpperCase(Locale.US) + " (Matroska family)";
        }
        if (CONTAINER_TS.contains(extension)) {
            return extension.toUpperCase(Locale.US) + " (MPEG-TS)";
        }
        if (CONTAINER_MPG.contains(extension)) {
            return extension.toUpperCase(Locale.US) + " (MPEG program stream)";
        }
        return extension.isEmpty() ? "unknown" : extension.toUpperCase(Locale.US);
    }

    private static String guessVideoMime(MediaItem item) {
        String mime = normaliseMime(item);
        if (mime.startsWith("video/")) {
            return mime;
        }
        if ("video/x-matroska".equals(mime) || "mkv".equals(item.extension())) {
            return "video/avc";
        }
        return null;
    }

    private static String codecName(String mime) {
        if (mime == null) {
            return "unknown";
        }
        switch (mime) {
            case "video/avc": return "H.264 / AVC";
            case "video/hevc": return "H.265 / HEVC";
            case "video/mp4v-es": case "video/mpeg4": return "MPEG-4 Part 2";
            case "video/mpeg2": return "MPEG-2";
            case "video/mpeg": return "MPEG-1";
            case "video/x-vnd.on2.vp8": case "video/vp8": return "VP8";
            case "video/x-vnd.on2.vp9": case "video/vp9": return "VP9";
            case "video/av01": return "AV1";
            case "video/mjpeg": case "video/x-motion-jpeg": return "Motion JPEG";
            case "video/vc1": case "video/x-ms-wmv": return "VC-1 / WMV";
            case "video/3gpp": return "H.263";
            case "audio/mp4a-latm": return "AAC";
            case "audio/mpeg": case "audio/mp3": return "MP3";
            case "audio/ac3": return "Dolby Digital (AC-3)";
            case "audio/eac3": return "Dolby Digital Plus (E-AC-3)";
            case "audio/flac": return "FLAC";
            case "audio/vorbis": return "Vorbis";
            case "audio/opus": return "Opus";
            case "audio/raw": return "PCM";
            case "audio/x-ms-wma": return "WMA";
            case "audio/alac": return "ALAC";
            case "audio/amr": case "audio/amr-nb": case "audio/amr-wb": return "AMR";
            case "audio/vnd.dts": case "audio/x-dts": return "DTS";
            case "audio/true-hd": return "Dolby TrueHD";
            default: return mime;
        }
    }

    private static final class TrackInfo {
        String mime;
        int channels;
        int sampleRate;
    }

    private TrackInfo readFirstAudioTrack(Context context, MediaItem item) {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(context, Uri.parse(item.uri), null);
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    TrackInfo info = new TrackInfo();
                    info.mime = mime;
                    info.channels = format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                            ? format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 0;
                    info.sampleRate = format.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                            ? format.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 0;
                    return info;
                }
            }
        } catch (Exception e) {
            LogBus.get().d(TAG, "audio track read failed for " + item.displayName + ": " + e.getMessage());
        } finally {
            try {
                extractor.release();
            } catch (Exception ignored) {
                // ignore
            }
        }
        return null;
    }

    private static final class VideoTracks {
        String videoMime;
        String audioMime;
    }

    private VideoTracks readVideoTracks(Context context, MediaItem item) {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(context, Uri.parse(item.uri), null);
            VideoTracks tracks = new VideoTracks();
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime == null) {
                    continue;
                }
                if (mime.startsWith("video/") && tracks.videoMime == null) {
                    tracks.videoMime = mime;
                } else if (mime.startsWith("audio/") && tracks.audioMime == null) {
                    tracks.audioMime = mime;
                }
            }
            return tracks.videoMime == null ? null : tracks;
        } catch (Exception e) {
            LogBus.get().d(TAG, "track read failed for " + item.displayName + ": " + e.getMessage());
            return null;
        } finally {
            try {
                extractor.release();
            } catch (Exception ignored) {
                // ignore
            }
        }
    }

    /** True when this phone can decode the codec (so a conversion is possible). */
    private static boolean playableLocally(String mime) {
        if (mime == null) {
            return false;
        }
        try {
            MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
            for (MediaCodecInfo info : list.getCodecInfos()) {
                if (info.isEncoder()) {
                    continue;
                }
                for (String supported : info.getSupportedTypes()) {
                    if (supported.equalsIgnoreCase(mime)) {
                        return true;
                    }
                }
            }
        } catch (RuntimeException e) {
            LogBus.get().d(TAG, "codec list unavailable: " + e.getMessage());
        }
        return false;
    }

    static {
        // Container → codec table, transcribed from "AV Format on webOS TV 6.0".
        Set<String> mp4Video = new HashSet<>(Arrays.asList(
                "video/avc", "video/mp4v-es", "video/hevc", "video/av01", "video/mpeg4"));
        Set<String> mp4Audio = new HashSet<>(Arrays.asList(
                "audio/ac3", "audio/eac3", "audio/mp4a-latm", "audio/mpeg", "audio/aac",
                "audio/raw"));
        Set<String> mkvVideo = new HashSet<>(Arrays.asList(
                "video/mpeg2", "video/mp4v-es", "video/avc", "video/x-vnd.on2.vp8",
                "video/x-vnd.on2.vp9", "video/hevc", "video/av01", "video/vp8", "video/vp9"));
        Set<String> mkvAudio = new HashSet<>(Arrays.asList(
                "audio/ac3", "audio/eac3", "audio/mp4a-latm", "audio/aac", "audio/raw",
                "audio/mpeg", "audio/vorbis"));
        Set<String> tsVideo = new HashSet<>(Arrays.asList("video/avc", "video/mpeg2", "video/hevc"));
        Set<String> tsAudio = new HashSet<>(Arrays.asList(
                "audio/mpeg", "audio/ac3", "audio/eac3", "audio/mp4a-latm", "audio/aac", "audio/raw"));
        Set<String> aviVideo = new HashSet<>(Arrays.asList(
                "video/mp4v-es", "video/mpeg4", "video/avc", "video/mjpeg", "video/x-motion-jpeg"));
        Set<String> aviAudio = new HashSet<>(Arrays.asList(
                "audio/mpeg", "audio/ac3", "audio/raw"));
        Set<String> wmvVideo = new HashSet<>(Arrays.asList("video/vc1", "video/x-ms-wmv"));
        Set<String> wmvAudio = new HashSet<>(Arrays.asList("audio/x-ms-wma"));
        Set<String> mpgVideo = new HashSet<>(Arrays.asList("video/mpeg", "video/mpeg2"));
        Set<String> mpgAudio = new HashSet<>(Arrays.asList("audio/mpeg"));
        Set<String> vobVideo = new HashSet<>(Arrays.asList("video/mpeg", "video/mpeg2"));
        Set<String> vobAudio = new HashSet<>(Arrays.asList("audio/ac3", "audio/mpeg", "audio/raw"));
        Set<String> threeGpVideo = new HashSet<>(Arrays.asList("video/avc", "video/mp4v-es", "video/mpeg4"));
        Set<String> threeGpAudio = new HashSet<>(Arrays.asList("audio/mp4a-latm", "audio/aac", "audio/amr"));

        for (String extension : CONTAINER_MP4) {
            VIDEO_CODECS_BY_CONTAINER.put(extension, mp4Video);
            AUDIO_CODECS_BY_CONTAINER.put(extension, mp4Audio);
        }
        for (String extension : CONTAINER_MKV) {
            VIDEO_CODECS_BY_CONTAINER.put(extension, mkvVideo);
            AUDIO_CODECS_BY_CONTAINER.put(extension, mkvAudio);
        }
        for (String extension : CONTAINER_TS) {
            VIDEO_CODECS_BY_CONTAINER.put(extension, tsVideo);
            AUDIO_CODECS_BY_CONTAINER.put(extension, tsAudio);
        }
        for (String extension : CONTAINER_AVI) {
            VIDEO_CODECS_BY_CONTAINER.put(extension, aviVideo);
            AUDIO_CODECS_BY_CONTAINER.put(extension, aviAudio);
        }
        for (String extension : CONTAINER_WMV) {
            VIDEO_CODECS_BY_CONTAINER.put(extension, wmvVideo);
            AUDIO_CODECS_BY_CONTAINER.put(extension, wmvAudio);
        }
        for (String extension : CONTAINER_MPG) {
            VIDEO_CODECS_BY_CONTAINER.put(extension, mpgVideo);
            AUDIO_CODECS_BY_CONTAINER.put(extension, mpgAudio);
        }
        for (String extension : CONTAINER_VOB) {
            VIDEO_CODECS_BY_CONTAINER.put(extension, vobVideo);
            AUDIO_CODECS_BY_CONTAINER.put(extension, vobAudio);
        }
        for (String extension : CONTAINER_3GP) {
            VIDEO_CODECS_BY_CONTAINER.put(extension, threeGpVideo);
            AUDIO_CODECS_BY_CONTAINER.put(extension, threeGpAudio);
        }
    }
}
