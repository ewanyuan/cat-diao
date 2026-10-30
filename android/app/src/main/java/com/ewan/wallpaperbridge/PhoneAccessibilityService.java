package com.ewan.wallpaperbridge;

import android.annotation.TargetApi;
import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class PhoneAccessibilityService extends AccessibilityService {
    private static volatile PhoneAccessibilityService instance;
    private final Handler main = new Handler(Looper.getMainLooper());

    public static PhoneAccessibilityService current() { return instance; }

    public static boolean isEnabled(Context context) {
        AccessibilityManager manager = (AccessibilityManager)
                context.getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (manager == null) return false;
        for (AccessibilityServiceInfo info : manager.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
            if (info.getResolveInfo() != null && info.getResolveInfo().serviceInfo != null &&
                    context.getPackageName().equals(info.getResolveInfo().serviceInfo.packageName) &&
                    PhoneAccessibilityService.class.getName().equals(
                            info.getResolveInfo().serviceInfo.name)) return true;
        }
        return false;
    }

    @Override protected void onServiceConnected() { super.onServiceConnected(); instance = this; }
    private static volatile String foregroundPackage = "";
    private static volatile long lastUserEventAt = 0;
    private static volatile long lastOwnActionAt = 0;
    /** Events within this window after our own gesture are attributed to us, not the user. */
    static final long OWN_ACTION_GRACE_MS = 1500;

    public static String foregroundPackage() { return foregroundPackage; }

    /** Milliseconds since the last on-device interaction not caused by 猫叼, or -1 if unknown. */
    public static long userIdleMs() {
        long at = lastUserEventAt;
        return at == 0 ? -1 : SystemClock.elapsedRealtime() - at;
    }

    private static void markOwnAction() { lastOwnActionAt = SystemClock.elapsedRealtime(); }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        CharSequence pkg = event.getPackageName();
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pkg != null
                && !"com.android.systemui".contentEquals(pkg)) foregroundPackage = pkg.toString();
        if (pkg != null && getPackageName().contentEquals(pkg)) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastOwnActionAt > OWN_ACTION_GRACE_MS) lastUserEventAt = now;
    }
    @Override public void onInterrupt() { }
    @Override public void onDestroy() { instance = null; super.onDestroy(); }

    public boolean global(String type) throws InterruptedException {
        int action;
        switch (type) {
            case "home": action = GLOBAL_ACTION_HOME; break;
            case "back": action = GLOBAL_ACTION_BACK; break;
            case "recents": action = GLOBAL_ACTION_RECENTS; break;
            case "notifications": action = GLOBAL_ACTION_NOTIFICATIONS; break;
            default: return false;
        }
        markOwnAction();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Boolean> result = new AtomicReference<>(false);
        main.post(() -> { result.set(performGlobalAction(action)); latch.countDown(); });
        return latch.await(5, TimeUnit.SECONDS) && result.get();
    }

    public boolean tap(int x, int y) throws InterruptedException {
        if (x < 0 || y < 0) return false;
        Path path = new Path(); path.moveTo(x, y);
        return gesture(path, 90);
    }

    public boolean swipe(int x1, int y1, int x2, int y2, int duration) throws InterruptedException {
        if (x1 < 0 || y1 < 0 || x2 < 0 || y2 < 0 || duration < 100 || duration > 3000) return false;
        Path path = new Path(); path.moveTo(x1, y1); path.lineTo(x2, y2);
        return gesture(path, duration);
    }

    private boolean gesture(Path path, int duration) throws InterruptedException {
        markOwnAction();
        GestureDescription description = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, duration)).build();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Boolean> result = new AtomicReference<>(false);
        main.post(() -> {
            boolean sent = dispatchGesture(description, new GestureResultCallback() {
                @Override public void onCompleted(GestureDescription gestureDescription) {
                    result.set(true); latch.countDown();
                }
                @Override public void onCancelled(GestureDescription gestureDescription) {
                    latch.countDown();
                }
            }, main);
            if (!sent) latch.countDown();
        });
        return latch.await(5, TimeUnit.SECONDS) && result.get();
    }

    @TargetApi(30)
    public byte[] screenshot() throws InterruptedException {
        if (Build.VERSION.SDK_INT < 30) return null;
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<byte[]> result = new AtomicReference<>();
        main.post(() -> takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
            @Override public void onSuccess(ScreenshotResult screenshot) {
                HardwareBuffer buffer = screenshot.getHardwareBuffer();
                Bitmap hardware = null;
                Bitmap software = null;
                try {
                    hardware = Bitmap.wrapHardwareBuffer(buffer, screenshot.getColorSpace());
                    if (hardware != null) {
                        software = hardware.copy(Bitmap.Config.ARGB_8888, false);
                        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                        software.compress(Bitmap.CompressFormat.JPEG, 85, bytes);
                        result.set(bytes.toByteArray());
                    }
                } finally {
                    if (software != null) software.recycle();
                    if (hardware != null) hardware.recycle();
                    buffer.close(); latch.countDown();
                }
            }
            @Override public void onFailure(int code) { latch.countDown(); }
        }));
        return latch.await(8, TimeUnit.SECONDS) ? result.get() : null;
    }

    private static final int UI_NODE_LIMIT = 600;

    /** Visible UI text tree of the active window. Password fields are masked. */
    public JSONArray uiTree() throws Exception {
        JSONArray out = new JSONArray();
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return out;
        java.util.ArrayDeque<AccessibilityNodeInfo> queue = new java.util.ArrayDeque<>();
        queue.add(root);
        Rect bounds = new Rect();
        while (!queue.isEmpty() && out.length() < UI_NODE_LIMIT) {
            AccessibilityNodeInfo node = queue.poll();
            if (node == null) continue;
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.add(child);
            }
            if (!node.isVisibleToUser()) continue;
            CharSequence text = node.getText();
            CharSequence desc = node.getContentDescription();
            boolean useful = text != null || desc != null || node.isClickable() || node.isEditable();
            if (!useful) continue;
            node.getBoundsInScreen(bounds);
            JSONObject item = new JSONObject()
                    .put("class", node.getClassName() == null ? "" : node.getClassName().toString())
                    .put("bounds", new JSONArray().put(bounds.left).put(bounds.top).put(bounds.right).put(bounds.bottom))
                    .put("clickable", node.isClickable()).put("editable", node.isEditable())
                    .put("focused", node.isFocused());
            if (node.getViewIdResourceName() != null) item.put("id", node.getViewIdResourceName());
            if (node.isPassword()) item.put("text", "••••").put("password", true);
            else if (text != null) item.put("text", text.toString());
            if (desc != null) item.put("desc", desc.toString());
            out.put(item);
        }
        return out;
    }

    /** Result codes for setText. */
    public static final int SET_OK = 0, SET_NO_FIELD = 1, SET_PASSWORD = 2, SET_FAILED = 3;

    /** Replace the text of the focused editable field; refuses password fields. */
    public int setText(String value) {
        AccessibilityNodeInfo node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (node == null || !node.isEditable()) return SET_NO_FIELD;
        if (node.isPassword()) return SET_PASSWORD;
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
        markOwnAction();
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) ? SET_OK : SET_FAILED;
    }
}
