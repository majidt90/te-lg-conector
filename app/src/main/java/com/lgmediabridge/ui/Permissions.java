package com.lgmediabridge.ui;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import com.lgmediabridge.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Media-access permissions across Android versions.
 *
 * Android 13 split storage access per media type (READ_MEDIA_IMAGES /
 * READ_MEDIA_VIDEO / READ_MEDIA_AUDIO); Android 14 added a "selected photos
 * only" grant. The app therefore asks only for the media types the user chose to
 * share, reports partial grants honestly instead of pretending to have access,
 * and never requests MANAGE_EXTERNAL_STORAGE - which is unnecessary for a
 * MediaStore-based server and would be rejected by Play review.
 */
public final class Permissions {

    public static final int REQUEST_MEDIA = 1001;
    public static final int REQUEST_NOTIFICATIONS = 1002;

    private Permissions() {
    }

    /** Permissions needed to read the media types enabled in settings. */
    public static String[] mediaPermissions(com.lgmediabridge.settings.Settings settings) {
        List<String> permissions = new ArrayList<>();
        com.lgmediabridge.settings.Settings.Scope scope = settings.scope();
        if (Build.VERSION.SDK_INT >= 33) {
            if (scope != com.lgmediabridge.settings.Settings.Scope.AUDIO) {
                permissions.add(Manifest.permission.READ_MEDIA_IMAGES);
                permissions.add(Manifest.permission.READ_MEDIA_VIDEO);
            }
            if (scope != com.lgmediabridge.settings.Settings.Scope.VIDEO_PHOTO) {
                permissions.add(Manifest.permission.READ_MEDIA_AUDIO);
            }
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        }
        return permissions.toArray(new String[0]);
    }

    public static boolean hasMediaAccess(Context context, com.lgmediabridge.settings.Settings settings) {
        for (String permission : mediaPermissions(settings)) {
            if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    /** Android 14+: the user granted access to a chosen subset of media. */
    public static boolean hasPartialMediaAccess(Context context) {
        return Build.VERSION.SDK_INT >= 34
                && context.checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                == PackageManager.PERMISSION_GRANTED;
    }

    public static boolean needsNotificationPermission(Context context) {
        return Build.VERSION.SDK_INT >= 33
                && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED;
    }

    public static void request(Activity activity, String[] permissions, int requestCode) {
        try {
            activity.requestPermissions(permissions, requestCode);
        } catch (Exception e) {
            Ui.toast(activity, activity.getString(R.string.error_permission_media));
        }
    }

    /** True when the OS will no longer show the dialog and the user must go to Settings. */
    public static boolean shouldExplainRationale(Activity activity, String permission) {
        return Build.VERSION.SDK_INT >= 23
                && !activity.shouldShowRequestPermissionRationale(permission);
    }

    /** Opens this app's page in system settings, where permissions can be changed. */
    public static void openAppSettings(Activity activity) {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.fromParts("package", activity.getPackageName(), null));
        Ui.startSafely(activity, intent);
    }

    /** Battery optimisation exemption request, offered when a TV keeps dropping out. */
    public static void requestIgnoreBatteryOptimizations(Activity activity) {
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.fromParts("package", activity.getPackageName(), null));
            activity.startActivity(intent);
        } catch (Exception e) {
            openAppSettings(activity);
        }
    }
}
