package com.dshpet.android;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;

/**
 * 透明中转页：只负责调起系统图片选择器，把图片解码缩小后回传给悬浮聊天窗。
 * 不申请读取相册权限：Android 13+ 用系统照片选择器，低版本用 SAF 文档选择器。
 */
public final class PickAct extends Activity {
    /** 选中返回图片；取消时 bmp 为 null，调用方负责恢复被隐藏的窗口。 */
    public interface Cb {
        void onDone(Bitmap bmp);
    }

    private static final int REQ = 41;
    private static volatile Cb pendingCb;

    /** 从服务或悬浮窗调起。 */
    public static void open(Context c, Cb cb) {
        pendingCb = cb;
        try {
            Intent i = new Intent(c, PickAct.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            c.startActivity(i);
        } catch (Throwable t) {
            pendingCb = null;
            // 系统拦掉这次启动时给一句反馈，否则表现就是点了完全没反应
            try {
                android.widget.Toast.makeText(c, "打不开相册，稍后再试一次", android.widget.Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {
            }
            if (cb != null) cb.onDone(null);
        }
    }

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        if (pendingCb == null) {
            finish();
            return;
        }
        if (!launchPicker()) finish();
    }

    private boolean launchPicker() {
        try {
            Intent p;
            if (Build.VERSION.SDK_INT >= 33) {
                p = new Intent(android.provider.MediaStore.ACTION_PICK_IMAGES);
                p.setType("image/*");
            } else {
                p = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                p.addCategory(Intent.CATEGORY_OPENABLE);
                p.setType("image/*");
            }
            startActivityForResult(p, REQ);
            return true;
        } catch (Throwable ignored) {
        }
        try {
            Intent g = new Intent(Intent.ACTION_GET_CONTENT);
            g.setType("image/*");
            g.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(g, REQ);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ) return;
        Uri uri = data == null ? null : data.getData();
        final Cb cb = pendingCb;
        pendingCb = null;
        if (res != RESULT_OK || uri == null) {
            if (cb != null) cb.onDone(null);
            finish();
            return;
        }
        new Thread(() -> {
            final Bitmap bmp = decode(uri);
            runOnUiThread(() -> {
                if (cb != null) cb.onDone(bmp);
                finish();
            });
        }, "pick-decode").start();
    }

    /** 先按边界采样，再把最长边压到 max，避免大图把界面拖卡。 */
    private Bitmap decode(Uri uri) {
        return decode(this, uri, 720);
    }

    public static Bitmap decode(Context c, Uri uri, int max) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (java.io.InputStream in = c.getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, o);
            }
            int w = o.outWidth, h = o.outHeight;
            if (w <= 0 || h <= 0) return null;
            int sample = 1;
            while (w / (sample * 2) >= 1024 && h / (sample * 2) >= 1024) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            Bitmap bmp;
            try (java.io.InputStream in = c.getContentResolver().openInputStream(uri)) {
                bmp = BitmapFactory.decodeStream(in, null, o2);
            }
            if (bmp == null) return null;
            int mw = bmp.getWidth(), mh = bmp.getHeight();
            if (mw <= max && mh <= max) return bmp;
            float sc = Math.min(max / (float) mw, max / (float) mh);
            Bitmap out = Bitmap.createScaledBitmap(bmp, Math.max(1, (int) (mw * sc)),
                    Math.max(1, (int) (mh * sc)), true);
            if (out != bmp) bmp.recycle();
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    protected void onDestroy() {
        if (isFinishing()) pendingCb = null;
        super.onDestroy();
    }
}
