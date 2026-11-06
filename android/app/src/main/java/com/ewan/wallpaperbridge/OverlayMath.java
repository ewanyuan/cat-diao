package com.ewan.wallpaperbridge;

/** Geometry for the floating cat. The window stays fully on screen. */
final class OverlayMath {
    static final int CAT_DP = 44;
    static final int CLOSE_CHIP_DP = 40;
    static final int CHIP_GAP_DP = 6;
    static final int EDGE_MARGIN_DP = 8;
    static final int CLOSE_TARGET_WIDTH_DP = 200;
    static final int CLOSE_TARGET_HEIGHT_DP = 56;
    static final int CLOSE_TARGET_BOTTOM_DP = 48;
    static final int CLOSE_SLOP_DP = 16;

    private OverlayMath() { }

    /** Snap a loose x to the nearest side edge. Kept for older position data. */
    static int snapX(int x, int size, int screenWidth) {
        return x + size / 2 < screenWidth / 2 ? 0 : Math.max(0, screenWidth - size);
    }

    static boolean prefersLeft(int x, int windowWidth, int screenWidth) {
        return x + windowWidth / 2 < screenWidth / 2;
    }

    /** Fully on-screen x. Nothing is placed past the edge, so the window cannot cover the screen. */
    static int bubbleX(boolean leftSide, int screenWidth, int windowWidth, float density) {
        int margin = Math.round(EDGE_MARGIN_DP * density);
        int span = Math.max(0, screenWidth - windowWidth);
        if (leftSide) return Math.min(margin, span);
        return Math.max(margin, span - margin);
    }

    static int bubbleWidthPx(float density) {
        return Math.round((CAT_DP + CHIP_GAP_DP + CLOSE_CHIP_DP) * density);
    }

    static int bubbleHeightPx(float density) {
        return Math.round(Math.max(CAT_DP, CLOSE_CHIP_DP) * density);
    }

    /** Chip sits on the inner side: right of the cat on the left edge, left of the cat on the right edge. */
    static int closeChipLeftPx(boolean leftSide, float density) {
        if (!leftSide) return 0;
        return Math.round((CAT_DP + CHIP_GAP_DP) * density);
    }

    /** Screen-space hit test for the ✕ chip. The rect is only the chip, not the screen. */
    static boolean inCloseChip(float rawX, float rawY, int windowX, int windowY,
                                boolean leftSide, float density) {
        int chip = Math.round(CLOSE_CHIP_DP * density);
        int left = windowX + closeChipLeftPx(leftSide, density);
        int top = windowY + Math.max(0, (bubbleHeightPx(density) - chip) / 2);
        return rawX >= left && rawX < left + chip && rawY >= top && rawY < top + chip;
    }

    /**
     * Finger is over the drag target drawn at the bottom centre.
     * The rectangle matches that view, plus a small slop.
     */
    static boolean inCloseZone(float rawX, float rawY, int screenWidth, int screenHeight, float density) {
        float width = CLOSE_TARGET_WIDTH_DP * density;
        float height = CLOSE_TARGET_HEIGHT_DP * density;
        float slop = CLOSE_SLOP_DP * density;
        float left = (screenWidth - width) / 2f - slop;
        float right = left + width + slop * 2;
        float bottom = screenHeight - CLOSE_TARGET_BOTTOM_DP * density + slop;
        float top = bottom - height - slop * 2;
        return rawX >= left && rawX <= right && rawY >= top && rawY <= bottom;
    }
}
