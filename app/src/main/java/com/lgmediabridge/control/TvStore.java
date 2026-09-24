package com.lgmediabridge.control;

import android.content.Context;
import android.content.SharedPreferences;

import com.lgmediabridge.core.LogBus;
import com.lgmediabridge.security.SecureStore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Remembers TVs across sessions: names, addresses and - encrypted with the
 * Android Keystore - the webOS client keys handed out during pairing.
 *
 * Pairing keys are credentials: whoever holds one can control the TV. They are
 * therefore never written in clear text and are excluded from backup by the
 * app's data-extraction rules.
 */
public final class TvStore {

    private static final String TAG = "TvStore";
    private static final String FILE = "mediabridge_devices";
    private static final String KEY_DEVICES = "devices";
    private static final String KEY_KEYS = "client_keys";

    private final SharedPreferences prefs;
    private final SecureStore secureStore;
    private final Map<String, TvDevice> devices = new LinkedHashMap<>();

    public TvStore(Context context) {
        Context appContext = context.getApplicationContext();
        this.prefs = appContext.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        this.secureStore = new SecureStore(appContext);
        load();
    }

    private synchronized void load() {
        devices.clear();
        String blob = prefs.getString(KEY_DEVICES, null);
        if (blob != null) {
            Map<String, Object> map = com.lgmediabridge.core.Json.parseObject(blob);
            if (map != null) {
                for (Map.Entry<String, Object> entry : map.entrySet()) {
                    TvDevice device = TvDevice.fromJson(String.valueOf(entry.getValue()));
                    if (device != null) {
                        devices.put(entry.getKey(), device);
                    }
                }
            }
        }
        Map<String, Object> keys = com.lgmediabridge.core.Json.parseObject(
                prefs.getString(KEY_KEYS, "{}"));
        if (keys != null) {
            for (Map.Entry<String, Object> entry : keys.entrySet()) {
                String id = entry.getKey();
                TvDevice device = devices.get(id);
                if (device != null) {
                    String key = secureStore.decrypt(String.valueOf(entry.getValue()));
                    if (key != null) {
                        device.clientKey = key;
                        device.paired = true;
                    }
                }
            }
        }
        LogBus.get().d(TAG, "loaded " + devices.size() + " saved device(s)");
    }

    /** All known TVs, most recently used first. */
    public synchronized List<TvDevice> all() {
        List<TvDevice> list = new ArrayList<>(devices.values());
        Collections.sort(list, (a, b) -> Long.compare(b.lastSeenAt, a.lastSeenAt));
        return list;
    }

    public synchronized TvDevice byId(String id) {
        return id == null ? null : devices.get(id);
    }

    /** Stores or updates a device; pairing keys survive updates. */
    public synchronized void save(TvDevice device) {
        if (device == null) {
            return;
        }
        String id = device.effectiveId();
        device.id = id;
        TvDevice existing = devices.get(id);
        if (existing != null) {
            String key = existing.clientKey;
            boolean paired = existing.paired;
            existing.mergeFrom(device);
            if (key != null && existing.clientKey == null) {
                existing.clientKey = key;
                existing.paired = paired;
            }
        } else {
            devices.put(id, device);
        }
        persist();
    }

    public synchronized void storeClientKey(TvDevice device, String clientKey) {
        if (device == null || clientKey == null) {
            return;
        }
        device.clientKey = clientKey;
        device.paired = true;
        device.id = device.effectiveId();
        devices.put(device.id, device);
        Map<String, Object> encrypted = new LinkedHashMap<>();
        String blob = prefs.getString(KEY_KEYS, "{}");
        Map<String, Object> existing = com.lgmediabridge.core.Json.parseObject(blob);
        if (existing != null) {
            encrypted.putAll(existing);
        }
        String envelope = secureStore.encrypt(clientKey);
        if (envelope == null) {
            LogBus.get().w(TAG, "keystore unavailable - pairing key not stored");
            return;
        }
        encrypted.put(device.id, envelope);
        prefs.edit().putString(KEY_KEYS, com.lgmediabridge.core.Json.write(encrypted)).apply();
        persist();
        LogBus.get().i(TAG, "paired with " + device.displayName() + " (" + device.id + ")");
    }

    public synchronized void forget(String id) {
        TvDevice removed = devices.remove(id);
        if (removed != null) {
            LogBus.get().i(TAG, "forgot " + removed.displayName());
        }
        Map<String, Object> keys = com.lgmediabridge.core.Json.parseObject(
                prefs.getString(KEY_KEYS, "{}"));
        if (keys != null) {
            keys.remove(id);
            prefs.edit().putString(KEY_KEYS, com.lgmediabridge.core.Json.write(keys)).apply();
        }
        persist();
    }

    public synchronized void clear() {
        devices.clear();
        prefs.edit().remove(KEY_DEVICES).remove(KEY_KEYS).apply();
    }

    private void persist() {
        Map<String, Object> map = new LinkedHashMap<>();
        for (Map.Entry<String, TvDevice> entry : devices.entrySet()) {
            map.put(entry.getKey(), entry.getValue().toJson());
        }
        prefs.edit().putString(KEY_DEVICES, com.lgmediabridge.core.Json.write(map)).apply();
    }
}
