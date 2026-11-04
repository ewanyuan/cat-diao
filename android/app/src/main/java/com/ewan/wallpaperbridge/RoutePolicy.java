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
        return ("GET".equals(method) && ("/screen".equals(path) || "/ui".equals(path)))
                || ("POST".equals(method) && "/action".equals(path));
    }

    public static boolean needsWriteSettings(String method, String path) {
        return "POST".equals(method) && "/brightness".equals(path);
    }

    /** Only web links may be opened remotely. */
    public static boolean allowedUrl(String url) {
        if (url == null || url.length() > 4096) return false;
        String lower = url.toLowerCase(java.util.Locale.ROOT);
        return (lower.startsWith("https://") || lower.startsWith("http://")) && url.length() > 8
                && url.indexOf(' ') < 0 && url.indexOf('\n') < 0;
    }

    public static boolean validPackage(String name) {
        return name != null && name.matches("[a-zA-Z][a-zA-Z0-9_.]{1,199}");
    }

    /** Remote control of the phone. Saving and heartbeat keep working while this is paused. */
    public static boolean pausesWithRemote(String method, String path) {
        if (needsScreen(method, path) || needsWriteSettings(method, path)) return true;
        if (!"POST".equals(method) || path == null) return false;
        return "/clipboard".equals(path) || "/file".equals(path) || "/wallpaper/home".equals(path)
                || "/volume".equals(path) || "/open".equals(path)
                || "/open-url".equals(path) || "/install".equals(path);
    }
}
