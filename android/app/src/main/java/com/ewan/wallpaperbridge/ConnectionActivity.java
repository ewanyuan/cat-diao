package com.ewan.wallpaperbridge;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.text.LineBreaker;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Layout;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

/** Straight-line first connection. Steps resume after the app is left. */
public class ConnectionActivity extends CatDiaoActivity {
    private static final int CREAM = Color.rgb(250, 247, 241);
    private static final int PAPER = Color.WHITE;
    private static final int INK = Color.rgb(61, 52, 47);
    private static final int MUTED = Color.rgb(108, 98, 91);
    private static final int CORAL = Color.rgb(185, 80, 56);
    private static final int CORAL_DARK = Color.rgb(160, 70, 49);
    private static final int MINT = Color.rgb(72, 123, 104);
    private static final int BORDER = Color.rgb(232, 221, 210);

    private static final String PREF_STEP = "pair_flow_step";
    private static final String PREF_WAIT = "pair_flow_wait_at";
    private static final String PREF_QR = "pair_flow_qr";
    private static final String PREF_SAW = "pair_flow_saw_code";
    private static final String PREF_HELP = "pair_flow_help";
    private static final String PREF_ATTEMPT = "pair_flow_attempted";

    private static volatile boolean visible;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean waiting;
    private boolean qrOpen;
    private boolean attempted;
    private boolean sawCode;
    private boolean helpOpen;
    private long waitStartedAt;
    private boolean progressCleared;
    private String rendered = "";
    private TextView liveSentence;

    public static boolean isVisible() { return visible; }

