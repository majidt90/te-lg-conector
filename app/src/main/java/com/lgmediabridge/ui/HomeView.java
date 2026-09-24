package com.lgmediabridge.ui;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.lgmediabridge.App;
import com.lgmediabridge.R;
import com.lgmediabridge.catalog.MediaCatalog;
import com.lgmediabridge.control.TvControl;
import com.lgmediabridge.control.TvDevice;
import com.lgmediabridge.core.Formats;
import com.lgmediabridge.core.LogBus;
import com.lgmediabridge.net.LocalNetwork;
import com.lgmediabridge.server.ServerStatus;
import com.lgmediabridge.service.MediaServerService;
import com.lgmediabridge.settings.Settings;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * The home screen: one hero card that always answers "is my phone shareable right
 * now?", the connected TV with live remote controls when the TV exposes
 * AVTransport, library counters, permission prompts and recent activity.
 */
public final class HomeView {

    private final MainActivity activity;
    private final View root;

    private final View hero;
    private final TextView heroState;
    private final TextView heroDetail;
    private final View heroAddressRow;
    private final TextView heroAddress;
    private final View heroCopy;
    private final Button heroAction;

    private final View deviceCard;
    private final View deviceCardEmpty;
    private final View deviceCardActive;
    private final View deviceDot;
    private final TextView deviceName;
    private final TextView deviceBadge;
    private final TextView deviceMeta;
    private final View deviceNowPlaying;
    private final TextView deviceNowTitle;
    private final ProgressBar deviceNowProgress;
    private final TextView deviceNowPosition;
    private final TextView deviceNowState;
    private final Button deviceOpen;
    private final Button deviceDisconnect;
    private final ImageButton controlPlayPause;

    private final TextView statVideos;
    private final TextView statPhotos;
    private final TextView statMusic;
    private final View libraryWarning;
    private final TextView libraryWarningText;
    private final View libraryWarningAction;
    private final View recentCard;
    private final TextView recentEmpty;
    private final LinearLayout recentRows;
    private final Button openSessions;

    private ScheduledFuture<?> poller;
    private TvControl control;
    private boolean playing;
    private boolean remoteBusy;

    public HomeView(MainActivity activity) {
        this.activity = activity;
        this.root = LayoutInflater.from(activity).inflate(R.layout.view_home, null, false);

        hero = root.findViewById(R.id.hero);
        heroState = root.findViewById(R.id.hero_state);
        heroDetail = root.findViewById(R.id.hero_detail);
        heroAddressRow = root.findViewById(R.id.hero_address_row);
        heroAddress = root.findViewById(R.id.hero_address);
        heroCopy = root.findViewById(R.id.hero_copy);
        heroAction = root.findViewById(R.id.hero_action);

        deviceCard = root.findViewById(R.id.device_card);
        deviceCardEmpty = root.findViewById(R.id.device_card_empty);
        deviceCardActive = root.findViewById(R.id.device_card_active);
        deviceDot = root.findViewById(R.id.device_dot);
        deviceName = root.findViewById(R.id.device_name);
        deviceBadge = root.findViewById(R.id.device_badge);
        deviceMeta = root.findViewById(R.id.device_meta);
        deviceNowPlaying = root.findViewById(R.id.device_now_playing);
        deviceNowTitle = root.findViewById(R.id.device_now_title);
        deviceNowProgress = root.findViewById(R.id.device_now_progress);
        deviceNowPosition = root.findViewById(R.id.device_now_position);
        deviceNowState = root.findViewById(R.id.device_now_state);
        deviceOpen = root.findViewById(R.id.device_open);
        deviceDisconnect = root.findViewById(R.id.device_disconnect);
        controlPlayPause = root.findViewById(R.id.control_play_pause);

        statVideos = root.findViewById(R.id.stat_videos_value);
        statPhotos = root.findViewById(R.id.stat_photos_value);
        statMusic = root.findViewById(R.id.stat_music_value);
        libraryWarning = root.findViewById(R.id.library_warning);
        libraryWarningText = root.findViewById(R.id.library_warning_text);
        libraryWarningAction = root.findViewById(R.id.library_warning_action);
        recentCard = root.findViewById(R.id.recent_card);
        recentEmpty = root.findViewById(R.id.recent_empty);
        recentRows = root.findViewById(R.id.recent_rows);
        openSessions = root.findViewById(R.id.open_sessions);

        wire();
    }

    public View root() {
        return root;
    }

