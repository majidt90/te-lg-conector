package com.lgmediabridge.service;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** Single place that knows how to start and stop the sharing service on every API level. */
public final class Broadcasts {

    private Broadcasts() {
    }

    public static void start(Context context, String action) {
        Intent intent = new Intent(context, MediaServerService.class);
        intent.setAction(action);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Exception e) {
            com.lgmediabridge.core.LogBus.get().w("ServiceStart",
                    "could not start the service: " + e.getMessage());
        }
    }

    /**
     * Stops the service outright.
     *
     * Deliberately not "start with ACTION_STOP": starting a foreground service
     * in order to stop it would have to call startForeground() within a few
     * seconds or the OS kills the process, which is unnecessary risk for a stop
     * request.
     */
    public static void stop(Context context) {
        try {
            context.stopService(new Intent(context, MediaServerService.class));
        } catch (Exception e) {
            com.lgmediabridge.core.LogBus.get().w("ServiceStart",
                    "could not stop the service: " + e.getMessage());
        }
    }
}
