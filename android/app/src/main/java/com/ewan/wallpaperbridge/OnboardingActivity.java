package com.ewan.wallpaperbridge;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class OnboardingActivity extends Activity {
    private static final int CREAM = Color.rgb(250, 247, 241);
    private static final int PAPER = Color.WHITE;
    private static final int INK = Color.rgb(61, 52, 47);
    private static final int MUTED = Color.rgb(108, 98, 91);
    private static final int CORAL = Color.rgb(185, 80, 56);
    private static final int BORDER = Color.rgb(232, 221, 210);
    private int page;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        page = state == null ? 0 : state.getInt("page", 0);
        getWindow().setStatusBarColor(CREAM);
        getWindow().setNavigationBarColor(CREAM);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        showPage();
    }

    @Override protected void onStart() {
        super.onStart();
        BridgeService.appActivityStarted();
    }

    @Override protected void onStop() {
        BridgeService.appActivityStopped();
        super.onStop();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("page", page);
        super.onSaveInstanceState(state);
    }

    @Override public void onBackPressed() {
        if (page > 0) {
            page--;
            showPage();
        } else {
            finishGuide();
        }
    }

    private void finishGuide() {
        getSharedPreferences("bridge", MODE_PRIVATE).edit()
                .putBoolean("onboarding_seen_v1", true).apply();
        finish();
    }

    private void showPage() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(CREAM);
        root.setPadding(dp(22), dp(18), dp(22), dp(20));
        setContentView(root);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(top);
        ImageView cat = new ImageView(this);
        cat.setImageResource(getResources().getIdentifier("cat_diao_launcher", "drawable", getPackageName()));
        cat.setScaleType(ImageView.ScaleType.FIT_CENTER);
        top.addView(cat, new LinearLayout.LayoutParams(dp(32), dp(32)));
        TextView brand = label("猫叼", 16, INK, true);
        brand.setPadding(dp(7), 0, 0, 0);
        top.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        TextView skip = label("跳过", 13, MUTED, false);
        skip.setPadding(dp(12), dp(8), 0, dp(8));
        top.addView(skip);
        skip.setOnClickListener(view -> finishGuide());

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout.LayoutParams scrollLayout = new LinearLayout.LayoutParams(-1, 0, 1);
        scrollLayout.topMargin = dp(12);
        root.addView(scroll, scrollLayout);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content);

        ImageView illustration = new ImageView(this);
        String illustrationName = page == 0 ? "cat_cozy_computer_v1" :
                page == 1 ? "cat_cozy_phone_v1" : "cat_cozy_nest_v1";
        illustration.setImageResource(getResources().getIdentifier(
                illustrationName, "drawable", getPackageName()));
        illustration.setScaleType(ImageView.ScaleType.CENTER_CROP);
        illustration.setBackground(round(PAPER, BORDER, 18));
        illustration.setClipToOutline(true);
        illustration.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams illustrationLayout = new LinearLayout.LayoutParams(-1, dp(188));
        illustrationLayout.topMargin = dp(18);
        content.addView(illustration, illustrationLayout);

        String[] titles = {"猫叼是什么", "怎么收藏", "还能做什么"};
        TextView title = label(titles[page], 22, INK, true);
        LinearLayout.LayoutParams titleLayout = new LinearLayout.LayoutParams(-1, -2);
        titleLayout.topMargin = dp(22);
        content.addView(title, titleLayout);

        LinearLayout panel = panel(content);
        if (page == 0) {
            row(panel, "猫", "手机上的这个 App。", true);
            row(panel, "叼", "收藏先存在手机，连上小窝再发送。", false);
            row(panel, "小窝", "你的电脑。", false);
        } else if (page == 1) {
            row(panel, "分享", "在文章里点「分享」，选「猫叼」。", true);
            row(panel, "复制", "复制链接或文字，点悬浮猫，再点收藏。首页也有「收藏复制内容」。", false);
            note(content, "显示「已叼在嘴里」，就是还没送到小窝。");
        } else {
            row(panel, "发到小窝", "把复制的文字发到小窝剪贴板，或选文件发送。", true);
            row(panel, "操作手机", "在电脑上的 AI 工具（如 Codex）里查看手机、操作屏幕、换壁纸，也能把文字和文件发回手机。", false);
            note(content, "第一次连接，先按「小窝连接」里的说明安装电脑端；再让电脑上的 AI 工具（如 Codex）连接猫叼，手机点「允许」。");
            note(content, "换了 Wi-Fi 后，只要电脑端「猫叼接收」仍在运行，两台设备回到可互访的同一 Wi-Fi 就会自动连接并补送收藏，不用重新配对。");
        }

        LinearLayout dots = new LinearLayout(this);
        dots.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams dotsLayout = new LinearLayout.LayoutParams(-1, dp(28));
        dotsLayout.bottomMargin = dp(12);
        root.addView(dots, dotsLayout);
        for (int index = 0; index < 3; index++) {
            View dot = new View(this);
            dot.setBackground(round(index == page ? CORAL : BORDER,
                    index == page ? CORAL : BORDER, 8));
            LinearLayout.LayoutParams dotLayout = new LinearLayout.LayoutParams(
                    dp(index == page ? 22 : 8), dp(8));
            if (index > 0) dotLayout.leftMargin = dp(7);
            dots.addView(dot, dotLayout);
        }

        LinearLayout footer = new LinearLayout(this);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(footer);
        TextView back = label("上一步", 14, MUTED, false);
        back.setGravity(Gravity.CENTER);
        back.setVisibility(page == 0 ? View.INVISIBLE : View.VISIBLE);
        footer.addView(back, new LinearLayout.LayoutParams(dp(72), dp(50)));
        back.setOnClickListener(view -> {
            page--;
            showPage();
        });
        Button next = new Button(this);
        next.setText(page == 2 ? "开始使用" : "下一步");
        next.setTextColor(Color.WHITE);
        next.setTextSize(15);
        next.setAllCaps(false);
        next.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        next.setBackground(round(CORAL, CORAL, 14));
        next.setElevation(0);
        LinearLayout.LayoutParams nextLayout = new LinearLayout.LayoutParams(0, dp(50), 1);
        nextLayout.leftMargin = dp(10);
        footer.addView(next, nextLayout);
        next.setOnClickListener(view -> {
            if (page == 2) finishGuide();
            else {
                page++;
                showPage();
            }
        });
    }

    private LinearLayout panel(LinearLayout parent) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), 0, dp(16), 0);
        panel.setBackground(round(PAPER, BORDER, 12));
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2);
        layout.topMargin = dp(16);
        parent.addView(panel, layout);
        return panel;
    }

    private void row(LinearLayout panel, String heading, String detail, boolean first) {
        if (!first) {
            View divider = new View(this);
            divider.setBackgroundColor(BORDER);
            panel.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
        }
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        body.setGravity(Gravity.TOP);
        body.setPadding(0, dp(17), 0, dp(17));
        panel.addView(body);
        body.addView(label(heading, 15, INK, true),
                new LinearLayout.LayoutParams(dp(82), -2));
        TextView text = label(detail, 14, MUTED, false);
        text.setLineSpacing(dp(3), 1);
        body.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
    }

    private void note(LinearLayout parent, String message) {
        TextView text = label(message, 13, MUTED, false);
        text.setLineSpacing(dp(3), 1);
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2);
        layout.topMargin = dp(17);
        parent.addView(text, layout);
    }

    private TextView label(String value, int size, int color, boolean bold) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        if (bold) text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return text;
    }

    private GradientDrawable round(int fill, int stroke, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(radius));
        shape.setStroke(dp(1), stroke);
        return shape;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
