package com.lgmediabridge.ui;

import android.app.AlertDialog;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import com.lgmediabridge.App;
import com.lgmediabridge.R;
import com.lgmediabridge.core.Formats;
import com.lgmediabridge.settings.Settings;


/**
 * Settings, grouped the way the app is used: server, library, network, storage,
 * about. Every option here changes real behaviour of the media server - nothing
 * is decorative, and each row explains its consequence in one sentence.
 */
public final class SettingsView {

    private final MainActivity activity;
    private final View root;
    private final LinearLayout containerServer;
    private final LinearLayout containerLibrary;
    private final LinearLayout containerNetwork;
    private final LinearLayout containerStorage;
    private final LinearLayout containerAbout;

    public SettingsView(MainActivity activity) {
        this.activity = activity;
        this.root = LayoutInflater.from(activity).inflate(R.layout.view_settings, null, false);
        this.containerServer = root.findViewById(R.id.settings_container_server);
        this.containerLibrary = root.findViewById(R.id.settings_container_library);
        this.containerNetwork = root.findViewById(R.id.settings_container_network);
        this.containerStorage = root.findViewById(R.id.settings_container_storage);
        this.containerAbout = root.findViewById(R.id.settings_container_about);
        build();
    }

    public View root() {
        return root;
    }

    public void refresh() {
        containerServer.removeAllViews();
        containerLibrary.removeAllViews();
        containerNetwork.removeAllViews();
        containerStorage.removeAllViews();
        containerAbout.removeAllViews();
        build();
    }

    private void build() {
        final Settings settings = App.get().settings();

        // --- server -------------------------------------------------------
        containerServer.addView(actionRow(R.string.settings_server_name,
                settings.name(), R.string.settings_server_name_desc, view -> renameServer()));
        containerServer.addView(actionRow(R.string.settings_port,
                String.valueOf(settings.port()), R.string.settings_port_desc, view -> changePort()));
        containerServer.addView(switchRow(R.string.settings_start_boot,
                R.string.settings_start_boot_desc, settings.startOnBoot(), value -> {
                    settings.setStartOnBoot(value);
                    return true;
                }));
        containerServer.addView(actionRow(R.string.settings_share_scope,
                scopeLabel(settings.scope()), R.string.settings_share_scope_desc,
                view -> chooseScope()));

        // --- library ------------------------------------------------------
        containerLibrary.addView(switchRow(R.string.settings_hide_unsupported,
                R.string.settings_hide_unsupported_desc, settings.hideUnsupported(), value -> {
                    settings.setHideUnsupported(value);
                    App.get().catalog().refresh(false);
                    return true;
                }));
        containerLibrary.addView(switchRow(R.string.settings_folder_view,
                R.string.settings_folder_view_desc, settings.folderView(), value -> {
                    settings.setFolderView(value);
                    return true;
                }));
        containerLibrary.addView(switchRow(R.string.settings_convert_photos,
                R.string.settings_convert_photos_desc, settings.photoConvert(), value -> {
                    settings.setPhotoConvert(value);
                    return true;
                }));
        containerLibrary.addView(switchRow(R.string.settings_convert_audio,
                R.string.settings_convert_audio_desc, settings.audioConvert(), value -> {
                    settings.setAudioConvert(value);
                    return true;
                }));
        containerLibrary.addView(actionRow(R.string.settings_reindex,
                App.get().catalog().statsLine(), R.string.settings_reindex_desc,
                view -> {
                    App.get().catalog().refresh(true);
                    Ui.toast(activity, activity.getString(R.string.library_indexing));
                    refresh();
                }));

        // --- network ------------------------------------------------------
        containerNetwork.addView(switchRow(R.string.settings_keep_wifi,
                R.string.settings_keep_wifi_desc, settings.keepWifiAwake(), value -> {
                    settings.setKeepWifiAwake(value);
                    activity.restartServerIfRunning();
                    return true;
                }));
        containerNetwork.addView(switchRow(R.string.settings_trusted_only,
                R.string.settings_trusted_only_desc, settings.trustedOnly(), value -> {
                    settings.setTrustedOnly(value);
                    return true;
                }));
        containerNetwork.addView(actionRow(R.string.settings_trusted_devices,
                activity.getString(R.string.settings_trusted_count, settings.trustedIps().size()),
                R.string.settings_trusted_devices_desc, view -> manageTrusted(settings)));
        containerNetwork.addView(actionRow(R.string.settings_battery,
                "", R.string.settings_battery_desc,
                view -> Permissions.requestIgnoreBatteryOptimizations(activity)));

        // --- storage ------------------------------------------------------
        long photoCache = App.get().photoTranscoder().cacheSize();
        long audioCache = App.get().audioTranscoder().cacheSize();
        containerStorage.addView(actionRow(R.string.settings_clear_cache,
                Formats.bytes(photoCache + audioCache), R.string.settings_clear_cache_desc,
                view -> {
                    App.get().photoTranscoder().clearCache();
                    App.get().audioTranscoder().clearCache();
                    Ui.toast(activity, activity.getString(R.string.settings_cache_cleared));
                    refresh();
                }));
        containerStorage.addView(actionRow(R.string.settings_cache_limit,
                Formats.bytes(96L * 1024 * 1024), R.string.settings_cache_limit_desc, null));

        // --- about --------------------------------------------------------
        containerAbout.addView(infoRow(R.string.settings_version, versionName(),
                R.string.settings_version_desc));
        containerAbout.addView(infoRow(R.string.settings_device, Ui.deviceSummary(),
                R.string.settings_device_desc));
        containerAbout.addView(infoRow(R.string.settings_uuid, settings.deviceUuid(),
                R.string.settings_uuid_desc));
        containerAbout.addView(switchRow(R.string.settings_theme_dark,
                R.string.settings_theme_dark_desc,
                settings.theme() == Settings.Theme.DARK, value -> {
                    settings.setTheme(value ? Settings.Theme.DARK : Settings.Theme.LIGHT);
                    activity.recreate();
                    return true;
                }));
        containerAbout.addView(actionRow(R.string.settings_theme_system,
                settings.theme() == Settings.Theme.SYSTEM ? "on" : "off",
                R.string.settings_theme_system_desc, view -> {
                    settings.setTheme(Settings.Theme.SYSTEM);
                    activity.recreate();
                }));
        containerAbout.addView(actionRow(R.string.settings_replay_onboarding, "",
                R.string.settings_replay_onboarding_desc, view -> {
                    settings.setOnboarded(false);
                    activity.startActivity(new Intent(activity, OnboardingActivity.class));
                }));
    }

