package com.lgmediabridge.control;

import com.lgmediabridge.core.LogBus;

import java.util.Map;

/**
 * Remote playback and volume for a UPnP AV renderer (AVTransport +
 * RenderingControl).
 *
 * Both of these are part of UPnP AV, the same standard the TV uses to browse
 * media, so nothing here relies on undocumented behaviour:
 * <ul>
 *   <li>{@code SetAVTransportURI} tells the TV what to play, given a URL on this
 *       phone's media server;</li>
 *   <li>{@code Play}/{@code Pause}/{@code Stop}/{@code Seek} drive it;</li>
 *   <li>{@code GetTransportInfo}/{@code GetPositionInfo}/{@code GetMediaInfo}
 *       report state, position and duration;</li>
 *   <li>{@code GetVolume}/{@code SetVolume} live in RenderingControl.</li>
 * </ul>
 * Whether a given TV exposes AVTransport is discovered from its device
 * description, never assumed: {@link TvDevice#supportsRemotePlayback()} must be
 * true before any of these calls are made.
 */
public final class TvControl {

    private static final String TAG = "TvControl";

    public enum TransportState {
        STOPPED, PLAYING, PAUSED, TRANSITIONING, NO_MEDIA, UNKNOWN;

        static TransportState parse(String value) {
            if (value == null) {
                return UNKNOWN;
            }
            switch (value) {
                case "PLAYING": return PLAYING;
                case "PAUSED_PLAYBACK":
                case "PAUSED_RECORDING": return PAUSED;
                case "STOPPED": return STOPPED;
                case "TRANSITIONING": return TRANSITIONING;
                case "NO_MEDIA_PRESENT": return NO_MEDIA;
                default: return UNKNOWN;
            }
        }
    }

    public static final class PlaybackInfo {
        public final TransportState state;
        public final long positionMs;
        public final long durationMs;
        public final String currentUri;
        public final String currentUriMetadata;
        public final String mediaDuration;
        public final int volume;

        PlaybackInfo(TransportState state, long positionMs, long durationMs, String currentUri,
                     String currentUriMetadata, String mediaDuration, int volume) {
            this.state = state;
            this.positionMs = positionMs;
            this.durationMs = durationMs;
            this.currentUri = currentUri;
            this.currentUriMetadata = currentUriMetadata;
            this.mediaDuration = mediaDuration;
            this.volume = volume;
        }

        public int progressPercent() {
            if (durationMs <= 0) {
                return 0;
            }
            long clamped = Math.max(0, Math.min(positionMs, durationMs));
            return (int) (clamped * 100 / durationMs);
        }
    }

    private final TvDevice device;

    public TvControl(TvDevice device) {
        this.device = device;
    }

    public boolean isAvailable() {
        return device != null && device.supportsRemotePlayback();
    }

    private Map<String, String> baseArguments() {
        return SoapClient.args("InstanceID", "0");
    }

    /** Instances are 0 on every renderer observed in the wild; asked for once. */
    public int instanceId() {
        return 0;
    }

    /** Points the TV at a media URL on this phone and starts playback. */
    public boolean playMedia(String url, String didlMetadata, String mimeType) {
        if (!isAvailable()) {
            LogBus.get().d(TAG, "playback control unsupported by " + describe());
            return false;
        }
        Map<String, String> arguments = baseArguments();
        arguments.put("CurrentURI", url);
        arguments.put("CurrentURIMetaData", didlMetadata == null ? "" : didlMetadata);
        SoapClient.Response setUri = SoapClient.call(device.avTransportUrl, device.avTransportType,
                "SetAVTransportURI", arguments, clientTag());
        if (!setUri.success) {
            LogBus.get().w(TAG, "could not hand the media to " + describe() + ": " + setUri.error);
            return false;
        }
        return play();
    }

    public boolean play() {
        Map<String, String> arguments = baseArguments();
        arguments.put("Speed", "1");
        return invoke("Play", arguments);
    }

    public boolean pause() {
        return invoke("Pause", baseArguments());
    }

    public boolean stop() {
        return invoke("Stop", baseArguments());
    }

    public boolean seek(long positionMs) {
        Map<String, String> arguments = baseArguments();
        arguments.put("Unit", "REL_TIME");
        arguments.put("Target", time(positionMs));
        return invoke("Seek", arguments);
    }

    public boolean next() {
        return invoke("Next", baseArguments());
    }

    public boolean previous() {
        return invoke("Previous", baseArguments());
    }

