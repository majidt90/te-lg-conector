package com.lgmediabridge.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import com.lgmediabridge.R;
import com.lgmediabridge.core.Formats;
import com.lgmediabridge.core.LogEntry;

import java.util.ArrayList;
import java.util.List;

/** The diagnostics log, colour-coded by severity. */
public final class LogListAdapter extends BaseAdapter {

    private final Context context;
    private final List<LogEntry> entries = new ArrayList<>();

    public LogListAdapter(Context context) {
        this.context = context;
    }

    public void setEntries(List<LogEntry> newEntries) {
        entries.clear();
        if (newEntries != null) {
            entries.addAll(newEntries);
        }
        notifyDataSetChanged();
    }

    @Override public int getCount() {
        return entries.size();
    }

    @Override public Object getItem(int position) {
        return entries.get(position);
    }

    @Override public long getItemId(int position) {
        return entries.get(position).timestamp;
    }

    @Override public View getView(int position, View convertView, ViewGroup parent) {
        View row = convertView;
        if (row == null) {
            row = LayoutInflater.from(context).inflate(R.layout.item_log, parent, false);
        }
        LogEntry entry = entries.get(position);
        TextView time = row.findViewById(R.id.log_time);
        TextView level = row.findViewById(R.id.log_level);
        TextView tag = row.findViewById(R.id.log_tag);
        TextView message = row.findViewById(R.id.log_message);

        time.setText(Formats.clock(entry.timestamp));
        level.setText(entry.level.shortName());
        tag.setText(entry.tag);
        message.setText(entry.message);

        int color;
        switch (entry.level) {
            case ERROR: color = R.color.error; break;
            case WARN: color = R.color.warning; break;
            case INFO: color = R.color.text_secondary; break;
            default: color = R.color.text_tertiary; break;
        }
        int resolved = context.getResources().getColor(color, null);
        level.setTextColor(resolved);
        tag.setTextColor(resolved);
        message.setTextColor(context.getResources().getColor(
                entry.isError() ? R.color.text_primary : R.color.text_secondary, null));
        return row;
    }
}
