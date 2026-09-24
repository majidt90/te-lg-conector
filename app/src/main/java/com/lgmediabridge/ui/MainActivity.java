package com.lgmediabridge.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import com.lgmediabridge.App;
import com.lgmediabridge.R;
import com.lgmediabridge.catalog.MediaCatalog;
import com.lgmediabridge.compat.MediaCompat;
import com.lgmediabridge.control.TvDevice;
import com.lgmediabridge.core.Formats;
import com.lgmediabridge.core.LogBus;
import com.lgmediabridge.dlna.ContentDirectory;
import com.lgmediabridge.net.LocalNetwork;
import com.lgmediabridge.server.ServerStatus;
import com.lgmediabridge.service.MediaServerService;
import com.lgmediabridge.settings.Settings;
import com.lgmediabridge.stream.StreamRegistry;

import java.util.List;

/**
 * The single activity that owns the four main tabs.
 *
 * Design notes:
 * <ul>
 *   <li>one activity, four views: switching tabs never restarts the catalog or
 *       the server, and the header keeps showing live state no matter where the
 *       user is;</li>
 *   <li>the header strip is the app's pulse: green while sharing, amber while
 *       something needs attention, grey when stopped;</li>
 *   <li>the activity is only the coordinator - every screen is a self-contained
 *       view class that can be tested and reasoned about on its own.</li>
 * </ul>
 */