    private void wire() {
        heroAction.setOnClickListener(view -> activity.toggleSharing());
        heroCopy.setOnClickListener(view -> {
            ServerStatus status = App.get().serverStatus();
            if (status.baseUrl != null) {
                Ui.copyToClipboard(activity, activity.getString(R.string.home_address), status.baseUrl);
            }
        });
        deviceOpen.setOnClickListener(view -> activity.openDevicesTab());
        deviceDisconnect.setOnClickListener(view -> activity.disconnectDevice());
        openSessions.setOnClickListener(view -> activity.openSessions());
        root.findViewById(R.id.stat_videos).setOnClickListener(view -> activity.openLibrary(0));
        root.findViewById(R.id.stat_photos).setOnClickListener(view -> activity.openLibrary(1));
        root.findViewById(R.id.stat_music).setOnClickListener(view -> activity.openLibrary(2));

        root.findViewById(R.id.control_stop).setOnClickListener(view -> sendControl("stop"));
        root.findViewById(R.id.control_rewind).setOnClickListener(view -> seekRelative(-30_000));
        root.findViewById(R.id.control_volume_up).setOnClickListener(view -> changeVolume(+5));
        root.findViewById(R.id.control_volume_down).setOnClickListener(view -> changeVolume(-5));
        controlPlayPause.setOnClickListener(view -> {
            if (playing) {
                sendControl("pause");
            } else {
                sendControl("play");
            }
        });
    }

    // --------------------------------------------------------------- state

    public void bindServerStatus(ServerStatus status) {
        Settings settings = App.get().settings();
        if (status.isRunning()) {
            heroState.setText(activity.getString(R.string.home_server_running));
            heroDetail.setText(activity.getString(R.string.home_server_running_body,
                    settings.name(), status.hostPort()));
            heroAction.setText(R.string.action_stop_server);
            heroAction.setEnabled(true);
            heroAddressRow.setVisibility(View.VISIBLE);
            heroAddress.setText(status.baseUrl);
        } else if (status.state == ServerStatus.State.STARTING) {
            heroState.setText(R.string.state_loading);
            heroDetail.setText(R.string.home_server_stopped_body);
            heroAction.setText(R.string.action_stop_server);
            heroAction.setEnabled(false);
            heroAddressRow.setVisibility(View.GONE);
        } else {
            heroState.setText(R.string.home_server_stopped);
            heroDetail.setText(status.error != null ? status.error
                    : activity.getString(R.string.home_server_stopped_body));
            heroAction.setText(R.string.action_start_server);
            heroAction.setEnabled(true);
            heroAddressRow.setVisibility(status.baseUrl == null ? View.GONE : View.VISIBLE);
            if (status.baseUrl != null) {
                heroAddress.setText(status.baseUrl);
            }
        }
    }

    public void bindDevice(TvDevice device) {
        if (device == null) {
            deviceCardEmpty.setVisibility(View.VISIBLE);
            deviceCardActive.setVisibility(View.GONE);
            control = null;
            return;
        }
        deviceCardEmpty.setVisibility(View.GONE);
        deviceCardActive.setVisibility(View.VISIBLE);
        deviceName.setText(device.displayName());
        deviceBadge.setText(device.isLg() ? R.string.chip_lg_webos : R.string.chip_connected);
        StringBuilder meta = new StringBuilder();
        meta.append(device.address == null ? "" : device.address);
        if (device.modelName != null && !device.modelName.isEmpty()) {
            meta.append(" · ").append(device.modelName);
        }
        meta.append(" · ").append(activity.getString(R.string.devices_capabilities))
            .append(": ").append(device.capabilitySummary());
        deviceMeta.setText(meta.toString());
        Ui.dotSeverity(deviceDot, device.supportsRemotePlayback() ? 0 : 1);

        control = new TvControl(device);
        boolean remote = device.supportsRemotePlayback();
        deviceNowPlaying.setVisibility(remote ? View.VISIBLE : View.GONE);
        root.findViewById(R.id.control_volume_up)
                .setEnabled(device.supportsRemoteVolume());
        root.findViewById(R.id.control_volume_down)
                .setEnabled(device.supportsRemoteVolume());
        root.findViewById(R.id.control_rewind).setEnabled(remote);
        root.findViewById(R.id.control_stop).setEnabled(remote);
        controlPlayPause.setEnabled(remote);
    }

