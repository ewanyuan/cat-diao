package com.ewan.wallpaperbridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

/** Notification actions: pause/resume remote control, or close the floating cat. */
public class RemotePauseReceiver extends BroadcastReceiver {
    static final String HIDE_OVERLAY = "com.ewan.wallpaperbridge.HIDE_OVERLAY";

    @Override public void onReceive(Context context, Intent intent) {
        if (HIDE_OVERLAY.equals(intent.getAction())) {
            BridgeService.setOverlay(context, false);
            Toast.makeText(context, "悬浮猫已关闭，可在猫叼「更多」里重新打开", Toast.LENGTH_LONG).show();
            return;
        }
        BridgeService.toggleRemotePause(context);
    }
}
