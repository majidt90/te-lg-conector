package com.lgmediabridge.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.View;
import android.view.animation.AnimationUtils;
import android.widget.TextView;
import android.widget.Toast;

import com.lgmediabridge.App;
import com.lgmediabridge.R;
import com.lgmediabridge.settings.Settings;

/**
 * Small, shared UI helpers: theme resolution, density maths, list animations and
 * consistent toasts. Keeping them here means every screen behaves the same way in
 * light mode, dark mode and on tablets.
 */
public final class Ui {

    private Ui() {
    }

    /**
     * Applies the user's theme choice.
     *
     * Called as the very first statement of {@code onCreate}, before
     * {@code super.onCreate()}: overriding the configuration re-resolves
     * {@code values-night} resources, so an explicit Light/Dark choice overrides
     * the system setting without needing AppCompat.
     */
    public static void applyTheme(Activity activity) {
        Settings.Theme theme = App.get().settings().theme();
        if (theme == Settings.Theme.SYSTEM) {
            return;
        }
        boolean night = theme == Settings.Theme.DARK;
        Configuration configuration = new Configuration(activity.getResources().getConfiguration());
        int mask = Configuration.UI_MODE_NIGHT_MASK;
        int value = night ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO;
        if ((configuration.uiMode & mask) == value) {
            return;
        }
        configuration.uiMode = (configuration.uiMode & ~mask) | value;
        activity.applyOverrideConfiguration(configuration);
    }

    /** True when the given dialog/sheet theme should be the dark variant. */
    public static boolean isNight(Context context) {
        int mode = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    /**
     * Dialog and bottom-sheet styles. The night qualifier is resolved by the
     * resource system, so a single name is correct for both modes.
     */
    public static int dialogTheme() {
        return R.style.Theme_MediaBridge_Dialog;
    }

    public static int sheetTheme() {
        return R.style.Theme_MediaBridge_Sheet;
    }

    public static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    public static void toast(Context context, String message) {
        if (context == null || message == null) {
            return;
        }
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
    }

    public static void toast(Context context, int stringRes) {
        toast(context, context.getString(stringRes));
    }

    /** Animates a freshly bound list row in, with a stagger so the list flows. */
    public static void animateIn(View view, int position) {
        if (view == null) {
            return;
        }
        long delay = Math.min(180, position * 25L);
        view.postDelayed(() -> {
            view.startAnimation(AnimationUtils.loadAnimation(view.getContext(),
                    R.anim.item_appear));
        }, delay);
    }

    public static void crossFade(final View in, final View out) {
        if (out != null) {
            out.animate().alpha(0f).setDuration(120).withEndAction(() -> {
                out.setVisibility(View.GONE);
                out.setAlpha(1f);
            }).start();
        }
        if (in != null) {
            in.setAlpha(0f);
            in.setVisibility(View.VISIBLE);
            in.animate().alpha(1f).setDuration(160).start();
        }
    }

    public static void setSelected(TextView view, boolean selected) {
        if (view == null) {
            return;
        }
        view.setVisibility(selected ? View.VISIBLE : View.GONE);
        if (selected) {
            view.startAnimation(AnimationUtils.loadAnimation(view.getContext(),
                    R.anim.fade_in_slight));
        }
    }

    /** Tints an icon that was loaded from a vector drawable. */
    public static void tint(android.widget.ImageView view, int color) {
        if (view == null) {
            return;
        }
        Drawable drawable = view.getDrawable();
        if (drawable != null) {
            drawable = drawable.mutate();
            drawable.setTint(color);
            view.setImageDrawable(drawable);
        }
    }

    /** Colours a dot view according to a severity: 0 ok, 1 warning, 2 error, 3 idle. */
    public static void dotSeverity(View dot, int severity) {
        if (dot == null) {
            return;
        }
        int background;
        switch (severity) {
            case 0: background = R.drawable.bg_dot_ok; break;
            case 1: background = R.drawable.bg_dot_warning; break;
            case 2: background = R.drawable.bg_dot_error; break;
            default: background = R.drawable.bg_dot_idle; break;
        }
        dot.setBackgroundResource(background);
    }

    /** Colours a chip: 0 neutral, 1 success, 2 warning, 3 error. */
    public static void chipStyle(TextView chip, int tone) {
        if (chip == null) {
            return;
        }
        switch (tone) {
            case 1: chip.setBackgroundResource(R.drawable.bg_chip_success); break;
            case 2: chip.setBackgroundResource(R.drawable.bg_chip_warning); break;
            case 3: chip.setBackgroundResource(R.drawable.bg_chip_error); break;
            default: chip.setBackgroundResource(R.drawable.bg_chip); break;
        }
    }

    /** Opens a system screen, never crashing when the device lacks the activity. */
    public static void startSafely(Context context, Intent intent) {
        try {
            context.startActivity(intent);
        } catch (Exception e) {
            toast(context, context.getString(R.string.error_unknown, e.getClass().getSimpleName()));
        }
    }

    public static void copyToClipboard(Context context, String label, String value) {
        try {
            android.content.ClipboardManager clipboard = (android.content.ClipboardManager)
                    context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText(label, value));
                toast(context, context.getString(R.string.action_copied));
            }
        } catch (Exception e) {
            toast(context, context.getString(R.string.state_error_title));
        }
    }

    /** Long-press-free device model string used in the About card. */
    public static String deviceSummary() {
        String model = Build.MANUFACTURER + " " + Build.MODEL;
        return model.trim() + " · Android " + Build.VERSION.RELEASE
                + " (API " + Build.VERSION.SDK_INT + ")";
    }
}
