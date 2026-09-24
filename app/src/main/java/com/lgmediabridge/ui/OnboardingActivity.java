package com.lgmediabridge.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.lgmediabridge.App;
import com.lgmediabridge.R;
import com.lgmediabridge.core.LogBus;
import com.lgmediabridge.net.LocalNetwork;
import com.lgmediabridge.settings.Settings;

/**
 * First run: what the app does, the two permissions it needs, the network check
 * and the first start of sharing - in that order, because each step can fail and
 * the user should see exactly which one did.
 */
public final class OnboardingActivity extends android.app.Activity
        implements LocalNetwork.Listener {

    private static final int STEP_COUNT = 4;

    private LinearLayout stepContainer;
    private LinearLayout dots;
    private Button primary;
    private Button secondary;
    private ProgressBar progress;
    private int step;

    private boolean shareStarted;

    @Override protected void onCreate(Bundle savedInstanceState) {
        Ui.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_onboarding);

        stepContainer = findViewById(R.id.step_container);
        dots = findViewById(R.id.dots);
        primary = findViewById(R.id.button_primary);
        secondary = findViewById(R.id.button_secondary);
        progress = findViewById(R.id.step_progress);

        primary.setOnClickListener(view -> onPrimary());
        secondary.setOnClickListener(view -> finishOnboarding(false));

        buildDots();
        showStep(0);
        LocalNetwork.addListener(this);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        LocalNetwork.removeListener(this);
    }

    private void buildDots() {
        dots.removeAllViews();
        for (int i = 0; i < STEP_COUNT; i++) {
            View dot = new View(this);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    Ui.dp(this, 8), Ui.dp(this, 8));
            params.setMargins(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
            dot.setLayoutParams(params);
            dots.addView(dot);
            updateDot(dot, i);
        }
    }

    private void updateDot(View dot, int index) {
        dot.setBackgroundResource(index == step ? R.drawable.bg_dot_ok : R.drawable.bg_dot_idle);
    }

    private void showStep(int index) {
        step = Math.max(0, Math.min(STEP_COUNT - 1, index));
        stepContainer.removeAllViews();
        View content = buildStep(step);
        stepContainer.addView(content);
        content.startAnimation(android.view.animation.AnimationUtils.loadAnimation(this,
                R.anim.fade_in_slight));
        for (int i = 0; i < dots.getChildCount(); i++) {
            updateDot(dots.getChildAt(i), i);
        }
        secondary.setText(step == 0 ? R.string.action_skip : R.string.action_back);
        primary.setText(step == STEP_COUNT - 1 ? R.string.action_start_server
                : R.string.action_continue);
        progress.setVisibility(View.GONE);
    }

    private View buildStep(int index) {
        LayoutInflater inflater = LayoutInflater.from(this);
        View view = inflater.inflate(R.layout.view_onboarding_step, stepContainer, false);
        ImageView icon = view.findViewById(R.id.step_icon);
        TextView title = view.findViewById(R.id.step_title);
        TextView body = view.findViewById(R.id.step_body);
        LinearLayout bullets = view.findViewById(R.id.step_bullets);

        switch (index) {
            case 0:
                icon.setImageResource(R.drawable.ic_logo);
                title.setText(R.string.ob_welcome_title);
                body.setText(R.string.ob_welcome_body);
                addBullet(bullets, R.string.ob_welcome_point_1);
                addBullet(bullets, R.string.ob_welcome_point_2);
                addBullet(bullets, R.string.ob_welcome_point_3);
                break;
            case 1:
                icon.setImageResource(R.drawable.ic_shield);
                title.setText(R.string.ob_permission_title);
                body.setText(R.string.ob_permission_body);
                addBullet(bullets, R.string.ob_permission_point_1);
                addBullet(bullets, R.string.ob_permission_point_2);
                addBullet(bullets, R.string.ob_permission_point_3);
                break;
            case 2:
                icon.setImageResource(R.drawable.ic_wifi);
                title.setText(R.string.ob_network_title);
                body.setText(R.string.ob_network_body);
                addBullet(bullets, R.string.ob_network_point_1);
                addBullet(bullets, R.string.ob_network_point_2);
                addBullet(bullets, R.string.ob_network_point_3);
                break;
            default:
                icon.setImageResource(R.drawable.ic_cast);
                title.setText(R.string.ob_ready_title);
                body.setText(R.string.ob_ready_body);
                addBullet(bullets, R.string.ob_ready_point_1);
                addBullet(bullets, R.string.ob_ready_point_2);
                addBullet(bullets, R.string.ob_ready_point_3);
                break;
        }
        return view;
    }

    private void addBullet(LinearLayout parent, int textRes) {
        View row = LayoutInflater.from(this).inflate(R.layout.item_bullet, parent, false);
        ImageView icon = row.findViewById(R.id.bullet_icon);
        icon.setImageResource(R.drawable.ic_check);
        icon.setColorFilter(getResources().getColor(R.color.success, null));
        ((TextView) row.findViewById(R.id.bullet_text)).setText(textRes);
        parent.addView(row);
    }

    private void onPrimary() {
        switch (step) {
            case 1:
                requestPermissionsForStep();
                return;
            case 2:
                if (!LocalNetwork.state(this).connected) {
                    Ui.toast(this, getString(R.string.error_no_wifi));
                    return;
                }
                showStep(3);
                return;
            case 3:
                finishOnboarding(true);
                return;
            default:
                showStep(step + 1);
        }
    }

    private void requestPermissionsForStep() {
        String[] permissions = Permissions.mediaPermissions(App.get().settings());
        java.util.List<String> request = new java.util.ArrayList<>();
        java.util.Collections.addAll(request, permissions);
        if (Permissions.needsNotificationPermission(this)) {
            request.add(android.Manifest.permission.POST_NOTIFICATIONS);
        }
        permissions = request.toArray(new String[0]);
        if (permissions.length == 0 || Permissions.hasMediaAccess(this, App.get().settings())) {
            showStep(2);
            return;
        }
        Permissions.request(this, permissions, Permissions.REQUEST_MEDIA);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                                     int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != Permissions.REQUEST_MEDIA) {
            return;
        }
        if (Permissions.hasMediaAccess(this, App.get().settings())) {
            showStep(2);
        } else if (Permissions.hasPartialMediaAccess(this)) {
            Ui.toast(this, getString(R.string.library_permission_partial));
            showStep(2);
        } else {
            Ui.toast(this, getString(R.string.error_permission_media));
            // Let the user continue to the network step and grant access later
            // from the library screen - onboarding must never be a dead end.
            showStep(2);
        }
    }

    private void finishOnboarding(boolean startSharing) {
        Settings settings = App.get().settings();
        settings.setOnboarded(true);
        if (startSharing) {
            settings.setServerDesired(true);
            settings.setStartOnBoot(true);
            shareStarted = true;
            progress.setVisibility(View.VISIBLE);
            HomeView.startSharingService(this);
            LogBus.get().i("Onboarding", "sharing enabled during onboarding");
            primary.postDelayed(() -> {
                progress.setVisibility(View.GONE);
                openMain();
            }, 900);
            return;
        }
        openMain();
    }

    private void openMain() {
        startActivity(new Intent(this, MainActivity.class));
        finish();
        overridePendingTransition(R.anim.fade_in_slight, R.anim.fade_out_slight);
    }

    @Override public void onNetworkChanged(LocalNetwork.State state) {
        runOnUiThread(() -> {
            if (step == 2 && state.connected) {
                secondary.setText(R.string.action_back);
            }
        });
    }
}
