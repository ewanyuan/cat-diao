package com.ewan.wallpaperbridge;

import java.nio.charset.StandardCharsets;

/** Packs serialized saves by UTF-8 bytes rather than by the number of characters. */
final class CaptureBatch {
    static final int MAX_BYTES = 600000;
    private static final String PREFIX = "{\"items\":[";
    private static final String SUFFIX = "]}";
    private final StringBuilder text = new StringBuilder(PREFIX);
    private int bytes = PREFIX.length() + SUFFIX.length();
    private int count;

    boolean add(String item) {
        int added = item.getBytes(StandardCharsets.UTF_8).length + (count == 0 ? 0 : 1);
        if (bytes + added > MAX_BYTES) return false;
        if (count > 0) text.append(',');
        text.append(item);
        bytes += added;
        count++;
        return true;
    }

    int count() { return count; }
    byte[] payload() { return (text.toString() + SUFFIX).getBytes(StandardCharsets.UTF_8); }
}
