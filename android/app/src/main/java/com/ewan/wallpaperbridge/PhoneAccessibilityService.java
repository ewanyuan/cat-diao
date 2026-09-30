package com.ewan.wallpaperbridge;

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
    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
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
}
