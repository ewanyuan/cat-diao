package com.ewan.wallpaperbridge;

/** User-facing copy for permissions that cannot be granted inside the app. */
public final class PermissionGuide {
    public static final String SPLIT_INTRO =
            "收藏不需要额外权限。发文件和剪贴板只需要网络。看画面、点画面需要屏幕控制。"
                    + "调节亮度需要「修改系统设置」。悬浮窗是可选的，不开也能收藏。";

    public static final String RESTRICTED_SETTINGS_TITLE = "先允许受限设置";
    public static final String RESTRICTED_SETTINGS_BODY =
            "从浏览器或文件管理器安装猫叼时，安卓 13 及以上会拦住屏幕控制。"
                    + "请打开猫叼的应用信息，点右上角 ⋯，选择「允许受限设置」。"
                    + "不同品牌的菜单位置可能不一样。完成后，再回来开启「猫叼屏幕控制」。";

    public static final String BATTERY_BODY =
            "请允许猫叼忽略电池优化，否则手机会在后台关掉连接。"
                    + "部分手机还要单独打开自启动和后台运行："
                    + "小米是自启动和省电策略；华为是应用启动管理；OPPO 是耗电保护；"
                    + "vivo 是后台高耗电；三星是不受限制。";

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
