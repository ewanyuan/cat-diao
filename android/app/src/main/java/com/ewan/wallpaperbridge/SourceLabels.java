package com.ewan.wallpaperbridge;

import android.content.Context;

/** Display names for capture sources. Stored values stay stable for the computer ledger. */
final class SourceLabels {
    private SourceLabels() { }

    static String display(Context context, String source) {
        if (source == null || source.isEmpty() || "分享".equals(source))
            return context.getString(R.string.source_share);
        if ("悬浮猫".equals(source)) return context.getString(R.string.source_overlay);
        if ("系统分享".equals(source)) return context.getString(R.string.source_system_share);
        if (source.startsWith("系统分享："))
            return context.getString(R.string.source_system_share_from, source.substring("系统分享：".length()));
        if ("复制链接".equals(source)) return context.getString(R.string.source_clipboard);
        if ("未知应用".equals(source)) return context.getString(R.string.source_unknown);
        return source;
    }
}
