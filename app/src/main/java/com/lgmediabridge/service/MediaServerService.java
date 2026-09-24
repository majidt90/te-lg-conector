package com.lgmediabridge.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import com.lgmediabridge.App;
import com.lgmediabridge.R;
import com.lgmediabridge.catalog.MediaCatalog;
import com.lgmediabridge.core.Formats;
import com.lgmediabridge.core.LogBus;
import com.lgmediabridge.dlna.ContentDirectory;
import com.lgmediabridge.net.LocalNetwork;
import com.lgmediabridge.server.MediaServerRuntime;
import com.lgmediabridge.server.ServerStatus;
import com.lgmediabridge.settings.Settings;
import com.lgmediabridge.transcode.AudioTranscoder;
import com.lgmediabridge.transcode.PhotoTranscoder;
import com.lgmediabridge.ui.MainActivity;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Keeps media sharing alive while the phone is locked or the app is in the
 * background - which is the normal case, since the TV is the screen the user is
 * watching.
 *
 * Responsibilities:
 * <ul>
 *   <li>runs the DLNA server ({@link MediaServerRuntime}) in the foreground, as a
 *       {@code dataSync} foreground service, so Android does not freeze it;</li>
 *   <li>holds a {@code MulticastLock} (SSDP replies are otherwise dropped when the
 *       screen turns off) and, when the user asks for it, a {@code WifiLock};</li>
 *   <li>watches {@link LocalNetwork} so a new IP address or a dropped Wi-Fi
 *       connection rebinds the server and re-announces it;</li>
 *   <li>publishes an ongoing notification with live transfer status, plus a
 *       "Stop sharing" action.</li>
 * </ul>
 */
