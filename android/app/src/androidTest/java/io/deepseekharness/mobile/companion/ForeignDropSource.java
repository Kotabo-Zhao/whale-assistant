package io.deepseekharness.mobile.companion;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Runs in the test APK's own UID. Its private file can only be read with a real drag grant. */
public class ForeignDropSource extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (getIntent().getBooleanExtra("portrait", false)) setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        String note = getIntent().getStringExtra("note");
        if (note == null || !note.matches("[a-z0-9-]{1,80}")) { finish(); return; }
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xffe8f3f7);
        TextView item = new TextView(this);
        item.setText("测试来源 · 长按投放"); item.setGravity(Gravity.CENTER); item.setTextSize(20);
        item.setBackgroundColor(0xffd0e9ee);
        int width = (int)(220 * getResources().getDisplayMetrics().density);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(width, width / 2, Gravity.START | Gravity.CENTER_VERTICAL);
        root.addView(item, lp); setContentView(root);
        if ("menu-probe".equals(note)) {
            root.setOnClickListener(v -> item.setText("测试来源 · 已收到点击"));
            item.setOnClickListener(v -> item.setText("测试来源 · 已收到点击"));
        }
        if ("clipboard".equals(getIntent().getStringExtra("mode"))) {
            item.setText("复制验收文字");
            item.setOnClickListener(v -> {
                ClipboardManager clipboard = getSystemService(ClipboardManager.class);
                if (clipboard.hasPrimaryClip()) { item.setText("已有剪贴板内容"); return; }
                clipboard.setPrimaryClip(ClipData.newPlainText("鲸鱼验收", note));
                item.setText("清除本次测试副本");
                item.setOnClickListener(button -> {
                    ClipData clip = clipboard.getPrimaryClip();
                    if (clip != null && clip.getItemCount() == 1 && note.contentEquals(clip.getItemAt(0).getText())) {
                        if (android.os.Build.VERSION.SDK_INT >= 28) clipboard.clearPrimaryClip();
                    }
                });
            });
            return;
        }
        item.setOnLongClickListener(view -> {
            try {
                File dir = new File(getCacheDir(), "drag-probe"); dir.mkdirs();
                File file = new File(dir, note + ".txt");
                try (FileOutputStream out = new FileOutputStream(file)) { out.write(("private document " + note).getBytes(StandardCharsets.UTF_8)); }
                Uri uri = new Uri.Builder().scheme("content").authority(getPackageName() + ".dragprobe").appendPath(file.getName()).build();
                ClipData data = ClipData.newPlainText("note", note);
                data.addItem(new ClipData.Item(uri));
                return item.startDragAndDrop(data, new View.DragShadowBuilder(item), null, View.DRAG_FLAG_GLOBAL | View.DRAG_FLAG_GLOBAL_URI_READ);
            } catch (Exception error) { throw new IllegalStateException(error); }
        });
    }
}
