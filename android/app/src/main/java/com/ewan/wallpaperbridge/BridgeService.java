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
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.StatFs;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.view.WindowManager;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;

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
    private final Object captureWake = new Object();
    private Thread captureWorker;
    private volatile ComputerConnection computerConnection;
    private volatile ComputerConnection.Attempt captureAttempt;

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
        computerConnection = new ComputerConnection(attempt -> ComputerConnection.probe(
                preferences.getString("computer_address", ""), 8793,
                preferences.getString("computer_token", ""), attempt), snapshot -> {
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
                .putBoolean("overlay_wanted", enabled).apply();
        BridgeService service = active;
        if (service != null) service.updateOverlay();
    }

    public static void setupFinished() {
        if (active != null) active.showNotification("小窝连接已设好");
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
            service.showNotification(allow ? "正在连接小窝" : "已拒绝小窝连接");
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
            ComputerConnection.Attempt attempt = service.captureAttempt;
            if (attempt != null) attempt.cancel();
            LocalBridgeServer server = service.localServer;
            if (server != null) server.closeClients();
            service.showNotification("小窝未连接；收藏会先保存在手机");
        }
    }

    public static void shareFile(Context context, Uri uri) {
        context.getSharedPreferences("bridge", MODE_PRIVATE).edit()
                .putString("shared_uri", uri.toString()).apply();
    }

    @Override public void onCreate() {
        super.onCreate();
        active = this;
        preferences = getSharedPreferences("bridge", MODE_PRIVATE);
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "猫叼与小窝", NotificationManager.IMPORTANCE_LOW));
        startForeground(47, notification(preferences.getString("computer_token", "").isEmpty()
                ? "小窝未连接；收藏会先保存在手机"
                : setupComplete() ? "小窝连接已设好" : "小窝连接设置待完成"));
        preferences.edit().remove("keep_awake").apply();
        localServer = new LocalBridgeServer(PORT, 8769, this::handleClient, error -> {
            if (!destroyed && active == this) showNotification("连接未开启，请重新打开应用");
        });
        startServer();
        updateOverlay();
        captureWorker = new Thread(this::captureSyncLoop, "phone-capture-sync");
        captureWorker.start();
        startConnectionChecks();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        startServer();
        if (intent != null && ACTION_CHECK_CONNECTION.equals(intent.getAction())) {
            startConnectionChecks();
            ComputerConnection check = computerConnection;
            if (check != null) check.request(true);
        }
        return START_STICKY;
    }

    @Override public void onDestroy() {
        destroyed = true;
        stopConnectionChecks();
        LocalBridgeServer server = localServer;
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

    private void updateOverlay() {
        if (!overlayEnabled(this) || visibleAppActivities > 0) { removeOverlay(); return; }
        if (overlayView != null) return;
        try {
            overlayManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            ImageView bubble = new ImageView(this);
            bubble.setImageResource(getResources().getIdentifier("cat_diao_launcher", "drawable", getPackageName()));
            bubble.setScaleType(ImageView.ScaleType.FIT_CENTER);
            bubble.setContentDescription("打开猫叼随手操作");
            int size = Math.round(56 * getResources().getDisplayMetrics().density);
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    android.graphics.PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.LEFT;
            params.x = Math.round(8 * getResources().getDisplayMetrics().density);
            params.y = Math.round(240 * getResources().getDisplayMetrics().density);
            bubble.setOnTouchListener(new View.OnTouchListener() {
                private float downX, downY;
                private int startX, startY;
                @Override public boolean onTouch(View view, MotionEvent event) {
                    if (event.getAction() == MotionEvent.ACTION_DOWN) {
                        downX = event.getRawX(); downY = event.getRawY();
                        startX = params.x; startY = params.y;
                        return true;
                    }
                    if (event.getAction() == MotionEvent.ACTION_MOVE) {
                        params.x = startX + Math.round(event.getRawX() - downX);
                        params.y = startY + Math.round(event.getRawY() - downY);
                        overlayManager.updateViewLayout(view, params);
                        return true;
                    }
                    if (event.getAction() == MotionEvent.ACTION_UP) {
                        int slop = Math.round(12 * getResources().getDisplayMetrics().density);
                        if (Math.abs(event.getRawX() - downX) < slop && Math.abs(event.getRawY() - downY) < slop) {
                            Intent capture = new Intent(BridgeService.this, QuickActionsActivity.class)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(capture);
                        }
                        return true;
                    }
                    return false;
                }
            });
            overlayManager.addView(bubble, params);
            overlayView = bubble;
        } catch (RuntimeException error) { overlayView = null; }
    }

    private void removeOverlay() {
        if (overlayView != null && overlayManager != null) {
            try { overlayManager.removeView(overlayView); } catch (RuntimeException ignored) { }
        }
        overlayView = null;
    }

    private void captureSyncLoop() {
        while (!destroyed && active == this) {
            boolean more = false;
            try { more = syncCapturesOnce(); }
            catch (Exception error) {
                String detail = error instanceof ComputerConnection.CheckFailure ? error.getMessage()
                        : "本次收藏未送达，电脑接收程序暂未确认。";
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
            preferences.edit().putString("capture_sync_error", "小窝未连接").apply();
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
            if (batch.count() == 0) throw new IOException("这条收藏过大，暂时无法送达");
            byte[] payload = batch.payload();
            ComputerConnection.Attempt attempt = new ComputerConnection.Attempt(ComputerConnection.TIMEOUT_MS);
            captureAttempt = attempt;
            Timer deadline = new Timer("catdiao-capture-deadline", true);
            deadline.schedule(new TimerTask() {
                @Override public void run() { attempt.cancel(); }
            }, ComputerConnection.TIMEOUT_MS);
            try {
                if (destroyed || active != this) throw new IOException("手机服务已停止");
                ComputerConnection.Reply response = ComputerConnection.exchange(address, 8793, "POST", "/captures",
                        "X-Phone-Token", token, payload, attempt);
                if (response.code != 200) {
                    throw new ComputerConnection.CheckFailure(response.code == 403
                            ? "电脑没有确认当前配对，请查看「小窝连接」。"
                            : "电脑暂未接收这批收藏，稍后会重试。");
                }
                JSONObject reply = new JSONObject(new String(response.body, StandardCharsets.UTF_8));
                JSONArray acknowledged = reply.optJSONArray("accepted");
                JSONArray accepted = new JSONArray();
                if (acknowledged != null) for (int index = 0; index < acknowledged.length(); index++) {
                    String id = acknowledged.optString(index);
                    if (sentIds.contains(id)) accepted.put(id);
                }
                if (accepted.length() == 0) throw new ComputerConnection.CheckFailure(
                        "电脑尚未确认本批收藏，猫叼会保留它们并重试。");
                if (destroyed || active != this || !token.equals(preferences.getString("computer_token", ""))) return false;
                store.acknowledge(accepted);
                preferences.edit().remove("capture_sync_error")
                        .putLong("capture_sync_last_success_at", System.currentTimeMillis()).apply();
                return store.pendingCount() > 0;
            } finally {
                deadline.cancel();
                attempt.cancel();
                if (captureAttempt == attempt) captureAttempt = null;
            }
        }
    }

    private Notification notification(String message) {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent open = PendingIntent.getActivity(this, 47, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(getResources().getIdentifier("cat_paw_notification", "drawable", getPackageName()))
                .setContentTitle("猫叼").setContentText(message)
                .setContentIntent(open).setOngoing(true).build();
    }

    private void showNotification(String message) {
        getSystemService(NotificationManager.class).notify(47, notification(message));
    }

    private void startServer() {
        LocalBridgeServer server = localServer;
        if (destroyed || active != this || server == null) return;
        try { server.start(); }
        catch (IOException error) { showNotification("连接未开启，请重新打开应用"); }
    }

    private boolean isLocal(InetAddress address) {
        return address.isSiteLocalAddress() || address.isLoopbackAddress();
    }

    private void handleClient(LocalBridgeServer.Client session) throws Exception {
        Socket client = session.socket;
        if (!isLocal(client.getInetAddress())) {
            respond(client.getOutputStream(), 403, "仅允许局域网", "text/plain", null);
            return;
        }
        try { handle(session); }
        catch (Exception error) {
            try {
                json(client.getOutputStream(), 500, new JSONObject().put("error", "手机处理请求失败：" +
                        (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage())));
            } catch (Exception ignored) { }
        }
    }


    private void handle(LocalBridgeServer.Client session) throws Exception {
        Socket client = session.socket;
        InputStream input = client.getInputStream();
        OutputStream output = client.getOutputStream();
        String[] lines = session.readHeaders().split("\r\n");
        String[] request = lines[0].split(" ");
        if (request.length < 2) { respond(output, 400, "请求无效", "text/plain", null); return; }
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
            catch (NumberFormatException error) { respond(output, 400, "长度无效", "text/plain", null); return; }
        }
        if (length < 0) { respond(output, 400, "长度无效", "text/plain", null); return; }
        // Small JSON bodies have a total deadline; file uploads retain their progress timeout.
        if (length > 0 && !request[1].equals("/file") && !request[1].equals("/wallpaper/home"))
            session.watchBody(20000);

        if (method.equals("GET") && path.equals("/hello")) {
            JSONObject result = new JSONObject().put("name", "猫叼")
                    .put("version", "1.13").put("paired", !preferences.getString("computer_token", "").isEmpty())
                    .put("setup_complete", setupComplete())
                    .put("model", Build.MODEL);
            json(output, 200, result); return;
        }
        if (method.equals("POST") && path.equals("/pair")) {
            if (length > 8192) { json(output, 413, new JSONObject().put("error", "连接请求过长")); return; }
            JSONObject body = readJson(input, (int) length);
            session.bodyReceived();
            String name = body.optString("name", "小窝").trim();
            String token = body.optString("token", "");
            if (name.length() < 1 || name.length() > 60 || !token.matches("[a-fA-F0-9]{64}")) {
                json(output, 400, new JSONObject().put("error", "连接请求无效")); return;
            }
            if (!preferences.getString("computer_token", "").isEmpty()) {
                json(output, 409, new JSONObject().put("error", "已有小窝连接；如需更换，请在猫叼的「小窝连接」中移除")); return;
            }
            PairingGate.Request pair = pairing.offer(name, client.getInetAddress().getHostAddress(), token,
                    () -> !preferences.getString("computer_token", "").isEmpty());
            if (pair == null) {
                json(output, 409, new JSONObject().put("error", "已有小窝连接或连接请求，请先在猫叼处理"));
                return;
            }
            showNotification("打开猫叼确认小窝连接：" + name);
            json(output, 202, new JSONObject().put("id", pair.id).put("state", "pending")); return;
        }
        if (method.equals("GET") && path.startsWith("/pair-status?id=")) {
            String id = path.substring("/pair-status?id=".length());
            String state = pairing.status(id);
            if (state == null) {
                json(output, 404, new JSONObject().put("error", "请求已失效")); return;
            }
            json(output, 200, new JSONObject().put("state", state)); return;
        }
        String expected = preferences.getString("computer_token", "");
        String supplied = headers.getOrDefault("x-phone-token", "");
        if (expected.isEmpty() || !constantTimeEquals(expected, supplied)) {
            json(output, 401, new JSONObject().put("error", "请先在猫叼连接小窝")); return;
        }
        preferences.edit().putLong("computer_last_seen_at", System.currentTimeMillis()).apply();
        String currentComputerAddress = client.getInetAddress().getHostAddress();
        if (!currentComputerAddress.equals(preferences.getString("computer_address", ""))) {
            preferences.edit().putString("computer_address", currentComputerAddress).apply();
            requestCaptureSync();
            ComputerConnection check = computerConnection;
            if (check != null) check.request(false);
        }
        if (method.equals("GET") && path.equals("/heartbeat")) {
            requestCaptureSync();
            json(output, 200, new JSONObject().put("connected", true)); return;
        }
        if (method.equals("GET") && path.equals("/status")) { json(output, 200, status()); return; }
        if (!setupComplete()) {
            json(output, 428, new JSONObject().put("error",
                    "小窝连接设置未完成；请打开猫叼继续设置")); return;
        }
        if (method.equals("GET") && path.equals("/apps")) { json(output, 200, apps()); return; }
        if (method.equals("GET") && path.equals("/screen")) { screenshot(output); return; }
        if (method.equals("GET") && path.equals("/shared-file")) { sharedFile(output); return; }
        if (method.equals("POST") && path.equals("/clipboard")) {
            if (length > 262144) {
                json(output, 413, new JSONObject().put("error", "文字不能超过 256 KB")); return;
            }
            JSONObject body = readJson(input, (int) length);
            session.bodyReceived();
            if (!constantTimeEquals(preferences.getString("computer_token", ""), supplied)) {
                json(output, 401, new JSONObject().put("error", "这台小窝的连接已移除")); return;
            }
            Object value = body.opt("text");
            if (!(value instanceof String) || ((String) value).isEmpty()) {
                json(output, 400, new JSONObject().put("error", "请提供要复制的文字")); return;
            }
            ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(
                    ClipData.newPlainText("来自小窝", (String) value));
            json(output, 200, new JSONObject().put("copied", true)
                    .put("characters", ((String) value).length())); return;
        }
        if (method.equals("POST") && path.equals("/file")) {
            client.setSoTimeout(180000);
            receiveFile(input, output, headers, length); return;
        }
        if (method.equals("POST") && path.equals("/wallpaper/home")) {
            if (length > MAX_WALLPAPER) {
                json(output, 413, new JSONObject().put("error", "壁纸图片不能超过 20 MB")); return;
            }
            setHomeWallpaper(input, output, (int) length); return;
        }
        if (!method.equals("POST") || length > 8192) {
            json(output, 404, new JSONObject().put("error", "没有这个操作")); return;
        }
        JSONObject body = readJson(input, (int) length);
        session.bodyReceived();
        if (!constantTimeEquals(preferences.getString("computer_token", ""), supplied)) {
            json(output, 401, new JSONObject().put("error", "这台小窝的连接已移除")); return;
        }
        if (path.equals("/volume")) { setVolume(output, body); return; }
        if (path.equals("/brightness")) { setBrightness(output, body); return; }
        if (path.equals("/open")) { openApp(output, body); return; }
        if (path.equals("/action")) { action(output, body); return; }
        json(output, 404, new JSONObject().put("error", "没有这个操作"));
    }

    private boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) return false;
        int difference = 0;
        for (int i = 0; i < a.length(); i++) difference |= a.charAt(i) ^ b.charAt(i);
        return difference == 0;
    }

    private boolean setupComplete() {
        return PhoneAccessibilityService.isEnabled(this) &&
                Settings.canDrawOverlays(this) && Settings.System.canWrite(this);
    }

    private JSONObject readJson(InputStream input, int length) throws Exception {
        byte[] bytes = new byte[length];
        readFully(input, bytes);
        return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
    }

    private void readFully(InputStream input, byte[] bytes) throws IOException {
        int offset = 0;
        while (offset < bytes.length) {
            int count = input.read(bytes, offset, bytes.length - offset);
            if (count < 0) throw new IOException("连接中断");
            offset += count;
        }
    }

    private void setHomeWallpaper(InputStream input, OutputStream output, int length) throws Exception {
        if (length <= 0 || length > MAX_WALLPAPER) {
            json(output, 413, new JSONObject().put("error", "壁纸图片不能超过 20 MB")); return;
        }
        byte[] data = new byte[length];
        readFully(input, data);
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
                bounds.outWidth > 16000 || bounds.outHeight > 16000) {
            json(output, 415, new JSONObject().put("error", "图片格式或尺寸不支持")); return;
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
            json(output, 415, new JSONObject().put("error", "无法解码图片")); return;
        }
        Bitmap scaled = null;
        Bitmap cropped = null;
        try {
            float scale = Math.max((float) size.x / decoded.getWidth(),
                    (float) size.y / decoded.getHeight());
            int width = Math.max(size.x, Math.round(decoded.getWidth() * scale));
            int height = Math.max(size.y, Math.round(decoded.getHeight() * scale));
            if ((long) width * height > 40_000_000L) {
                json(output, 413, new JSONObject().put("error", "图片比例过于极端")); return;
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
                json(output, 500, new JSONObject().put("error", "手机未确认桌面壁纸已更新")); return;
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
                .put("capture_sync_error", preferences.getString("capture_sync_error", ""))
                .put("capture_sync_last_attempt_at", preferences.getLong("capture_sync_last_attempt_at", 0))
                .put("capture_sync_last_success_at", preferences.getLong("capture_sync_last_success_at", 0))
                .put("computer_last_seen_at", preferences.getLong("computer_last_seen_at", 0))
                .put("computer_receiver_last_seen_at", preferences.getLong("computer_receiver_last_seen_at", 0))
                .put("computer_connection_state", check.phase.name().toLowerCase(Locale.ROOT))
                .put("computer_check_started_at", check.startedAt)
                .put("computer_check_finished_at", check.finishedAt)
                .put("computer_check_error", check.error)
                .put("computer_retry_in_progress", check.attemptRunning)
                .put("computer_retry_started_at", check.attemptStartedAt)
                .put("computer_retry_remaining_ms", check.remainingMs())
                .put("computer_address", preferences.getString("computer_address", ""))
                .put("shared_file_selected", !preferences.getString("shared_uri", "").isEmpty());
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
            json(output, 400, new JSONObject().put("error", "音量范围无效")); return;
        }
        audio.setStreamVolume(stream, level, 0);
        json(output, 200, new JSONObject().put("channel", channel)
                .put("level", audio.getStreamVolume(stream)));
    }

    private void setBrightness(OutputStream output, JSONObject body) throws Exception {
        if (!Settings.System.canWrite(this)) {
            json(output, 403, new JSONObject().put("error", "请在手机应用中允许修改系统设置")); return;
        }
        int level = body.optInt("level", -1);
        if (level < 1 || level > 255) {
            json(output, 400, new JSONObject().put("error", "亮度须在 1 到 255 之间")); return;
        }
        Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
        Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, level);
        json(output, 200, new JSONObject().put("brightness", level));
    }

    private void openApp(OutputStream output, JSONObject body) throws Exception {
        String packageName = body.optString("package", "");
        if (!packageName.matches("[a-zA-Z][a-zA-Z0-9_.]{1,199}")) {
            json(output, 400, new JSONObject().put("error", "应用名称无效")); return;
        }
        Intent launch = getPackageManager().getLaunchIntentForPackage(packageName);
        if (launch == null) { json(output, 404, new JSONObject().put("error", "找不到可打开的应用")); return; }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (MainActivity.isVisible()) {
            startActivity(launch);
            json(output, 200, new JSONObject().put("state", "opened"));
        } else {
            PendingIntent tap = PendingIntent.getActivity(this, 48, launch,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification note = new Notification.Builder(this, CHANNEL)
                    .setSmallIcon(getResources().getIdentifier("cat_paw_notification", "drawable", getPackageName()))
                    .setContentTitle("小窝请求打开应用")
                    .setContentText("点此打开 " + packageName).setContentIntent(tap).setAutoCancel(true).build();
            getSystemService(NotificationManager.class).notify(48, note);
            json(output, 202, new JSONObject().put("state", "tap_phone_notification"));
        }
    }

    private void action(OutputStream output, JSONObject body) throws Exception {
        PhoneAccessibilityService accessibility = PhoneAccessibilityService.current();
        if (accessibility == null) {
            json(output, 403, new JSONObject().put("error", "请在手机上开启「猫叼」屏幕控制")); return;
        }
        String type = body.optString("type", "");
        boolean accepted;
        if (type.equals("tap")) accepted = accessibility.tap(body.optInt("x", -1), body.optInt("y", -1));
        else if (type.equals("swipe")) accepted = accessibility.swipe(body.optInt("x1", -1),
                body.optInt("y1", -1), body.optInt("x2", -1), body.optInt("y2", -1),
                body.optInt("duration_ms", 350));
        else accepted = accessibility.global(type);
        json(output, accepted ? 200 : 400,
                new JSONObject().put("accepted", accepted).put("type", type));
    }

    private void screenshot(OutputStream output) throws Exception {
        PhoneAccessibilityService accessibility = PhoneAccessibilityService.current();
        if (accessibility == null) {
            json(output, 403, new JSONObject().put("error", "请在手机上开启「猫叼」屏幕控制")); return;
        }
        byte[] jpeg = accessibility.screenshot();
        if (jpeg == null) {
            json(output, 503, new JSONObject().put("error", "截图暂不可用；锁屏或受保护的页面可能禁止截图")); return;
        }
        respond(output, 200, null, "image/jpeg", jpeg);
    }

    private void receiveFile(InputStream input, OutputStream output, Map<String, String> headers, long length) throws Exception {
        if (length <= 0) { json(output, 400, new JSONObject().put("error", "文件为空")); return; }
        File storage = getExternalFilesDir(null);
        if (storage == null) {
            json(output, 503, new JSONObject().put("error", "手机存储暂不可用")); return;
        }
        long available = new StatFs(storage.getPath()).getAvailableBytes();
        if (length > available - STORAGE_RESERVE) {
            json(output, 507, new JSONObject().put("error", "手机剩余空间不足，无法保存此文件")); return;
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
        if (uri == null) { json(output, 500, new JSONObject().put("error", "手机无法创建文件")); return; }
        try (OutputStream file = getContentResolver().openOutputStream(uri)) {
            if (file == null) throw new IOException("手机无法写入文件");
            byte[] buffer = new byte[65536];
            long remaining = length;
            while (remaining > 0) {
                int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (count < 0) throw new IOException("传输中断");
                file.write(buffer, 0, count);
                remaining -= count;
            }
        } catch (Exception error) {
            getContentResolver().delete(uri, null, null);
            throw error;
        }
        values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0);
        getContentResolver().update(uri, values, null, null);
        json(output, 200, new JSONObject().put("saved", name).put("folder", "下载/猫叼"));
    }

    private void sharedFile(OutputStream output) throws Exception {
        String saved = preferences.getString("shared_uri", "");
        if (saved.isEmpty()) { json(output, 404, new JSONObject().put("error", "请先在手机应用里选择文件")); return; }
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
            if (file == null) throw new IOException("手机无法读取选中的文件");
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
        String headers = "HTTP/1.1 " + status + " " + reason + "\r\n" +
                "Content-Type: " + type + "\r\nContent-Length: " + body.length +
                "\r\nConnection: close\r\n\r\n";
        output.write(headers.getBytes(StandardCharsets.US_ASCII));
        output.write(body); output.flush();
    }
}
