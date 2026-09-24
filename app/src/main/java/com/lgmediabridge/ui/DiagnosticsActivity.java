package com.lgmediabridge.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import com.lgmediabridge.App;
import com.lgmediabridge.R;
import com.lgmediabridge.core.LogBus;
import com.lgmediabridge.core.LogEntry;
import com.lgmediabridge.dlna.SsdpServer;
import com.lgmediabridge.net.LocalNetwork;
import com.lgmediabridge.server.ServerStatus;

import java.util.ArrayList;
import java.util.List;

/**
 * Diagnostics: the facts needed to fix "the TV cannot see my phone".
 *
 * Everything on this screen comes from live state (network, server, SSDP, request
 * counters, log) rather than from guesses, and the whole log can be shared as text
 * - which is what makes a bug report useful.
 */
public final class DiagnosticsActivity extends android.app.Activity
        implements LogBus.Listener, com.lgmediabridge.service.MediaServerService.Listener {

    private static final int FILTER_ALL = 0;
    private static final int FILTER_WARNINGS = 1;
    private static final int FILTER_ERRORS = 2;
    private static final int FILTER_STREAM = 3;

    private TextView subtitle;
    private android.widget.ListView list;
    private LogListAdapter adapter;
    private android.widget.LinearLayout summary;
    private View[] chips;

    private int filter = FILTER_ALL;
    private final List<LogEntry> shown = new ArrayList<>();

    @Override protected void onCreate(Bundle savedInstanceState) {
        Ui.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_diagnostics);

        subtitle = findViewById(R.id.diagnostics_subtitle);
        list = findViewById(R.id.diagnostics_list);
        summary = findViewById(R.id.diagnostics_summary);
        adapter = new LogListAdapter(this);
        list.setAdapter(adapter);

        chips = new View[]{findViewById(R.id.filter_all), findViewById(R.id.filter_warnings),
                findViewById(R.id.filter_errors), findViewById(R.id.filter_stream)};
        chips[0].setOnClickListener(view -> setFilter(FILTER_ALL));
        chips[1].setOnClickListener(view -> setFilter(FILTER_WARNINGS));
        chips[2].setOnClickListener(view -> setFilter(FILTER_ERRORS));
        chips[3].setOnClickListener(view -> setFilter(FILTER_STREAM));

        findViewById(R.id.back).setOnClickListener(view -> finish());
        findViewById(R.id.diagnostics_share).setOnClickListener(view -> share());
        findViewById(R.id.diagnostics_export).setOnClickListener(view -> export());
        findViewById(R.id.diagnostics_clear).setOnClickListener(view -> {
            LogBus.get().clear();
            refresh();
        });

        LogBus.get().addListener(this);
        com.lgmediabridge.service.MediaServerService.addListener(this);
        setFilter(FILTER_ALL);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        LogBus.get().removeListener(this);
        com.lgmediabridge.service.MediaServerService.removeListener(this);
    }

    @Override public void onLogEntry(LogEntry entry) {
        runOnUiThread(this::refresh);
    }

    @Override public void onStatusChanged(ServerStatus status) {
        runOnUiThread(this::refreshSummary);
    }

    @Override public void onNetworkChanged(LocalNetwork.State state) {
        runOnUiThread(this::refreshSummary);
    }

    private void setFilter(int newFilter) {
        this.filter = newFilter;
        for (int i = 0; i < chips.length; i++) {
            chips[i].setBackgroundResource(i == newFilter
                    ? R.drawable.bg_chip_info : R.drawable.bg_chip);
        }
        refresh();
    }

    private void refresh() {
        List<LogEntry> entries;
        switch (filter) {
            case FILTER_WARNINGS:
                entries = LogBus.get().snapshot(true, 400);
                break;
            case FILTER_ERRORS:
                entries = new ArrayList<>();
                for (LogEntry entry : LogBus.get().snapshot(true, 400)) {
                    if (entry.isError()) {
                        entries.add(entry);
                    }
                }
                break;
            case FILTER_STREAM:
                entries = new ArrayList<>();
                for (LogEntry entry : LogBus.get().snapshot(false, 400)) {
                    if (entry.tag.toLowerCase().contains("stream")
                            || entry.tag.toLowerCase().contains("server")
                            || entry.message.toLowerCase().contains("stream")) {
                        entries.add(entry);
                    }
                }
                break;
            default:
                entries = LogBus.get().snapshot(false, 400);
                break;
        }
        shown.clear();
        shown.addAll(entries);
        adapter.setEntries(entries);
        subtitle.setText(getString(R.string.diag_log_entries, entries.size())
                + " · " + getString(R.string.diag_errors, LogBus.get().errorCount()));
        refreshSummary();
    }

    private void refreshSummary() {
        ServerStatus status = App.get().serverStatus();
        LocalNetwork.State network = LocalNetwork.state(this);
        summary.removeAllViews();

        addRow(R.string.diag_server, status.isRunning()
                ? getString(R.string.diag_server_running, status.hostPort())
                : getString(R.string.diag_server_stopped));
        addRow(R.string.diag_network, network.describe());
        addRow(R.string.diag_ssdp, status.ssdpListening
                ? getString(R.string.diag_ssdp_listening)
                : getString(R.string.diag_ssdp_idle));
        addRow(R.string.diag_msearch, getString(R.string.diag_msearch_value,
                status.searchesAnswered));
        addRow(R.string.diag_clients, getString(R.string.diag_clients_value,
                status.requestsServed, status.activeClients));
        addRow(R.string.diag_locks, getString(R.string.diag_locks_value,
                SsdpServer.canReceiveMulticast() ? "multicast" : "—"));
        addRow(R.string.diag_last_request, App.get().streams().activeCount() == 0
                ? getString(R.string.sessions_empty_title)
                : getString(R.string.sessions_active, App.get().streams().activeCount()));
        addRow(R.string.diag_battery, batteryLine());
    }

    private String batteryLine() {
        try {
            android.os.PowerManager power = (android.os.PowerManager)
                    getSystemService(POWER_SERVICE);
            if (power == null) {
                return "—";
            }
            boolean ignoring = power.isIgnoringBatteryOptimizations(getPackageName());
            return getString(ignoring ? R.string.diag_battery_unrestricted
                    : R.string.diag_battery_restricted);
        } catch (Exception e) {
            return "—";
        }
    }

    private void addRow(int labelRes, String value) {
        View row = LayoutInflater.from(this).inflate(R.layout.item_setting_action, summary, false);
        ((TextView) row.findViewById(R.id.setting_title)).setText(labelRes);
        row.findViewById(R.id.setting_summary).setVisibility(View.GONE);
        ((TextView) row.findViewById(R.id.setting_value)).setText(value);
        row.setClickable(false);
        row.setOnClickListener(null);
        summary.addView(row);
    }

    private void export() {
        String text = LogBus.get().exportText();
        try {
            java.io.File dir = new java.io.File(getExternalFilesDir(null), "logs");
            if (!dir.exists() && !dir.mkdirs()) {
                throw new java.io.IOException("cannot create " + dir);
            }
            java.io.File file = new java.io.File(dir,
                    "mediabridge-" + System.currentTimeMillis() + ".txt");
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(file)) {
                out.write(text.getBytes("UTF-8"));
            }
            Ui.toast(this, getString(R.string.diag_exported, file.getName()));
        } catch (Exception e) {
            Ui.toast(this, getString(R.string.diag_export_failed));
        }
    }

    private void share() {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.diagnostics_title));
        intent.putExtra(Intent.EXTRA_TEXT, LogBus.get().exportText());
        Ui.startSafely(this, Intent.createChooser(intent,
                getString(R.string.action_share_log)));
    }
}
