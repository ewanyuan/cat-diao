package com.ewan.wallpaperbridge;

/** Which phone routes stay open, which need screen control, and which a pause stops. */
public final class RoutePolicy {
    private RoutePolicy() { }

    public static boolean open(String method, String path) {
        if ("GET".equals(method) && "/hello".equals(path)) return true;
        if ("POST".equals(method) && "/pair".equals(path)) return true;
        return "GET".equals(method) && path != null && path.startsWith("/pair-status?");
    }

    public static boolean needsScreen(String method, String path) {
        return ("GET".equals(method) && "/screen".equals(path))
                || ("POST".equals(method) && "/action".equals(path));
    }

    public static boolean needsWriteSettings(String method, String path) {
        return "POST".equals(method) && "/brightness".equals(path);
    }

    /** Remote control of the phone. Saving and heartbeat keep working while this is paused. */
    public static boolean pausesWithRemote(String method, String path) {
        if (needsScreen(method, path) || needsWriteSettings(method, path)) return true;
        if (!"POST".equals(method) || path == null) return false;
        return "/clipboard".equals(path) || "/file".equals(path) || "/wallpaper/home".equals(path)
                || "/volume".equals(path) || "/open".equals(path);
    }
}
