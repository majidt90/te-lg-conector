package com.lgmediabridge.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.lgmediabridge.App;
import com.lgmediabridge.core.LogBus;

/**
 * Restores sharing after a reboot, if the user enabled "start on boot".
 *
 * Android only allows launching a foreground service from the background in
 * specific situations; BOOT_COMPLETED is one of them, and any failure is logged
 * rather than crashing - the user can always start sharing from the app.
 */
public final class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                && !Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)) {
            return;
        }
        App app = App.get();
        if (!app.settings().startOnBoot() || !app.settings().serverDesired()) {
            LogBus.get().d(TAG, "start on boot is off; nothing to do");
            return;
        }
        LogBus.get().i(TAG, "restarting media sharing after " + action);
        try {
            Broadcasts.start(context, MediaServerService.ACTION_START);
        } catch (Exception e) {
            LogBus.get().w(TAG, "could not restart sharing: " + e.getMessage());
        }
    }
}
