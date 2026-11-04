package com.ewan.wallpaperbridge;

/** Pure geometry for the floating cat so it can be unit tested. */
final class OverlayMath {
    static final int CLOSE_BOTTOM_DP = 48;
    private OverlayMath() { }

    /** Snap to the nearest side edge. */
    static int snapX(int x, int size, int screenWidth) {
        return x + size / 2 < screenWidth / 2 ? 0 : Math.max(0, screenWidth - size);
    }

    /** Hide about 40% of the cat past its edge so it covers little content. */
    static int tuckedX(int x, int size, int screenWidth) {
        int hidden = size * 2 / 5;
        return snapX(x, size, screenWidth) == 0 ? -hidden : screenWidth - size + hidden;
    }

    /** True when a finger is over the close target at the bottom centre. */
    static boolean inCloseZone(float rawX, float rawY, int screenWidth, int screenHeight, float density) {
        float halfWidth = 110 * density;
        float top = screenHeight - (CLOSE_BOTTOM_DP + 48 + 40) * density;
        return rawY >= top && Math.abs(rawX - screenWidth / 2f) <= halfWidth;
    }
}
