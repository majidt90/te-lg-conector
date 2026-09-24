package com.lgmediabridge.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import com.lgmediabridge.R;
import com.lgmediabridge.control.TvDevice;
import com.lgmediabridge.core.Formats;

import java.util.ArrayList;
import java.util.List;

/** Rows for discovered and saved televisions. */
public final class DeviceListAdapter extends BaseAdapter {

    public interface Listener {
        void onPrimaryAction(TvDevice device);

        void onSecondaryAction(TvDevice device);
    }

    private final Context context;
    private final Listener listener;
    private final List<TvDevice> devices = new ArrayList<>();
    private String connectedId;

    public DeviceListAdapter(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
    }

    public void setDevices(List<TvDevice> newDevices) {
        devices.clear();
        if (newDevices != null) {
            devices.addAll(newDevices);
        }
        notifyDataSetChanged();
    }

    public void setConnectedId(String connectedId) {
        this.connectedId = connectedId;
        notifyDataSetChanged();
    }

    @Override public int getCount() {
        return devices.size();
    }

    @Override public Object getItem(int position) {
        return devices.get(position);
    }

    @Override public long getItemId(int position) {
        return devices.get(position).effectiveId().hashCode();
    }

    @Override public View getView(int position, View convertView, ViewGroup parent) {
        View row = convertView;
        if (row == null) {
            row = LayoutInflater.from(context).inflate(R.layout.item_device, parent, false);
        }
        final TvDevice device = devices.get(position);
        TextView title = row.findViewById(R.id.device_title);
        TextView subtitle = row.findViewById(R.id.device_subtitle);
        TextView capabilities = row.findViewById(R.id.device_capabilities);
        TextView connected = row.findViewById(R.id.device_connected);
        ImageView icon = row.findViewById(R.id.device_icon);
        Button primary = row.findViewById(R.id.device_primary);
        Button secondary = row.findViewById(R.id.device_secondary);

        title.setText(device.displayName());
        icon.setImageResource(device.isLg() ? R.drawable.ic_tv : R.drawable.ic_devices);

        StringBuilder meta = new StringBuilder();
        meta.append(device.address == null ? "—" : device.address);
        if (device.modelName != null && !device.modelName.isEmpty()) {
            meta.append(" · ").append(device.modelName);
        }
        meta.append(" · seen ").append(Formats.timeAgo(context, device.lastSeenAt));
        subtitle.setText(meta.toString());

        capabilities.setText(capabilityText(device));

        boolean isConnected = device.effectiveId().equals(connectedId);
        connected.setVisibility(isConnected ? View.VISIBLE : View.GONE);
        primary.setText(isConnected ? R.string.action_disconnect : R.string.action_connect);
        secondary.setText(device.paired ? R.string.devices_forget_confirm : R.string.action_forget);

        primary.setOnClickListener(view -> listener.onPrimaryAction(device));
        secondary.setOnClickListener(view -> listener.onSecondaryAction(device));
        row.setOnClickListener(view -> listener.onPrimaryAction(device));
        Ui.animateIn(row, position);
        return row;
    }

    private String capabilityText(TvDevice device) {
        StringBuilder sb = new StringBuilder();
        sb.append(device.isLg() ? context.getString(R.string.chip_lg_webos)
                : context.getString(R.string.devices_cap_unknown));
        if (device.supportsRemotePlayback()) {
            sb.append(" · ").append(context.getString(R.string.devices_cap_avtransport));
        }
        if (device.supportsRemoteVolume()) {
            sb.append(" · ").append(context.getString(R.string.devices_cap_rendering));
        }
        if (device.paired) {
            sb.append(" · ").append(context.getString(R.string.devices_paired));
        }
        if (device.lgSecondScreen) {
            sb.append(" · ").append(context.getString(R.string.devices_cap_pairing));
        }
        return sb.toString();
    }
}
