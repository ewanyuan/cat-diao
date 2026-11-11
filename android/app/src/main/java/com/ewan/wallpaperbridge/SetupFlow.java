package com.ewan.wallpaperbridge;

/**
 * Post-pair setup steps. Screen control and system-settings write are required.
 * Overlay draws the floating cat only. It is not used for pairing, saves, or
 * screen control, so skipping it must still let setup finish.
 */
public final class SetupFlow {
    public static final int SCREEN = 1;
    public static final int OVERLAY = 2;
    public static final int WRITE = 3;
    public static final int DONE = 0;
    /** Steps that block finishing. Overlay is not one of them. */
    public static final int REQUIRED = 2;

    private SetupFlow() {}

    public static boolean finished(boolean screenControl, boolean writeSettings) {
        return screenControl && writeSettings;
    }

    public static int readyCount(boolean screenControl, boolean writeSettings) {
        return (screenControl ? 1 : 0) + (writeSettings ? 1 : 0);
    }

    /**
     * Next step. Overlay is offered once, unless it was granted or skipped.
     * Skip still reaches write settings, then done.
     */
    public static int next(boolean screenControl, boolean overlayGranted, boolean overlaySkipped,
                           boolean writeSettings) {
        if (!screenControl) return SCREEN;
        if (!overlayGranted && !overlaySkipped) return OVERLAY;
        if (!writeSettings) return WRITE;
        return DONE;
    }

    public static boolean canSkip(int step) {
        return step == OVERLAY;
    }
}
