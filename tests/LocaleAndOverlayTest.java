package com.ewan.wallpaperbridge;

/** Language choice and floating-cat close rules that do not need Android. */
public final class LocaleAndOverlayTest {
    public static void main(String[] args) {
        require(LocalePolicy.TAGS.length == 20, "twenty locales");
        require(LocalePolicy.resolve(null, "zh-CN").equals("zh-CN"), "existing simplified");
        require(LocalePolicy.resolve("", "zh-Hans-CN").equals("zh-CN"), "hans");
        require(LocalePolicy.resolve("system", "zh-TW").equals("zh-TW"), "traditional taiwan");
        require(LocalePolicy.resolve("system", "zh-HK").equals("zh-TW"), "hong kong traditional");
        require(LocalePolicy.resolve("system", "zh-Hant").equals("zh-TW"), "hant script");
        require(LocalePolicy.resolve("system", "zh").equals("zh-CN"), "bare chinese stays simplified");
        require(LocalePolicy.resolve("en", "zh-CN").equals("en"), "explicit english");
        require(LocalePolicy.resolve("zh-TW", "en-US").equals("zh-TW"), "explicit traditional");
        require(LocalePolicy.resolve("system", "ja-JP").equals("ja"), "japanese");
        require(LocalePolicy.resolve("system", "pt-BR").equals("pt-BR"), "brazil");
        require(LocalePolicy.resolve("system", "pt-PT").equals("en"), "portugal is not brazilian");
        require(LocalePolicy.resolve("system", "es-MX").equals("es"), "spanish region");
        require(LocalePolicy.resolve("system", "ar-EG").equals("ar"), "arabic");
        require(LocalePolicy.resolve("system", "sw").equals("en"), "unsupported falls back to english");
        require(LocalePolicy.resolve("made-up", "fr-FR").equals("fr"), "unknown choice follows system");
        require(LocalePolicy.nativeName("zh-CN").equals("简体中文"), "native simplified");
        require(LocalePolicy.nativeName("ar").equals("العربية"), "native arabic");
        require(LocalePolicy.nativeName("pt-BR").equals("Português (Brasil)"), "native brazilian");

        require(!OverlayPolicy.DISABLE_STOPS_BRIDGE, "close keeps the bridge");
        require(!OverlayPolicy.DISABLE_STOPS_ACCESSIBILITY, "close keeps screen control");
        require(!OverlayPolicy.PANEL_DISMISS_DISABLES_OVERLAY, "panel close is not disable");
        boolean wanted = true;
        require(OverlayPolicy.shouldShow(wanted, true, 0), "show when wanted");
        require(!OverlayPolicy.shouldShow(wanted, true, 1), "hide while a screen is open");
        require(OverlayPolicy.shouldShow(wanted, true, 0), "return after leaving the app");
        require(!OverlayPolicy.shouldShow(wanted, false, 0), "no permission means no window");
        require(OverlayPolicy.wantedAfterPanelDismiss(wanted), "dismiss keeps the cat");
        wanted = OverlayPolicy.wantedAfterDisable();
        require(!wanted, "disable clears the flag");
        require(!OverlayPolicy.shouldShowAfterRestart(wanted, true, 0), "restart does not resurrect");
        wanted = true;
        require(OverlayPolicy.shouldShowAfterRestart(wanted, true, 0), "restart keeps an enabled cat");
        require(!OverlayPolicy.shouldShowAfterRestart(wanted, true, 2), "restart while app is open stays hidden");
        wanted = OverlayPolicy.wantedAfterDisable();
        require(!OverlayPolicy.shouldShow(wanted, true, 0), "second close stays off");

        float density = 2.75f;
        int width = OverlayMath.bubbleWidthPx(density);
        int height = OverlayMath.bubbleHeightPx(density);
        require(!OverlayPolicy.coversScreen(width, height, 1080, 2340), "cat window is not full screen");
        int targetW = Math.round(OverlayMath.CLOSE_TARGET_WIDTH_DP * density);
        int targetH = Math.round(OverlayMath.CLOSE_TARGET_HEIGHT_DP * density);
        require(!OverlayPolicy.coversScreen(targetW, targetH, 1080, 2340), "drag target is not full screen");
        int left = OverlayMath.bubbleX(true, 1080, width, density);
        int right = OverlayMath.bubbleX(false, 1080, width, density);
        require(left >= 0 && left + width <= 1080, "left bubble on screen");
        require(right >= 0 && right + width <= 1080, "right bubble on screen");
        int chip = Math.round(OverlayMath.CLOSE_CHIP_DP * density);
        require(OverlayMath.closeChipLeftPx(true, density) + chip <= width, "chip inside left window");
        require(OverlayMath.closeChipLeftPx(false, density) == 0, "chip on the inner side when docked right");
        require(OverlayMath.inCloseChip(left + OverlayMath.closeChipLeftPx(true, density) + 2,
                240, left, 240 - chip / 4, true, density), "tap lands on the chip");
        require(!OverlayMath.inCloseChip(10, 10, left, 240, true, density), "chip is not the whole screen");
        System.out.println("PASS locale and overlay");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
