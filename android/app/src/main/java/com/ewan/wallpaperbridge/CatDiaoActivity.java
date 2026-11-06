package com.ewan.wallpaperbridge;

import android.app.Activity;
import android.content.Context;
import android.text.TextUtils;
import android.view.View;

/** Activities share the saved language and RTL direction. */
public abstract class CatDiaoActivity extends Activity {
    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(AppLocale.wrap(base));
    }

    @Override public void setContentView(View view) {
        int direction = TextUtils.getLayoutDirectionFromLocale(AppLocale.resolvedLocale(this));
        view.setLayoutDirection(direction);
        super.setContentView(view);
        getWindow().getDecorView().setLayoutDirection(direction);
    }
}