    private String versionName() {
        try {
            return activity.getPackageManager()
                    .getPackageInfo(activity.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "1.0.0";
        }
    }

    private String scopeLabel(Settings.Scope scope) {
        switch (scope) {
            case AUDIO: return activity.getString(R.string.media_music);
            case VIDEO_PHOTO: return activity.getString(R.string.media_videos) + " + "
                    + activity.getString(R.string.media_photos);
            default: return activity.getString(R.string.filter_all);
        }
    }

    // ------------------------------------------------------------- actions

    private void renameServer() {
        final EditText input = new EditText(activity);
        input.setText(App.get().settings().name());
        input.setSelection(input.getText().length());
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        new AlertDialog.Builder(activity, Ui.dialogTheme())
                .setTitle(R.string.settings_server_name)
                .setView(wrap(input, R.string.settings_server_name_desc))
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_done, (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (!name.isEmpty()) {
                        App.get().settings().setServerName(name);
                        activity.restartServerIfRunning();
                        refresh();
                    }
                })
                .show();
    }

    private void changePort() {
        final EditText input = new EditText(activity);
        input.setText(String.valueOf(App.get().settings().port()));
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        new AlertDialog.Builder(activity, Ui.dialogTheme())
                .setTitle(R.string.settings_port)
                .setView(wrap(input, R.string.settings_port_desc))
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_done, (dialog, which) -> {
                    try {
                        int port = Integer.parseInt(input.getText().toString().trim());
                        if (port < 1024 || port > 65535) {
                            Ui.toast(activity, activity.getString(R.string.settings_port_invalid));
                            return;
                        }
                        App.get().settings().setPort(port);
                        activity.restartServerIfRunning();
                        refresh();
                    } catch (NumberFormatException e) {
                        Ui.toast(activity, activity.getString(R.string.settings_port_invalid));
                    }
                })
                .show();
    }

    private void chooseScope() {
        final String[] labels = {
                activity.getString(R.string.filter_all),
                activity.getString(R.string.media_videos) + " + "
                        + activity.getString(R.string.media_photos),
                activity.getString(R.string.media_music)};
        final Settings.Scope[] values = {Settings.Scope.ALL, Settings.Scope.VIDEO_PHOTO,
                Settings.Scope.AUDIO};
        int checked = App.get().settings().scope() == Settings.Scope.ALL ? 0
                : App.get().settings().scope() == Settings.Scope.VIDEO_PHOTO ? 1 : 2;
        new AlertDialog.Builder(activity, Ui.dialogTheme())
                .setTitle(R.string.settings_share_scope)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    App.get().settings().setScope(values[which]);
                    dialog.dismiss();
                    activity.restartServerIfRunning();
                    refresh();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void manageTrusted(final Settings settings) {
        final java.util.Set<String> trusted = settings.trustedIps();
        if (trusted.isEmpty()) {
            Ui.toast(activity, activity.getString(R.string.settings_trusted_empty));
            return;
        }
        final String[] entries = trusted.toArray(new String[0]);
        new AlertDialog.Builder(activity, Ui.dialogTheme())
                .setTitle(R.string.settings_trusted_devices)
                .setItems(entries, (dialog, which) -> {
                    settings.distrustIp(entries[which]);
                    Ui.toast(activity, activity.getString(R.string.action_done));
                    refresh();
                })
                .setNegativeButton(R.string.action_close, null)
                .show();
    }

    private View wrap(EditText input, int hintRes) {
        LinearLayout wrapper = new LinearLayout(activity);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(activity, 20);
        wrapper.setPadding(pad, Ui.dp(activity, 4), pad, 0);
        TextView hint = new TextView(activity);
        hint.setText(hintRes);
        hint.setTextColor(activity.getResources().getColor(R.color.text_secondary, null));
        hint.setTextSize(13f);
        wrapper.addView(hint);
        wrapper.addView(input);
        return wrapper;
    }

    // ------------------------------------------------------------ row types

    private interface BooleanChange {
        boolean apply(boolean value);
    }

    private View switchRow(int titleRes, int summaryRes, boolean checked,
                           final BooleanChange change) {
        View row = LayoutInflater.from(activity).inflate(R.layout.item_setting_switch,
                containerServer, false);
        ((TextView) row.findViewById(R.id.setting_title)).setText(titleRes);
        ((TextView) row.findViewById(R.id.setting_summary)).setText(summaryRes);
        final Switch toggle = row.findViewById(R.id.setting_switch);
        toggle.setChecked(checked);
        toggle.setOnCheckedChangeListener((button, value) -> {
            boolean accepted = change.apply(value);
            if (!accepted) {
                toggle.setChecked(!value);
            }
        });
        row.setOnClickListener(view -> toggle.setChecked(!toggle.isChecked()));
        Ui.animateIn(row, 0);
        return row;
    }

    private View actionRow(int titleRes, String value, int summaryRes, View.OnClickListener click) {
        View row = LayoutInflater.from(activity).inflate(R.layout.item_setting_action,
                containerServer, false);
        ((TextView) row.findViewById(R.id.setting_title)).setText(titleRes);
        ((TextView) row.findViewById(R.id.setting_summary)).setText(summaryRes);
        TextView valueView = row.findViewById(R.id.setting_value);
        if (value == null || value.isEmpty()) {
            valueView.setVisibility(View.GONE);
        } else {
            valueView.setText(value);
        }
        if (click != null) {
            row.setOnClickListener(click);
        } else {
            row.findViewById(R.id.setting_value).setVisibility(View.GONE);
            row.setClickable(false);
        }
        Ui.animateIn(row, 0);
        return row;
    }

    private View infoRow(int titleRes, String value, int summaryRes) {
        View row = LayoutInflater.from(activity).inflate(R.layout.item_setting_action,
                containerServer, false);
        ((TextView) row.findViewById(R.id.setting_title)).setText(titleRes);
        ((TextView) row.findViewById(R.id.setting_summary)).setText(summaryRes);
        TextView valueView = row.findViewById(R.id.setting_value);
        valueView.setText(value);
        row.setClickable(false);
        row.setOnClickListener(null);
        return row;
    }
}
