package com.ewan.wallpaperbridge;

/** Probe quickly, then back off. A restored network resets the interval. */
public final class ReconnectSchedule {
    public final long initialMs;
    public final long maximumMs;
    private long currentMs;

    public ReconnectSchedule(long initialMs, long maximumMs) {
        if (initialMs < 1) throw new IllegalArgumentException("interval");
        this.initialMs = initialMs;
        this.maximumMs = Math.max(initialMs, maximumMs);
        this.currentMs = initialMs;
    }

    public synchronized long currentMs() { return currentMs; }

    public synchronized void success() { currentMs = initialMs; }

    public synchronized void failure() {
        if (currentMs > maximumMs / 2) currentMs = maximumMs;
        else currentMs = Math.min(maximumMs, currentMs * 2);
    }
}
