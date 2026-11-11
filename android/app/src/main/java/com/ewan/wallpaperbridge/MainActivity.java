package com.ewan.wallpaperbridge;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.Menu;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public class MainActivity extends CatDiaoActivity {
    private static final int CREAM = Color.rgb(250, 247, 241);
    private static final int PAPER = Color.WHITE;
    private static final int INK = Color.rgb(61, 52, 47);
    private static final int MUTED = Color.rgb(108, 98, 91);
    private static final int CORAL = Color.rgb(185, 80, 56);
    private static final int CORAL_DARK = Color.rgb(160, 70, 49);
    private static final int MINT = Color.rgb(72, 123, 104);
    private static final int BORDER = Color.rgb(232, 221, 210);
    private static final String LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK";

    private static volatile boolean visible;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            refreshHome();
            mainHandler.postDelayed(this, 1000);
        }
    };
    private final Runnable connectionRefresh = this::refreshHome;
    private final Runnable connectionListener = () -> mainHandler.post(connectionRefresh);
    private TextView summary;
    private TextView summaryDetail;
    private TextView computerStatus;
    private LinearLayout connectionPanel;
    private TextView connectionTitle;
    private TextView connectionSteps;
    private Button connectionRetry;
    private Button connectionBluetooth;
    private Button revokeButton;
    private Button captureButton;
    private Button connectPrimary;
    private LinearLayout controlPanel;
    private TextView controlStatus;
    private LinearLayout recentPanel;
    private LinearLayout recentList;
    private String recentSignature = "";
    private String pairDialogId = "";
    private AlertDialog setupDialog;
    private boolean setupInProgress;
    private int setupAttemptedStep;
    private boolean overlayPermissionPending;

    public static boolean isVisible() { return visible; }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setupInProgress = getSharedPreferences("bridge", MODE_PRIVATE)
                .getBoolean("setup_in_progress", false);
        setupAttemptedStep = getSharedPreferences("bridge", MODE_PRIVATE)
                .getInt("setup_attempted_step", 0);
        getWindow().setStatusBarColor(CREAM);
        getWindow().setNavigationBarColor(CREAM);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        buildScreen();
        if (state == null && !getSharedPreferences("bridge", MODE_PRIVATE)
                .getBoolean("onboarding_seen_v1", false)) {
            startActivity(new Intent(this, OnboardingActivity.class));
        }
    }

    @Override protected void onStart() {
        super.onStart();
        visible = true;
        BridgeService.appActivityStarted();
        BridgeService.addConnectionListener(connectionListener);
        try {
            startForegroundService(new Intent(this, BridgeService.class)
                    .setAction(BridgeService.ACTION_CHECK_CONNECTION));
        } catch (Exception error) {
            Toast.makeText(this, R.string.toast_service_down, Toast.LENGTH_LONG).show();
        }
        mainHandler.post(refresh);
        mainHandler.postDelayed(this::maybeStartSetup, 650);
        mainHandler.postDelayed(this::maybeAskBackgroundExemption, 1800);
        if (Build.VERSION.SDK_INT >= 37 &&
                checkSelfPermission(LOCAL_NETWORK_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{LOCAL_NETWORK_PERMISSION}, 1);
        }
    }

    // 已配对但系统仍在省电管着猫叼时，每天最多提醒一次；不然 OPPO 等机型息屏就断。
    private void maybeAskBackgroundExemption() {
        if (!visible || isFinishing()) return;
        android.content.SharedPreferences prefs = getSharedPreferences("bridge", MODE_PRIVATE);
        if (prefs.getString("computer_token", "").isEmpty()) return;
        android.os.PowerManager power = getSystemService(android.os.PowerManager.class);
        if (power == null || power.isIgnoringBatteryOptimizations(getPackageName())) return;
        long now = System.currentTimeMillis();
        if (now - prefs.getLong("battery_prompt_at", 0L) < 24L * 60 * 60 * 1000) return;
        prefs.edit().putLong("battery_prompt_at", now).apply();
        new AlertDialog.Builder(this)
                .setTitle(R.string.perm_screen_off_title)
                .setMessage(R.string.perm_screen_off_body)
                .setNegativeButton(R.string.action_later, null)
                .setPositiveButton(R.string.conn_allow, (dialog, which) -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:" + getPackageName())));
                    } catch (Exception error) {
                        try {
                            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:" + getPackageName())));
                        } catch (Exception ignored) {
                            Toast.makeText(this, R.string.toast_battery_settings, Toast.LENGTH_LONG).show();
                        }
                    }
                }).show();
    }

    @Override protected void onResume() {
        super.onResume();
        boolean overlayGranted = Settings.canDrawOverlays(this);
        if (getSharedPreferences("bridge", MODE_PRIVATE).getBoolean("overlay_wanted", false)
                && overlayGranted) {
            BridgeService.setOverlay(this, true);
        }
        if (overlayPermissionPending) {
            overlayPermissionPending = false;
            Toast.makeText(this, overlayGranted ? R.string.toast_overlay_on :
                    R.string.toast_overlay_kept_off, Toast.LENGTH_LONG).show();
        }
        if (setupInProgress) mainHandler.postDelayed(this::advanceSetup, 250);
    }

    @Override protected void onStop() {
        visible = false;
        BridgeService.appActivityStopped();
        BridgeService.removeConnectionListener(connectionListener);
        mainHandler.removeCallbacks(refresh);
        mainHandler.removeCallbacks(connectionRefresh);
        if (setupDialog != null) setupDialog.dismiss();
        setupDialog = null;
        pairDialogId = "";
        super.onStop();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == 14) {
            if (BluetoothLink.permission(this)) openBluetoothSettings();
            else Toast.makeText(this, R.string.toast_bt_off, Toast.LENGTH_LONG).show();
            return;
        }
        if (requestCode == 1 && (results.length == 0 || results[0] != PackageManager.PERMISSION_GRANTED)) {
            Toast.makeText(this, R.string.toast_lan_needed, Toast.LENGTH_LONG).show();
        }
    }

    private void buildScreen() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(CREAM);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(22), dp(20), dp(28));
        scroll.addView(page);
        setContentView(scroll);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        page.addView(header);
        ImageView cat = new ImageView(this);
        cat.setImageResource(getResources().getIdentifier("cat_diao_launcher", "drawable", getPackageName()));
        cat.setScaleType(ImageView.ScaleType.FIT_CENTER);
        header.addView(cat, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout nameBlock = new LinearLayout(this);
        nameBlock.setOrientation(LinearLayout.VERTICAL);
        nameBlock.setPaddingRelative(dp(8), 0, 0, 0);
        header.addView(nameBlock, new LinearLayout.LayoutParams(0, -2, 1));
        nameBlock.addView(label(getString(R.string.app_name), 22, INK, true));
        TextView more = label("⋯", 26, CORAL_DARK, false);
        more.setGravity(Gravity.CENTER);
        more.setContentDescription(getString(R.string.menu_more));
        more.setBackground(round(PAPER, BORDER, 14));
        header.addView(more, new LinearLayout.LayoutParams(dp(48), dp(48)));
        more.setOnClickListener(this::showMore);

        LinearLayout hero = panel(PAPER, BORDER);
        LinearLayout.LayoutParams heroLayout = new LinearLayout.LayoutParams(-1, -2);
        heroLayout.topMargin = dp(18);
        page.addView(hero, heroLayout);
        ImageView illustration = new ImageView(this);
        illustration.setImageResource(getResources().getIdentifier(
                "cat_cozy_computer_v1", "drawable", getPackageName()));
        illustration.setScaleType(ImageView.ScaleType.CENTER_CROP);
        illustration.setBackground(round(CREAM, BORDER, 14));
        illustration.setClipToOutline(true);
        illustration.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams illustrationLayout = new LinearLayout.LayoutParams(-1, dp(148));
        illustrationLayout.bottomMargin = dp(16);
        hero.addView(illustration, illustrationLayout);
        summary = label(getString(R.string.home_loading), 21, INK, true);
        hero.addView(summary);
        summaryDetail = label("", 14, CORAL_DARK, false);
        summaryDetail.setPadding(0, dp(4), 0, 0);
        hero.addView(summaryDetail);
        computerStatus = label("", 13, MUTED, false);
        computerStatus.setPadding(0, dp(10), 0, dp(2));
        hero.addView(computerStatus);
        connectPrimary = button(getString(R.string.home_connect_link), true);
        connectPrimary.setVisibility(View.GONE);
        LinearLayout.LayoutParams connectLayout = new LinearLayout.LayoutParams(-1, -2);
        connectLayout.topMargin = dp(15);
        hero.addView(connectPrimary, connectLayout);
        connectPrimary.setOnClickListener(v -> openConnection());
        captureButton = button(getString(R.string.home_capture), true);
        LinearLayout.LayoutParams captureLayout = new LinearLayout.LayoutParams(-1, -2);
        captureLayout.topMargin = dp(10);
        hero.addView(captureButton, captureLayout);
        captureButton.setOnClickListener(v -> startActivity(new Intent(this, CaptureActivity.class)
                .setAction(CaptureActivity.ACTION_CLIPBOARD)));

        connectionPanel = panel(PAPER, BORDER);
        LinearLayout.LayoutParams connectionLayout = new LinearLayout.LayoutParams(-1, -2);
        connectionLayout.topMargin = dp(14);
        page.addView(connectionPanel, connectionLayout);
        connectionTitle = label("", 17, INK, true);
        connectionPanel.addView(connectionTitle);
        connectionSteps = label("", 14, MUTED, false);
        connectionSteps.setPadding(0, dp(8), 0, dp(8));
        connectionPanel.addView(connectionSteps);
        connectionRetry = button(getString(R.string.home_retry), false);
        connectionPanel.addView(connectionRetry);
        connectionRetry.setOnClickListener(v -> {
            try {
                startForegroundService(new Intent(this, BridgeService.class)
                        .setAction(BridgeService.ACTION_CHECK_CONNECTION));
            } catch (Exception error) {
                Toast.makeText(this, R.string.toast_check_failed, Toast.LENGTH_LONG).show();
            }
        });
        connectionBluetooth = button(getString(R.string.home_bluetooth), false);
        connectionBluetooth.setOnClickListener(v -> setupBluetooth());
        connectionPanel.addView(connectionBluetooth);
        connectionPanel.addView(connectLink());
        connectionPanel.setOnClickListener(v -> openConnection());
        revokeButton = button(getString(R.string.home_revoke), false);
        LinearLayout.LayoutParams revokeLayout = new LinearLayout.LayoutParams(-1, -2);
        revokeLayout.topMargin = dp(14);
        page.addView(revokeButton, revokeLayout);
        revokeButton.setOnClickListener(v -> confirmRevoke());

        controlPanel = panel(PAPER, BORDER);
        controlPanel.setVisibility(View.GONE);
        LinearLayout.LayoutParams controlLayout = new LinearLayout.LayoutParams(-1, -2);
        controlLayout.topMargin = dp(14);
        page.addView(controlPanel, controlLayout);
        controlPanel.addView(label(getString(R.string.home_control_title), 17, INK, true));
        controlStatus = label("", 13, MUTED, false);
        controlStatus.setPadding(0, dp(6), 0, 0);
        controlPanel.addView(controlStatus);
        controlPanel.setOnClickListener(v -> showPermissionGuide());

        recentPanel = panel(PAPER, BORDER);
        LinearLayout.LayoutParams recentsLayout = new LinearLayout.LayoutParams(-1, -2);
        recentsLayout.topMargin = dp(14);
        page.addView(recentPanel, recentsLayout);
        recentPanel.addView(label(getString(R.string.home_recent), 17, INK, true));
        recentList = new LinearLayout(this);
        recentList.setOrientation(LinearLayout.VERTICAL);
        recentPanel.addView(recentList);
    }

    private void refreshHome() {
        String trusted = BridgeService.trustedName(this);
        int pending = 0;
        boolean savesReadable = true;
        try (CaptureStore store = new CaptureStore(this)) {
            int total = store.totalCount();
            pending = store.pendingCount();
            recentPanel.setVisibility(total == 0 ? View.GONE : View.VISIBLE);
            if (total == 0) {
                summary.setText(R.string.home_empty_title);
                summaryDetail.setText(R.string.home_empty_body);
            } else if (pending > 0) {
                summary.setText(getResources().getQuantityString(R.plurals.home_pending, pending, pending));
                summaryDetail.setText(R.string.home_pending_body);
            } else {
                summary.setText(R.string.home_all_done);
                summaryDetail.setText(getResources().getQuantityString(R.plurals.home_total, total, total));
            }
            renderRecent(store.recent(3));
        } catch (Exception error) {
            savesReadable = false;
            summary.setText(R.string.home_read_error);
            summaryDetail.setText(R.string.home_read_error_body);
            recentPanel.setVisibility(View.GONE);
        }
        boolean setupDone = SetupFlow.finished(screenReady(), writeReady());
        boolean online = computerOnline();
        ComputerConnection.Snapshot check = BridgeService.computerConnectionSnapshot();
        boolean checking = check.phase == ComputerConnection.Phase.CHECKING && BridgeService.isRunning();
        boolean bluetoothOnly = BridgeService.bluetoothOnline() && !BridgeService.wifiOnline(this);
        revokeButton.setVisibility(trusted.isEmpty() ? View.GONE : View.VISIBLE);
        if (trusted.isEmpty()) {
            if (savesReadable) {
                summary.setText(R.string.home_value_line);
                summaryDetail.setTextColor(MUTED);
                summaryDetail.setText(pending > 0 ? R.string.home_pending_body : R.string.status_unpaired);
            }
            computerStatus.setVisibility(View.GONE);
            connectionPanel.setVisibility(View.GONE);
            connectPrimary.setVisibility(View.VISIBLE);
            connectPrimary.setText(BridgeService.pendingPair() != null
                    ? R.string.conn_pairing_title : R.string.home_connect_link);
            styleButton(connectPrimary, true);
            styleButton(captureButton, false);
            controlPanel.setVisibility(View.GONE);
            showPairRequest();
            return;
        }
        summaryDetail.setTextColor(CORAL_DARK);
        connectPrimary.setVisibility(View.GONE);
        styleButton(captureButton, true);
        computerStatus.setVisibility(View.VISIBLE);
        connectionRetry.setVisibility(online ? View.GONE : View.VISIBLE);
        connectionRetry.setEnabled(!checking);
        connectionRetry.setText(checking ? R.string.home_searching : R.string.home_retry);
        connectionBluetooth.setVisibility(!trusted.isEmpty() && !online ? View.VISIBLE : View.GONE);
        computerStatus.setText(computerConnectionText(trusted));
        computerStatus.setTextColor(online ? MINT : CORAL_DARK);
        computerStatus.setVisibility(View.VISIBLE);
        if (!online) {
            if (checking) {
                long seconds = (check.remainingMs() + 999) / 1000;
                connectionTitle.setText(getString(R.string.home_finding_title, seconds));
                connectionSteps.setText(R.string.home_finding_body);
            } else {
                connectionTitle.setText(R.string.home_missing_title);
                connectionSteps.setText(!BridgeService.isRunning()
                        ? R.string.home_missing_stopped : R.string.home_missing_body);
            }
            connectionPanel.setVisibility(View.VISIBLE);
        } else if (bluetoothOnly) {
            connectionTitle.setText(R.string.home_bt_title);
            connectionSteps.setText(R.string.home_bt_body);
            connectionPanel.setVisibility(View.VISIBLE);
        } else if (pending > 0) {
            String syncError = getSharedPreferences("bridge", MODE_PRIVATE).getString("capture_sync_error", "");
            connectionTitle.setText(syncError.isEmpty() ? R.string.home_sending_title : R.string.home_held_title);
            connectionSteps.setText(syncError.isEmpty() ? R.string.home_sending_body : R.string.home_held_body);
            connectionPanel.setVisibility(View.VISIBLE);
        } else {
            connectionPanel.setVisibility(View.GONE);
        }
        controlPanel.setVisibility(trusted.isEmpty() || setupDone ? View.GONE : View.VISIBLE);
        if (!trusted.isEmpty() && !setupDone) {
            controlStatus.setText(R.string.perm_intro);
        }
        showPairRequest();
    }

    private boolean computerOnline() {
        return BridgeService.bluetoothOnline() || BridgeService.isRunning() && BridgeService.wifiOnline(this);
    }

    private String computerConnectionText(String trusted) {
        if (trusted.isEmpty()) return getString(R.string.status_unpaired);
        if (!BridgeService.isRunning()) return getString(R.string.status_stopped);
        if (computerOnline()) return getString(BridgeService.wifiOnline(this)
                ? R.string.status_wifi : R.string.status_bluetooth, trusted);
        ComputerConnection.Snapshot check = BridgeService.computerConnectionSnapshot();
        if (check.phase == ComputerConnection.Phase.CHECKING) return getString(R.string.status_finding, trusted);
        return getString(R.string.status_offline, trusted);
    }

    private void renderRecent(JSONArray items) {
        String signature = items.toString();
        if (signature.equals(recentSignature)) return;
        recentSignature = signature;
        recentList.removeAllViews();
        if (items.length() == 0) {
            TextView empty = label(getString(R.string.home_recent_empty), 14, MUTED, false);
            empty.setPadding(0, dp(15), 0, dp(5));
            recentList.addView(empty);
            return;
        }
        for (int index = 0; index < items.length(); index++) {
            JSONObject item = items.optJSONObject(index);
            if (item == null) continue;
            if (index > 0) {
                View divider = new View(this);
                divider.setBackgroundColor(BORDER);
                LinearLayout.LayoutParams dividerLayout = new LinearLayout.LayoutParams(-1, dp(1));
                dividerLayout.topMargin = dp(9);
                recentList.addView(divider, dividerLayout);
            }
            String value = item.optString("text", "").replace('\n', ' ').trim();
            TextView title = label(value.isEmpty() ? getString(R.string.home_unnamed) : value, 15, INK, true);
            title.setMaxLines(2);
            title.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams titleLayout = new LinearLayout.LayoutParams(-1, -2);
            titleLayout.topMargin = dp(10);
            recentList.addView(title, titleLayout);
            boolean pending = "pending".equals(item.optString("state"));
            String source = SourceLabels.display(this, item.optString("source", "分享"));
            recentList.addView(label(getString(R.string.home_source_line, source,
                    getString(pending ? R.string.home_state_pending : R.string.home_state_sent)),
                    12, pending ? CORAL_DARK : MINT, false));
        }
    }

    private void openConnection() {
        startActivity(new Intent(this, ConnectionActivity.class));
    }

    private void showPairRequest() {
        PairingGate.Request request = BridgeService.pendingPair();
        if (request == null) {
            pairDialogId = "";
            return;
        }
        if (ConnectionActivity.isVisible()) return;
        if (request.id.equals(pairDialogId)) return;
        pairDialogId = request.id;
        openConnection();
    }

    private LinearLayout connectLink() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(label(getString(R.string.home_connect_link), 13, CORAL_DARK, true));
        TextView arrow = label(getString(R.string.chevron_forward), 13, CORAL_DARK, true);
        arrow.setPaddingRelative(dp(6), 0, 0, 0);
        row.addView(arrow);
        return row;
    }

    private void showLanguage() {
        String[] tags = LocalePolicy.TAGS;
        String[] labels = new String[tags.length + 1];
        String[] values = new String[labels.length];
        labels[0] = getString(R.string.language_system);
        values[0] = LocalePolicy.SYSTEM;
        for (int index = 0; index < tags.length; index++) {
            labels[index + 1] = LocalePolicy.nativeName(tags[index]);
            values[index + 1] = tags[index];
        }
        String current = AppLocale.choice(this);
        int checked = 0;
        for (int index = 0; index < values.length; index++) {
            if (values[index].equalsIgnoreCase(current)) checked = index;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.language_title)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    dialog.dismiss();
                    if (!values[which].equalsIgnoreCase(current)) {
                        AppLocale.setChoice(this, values[which]);
                        recreate();
                    }
                }).show();
    }

    private void showMore(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(Menu.NONE, 1, 1, getString(R.string.menu_send));
        menu.getMenu().add(Menu.NONE, 2, 2, getString(BridgeService.overlayEnabled(this)
                ? R.string.menu_overlay_off : R.string.menu_overlay_on));
        menu.getMenu().add(Menu.NONE, 3, 3, getString(R.string.menu_connection));
        menu.getMenu().add(Menu.NONE, 4, 4, getString(R.string.menu_guide));
        menu.getMenu().add(Menu.NONE, 5, 5, getString(R.string.menu_bluetooth));
        menu.getMenu().add(Menu.NONE, 6, 6, getString(R.string.menu_permissions));
        menu.getMenu().add(Menu.NONE, 7, 7, getString(R.string.menu_language));
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) showTransfer();
            else if (item.getItemId() == 2) toggleOverlay();
            else if (item.getItemId() == 3) openConnection();
            else if (item.getItemId() == 4)
                startActivity(new Intent(this, OnboardingActivity.class));
            else if (item.getItemId() == 5) setupBluetooth();
            else if (item.getItemId() == 6) showPermissionGuide();
            else if (item.getItemId() == 7) showLanguage();
            return true;
        });
        menu.show();
    }

    private void showTransfer() {
        if (BridgeService.trustedName(this).isEmpty()) {
            Toast.makeText(this, R.string.toast_need_pair_first, Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.menu_send)
                .setItems(new String[]{getString(R.string.panel_send_file), getString(R.string.panel_send_text)}, (dialog, choice) -> {
                    if (choice == 0) {
                        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        pick.addCategory(Intent.CATEGORY_OPENABLE);
                        pick.setType("*/*");
                        startActivityForResult(pick, 29);
                    } else {
                        sendClipboardToComputer(this);
                    }
                }).show();
    }

    private void toggleOverlay() {
        if (BridgeService.overlayEnabled(this)) {
            getSharedPreferences("bridge", MODE_PRIVATE).edit().putBoolean("overlay_wanted", false).commit();
            BridgeService.setOverlay(this, false);
            return;
        }
        if (Settings.canDrawOverlays(this)) {
            BridgeService.setOverlay(this, true);
        } else {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.overlay_enable_title)
                    .setMessage(R.string.overlay_enable_body)
                    .setNegativeButton(R.string.overlay_not_now, null)
                    .setPositiveButton(R.string.overlay_go_settings, (dialog, which) -> {
                        getSharedPreferences("bridge", MODE_PRIVATE).edit()
                                .putBoolean("overlay_wanted", true).commit();
                        overlayPermissionPending = true;
                        startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + getPackageName())));
                    }).show();
        }
    }

    private boolean screenReady() { return PhoneAccessibilityService.isEnabled(this); }

    private boolean writeReady() { return Settings.System.canWrite(this); }

    private boolean overlaySkipped() {
        return getSharedPreferences("bridge", MODE_PRIVATE).getBoolean("overlay_setup_skipped", false);
    }

    private int setupStep() {
        return SetupFlow.next(screenReady(), Settings.canDrawOverlays(this), overlaySkipped(), writeReady());
    }

    private void setSetupInProgress(boolean value) {
        setupInProgress = value;
        getSharedPreferences("bridge", MODE_PRIVATE).edit()
                .putBoolean("setup_in_progress", value).apply();
    }

    private void resetSetupAttempt() {
        setupAttemptedStep = 0;
        getSharedPreferences("bridge", MODE_PRIVATE).edit()
                .putInt("setup_attempted_step", 0).apply();
    }

    private void maybeStartSetup() {
        if (!visible || setupInProgress || BridgeService.trustedName(this).isEmpty() ||
                setupStep() == SetupFlow.DONE) return;
        if (getSharedPreferences("bridge", MODE_PRIVATE).getBoolean("setup_prompted_once", false)) return;
        getSharedPreferences("bridge", MODE_PRIVATE).edit()
                .putBoolean("setup_prompted_once", true).apply();
        resetSetupAttempt();
        setSetupInProgress(true);
        advanceSetup();
    }

    private void advanceSetup() {
        if (!setupInProgress || !visible ||
                (setupDialog != null && setupDialog.isShowing())) return;
        if (BridgeService.trustedName(this).isEmpty()) {
            setSetupInProgress(false);
            return;
        }
        refreshHome();
        int step = setupStep();
        if (step == SetupFlow.DONE) {
            setSetupInProgress(false);
            BridgeService.setupFinished();
            Toast.makeText(this, R.string.toast_setup_ready, Toast.LENGTH_LONG).show();
            return;
        }
        String instruction;
        Intent settings;
        if (step == SetupFlow.SCREEN) {
            if (Build.VERSION.SDK_INT >= 33 && !getSharedPreferences("bridge", MODE_PRIVATE)
                    .getBoolean("restricted_settings_opened", false)) {
                instruction = getString(R.string.perm_restricted_body);
                settings = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName()));
            } else {
                instruction = getString(R.string.perm_setup_a11y);
                settings = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            }
        } else if (step == SetupFlow.OVERLAY) {
            instruction = getString(R.string.perm_setup_overlay);
            settings = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
        } else {
            instruction = getString(R.string.perm_setup_write);
            settings = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
        }
        // Overlay is optional. Opening its settings on arrival left no way to continue.
        if (step != SetupFlow.OVERLAY && setupAttemptedStep != step) {
            openSetupSettings(step, settings);
            return;
        }
        int ready = SetupFlow.readyCount(screenReady(), writeReady());
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.perm_setup_title, ready))
                .setMessage(instruction)
                .setCancelable(false);
        if (step == SetupFlow.OVERLAY) {
            builder.setPositiveButton(R.string.onb_skip, (dialog, which) -> skipOverlay())
                    .setNegativeButton(R.string.overlay_go_settings, (dialog, which) ->
                            openSetupSettings(step, settings));
        } else {
            builder.setNegativeButton(R.string.action_later, (dialog, which) -> setSetupInProgress(false))
                    .setPositiveButton(R.string.action_continue, (dialog, which) ->
                            openSetupSettings(step, settings));
        }
        setupDialog = builder.create();
        setupDialog.show();
    }

    /** Continue setup without the floating cat. More can still turn it on later. */
    private void skipOverlay() {
        setupDialog = null;
        getSharedPreferences("bridge", MODE_PRIVATE).edit()
                .putBoolean("overlay_setup_skipped", true).apply();
        // Let the current dialog finish dismissing before the next step opens.
        mainHandler.post(this::advanceSetup);
    }

    private void openSetupSettings(int step, Intent settings) {
        setupAttemptedStep = step;
        getSharedPreferences("bridge", MODE_PRIVATE).edit()
                .putInt("setup_attempted_step", step).apply();
        if (step == SetupFlow.SCREEN && settings.getAction().equals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)) {
            getSharedPreferences("bridge", MODE_PRIVATE).edit()
                    .putBoolean("restricted_settings_opened", true).apply();
        }
        if (step == SetupFlow.OVERLAY) {
            getSharedPreferences("bridge", MODE_PRIVATE).edit()
                    .putBoolean("overlay_wanted", true).commit();
        }
        try {
            Toast.makeText(this, step == SetupFlow.SCREEN ? R.string.perm_toast_a11y :
                    step == SetupFlow.OVERLAY ? R.string.perm_toast_overlay :
                            R.string.perm_toast_write, Toast.LENGTH_LONG).show();
            startActivity(settings);
        } catch (Exception error) {
            setSetupInProgress(false);
            Toast.makeText(this, R.string.toast_settings_fail, Toast.LENGTH_LONG).show();
        }
    }

    private void confirmRevoke() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.revoke_title)
                .setMessage(R.string.revoke_body)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_remove, (dialog, which) -> {
                    BridgeService.revoke(this);
                    ConnectionActivity.clearProgress(this);
                    refreshHome();
                }).show();
    }

    private void showPermissionGuide() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(22), dp(8), dp(22), dp(8));
        scroll.addView(body);
        body.addView(label(getString(R.string.perm_intro), 14, INK, false));
        Button screen = button(getString(R.string.perm_screen), true);
        screen.setOnClickListener(v -> openScreenControl());
        body.addView(screen);
        body.addView(label(getString(R.string.perm_restricted_body), 13, MUTED, false));
        Button overlay = button(getString(R.string.perm_overlay), false);
        overlay.setOnClickListener(v -> {
            getSharedPreferences("bridge", MODE_PRIVATE).edit().putBoolean("overlay_wanted", true).commit();
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        });
        body.addView(overlay);
        Button brightness = button(getString(R.string.perm_brightness), false);
        brightness.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                Uri.parse("package:" + getPackageName()))));
        body.addView(brightness);
        body.addView(label(getString(R.string.perm_battery), 13, MUTED, false));
        Button battery = button(getString(R.string.perm_battery_button), false);
        battery.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception error) {
                Toast.makeText(this, R.string.toast_battery_settings, Toast.LENGTH_LONG).show();
            }
        });
        body.addView(battery);
        body.addView(label(getString(R.string.perm_autostart), 13, MUTED, false));
        new AlertDialog.Builder(this).setTitle(R.string.perm_title).setView(scroll)
                .setPositiveButton(R.string.action_done, null).show();
    }

    private void openScreenControl() {
        if (Build.VERSION.SDK_INT >= 33 && !getSharedPreferences("bridge", MODE_PRIVATE)
                .getBoolean("restricted_settings_opened", false)) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.perm_restricted_title)
                    .setMessage(R.string.perm_restricted_body)
                    .setNegativeButton(R.string.action_later, null)
                    .setPositiveButton(R.string.perm_open_app_info, (dialog, which) -> {
                        getSharedPreferences("bridge", MODE_PRIVATE).edit()
                                .putBoolean("restricted_settings_opened", true).apply();
                        try {
                            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:" + getPackageName())));
                        } catch (Exception error) {
                            Toast.makeText(this, R.string.toast_app_info_fail, Toast.LENGTH_LONG).show();
                        }
                    }).show();
            return;
        }
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (Exception error) {
            Toast.makeText(this, R.string.toast_a11y_fail, Toast.LENGTH_LONG).show();
        }
    }

    private void setupBluetooth() {
        if (BridgeService.trustedName(this).isEmpty()) {
            openConnection();
            return;
        }
        new AlertDialog.Builder(this).setTitle(R.string.bt_title)
                .setMessage(R.string.bt_body)
                .setNegativeButton(R.string.action_later, null)
                .setPositiveButton(R.string.bt_go, (dialog, which) -> {
                    if (!BluetoothLink.permission(this)) requestPermissions(
                            new String[]{android.Manifest.permission.BLUETOOTH_CONNECT}, 14);
                    else openBluetoothSettings();
                }).show();
    }

    private void openBluetoothSettings() {
        try {
            startForegroundService(new Intent(this, BridgeService.class));
            startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
        } catch (Exception error) {
            Toast.makeText(this, R.string.toast_bt_settings_fail, Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != 29 || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            BridgeService.shareFile(this, uri);
            sendSelectedFileToComputer(this);
        } catch (Exception error) {
            Toast.makeText(this, R.string.toast_file_unopenable, Toast.LENGTH_LONG).show();
        }
    }

    static void sendClipboardToComputer(Activity activity) {
        if (BridgeService.bluetoothOnline() && !BridgeService.wifiOnline(activity)) {
            Toast.makeText(activity, R.string.toast_clipboard_needs_wifi, Toast.LENGTH_LONG).show();
            return;
        }
        String address = activity.getSharedPreferences("bridge", MODE_PRIVATE).getString("computer_address", "");
        String token = activity.getSharedPreferences("bridge", MODE_PRIVATE).getString("computer_token", "");
        if (address.isEmpty() || token.isEmpty()) {
            Toast.makeText(activity, R.string.toast_connect_first, Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = clipboard == null ? null : clipboard.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) {
            Toast.makeText(activity, R.string.toast_copy_first, Toast.LENGTH_SHORT).show();
            return;
        }
        CharSequence content = clip.getItemAt(0).coerceToText(activity);
        String value = content == null ? "" : content.toString();
        if (value.isEmpty()) {
            Toast.makeText(activity, R.string.toast_clipboard_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        byte[] payload = ("{\"text\":" + JSONObject.quote(value) + "}")
                .getBytes(StandardCharsets.UTF_8);
        if (payload.length > 262144) {
            Toast.makeText(activity, R.string.toast_text_too_big, Toast.LENGTH_LONG).show();
            return;
        }
        new Thread(() -> {
            boolean sent = false;
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL("http://" + address + ":8793/clipboard").openConnection();
                connection.setConnectTimeout(4000);
                connection.setReadTimeout(6000);
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                RequestAuth.Signed signed = RequestAuth.sign("POST", "/clipboard", token, payload,
                        System.currentTimeMillis() / 1000L);
                for (Map.Entry<String, String> field : signed.headers.entrySet()) {
                    connection.setRequestProperty(field.getKey(), field.getValue());
                }
                connection.setFixedLengthStreamingMode(payload.length);
                try (OutputStream output = connection.getOutputStream()) { output.write(payload); }
                sent = connection.getResponseCode() == 200;
            } catch (Exception ignored) {
                // The computer may be off or its clipboard receiver may not be running.
            } finally {
                if (connection != null) connection.disconnect();
            }
            boolean delivered = sent;
            activity.runOnUiThread(() -> Toast.makeText(activity, delivered ? R.string.toast_clipboard_sent :
                    R.string.toast_clipboard_failed, Toast.LENGTH_LONG).show());
        }, "phone-clipboard-send").start();
    }

    static void sendSelectedFileToComputer(Context context) {
        if (BridgeService.bluetoothOnline() && !BridgeService.wifiOnline(context)) {
            Toast.makeText(context, R.string.toast_file_needs_wifi, Toast.LENGTH_LONG).show();
            return;
        }
        String address = context.getSharedPreferences("bridge", MODE_PRIVATE).getString("computer_address", "");
        String token = context.getSharedPreferences("bridge", MODE_PRIVATE).getString("computer_token", "");
        if (address.isEmpty() || token.isEmpty()) {
            Toast.makeText(context, R.string.toast_connect_first, Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(context, R.string.toast_file_sending, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String result = context.getString(R.string.toast_file_failed);
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL("http://" + address + ":8793/file-ready").openConnection();
                connection.setConnectTimeout(4000);
                connection.setReadTimeout(3600000);
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                byte[] payload = "{}".getBytes(StandardCharsets.UTF_8);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                RequestAuth.Signed signed = RequestAuth.sign("POST", "/file-ready", token, payload,
                        System.currentTimeMillis() / 1000L);
                for (Map.Entry<String, String> field : signed.headers.entrySet()) {
                    connection.setRequestProperty(field.getKey(), field.getValue());
                }
                connection.setFixedLengthStreamingMode(payload.length);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(payload);
                }
                InputStream stream = connection.getResponseCode() < 400
                        ? connection.getInputStream() : connection.getErrorStream();
                if (stream != null) {
                    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                    try (InputStream input = stream) {
                        byte[] chunk = new byte[1024];
                        int count;
                        while ((count = input.read(chunk)) != -1 && buffer.size() < 4096)
                            buffer.write(chunk, 0, count);
                    }
                    JSONObject reply = new JSONObject(buffer.toString("UTF-8"));
                    if (connection.getResponseCode() == 200)
                        result = context.getString(R.string.toast_file_sent, fileName(reply.optString("saved", context.getString(R.string.toast_file_name))));
                    else result = reply.optString("error", reply.optString("message", result));
                }
            } catch (Exception ignored) {
                // The receiver may be unavailable or the transfer may have stopped.
            } finally {
                if (connection != null) connection.disconnect();
            }
            String message = result;
            new Handler(Looper.getMainLooper()).post(() ->
                    Toast.makeText(context.getApplicationContext(), message, Toast.LENGTH_LONG).show());
        }, "phone-file-send").start();
    }

    private static String fileName(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return path.substring(slash + 1);
    }

    private LinearLayout panel(int fill, int stroke) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(16), dp(16), dp(16));
        panel.setBackground(round(fill, stroke, 18));
        return panel;
    }

    private TextView label(String value, int size, int color, boolean bold) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        text.setGravity(Gravity.START);
        text.setLineSpacing(0, 1.14f);
        if (bold) text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return text;
    }

    private Button button(String value, boolean primary) {
        Button button = new Button(this);
        button.setText(value);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setSingleLine(false);
        button.setMaxLines(3);
        button.setEllipsize(null);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setPaddingRelative(dp(16), dp(10), dp(16), dp(10));
        button.setGravity(Gravity.CENTER);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setElevation(0);
        styleButton(button, primary);
        return button;
    }

    private void styleButton(Button button, boolean primary) {
        button.setTextColor(primary ? Color.WHITE : CORAL_DARK);
        button.setBackground(round(primary ? CORAL : CREAM, primary ? CORAL : BORDER, 14));
    }

    private GradientDrawable round(int fill, int stroke, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(radius));
        shape.setStroke(dp(1), stroke);
        return shape;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
