package com.lgmediabridge.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.lgmediabridge.R;
import com.lgmediabridge.core.Formats;
import com.lgmediabridge.stream.StreamSession;

import java.util.ArrayList;
import java.util.List;

/** Active transfers (and recent history) with live throughput. */
public final class StreamListAdapter extends BaseAdapter {

    private final Context context;
    private final List<StreamSession> sessions = new ArrayList<>();

    public StreamListAdapter(Context context) {
        this.context = context;
    }

    public void setSessions(List<StreamSession> newSessions) {
        sessions.clear();
        if (newSessions != null) {
            sessions.addAll(newSessions);
        }
        notifyDataSetChanged();
    }

    public boolean isEmpty() {
        return sessions.isEmpty();
    }

    @Override public int getCount() {
        return sessions.size();
    }

    @Override public Object getItem(int position) {
        return sessions.get(position);
    }

    @Override public long getItemId(int position) {
        return sessions.get(position).id.hashCode();
    }

    @Override public View getView(int position, View convertView, ViewGroup parent) {
        View row = convertView;
        if (row == null) {
            row = LayoutInflater.from(context).inflate(R.layout.item_stream, parent, false);
        }
        StreamSession session = sessions.get(position);
        boolean finished = session.isFinished();

        TextView title = row.findViewById(R.id.stream_title);
        TextView rate = row.findViewById(R.id.stream_rate);
        TextView meta = row.findViewById(R.id.stream_meta);
        TextView state = row.findViewById(R.id.stream_state);
        TextView bytes = row.findViewById(R.id.stream_bytes);
        ProgressBar progress = row.findViewById(R.id.stream_progress);
        View dot = row.findViewById(R.id.stream_dot);
        View historyRow = row.findViewById(R.id.stream_history_row);
        TextView historyMeta = row.findViewById(R.id.stream_history_meta);
        TextView status = row.findViewById(R.id.stream_status);

        title.setText(session.title == null || session.title.isEmpty()
                ? session.mediaId : session.title);
        rate.setText(finished ? "" : Formats.rate(session.bytesPerSecond()));
        rate.setTextColor(context.getResources().getColor(R.color.text_secondary, null));

        String client = session.clientLabel == null || session.clientLabel.isEmpty()
                ? session.clientAddress : session.clientLabel;
        meta.setText(context.getString(R.string.sessions_client) + ": " + client
                + " · " + session.mimeType
                + (session.converted ? " · " + context.getString(R.string.sessions_transcoding) : ""));

        int percent = session.progressPercent();
        progress.setProgress(Math.max(0, Math.min(100, percent)));

        state.setText(session.state() + " · " + context.getString(R.string.sessions_progress,
                percent) + " · " + Formats.elapsed(session.elapsedMillis()));
        bytes.setText(Formats.bytes(session.bytesSent()) + " / "
                + Formats.bytes(session.displayTotalBytes()));
        Ui.dotSeverity(dot, finished ? 3 : 0);

        historyRow.setVisibility(finished ? View.VISIBLE : View.GONE);
        if (finished) {
            historyMeta.setText(context.getString(R.string.sessions_rate) + ": "
                    + Formats.rate(session.bytesPerSecond()) + " · "
                    + Formats.elapsed(session.elapsedMillis()));
            status.setText(session.state());
            Ui.chipStyle(status, 1);
        }
        Ui.animateIn(row, position);
        return row;
    }
}
