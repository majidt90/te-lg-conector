package com.lgmediabridge.settings;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Typed access to every user-visible preference plus the internal values that
 * must survive process death (the UPnP device UUID, the desired server state).
 *
 * All reads have sane defaults so the app behaves correctly on first launch and
 * after an upgrade that adds new keys.
 */
public final class Settings {

    public enum Theme { SYSTEM, LIGHT, DARK }

    /** Which parts of the library are exposed to the TV. */
    public enum Scope { ALL, VIDEO_PHOTO, AUDIO }

    private static final String FILE = "mediabridge_settings";

    private static final String KEY_SERVER_NAME = "server_name";
    private static final String KEY_PORT = "server_port";
    private static final String KEY_START_ON_BOOT = "start_on_boot";
    private static final String KEY_KEEP_AWAKE = "keep_wifi_awake";
    private static final String KEY_PHOTO_CONVERT = "photo_convert";
    private static final String KEY_AUDIO_CONVERT = "audio_convert";
    private static final String KEY_CONVERT_ON_DEMAND = "convert_on_demand";
    private static final String KEY_HIDE_UNSUPPORTED = "hide_unsupported";
    private static final String KEY_SCOPE = "sharing_scope";
    private static final String KEY_FOLDER_VIEW = "folder_view";
    private static final String KEY_LAN_ONLY = "lan_only";
    private static final String KEY_TRUSTED_ONLY = "trusted_only";
    private static final String KEY_TRUSTED_IPS = "trusted_ips";
    private static final String KEY_THEME = "theme";
    private static final String KEY_SERVER_ENABLED = "server_enabled";
    private static final String KEY_ONBOARDED = "onboarded";
    private static final String KEY_UUID = "server_uuid";
    private static final String KEY_DEFAULT_DEVICE = "default_device";
    private static final String KEY_LAST_STREAM_TITLE = "last_stream_title";
    private static final String KEY_LAST_STREAM_AT = "last_stream_at";
    private static final String KEY_STREAM_NOTIFICATIONS = "stream_notifications";

    public static final int DEFAULT_PORT = 8200;
    public static final String DEFAULT_NAME = "MediaBridge";

    private final SharedPreferences prefs;

