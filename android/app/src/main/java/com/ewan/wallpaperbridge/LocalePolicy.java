package com.ewan.wallpaperbridge;

import java.util.Locale;

/**
 * Picks the app language. Unsupported system languages fall back to English.
 * Chinese script and region choose Simplified or Traditional; other regions of a
 * supported language use that language (es-MX → es). Portugal Portuguese is not
 * Brazilian Portuguese and falls back to English.
 */
public final class LocalePolicy {
    public static final String SYSTEM = "system";

    /** Selector order. Tags are BCP 47 and match Android resource qualifiers. */
    public static final String[] TAGS = {
            "zh-CN", "zh-TW", "en", "ja", "ko", "es", "fr", "de", "pt-BR", "ru",
            "ar", "hi", "id", "vi", "th", "it", "tr", "nl", "pl", "uk"
    };

    private static final String[] NATIVE_NAMES = {
            "简体中文", "繁體中文", "English", "日本語", "한국어", "Español", "Français",
            "Deutsch", "Português (Brasil)", "Русский", "العربية", "हिन्दी",
            "Bahasa Indonesia", "Tiếng Việt", "ไทย", "Italiano", "Türkçe",
            "Nederlands", "Polski", "Українська"
    };

    private LocalePolicy() { }

    public static String nativeName(String tag) {
        for (int i = 0; i < TAGS.length; i++) if (TAGS[i].equals(tag)) return NATIVE_NAMES[i];
        return tag;
    }

    public static boolean supported(String tag) {
        if (tag == null) return false;
        for (String known : TAGS) if (known.equals(tag)) return true;
        return false;
    }

    public static Locale toLocale(String tag) {
        if ("zh-CN".equals(tag)) return Locale.SIMPLIFIED_CHINESE;
        if ("zh-TW".equals(tag)) return Locale.TRADITIONAL_CHINESE;
        if ("pt-BR".equals(tag)) return new Locale("pt", "BR");
        if ("en".equals(tag)) return Locale.ENGLISH;
        int dash = tag.indexOf('-');
        if (dash < 0) return new Locale(tag);
        return new Locale(tag.substring(0, dash), tag.substring(dash + 1));
    }

    /**
     * @param preference stored choice, or null/"system"/unknown to follow the system
     * @param systemLanguageTag the device locale, not an already-wrapped app locale
     * @return one of {@link #TAGS}
     */
    public static String resolve(String preference, String systemLanguageTag) {
        String choice = canonicalPreference(preference);
        if (choice != null) return choice;
        return fromSystem(systemLanguageTag);
    }

    private static String canonicalPreference(String preference) {
        if (preference == null) return null;
        String trimmed = preference.trim();
        if (trimmed.isEmpty() || SYSTEM.equalsIgnoreCase(trimmed)) return null;
        String tag = trimmed.replace('_', '-');
        for (String known : TAGS) if (known.equalsIgnoreCase(tag)) return known;
        return null;
    }

    static String fromSystem(String systemLanguageTag) {
        String raw = systemLanguageTag == null ? "" : systemLanguageTag.trim().replace('_', '-');
        if (raw.isEmpty()) return "en";
        Locale locale = Locale.forLanguageTag(raw);
        String language = locale.getLanguage().toLowerCase(Locale.ROOT);
        String country = locale.getCountry().toUpperCase(Locale.ROOT);
        String script = locale.getScript().toLowerCase(Locale.ROOT);
        if ("zh".equals(language)) {
            if ("hant".equals(script) || "TW".equals(country) || "HK".equals(country) || "MO".equals(country))
                return "zh-TW";
            return "zh-CN";
        }
        if ("pt".equals(language)) return "BR".equals(country) ? "pt-BR" : "en";
        for (String known : TAGS) {
            if (known.equals(language)) return known;
        }
        return "en";
    }
}
