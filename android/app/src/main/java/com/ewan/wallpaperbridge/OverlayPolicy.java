package com.ewan.wallpaperbridge;

/**
 * What the floating cat and the quick panel do when the user closes them.
 *
 * <p>Turning the cat off clears only {@code overlay_wanted}. It does not stop the
 * bridge service, the computer connection, or screen-control accessibility.
 * Dismissing the quick panel (Close, Back, or a tap outside) leaves the cat on.
 * The cat is drawn again only when it is still wanted, overlay permission exists,
 * and no Cat Diao screen is in the foreground. A service restart uses the same rule
 * and must not turn the cat back on by itself.
 */
public final class OverlayPolicy {
    public static final boolean DISABLE_STOPS_BRIDGE = false;
    public static final boolean DISABLE_STOPS_ACCESSIBILITY = false;
    public static final boolean PANEL_DISMISS_DISABLES_OVERLAY = false;

    private OverlayPolicy() { }

    public static boolean shouldShow(boolean wanted, boolean canDrawOverlays, int visibleActivities) {
        return wanted && canDrawOverlays && visibleActivities <= 0;
    }

    /** Close / Back / outside tap on the quick panel. */
    public static boolean wantedAfterPanelDismiss(boolean wanted) {
        return PANEL_DISMISS_DISABLES_OVERLAY ? false : wanted;
    }

    /** ✕, long-press, drag-to-bottom, quick panel "turn off", notification, or More. */
    public static boolean wantedAfterDisable() {
        return false;
    }

    public static boolean shouldShowAfterRestart(boolean wanted, boolean canDrawOverlays, int visibleActivities) {
        return shouldShow(wanted, canDrawOverlays, visibleActivities);
    }

    /** True when a touch window would cover most of the screen and block other apps. */
    public static boolean coversScreen(int windowWidth, int windowHeight, int screenWidth, int screenHeight) {
        if (screenWidth <= 0 || screenHeight <= 0) return false;
        long area = (long) windowWidth * windowHeight;
        return area > (long) screenWidth * screenHeight / 2;
    }
}
