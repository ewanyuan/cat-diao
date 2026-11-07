package com.ewan.wallpaperbridge;

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
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Quick panel opened by the floating cat. Close hides the panel; Turn off cat disables the overlay. */
public class QuickActionsActivity extends CatDiaoActivity {
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
        int maxHeight = (int) (getResources().getDisplayMetrics().heightPixels * 0.78f);
        ScrollView scroll = new ScrollView(this) {
            @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST));
            }
        };
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(22), dp(18), dp(22), dp(16));
        body.setBackgroundColor(CREAM);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = label(getString(R.string.panel_title), 20, INK, true);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView dismiss = label(getString(R.string.panel_dismiss), 15, CORAL, true);
        dismiss.setPaddingRelative(dp(12), dp(8), 0, dp(8));
        dismiss.setOnClickListener(view -> finish());
        header.addView(dismiss);
        body.addView(header);

        TextView caption = label(getString(R.string.panel_caption), 13, MUTED, false);
        caption.setPadding(0, dp(13), 0, 0);
        body.addView(caption);
        preview = label(getString(R.string.panel_reading), 14, INK, false);
        preview.setMaxLines(3);
        preview.setEllipsize(TextUtils.TruncateAt.END);
        preview.setPadding(dp(12), dp(11), dp(12), dp(11));
        preview.setBackground(round(Color.WHITE, BORDER, 12));
        LinearLayout.LayoutParams previewLayout = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        previewLayout.topMargin = dp(7);
        body.addView(preview, previewLayout);

        collect = button(getString(R.string.panel_save), true);
        addAction(body, collect, 15);
        collect.setEnabled(false);
        collect.setOnClickListener(view -> collectCopiedText());
        sendText = button(getString(R.string.panel_send_text), false);
        addAction(body, sendText, 9);
        sendText.setEnabled(false);
        sendText.setOnClickListener(view -> MainActivity.sendClipboardToComputer(this));

        Button sendFile = button(getString(R.string.panel_send_file), false);
        addAction(body, sendFile, 9);
        sendFile.setOnClickListener(view -> chooseFile());

        LinearLayout openRow = linkRow(getString(R.string.panel_open_saves));
        openRow.setOnClickListener(view -> {
            startActivity(new Intent(this, MainActivity.class));
            finish();
        });
        body.addView(openRow);

        TextView hideCat = label(getString(R.string.panel_disable), 14, MUTED, true);
        hideCat.setGravity(Gravity.CENTER);
        hideCat.setPadding(0, dp(16), 0, dp(4));
        body.addView(hideCat);
        hideCat.setOnClickListener(view -> {
            BridgeService.setOverlay(this, false);
            Toast.makeText(this, R.string.overlay_off_toast, Toast.LENGTH_LONG).show();
            finish();
        });
        scroll.addView(body);
        setContentView(scroll);
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
        preview.setText(available ? copiedText : getString(R.string.panel_empty));
        collect.setEnabled(available);
        sendText.setEnabled(available);
        collect.setText(copiedText.contains("http://") || copiedText.contains("https://")
                ? getString(R.string.panel_save_link) : getString(R.string.panel_save_text));
    }

    private void collectCopiedText() {
        if (copiedText.isEmpty()) return;
        try (CaptureStore store = new CaptureStore(this)) {
            store.save("悬浮猫", copiedText);
            BridgeService.requestCaptureSync();
            Toast.makeText(this, R.string.panel_saved, Toast.LENGTH_LONG).show();
            finish();
        } catch (Exception error) {
            Toast.makeText(this, R.string.panel_save_failed, Toast.LENGTH_LONG).show();
        }
    }

    private void chooseFile() {
        if (BridgeService.trustedName(this).isEmpty()) {
            Toast.makeText(this, R.string.panel_need_link, Toast.LENGTH_LONG).show();
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
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            BridgeService.shareFile(this, uri);
            MainActivity.sendSelectedFileToComputer(this);
            finish();
        } catch (Exception error) {
            Toast.makeText(this, R.string.toast_file_unopenable, Toast.LENGTH_LONG).show();
        }
    }

    private LinearLayout linkRow(String text) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setPadding(0, dp(16), 0, 0);
        row.addView(label(text, 13, MUTED, false));
        TextView arrow = label(getString(R.string.chevron_forward), 13, MUTED, false);
        arrow.setPaddingRelative(dp(6), 0, 0, 0);
        row.addView(arrow);
        return row;
    }

    private void addAction(LinearLayout body, Button action, int topMargin) {
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        layout.topMargin = dp(topMargin);
        body.addView(action, layout);
    }

    private TextView label(String value, int size, int color, boolean bold) {
        TextView label = new TextView(this);
        label.setText(value);
        label.setTextSize(size);
        label.setTextColor(color);
        label.setGravity(Gravity.START);
        if (bold) label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return label;
    }

    private Button button(String value, boolean primary) {
        Button button = new Button(this);
        button.setText(value);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setSingleLine(false);
        button.setMaxLines(2);
        button.setMinHeight(dp(48));
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
