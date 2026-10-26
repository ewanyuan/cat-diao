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

public class MainActivity extends Activity {
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
            Toast.makeText(this, "猫叼暂时连不上小窝，请重新打开", Toast.LENGTH_LONG).show();
        }
        mainHandler.post(refresh);
        mainHandler.postDelayed(this::maybeStartSetup, 650);
        if (Build.VERSION.SDK_INT >= 37 &&
                checkSelfPermission(LOCAL_NETWORK_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{LOCAL_NETWORK_PERMISSION}, 1);
        }
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
            Toast.makeText(this, overlayGranted ? "悬浮猫已开启" :
                    "悬浮猫未开启，其他收藏方式仍可使用", Toast.LENGTH_LONG).show();
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
            else Toast.makeText(this, "蓝牙未开启，仍可通过 Wi-Fi 连接", Toast.LENGTH_LONG).show();
            return;
        }
        if (requestCode == 1 && (results.length == 0 || results[0] != PackageManager.PERMISSION_GRANTED)) {
            Toast.makeText(this, "允许局域网访问后，猫叼才能找到小窝", Toast.LENGTH_LONG).show();
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
        nameBlock.setPadding(dp(8), 0, 0, 0);
        header.addView(nameBlock, new LinearLayout.LayoutParams(0, -2, 1));
        nameBlock.addView(label("猫叼", 22, INK, true));
        TextView more = label("⋯", 26, CORAL_DARK, false);
        more.setGravity(Gravity.CENTER);
        more.setContentDescription("更多操作");
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
        summary = label("正在查看收藏…", 21, INK, true);
        hero.addView(summary);
        summaryDetail = label("", 14, CORAL_DARK, false);
        summaryDetail.setPadding(0, dp(4), 0, 0);
        hero.addView(summaryDetail);
        computerStatus = label("", 13, MUTED, false);
        computerStatus.setPadding(0, dp(10), 0, dp(2));
        hero.addView(computerStatus);
        Button capture = button("收藏复制内容", true);
        LinearLayout.LayoutParams captureLayout = new LinearLayout.LayoutParams(-1, dp(50));
        captureLayout.topMargin = dp(15);
        hero.addView(capture, captureLayout);
        capture.setOnClickListener(v -> startActivity(new Intent(this, CaptureActivity.class)
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
        connectionRetry = button("再试一次", false);
        connectionPanel.addView(connectionRetry);
        connectionRetry.setOnClickListener(v -> {
            try {
                startForegroundService(new Intent(this, BridgeService.class)
                        .setAction(BridgeService.ACTION_CHECK_CONNECTION));
            } catch (Exception error) {
                Toast.makeText(this, "连接检查未能启动，请重新打开猫叼。", Toast.LENGTH_LONG).show();
            }
        });
        connectionBluetooth = button("用蓝牙连接", false);
        connectionBluetooth.setOnClickListener(v -> setupBluetooth());
        connectionPanel.addView(connectionBluetooth);
        connectionPanel.addView(label("开始连接  ›", 13, CORAL_DARK, true));
        connectionPanel.setOnClickListener(v -> openConnection());
        revokeButton = button("移除小窝连接", false);
        LinearLayout.LayoutParams revokeLayout = new LinearLayout.LayoutParams(-1, dp(50));
        revokeLayout.topMargin = dp(14);
        page.addView(revokeButton, revokeLayout);
        revokeButton.setOnClickListener(v -> confirmRevoke());

        controlPanel = panel(PAPER, BORDER);
        controlPanel.setVisibility(View.GONE);
        LinearLayout.LayoutParams controlLayout = new LinearLayout.LayoutParams(-1, -2);
        controlLayout.topMargin = dp(14);
        page.addView(controlPanel, controlLayout);
        controlPanel.addView(label("继续设置手机控制", 17, INK, true));
        controlStatus = label("", 13, MUTED, false);
        controlStatus.setPadding(0, dp(6), 0, 0);
        controlPanel.addView(controlStatus);
        controlPanel.setOnClickListener(v -> showPermissionGuide());

        recentPanel = panel(PAPER, BORDER);
        LinearLayout.LayoutParams recentsLayout = new LinearLayout.LayoutParams(-1, -2);
        recentsLayout.topMargin = dp(14);
        page.addView(recentPanel, recentsLayout);
        recentPanel.addView(label("最近收藏", 17, INK, true));
        recentList = new LinearLayout(this);
        recentList.setOrientation(LinearLayout.VERTICAL);
        recentPanel.addView(recentList);
    }

    private void refreshHome() {
        String trusted = BridgeService.trustedName(this);
        int pending = 0;
        try (CaptureStore store = new CaptureStore(this)) {
            int total = store.totalCount();
            pending = store.pendingCount();
            recentPanel.setVisibility(total == 0 ? View.GONE : View.VISIBLE);
            if (total == 0) {
                summary.setText("还没有收藏");
                summaryDetail.setText("看到喜欢的内容，分享给猫叼就行。");
            } else if (pending > 0) {
                summary.setText(pending + " 条待叼回小窝");
                summaryDetail.setText("已保存在手机，连上小窝后自动送达。");
            } else {
                summary.setText("都叼回小窝了");
                summaryDetail.setText("共收藏 " + total + " 条。");
            }
            renderRecent(store.recent(3));
        } catch (Exception error) {
            summary.setText("暂时读不到收藏");
            summaryDetail.setText("请稍后重新打开猫叼。");
            recentPanel.setVisibility(View.GONE);
        }
        int ready = setupReadyCount();
        boolean online = computerOnline();
        ComputerConnection.Snapshot check = BridgeService.computerConnectionSnapshot();
        boolean checking = check.phase == ComputerConnection.Phase.CHECKING && BridgeService.isRunning();
        boolean bluetoothOnly = BridgeService.bluetoothOnline() && !BridgeService.wifiOnline(this);
        revokeButton.setVisibility(trusted.isEmpty() ? View.GONE : View.VISIBLE);
        connectionRetry.setVisibility(trusted.isEmpty() || online ? View.GONE : View.VISIBLE);
        connectionRetry.setEnabled(!checking);
        connectionRetry.setText(checking ? "正在找小窝…" : "再试一次");
        connectionBluetooth.setVisibility(!trusted.isEmpty() && !online ? View.VISIBLE : View.GONE);
        computerStatus.setText(computerConnectionText(trusted));
        computerStatus.setTextColor(trusted.isEmpty() ? MUTED : online ? MINT : CORAL_DARK);
        computerStatus.setVisibility(View.VISIBLE);
        if (trusted.isEmpty()) {
            PairingGate.Request pendingPair = BridgeService.pendingPair();
            if (pendingPair != null) {
                connectionTitle.setText("电脑正在请求连接");
                connectionSteps.setText("点这里核对 6 位数字。");
            } else {
                connectionTitle.setText("把电脑连成你的小窝");
                connectionSteps.setText("点这里，一步一步连。电脑上运行 run.ps1 pair。");
            }
            connectionPanel.setVisibility(View.VISIBLE);
        } else if (!online) {
            if (checking) {
                long seconds = (check.remainingMs() + 999) / 1000;
                connectionTitle.setText("正在找小窝 · " + seconds + " 秒");
                connectionSteps.setText("收藏已保存在手机，连上后会自动送达。");
            } else {
                connectionTitle.setText("还没找到小窝");
                connectionSteps.setText(!BridgeService.isRunning() ? "重新打开猫叼，再试一次。收藏还在手机里。" :
                        "确认电脑上的猫叼接收已开启。换了网络？可以用蓝牙找回连接。");
            }
            connectionPanel.setVisibility(View.VISIBLE);
        } else if (bluetoothOnly) {
            connectionTitle.setText("已通过蓝牙连接");
            connectionSteps.setText("收藏会自动送达。传文件或剪贴板文字时，请连同一个 Wi-Fi。");
            connectionPanel.setVisibility(View.VISIBLE);
        } else if (pending > 0) {
            String syncError = getSharedPreferences("bridge", MODE_PRIVATE).getString("capture_sync_error", "");
            connectionTitle.setText(syncError.isEmpty() ? "正在送到小窝" : "收藏还在手机里");
            connectionSteps.setText(syncError.isEmpty() ? "稍等一下，正在补送收藏。"
                    : "这次还没送达，猫叼会自动再试。");
            connectionPanel.setVisibility(View.VISIBLE);
        } else {
            connectionPanel.setVisibility(View.GONE);
        }
        controlPanel.setVisibility(trusted.isEmpty() || ready == 3 ? View.GONE : View.VISIBLE);
        if (!trusted.isEmpty() && ready < 3) {
            controlStatus.setText(PermissionGuide.SPLIT_INTRO);
        }
        showPairRequest();
    }

    private boolean computerOnline() {
        return BridgeService.bluetoothOnline() || BridgeService.isRunning() && BridgeService.wifiOnline(this);
    }

    private String computerConnectionText(String trusted) {
        if (trusted.isEmpty()) return "还未与电脑配对";
        if (!BridgeService.isRunning()) return "手机连接服务已停止，请重新打开猫叼。";
        if (computerOnline()) return (BridgeService.wifiOnline(this) ? "Wi-Fi 已连接 · " : "蓝牙已连接 · ") + trusted;
        ComputerConnection.Snapshot check = BridgeService.computerConnectionSnapshot();
        if (check.phase == ComputerConnection.Phase.CHECKING) {
            return "正在找小窝 · " + trusted;
        }
        return "小窝暂未连接 · " + trusted;
    }

    private void renderRecent(JSONArray items) {
        String signature = items.toString();
        if (signature.equals(recentSignature)) return;
        recentSignature = signature;
        recentList.removeAllViews();
        if (items.length() == 0) {
            TextView empty = label("还没有收藏。下次看到想留下的内容，直接分享到猫叼。", 14, MUTED, false);
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
            TextView title = label(value.isEmpty() ? "未命名收藏" : value, 15, INK, true);
            title.setMaxLines(2);
            title.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams titleLayout = new LinearLayout.LayoutParams(-1, -2);
            titleLayout.topMargin = dp(10);
            recentList.addView(title, titleLayout);
            boolean pending = "pending".equals(item.optString("state"));
            String source = item.optString("source", "分享");
            recentList.addView(label(source + "  ·  " + (pending ? "待叼回小窝" : "已到小窝"),
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

    private void showMore(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(Menu.NONE, 1, 1, "发到小窝");
        menu.getMenu().add(Menu.NONE, 2, 2, BridgeService.overlayEnabled(this)
                ? "关闭悬浮猫" : "开启悬浮猫");
        menu.getMenu().add(Menu.NONE, 3, 3, "小窝连接");
        menu.getMenu().add(Menu.NONE, 4, 4, "使用指南");
        menu.getMenu().add(Menu.NONE, 5, 5, "蓝牙连接");
        menu.getMenu().add(Menu.NONE, 6, 6, "权限与省电");
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) showTransfer();
            else if (item.getItemId() == 2) toggleOverlay();
            else if (item.getItemId() == 3) openConnection();
            else if (item.getItemId() == 4)
                startActivity(new Intent(this, OnboardingActivity.class));
            else if (item.getItemId() == 5) setupBluetooth();
            else if (item.getItemId() == 6) showPermissionGuide();
            return true;
        });
        menu.show();
    }

    private void showTransfer() {
        if (BridgeService.trustedName(this).isEmpty()) {
            Toast.makeText(this, "请先从小窝发起连接，并在猫叼确认", Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("发到小窝")
                .setItems(new String[]{"发送文件", "发送复制的文字"}, (dialog, choice) -> {
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
            getSharedPreferences("bridge", MODE_PRIVATE).edit().putBoolean("overlay_wanted", false).apply();
            BridgeService.setOverlay(this, false);
            return;
        }
        if (Settings.canDrawOverlays(this)) {
            BridgeService.setOverlay(this, true);
        } else {
            new AlertDialog.Builder(this)
                    .setTitle("开启悬浮猫")
                    .setMessage("允许悬浮显示后，在其他应用里点猫叼就能收藏，或把文字、文件发到小窝。")
                    .setNegativeButton("暂不开启", null)
                    .setPositiveButton("去系统设置", (dialog, which) -> {
                        getSharedPreferences("bridge", MODE_PRIVATE).edit()
                                .putBoolean("overlay_wanted", true).apply();
                        overlayPermissionPending = true;
                        startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + getPackageName())));
                    }).show();
        }
    }

    private int setupReadyCount() {
        int count = 0;
        if (PhoneAccessibilityService.isEnabled(this)) count++;
        if (Settings.canDrawOverlays(this)) count++;
        if (Settings.System.canWrite(this)) count++;
        return count;
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
                setupReadyCount() == 3) return;
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
        int ready = setupReadyCount();
        refreshHome();
        if (ready == 3) {
            setSetupInProgress(false);
            BridgeService.setupFinished();
            Toast.makeText(this, "小窝连接已设好", Toast.LENGTH_LONG).show();
            return;
        }
        String instruction;
        Intent settings;
        int step;
        if (!PhoneAccessibilityService.isEnabled(this)) {
            step = 1;
            if (Build.VERSION.SDK_INT >= 33 && !getSharedPreferences("bridge", MODE_PRIVATE)
                    .getBoolean("restricted_settings_opened", false)) {
                instruction = PermissionGuide.RESTRICTED_SETTINGS_BODY;
                settings = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName()));
            } else {
                instruction = "在系统设置中开启「猫叼屏幕控制」，然后返回猫叼。收藏不需要这一步。";
                settings = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            }
        } else if (!Settings.canDrawOverlays(this)) {
            step = 2;
            instruction = "允许「猫叼」显示在其他应用上层，然后返回猫叼。";
            settings = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
        } else {
            step = 3;
            instruction = "允许「猫叼」修改系统设置，然后返回猫叼。";
            settings = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
        }
        if (setupAttemptedStep != step) {
            openSetupSettings(step, settings);
            return;
        }
        setupDialog = new AlertDialog.Builder(this)
                .setTitle("继续连接小窝（" + ready + "/3）")
                .setMessage(instruction)
                .setNegativeButton("稍后", (dialog, which) -> setSetupInProgress(false))
                .setPositiveButton("继续", (dialog, which) -> openSetupSettings(step, settings))
                .setCancelable(false).create();
        setupDialog.show();
    }

    private void openSetupSettings(int step, Intent settings) {
        setupAttemptedStep = step;
        getSharedPreferences("bridge", MODE_PRIVATE).edit()
                .putInt("setup_attempted_step", step).apply();
        if (step == 1 && settings.getAction().equals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)) {
            getSharedPreferences("bridge", MODE_PRIVATE).edit()
                    .putBoolean("restricted_settings_opened", true).apply();
        }
        if (step == 2) {
            getSharedPreferences("bridge", MODE_PRIVATE).edit()
                    .putBoolean("overlay_wanted", true).apply();
        }
        try {
            Toast.makeText(this, step == 1 ? "请开启「猫叼屏幕控制」，然后返回猫叼" :
                    step == 2 ? "请允许猫叼悬浮显示，然后返回猫叼" :
                            "请允许猫叼修改系统设置，然后返回猫叼", Toast.LENGTH_LONG).show();
            startActivity(settings);
        } catch (Exception error) {
            setSetupInProgress(false);
            Toast.makeText(this, "无法打开系统设置，请从手机设置里开启猫叼", Toast.LENGTH_LONG).show();
        }
    }

    private void confirmRevoke() {
        new AlertDialog.Builder(this)
                .setTitle("移除小窝连接？")
                .setMessage("这台电脑将不能再连接猫叼。还没送到电脑的收藏仍保存在手机。")
                .setNegativeButton("取消", null)
                .setPositiveButton("移除", (dialog, which) -> {
                    BridgeService.revoke(this);
                    refreshHome();
                }).show();
    }

    private void showPermissionGuide() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(22), dp(8), dp(22), dp(8));
        scroll.addView(body);
        body.addView(label(PermissionGuide.SPLIT_INTRO, 14, INK, false));
        Button screen = button("开启屏幕控制", true);
        screen.setOnClickListener(v -> openScreenControl());
        body.addView(screen);
        body.addView(label(PermissionGuide.RESTRICTED_SETTINGS_BODY, 13, MUTED, false));
        Button overlay = button("悬浮窗（可选）", false);
        overlay.setOnClickListener(v -> {
            getSharedPreferences("bridge", MODE_PRIVATE).edit().putBoolean("overlay_wanted", true).apply();
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        });
        body.addView(overlay);
        Button brightness = button("允许调节亮度", false);
        brightness.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                Uri.parse("package:" + getPackageName()))));
        body.addView(brightness);
        body.addView(label(PermissionGuide.BATTERY_BODY, 13, MUTED, false));
        Button battery = button("加入电池优化白名单", false);
        battery.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception error) {
                Toast.makeText(this, "请到系统设置的电池页面，允许猫叼在后台运行", Toast.LENGTH_LONG).show();
            }
        });
        body.addView(battery);
        body.addView(label(PermissionGuide.AUTOSTART_BODY, 13, MUTED, false));
        new AlertDialog.Builder(this).setTitle("权限与省电").setView(scroll)
                .setPositiveButton("完成", null).show();
    }

    private void openScreenControl() {
        if (Build.VERSION.SDK_INT >= 33 && !getSharedPreferences("bridge", MODE_PRIVATE)
                .getBoolean("restricted_settings_opened", false)) {
            new AlertDialog.Builder(this)
                    .setTitle(PermissionGuide.RESTRICTED_SETTINGS_TITLE)
                    .setMessage(PermissionGuide.RESTRICTED_SETTINGS_BODY)
                    .setNegativeButton("稍后", null)
                    .setPositiveButton("打开应用信息", (dialog, which) -> {
                        getSharedPreferences("bridge", MODE_PRIVATE).edit()
                                .putBoolean("restricted_settings_opened", true).apply();
                        try {
                            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:" + getPackageName())));
                        } catch (Exception error) {
                            Toast.makeText(this, "请到系统设置里打开猫叼的应用信息", Toast.LENGTH_LONG).show();
                        }
                    }).show();
            return;
        }
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (Exception error) {
            Toast.makeText(this, "请到系统设置的无障碍里开启「猫叼屏幕控制」", Toast.LENGTH_LONG).show();
        }
    }

    private void setupBluetooth() {
        if (BridgeService.trustedName(this).isEmpty()) {
            openConnection();
            return;
        }
        new AlertDialog.Builder(this).setTitle("用蓝牙找回小窝")
                .setMessage("打开电脑和手机的蓝牙，在系统设置中配对一次。以后换网络时，猫叼会自动找回连接。\n\n蓝牙可以送收藏；文件和剪贴板文字仍通过 Wi-Fi 传送。")
                .setNegativeButton("稍后", null)
                .setPositiveButton("去设置", (dialog, which) -> {
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
            Toast.makeText(this, "请在手机设置中打开蓝牙", Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != 29 || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(uri,
                    data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION);
            BridgeService.shareFile(this, uri);
            sendSelectedFileToComputer(this);
        } catch (Exception error) {
            Toast.makeText(this, "文件授权失败：" + error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    static void sendClipboardToComputer(Activity activity) {
        if (BridgeService.bluetoothOnline() && !BridgeService.wifiOnline(activity)) {
            Toast.makeText(activity, "发送到电脑剪贴板需要 Wi-Fi；也可以先收藏这段文字", Toast.LENGTH_LONG).show();
            return;
        }
        String address = activity.getSharedPreferences("bridge", MODE_PRIVATE).getString("computer_address", "");
        String token = activity.getSharedPreferences("bridge", MODE_PRIVATE).getString("computer_token", "");
        if (address.isEmpty() || token.isEmpty()) {
            Toast.makeText(activity, "请先连接小窝", Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = clipboard == null ? null : clipboard.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) {
            Toast.makeText(activity, "请先在手机上复制一段文字", Toast.LENGTH_SHORT).show();
            return;
        }
        CharSequence content = clip.getItemAt(0).coerceToText(activity);
        String value = content == null ? "" : content.toString();
        if (value.isEmpty()) {
            Toast.makeText(activity, "手机剪贴板里没有文字", Toast.LENGTH_SHORT).show();
            return;
        }
        byte[] payload = ("{\"text\":" + JSONObject.quote(value) + "}")
                .getBytes(StandardCharsets.UTF_8);
        if (payload.length > 262144) {
            Toast.makeText(activity, "文字超过 256 KB，请改用文件传输", Toast.LENGTH_LONG).show();
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
            activity.runOnUiThread(() -> Toast.makeText(activity, delivered ? "已放入小窝剪贴板" :
                    "小窝暂时没收到，稍后再试", Toast.LENGTH_LONG).show());
        }, "phone-clipboard-send").start();
    }

    static void sendSelectedFileToComputer(Context context) {
        if (BridgeService.bluetoothOnline() && !BridgeService.wifiOnline(context)) {
            Toast.makeText(context, "传文件请让手机和电脑连同一个 Wi-Fi", Toast.LENGTH_LONG).show();
            return;
        }
        String address = context.getSharedPreferences("bridge", MODE_PRIVATE).getString("computer_address", "");
        String token = context.getSharedPreferences("bridge", MODE_PRIVATE).getString("computer_token", "");
        if (address.isEmpty() || token.isEmpty()) {
            Toast.makeText(context, "请先连接小窝", Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(context, "正在发送文件到小窝…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String result = "小窝暂时没收到文件，稍后再试";
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
                        result = "已到小窝：" + fileName(reply.optString("saved", "文件"));
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
        text.setLineSpacing(0, 1.14f);
        if (bold) text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return text;
    }

    private Button button(String value, boolean primary) {
        Button button = new Button(this);
        button.setText(value);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setTextColor(primary ? Color.WHITE : CORAL_DARK);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setBackground(round(primary ? CORAL : CREAM, primary ? CORAL : BORDER, 14));
        button.setElevation(0);
        return button;
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
