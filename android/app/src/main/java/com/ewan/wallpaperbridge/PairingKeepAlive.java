package com.ewan.wallpaperbridge;

/**
 * Keeps port 8767 reachable while the user is waiting for the computer or
 * checking the 6-digit code. A hint is not enough: OPPO can pause the
 * listener when the screen dims or the app is briefly left.
 */
public final class PairingKeepAlive {
    /** Preference key. Value is an elapsedRealtime deadline, not a token. */
    public static final String PREF_AWAKE_UNTIL = "pairing_awake_until";
    /** Refreshed while the waiting or confirm screen is showing. */
    public static final long HOLD_MS = 180_000L;
    /** After 允许, stay awake long enough for the computer to observe approval. */
    public static final long PAIRED_GRACE_MS = 45_000L;

    private PairingKeepAlive() {}

    public static boolean holdScreen(FirstConnection.State state) {
        return state == FirstConnection.State.WAITING || state == FirstConnection.State.PAIRING;
    }

    public static boolean pairedGrace(FirstConnection.State state) {
        return state == FirstConnection.State.PAIRED;
    }

    public static boolean wakeActive(long untilElapsed, long nowElapsed) {
        return untilElapsed > nowElapsed;
    }

    /**
     * Bring the listener back if the ROM kills the process during first pairing.
     * A saved token already restarts. A pending confirm, or a screen-hold that
     * has not expired, must too: the token is not stored until the user taps 允许.
     */
    public static boolean restartListener(boolean hasComputerToken, boolean wakeActive, boolean pairPending) {
        return hasComputerToken || wakeActive || pairPending;
    }
}
