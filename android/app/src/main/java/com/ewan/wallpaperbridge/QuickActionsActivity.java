package com.ewan.wallpaperbridge;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class QuickActionsActivity extends Activity {
    private static final int FILE_PICKER = 31;
    private static final int CREAM = Color.rgb(250, 247, 241);
    private static final int INK = Color.rgb(61, 52, 47);
    private static final int MUTED = Color.rgb(108, 98, 91);
    private static final int CORAL = Color.rgb(185, 80, 56);
    private static final int BORDER = Color.rgb(232, 221, 210);

    private String copiedText = "";
    private TextView preview;
    private Button collect;
    private Button sendText;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setFinishOnTouchOutside(true);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(22), dp(20), dp(22), dp(18));
        body.setBackgroundColor(CREAM);

        TextView title = label("猫叼", 20, INK, true);
        body.addView(title);

        TextView caption = label("刚复制的内容", 13, MUTED, false);
        caption.setPadding(0, dp(13), 0, 0);
        body.addView(caption);
        preview = label("正在读取剪贴板…", 14, INK, false);
        preview.setMaxLines(3);
        preview.setEllipsize(TextUtils.TruncateAt.END);
        preview.setPadding(dp(12), dp(11), dp(12), dp(11));
        preview.setBackground(round(Color.WHITE, BORDER, 12));
        LinearLayout.LayoutParams previewLayout = new LinearLayout.LayoutParams(-1, -2);
        previewLayout.topMargin = dp(7);
        body.addView(preview, previewLayout);

        collect = button("收藏复制内容", true);
        addAction(body, collect, 15);
        collect.setEnabled(false);
        collect.setOnClickListener(view -> collectCopiedText());
        sendText = button("复制文字发到小窝", false);
        addAction(body, sendText, 9);
        sendText.setEnabled(false);
        sendText.setOnClickListener(view -> MainActivity.sendClipboardToComputer(this));

        Button sendFile = button("发送文件到小窝", false);
        addAction(body, sendFile, 9);
        sendFile.setOnClickListener(view -> chooseFile());

        TextView openApp = label("查看收藏  ›", 13, MUTED, false);
        openApp.setGravity(Gravity.CENTER);
        openApp.setPadding(0, dp(17), 0, 0);
        body.addView(openApp);
        openApp.setOnClickListener(view -> {
            startActivity(new Intent(this, MainActivity.class));
            finish();
        });
        setContentView(body);
    }

    @Override protected void onStart() {
        super.onStart();
        BridgeService.appActivityStarted();
    }

    @Override protected void onStop() {
        BridgeService.appActivityStopped();
        super.onStop();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus) return;
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        ClipData clip = clipboard == null ? null : clipboard.getPrimaryClip();
        CharSequence content = clip == null || clip.getItemCount() == 0 ? null :
                clip.getItemAt(0).coerceToText(this);
        copiedText = content == null ? "" : content.toString().trim();
        boolean available = !copiedText.isEmpty();
        preview.setText(available ? copiedText : "还没有复制内容。也可以从文章页面直接分享给猫叼。");
        collect.setEnabled(available);
        sendText.setEnabled(available);
        collect.setText(copiedText.contains("http://") || copiedText.contains("https://")
                ? "收藏这个链接" : "收藏这段文字");
    }

    private void collectCopiedText() {
        if (copiedText.isEmpty()) return;
        try (CaptureStore store = new CaptureStore(this)) {
            store.save("悬浮猫", copiedText);
            BridgeService.requestCaptureSync();
            Toast.makeText(this, "已叼在嘴里，连上小窝后自动送达", Toast.LENGTH_LONG).show();
            finish();
        } catch (Exception error) {
            Toast.makeText(this, "收藏失败：" + error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void chooseFile() {
        if (BridgeService.trustedName(this).isEmpty()) {
            Toast.makeText(this, "请先在猫叼连接小窝", Toast.LENGTH_LONG).show();
            return;
        }
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        pick.setType("*/*");
        startActivityForResult(pick, FILE_PICKER);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != FILE_PICKER || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(uri,
                    data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION);
            BridgeService.shareFile(this, uri);
            MainActivity.sendSelectedFileToComputer(this);
            finish();
        } catch (Exception error) {
            Toast.makeText(this, "文件授权失败：" + error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void addAction(LinearLayout body, Button action, int topMargin) {
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, dp(48));
        layout.topMargin = dp(topMargin);
        body.addView(action, layout);
    }

    private TextView label(String value, int size, int color, boolean bold) {
        TextView label = new TextView(this);
        label.setText(value);
        label.setTextSize(size);
        label.setTextColor(color);
        if (bold) label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return label;
    }

    private Button button(String value, boolean primary) {
        Button button = new Button(this);
        button.setText(value);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setTextColor(primary ? Color.WHITE : INK);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setBackground(round(primary ? CORAL : Color.WHITE, primary ? CORAL : BORDER, 14));
        button.setElevation(0);
        return button;
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
