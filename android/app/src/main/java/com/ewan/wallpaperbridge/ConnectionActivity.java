package com.ewan.wallpaperbridge;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

/** One step of first connection. The copy comes from {@link FirstConnection}. */
public class ConnectionActivity extends Activity {
    private static final int CREAM = Color.rgb(250, 247, 241);
    private static final int PAPER = Color.WHITE;
    private static final int INK = Color.rgb(61, 52, 47);
    private static final int MUTED = Color.rgb(108, 98, 91);
    private static final int CORAL = Color.rgb(185, 80, 56);
    private static final int CORAL_DARK = Color.rgb(160, 70, 49);
    private static final int MINT = Color.rgb(72, 123, 104);
    private static final int BORDER = Color.rgb(232, 221, 210);

    private static volatile boolean visible;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean askedToWait;
    private boolean askedForHelp;
    private boolean howOpen;
    private String rendered = "";

    public static boolean isVisible() { return visible; }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) {
            askedToWait = state.getBoolean("wait");
            askedForHelp = state.getBoolean("help");
            howOpen = state.getBoolean("how");
        }
        getWindow().setStatusBarColor(CREAM);
        getWindow().setNavigationBarColor(CREAM);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("wait", askedToWait);
        state.putBoolean("help", askedForHelp);
        state.putBoolean("how", howOpen);
        super.onSaveInstanceState(state);
    }

    @Override protected void onStart() {
        super.onStart();
        visible = true;
        BridgeService.appActivityStarted();
        try {
            startForegroundService(new Intent(this, BridgeService.class));
        } catch (Exception ignored) { }
        render();
    }

    @Override protected void onStop() {
        visible = false;
        BridgeService.appActivityStopped();
        handler.removeCallbacksAndMessages(null);
        super.onStop();
    }

    private void render() {
        if (isFinishing()) return;
        handler.removeCallbacksAndMessages(null);
        PairingGate.Request request = BridgeService.pendingPair();
        String trusted = BridgeService.trustedName(this);
        String code = request == null || request.confirmCode == null ? "" : request.confirmCode;
        String name = trusted.isEmpty() && request != null ? request.name : trusted;
        long syncedAt = getSharedPreferences("bridge", MODE_PRIVATE).getLong("capture_sync_last_success_at", 0);
        String sync = syncedAt <= 0 ? "" : android.text.format.DateFormat.getTimeFormat(this).format(new java.util.Date(syncedAt));
        FirstConnection.Screen screen = FirstConnection.present(new FirstConnection.Facts(
                !trusted.isEmpty(), askedToWait, askedForHelp, code, name, sync, localAddress()));
        String signature = screen.state + screen.code + screen.sentence + howOpen + screen.detail;
        if (signature.equals(rendered) && getWindow().getDecorView().findViewById(android.R.id.content) != null
                && rendered.length() > 0) {
            handler.postDelayed(this::render, 700);
            return;
        }
        rendered = signature;
        draw(screen, request);
        handler.postDelayed(this::render, 700);
    }

    private void draw(FirstConnection.Screen screen, PairingGate.Request request) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(CREAM);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(22), dp(18), dp(22), dp(28));
        scroll.addView(page);

        TextView back = label("关闭", 14, MUTED, false);
        back.setOnClickListener(v -> finish());
        page.addView(back);

        TextView title = label(screen.title, 28, screen.green ? MINT : INK, true);
        title.setPadding(0, dp(18), 0, 0);
        page.addView(title);
        TextView sentence = label(screen.sentence, 16, INK, false);
        sentence.setPadding(0, dp(10), 0, 0);
        page.addView(sentence);

        if (!screen.detail.isEmpty()) {
            TextView detail = label(screen.detail, 14, MUTED, false);
            detail.setPadding(0, dp(8), 0, 0);
            page.addView(detail);
        }
        if (!screen.code.isEmpty()) {
            TextView digits = label(screen.code, 40, INK, true);
            digits.setGravity(Gravity.CENTER);
            digits.setLetterSpacing(0.2f);
            digits.setPadding(0, dp(22), 0, dp(8));
            page.addView(digits);
        }
        if (!screen.copyText.isEmpty()) {
            TextView card = label(screen.copyText, 15, INK, false);
            card.setPadding(dp(14), dp(14), dp(14), dp(14));
            card.setBackground(round(PAPER, BORDER, 14));
            LinearLayout.LayoutParams cardLayout = new LinearLayout.LayoutParams(-1, -2);
            cardLayout.topMargin = dp(16);
            page.addView(card, cardLayout);
        }
        if (screen.showAddress) {
            TextView address = label(screen.address, 13, MUTED, false);
            address.setPadding(0, dp(14), 0, 0);
            address.setTextIsSelectable(true);
            page.addView(address);
            TextView copyAddress = label("复制地址", 14, CORAL_DARK, true);
            copyAddress.setPadding(0, dp(8), 0, 0);
            copyAddress.setOnClickListener(v -> copy(screen.address));
            page.addView(copyAddress);
        }
        if (screen.showQr && screen.address.startsWith("http://")) {
            try {
                boolean[][] matrix = QrCode.encode(screen.address);
                ImageView image = new ImageView(this);
                image.setImageBitmap(bitmap(matrix));
                image.setContentDescription("连接地址二维码");
                LinearLayout.LayoutParams qr = new LinearLayout.LayoutParams(dp(168), dp(168));
                qr.topMargin = dp(12);
                qr.gravity = Gravity.CENTER_HORIZONTAL;
                page.addView(image, qr);
                TextView caption = label("电脑找不到时，把这个地址给电脑。", 12, MUTED, false);
                caption.setPadding(0, dp(6), 0, 0);
                page.addView(caption);
            } catch (RuntimeException ignored) { }
        }
        for (String cause : screen.causes) {
            TextView line = label(cause, 15, INK, false);
            line.setPadding(0, dp(12), 0, 0);
            page.addView(line);
        }
        if (!screen.how.isEmpty()) {
            TextView toggle = label(howOpen ? "收起" : "怎么做", 14, CORAL_DARK, true);
            toggle.setPadding(0, dp(16), 0, 0);
            toggle.setOnClickListener(v -> {
                howOpen = !howOpen;
                rendered = "";
                render();
            });
            page.addView(toggle);
            if (howOpen) {
                TextView body = label(screen.how, 14, MUTED, false);
                body.setPadding(0, dp(8), 0, 0);
                page.addView(body);
            }
        }
        if (!screen.primary.isEmpty()) {
            Button primary = button(screen.primary, true);
            LinearLayout.LayoutParams primaryLayout = new LinearLayout.LayoutParams(-1, dp(52));
            primaryLayout.topMargin = dp(22);
            page.addView(primary, primaryLayout);
            primary.setOnClickListener(v -> act(screen.primaryAction, request));
        }
        if (!screen.secondary.isEmpty()) {
            Button secondary = button(screen.secondary, false);
            LinearLayout.LayoutParams secondaryLayout = new LinearLayout.LayoutParams(-1, dp(48));
            secondaryLayout.topMargin = dp(10);
            page.addView(secondary, secondaryLayout);
            secondary.setOnClickListener(v -> act(screen.secondaryAction, request));
        }
        if (!screen.bluetoothReason.isEmpty()) {
            TextView reason = label(screen.bluetoothReason, 13, MUTED, false);
            reason.setPadding(0, dp(16), 0, 0);
            page.addView(reason);
        }
        setContentView(scroll);
    }

    private void act(String action, PairingGate.Request request) {
        if ("start".equals(action)) {
            askedToWait = true;
            askedForHelp = false;
        } else if ("problems".equals(action)) {
            askedForHelp = true;
        } else if ("retry".equals(action)) {
            askedForHelp = false;
            askedToWait = true;
        } else if ("copy".equals(action)) {
            copy(FirstConnection.AI_LINE);
            return;
        } else if ("allow".equals(action) || "deny".equals(action)) {
            if (request == null || !BridgeService.decidePair(this, request.id, "allow".equals(action))) {
                Toast.makeText(this, "这条连接请求已失效，请从电脑重新发起", Toast.LENGTH_LONG).show();
            } else if ("allow".equals(action)) {
                BridgeService.requestCaptureSync();
            }
        } else if ("done".equals(action)) {
            finish();
            return;
        } else if ("bluetooth".equals(action)) {
            if (!BridgeService.trustedName(this).isEmpty()) openBluetooth();
            return;
        }
        rendered = "";
        render();
    }

    private void openBluetooth() {
        try {
            if (!BluetoothLink.permission(this)) {
                requestPermissions(new String[]{android.Manifest.permission.BLUETOOTH_CONNECT}, 14);
                return;
            }
            startForegroundService(new Intent(this, BridgeService.class));
            startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
        } catch (Exception error) {
            Toast.makeText(this, "请在手机设置中打开蓝牙", Toast.LENGTH_LONG).show();
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == 14 && BluetoothLink.permission(this)) openBluetooth();
    }

    private void copy(String value) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("catdiao", value));
        Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
    }

    private Bitmap bitmap(boolean[][] matrix) {
        int scale = 8;
        int quiet = 2;
        int side = (matrix.length + quiet * 2) * scale;
        Bitmap bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.WHITE);
        for (int row = 0; row < matrix.length; row++) {
            for (int col = 0; col < matrix[row].length; col++) {
                if (!matrix[row][col]) continue;
                int left = (col + quiet) * scale;
                int top = (row + quiet) * scale;
                for (int y = 0; y < scale; y++) {
                    for (int x = 0; x < scale; x++) bitmap.setPixel(left + x, top + y, INK);
                }
            }
        }
        return bitmap;
    }

    private String localAddress() {
        try {
            String fallback = null;
            Enumeration<NetworkInterface> networks = NetworkInterface.getNetworkInterfaces();
            while (networks != null && networks.hasMoreElements()) {
                NetworkInterface network = networks.nextElement();
                if (!network.isUp() || network.isLoopback()) continue;
                Enumeration<InetAddress> addresses = network.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (!(address instanceof Inet4Address) || address.isLoopbackAddress()) continue;
                    String ip = address.getHostAddress();
                    if (network.getName().startsWith("wlan")) return "http://" + ip + ":" + FirstConnection.PHONE_PORT;
                    if (address.isSiteLocalAddress()) fallback = ip;
                }
            }
            if (fallback != null) return "http://" + fallback + ":" + FirstConnection.PHONE_PORT;
        } catch (Exception ignored) { }
        return "请先连上和电脑相同的 Wi-Fi";
    }

    private TextView label(String value, int size, int color, boolean bold) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        text.setLineSpacing(0, 1.2f);
        if (bold) text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return text;
    }

    private Button button(String value, boolean primary) {
        Button button = new Button(this);
        button.setText(value);
        button.setAllCaps(false);
        button.setTextSize(16);
        button.setTextColor(primary ? Color.WHITE : CORAL_DARK);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setBackground(round(primary ? CORAL : PAPER, primary ? CORAL : BORDER, 14));
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
