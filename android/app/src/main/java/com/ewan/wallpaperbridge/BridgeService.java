package com.ewan.wallpaperbridge;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.WallpaperManager;
import android.content.ContentValues;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Point;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.PowerManager;
import android.net.wifi.WifiManager;
import android.os.StatFs;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.view.WindowManager;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CopyOnWriteArraySet;


public class BridgeService extends Service {
    public static final int PORT = 8767;
    public static final long COMPUTER_ONLINE_WINDOW_MS = 45000;
    public static final String ACTION_CHECK_CONNECTION = "com.ewan.wallpaperbridge.CHECK_CONNECTION";
    private static final String CHANNEL = "phone_bridge";
    private static final int MAX_WALLPAPER = 20 * 1024 * 1024;
    private static final long STORAGE_RESERVE = 64L * 1024 * 1024;
    private static volatile BridgeService active;
    private static final PairingGate pairing = new PairingGate(android.os.SystemClock::elapsedRealtime);
    private static int visibleAppActivities;
    private static final CopyOnWriteArraySet<Runnable> connectionListeners = new CopyOnWriteArraySet<>();
    private volatile LocalBridgeServer localServer;
    private volatile boolean destroyed;
    private SharedPreferences preferences;
    private WindowManager overlayManager;
    private View overlayView;
    private View closeChip;
    private final ArrayList<View> overlayWindows = new ArrayList<>();
    private Context localized;
    private final Object captureWake = new Object();
    private Thread captureWorker;
    private volatile ComputerConnection computerConnection;
    private volatile ComputerConnection.Attempt captureAttempt;
    private volatile BluetoothLink bluetooth;
    private final RequestAuth.NonceWindow nonces = new RequestAuth.NonceWindow();
    private final ThreadLocal<String> claimedBody = new ThreadLocal<>();
    private final ThreadLocal<String> requestNonce = new ThreadLocal<>();
    private final ThreadLocal<String> requestToken = new ThreadLocal<>();
    private ConnectivityManager.NetworkCallback networkCallback;

    static boolean bluetoothOnline() {
        BridgeService service = active;
        return service != null && service.bluetooth != null && service.bluetooth.online();
    }

    static String bluetoothState() {
        BridgeService service = active;
        return service == null || service.bluetooth == null ? "starting" : service.bluetooth.state();
    }

    static boolean wifiOnline(Context context) {
        long seen = context.getSharedPreferences("bridge", MODE_PRIVATE).getLong("wifi_last_seen_at", 0);
        return seen > 0 && System.currentTimeMillis() - seen < 20000;
    }

    private void startBluetooth() {
        if (bluetooth == null) bluetooth = new BluetoothLink(this, new BluetoothLink.Host() {
            public String token() { return preferences.getString("computer_token", ""); }
            public boolean wifiOnline() { return BridgeService.wifiOnline(BridgeService.this); }
            public JSONArray pending() throws Exception {
                try (CaptureStore store = new CaptureStore(BridgeService.this)) {
                    JSONArray items = store.pending(20);
                    CaptureBatch batch = new CaptureBatch();
                    JSONArray result = new JSONArray();
                    for (int i = 0; i < items.length(); i++) {
                        if (!batch.add(items.getJSONObject(i).toString())) break;
                        result.put(items.getJSONObject(i));
                    }
                    return result;
                }
            }
            public void acknowledge(JSONArray ids) throws Exception {
                try (CaptureStore store = new CaptureStore(BridgeService.this)) { store.acknowledge(ids); }
                preferences.edit().remove("capture_sync_error")
                        .putLong("capture_sync_last_success_at", System.currentTimeMillis()).apply();
            }
            public void address(String address) {
                if (!address.equals(preferences.getString("computer_address", ""))) {
                    preferences.edit().putString("computer_address", address).apply();
                    ComputerConnection check = computerConnection;
                    if (check != null) check.request(false);
                    requestCaptureSync();
                }
            }
            public void changed() { for (Runnable listener : connectionListeners) listener.run(); }
        });
        bluetooth.start();
    }

    public static PairingGate.Request pendingPair() { return pairing.pending(); }

    public static boolean isRunning() {
        BridgeService service = active;
        LocalBridgeServer server = service == null ? null : service.localServer;
        return server != null && server.isRunning();
    }

    static ComputerConnection.Snapshot computerConnectionSnapshot() {
        BridgeService service = active;
        ComputerConnection check = service == null ? null : service.computerConnection;
        return check == null ? new ComputerConnection.Snapshot(
                ComputerConnection.Phase.IDLE, 0, 0, 0, "", false) : check.snapshot();
    }

    static void addConnectionListener(Runnable listener) { connectionListeners.add(listener); }
    static void removeConnectionListener(Runnable listener) { connectionListeners.remove(listener); }

    private synchronized void startConnectionChecks() {
        if (destroyed || active != this || computerConnection != null
                || preferences.getString("computer_token", "").isEmpty()) return;
        computerConnection = new ComputerConnection(attempt -> {
            try {
                ComputerConnection.probe(preferences.getString("computer_address", ""), 8793,
                        preferences.getString("computer_token", ""), attempt);
                preferences.edit().putLong("wifi_last_seen_at", System.currentTimeMillis()).apply();
            } catch (Exception error) {
                preferences.edit().remove("wifi_last_seen_at").apply();
                if (!bluetoothOnline()) throw error;
            }
        }, snapshot -> {
            if (snapshot.phase == ComputerConnection.Phase.CONNECTED) {
                preferences.edit().putLong("computer_receiver_last_seen_at", snapshot.finishedAt).apply();
                requestCaptureSync();
            }
            for (Runnable listener : connectionListeners) listener.run();
        });
        computerConnection.request(true);
    }

    private synchronized void stopConnectionChecks() {
        ComputerConnection check = computerConnection;
        computerConnection = null;
        if (check != null) check.close();
        for (Runnable listener : connectionListeners) listener.run();
    }

    public static void appActivityStarted() {
        visibleAppActivities++;
        if (active != null) active.updateOverlay();
    }

    public static void appActivityStopped() {
        visibleAppActivities = Math.max(0, visibleAppActivities - 1);
        if (active != null) active.updateOverlay();
    }

    public static boolean overlayEnabled(Context context) {
        return context.getSharedPreferences("bridge", MODE_PRIVATE)
                .getBoolean("overlay_wanted", false) && Settings.canDrawOverlays(context);
    }

    public static void setOverlay(Context context, boolean enabled) {
        context.getSharedPreferences("bridge", MODE_PRIVATE).edit()
                .putBoolean("overlay_wanted", enabled).commit();
        BridgeService service = active;
        if (service != null) service.overlayHandler.post(() -> {
            service.updateOverlay();
            service.showNotification(service.lastNotificationText);
        });
    }

    /** Persist the wish without implying the bridge or accessibility should stop. */
    public static void setOverlayWanted(Context context, boolean enabled) {
        setOverlay(context, enabled);
    }

    public static void refreshLocale() {
        BridgeService service = active;
        if (service != null) service.overlayHandler.post(service::refreshLocaleBody);
    }

    public static void setupFinished() {
        if (active != null) active.showNotification(active.s(R.string.notif_ready));
    }

    public static void requestCaptureSync() {
        BridgeService service = active;
        if (service != null) synchronized (service.captureWake) { service.captureWake.notifyAll(); }
    }

    public static String trustedName(Context context) {
        return context.getSharedPreferences("bridge", MODE_PRIVATE).getString("computer_name", "");
    }

    public static boolean decidePair(Context context, String displayedId, boolean allow) {
        SharedPreferences settings = context.getSharedPreferences("bridge", MODE_PRIVATE);
        boolean decided = pairing.decide(displayedId, allow,
                () -> !settings.getString("computer_token", "").isEmpty(), request -> settings.edit()
                    .putString("computer_token", request.token)
                    .putString("computer_name", request.name)
                    .putString("computer_address", request.address)
                    .remove("computer_last_seen_at").remove("computer_receiver_last_seen_at").apply());
        if (!decided) return false;
        BridgeService service = active;
        if (service != null) {
            service.showNotification(allow ? service.s(R.string.notif_connecting) : service.s(R.string.notif_rejected));
            if (allow) service.startConnectionChecks();
        }
        return true;
    }

    public static void revoke(Context context) {
        pairing.revoke(() -> context.getSharedPreferences("bridge", MODE_PRIVATE).edit()
                .remove("computer_token").remove("computer_name").remove("computer_address")
                .remove("computer_last_seen_at").remove("computer_receiver_last_seen_at").apply());
        BridgeService service = active;
        if (service != null) {
            service.stopConnectionChecks();
            if (service.bluetooth != null) service.bluetooth.disconnect();
            service.preferences.edit().remove("wifi_last_seen_at").apply();
            ComputerConnection.Attempt attempt = service.captureAttempt;
            if (attempt != null) attempt.cancel();
            LocalBridgeServer server = service.localServer;
            if (server != null) server.closeClients();
            service.showNotification(service.s(R.string.notif_unpaired));
        }
    }

