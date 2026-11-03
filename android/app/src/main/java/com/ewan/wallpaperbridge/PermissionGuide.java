package com.ewan.wallpaperbridge;

/** User-facing copy for permissions that cannot be granted inside the app. */
public final class PermissionGuide {
    public static final String SPLIT_INTRO =
            "收藏不用开权限。想让电脑看和点手机，再开「屏幕控制」。其余都可选。";

    public static final String RESTRICTED_SETTINGS_TITLE = "先允许受限设置";
    public static final String RESTRICTED_SETTINGS_BODY =
            "安卓 13 以上要先放行：打开猫叼的应用信息，点右上角 ⋯，选「允许受限设置」，再回来开屏幕控制。";

    public static final String BATTERY_BODY =
            "允许猫叼后台运行，电脑才能一直连上；OPPO 还要打开「允许完全后台行为」。";

    public static final String SCREEN_OFF_TITLE = "让猫叼息屏后也能连上电脑";
    public static final String SCREEN_OFF_BODY =
            "点「去允许」选允许；OPPO 还要到 应用管理→猫叼→耗电管理，打开「允许完全后台行为」和「允许自动启动」。";

    public static final String AUTOSTART_BODY =
            "猫叼会在手机开机后自动恢复连接。若系统仍拦住后台，请按上面的说明打开自启动。";

    public static final String PAUSE_REMOTE = "暂停遥控";
    public static final String RESUME_REMOTE = "恢复遥控";

    private PermissionGuide() { }

    public static boolean mentionsRestrictedSettings() {
        return RESTRICTED_SETTINGS_BODY.contains("允许受限设置")
                && RESTRICTED_SETTINGS_BODY.contains("右上角");
    }
}