public final class MainActivity extends android.app.Activity
        implements MediaServerService.Listener, StreamRegistry.Listener, MediaCatalog.Listener,
        LocalNetwork.Listener {

    private static final String TAG = "Main";
    private static final int REQUEST_ALL = 2001;

    private FrameLayout content;
    private TextView title;
    private TextView subtitle;
    private View statusStrip;

    private HomeView homeView;
    private LibraryView libraryView;
    private DevicesView devicesView;
    private SettingsView settingsView;

    private View[] tabs;
    private ImageView[] tabIcons;
    private TextView[] tabLabels;

    private int currentTab = -1;
    private TvDevice connected;
    private String connectedId;

    @Override protected void onCreate(Bundle savedInstanceState) {
        Ui.applyTheme(this);
        super.onCreate(savedInstanceState);
        Settings settings = App.get().settings();
        if (!settings.onboarded()) {
            startActivity(new Intent(this, OnboardingActivity.class));
            finish();
            return;
        }
        setContentView(R.layout.activity_main);

        content = findViewById(R.id.content);
        title = findViewById(R.id.title);
        subtitle = findViewById(R.id.subtitle);
        statusStrip = findViewById(R.id.status_strip);

        tabs = new View[]{findViewById(R.id.tab_home), findViewById(R.id.tab_library),
                findViewById(R.id.tab_devices), findViewById(R.id.tab_settings)};
        tabIcons = new ImageView[]{findViewById(R.id.tab_home_icon),
                findViewById(R.id.tab_library_icon), findViewById(R.id.tab_devices_icon),
                findViewById(R.id.tab_settings_icon)};
        tabLabels = new TextView[]{findViewById(R.id.tab_home_label),
                findViewById(R.id.tab_library_label), findViewById(R.id.tab_devices_label),
                findViewById(R.id.tab_settings_label)};

        tabs[0].setOnClickListener(view -> selectTab(0));
        tabs[1].setOnClickListener(view -> selectTab(1));
        tabs[2].setOnClickListener(view -> selectTab(2));
        tabs[3].setOnClickListener(view -> selectTab(3));

        findViewById(R.id.button_sessions).setOnClickListener(view -> openSessions());
        findViewById(R.id.button_diagnostics).setOnClickListener(view ->
                startActivity(new Intent(this, DiagnosticsActivity.class)));

        homeView = new HomeView(this);
        libraryView = new LibraryView(this);
        devicesView = new DevicesView(this);
        settingsView = new SettingsView(this);

        restoreConnectedDevice();
        selectTab(savedInstanceState == null ? 0
                : savedInstanceState.getInt("tab", 0));

        MediaServerService.addListener(this);
        App.get().streams().addListener(this);
        App.get().catalog().addListener(this);
        LocalNetwork.addListener(this);
        requestStartupPermissions();

        // If the service was already running (process was alive), reflect that
        // immediately instead of showing "stopped" until the first callback.
        if (MediaServerService.isRunning()) {
            onStatusChanged(App.get().serverStatus());
        }
    }

    @Override protected void onResume() {
        super.onResume();
        App.get().catalog().start();
        onStatusChanged(App.get().serverStatus());
        homeView.bindLibrary(App.get().catalog());
        homeView.bindRecentStreams();
        homeView.startPolling();
        if (currentTab == 1) {
            libraryView.start();
        }
    }

    @Override protected void onPause() {
        super.onPause();
        homeView.stopPolling();
        libraryView.stop();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        MediaServerService.removeListener(this);
        App.get().streams().removeListener(this);
        App.get().catalog().removeListener(this);
        LocalNetwork.removeListener(this);
        if (devicesView != null) {
            devicesView.destroy();
        }
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt("tab", Math.max(0, currentTab));
    }

    // ------------------------------------------------------------------ tabs

    private void selectTab(int index) {
        if (index == currentTab) {
            if (index == 1) {
                libraryView.start();
            }
            return;
        }
        currentTab = index;
        View view;
        switch (index) {
            case 1:
                view = libraryView.root();
                libraryView.start();
                title.setText(R.string.nav_library);
                break;
            case 2:
                view = devicesView.root();
                devicesView.refresh();
                title.setText(R.string.nav_devices);
                break;
            case 3:
                view = settingsView.root();
                settingsView.refresh();
                title.setText(R.string.nav_settings);
                break;
            default:
                view = homeView.root();
                homeView.refreshToggle();
                title.setText(R.string.nav_home);
                break;
        }
        content.removeAllViews();
        content.addView(view, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        view.startAnimation(android.view.animation.AnimationUtils.loadAnimation(this,
                R.anim.fade_in_slight));

        for (int i = 0; i < tabs.length; i++) {
            boolean selected = i == index;
            tabLabels[i].setTextColor(getResources().getColor(
                    selected ? R.color.primary : R.color.text_tertiary, null));
            Ui.tint(tabIcons[i], getResources().getColor(
                    selected ? R.color.primary : R.color.text_tertiary, null));
            Ui.setSelected(tabLabels[i], selected);
        }
        if (index == 1) {
            libraryView.stop();
            libraryView.start();
        }
        if (index == 2) {
            devicesView.start();
        } else {
            devicesView.stop();
        }
    }

    /** Called from the home screen counters, to jump into a filtered library. */
    public void openLibrary(int kindIndex) {
        selectTab(1);
        libraryView.select(kindIndex);
    }

    public void openDevicesTab() {
        selectTab(2);
    }

    public void openSessions() {
        startActivity(new Intent(this, SessionsActivity.class));
    }

    // ------------------------------------------------------------- sharing

    public void toggleSharing() {
        boolean running = App.get().serverStatus().isRunning();
        if (running) {
            com.lgmediabridge.service.Broadcasts.stop(this);
        } else {
            App.get().settings().setServerDesired(true);
            homeView.onSharingToggled();
            com.lgmediabridge.ui.HomeView.startSharingService(this);
        }
    }

    public void restartServerIfRunning() {
        if (!MediaServerService.isRunning()) {
            return;
        }
        Intent intent = new Intent(this, MediaServerService.class);
        intent.setAction(MediaServerService.ACTION_RESTART);
        startService(intent);
    }

    // ------------------------------------------------------------- devices

    private void restoreConnectedDevice() {
        String id = App.get().settings().defaultDeviceId();
        if (id != null) {
            connected = App.get().tvStore().byId(id);
            connectedId = connected == null ? null : connected.effectiveId();
        }
        homeView.bindDevice(connected);
    }

    public String connectedId() {
        return connectedId;
    }

    public TvDevice connectedDevice() {
        return connected;
    }

    public void connectDevice(TvDevice device) {
        App.get().tvStore().save(device);
        connected = device;
        connectedId = device.effectiveId();
        App.get().settings().setDefaultDeviceId(connectedId);
        homeView.bindDevice(connected);
        devicesView.refresh();
        Ui.toast(this, getString(R.string.devices_connected, device.displayName()));
        LogBus.get().i(TAG, "connected to " + device.displayName() + " (" + connectedId + ")");
    }

    public void disconnectDevice() {
        if (connected != null) {
            LogBus.get().i(TAG, "disconnected from " + connected.displayName());
        }
        connected = null;
        connectedId = null;
        App.get().settings().setDefaultDeviceId(null);
        homeView.bindDevice(null);
        devicesView.refresh();
    }

    public void forgetDevice(TvDevice device) {
        App.get().tvStore().forget(device.effectiveId());
        if (device.effectiveId().equals(connectedId)) {
            disconnectDevice();
        }
        Ui.toast(this, getString(R.string.devices_forgot, device.displayName()));
    }

    /** Hands a media file to the connected TV through UPnP AVTransport. */
    public void playOnTv(com.lgmediabridge.catalog.MediaItem item) {
        ServerStatus status = App.get().serverStatus();
        if (!status.isRunning()) {
            Ui.toast(this, getString(R.string.error_server_off));
            return;
        }
        if (connected == null || !connected.supportsRemotePlayback()) {
            Ui.toast(this, getString(R.string.sessions_no_renderer));
            return;
        }
        String mime = MediaCompat.effectiveMime(item, App.get().compat().quick(item));
        String metadata = MediaDetailSheet.metadataFor(item, status.baseUrl, mime);
        String url = ContentDirectory.mediaUrl(status.baseUrl, item, mime);
        homeView.openMediaOnTv(url, metadata, mime);
        selectTab(0);
    }

    // ----------------------------------------------------------- callbacks

    @Override public void onStatusChanged(ServerStatus status) {
        runOnUiThread(() -> {
            homeView.bindServerStatus(status);
            if (status.isRunning()) {
                subtitle.setText(getString(R.string.home_server_running) + " · "
                        + status.hostPort() + " · "
                        + getString(R.string.home_clients, status.requestsServed));
            } else if (status.state == ServerStatus.State.STARTING) {
                subtitle.setText(R.string.state_loading);
            } else if (status.state == ServerStatus.State.ERROR && status.error != null) {
                subtitle.setText(status.error);
            } else {
                subtitle.setText(R.string.home_server_stopped);
            }
            int color;
            if (status.isRunning()) {
                color = R.color.success;
            } else if (status.state == ServerStatus.State.ERROR) {
                color = R.color.error;
            } else if (status.state == ServerStatus.State.STARTING) {
                color = R.color.warning;
            } else {
                color = R.color.text_tertiary;
            }
            statusStrip.setBackgroundColor(getResources().getColor(color, null));
        });
    }

    @Override public void onNetworkChanged(LocalNetwork.State state) {
        runOnUiThread(() -> homeView.bindNetwork(state));
    }

    @Override public void onStreamsChanged(List<com.lgmediabridge.stream.StreamSession> sessions) {
        runOnUiThread(() -> {
            homeView.bindRecentStreams();
            if (sessions != null && !sessions.isEmpty()) {
                subtitle.setText(getString(R.string.sessions_active, sessions.size()));
            } else {
                ServerStatus idle = App.get().serverStatus();
                subtitle.setText(idle.isRunning() ? R.string.home_server_running
                        : R.string.home_server_stopped);
            }
        });
    }

    @Override public void onCatalogChanged(MediaCatalog catalog) {
        runOnUiThread(() -> {
            homeView.bindLibrary(catalog);
            if (currentTab == 0) {
                homeView.bindRecentStreams();
            }
        });
    }

    // --------------------------------------------------------- permissions

    private void requestStartupPermissions() {
        String[] needed = Permissions.mediaPermissions(App.get().settings());
        boolean missingMedia = !Permissions.hasMediaAccess(this, App.get().settings());
        boolean missingNotifications = Permissions.needsNotificationPermission(this);
        if (!missingMedia && !missingNotifications) {
            return;
        }
        java.util.List<String> request = new java.util.ArrayList<>();
        if (missingMedia) {
            java.util.Collections.addAll(request, needed);
        }
        if (missingNotifications) {
            request.add(android.Manifest.permission.POST_NOTIFICATIONS);
        }
        Permissions.request(this, request.toArray(new String[0]), REQUEST_ALL);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                                     int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_ALL) {
            return;
        }
        boolean granted = Permissions.hasMediaAccess(this, App.get().settings());
        if (granted) {
            App.get().catalog().refresh(true);
        } else if (Permissions.hasPartialMediaAccess(this)) {
            Ui.toast(this, getString(R.string.library_permission_partial));
            App.get().catalog().refresh(true);
        } else {
            Ui.toast(this, getString(R.string.error_permission_media));
        }
        homeView.bindLibrary(App.get().catalog());
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
    }

    /** Convenience used by the header: how long the server has been up. */
    public String uptimeText() {
        ServerStatus status = App.get().serverStatus();
        return status.isRunning() ? Formats.elapsed(System.currentTimeMillis() - status.startedAt)
                : "—";
    }
}