    public static void clearProgress(Context context) {
        context.getSharedPreferences("bridge", MODE_PRIVATE).edit()
                .remove(PREF_STEP).remove(PREF_WAIT).remove(PREF_QR)
                .remove(PREF_SAW).remove(PREF_HELP).remove(PREF_ATTEMPT).apply();
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) {
            waiting = state.getBoolean("wait");
            qrOpen = state.getBoolean("qr");
            attempted = state.getBoolean("attempted");
            sawCode = state.getBoolean("saw");
            helpOpen = state.getBoolean("help");
            waitStartedAt = state.getLong("waitAt");
        } else {
            loadProgress(getSharedPreferences("bridge", MODE_PRIVATE));
        }
        getWindow().setStatusBarColor(CREAM);
        getWindow().setNavigationBarColor(CREAM);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("wait", waiting);
        state.putBoolean("qr", qrOpen);
        state.putBoolean("attempted", attempted);
        state.putBoolean("saw", sawCode);
        state.putBoolean("help", helpOpen);
        state.putLong("waitAt", waitStartedAt);
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
        if (!progressCleared) persist();
        super.onStop();
    }

    @Override public void onBackPressed() {
        FirstConnection.Screen screen = compose();
        if (screen.state == FirstConnection.State.GUIDE || screen.state == FirstConnection.State.PAIRED) {
            if (screen.state == FirstConnection.State.PAIRED) finish();
            else exitGuide();
            return;
        }
        if (screen.state == FirstConnection.State.PAIRING) return;
        resetToGuide();
        render();
    }

    private void render() {
        if (isFinishing()) return;
        handler.removeCallbacksAndMessages(null);
        FirstConnection.Screen screen = compose();
        if (screen.state == FirstConnection.State.PAIRING && !sawCode) {
            sawCode = true;
            persist();
        }
        if (screen.state == FirstConnection.State.PAIRED && !progressCleared) {
            progressCleared = true;
            clearProgress(this);
        }
        String structure = screen.state.name() + '|' + screen.code + '|' + screen.primaryAction
                + '|' + screen.secondaryAction + '|' + helpOpen + '|' + qrOpen + '|' + screen.detail
                + '|' + screen.commandLine + '|' + screen.title + '|' + screen.showQr
                + '|' + screen.address;
        if (structure.equals(rendered)) {
            if (liveSentence != null && screen.state == FirstConnection.State.WAITING)
                liveSentence.setText(screen.sentence);
            handler.postDelayed(this::render, 700);
            return;
        }
        rendered = structure;
        liveSentence = null;
        draw(screen, BridgeService.pendingPair());
        handler.postDelayed(this::render, 700);
    }

    private FirstConnection.Screen compose() {
        PairingGate.Request request = BridgeService.pendingPair();
        String trusted = BridgeService.trustedName(this);
        String code = request == null || request.confirmCode == null ? "" : request.confirmCode;
        String name = trusted.isEmpty() && request != null ? request.name : trusted;
        long syncedAt = getSharedPreferences("bridge", MODE_PRIVATE).getLong("capture_sync_last_success_at", 0);
        String sync = syncedAt <= 0 ? "" : android.text.format.DateFormat.getTimeFormat(this)
                .format(new java.util.Date(syncedAt));
        long waited = waitStartedAt <= 0 ? 0L : Math.max(0L, System.currentTimeMillis() - waitStartedAt);
        return FirstConnection.present(new FirstConnection.Facts(
                !trusted.isEmpty(), waiting, qrOpen, attempted, sawCode, helpOpen,
                code, name, sync, localAddress(), probeLink(), BridgeService.isRunning(), waited), phrases());
    }

    private void draw(FirstConnection.Screen screen, PairingGate.Request request) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(CREAM);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPaddingRelative(dp(22), dp(18), dp(22), dp(28));
        scroll.addView(page);
        page.addView(topBar(screen));

        TextView title = label(screen.title, 26, screen.green ? MINT : INK, true);
        title.setPaddingRelative(0, dp(18), 0, 0);
        page.addView(title, new LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT));
        TextView sentence = label(screen.sentence, 16, INK, false);
        sentence.setPaddingRelative(0, dp(10), 0, 0);
        page.addView(sentence, new LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT));
        if (screen.state == FirstConnection.State.WAITING) liveSentence = sentence;

        if ((screen.state == FirstConnection.State.GUIDE || screen.state == FirstConnection.State.WAITING)
                && !screen.copyText.isEmpty()) {
            addCopyCard(page, screen);
        }
        if (!screen.detail.isEmpty() && screen.state != FirstConnection.State.WAITING) {
            TextView detail = label(screen.detail, 14, screen.green ? MINT : MUTED, false);
            detail.setPaddingRelative(0, dp(8), 0, 0);
            page.addView(detail);
        }
        if (!screen.code.isEmpty()) {
            TextView digits = label(screen.code, 40, INK, true);
            digits.setGravity(Gravity.CENTER);
            digits.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
            digits.setSingleLine(true);
            digits.setLetterSpacing(0.18f);
            digits.setPaddingRelative(0, dp(22), 0, dp(8));
            page.addView(digits, new LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT));
        } else if (screen.state == FirstConnection.State.WAITING && !screen.detail.isEmpty()) {
            TextView pending = label(screen.detail, 20, MUTED, true);
            pending.setGravity(Gravity.CENTER);
            pending.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
            pending.setPaddingRelative(0, dp(22), 0, dp(8));
            page.addView(pending, new LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT));
        }

        if (screen.showQr && screen.state == FirstConnection.State.GUIDE && screen.address.startsWith("http://")) {
            addQr(page, screen.address);
        }

        if (!screen.primary.isEmpty()) {
            Button primary = button(screen.primary, true);
            LinearLayout.LayoutParams primaryLayout = new LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT);
            primaryLayout.topMargin = dp(18);
            page.addView(primary, primaryLayout);
            primary.setOnClickListener(v -> act(screen.primaryAction, request, screen));
        }
        if (!screen.secondary.isEmpty()) {
            Button secondary = button(screen.secondary, false);
            LinearLayout.LayoutParams secondaryLayout = new LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT);
            secondaryLayout.topMargin = dp(10);
            page.addView(secondary, secondaryLayout);
            secondary.setOnClickListener(v -> act(screen.secondaryAction, request, screen));
        }

        if (!screen.how.isEmpty()) {
            TextView toggle = label(getString(helpOpen ? R.string.conn_advanced_hide : R.string.conn_advanced_show),
                    15, CORAL_DARK, true);
            toggle.setPaddingRelative(0, dp(18), 0, dp(8));
            toggle.setOnClickListener(v -> {
                helpOpen = !helpOpen;
                persist();
                rendered = "";
                render();
            });
            page.addView(toggle);
            if (helpOpen) addHelp(page, screen);
        }
        setContentView(scroll);
    }

    private View topBar(FirstConnection.Screen screen) {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        boolean guide = screen.state == FirstConnection.State.GUIDE;
        if (guide) {
            LinearLayout exit = new LinearLayout(this);
            exit.setOrientation(LinearLayout.HORIZONTAL);
            exit.setGravity(Gravity.CENTER_VERTICAL);
            exit.setPaddingRelative(0, dp(6), dp(8), dp(6));
            TextView mark = label(getString(R.string.conn_back_mark), 18, INK, true);
            exit.addView(mark);
            TextView word = label(getString(R.string.conn_close), 14, MUTED, false);
            word.setPaddingRelative(dp(6), 0, 0, 0);
            exit.addView(word);
            exit.setOnClickListener(v -> exitGuide());
            exit.setContentDescription(getString(R.string.conn_close));
            bar.addView(exit);
            return bar;
        }
        if (screen.state != FirstConnection.State.PAIRING && screen.state != FirstConnection.State.PAIRED) {
            TextView back = label(getString(R.string.conn_back_mark), 20, INK, true);
            back.setContentDescription(getString(R.string.conn_back));
            back.setPaddingRelative(0, dp(6), dp(14), dp(6));
            back.setOnClickListener(v -> onBackPressed());
            bar.addView(back);
        }
        View spacer = new View(this);
        bar.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1));
        TextView leave = label(getString(R.string.conn_close), 14, MUTED, false);
        leave.setPaddingRelative(dp(8), dp(6), 0, dp(6));
        leave.setOnClickListener(v -> {
            if (screen.state == FirstConnection.State.PAIRED) finish();
            else exitGuide();
        });
        bar.addView(leave);
        return bar;
    }

    /** The card is the whole sentence Copy places on the clipboard. */
    private void addCopyCard(LinearLayout page, FirstConnection.Screen screen) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPaddingRelative(dp(14), dp(14), dp(14), dp(14));
        card.setBackground(round(PAPER, BORDER, 14));
        TextView phrase = label(wrapForReading(screen.copyText), 16, INK, true);
        phrase.setContentDescription(screen.copyText);
        card.addView(phrase);
        LinearLayout.LayoutParams cardLayout = new LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardLayout.topMargin = dp(16);
        page.addView(card, cardLayout);
    }

    /** Lets a long URL wrap. The clipboard still receives the original sentence. */
    private static String wrapForReading(String value) {
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            out.append(c);
            if (c == '/' || c == '.' || c == '-' || c == ':' || c == '?') out.append('\u200b');
        }
        return out.toString();
    }

    private void addHelp(LinearLayout page, FirstConnection.Screen screen) {
        TextView how = label(screen.how, 14, MUTED, false);
        page.addView(how);
        page.addView(lockedLine(screen.commandLine, 14, INK));
        if (!screen.commandWithAddress.isEmpty()) page.addView(lockedLine(screen.commandWithAddress, 14, INK));
        Button copyCommand = button(getString(R.string.conn_copy_command), false);
        LinearLayout.LayoutParams commandLayout = new LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT);
        commandLayout.topMargin = dp(10);
        page.addView(copyCommand, commandLayout);
        copyCommand.setOnClickListener(v -> copy(screen.commandWithAddress.isEmpty()
                ? screen.commandLine : screen.commandWithAddress));
        if (screen.address.startsWith("http://")) {
            page.addView(lockedLine(screen.address, 14, INK));
            Button copyAddress = button(getString(R.string.conn_copy_address), false);
            LinearLayout.LayoutParams addressLayout = new LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT);
            addressLayout.topMargin = dp(10);
            page.addView(copyAddress, addressLayout);
            copyAddress.setOnClickListener(v -> copy(screen.address));
            addQr(page, screen.address);
        }
    }

    private void addQr(LinearLayout page, String address) {
        TextView qrHint = label(getString(R.string.conn_qr_hint), 15, INK, true);
        qrHint.setPaddingRelative(0, dp(16), 0, 0);
        page.addView(qrHint);
        try {
            boolean[][] matrix = QrCode.encode(address);
            ImageView image = new ImageView(this);
            image.setImageBitmap(bitmap(matrix));
            image.setContentDescription(getString(R.string.conn_qr_cd));
            LinearLayout.LayoutParams qr = new LinearLayout.LayoutParams(dp(200), dp(200));
            qr.topMargin = dp(10);
            qr.gravity = Gravity.CENTER_HORIZONTAL;
            page.addView(image, qr);
        } catch (RuntimeException ignored) { }
    }

    private View lockedLine(String value, int size, int color) {
        HorizontalScrollView scroller = new HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setFillViewport(true);
        TextView text = label(value, size, color, true);
        text.setSingleLine(true);
        text.setHorizontallyScrolling(true);
        text.setTypeface(Typeface.MONOSPACE);
        text.setTextIsSelectable(true);
        text.setPaddingRelative(dp(12), dp(10), dp(12), dp(10));
        text.setBackground(round(PAPER, BORDER, 12));
        scroller.addView(text, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT);
        layout.topMargin = dp(8);
        scroller.setLayoutParams(layout);
        return scroller;
    }

    private void act(String action, PairingGate.Request request, FirstConnection.Screen screen) {
        if ("copy".equals(action)) {
            String line = screen.copyText.isEmpty()
                    ? FirstConnection.aiLine(phrases(), localAddress()) : screen.copyText;
            copy(line, getString(R.string.conn_sentence_copied));
            beginAttempt(true);
        } else if ("qr".equals(action)) {
            qrOpen = !qrOpen;
            if (qrOpen) beginAttempt(false);
            else persist();
        } else if ("recheck".equals(action)) {
            rendered = "";
        } else if ("retry".equals(action)) {
            helpOpen = false;
            sawCode = false;
            waiting = true;
            attempted = true;
            qrOpen = false;
            waitStartedAt = System.currentTimeMillis();
            persist();
        } else if ("allow".equals(action) || "deny".equals(action)) {
            if (request == null || !BridgeService.decidePair(this, request.id, "allow".equals(action))) {
                Toast.makeText(this, R.string.conn_pair_expired, Toast.LENGTH_LONG).show();
            } else if ("allow".equals(action)) {
                BridgeService.requestCaptureSync();
            } else {
                sawCode = false;
                waiting = true;
                persist();
            }
        } else if ("try".equals(action)) {
            trySample();
            return;
        } else if ("done".equals(action)) {
            finish();
            return;
        }
        rendered = "";
        render();
    }

    private void beginAttempt(boolean goWait) {
        attempted = true;
        if (goWait) waiting = true;
        if (waitStartedAt <= 0) waitStartedAt = System.currentTimeMillis();
        persist();
    }

    private void resetToGuide() {
        waiting = false;
        attempted = false;
        sawCode = false;
        helpOpen = false;
        qrOpen = false;
        waitStartedAt = 0L;
        persist();
        rendered = "";
    }

    private void exitGuide() {
        progressCleared = true;
        clearProgress(this);
        finish();
    }

    private void trySample() {
        String sample = getString(R.string.conn_try_sample);
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("catdiao", sample));
        MainActivity.sendClipboardToComputer(this);
    }

    private void loadProgress(SharedPreferences prefs) {
        waiting = "waiting".equals(prefs.getString(PREF_STEP, ""));
        qrOpen = prefs.getBoolean(PREF_QR, false);
        attempted = prefs.getBoolean(PREF_ATTEMPT, false);
        sawCode = prefs.getBoolean(PREF_SAW, false);
        helpOpen = prefs.getBoolean(PREF_HELP, false);
        waitStartedAt = prefs.getLong(PREF_WAIT, 0L);
    }

    private void persist() {
        getSharedPreferences("bridge", MODE_PRIVATE).edit()
                .putString(PREF_STEP, waiting ? "waiting" : "guide")
                .putBoolean(PREF_QR, qrOpen)
                .putBoolean(PREF_ATTEMPT, attempted)
                .putBoolean(PREF_SAW, sawCode)
                .putBoolean(PREF_HELP, helpOpen)
                .putLong(PREF_WAIT, waitStartedAt)
                .apply();
    }

    private FirstConnection.Link probeLink() {
        boolean lan = false;
        boolean cellular = false;
        boolean vpn = false;
        boolean captive = false;
        ConnectivityManager manager = getSystemService(ConnectivityManager.class);
        if (manager != null) {
            Network network = manager.getActiveNetwork();
            NetworkCapabilities caps = network == null ? null : manager.getNetworkCapabilities(network);
            if (caps != null) {
                lan = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
                cellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
                vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
                captive = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL);
            }
        }
        return FirstConnection.classify(lan, cellular, vpn, captive, !siteLocalIpv4().isEmpty());
    }

    private void copy(String value) {
        copy(value, getString(R.string.conn_copied));
    }

    private void copy(String value, String notice) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("catdiao", value));
        Toast.makeText(this, notice, Toast.LENGTH_SHORT).show();
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
        String wlan = "";
        String fallback = "";
        try {
            Enumeration<NetworkInterface> networks = NetworkInterface.getNetworkInterfaces();
            while (networks != null && networks.hasMoreElements()) {
                NetworkInterface network = networks.nextElement();
                if (!network.isUp() || network.isLoopback()) continue;
                Enumeration<InetAddress> addresses = network.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (!(address instanceof Inet4Address) || !address.isSiteLocalAddress()) continue;
                    String ip = address.getHostAddress();
                    if (network.getName().startsWith("wlan")) wlan = ip;
                    else if (fallback.isEmpty()) fallback = ip;
                }
            }
        } catch (Exception ignored) { }
        String ip = !wlan.isEmpty() ? wlan : fallback;
        return ip.isEmpty() ? "" : "http://" + ip + ":" + FirstConnection.PHONE_PORT;
    }

    private String siteLocalIpv4() {
        String address = localAddress();
        if (!address.startsWith("http://")) return "";
        int slash = address.indexOf("://");
        int colon = address.lastIndexOf(':');
        if (slash < 0 || colon <= slash) return "";
        return address.substring(slash + 3, colon);
    }

    private FirstConnection.Copy phrases() {
        return new FirstConnection.Copy() {
            @Override public String t(String key) { return text(key); }
            @Override public String f(String key, Object... args) { return text(key, args); }
        };
    }

    private String text(String key, Object... args) {
        int id = getResources().getIdentifier(key, "string", getPackageName());
        if (id == 0) throw new IllegalStateException(key);
        return args.length == 0 ? getString(id) : getString(id, args);
    }

    private TextView label(String value, int size, int color, boolean bold) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        text.setGravity(Gravity.START);
        text.setBreakStrategy(LineBreaker.BREAK_STRATEGY_SIMPLE);
        text.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE);
        text.setLineSpacing(0, 1.2f);
        if (bold) text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return text;
    }

    private Button button(String value, boolean primary) {
        Button button = new Button(this);
        button.setText(value);
        button.setAllCaps(false);
        button.setTextSize(16);
        button.setSingleLine(false);
        button.setMaxLines(3);
        button.setEllipsize(null);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setPaddingRelative(dp(16), dp(12), dp(16), dp(12));
        button.setGravity(Gravity.CENTER);
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
