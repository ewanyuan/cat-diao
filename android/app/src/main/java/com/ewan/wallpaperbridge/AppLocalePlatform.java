package com.ewan.wallpaperbridge;

import android.annotation.TargetApi;
import android.content.Context;
import android.os.Build;
import android.os.LocaleList;

/** Android 13+ per-app locales. Loaded only when {@code SDK_INT >= 33}. */
@TargetApi(Build.VERSION_CODES.TIRAMISU)
final class AppLocalePlatform {
    private AppLocalePlatform() { }

    static void sync(Context context, String stored) {
        android.app.LocaleManager manager = context.getSystemService(android.app.LocaleManager.class);
        if (manager == null) return;
        LocaleList desired = LocalePolicy.SYSTEM.equals(stored)
                ? LocaleList.getEmptyLocaleList()
                : new LocaleList(LocalePolicy.toLocale(stored));
        LocaleList current = manager.getApplicationLocales();
        if (same(current, desired)) return;
        manager.setApplicationLocales(desired);
    }

    private static boolean same(LocaleList current, LocaleList desired) {
        if (current.size() != desired.size()) return false;
        for (int i = 0; i < current.size(); i++) {
            if (!current.get(i).equals(desired.get(i))) return false;
        }
        return true;
    }
}
