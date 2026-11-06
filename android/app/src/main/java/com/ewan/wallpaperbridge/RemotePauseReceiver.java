package com.ewan.wallpaperbridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

/** Notification actions: pause/resume remote control, or turn off the floating cat. */
public class RemotePauseReceiver extends BroadcastReceiver {
    static final String HIDE_OVERLAY = "com.ewan.wallpaperbridge.HIDE_OVERLAY";

    @Override public void onReceive(Context context, Intent intent) {
        Context localized = AppLocale.wrap(context);
        if (intent != null && HIDE_OVERLAY.equals(intent.getAction())) {
            BridgeService.setOverlay(localized, false);
            Toast.makeText(localized, R.string.overlay_off_toast, Toast.LENGTH_LONG).show();
            return;
        }
        BridgeService.toggleRemotePause(localized);
    }
}
