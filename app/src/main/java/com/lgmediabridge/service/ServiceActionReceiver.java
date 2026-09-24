package com.lgmediabridge.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.lgmediabridge.core.LogBus;

/**
 * Notification and quick-action entry points. Kept separate from the service so
 * the actions work even when the process was not running.
 */
public final class ServiceActionReceiver extends BroadcastReceiver {

    private static final String TAG = "ServiceActions";

    @Override public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (action == null) {
            return;
        }
        LogBus.get().i(TAG, "received " + action);
        switch (action) {
            case MediaServerService.ACTION_START:
            case MediaServerService.ACTION_REFRESH:
            case MediaServerService.ACTION_RESTART:
                Broadcasts.start(context, action);
                break;
            case MediaServerService.ACTION_STOP:
                Broadcasts.stop(context);
                break;
            default:
                LogBus.get().d(TAG, "ignoring unknown action " + action);
        }
    }
}
