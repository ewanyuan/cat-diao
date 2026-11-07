package com.ewan.wallpaperbridge;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;

import java.util.Locale;

/** Applies the saved language on API 29 and newer without changing protocol formatting. */
public final class AppLocale {
    public static final String PREF = "app_language";

    private AppLocale() { }

    public static String choice(Context context) {
        try {
            String stored = context.getSharedPreferences("bridge", Context.MODE_PRIVATE)
                    .getString(PREF, LocalePolicy.SYSTEM);
            return stored == null || stored.isEmpty() ? LocalePolicy.SYSTEM : stored;
        } catch (RuntimeException error) {
            return LocalePolicy.SYSTEM;
        }
    }

    public static String systemTag() {
        Locale locale = Resources.getSystem().getConfiguration().getLocales().get(0);
        return locale == null ? "en" : locale.toLanguageTag();
    }

    public static String resolvedTag(Context context) {
        return LocalePolicy.resolve(choice(context), systemTag());
    }

    public static Locale resolvedLocale(Context context) {
        return LocalePolicy.toLocale(resolvedTag(context));
    }

    public static Context wrap(Context base) {
        try {
            Locale locale = LocalePolicy.toLocale(LocalePolicy.resolve(choice(base), systemTag()));
            Configuration config = new Configuration(base.getResources().getConfiguration());
            config.setLocale(locale);
            config.setLayoutDirection(locale);
            return base.createConfigurationContext(config);
        } catch (RuntimeException error) {
            return base;
        }
    }

    public static void setChoice(Context context, String choice) {
        String stored = LocalePolicy.supported(choice) ? choice : LocalePolicy.SYSTEM;
        context.getSharedPreferences("bridge", Context.MODE_PRIVATE).edit()
                .putString(PREF, stored).commit();
        syncPlatform(context, stored);
        BridgeService.refreshLocale();
    }

    /** Mirror the in-app choice into the platform per-app locale on Android 13+. */
    public static void syncPlatform(Context context, String stored) {
        if (Build.VERSION.SDK_INT < 33) return;
        try { AppLocalePlatform.sync(context, stored); }
        catch (RuntimeException ignored) { }
    }
}
