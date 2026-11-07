package com.ewan.wallpaperbridge;

import android.app.Application;
import android.content.Context;

public class CatDiaoApp extends Application {
    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(AppLocale.wrap(base));
    }

    @Override public void onCreate() {
        super.onCreate();
        AppLocale.syncPlatform(this, AppLocale.choice(this));
    }
}
