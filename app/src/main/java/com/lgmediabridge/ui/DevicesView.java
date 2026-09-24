package com.lgmediabridge.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import com.lgmediabridge.App;
import com.lgmediabridge.R;
import com.lgmediabridge.control.TvDevice;
import com.lgmediabridge.control.TvDiscovery;
import com.lgmediabridge.core.LogBus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Device screen: discovery, saved devices, connection and pairing.
 *
 * What each action does is deliberately honest about capability:
 * <ul>
 *   <li><b>Connect</b> marks the TV as the target for "play on TV"; the phone
 *       keeps serving media whether or not a TV is connected, because the TV is
 *       the one that starts browsing.</li>
 *   <li><b>Pair</b> only appears for LG sets that announce the webOS
 *       second-screen service, and it stores the key the TV returns, encrypted.
 *       Everything the app needs for streaming works without pairing.</li>
 * </ul>
 */
public final class DevicesView implements TvDiscovery.Listener {

    private static final String TAG = "DevicesView";

    private final MainActivity activity;
    private final View root;
    private final ListView list;
    private final TextView foundHeader;
    private final TextView savedHeader;
    private final View scanRow;
    private final TextView scanStatus;
    private final View scanButton;
    private final DeviceListAdapter adapter;

    private final Map<String, TvDevice> found = new LinkedHashMap<>();
    private final TvDiscovery discovery = new TvDiscovery(App.ctx());
    private List<TvDevice> rows = new ArrayList<>();

    public DevicesView(MainActivity activity) {
        this.activity = activity;
        this.root = LayoutInflater.from(activity).inflate(R.layout.view_devices, null, false);
        this.list = root.findViewById(R.id.devices_list);
        this.foundHeader = root.findViewById(R.id.devices_found_header);
        this.savedHeader = root.findViewById(R.id.devices_saved_header);
        this.scanRow = root.findViewById(R.id.devices_scan_row);
        this.scanStatus = root.findViewById(R.id.devices_scan_status);
        this.scanButton = root.findViewById(R.id.devices_scan);

        this.adapter = new DeviceListAdapter(activity, new DeviceListAdapter.Listener() {
            @Override public void onPrimaryAction(TvDevice device) {
                if (device.effectiveId().equals(activity.connectedId())) {
                    activity.disconnectDevice();
                } else {
                    activity.connectDevice(device);
                }
                bind();
            }

            @Override public void onSecondaryAction(TvDevice device) {
                if (device.paired) {
                    confirmForget(device);
                } else {
                    activity.forgetDevice(device);
                    bind();
                }
            }
        });
        list.setAdapter(adapter);
        discovery.addListener(this);

        scanButton.setOnClickListener(view -> startScan());
        root.findViewById(R.id.devices_add_manual).setOnClickListener(view -> addByAddress());
        bind();
    }

    public View root() {
        return root;
    }

    public void start() {
        startScan();
    }

    public void stop() {
        discovery.stop();
    }

    public void destroy() {
        discovery.removeListener(this);
        discovery.stop();
    }

    private void startScan() {
        found.clear();
        scanRow.setVisibility(View.VISIBLE);
        scanStatus.setText(R.string.action_scanning);
        scanButton.setEnabled(false);
        discovery.scan(4);
    }

    private void addByAddress() {
        View content = LayoutInflater.from(activity).inflate(R.layout.dialog_text_input, null);
        ((TextView) content.findViewById(R.id.dialog_message))
                .setText(R.string.devices_add_by_address_hint);
        final EditText input = content.findViewById(R.id.dialog_input);
        input.setHint("192.168.1.50");
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT);

        new AlertDialog.Builder(activity, Ui.dialogTheme())
                .setTitle(R.string.action_add_by_address)
                .setView(content)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_continue, (dialog, which) -> {
                    String address = input.getText().toString().trim();
                    if (!address.isEmpty()) {
                        scanRow.setVisibility(View.VISIBLE);
                        scanStatus.setText(R.string.action_scanning);
                        discovery.probeAddress(address);
                    }
                })
                .show();
    }

    private void confirmForget(final TvDevice device) {
        new AlertDialog.Builder(activity, Ui.dialogTheme())
                .setTitle(R.string.action_forget)
                .setMessage(activity.getString(R.string.devices_forget_confirm, device.displayName()))
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_forget, (dialog, which) -> {
                    activity.forgetDevice(device);
                    bind();
                })
                .show();
    }

    // ---------------------------------------------------- discovery callbacks

    @Override public void onDevice(TvDevice device) {
        synchronized (found) {
            TvDevice existing = found.get(device.effectiveId());
            if (existing == null) {
                found.put(device.effectiveId(), device);
            } else {
                existing.mergeFrom(device);
            }
        }
        root.post(this::bind);
    }

    @Override public void onScanFinished(int deviceCount) {
        root.post(() -> {
            scanRow.setVisibility(View.GONE);
            scanButton.setEnabled(true);
            bind();
        });
    }

    @Override public void onStatus(String message) {
        root.post(() -> scanStatus.setText(message));
    }

    // ------------------------------------------------------------------ bind

    public void bind() {
        List<TvDevice> combined = new ArrayList<>();
        List<TvDevice> foundList = new ArrayList<>();
        List<TvDevice> savedList = new ArrayList<>();

        synchronized (found) {
            for (TvDevice device : found.values()) {
                // A discovered TV that is also saved shows the saved copy, which
                // knows whether it is paired.
                TvDevice stored = App.get().tvStore().byId(device.effectiveId());
                if (stored != null) {
                    stored.mergeFrom(device);
                    savedList.add(stored);
                } else {
                    foundList.add(device);
                }
            }
        }
        for (TvDevice saved : App.get().tvStore().all()) {
            boolean present = false;
            for (TvDevice device : savedList) {
                if (device.effectiveId().equals(saved.effectiveId())) {
                    present = true;
                    break;
                }
            }
            if (!present) {
                savedList.add(saved);
            }
        }

        String connectedId = activity.connectedId();
        combined.addAll(foundList);
        combined.addAll(savedList);
        rows = combined;
        adapter.setConnectedId(connectedId);
        adapter.setDevices(combined);

        foundHeader.setVisibility(foundList.isEmpty() ? View.GONE : View.VISIBLE);
        savedHeader.setVisibility(savedList.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /** Called after the app connects or disconnects, to refresh the badges. */
    public void refresh() {
        bind();
    }
}
