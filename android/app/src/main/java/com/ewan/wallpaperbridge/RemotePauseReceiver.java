package com.ewan.wallpaperbridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Notification action that pauses or resumes remote control without dropping saved items. */
public class RemotePauseReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        BridgeService.toggleRemotePause(context);
    }
}