    public void bindLibrary(MediaCatalog catalog) {
        List<com.lgmediabridge.catalog.MediaItem> videos = catalog.videos();
        List<com.lgmediabridge.catalog.MediaItem> photos = catalog.photos();
        List<com.lgmediabridge.catalog.MediaItem> audio = catalog.audio();
        statVideos.setText(String.format(Locale.US, "%,d", videos.size()));
        statPhotos.setText(String.format(Locale.US, "%,d", photos.size()));
        statMusic.setText(String.format(Locale.US, "%,d", audio.size()));

        boolean granted = Permissions.hasMediaAccess(activity, App.get().settings());
        boolean partial = Permissions.hasPartialMediaAccess(activity);
        if (!granted) {
            libraryWarning.setVisibility(View.VISIBLE);
            libraryWarningText.setText(partial
                    ? activity.getString(R.string.library_permission_partial)
                    : activity.getString(R.string.library_permission_body));
            libraryWarningAction.setVisibility(View.VISIBLE);
            libraryWarningAction.setOnClickListener(view ->
                    Permissions.request(activity,
                            Permissions.mediaPermissions(App.get().settings()),
                            Permissions.REQUEST_MEDIA));
        } else {
            libraryWarning.setVisibility(View.GONE);
        }
    }

    public void bindRecentStreams() {
        List<com.lgmediabridge.stream.StreamSession> sessions =
                App.get().streams().activeSorted();
        String lastTitle = App.get().settings().lastStreamTitle();
        long lastAt = App.get().settings().lastStreamAt();

        recentRows.removeAllViews();
        boolean hasContent = !sessions.isEmpty() || lastTitle != null;
        recentEmpty.setVisibility(hasContent ? View.GONE : View.VISIBLE);
        recentRows.setVisibility(hasContent ? View.VISIBLE : View.GONE);
        if (!hasContent) {
            recentEmpty.setText(R.string.home_no_streams);
            return;
        }
        LayoutInflater inflater = LayoutInflater.from(activity);
        int shown = 0;
        for (com.lgmediabridge.stream.StreamSession session : sessions) {
            if (shown++ >= 3) {
                break;
            }
            View row = inflater.inflate(R.layout.item_stream, recentRows, false);
            ((TextView) row.findViewById(R.id.stream_title)).setText(
                    session.title == null ? session.mediaId : session.title);
            ((TextView) row.findViewById(R.id.stream_rate)).setText(
                    Formats.rate(session.bytesPerSecond()));
            ((TextView) row.findViewById(R.id.stream_meta)).setText(
                    session.clientLabel + " · " + Formats.bytes(session.totalBytes()));
            ProgressBar progress = row.findViewById(R.id.stream_progress);
            progress.setProgress(Math.max(0, Math.min(100, session.progressPercent())));
            ((TextView) row.findViewById(R.id.stream_state)).setText(session.state());
            ((TextView) row.findViewById(R.id.stream_bytes)).setText(
                    Formats.bytes(session.bytesSent()));
            row.findViewById(R.id.stream_history_row).setVisibility(View.GONE);
            recentRows.addView(row);
        }
        if (sessions.isEmpty() && lastTitle != null) {
            View row = inflater.inflate(R.layout.item_stream, recentRows, false);
            ((TextView) row.findViewById(R.id.stream_title)).setText(lastTitle);
            ((TextView) row.findViewById(R.id.stream_rate)).setText("");
            ((TextView) row.findViewById(R.id.stream_meta)).setText(
                    activity.getString(R.string.home_last_stream, Formats.timeAgo(activity, lastAt)));
            row.findViewById(R.id.stream_progress).setVisibility(View.GONE);
            ((TextView) row.findViewById(R.id.stream_state)).setText(R.string.action_stop);
            ((TextView) row.findViewById(R.id.stream_bytes)).setText("");
            recentRows.addView(row);
        }
    }

    public void bindNetwork(LocalNetwork.State state) {
        if (!state.connected && heroAddressRow.getVisibility() != View.VISIBLE) {
            heroDetail.setText(R.string.error_no_wifi);
        }
    }

    // ------------------------------------------------------- remote control

    private void sendControl(String what) {
        TvControl current = control;
        if (current == null || remoteBusy) {
            return;
        }
        remoteBusy = true;
        App.get().runOnControlPool(() -> {
            boolean ok;
            switch (what) {
                case "play": ok = current.play(); break;
                case "pause": ok = current.pause(); break;
                default: ok = current.stop(); break;
            }
            remoteBusy = false;
            final boolean success = ok;
            root.post(() -> {
                if (!success) {
                    Ui.toast(activity, activity.getString(R.string.error_offline_device,
                            activity.getString(R.string.nav_devices)));
                }
                refreshRemoteState();
            });
        });
    }

