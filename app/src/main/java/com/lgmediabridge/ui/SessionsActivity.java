package com.lgmediabridge.ui;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;

import com.lgmediabridge.App;
import com.lgmediabridge.R;
import com.lgmediabridge.core.Formats;
import com.lgmediabridge.stream.StreamRegistry;
import com.lgmediabridge.stream.StreamSession;

import java.util.ArrayList;
import java.util.List;

/**
 * Live view of every transfer between this phone and a TV.
 *
 * The TV pulls data on its own schedule, so the phone cannot always know whether
 * a stall is a paused video or a dropped connection - this screen shows the
 * evidence (bytes, rate, ranges, last activity) and lets the user stop a transfer
 * that is no longer wanted.
 */
public final class SessionsActivity extends android.app.Activity
        implements StreamRegistry.Listener {

    private ListView list;
    private TextView subtitle;
    private com.lgmediabridge.ui.StatePanel state;
    private StreamListAdapter adapter;
    private View stopAll;

    @Override protected void onCreate(Bundle savedInstanceState) {
        Ui.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sessions);

        list = findViewById(R.id.sessions_list);
        subtitle = findViewById(R.id.sessions_subtitle);
        state = new StatePanel(findViewById(R.id.sessions_state));
        stopAll = findViewById(R.id.sessions_stop_all);
        adapter = new StreamListAdapter(this);
        list.setAdapter(adapter);

        findViewById(R.id.back).setOnClickListener(view -> finish());
        ((Button) stopAll).setText(R.string.action_stop_all);
        stopAll.setOnClickListener(view -> {
            App.get().streams().stopAll();
            Ui.toast(this, getString(R.string.action_stop_all));
            refresh();
        });

        App.get().streams().addListener(this);
        refresh();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        App.get().streams().removeListener(this);
    }

    @Override public void onStreamsChanged(List<StreamSession> sessions) {
        runOnUiThread(this::refresh);
    }

    private void refresh() {
        List<StreamSession> active = App.get().streams().activeSorted();
        List<StreamSession> finished = App.get().streams().recentFinished();
        List<StreamSession> all = new ArrayList<>(active.size() + finished.size());
        all.addAll(active);
        all.addAll(finished);
        adapter.setSessions(all);

        long rate = App.get().streams().totalBytesPerSecond();
        subtitle.setText(getString(R.string.sessions_total_rate, Formats.rate(rate))
                + " · " + getString(R.string.sessions_active, active.size()));
        stopAll.setEnabled(!active.isEmpty());

        if (all.isEmpty()) {
            state.empty(R.drawable.ic_speed, getString(R.string.sessions_empty_title),
                    getString(R.string.sessions_empty_body), null, null);
        } else {
            state.hide();
        }
    }
}