    public static void shareFile(Context context, Uri uri) {
        context.getSharedPreferences("bridge", MODE_PRIVATE).edit()
                .putString("shared_uri", uri.toString()).apply();
    }

    private PowerManager.WakeLock cpuLock;
    private PowerManager.WakeLock screenLock;
    private WifiManager.WifiLock wifiLock;
    private final Runnable pairingAwakeTick = this::applyPairingAwake;

    // 息屏后保持 CPU 与 WiFi 不睡，8767 才能被电脑连上（OPPO 等机型息屏会断 WiFi）
    @SuppressWarnings("deprecation")
    private void acquireLocks() {
        try {
            PowerManager power = getSystemService(PowerManager.class);
            if (power != null && cpuLock == null) {
                cpuLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "catdiao:bridge");
                cpuLock.setReferenceCounted(false);
                cpuLock.acquire();
            }
        } catch (RuntimeException ignored) { cpuLock = null; }
        try {
            WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifi != null && wifiLock == null) {
                int mode = Build.VERSION.SDK_INT >= 29 ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                        : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
                wifiLock = wifi.createWifiLock(mode, "catdiao:bridge");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
            }
        } catch (RuntimeException ignored) { wifiLock = null; }
    }

    private void releaseLocks() {
        try { if (cpuLock != null && cpuLock.isHeld()) cpuLock.release(); } catch (RuntimeException ignored) { }
        try { if (wifiLock != null && wifiLock.isHeld()) wifiLock.release(); } catch (RuntimeException ignored) { }
        releaseScreenLock();
        cpuLock = null; wifiLock = null;
    }

    /**
     * Hold the screen and the listener while the waiting or confirm page is up.
     * PARTIAL_WAKE_LOCK alone still lets OPPO dim the panel and drop port 8767.
     */
    public static void requestPairingAwake(Context context, long untilElapsed) {
        android.content.SharedPreferences prefs = context.getSharedPreferences("bridge", MODE_PRIVATE);
        long now = android.os.SystemClock.elapsedRealtime();
        long current = prefs.getLong(PairingKeepAlive.PREF_AWAKE_UNTIL, 0L);
        if (!(current > now && Math.abs(untilElapsed - current) < 20_000L)) {
            prefs.edit().putLong(PairingKeepAlive.PREF_AWAKE_UNTIL, untilElapsed).commit();
        }
        BridgeService service = active;
        if (service != null) service.applyPairingAwake();
    }

    public static void clearPairingAwake(Context context) {
        context.getSharedPreferences("bridge", MODE_PRIVATE).edit()
                .remove(PairingKeepAlive.PREF_AWAKE_UNTIL).commit();
        BridgeService service = active;
        if (service != null) service.applyPairingAwake();
    }

    private void applyPairingAwake() {
        overlayHandler.removeCallbacks(pairingAwakeTick);
        if (destroyed || active != this) return;
        long until = preferences.getLong(PairingKeepAlive.PREF_AWAKE_UNTIL, 0L);
        long now = android.os.SystemClock.elapsedRealtime();
        if (!PairingKeepAlive.wakeActive(until, now)) {
            releaseScreenLock();
            if (until != 0L) preferences.edit().remove(PairingKeepAlive.PREF_AWAKE_UNTIL).apply();
            return;
        }
        boolean holding = screenLock != null && screenLock.isHeld();
        acquireScreenLock();
        ensureRadioAwake();
        startServer();
        if (!holding) {
            try { startForeground(47, notification(currentStatus())); }
            catch (RuntimeException ignored) { }
        }
        long delay = Math.min(Math.max(500L, until - now), 15_000L);
        overlayHandler.postDelayed(pairingAwakeTick, delay);
    }

    private void ensureRadioAwake() {
        try { if (cpuLock != null && !cpuLock.isHeld()) cpuLock.acquire(); }
        catch (RuntimeException ignored) { }
        try { if (wifiLock != null && !wifiLock.isHeld()) wifiLock.acquire(); }
        catch (RuntimeException ignored) { }
        if (cpuLock == null || wifiLock == null) acquireLocks();
    }

    @SuppressWarnings("deprecation")
    private void acquireScreenLock() {
        try {
            if (screenLock != null && screenLock.isHeld()) return;
            PowerManager power = getSystemService(PowerManager.class);
            if (power == null) return;
            if (screenLock == null) {
                screenLock = power.newWakeLock(
                        PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                        "catdiao:pairing");
                screenLock.setReferenceCounted(false);
            }
            screenLock.acquire();
        } catch (RuntimeException ignored) { screenLock = null; }
    }

    private void releaseScreenLock() {
        try { if (screenLock != null && screenLock.isHeld()) screenLock.release(); }
        catch (RuntimeException ignored) { }
        screenLock = null;
    }

    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(AppLocale.wrap(base));
    }

    @Override public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        refreshLocaleBody();
    }

    @Override public void onCreate() {
        super.onCreate();
        active = this;
        preferences = getSharedPreferences("bridge", MODE_PRIVATE);
        ensureChannel();
        startForeground(47, notification(currentStatus()));
        acquireLocks();
        preferences.edit().remove("keep_awake").apply();
        localServer = new LocalBridgeServer(PORT, 8769, this::handleClient, error -> {
            if (!destroyed && active == this) showNotification(s(R.string.notif_server_down));
        });
        startServer();
        advertise();
        updateOverlay();
        captureWorker = new Thread(this::captureSyncLoop, "phone-capture-sync");
        captureWorker.start();
        startConnectionChecks();
        startBluetooth();
        watchNetwork();
        applyPairingAwake();
    }

    private void watchNetwork() {
        ConnectivityManager manager = getSystemService(ConnectivityManager.class);
        if (manager == null || networkCallback != null) return;
        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build();
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) {
                acquireLocks();
                ComputerConnection check = computerConnection;
                if (check != null) check.networkReady();
                requestCaptureSync();
            }
        };
        try { manager.registerNetworkCallback(request, networkCallback); }
        catch (RuntimeException ignored) { networkCallback = null; }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        startServer();
        advertise();
        startBluetooth();
        if (intent != null && ACTION_CHECK_CONNECTION.equals(intent.getAction())) {
            startConnectionChecks();
            ComputerConnection check = computerConnection;
            if (check != null) check.request(true);
        }
        return START_STICKY;
    }

    /** OPPO and others stop the service when 猫叼 is swiped away; come back shortly after. */
    @Override public void onTaskRemoved(Intent rootIntent) {
        scheduleServiceRestart();
        super.onTaskRemoved(rootIntent);
    }

    /** Ask AlarmManager to bring the foreground service back if the ROM killed it. */
    private void scheduleServiceRestart() {
        try {
            android.app.AlarmManager alarms = getSystemService(android.app.AlarmManager.class);
            if (alarms == null) return;
            PendingIntent restart = PendingIntent.getForegroundService(this, 53,
                    new Intent(this, BridgeService.class),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            long when = android.os.SystemClock.elapsedRealtime() + 3000;
            if (Build.VERSION.SDK_INT >= 23) {
                alarms.setAndAllowWhileIdle(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP, when, restart);
            } else {
                alarms.set(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP, when, restart);
            }
        } catch (RuntimeException ignored) { }
    }

    @Override public void onDestroy() {
        // ROM may kill us while the screen is off. Come back during first pairing
        // too: the token is not stored until the user taps 允许.
        overlayHandler.removeCallbacks(pairingAwakeTick);
        boolean wake = PairingKeepAlive.wakeActive(
                preferences.getLong(PairingKeepAlive.PREF_AWAKE_UNTIL, 0L),
                android.os.SystemClock.elapsedRealtime());
        if (PairingKeepAlive.restartListener(
                !preferences.getString("computer_token", "").isEmpty(),
                wake, pairing.pending() != null)) scheduleServiceRestart();
        destroyed = true; releaseLocks();
        if (bluetooth != null) bluetooth.close();
        ConnectivityManager manager = getSystemService(ConnectivityManager.class);
        if (manager != null && networkCallback != null) {
            try { manager.unregisterNetworkCallback(networkCallback); } catch (RuntimeException ignored) { }
            networkCallback = null;
        }
        stopConnectionChecks();
        LocalBridgeServer server = localServer;
        stopAdvertising();
        if (server != null) server.close();
        ComputerConnection.Attempt attempt = captureAttempt;
        if (attempt != null) attempt.cancel();
        if (captureWorker != null) captureWorker.interrupt();
        removeOverlay();
        synchronized (captureWake) { captureWake.notifyAll(); }
        if (active == this) active = null;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private View closeTarget;
    private final android.os.Handler overlayHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    /** Fade the cat only. The close chip stays readable and on screen. */
    private final Runnable tuckOverlay = () -> {
        View cat = overlayView == null ? null : overlayView.findViewWithTag("cat");
        if (cat != null) cat.setAlpha(0.55f);
    };

    private void updateOverlay() {
        boolean show = OverlayPolicy.shouldShow(
                getSharedPreferences("bridge", MODE_PRIVATE).getBoolean("overlay_wanted", false),
                Settings.canDrawOverlays(this), visibleAppActivities);
        if (!show) { removeOverlay(); return; }
        if (overlayView != null) return;
        try {
            overlayManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            if (overlayManager == null) return;
            float density = getResources().getDisplayMetrics().density;
            android.graphics.Point real = new android.graphics.Point();
            overlayManager.getDefaultDisplay().getRealSize(real);
            int screenWidth = Math.max(1, real.x);
            int screenHeight = Math.max(1, real.y);
            int width = OverlayMath.bubbleWidthPx(density);
            int height = OverlayMath.bubbleHeightPx(density);
            boolean left = OverlayMath.prefersLeft(preferences.getInt("overlay_x", 0), width, screenWidth);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            // Physical placement: the chip stays on the inner side even in RTL.
            row.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
            ImageView cat = new ImageView(this);
            cat.setTag("cat");
            cat.setImageResource(getResources().getIdentifier("cat_diao_launcher", "drawable", getPackageName()));
            cat.setScaleType(ImageView.ScaleType.FIT_CENTER);
            cat.setContentDescription(s(R.string.overlay_cd));
            int catPx = Math.round(OverlayMath.CAT_DP * density);
            TextView chip = new TextView(this);
            chip.setText("✕");
            chip.setGravity(Gravity.CENTER);
            chip.setTextColor(0xFFFFFFFF);
            chip.setTextSize(16);
            chip.setContentDescription(s(R.string.overlay_chip_cd));
            android.graphics.drawable.GradientDrawable chipBg = new android.graphics.drawable.GradientDrawable();
            chipBg.setColor(0xCC333333);
            chipBg.setCornerRadius(catPx);
            chip.setBackground(chipBg);
            int chipPx = Math.round(OverlayMath.CLOSE_CHIP_DP * density);
            int gap = Math.round(OverlayMath.CHIP_GAP_DP * density);
            LinearLayout.LayoutParams catLp = new LinearLayout.LayoutParams(catPx, catPx);
            LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(chipPx, chipPx);
            if (left) {
                row.addView(cat, catLp);
                chipLp.setMarginStart(gap);
                row.addView(chip, chipLp);
            } else {
                chipLp.setMarginEnd(gap);
                row.addView(chip, chipLp);
                row.addView(cat, catLp);
            }
            chip.setOnClickListener(v -> closeOverlayByUser());
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    width, height, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    android.graphics.PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.LEFT;
            params.x = OverlayMath.bubbleX(left, screenWidth, width, density);
            params.y = Math.max(0, Math.min(screenHeight - height,
                    preferences.getInt("overlay_y", Math.round(240 * density))));
            cat.setOnTouchListener(new View.OnTouchListener() {
                private float downX, downY;
                private int startX, startY;
                private boolean moved, longPressed;
                private final Runnable longPress = () -> {
                    longPressed = true;
                    closeOverlayByUser();
                };
                @Override public boolean onTouch(View view, MotionEvent event) {
                    int slop = Math.round(12 * density);
                    if (event.getAction() == MotionEvent.ACTION_DOWN) {
                        overlayHandler.removeCallbacks(tuckOverlay);
                        view.setAlpha(1f);
                        downX = event.getRawX(); downY = event.getRawY();
                        startX = params.x; startY = params.y;
                        moved = false; longPressed = false;
                        overlayHandler.postDelayed(longPress, 700);
                        return true;
                    }
                    if (longPressed) return true;
                    if (event.getAction() == MotionEvent.ACTION_MOVE) {
                        if (!moved && (Math.abs(event.getRawX() - downX) > slop || Math.abs(event.getRawY() - downY) > slop)) {
                            moved = true;
                            overlayHandler.removeCallbacks(longPress);
                            showCloseTarget();
                        }
                        if (moved) {
                            params.x = startX + Math.round(event.getRawX() - downX);
                            params.y = startY + Math.round(event.getRawY() - downY);
                            try { overlayManager.updateViewLayout(row, params); } catch (RuntimeException ignored) { }
                            if (closeTarget != null) closeTarget.setAlpha(
                                    OverlayMath.inCloseZone(event.getRawX(), event.getRawY(), screenWidth, screenHeight, density) ? 1f : 0.6f);
                        }
                        return true;
                    }
                    if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                        overlayHandler.removeCallbacks(longPress);
                        hideCloseTarget();
                        if (moved && OverlayMath.inCloseZone(event.getRawX(), event.getRawY(), screenWidth, screenHeight, density)) {
                            closeOverlayByUser();
                            return true;
                        }
                        if (moved) {
                            boolean snapLeft = OverlayMath.prefersLeft(params.x, width, screenWidth);
                            params.x = OverlayMath.bubbleX(snapLeft, screenWidth, width, density);
                            params.y = Math.max(0, Math.min(screenHeight - height, params.y));
                            try { overlayManager.updateViewLayout(row, params); } catch (RuntimeException ignored) { }
                            preferences.edit().putInt("overlay_x", params.x).putInt("overlay_y", params.y).commit();
                            placeChip(row, cat, chip, catLp, chipLp, snapLeft, gap);
                        } else if (event.getAction() == MotionEvent.ACTION_UP) {
                            startActivity(new Intent(BridgeService.this, QuickActionsActivity.class)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        }
                        overlayHandler.postDelayed(tuckOverlay, 2500);
                        return true;
                    }
                    return false;
                }
            });
            addOverlayWindow(row, params);
            overlayView = row;
            closeChip = chip;
            overlayHandler.postDelayed(tuckOverlay, 2500);
        } catch (RuntimeException error) { removeOverlay(); }
    }

    /** Rebuild the row so the ✕ stays on the inner side after a side change. */
    private void placeChip(LinearLayout row, ImageView cat, TextView chip,
                           LinearLayout.LayoutParams catLp, LinearLayout.LayoutParams chipLp,
                           boolean left, int gap) {
        row.removeAllViews();
        chipLp.setMarginStart(0);
        chipLp.setMarginEnd(0);
        if (left) {
            row.addView(cat, catLp);
            chipLp.setMarginStart(gap);
            row.addView(chip, chipLp);
        } else {
            chipLp.setMarginEnd(gap);
            row.addView(chip, chipLp);
            row.addView(cat, catLp);
        }
    }

    private void showCloseTarget() {
        if (closeTarget != null || overlayManager == null) return;
        try {
            float density = getResources().getDisplayMetrics().density;
            TextView target = new TextView(loc());
            target.setText(s(R.string.overlay_drag));
            target.setTextColor(0xFFFFFFFF);
            target.setTextSize(14);
            target.setGravity(Gravity.CENTER);
            target.setMaxLines(2);
            android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
            shape.setColor(0xCC333333);
            shape.setCornerRadius(Math.round(24 * density));
            target.setBackground(shape);
            int pad = Math.round(8 * density);
            target.setPadding(pad, pad, pad, pad);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    Math.round(OverlayMath.CLOSE_TARGET_WIDTH_DP * density),
                    Math.round(OverlayMath.CLOSE_TARGET_HEIGHT_DP * density),
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    android.graphics.PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            lp.y = Math.round(OverlayMath.CLOSE_TARGET_BOTTOM_DP * density);
            target.setAlpha(0.6f);
            addOverlayWindow(target, lp);
            closeTarget = target;
        } catch (RuntimeException ignored) { closeTarget = null; }
    }

    private void addOverlayWindow(View view, WindowManager.LayoutParams lp) {
        overlayManager.addView(view, lp);
        overlayWindows.add(view);
    }

    private void hideCloseTarget() {
        if (closeTarget != null) detachOverlayWindow(closeTarget);
        closeTarget = null;
    }

    private void detachOverlayWindow(View view) {
        if (overlayManager != null) {
            try { overlayManager.removeViewImmediate(view); }
            catch (RuntimeException ignored) {
                try { overlayManager.removeView(view); } catch (RuntimeException ignoredToo) { }
            }
        }
        overlayWindows.remove(view);
    }

    /** Turn the floating cat off. Does not stop the bridge, the computer link, or screen control. */
    private void closeOverlayByUser() {
        hideCloseTarget();
        setOverlay(this, false);
        android.widget.Toast.makeText(loc(), s(R.string.overlay_off_toast),
                android.widget.Toast.LENGTH_LONG).show();
    }

    private void removeOverlay() {
        overlayHandler.removeCallbacks(tuckOverlay);
        if (overlayManager == null) overlayManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        for (View view : new ArrayList<>(overlayWindows)) detachOverlayWindow(view);
        overlayWindows.clear();
        overlayView = null;
        closeTarget = null;
        closeChip = null;
    }

    private void captureSyncLoop() {
        while (!destroyed && active == this) {
            boolean more = false;
            try { more = syncCapturesOnce(); }
            catch (Exception error) {
                String detail = error instanceof ComputerConnection.CheckFailure ? error.getMessage()
                        : "ce:not_confirmed";
                preferences.edit().putString("capture_sync_error",
                        detail.length() > 180 ? detail.substring(0, 180) : detail).apply();
            }
            synchronized (captureWake) {
                if (destroyed || active != this) return;
                try { captureWake.wait(more ? 100 : 45000); } catch (InterruptedException ignored) { return; }
            }
        }
    }

    private boolean syncCapturesOnce() throws Exception {
        String address = preferences.getString("computer_address", "");
        String token = preferences.getString("computer_token", "");
        if (address.isEmpty() || token.isEmpty()) {
            preferences.edit().putString("capture_sync_error", "ce:not_paired").apply();
            return false;
        }
        try (CaptureStore store = new CaptureStore(this)) {
            JSONArray items = store.pending(20);
            if (items.length() == 0) {
                preferences.edit().remove("capture_sync_error").apply();
                return false;
            }
            preferences.edit().putLong("capture_sync_last_attempt_at", System.currentTimeMillis()).apply();
            CaptureBatch batch = new CaptureBatch();
            Set<String> sentIds = new HashSet<>();
            for (int index = 0; index < items.length(); index++) {
                if (!batch.add(items.getJSONObject(index).toString())) break;
                sentIds.add(items.getJSONObject(index).getString("id"));
            }
            if (batch.count() == 0) throw new IOException("ce:too_large");
            byte[] payload = batch.payload();
            ComputerConnection.Attempt attempt = new ComputerConnection.Attempt(ComputerConnection.TIMEOUT_MS);
            captureAttempt = attempt;
            Timer deadline = new Timer("catdiao-capture-deadline", true);
            deadline.schedule(new TimerTask() {
                @Override public void run() { attempt.cancel(); }
            }, ComputerConnection.TIMEOUT_MS);
            try {
                if (destroyed || active != this) throw new IOException("ce:service_stopped");
                ComputerConnection.Reply response = ComputerConnection.exchange(address, 8793, "POST", "/captures",
                        token, payload, attempt);
                if (response.code != 200) {
                    throw new ComputerConnection.CheckFailure(response.code == 403
                            ? "ce:pair_rejected" : "ce:batch_rejected");
                }
                JSONObject reply = new JSONObject(new String(response.body, StandardCharsets.UTF_8));
                JSONArray acknowledged = reply.optJSONArray("accepted");
                JSONArray accepted = new JSONArray();
                if (acknowledged != null) for (int index = 0; index < acknowledged.length(); index++) {
                    String id = acknowledged.optString(index);
                    if (sentIds.contains(id)) accepted.put(id);
                }
                if (accepted.length() == 0) throw new ComputerConnection.CheckFailure("ce:batch_unacked");
                if (destroyed || active != this || !token.equals(preferences.getString("computer_token", ""))) return false;
                store.acknowledge(accepted);
                preferences.edit().remove("capture_sync_error")
                        .putLong("wifi_last_seen_at", System.currentTimeMillis())
                        .putLong("capture_sync_last_success_at", System.currentTimeMillis()).apply();
                return store.pendingCount() > 0;
            } finally {
                deadline.cancel();
                attempt.cancel();
                if (captureAttempt == attempt) captureAttempt = null;
            }
        }
    }

    private String lastNotificationText = "";

    private void ensureChannel() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        manager.createNotificationChannel(new NotificationChannel(
                CHANNEL, s(R.string.channel_name), NotificationManager.IMPORTANCE_LOW));
    }

    private void refreshLocaleBody() {
        localized = null;
        ensureChannel();
        boolean wasShowing = overlayView != null;
        removeOverlay();
        if (wasShowing || OverlayPolicy.shouldShow(
                preferences.getBoolean("overlay_wanted", false),
                Settings.canDrawOverlays(this), visibleAppActivities)) updateOverlay();
        showNotification(currentStatus());
    }

    private Context loc() {
        if (localized == null) localized = AppLocale.wrap(this);
        return localized;
    }

    private String s(int id) { return loc().getString(id); }

    private String s(int id, Object... args) { return loc().getString(id, args); }

    private String currentStatus() {
        if (preferences.getString("computer_token", "").isEmpty()) return s(R.string.notif_unpaired);
        if (preferences.getBoolean("remote_paused", false)) return s(R.string.notif_paused);
        return setupComplete() ? s(R.string.notif_ready) : s(R.string.notif_setup_pending);
    }

    private String explain(Exception error) {
        String message = error.getMessage();
        if (message != null && message.startsWith("ce:")) return humanError(message);
        if (message != null && !message.isEmpty()) return message;
        return s(R.string.err_failed, error.getClass().getSimpleName());
    }

    /** Turn stored connection codes into the active language. Unknown text is left as-is. */
    private String humanError(String code) {
        if (code == null || code.isEmpty()) return "";
        if (code.startsWith("ce:http:")) return s(R.string.ce_http, code.substring("ce:http:".length()));
        switch (code) {
            case "ce:timeout": return s(R.string.ce_timeout);
            case "ce:no_response": return s(R.string.ce_no_response);
            case "ce:no_address": return s(R.string.ce_no_address);
            case "ce:receiver_outdated": return s(R.string.ce_receiver_outdated);
            case "ce:pair_rejected": return s(R.string.ce_pair_rejected);
            case "ce:bad_address": return s(R.string.ce_bad_address);
            case "ce:not_lan": return s(R.string.ce_not_lan);
            case "ce:bad_confirm": return s(R.string.ce_bad_confirm);
            case "ce:incomplete": return s(R.string.ce_incomplete);
            case "ce:confirm_size": return s(R.string.ce_confirm_size);
            case "ce:truncated": return s(R.string.ce_truncated);
            case "ce:bad_pair": return s(R.string.ce_bad_pair);
            case "ce:confirm_long": return s(R.string.ce_confirm_long);
            case "ce:ended": return s(R.string.ce_ended);
            case "ce:header_timeout": return s(R.string.ce_header_timeout);
            case "ce:header_cut": return s(R.string.ce_header_cut);
            case "ce:header_long": return s(R.string.ce_header_long);
            case "ce:not_paired": return s(R.string.ce_not_paired);
            case "ce:not_confirmed": return s(R.string.ce_not_confirmed);
            case "ce:too_large": return s(R.string.ce_too_large);
            case "ce:service_stopped": return s(R.string.ce_service_stopped);
            case "ce:batch_rejected": return s(R.string.ce_batch_rejected);
            case "ce:batch_unacked": return s(R.string.ce_batch_unacked);
            default: return code;
        }
    }

    private Notification notification(String message) {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent open = PendingIntent.getActivity(this, 47, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        boolean paused = preferences.getBoolean("remote_paused", false);
        Intent toggle = new Intent(this, RemotePauseReceiver.class);
        PendingIntent pause = PendingIntent.getBroadcast(this, 49, toggle,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String actionLabel = paused ? s(R.string.resume_remote) : s(R.string.pause_remote);
        lastNotificationText = message;
        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(getResources().getIdentifier("cat_paw_notification", "drawable", getPackageName()))
                .setContentTitle(s(R.string.app_name)).setContentText(message)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, actionLabel, pause).build());
        if (overlayEnabled(this)) {
            Intent hide = new Intent(this, RemotePauseReceiver.class).setAction(RemotePauseReceiver.HIDE_OVERLAY);
            builder.addAction(new Notification.Action.Builder(null, s(R.string.notif_hide_cat),
                    PendingIntent.getBroadcast(this, 51, hide,
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)).build());
        }
        return builder.setOngoing(true).build();
    }

    public static void toggleRemotePause(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("bridge", MODE_PRIVATE);
        boolean paused = !prefs.getBoolean("remote_paused", false);
        prefs.edit().putBoolean("remote_paused", paused).apply();
        BridgeService service = active;
        if (service != null) service.showNotification(paused
                ? service.s(R.string.notif_paused) : service.s(R.string.notif_resumed));
    }

    private void showNotification(String message) {
        getSystemService(NotificationManager.class).notify(47, notification(message));
    }

    private NsdManager.RegistrationListener nsdListener;

    private void advertise() {
        if (destroyed || nsdListener != null) return;
        try {
            NsdManager manager = (NsdManager) getSystemService(NSD_SERVICE);
            if (manager == null) return;
            NsdServiceInfo info = new NsdServiceInfo();
            info.setServiceName("catdiao");
            info.setServiceType(FirstConnection.SERVICE_TYPE + ".");
            info.setPort(PORT);
            NsdManager.RegistrationListener listener = new NsdManager.RegistrationListener() {
                @Override public void onServiceRegistered(NsdServiceInfo registered) { }
                @Override public void onRegistrationFailed(NsdServiceInfo service, int error) { nsdListener = null; }
                @Override public void onServiceUnregistered(NsdServiceInfo service) { nsdListener = null; }
                @Override public void onUnregistrationFailed(NsdServiceInfo service, int error) { }
            };
            nsdListener = listener;
            manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener);
        } catch (RuntimeException ignored) {
            nsdListener = null;
        }
    }

    private void stopAdvertising() {
        NsdManager.RegistrationListener listener = nsdListener;
        nsdListener = null;
        if (listener == null) return;
        try {
            NsdManager manager = (NsdManager) getSystemService(NSD_SERVICE);
            if (manager != null) manager.unregisterService(listener);
        } catch (RuntimeException ignored) { }
    }

    private void startServer() {
        LocalBridgeServer server = localServer;
        if (destroyed || active != this || server == null) return;
        try { server.start(); }
        catch (IOException error) { showNotification(s(R.string.notif_server_down)); }
    }

    private boolean isLocal(InetAddress address) {
        return LanPolicy.allows(address);
    }

    private void handleClient(LocalBridgeServer.Client session) throws Exception {
        Socket client = session.socket;
        if (!isLocal(client.getInetAddress())) {
            respond(client.getOutputStream(), 403, s(R.string.err_lan_only), "text/plain", null);
            return;
        }
        try { handle(session); }
        catch (Exception error) {
            try {
                json(client.getOutputStream(), 500, new JSONObject().put("error", explain(error)));
            } catch (Exception ignored) { }
        } finally {
            claimedBody.remove();
            requestNonce.remove();
            requestToken.remove();
        }
    }


    private void handle(LocalBridgeServer.Client session) throws Exception {
        Socket client = session.socket;
        InputStream input = client.getInputStream();
        OutputStream output = client.getOutputStream();
        String[] lines = session.readHeaders().split("\r\n");
        String[] request = lines[0].split(" ");
        if (request.length < 2) { respond(output, 400, s(R.string.err_bad_request), "text/plain", null); return; }
        String method = request[0], path = request[1];
        Map<String, String> headers = new HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int separator = lines[i].indexOf(':');
            if (separator > 0) headers.put(lines[i].substring(0, separator).trim().toLowerCase(Locale.US),
                    lines[i].substring(separator + 1).trim());
        }
        long length = 0;
        if (headers.containsKey("content-length")) {
            try { length = Long.parseLong(headers.get("content-length")); }
            catch (NumberFormatException error) { respond(output, 400, s(R.string.err_bad_length), "text/plain", null); return; }
        }
        if (length < 0) { respond(output, 400, s(R.string.err_bad_length), "text/plain", null); return; }
        // Small JSON bodies have a total deadline; file uploads retain their progress timeout.
        if (length > 0 && !request[1].equals("/file") && !request[1].equals("/wallpaper/home"))
            session.watchBody(20000);

        if (method.equals("GET") && path.equals("/hello")) {
            JSONObject result = new JSONObject().put("name", "猫叼")
                    .put("version", "2.0").put("paired", !preferences.getString("computer_token", "").isEmpty())
                    .put("protocol", "dh-2048-v1")
                    .put("setup_complete", setupComplete())
                    .put("screen_control_enabled", PhoneAccessibilityService.isEnabled(this))
                    .put("overlay_allowed", Settings.canDrawOverlays(this))
                    .put("brightness_control_allowed", Settings.System.canWrite(this))
                    .put("model", Build.MODEL)
                    .put("version_code", BuildConfig.VERSION_CODE)
                    .put("build_commit", BuildConfig.GIT_COMMIT);
            json(output, 200, result); return;
        }
        if (method.equals("POST") && path.equals("/pair")) {
            if (length > 8192) { json(output, 413, new JSONObject().put("error", s(R.string.err_pair_too_long))); return; }
            JSONObject body = readJson(input, (int) length);
            session.bodyReceived();
            String name = body.optString("name", s(R.string.conn_this_computer)).trim();
            String peerPublic = body.optString("public_key", "").trim().toLowerCase(Locale.ROOT);
            if (name.length() < 1 || name.length() > 60 || !PairingExchange.validPublic(peerPublic)) {
                json(output, 400, new JSONObject().put("error", s(R.string.err_pair_invalid))); return;
            }
            if (body.has("token")) {
                json(output, 400, new JSONObject().put("error", s(R.string.err_pair_legacy))); return;
            }
            if (!preferences.getString("computer_token", "").isEmpty()) {
                json(output, 409, new JSONObject().put("error", s(R.string.err_already_paired))); return;
            }
            String remoteAddress = client.getInetAddress().getHostAddress();
            PairingGate.Request existing = pairing.pending();
            if (existing != null && name.equals(existing.name) && remoteAddress.equals(existing.address)
                    && peerPublic.equals(existing.peerPublic)) {
                json(output, 202, new JSONObject().put("id", existing.id).put("state", "pending")
                        .put("public_key", existing.phonePublic));
                return;
            }
            PairingExchange.KeyPair local = PairingExchange.generate();
            byte[] shared;
            try { shared = PairingExchange.sharedSecret(local.privateHex, peerPublic); }
            catch (IllegalArgumentException error) {
                json(output, 400, new JSONObject().put("error", s(R.string.err_pair_invalid))); return;
            }
            String token = PairingExchange.token(shared);
            String code = PairingExchange.confirmCode(shared);
            PairingGate.Request pair = pairing.offer(name, remoteAddress, token, code, peerPublic, local.publicHex,
                    () -> !preferences.getString("computer_token", "").isEmpty());
            if (pair == null) {
                json(output, 409, new JSONObject().put("error", s(R.string.err_pair_busy)));
                return;
            }
            showNotification(s(R.string.notif_pair, name));
            json(output, 202, new JSONObject().put("id", pair.id).put("state", "pending")
                    .put("public_key", pair.phonePublic));
            return;
        }
        if (method.equals("GET") && path.startsWith("/pair-status?id=")) {
            String id = path.substring("/pair-status?id=".length());
            String state = pairing.status(id);
            if (state == null) {
                json(output, 404, new JSONObject().put("error", s(R.string.err_pair_gone))); return;
            }
            json(output, 200, new JSONObject().put("state", state)); return;
        }
        String expected = preferences.getString("computer_token", "");
        RequestAuth.Verdict verdict = RequestAuth.verify(method, path, expected, null, false,
                headers.get("x-catdiao-ts"), headers.get("x-catdiao-nonce"),
                headers.get("x-catdiao-body"), headers.get("x-catdiao-sig"),
                System.currentTimeMillis() / 1000L, nonces);
        if (!verdict.ok) {
            json(output, 401, new JSONObject().put("error", s(R.string.err_need_pair))); return;
        }
        claimedBody.set(verdict.bodyHash);
        requestNonce.set(verdict.nonce);
        requestToken.set(expected);
        if (length == 0 && !RequestAuth.bodyHash(null).equals(verdict.bodyHash)) {
            json(output, 400, new JSONObject().put("error", s(R.string.err_body_mismatch))); return;
        }
        preferences.edit().putLong("computer_last_seen_at", System.currentTimeMillis()).apply();
        String currentComputerAddress = client.getInetAddress().getHostAddress();
        if (!currentComputerAddress.equals(preferences.getString("computer_address", ""))) {
            preferences.edit().putString("computer_address", currentComputerAddress).apply();
            requestCaptureSync();
            ComputerConnection check = computerConnection;
            if (check != null) check.request(false);
        }
        if (RoutePolicy.pausesWithRemote(method, path) && preferences.getBoolean("remote_paused", false)) {
            json(output, 423, new JSONObject().put("error", s(R.string.err_paused))); return;
        }
        if (method.equals("GET") && path.equals("/heartbeat")) {
            requestCaptureSync();
            json(output, 200, new JSONObject().put("connected", true)); return;
        }
        if (method.equals("GET") && path.equals("/status")) { json(output, 200, status()); return; }
        if (method.equals("GET") && path.equals("/apps")) { json(output, 200, apps()); return; }
        if (method.equals("GET") && path.equals("/screen")) { screenshot(output); return; }
        if (method.equals("GET") && path.equals("/ui")) { uiTree(output); return; }
        if (method.equals("GET") && path.equals("/shared-file")) { sharedFile(output); return; }
        if (method.equals("POST") && path.equals("/clipboard")) {
            if (length > 262144) {
                json(output, 413, new JSONObject().put("error", s(R.string.err_text_too_big))); return;
            }
            JSONObject body = readJson(input, (int) length);
            session.bodyReceived();
            if (!expected.equals(preferences.getString("computer_token", ""))) {
                json(output, 401, new JSONObject().put("error", s(R.string.err_pair_removed))); return;
            }
            Object value = body.opt("text");
            if (!(value instanceof String) || ((String) value).isEmpty()) {
                json(output, 400, new JSONObject().put("error", s(R.string.err_need_text))); return;
            }
            ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(
                    ClipData.newPlainText(s(R.string.err_clip_label), (String) value));
            json(output, 200, new JSONObject().put("copied", true)
                    .put("characters", ((String) value).length())); return;
        }
        if (method.equals("POST") && path.equals("/file")) {
            client.setSoTimeout(180000);
            receiveFile(input, output, headers, length); return;
        }
        if (method.equals("POST") && path.equals("/wallpaper/home")) {
            if (length > MAX_WALLPAPER) {
                json(output, 413, new JSONObject().put("error", s(R.string.err_wallpaper_too_big))); return;
            }
            setHomeWallpaper(input, output, (int) length); return;
        }
        if (!method.equals("POST") || length > 8192) {
            json(output, 404, new JSONObject().put("error", s(R.string.err_unknown_action))); return;
        }
        JSONObject body = readJson(input, (int) length);
        session.bodyReceived();
        if (!expected.equals(preferences.getString("computer_token", ""))) {
            json(output, 401, new JSONObject().put("error", s(R.string.err_pair_removed))); return;
        }
        if (path.equals("/volume")) { setVolume(output, body); return; }
        if (path.equals("/brightness")) { setBrightness(output, body); return; }
        if (path.equals("/open")) { openApp(output, body); return; }
        if (path.equals("/open-url")) { openUrl(output, body); return; }
        if (path.equals("/install")) { installReceived(output); return; }
        if (path.equals("/action")) { action(output, body); return; }
        json(output, 404, new JSONObject().put("error", s(R.string.err_unknown_action)));
    }

    private boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) return false;
        int difference = 0;
        for (int i = 0; i < a.length(); i++) difference |= a.charAt(i) ^ b.charAt(i);
        return difference == 0;
    }

    private boolean setupComplete() {
        return SetupFlow.finished(
                PhoneAccessibilityService.isEnabled(this), Settings.System.canWrite(this));
    }

    private JSONObject readJson(InputStream input, int length) throws Exception {
        byte[] bytes = new byte[length];
        readFully(input, bytes);
        String claimed = claimedBody.get();
        if (claimed != null && !claimed.isEmpty() && !RequestAuth.bodyHash(bytes).equals(claimed))
            throw new IOException(s(R.string.err_body_mismatch));
        return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
    }

    private void readFully(InputStream input, byte[] bytes) throws IOException {
        int offset = 0;
        while (offset < bytes.length) {
            int count = input.read(bytes, offset, bytes.length - offset);
            if (count < 0) throw new IOException(s(R.string.err_transfer_cut));
            offset += count;
        }
    }

    private void setHomeWallpaper(InputStream input, OutputStream output, int length) throws Exception {
        if (length <= 0 || length > MAX_WALLPAPER) {
            json(output, 413, new JSONObject().put("error", s(R.string.err_wallpaper_too_big))); return;
        }
        byte[] data = new byte[length];
        readFully(input, data);
        String claimed = claimedBody.get();
        if (claimed != null && !claimed.isEmpty() && !RequestAuth.bodyHash(data).equals(claimed)) {
            json(output, 400, new JSONObject().put("error", s(R.string.err_wallpaper_mismatch))); return;
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
                bounds.outWidth > 16000 || bounds.outHeight > 16000) {
            json(output, 415, new JSONObject().put("error", s(R.string.err_wallpaper_format))); return;
        }
        Point size = new Point();
        ((WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getRealSize(size);
        BitmapFactory.Options options = new BitmapFactory.Options();
        int sample = 1;
        while (bounds.outWidth / sample > size.x * 2 &&
                bounds.outHeight / sample > size.y * 2) sample *= 2;
        options.inSampleSize = sample;
        Bitmap decoded = BitmapFactory.decodeByteArray(data, 0, data.length, options);
        if (decoded == null) {
            json(output, 415, new JSONObject().put("error", s(R.string.err_wallpaper_decode))); return;
        }
        Bitmap scaled = null;
        Bitmap cropped = null;
        try {
            float scale = Math.max((float) size.x / decoded.getWidth(),
                    (float) size.y / decoded.getHeight());
            int width = Math.max(size.x, Math.round(decoded.getWidth() * scale));
            int height = Math.max(size.y, Math.round(decoded.getHeight() * scale));
            if ((long) width * height > 40_000_000L) {
                json(output, 413, new JSONObject().put("error", s(R.string.err_wallpaper_ratio))); return;
            }
            scaled = Bitmap.createScaledBitmap(decoded, width, height, true);
            cropped = Bitmap.createBitmap(scaled, (width - size.x) / 2,
                    (height - size.y) / 2, size.x, size.y);
            WallpaperManager manager = WallpaperManager.getInstance(this);
            int before = manager.getWallpaperId(WallpaperManager.FLAG_SYSTEM);
            int lockBefore = manager.getWallpaperId(WallpaperManager.FLAG_LOCK);
            int applied = manager.setBitmap(cropped, null, false, WallpaperManager.FLAG_SYSTEM);
            int after = manager.getWallpaperId(WallpaperManager.FLAG_SYSTEM);
            int lockAfter = manager.getWallpaperId(WallpaperManager.FLAG_LOCK);
            if (applied <= 0 || after != applied || after == before) {
                json(output, 500, new JSONObject().put("error", s(R.string.err_wallpaper_unconfirmed))); return;
            }
            json(output, 200, new JSONObject().put("before", before).put("after", after)
                    .put("lock_unchanged", lockBefore == lockAfter));
        } finally {
            if (cropped != null && cropped != scaled && cropped != decoded) cropped.recycle();
            if (scaled != null && scaled != decoded) scaled.recycle();
            decoded.recycle();
        }
    }

    private JSONObject status() throws Exception {
        int capturePending;
        try (CaptureStore store = new CaptureStore(this)) {
            capturePending = store.pendingCount();
        }
        Intent battery = registerReceiver(null, new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int level = battery == null ? -1 : battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = battery == null ? -1 : battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        int state = battery == null ? -1 : battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        int temperature = battery == null ? -1 : battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);
        AudioManager audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        StatFs storage = new StatFs(Environment.getDataDirectory().getPath());
        ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
        ((ActivityManager) getSystemService(ACTIVITY_SERVICE)).getMemoryInfo(memory);
        ComputerConnection.Snapshot check = computerConnectionSnapshot();
        return new JSONObject().put("model", Build.MANUFACTURER + " " + Build.MODEL)
                .put("android", Build.VERSION.RELEASE)
                .put("battery_percent", scale > 0 ? level * 100 / scale : -1)
                .put("charging", state == BatteryManager.BATTERY_STATUS_CHARGING ||
                        state == BatteryManager.BATTERY_STATUS_FULL)
                .put("battery_celsius", temperature < 0 ? JSONObject.NULL : temperature / 10.0)
                .put("storage_free_bytes", storage.getAvailableBytes())
                .put("storage_total_bytes", storage.getTotalBytes())
                .put("memory_free_bytes", memory.availMem)
                .put("memory_low", memory.lowMemory)
                .put("media_volume", audio.getStreamVolume(AudioManager.STREAM_MUSIC))
                .put("media_volume_max", audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
                .put("brightness", Settings.System.getInt(getContentResolver(),
                        Settings.System.SCREEN_BRIGHTNESS, -1))
                .put("brightness_control_allowed", Settings.System.canWrite(this))
                .put("screen_control_enabled", PhoneAccessibilityService.isEnabled(this))
                .put("overlay_allowed", Settings.canDrawOverlays(this))
                .put("setup_complete", setupComplete())
                .put("capture_pending_count", capturePending)
                .put("capture_sync_error", humanError(preferences.getString("capture_sync_error", "")))
                .put("capture_sync_last_attempt_at", preferences.getLong("capture_sync_last_attempt_at", 0))
                .put("capture_sync_last_success_at", preferences.getLong("capture_sync_last_success_at", 0))
                .put("computer_last_seen_at", preferences.getLong("computer_last_seen_at", 0))
                .put("computer_receiver_last_seen_at", preferences.getLong("computer_receiver_last_seen_at", 0))
                .put("computer_connection_state", check.phase.name().toLowerCase(Locale.ROOT))
                .put("computer_check_started_at", check.startedAt)
                .put("computer_check_finished_at", check.finishedAt)
                .put("computer_check_error", humanError(check.error))
                .put("computer_retry_in_progress", check.attemptRunning)
                .put("computer_retry_started_at", check.attemptStartedAt)
                .put("computer_retry_remaining_ms", check.remainingMs())
                .put("computer_address", preferences.getString("computer_address", ""))
                .put("connection_transport", wifiOnline(this) ? "wifi" : bluetoothOnline() ? "bluetooth" : "offline")
                .put("bluetooth_state", bluetoothState())
                .put("shared_file_selected", !preferences.getString("shared_uri", "").isEmpty())
                .put("battery_optimization_exempt", powerExempt())
                .put("screen_interactive", screenInteractive())
                .put("foreground_package", PhoneAccessibilityService.foregroundPackage())
                .put("user_idle_ms", PhoneAccessibilityService.userIdleMs())
                .put("network", networkState())
                .put("last_received_apk", preferences.getString("last_received_apk_name", ""))
                .put("install_allowed", getPackageManager().canRequestPackageInstalls());
    }

    private boolean powerExempt() {
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        return power != null && power.isIgnoringBatteryOptimizations(getPackageName());
    }

    private boolean screenInteractive() {
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        return power != null && power.isInteractive();
    }

    private JSONObject networkState() throws Exception {
        JSONObject result = new JSONObject().put("vpn_active", false).put("validated", false)
                .put("transports", new JSONArray()).put("proxy", "");
        ConnectivityManager connectivity = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (connectivity == null) return result;
        Network active = connectivity.getActiveNetwork();
        NetworkCapabilities caps = active == null ? null : connectivity.getNetworkCapabilities(active);
        if (caps != null) {
            JSONArray transports = new JSONArray();
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) transports.put("wifi");
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) transports.put("cellular");
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) transports.put("vpn");
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) transports.put("ethernet");
            result.put("transports", transports)
                    .put("vpn_active", caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
                    .put("validated", caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED));
        }
        android.net.ProxyInfo proxy = connectivity.getDefaultProxy();
        if (proxy != null && proxy.getHost() != null) result.put("proxy", proxy.getHost() + ":" + proxy.getPort());
        return result;
    }

    private JSONArray apps() throws Exception {
        Intent launchable = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        JSONArray result = new JSONArray();
        for (android.content.pm.ResolveInfo info : getPackageManager().queryIntentActivities(launchable, 0)) {
            result.put(new JSONObject().put("name", info.loadLabel(getPackageManager()).toString())
                    .put("package", info.activityInfo.packageName));
        }
        return result;
    }

    private void setVolume(OutputStream output, JSONObject body) throws Exception {
        AudioManager audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        String channel = body.optString("channel", "media");
        int stream = channel.equals("ring") ? AudioManager.STREAM_RING : AudioManager.STREAM_MUSIC;
        int level = body.optInt("level", -1);
        if (level < 0 || level > audio.getStreamMaxVolume(stream)) {
            json(output, 400, new JSONObject().put("error", s(R.string.err_bad_volume))); return;
        }
        audio.setStreamVolume(stream, level, 0);
        json(output, 200, new JSONObject().put("channel", channel)
                .put("level", audio.getStreamVolume(stream)));
    }

    private void setBrightness(OutputStream output, JSONObject body) throws Exception {
        if (!Settings.System.canWrite(this)) {
            json(output, 403, new JSONObject().put("error", s(R.string.err_write_settings))); return;
        }
        int level = body.optInt("level", -1);
        if (level < 1 || level > 255) {
            json(output, 400, new JSONObject().put("error", s(R.string.err_brightness))); return;
        }
        Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
        Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, level);
        json(output, 200, new JSONObject().put("brightness", level));
    }

    private void openApp(OutputStream output, JSONObject body) throws Exception {
        String packageName = body.optString("package", "");
        if (!packageName.matches("[a-zA-Z][a-zA-Z0-9_.]{1,199}")) {
            json(output, 400, new JSONObject().put("error", s(R.string.err_bad_package))); return;
        }
        Intent launch = getPackageManager().getLaunchIntentForPackage(packageName);
        if (launch == null) { json(output, 404, new JSONObject().put("error", s(R.string.err_no_app))); return; }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PhoneAccessibilityService accessibility = PhoneAccessibilityService.current();
        if (accessibility != null) {
            try {
                accessibility.startActivity(launch);
                json(output, 200, new JSONObject().put("state", "opened"));
                return;
            } catch (RuntimeException ignored) { }
        }
        if (MainActivity.isVisible()) {
            startActivity(launch);
            json(output, 200, new JSONObject().put("state", "opened"));
        } else {
            PendingIntent tap = PendingIntent.getActivity(this, 48, launch,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification note = new Notification.Builder(this, CHANNEL)
                    .setSmallIcon(getResources().getIdentifier("cat_paw_notification", "drawable", getPackageName()))
                    .setContentTitle(s(R.string.notif_open_app_title))
                    .setContentText(s(R.string.notif_open_app_body, packageName)).setContentIntent(tap).setAutoCancel(true).build();
            getSystemService(NotificationManager.class).notify(48, note);
            json(output, 202, new JSONObject().put("state", "tap_phone_notification"));
        }
    }

    private void action(OutputStream output, JSONObject body) throws Exception {
        PhoneAccessibilityService accessibility = PhoneAccessibilityService.current();
        if (accessibility == null) {
            json(output, 403, new JSONObject().put("error", s(R.string.err_need_a11y))); return;
        }
        String type = body.optString("type", "");
        boolean accepted;
        if (type.equals("set_text")) {
            String text = body.optString("text", "");
            if (text.length() > 10000) { json(output, 413, new JSONObject().put("error", s(R.string.err_text_long))); return; }
            int code = accessibility.setText(text);
            if (code == PhoneAccessibilityService.SET_PASSWORD) {
                json(output, 403, new JSONObject().put("error", s(R.string.err_password))); return;
            }
            if (code == PhoneAccessibilityService.SET_NO_FIELD) {
                json(output, 409, new JSONObject().put("error", s(R.string.err_no_field))); return;
            }
            json(output, code == PhoneAccessibilityService.SET_OK ? 200 : 400,
                    new JSONObject().put("accepted", code == PhoneAccessibilityService.SET_OK).put("type", type));
            return;
        }
        if (body.optBoolean("respect_user", false)) {
            long idle = PhoneAccessibilityService.userIdleMs();
            if (idle >= 0 && idle < USER_ACTIVE_WINDOW_MS) {
                json(output, 409, new JSONObject().put("error", s(R.string.err_user_active))
                        .put("user_idle_ms", idle)); return;
            }
        }
        if (type.equals("tap")) accepted = accessibility.tap(body.optInt("x", -1), body.optInt("y", -1));
        else if (type.equals("swipe")) accepted = accessibility.swipe(body.optInt("x1", -1),
                body.optInt("y1", -1), body.optInt("x2", -1), body.optInt("y2", -1),
                body.optInt("duration_ms", 350));
        else accepted = accessibility.global(type);
        json(output, accepted ? 200 : 400,
                new JSONObject().put("accepted", accepted).put("type", type));
    }

    private static final long USER_ACTIVE_WINDOW_MS = 8000;

    private void uiTree(OutputStream output) throws Exception {
        PhoneAccessibilityService accessibility = PhoneAccessibilityService.current();
        if (accessibility == null) {
            json(output, 403, new JSONObject().put("error", s(R.string.err_need_a11y))); return;
        }
        json(output, 200, new JSONObject().put("package", PhoneAccessibilityService.foregroundPackage())
                .put("nodes", accessibility.uiTree()));
    }

    /** Start an activity like /open does: accessibility context, visible UI, else a notification. */
    private int launch(Intent intent, String label) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PhoneAccessibilityService accessibility = PhoneAccessibilityService.current();
        if (accessibility != null) {
            try { accessibility.startActivity(intent); return 200; } catch (RuntimeException ignored) { }
        }
        if (MainActivity.isVisible() || Settings.canDrawOverlays(this)) {
            try { startActivity(intent); return 200; } catch (RuntimeException ignored) { }
        }
        PendingIntent tap = PendingIntent.getActivity(this, 49, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification note = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(getResources().getIdentifier("cat_paw_notification", "drawable", getPackageName()))
                .setContentTitle(s(R.string.notif_open_title)).setContentText(label)
                .setContentIntent(tap).setAutoCancel(true).build();
        getSystemService(NotificationManager.class).notify(49, note);
        return 202;
    }

    private void launchReply(OutputStream output, Intent intent, String label) throws Exception {
        int code = launch(intent, label);
        json(output, code, new JSONObject().put("state", code == 200 ? "opened" : "tap_phone_notification"));
    }

    private void openUrl(OutputStream output, JSONObject body) throws Exception {
        String url = body.optString("url", "");
        if (!RoutePolicy.allowedUrl(url)) {
            json(output, 400, new JSONObject().put("error", s(R.string.err_bad_url))); return;
        }
        Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE);
        String pkg = body.optString("package", "");
        if (!pkg.isEmpty()) {
            if (!RoutePolicy.validPackage(pkg)) {
                json(output, 400, new JSONObject().put("error", s(R.string.err_bad_package))); return;
            }
            view.setPackage(pkg);
            if (view.resolveActivity(getPackageManager()) == null) {
                json(output, 404, new JSONObject().put("error", s(R.string.err_url_app))); return;
            }
        }
        launchReply(output, view, s(R.string.err_open_url, url.length() > 60 ? url.substring(0, 60) + "…" : url));
    }

    /** A newer 猫叼 APK opens the system installer right away so updating is one tap. */
    private String selfUpdate(Uri uri, String name) {
        File copy = new File(getCacheDir(), "incoming-update.apk");
        try {
            try (InputStream in = getContentResolver().openInputStream(uri);
                 OutputStream out = new java.io.FileOutputStream(copy)) {
                if (in == null) return "unreadable";
                byte[] buffer = new byte[65536];
                int count;
                while ((count = in.read(buffer)) > 0) out.write(buffer, 0, count);
            }
            android.content.pm.PackageInfo incoming = getPackageManager()
                    .getPackageArchiveInfo(copy.getPath(), 0);
            if (incoming == null || !getPackageName().equals(incoming.packageName)) return "other_app";
            long current = getPackageManager().getPackageInfo(getPackageName(), 0).getLongVersionCode();
            if (incoming.getLongVersionCode() <= current) return "not_newer";
            if (!getPackageManager().canRequestPackageInstalls()) {
                launch(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName())), s(R.string.err_allow_update));
                return "needs_install_permission";
            }
            Intent install = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            return launch(install, s(R.string.err_install_self)) == 200 ? "installer_opened" : "tap_phone_notification";
        } catch (Exception error) {
            return "check_failed";
        } finally {
            copy.delete();
        }
    }

    private void installReceived(OutputStream output) throws Exception {
        String saved = preferences.getString("last_received_apk_uri", "");
        if (saved.isEmpty()) {
            json(output, 404, new JSONObject().put("error", s(R.string.err_no_apk))); return;
        }
        if (!getPackageManager().canRequestPackageInstalls()) {
            launch(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName())), s(R.string.err_allow_install));
            json(output, 403, new JSONObject().put("error", s(R.string.err_install_blocked))
                    .put("state", "needs_install_permission")); return;
        }
        Intent install = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(Uri.parse(saved), "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        launchReply(output, install, s(R.string.err_install_named, preferences.getString("last_received_apk_name", "APK")));
    }

    private void screenshot(OutputStream output) throws Exception {
        PhoneAccessibilityService accessibility = PhoneAccessibilityService.current();
        if (accessibility == null) {
            json(output, 403, new JSONObject().put("error", s(R.string.err_need_a11y))); return;
        }
        byte[] jpeg = Build.VERSION.SDK_INT >= 30 ? accessibility.screenshot() : null;
        if (jpeg == null) {
            json(output, 503, new JSONObject().put("error", s(R.string.err_screenshot))); return;
        }
        respond(output, 200, null, "image/jpeg", jpeg);
    }

    private void receiveFile(InputStream input, OutputStream output, Map<String, String> headers, long length) throws Exception {
        if (length <= 0) { json(output, 400, new JSONObject().put("error", s(R.string.err_empty_file))); return; }
        File storage = getExternalFilesDir(null);
        if (storage == null) {
            json(output, 503, new JSONObject().put("error", s(R.string.err_storage))); return;
        }
        long available = new StatFs(storage.getPath()).getAvailableBytes();
        if (length > available - STORAGE_RESERVE) {
            json(output, 507, new JSONObject().put("error", s(R.string.err_no_space))); return;
        }
        String encoded = headers.getOrDefault("x-filename", "file.bin");
        String name = URLDecoder.decode(encoded, "UTF-8").replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        if (name.length() > 120) name = name.substring(name.length() - 120);
        if (name.isEmpty() || name.equals(".") || name.equals("..")) name = "file.bin";
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, name);
        values.put(MediaStore.Downloads.MIME_TYPE, headers.getOrDefault("content-type", "application/octet-stream"));
        values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/猫叼");
        values.put(MediaStore.Downloads.IS_PENDING, 1);
        Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) { json(output, 500, new JSONObject().put("error", s(R.string.err_cant_create))); return; }
        java.security.MessageDigest digest;
        try { digest = java.security.MessageDigest.getInstance("SHA-256"); }
        catch (Exception error) { throw new IOException(s(R.string.err_cant_hash)); }
        try (OutputStream file = getContentResolver().openOutputStream(uri)) {
            if (file == null) throw new IOException(s(R.string.err_cant_write));
            byte[] buffer = new byte[65536];
            long remaining = length;
            while (remaining > 0) {
                int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (count < 0) throw new IOException(s(R.string.err_transfer_cut));
                digest.update(buffer, 0, count);
                file.write(buffer, 0, count);
                remaining -= count;
            }
        } catch (Exception error) {
            getContentResolver().delete(uri, null, null);
            throw error;
        }
        String claimed = claimedBody.get();
        String actual = hex(digest.digest());
        if (claimed != null && !claimed.isEmpty() && !claimed.equals(actual)) {
            getContentResolver().delete(uri, null, null);
            json(output, 400, new JSONObject().put("error", s(R.string.err_file_mismatch))); return;
        }
        values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0);
        getContentResolver().update(uri, values, null, null);
        if (name.toLowerCase(Locale.ROOT).endsWith(".apk")) {
            preferences.edit().putString("last_received_apk_uri", uri.toString())
                    .putString("last_received_apk_name", name).apply();
            String update = selfUpdate(uri, name);
            json(output, 200, new JSONObject().put("saved", name).put("folder", "下载/猫叼")
                    .put("self_update", update));
            return;
        }
        json(output, 200, new JSONObject().put("saved", name).put("folder", "下载/猫叼"));
    }

    private void sharedFile(OutputStream output) throws Exception {
        String saved = preferences.getString("shared_uri", "");
        if (saved.isEmpty()) { json(output, 404, new JSONObject().put("error", s(R.string.err_pick_file))); return; }
        Uri uri = Uri.parse(saved);
        String name = "phone-file";
        long size = -1;
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                if (!cursor.isNull(0)) name = cursor.getString(0);
                if (!cursor.isNull(1)) size = cursor.getLong(1);
            }
        }
        String encoded = java.net.URLEncoder.encode(name, "UTF-8");
        String head = "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\n" +
                "X-Filename: " + encoded + "\r\n" +
                (size >= 0 ? "Content-Length: " + size + "\r\n" : "") +
                "Connection: close\r\n\r\n";
        try (InputStream file = getContentResolver().openInputStream(uri)) {
            if (file == null) throw new IOException(s(R.string.err_cant_read));
            output.write(head.getBytes(StandardCharsets.US_ASCII));
            byte[] buffer = new byte[65536];
            int count;
            while ((count = file.read(buffer)) >= 0) output.write(buffer, 0, count);
        }
        output.flush();
    }

    private void json(OutputStream output, int status, Object value) throws IOException {
        respond(output, status, null, "application/json; charset=utf-8",
                value.toString().getBytes(StandardCharsets.UTF_8));
    }

    private void respond(OutputStream output, int status, String message, String type, byte[] data) throws IOException {
        byte[] body = data == null ? message.getBytes(StandardCharsets.UTF_8) : data;
        String reason = status < 300 ? "OK" : "Error";
        String nonce = requestNonce.get();
        String token = requestToken.get();
        String signature = "";
        if (nonce != null && token != null && !nonce.isEmpty() && !token.isEmpty()) {
            signature = "X-CatDiao-Sig: " + RequestAuth.signResponse(token, nonce, status, body) + "\r\n";
        }
        String headers = "HTTP/1.1 " + status + " " + reason + "\r\n" +
                "Content-Type: " + type + "\r\nContent-Length: " + body.length + "\r\n" +
                signature + "Connection: close\r\n\r\n";
        output.write(headers.getBytes(StandardCharsets.US_ASCII));
        output.write(body); output.flush();
    }

    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) text.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return text.toString();
    }
}