    public Settings(Context context) {
        this.prefs = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public SharedPreferences raw() {
        return prefs;
    }

    // ------------------------------------------------------------- server

    public String serverName() {
        String name = prefs.getString(KEY_SERVER_NAME, DEFAULT_NAME);
        if (name == null || name.trim().isEmpty()) {
            return DEFAULT_NAME;
        }
        // Delimiters would break UPnP/SSDP header parsing on the TV side.
        return name.replaceAll("[<>\"'&\\\\\\r\\n\\t]", "").trim();
    }

    /** Alias used by the UPnP layer. */
    public String name() {
        return serverName();
    }

    public void setServerName(String value) {
        prefs.edit().putString(KEY_SERVER_NAME, value).apply();
    }

    public int port() {
        int port = prefs.getInt(KEY_PORT, DEFAULT_PORT);
        return port >= 1024 && port <= 65535 ? port : DEFAULT_PORT;
    }

    public void setPort(int value) {
        prefs.edit().putInt(KEY_PORT, value).apply();
    }

    public boolean serverDesired() {
        return prefs.getBoolean(KEY_SERVER_ENABLED, false);
    }

    public void setServerDesired(boolean value) {
        prefs.edit().putBoolean(KEY_SERVER_ENABLED, value).apply();
    }

    public boolean startOnBoot() {
        return prefs.getBoolean(KEY_START_ON_BOOT, false);
    }

    public void setStartOnBoot(boolean value) {
        prefs.edit().putBoolean(KEY_START_ON_BOOT, value).apply();
    }

    public boolean keepWifiAwake() {
        return prefs.getBoolean(KEY_KEEP_AWAKE, true);
    }

    public void setKeepWifiAwake(boolean value) {
        prefs.edit().putBoolean(KEY_KEEP_AWAKE, value).apply();
    }

    // ---------------------------------------------------------- playback

    public boolean photoConvert() {
        return prefs.getBoolean(KEY_PHOTO_CONVERT, true);
    }

    public void setPhotoConvert(boolean value) {
        prefs.edit().putBoolean(KEY_PHOTO_CONVERT, value).apply();
    }

    public boolean audioConvert() {
        return prefs.getBoolean(KEY_AUDIO_CONVERT, true);
    }

    public void setAudioConvert(boolean value) {
        prefs.edit().putBoolean(KEY_AUDIO_CONVERT, value).apply();
    }

    /** Allow the TV's request to trigger a conversion rather than failing. */
    public boolean convertOnDemand() {
        return prefs.getBoolean(KEY_CONVERT_ON_DEMAND, true);
    }

    public void setConvertOnDemand(boolean value) {
        prefs.edit().putBoolean(KEY_CONVERT_ON_DEMAND, value).apply();
    }

    public boolean hideUnsupported() {
        return prefs.getBoolean(KEY_HIDE_UNSUPPORTED, false);
    }

    public void setHideUnsupported(boolean value) {
        prefs.edit().putBoolean(KEY_HIDE_UNSUPPORTED, value).apply();
    }

    public Scope scope() {
        String value = prefs.getString(KEY_SCOPE, Scope.ALL.name());
        try {
            return Scope.valueOf(value);
        } catch (IllegalArgumentException e) {
            return Scope.ALL;
        }
    }

    public void setScope(Scope scope) {
        prefs.edit().putString(KEY_SCOPE, scope.name()).apply();
    }

    public boolean folderView() {
        return prefs.getBoolean(KEY_FOLDER_VIEW, true);
    }

    public void setFolderView(boolean value) {
        prefs.edit().putBoolean(KEY_FOLDER_VIEW, value).apply();
    }

    // ----------------------------------------------------------- privacy

    public boolean lanOnly() {
        return prefs.getBoolean(KEY_LAN_ONLY, true);
    }

    public void setLanOnly(boolean value) {
        prefs.edit().putBoolean(KEY_LAN_ONLY, value).apply();
    }

    public boolean trustedOnly() {
        return prefs.getBoolean(KEY_TRUSTED_ONLY, false);
    }

    public void setTrustedOnly(boolean value) {
        prefs.edit().putBoolean(KEY_TRUSTED_ONLY, value).apply();
    }

    public Set<String> trustedIps() {
        Set<String> stored = prefs.getStringSet(KEY_TRUSTED_IPS, null);
        return stored == null ? new LinkedHashSet<>() : new LinkedHashSet<>(stored);
    }

    public void setTrustedIps(Set<String> ips) {
        prefs.edit().putStringSet(KEY_TRUSTED_IPS, new LinkedHashSet<>(ips)).apply();
    }

    public void trustIp(String ip) {
        if (ip == null) {
            return;
        }
        Set<String> ips = trustedIps();
        if (ips.add(ip)) {
            setTrustedIps(ips);
        }
    }

    public void distrustIp(String ip) {
        Set<String> ips = trustedIps();
        if (ips.remove(ip)) {
            setTrustedIps(ips);
        }
    }

    // -------------------------------------------------------- appearance

    public Theme theme() {
        String value = prefs.getString(KEY_THEME, Theme.SYSTEM.name());
        try {
            return Theme.valueOf(value);
        } catch (IllegalArgumentException e) {
            return Theme.SYSTEM;
        }
    }

    public void setTheme(Theme theme) {
        prefs.edit().putString(KEY_THEME, theme.name()).apply();
    }

    // ------------------------------------------------------------ misc

    public boolean onboarded() {
        return prefs.getBoolean(KEY_ONBOARDED, false);
    }

    public void setOnboarded(boolean value) {
        prefs.edit().putBoolean(KEY_ONBOARDED, value).apply();
    }

    public boolean streamNotifications() {
        return prefs.getBoolean(KEY_STREAM_NOTIFICATIONS, true);
    }

    public void setStreamNotifications(boolean value) {
        prefs.edit().putBoolean(KEY_STREAM_NOTIFICATIONS, value).apply();
    }

    /** Stable per-installation UUID (the UPnP UDN base). Never regenerated by accident. */
    public String deviceUuid() {
        String uuid = prefs.getString(KEY_UUID, null);
        if (uuid == null || uuid.length() < 16) {
            uuid = UUID.randomUUID().toString();
            prefs.edit().putString(KEY_UUID, uuid).apply();
        }
        return uuid;
    }

    public String defaultDeviceId() {
        return prefs.getString(KEY_DEFAULT_DEVICE, null);
    }

    public void setDefaultDeviceId(String id) {
        prefs.edit().putString(KEY_DEFAULT_DEVICE, id).apply();
    }

    public void recordStream(String title) {
        prefs.edit().putString(KEY_LAST_STREAM_TITLE, title)
                .putLong(KEY_LAST_STREAM_AT, System.currentTimeMillis()).apply();
    }

    public String lastStreamTitle() {
        return prefs.getString(KEY_LAST_STREAM_TITLE, null);
    }

    public long lastStreamAt() {
        return prefs.getLong(KEY_LAST_STREAM_AT, 0L);
    }

    public Set<String> emptySet() {
        return Collections.emptySet();
    }
}