public final class MediaServerService extends Service
        implements LocalNetwork.Listener, com.lgmediabridge.stream.StreamRegistry.Listener {

    private static final String TAG = "ServerService";
    private static final String CHANNEL_ID = "sharing";
    public static final int NOTIFICATION_ID = 4711;

    public static final String ACTION_START = "com.lgmediabridge.action.START";
    public static final String ACTION_STOP = "com.lgmediabridge.action.STOP";
    public static final String ACTION_REFRESH = "com.lgmediabridge.action.REFRESH";
    public static final String ACTION_RESTART = "com.lgmediabridge.action.RESTART";

    public interface Listener {
        void onStatusChanged(ServerStatus status);

        void onNetworkChanged(LocalNetwork.State state);
    }

    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static volatile boolean running;

    private MediaServerRuntime runtime;
    private WifiManager.MulticastLock multicastLock;
    private WifiManager.WifiLock wifiLock;
    private PowerManager.WakeLock wakeLock;
    private ScheduledExecutorService ticker;
    private String currentAddress;
    private long lastStatusNotificationAt;
    private boolean foregroundStarted;
    private int consecutiveFailures;

    public static void addListener(Listener listener) {
        if (listener != null) {
            LISTENERS.add(listener);
        }
    }

    public static void removeListener(Listener listener) {
        LISTENERS.remove(listener);
    }

    /** True while the service believes the server is up. */
    public static boolean isRunning() {
        return running;
    }

    public static ServerStatus currentStatus() {
        return App.get().serverStatus();
    }

    // ------------------------------------------------------------ lifecycle

    @Override public void onCreate() {
        super.onCreate();
        LogBus.get().i(TAG, "service created");
        createNotificationChannel();
        // Registered here rather than when sharing starts: if there is no Wi-Fi
        // yet, this callback is what starts sharing as soon as there is.
        LocalNetwork.addListener(this);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            LogBus.get().i(TAG, "stop requested");
            // If this arrived through startForegroundService the OS expects a
            // startForeground() call; doing it before stopping keeps that
            // contract and makes the teardown notification-free.
            startForegroundSafely();
            stopSharing(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_RESTART.equals(action)) {
            LogBus.get().w(TAG, "restart requested");
            stopSharing(false);
            startSharing();
            return START_STICKY;
        }
        if (ACTION_REFRESH.equals(action)) {
            if (running) {
                updateNotification();
            } else {
                startSharing();
            }
            return START_STICKY;
        }
        startSharing();
        return START_STICKY;
    }

    @Override public void onDestroy() {
        LogBus.get().i(TAG, "service destroyed");
        stopSharing(false);
        LocalNetwork.removeListener(this);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null; // started service only
    }

    // -------------------------------------------------------------- sharing

    private void startSharing() {
        if (running) {
            updateNotification();
            return;
        }
        App app = App.get();
        Settings settings = app.settings();
        if (!settings.serverDesired()) {
            LogBus.get().i(TAG, "sharing is switched off in settings; not starting");
            startForegroundSafely();
            stopSharing(false);
            stopSelf();
            return;
        }
        LocalNetwork.State state = LocalNetwork.state(this);
        if (!state.connected || state.ipAddress == null) {
            LogBus.get().w(TAG, "no local network connection: " + state.describe());
            app.setServerStatus(ServerStatus.error(getString(R.string.error_no_wifi)));
            publishStatus();
            // Stay alive: the network callback will start sharing as soon as Wi-Fi returns.
            startForegroundSafely();
            scheduleTicker();
            return;
        }

        startForegroundSafely();
        acquireLocks();
        ContentDirectory directory = app.contentDirectory();
        MediaCatalog catalog = app.catalog();
        PhotoTranscoder photos = app.photoTranscoder();
        AudioTranscoder audio = app.audioTranscoder();
        runtime = new MediaServerRuntime(this, settings, catalog, app.compat(), directory,
                app.streams(), photos, audio);
        currentAddress = state.ipAddress;
        try {
            runtime.start(state.ipAddress, state.interfaceName);
            running = true;
            consecutiveFailures = 0;
            app.setServerStatus(runtime.status());
            LogBus.get().i(TAG, "sharing started on " + state.ipAddress + ":"
                    + settings.port());
            app.streams().addListener(this);
            // The catalog may not have been opened by any screen yet (boot start).
            app.catalog().start();
        } catch (Exception e) {
            consecutiveFailures++;
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            LogBus.get().e(TAG, "could not start sharing on port " + settings.port()
                    + ": " + message, e);
            app.setServerStatus(ServerStatus.error(message));
            if (runtime != null) {
                runtime.setLastError(message);
                runtime.stop();
                runtime = null;
            }
            // Do not give up: the ticker below retries, so a port that is
            // temporarily busy or a network that is still settling self-heals.
        }
        publishStatus();
        updateNotification();
        scheduleTicker();
    }

    private void stopSharing(boolean remember) {
        if (ticker != null) {
            ticker.shutdownNow();
            ticker = null;
        }
        if (runtime != null) {
            runtime.stop();
            runtime = null;
        }
        running = false;
        App app = App.get();
        app.streams().removeListener(this);
        app.streams().stopAll();
        app.setServerStatus(ServerStatus.stopped());
        if (remember) {
            app.settings().setServerDesired(false);
        }
        releaseLocks();
        if (foregroundStarted) {
            stopForeground(true);
            foregroundStarted = false;
        }
        publishStatus();
        LogBus.get().i(TAG, "sharing stopped");
    }

    private void acquireLocks() {
        // Multicast reception is dropped while the screen is off unless a lock is held.
        WifiManager wifi = (WifiManager) getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        if (wifi != null) {
            try {
                multicastLock = wifi.createMulticastLock("mediabridge-multicast");
                multicastLock.setReferenceCounted(false);
                multicastLock.acquire();
            } catch (Exception e) {
                LogBus.get().w(TAG, "multicast lock not available: " + e.getMessage());
            }
            if (App.get().settings().keepWifiAwake()) {
                try {
                    wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                            "mediabridge-wifi");
                    wifiLock.setReferenceCounted(false);
                    wifiLock.acquire();
                    LogBus.get().i(TAG, "holding a Wi-Fi lock so transfers are not batched");
                } catch (Exception e) {
                    LogBus.get().d(TAG, "wifi lock not available: " + e.getMessage());
                }
            }
        }
        try {
            PowerManager power = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (power != null) {
                wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mediabridge:stream");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire();
            }
        } catch (Exception e) {
            LogBus.get().d(TAG, "wake lock not available: " + e.getMessage());
        }
    }

    private void releaseLocks() {
        if (wakeLock != null && wakeLock.isHeld()) {
            try {
                wakeLock.release();
            } catch (RuntimeException ignored) {
                // already released
            }
        }
        wakeLock = null;
        if (wifiLock != null && wifiLock.isHeld()) {
            wifiLock.release();
        }
        wifiLock = null;
        if (multicastLock != null && multicastLock.isHeld()) {
            multicastLock.release();
        }
        multicastLock = null;
    }

    private void scheduleTicker() {
        if (ticker != null && !ticker.isShutdown()) {
            return;
        }
        ticker = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "server-service");
            thread.setDaemon(true);
            return thread;
        });
        ticker.scheduleWithFixedDelay(this::tick, 5, 5, TimeUnit.SECONDS);
    }

    /** Periodic housekeeping: status refresh, cache trimming, notification update. */
    private void tick() {
        try {
            if (runtime == null) {
                if (!running && App.get().settings().serverDesired()) {
                    LocalNetwork.State state = LocalNetwork.state(this);
                    if (state.connected && state.ipAddress != null) {
                        LogBus.get().i(TAG, "retrying sharing start");
                        startSharing();
                    }
                }
                return;
            }
            App app = App.get();
            ServerStatus status = runtime.status();
            app.setServerStatus(status);
            publishStatus();
            app.streams().evictStale();
            if (app.streams().hasActiveStreams()) {
                updateNotification();
            } else if (System.currentTimeMillis() - lastStatusNotificationAt > 60_000) {
                updateNotification();
            }
            app.photoTranscoder().trimCache(96L * 1024 * 1024);
            LocalNetwork.State state = LocalNetwork.state(this);
            if (state.ipAddress != null && !state.ipAddress.equals(currentAddress)) {
                LogBus.get().w(TAG, "local address changed to " + state.ipAddress);
                rebind(state);
            }
        } catch (Throwable t) {
            LogBus.get().w(TAG, "housekeeping failed: " + t);
        }
    }

    private void rebind(LocalNetwork.State state) {
        try {
            runtime.rebind(state.ipAddress, state.interfaceName);
            currentAddress = state.ipAddress;
            App.get().setServerStatus(runtime.status());
            publishStatus();
        } catch (Exception e) {
            LogBus.get().e(TAG, "rebinding failed", e);
            running = false;
            if (runtime != null) {
                runtime.stop();
                runtime = null;
            }
        }
    }

    // ------------------------------------------------------------ listeners

    @Override public void onNetworkChanged(LocalNetwork.State state) {
        LogBus.get().i(TAG, "network changed: " + state.describe());
        publishNetwork(state);
        if (state.connected && state.ipAddress != null) {
            if (!running) {
                startSharing();
            } else if (!state.ipAddress.equals(currentAddress)) {
                rebind(state);
            }
        } else {
            LogBus.get().w(TAG, "network lost; keeping the service for when it returns");
        }
    }

    @Override public void onStreamsChanged(
            java.util.List<com.lgmediabridge.stream.StreamSession> sessions) {
        updateNotification();
    }

    // --------------------------------------------------------- notification

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.notif_channel_desc));
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    private void startForegroundSafely() {
        if (foregroundStarted) {
            return;
        }
        Notification notification = buildNotification();
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            foregroundStarted = true;
        } catch (Exception e) {
            LogBus.get().w(TAG, "could not enter the foreground: " + e.getMessage());
        }
    }

    private void updateNotification() {
        if (!foregroundStarted) {
            return;
        }
        lastStatusNotificationAt = System.currentTimeMillis();
        NotificationManager manager = (NotificationManager)
                getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification());
        }
    }

    private Notification buildNotification() {
        App app = App.get();
        ServerStatus status = app.serverStatus();
        int active = app.streams().activeCount();

        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openIntent = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stop = new Intent(this, ServiceActionReceiver.class);
        stop.setAction(ACTION_STOP);
        PendingIntent stopIntent = PendingIntent.getBroadcast(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String title;
        String text;
        if (active > 0) {
            java.util.List<com.lgmediabridge.stream.StreamSession> sessions =
                    app.streams().activeSorted();
            com.lgmediabridge.stream.StreamSession session = sessions.isEmpty()
                    ? null : sessions.get(0);
            String detail = session == null ? Formats.rate(app.streams().totalBytesPerSecond())
                    : (session.title == null ? "" : session.title) + " · "
                    + Formats.rate(session.bytesPerSecond());
            title = getString(R.string.notif_title);
            text = getString(R.string.notif_text_streaming, active, detail);
        } else if (status.isRunning()) {
            title = getString(R.string.notif_title);
            text = getString(R.string.notif_text_idle, status.hostPort());
        } else {
            title = getString(R.string.notif_title);
            text = status.describe();
        }

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        builder.setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setOngoing(true)
                .setShowWhen(false)
                .setContentIntent(openIntent)
                .addAction(new Notification.Action.Builder(null,
                        getString(R.string.notif_action_stop), stopIntent).build());
        if (status.isRunning()) {
            builder.setSubText(status.hostPort());
        }
        return builder.build();
    }

    private void publishStatus() {
        ServerStatus status = App.get().serverStatus();
        for (Listener listener : LISTENERS) {
            listener.onStatusChanged(status);
        }
    }

    private void publishNetwork(LocalNetwork.State state) {
        for (Listener listener : LISTENERS) {
            listener.onNetworkChanged(state);
        }
    }
}
