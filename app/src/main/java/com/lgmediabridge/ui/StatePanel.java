package com.lgmediabridge.ui;

import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.lgmediabridge.R;

/**
 * The loading / empty / error surface shared by every list and screen, so state
 * handling is identical everywhere and empty screens always explain what to do
 * next instead of showing nothing.
 */
public final class StatePanel {

    private final View root;
    private final ProgressBar progress;
    private final ImageView icon;
    private final TextView title;
    private final TextView body;
    private final Button action;

    public StatePanel(View parent) {
        this.root = parent;
        this.progress = parent.findViewById(R.id.state_progress);
        this.icon = parent.findViewById(R.id.state_icon);
        this.title = parent.findViewById(R.id.state_title);
        this.body = parent.findViewById(R.id.state_body);
        this.action = parent.findViewById(R.id.state_action);
    }

    public void loading(String message) {
        root.setVisibility(View.VISIBLE);
        progress.setVisibility(View.VISIBLE);
        icon.setVisibility(View.GONE);
        title.setText(message);
        title.setVisibility(View.VISIBLE);
        body.setVisibility(View.GONE);
        action.setVisibility(View.GONE);
    }

    public void empty(int iconRes, String titleText, String bodyText, String actionText,
                      final Runnable onAction) {
        root.setVisibility(View.VISIBLE);
        progress.setVisibility(View.GONE);
        icon.setImageResource(iconRes);
        icon.setVisibility(View.VISIBLE);
        title.setText(titleText);
        title.setVisibility(titleText == null ? View.GONE : View.VISIBLE);
        if (bodyText == null || bodyText.isEmpty()) {
            body.setVisibility(View.GONE);
        } else {
            body.setText(bodyText);
            body.setVisibility(View.VISIBLE);
        }
        if (actionText == null || onAction == null) {
            action.setVisibility(View.GONE);
        } else {
            action.setText(actionText);
            action.setVisibility(View.VISIBLE);
            action.setOnClickListener(view -> onAction.run());
        }
    }

    public void error(String titleText, String bodyText, final Runnable onRetry) {
        empty(R.drawable.ic_warning, titleText, bodyText,
                onRetry == null ? null : root.getContext().getString(R.string.action_retry),
                onRetry);
    }

    public void hide() {
        root.setVisibility(View.GONE);
    }

    public boolean isVisible() {
        return root.getVisibility() == View.VISIBLE;
    }
}
