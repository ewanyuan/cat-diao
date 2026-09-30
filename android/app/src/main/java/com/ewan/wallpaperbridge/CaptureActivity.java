package com.ewan.wallpaperbridge;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class CaptureActivity extends Activity {
    static final String ACTION_CLIPBOARD = "com.ewan.wallpaperbridge.CAPTURE_CLIPBOARD";
    private static final int CREAM = Color.rgb(250, 247, 241);
    private static final int INK = Color.rgb(61, 52, 47);
    private static final int MUTED = Color.rgb(108, 98, 91);
    private static final int CORAL = Color.rgb(185, 80, 56);
    private boolean clipboardHandled;
    private EditText input;

    @Override protected void onStart() {
        super.onStart();
        BridgeService.appActivityStarted();
    }

    @Override protected void onStop() {
        BridgeService.appActivityStopped();
        super.onStop();
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (Intent.ACTION_SEND.equals(getIntent().getAction())) {
            CharSequence text = getIntent().getCharSequenceExtra(Intent.EXTRA_TEXT);
            if (text != null && text.length() > 0) {
                save(text.toString(), getReferrer() == null ? "系统分享" :
                        "系统分享：" + getReferrer().getHost());
                finish();
                return;
            }
        }
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(22), dp(20), dp(22), dp(22));
        layout.setBackgroundColor(CREAM);
        ImageView cat = new ImageView(this);
        cat.setImageResource(getResources().getIdentifier("cat_diao_launcher", "drawable", getPackageName()));
        cat.setScaleType(ImageView.ScaleType.FIT_CENTER);
        layout.addView(cat, new LinearLayout.LayoutParams(-1, dp(56)));
        TextView heading = new TextView(this);
        heading.setText("保存文字或链接");
        heading.setTextSize(21);
        heading.setTextColor(INK);
        heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        heading.setGravity(Gravity.CENTER);
        layout.addView(heading);
        TextView hint = new TextView(this);
        hint.setText("离线时先叼在嘴里，连上小窝后自动送达。");
        hint.setTextColor(MUTED);
        hint.setTextSize(13);
        hint.setGravity(Gravity.CENTER);
        layout.addView(hint);
        input = new EditText(this);
        input.setHint("文章链接或分享文字");
        input.setTextColor(INK);
        input.setHintTextColor(MUTED);
        input.setTextSize(15);
        input.setMinLines(3);
        input.setMaxLines(7);
        input.setPadding(dp(12), dp(10), dp(12), dp(10));
        input.setBackground(round(Color.WHITE, Color.rgb(232, 221, 210), 14));
        LinearLayout.LayoutParams inputLayout = new LinearLayout.LayoutParams(-1, -2);
        inputLayout.topMargin = dp(14);
        layout.addView(input, inputLayout);
        Button save = new Button(this);
        save.setText("保存到收藏");
        save.setAllCaps(false);
        save.setTextColor(Color.WHITE);
        save.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        save.setBackground(round(CORAL, CORAL, 14));
        save.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams saveLayout = new LinearLayout.LayoutParams(-1, dp(48));
        saveLayout.topMargin = dp(12);
        layout.addView(save, saveLayout);
        save.setOnClickListener(view -> {
            if (input.getText().toString().trim().isEmpty()) {
                Toast.makeText(this, "请先复制或粘贴链接", Toast.LENGTH_SHORT).show();
            } else {
                save(input.getText().toString(), "复制链接");
                finish();
            }
        });
        setContentView(layout);
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus || clipboardHandled || !ACTION_CLIPBOARD.equals(getIntent().getAction())) return;
        clipboardHandled = true;
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        ClipData clip = clipboard == null ? null : clipboard.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) return;
        CharSequence text = clip.getItemAt(0).coerceToText(this);
        String value = text == null ? "" : text.toString().trim();
        if (value.isEmpty()) return;
        if (value.contains("http://") || value.contains("https://")) {
            save(value, "复制链接");
            finish();
        } else {
            input.setText(value);
            input.setSelection(value.length());
        }
    }

    private void save(String text, String source) {
        try (CaptureStore store = new CaptureStore(this)) {
            store.save(source, text);
            try { startForegroundService(new Intent(this, BridgeService.class)); }
            catch (RuntimeException ignored) { }
            BridgeService.requestCaptureSync();
            Toast.makeText(this, "已叼在嘴里，连上小窝后自动送达", Toast.LENGTH_LONG).show();
        } catch (Exception error) {
            Toast.makeText(this, "收藏失败：" + error.getMessage(), Toast.LENGTH_LONG).show();
        }
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
