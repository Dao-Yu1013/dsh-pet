package com.dshpet.android;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/** 常驻剪贴板浮窗：点旁边不会关闭，只能点右上角 × 收起。 */
public final class ClipPanel {
    private final Context c;
    private final WindowManager wm;
    private final Handler h = new Handler(Looper.getMainLooper());
    private View root;
    private LinearLayout listBox;
    private ScrollView scroll;
    private TextView empty;
    private WindowManager.LayoutParams wp;
    private int screenW, screenH;
    private float dragOffX, dragOffY;
    private String lastSig = "";
    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            refreshList();
            if (root != null) h.postDelayed(this, 2000);
        }
    };

    public ClipPanel(Context c, WindowManager wm) {
        this.c = c;
        this.wm = wm;
    }

    public boolean visible() {
        return root != null;
    }

    public void show(int px, int py, int pw, int ph) {
        dismiss();
        float d = c.getResources().getDisplayMetrics().density;
        DisplayMetrics dm = c.getResources().getDisplayMetrics();
        int sw = dm.widthPixels, sh = dm.heightPixels;
        screenW = sw;
        screenH = sh;
        int width = dp(288, d);

        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14, d), dp(12, d), dp(14, d), dp(12, d));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(20, d));
        bg.setColor(0xF5141826);
        bg.setStroke(dp(1, d), 0x33FFFFFF);
        card.setBackground(bg);

        LinearLayout head = new LinearLayout(c);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(c);
        title.setText("剪贴板收藏");
        title.setTextColor(0xFFE6ECFA);
        title.setTextSize(13);
        title.setSingleLine(true);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        head.addView(title, tl);

        TextView close = new TextView(c);
        close.setText("×");
        close.setTextColor(0xFFE6ECFA);
        close.setTextSize(18);
        close.setGravity(Gravity.CENTER);
        GradientDrawable cbg = new GradientDrawable();
        cbg.setCornerRadius(dp(14, d));
        cbg.setColor(0xFF262E42);
        close.setBackground(cbg);
        close.setClickable(true);
        close.setOnClickListener(v -> dismiss());
        head.addView(close, new LinearLayout.LayoutParams(dp(28, d), dp(28, d)));
        card.addView(head);
        head.setOnTouchListener((v, e) -> {
            if (wp == null) return false;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    dragOffX = e.getRawX() - wp.x;
                    dragOffY = e.getRawY() - wp.y;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    int nx = (int) (e.getRawX() - dragOffX);
                    int ny = (int) (e.getRawY() - dragOffY);
                    nx = Math.max(0, Math.min(screenW - wp.width, nx));
                    ny = Math.max(0, Math.min(screenH - card.getHeight(), ny));
                    wp.x = nx;
                    wp.y = ny;
                    try {
                        wm.updateViewLayout(root, wp);
                    } catch (Throwable ignored) {
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    return true;
                default:
                    return false;
            }
        });

        TextView hint = new TextView(c);
        hint.setText("这里只显示本机收藏，点其他地方不会关闭");
        hint.setTextColor(0xFF6F7B95);
        hint.setTextSize(9.5f);
        hint.setPadding(0, dp(6, d), 0, dp(6, d));
        card.addView(hint);

        scroll = new ScrollView(c);
        scroll.setVerticalScrollBarEnabled(false);
        listBox = new LinearLayout(c);
        listBox.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(listBox, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(300, d)));

        empty = new TextView(c);
        empty.setText("还没有收藏。复制或分享内容后会自动出现在这里。");
        empty.setTextColor(0xFF5D6880);
        empty.setTextSize(11);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(8, d), dp(24, d), dp(8, d), dp(24, d));
        card.addView(empty);

        wp = new WindowManager.LayoutParams(width,
                ViewGroup.LayoutParams.WRAP_CONTENT, 2038,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT);
        wp.gravity = Gravity.TOP | Gravity.LEFT;
        int left = Math.max(dp(8, d), Math.min(sw - width - dp(8, d), px + pw / 2 - width / 2));
        int top = py - dp(320, d);
        if (top < dp(44, d)) top = Math.min(sh - dp(360, d), py + ph + dp(10, d));
        wp.x = left;
        wp.y = Math.max(dp(44, d), top);

        try {
            wm.addView(card, wp);
            root = card;
            lastSig = "";
            refreshList();
            h.removeCallbacks(refresh);
            h.postDelayed(refresh, 2000);
        } catch (Throwable ignored) {
        }
    }

    private void refreshList() {
        if (root == null || listBox == null) return;
        try {
            JSONArray a = Clip.list(c);
            String sig = a.toString();
            if (sig.equals(lastSig)) return;
            lastSig = sig;
            listBox.removeAllViews();
            int n = Math.min(a.length(), 30);
            if (n == 0) {
                scroll.setVisibility(View.GONE);
                empty.setVisibility(View.VISIBLE);
                return;
            }
            scroll.setVisibility(View.VISIBLE);
            empty.setVisibility(View.GONE);
            float d = c.getResources().getDisplayMetrics().density;
            for (int k = 0; k < n; k++) {
                JSONObject o = a.optJSONObject(k);
                if (o == null) continue;
                String text = o.optString("text", "");
                if (text.trim().isEmpty()) text = o.optString("path", "");
                TextView tv = new TextView(c);
                tv.setText(text);
                tv.setTextColor(0xFFDDE5F5);
                tv.setTextSize(11.5f);
                tv.setMaxLines(3);
                tv.setEllipsize(TextUtils.TruncateAt.END);
                tv.setLineSpacing(dp(2, d), 1f);
                tv.setPadding(dp(10, d), dp(9, d), dp(10, d), dp(9, d));
                GradientDrawable bg = new GradientDrawable();
                bg.setCornerRadius(dp(12, d));
                bg.setColor(0xFF1B2132);
                tv.setBackground(bg);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.bottomMargin = dp(7, d);
                listBox.addView(tv, lp);
            }
        } catch (Throwable ignored) {
        }
    }

    public void dismiss() {
        h.removeCallbacks(refresh);
        if (root == null) return;
        try {
            wm.removeView(root);
        } catch (Throwable ignored) {
        }
        root = null;
        wp = null;
        listBox = null;
        scroll = null;
        empty = null;
    }

    private static int dp(float v, float d) {
        return (int) (v * d + 0.5f);
    }
}