    public int getVolume() {
        if (device == null || !device.supportsRemoteVolume()) {
            return -1;
        }
        Map<String, String> arguments = baseArguments();
        arguments.put("Channel", "Master");
        SoapClient.Response response = SoapClient.call(device.renderingControlUrl,
                device.renderingControlType, "GetVolume", arguments, clientTag());
        if (!response.success) {
            return -1;
        }
        try {
            return Integer.parseInt(response.argument("CurrentVolume"));
        } catch (Exception e) {
            return -1;
        }
    }

    public boolean setVolume(int volume) {
        if (device == null || !device.supportsRemoteVolume()) {
            return false;
        }
        Map<String, String> arguments = baseArguments();
        arguments.put("Channel", "Master");
        arguments.put("DesiredVolume", String.valueOf(Math.max(0, Math.min(100, volume))));
        SoapClient.Response response = SoapClient.call(device.renderingControlUrl,
                device.renderingControlType, "SetVolume", arguments, clientTag());
        return response.success;
    }

    public boolean setMute(boolean muted) {
        if (device == null || !device.supportsRemoteVolume()) {
            return false;
        }
        Map<String, String> arguments = baseArguments();
        arguments.put("Channel", "Master");
        arguments.put("DesiredMute", muted ? "1" : "0");
        return SoapClient.call(device.renderingControlUrl, device.renderingControlType, "SetMute",
                arguments, clientTag()).success;
    }

    /** Reads state, position, duration, current URI and volume in two round trips. */
    public PlaybackInfo fetchInfo() {
        if (!isAvailable()) {
            return new PlaybackInfo(TransportState.UNKNOWN, 0, 0, null, null, null, -1);
        }
        TransportState state = TransportState.UNKNOWN;
        SoapClient.Response transport = SoapClient.call(device.avTransportUrl,
                device.avTransportType, "GetTransportInfo", baseArguments(), clientTag());
        if (transport.success) {
            state = TransportState.parse(transport.argument("CurrentTransportState"));
        }
        long position = 0;
        long duration = 0;
        String currentUri = null;
        String metadata = null;
        String mediaDuration = null;
        SoapClient.Response positionInfo = SoapClient.call(device.avTransportUrl,
                device.avTransportType, "GetPositionInfo", baseArguments(), clientTag());
        if (positionInfo.success) {
            position = parseTime(positionInfo.argument("RelTime"));
            duration = parseTime(positionInfo.argument("TrackDuration"));
            currentUri = positionInfo.argument("TrackURI");
            metadata = positionInfo.argument("TrackMetaData");
        }
        SoapClient.Response mediaInfo = SoapClient.call(device.avTransportUrl,
                device.avTransportType, "GetMediaInfo", baseArguments(), clientTag());
        if (mediaInfo.success) {
            mediaDuration = mediaInfo.argument("MediaDuration");
            if (duration <= 0) {
                duration = parseTime(mediaDuration);
            }
            if (currentUri == null) {
                currentUri = mediaInfo.argument("CurrentURI");
            }
        }
        return new PlaybackInfo(state, position, duration, currentUri, metadata, mediaDuration,
                getVolume());
    }

    public String transportStateText() {
        return fetchInfo().state.name();
    }

    private boolean invoke(String action, Map<String, String> arguments) {
        if (!isAvailable()) {
            return false;
        }
        SoapClient.Response response = SoapClient.call(device.avTransportUrl, device.avTransportType,
                action, arguments, clientTag());
        if (!response.success) {
            LogBus.get().d(TAG, action + " failed on " + describe() + ": " + response.error);
            return false;
        }
        return response.returnValue();
    }

    private String clientTag() {
        return "Android/MediaBridge UPnP/1.0";
    }

    private String describe() {
        return device == null ? "TV" : device.displayName();
    }

    public static String time(long millis) {
        long totalSeconds = Math.max(0, millis) / 1000;
        return String.format(java.util.Locale.US, "%d:%02d:%02d", totalSeconds / 3600,
                (totalSeconds % 3600) / 60, totalSeconds % 60);
    }

    /** Parses UPnP's H:MM:SS(.fff) - or NOT_IMPLEMENTED. */
    public static long parseTime(String value) {
        if (value == null || value.isEmpty() || value.startsWith("NOT_IMPLEMENTED")) {
            return 0;
        }
        try {
            String[] parts = value.split(":");
            if (parts.length == 3) {
                double hours = Double.parseDouble(parts[0]);
                double minutes = Double.parseDouble(parts[1]);
                double seconds = Double.parseDouble(parts[2]);
                return (long) ((hours * 3600 + minutes * 60 + seconds) * 1000);
            }
            if (parts.length == 2) {
                return (long) ((Double.parseDouble(parts[0]) * 60
                        + Double.parseDouble(parts[1])) * 1000);
            }
            return (long) (Double.parseDouble(value) * 1000);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