    private void seekRelative(long deltaMs) {
        final TvControl current = control;
        if (current == null) {
            return;
        }
        App.get().runOnControlPool(() -> {
            TvControl.PlaybackInfo info = current.fetchInfo();
            long target = Math.max(0, info.positionMs + deltaMs);
            current.seek(target);
            root.post(this::refreshRemoteState);
        });
    }

    private void changeVolume(int delta) {
        final TvControl current = control;
        if (current == null) {
            return;
        }
        App.get().runOnControlPool(() -> {
            int volume = current.getVolume();
            if (volume < 0) {
                return;
            }
            current.setVolume(volume + delta);
            final int applied = Math.max(0, Math.min(100, volume + delta));
            root.post(() -> Ui.toast(activity, activity.getString(R.string.sessions_volume)
                    + ": " + applied + "%"));
        });
    }

    /** Polls the TV while the home tab is visible; stops when it is not. */
    public void startPolling() {
        stopPolling();
        poller = App.get().scheduler().scheduleWithFixedDelay(this::refreshRemoteStateQuietly,
                1, 4, TimeUnit.SECONDS);
    }

    public void stopPolling() {
        if (poller != null) {
            poller.cancel(false);
            poller = null;
        }
    }

    private void refreshRemoteStateQuietly() {
        try {
            refreshRemoteState();
        } catch (Throwable t) {
            LogBus.get().d("HomeView", "remote poll failed: " + t);
        }
    }

    public void refreshRemoteState() {
        final TvControl current = control;
        if (current == null || !current.isAvailable()) {
            deviceNowPlaying.setVisibility(View.GONE);
            return;
        }
        App.get().runOnControlPool(() -> {
            TvControl.PlaybackInfo info = current.fetchInfo();
            root.post(() -> applyPlayback(info));
        });
    }

    private void applyPlayback(TvControl.PlaybackInfo info) {
        playing = info.state == TvControl.TransportState.PLAYING;
        controlPlayPause.setImageResource(playing
                ? R.drawable.ic_pause : R.drawable.ic_play);
        deviceNowPlaying.setVisibility(View.VISIBLE);
        String title = info.currentUri == null ? activity.getString(R.string.sessions_no_renderer)
                : Uri.parse(info.currentUri).getLastPathSegment();
        deviceNowTitle.setText(title == null ? info.state.name() : title);
        deviceNowProgress.setProgress(info.progressPercent());
        deviceNowPosition.setText(TvControl.time(info.positionMs) + " / "
                + TvControl.time(info.durationMs));
        StringBuilder state = new StringBuilder(info.state.name().toLowerCase(Locale.US)
                .replace('_', ' '));
        if (info.volume >= 0) {
            state.append(" · ").append(activity.getString(R.string.sessions_volume))
                    .append(' ').append(info.volume).append('%');
        }
        deviceNowState.setText(state.toString());
    }

    public void openMediaOnTv(String url, String didl, String mime) {
        final TvControl current = control;
        if (current == null || !current.isAvailable()) {
            Ui.toast(activity, activity.getString(R.string.sessions_no_renderer));
            return;
        }
        App.get().runOnControlPool(() -> {
            boolean ok = current.playMedia(url, didl, mime);
            root.post(() -> {
                if (ok) {
                    Ui.toast(activity, activity.getString(R.string.state_success));
                    refreshRemoteState();
                } else {
                    Ui.toast(activity, activity.getString(R.string.error_offline_device,
                            activity.getString(R.string.nav_devices)));
                }
            });
        });
    }

    public static void openOnPhone(Activity activity, com.lgmediabridge.catalog.MediaItem item) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(Uri.parse(item.uri), item.mimeType);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(intent);
        } catch (Exception e) {
            Ui.toast(activity, activity.getString(R.string.state_error_title));
        }
    }

    public void onSharingToggled() {
        bindServerStatus(App.get().serverStatus());
    }

    public void refreshToggle() {
        bindServerStatus(App.get().serverStatus());
        bindDevice(activity.connectedDevice());
        bindRecentStreams();
    }

    public static void startSharingService(Activity activity) {
        Intent intent = new Intent(activity, MediaServerService.class);
        intent.setAction(MediaServerService.ACTION_START);
        try {
            activity.startForegroundService(intent);
        } catch (Exception e) {
            LogBus.get().w("HomeView", "could not start the service: " + e.getMessage());
        }
    }
}
