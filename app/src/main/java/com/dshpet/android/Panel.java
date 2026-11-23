package com.dshpet.android;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

/** 长按桌宠弹出的悬浮快捷面板。 */
public final class Panel {
    public interface Act {
        void run(String id);
    }

    /** 面板里的实时调节：大小 / 不透明度。commit=true 时落盘。 */
    public interface Tune {
        void onSize(int dp, boolean commit);

        void onOpacity(int pct, boolean commit);
    }

    private final Context c;
    private final WindowManager wm;
    private final Act act;
    private final Tune tune;
    private final Handler h = new Handler(Looper.getMainLooper());
    private View root;
    private final Runnable autoHide = this::dismiss;

    public Panel(Context c, WindowManager wm, Act act, Tune tune) {
        this.c = c;
        this.wm = wm;
        this.act = act;
        this.tune = tune;
    }

    public boolean visible() {
        return root != null;
    }

    public void show(int px, int py, int pw, int ph) {
        dismiss();
        float d = c.getResources().getDisplayMetrics().density;
        int[] ss = Screen.size(c);
        int w = ss[0], hgt = ss[1];

        FrameLayout cover = new FrameLayout(c);
        cover.setBackgroundColor(0x22000000);
        cover.setOnClickListener(v -> dismiss());

        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12, d), dp(10, d), dp(12, d), dp(10, d));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(20, d));
        bg.setColor(0xF0141826);
        bg.setStroke(dp(1, d), 0x33FFFFFF);
        card.setBackground(bg);
        card.setOnClickListener(v -> {
        });

        TextView title = new TextView(c);
        title.setText("快捷操作");
        title.setTextColor(0xFF9AA7C2);
        title.setTextSize(11);
        title.setPadding(dp(6, d), 0, dp(6, d), dp(8, d));
        card.addView(title);

        LinearLayout r1 = row(d);
        add(r1, "聊天", "chat", d);
        add(r1, "余额", "balance", d);
        add(r1, "安静", "quiet", d, Policy.isQuiet(c));
        add(r1, Cfg.load(c).b("locked", false) ? "解锁" : "锁定", "lock", d);

        LinearLayout r2 = row(d);
        add(r2, "喂食", "feed", d);
        add(r2, "剪贴板", "clip", d);
        add(r2, "藏起来", "hide", d);
        add(r2, "控制台", "console", d);

        card.addView(r1);
        card.addView(r2);
        card.addView(tuneBlock(d));

        int cw = Math.min(dp(280, d), w - dp(12, d));
        // 横屏高度不够时让面板内部滚动，避免下半截被屏幕裁掉
        int cardH = hgt - dp(80, d);
        boolean scroll = cardH < dp(300, d);
        int panelH = scroll ? Math.max(dp(180, d), cardH) : 0;
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(cw,
                scroll ? panelH : ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.gravity = Gravity.TOP | Gravity.LEFT;
        int left = Math.max(dp(6, d), Math.min(w - cw - dp(6, d), px + pw / 2 - cw / 2));
        int top = py - dp(230, d);
        if (top < dp(40, d)) {
            top = scroll
                    ? Math.min(hgt - panelH - dp(8, d), py + ph + dp(8, d))
                    : Math.min(hgt - dp(270, d), py + ph + dp(8, d));
        }
        cp.leftMargin = left;
        cp.topMargin = Math.max(dp(40, d), top);
        if (scroll) {
            ScrollView sv = new ScrollView(c);
            sv.setFillViewport(false);
            sv.setVerticalScrollBarEnabled(false);
            sv.addView(card, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            cover.addView(sv, cp);
        } else {
            cover.addView(card, cp);
        }

        WindowManager.LayoutParams wp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, 2038,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                android.graphics.PixelFormat.TRANSLUCENT);
        wp.gravity = Gravity.TOP | Gravity.LEFT;
        try {
            wm.addView(cover, wp);
            root = cover;
            h.removeCallbacks(autoHide);
            h.postDelayed(autoHide, 9000);
        } catch (Throwable ignored) {
        }
    }

    public void dismiss() {
        h.removeCallbacks(autoHide);
        if (root == null) return;
        try {
            wm.removeView(root);
        } catch (Throwable ignored) {
        }
        root = null;
    }

    /** 大小 + 不透明度滑动模块，拖动即时生效，松手保存。 */
    private View tuneBlock(float d) {
        Cfg cf = Cfg.load(c);
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12, d));
        bg.setColor(0xFF161C2C);
        bg.setStroke(dp(1, d), 0x22FFFFFF);
        box.setBackground(bg);
        box.setPadding(dp(10, d), dp(8, d), dp(10, d), dp(10, d));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(2, d);
        box.setLayoutParams(blp);

        // 大小
        LinearLayout sizeHead = new LinearLayout(c);
        sizeHead.setOrientation(LinearLayout.HORIZONTAL);
        sizeHead.setGravity(Gravity.CENTER_VERTICAL);
        TextView sizeLabel = cap("大小", d);
        TextView sizeVal = val(String.valueOf(cf.sizeDp()) + " dp", d);
        sizeHead.addView(sizeLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        sizeHead.addView(sizeVal);
        box.addView(sizeHead);

        SeekBar sizeSb = slider(d, Cfg.MIN_SIZE, Cfg.MAX_SIZE, cf.sizeDp());
        sizeSb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                sizeVal.setText(p + " dp");
                if (fromUser && tune != null) tune.onSize(p, false);
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
                h.removeCallbacks(autoHide);
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                if (tune != null) tune.onSize(sb.getProgress(), true);
                restartAutoHide();
            }
        });
        box.addView(sizeSb);

        // 不透明度 50-100
        int pct = Math.max(50, Math.min(100, Math.round(cf.opacity() * 100f)));
        LinearLayout opHead = new LinearLayout(c);
        opHead.setOrientation(LinearLayout.HORIZONTAL);
        opHead.setGravity(Gravity.CENTER_VERTICAL);
        TextView opLabel = cap("不透明度", d);
        TextView opVal = val(pct + "%", d);
        opHead.addView(opLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        opHead.addView(opVal);
        box.addView(opHead);

        SeekBar opSb = slider(d, 50, 100, pct);
        opSb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                opVal.setText(p + "%");
                if (fromUser && tune != null) tune.onOpacity(p, false);
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
                h.removeCallbacks(autoHide);
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                if (tune != null) tune.onOpacity(sb.getProgress(), true);
                restartAutoHide();
            }
        });
        box.addView(opSb);
        return box;
    }

    private void restartAutoHide() {
        h.removeCallbacks(autoHide);
        h.postDelayed(autoHide, 9000);
    }

    private TextView cap(String s, float d) {
        TextView tv = new TextView(c);
        tv.setText(s);
        tv.setTextColor(0xFF9AA7C2);
        tv.setTextSize(10.5f);
        return tv;
    }

    private TextView val(String s, float d) {
        TextView tv = new TextView(c);
        tv.setText(s);
        tv.setTextColor(0xFFE6ECFA);
        tv.setTextSize(10.5f);
        return tv;
    }

    private SeekBar slider(float d, int min, int max, int value) {
        SeekBar sb = new SeekBar(c);
        sb.setMin(min);
        sb.setMax(max);
        sb.setProgress(Math.max(min, Math.min(max, value)));
        android.content.res.ColorStateList accent = android.content.res.ColorStateList.valueOf(0xFF8B6CFF);
        sb.setProgressTintList(accent);
        sb.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF2A3350));
        sb.setThumbTintList(android.content.res.ColorStateList.valueOf(0xFFE6ECFA));
        sb.setSplitTrack(false);
        sb.setPadding(dp(6, d), dp(4, d), dp(6, d), dp(4, d));
        return sb;
    }

    private LinearLayout row(float d) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(6, d);
        r.setLayoutParams(lp);
        return r;
    }

    private void add(LinearLayout row, String label, String id, float d) {
        add(row, label, id, d, false, 1f);
    }

    private void add(LinearLayout row, String label, String id, float d, boolean active) {
        add(row, label, id, d, active, 1f);
    }

    private void add(LinearLayout row, String label, final String id, float d, boolean active, float weight) {
        TextView tv = new TextView(c);
        tv.setText(label);
        tv.setTextColor(0xFFE6ECFA);
        tv.setTextSize(11.5f);
        tv.setSingleLine(true);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(3, d), dp(11, d), dp(3, d), dp(11, d));
        styleQuiet(tv, active, d);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight);
        // 相邻按钮之间留出 8dp（左右各 4dp），避免两枚按钮贴在一起
        lp.setMargins(dp(4, d), 0, dp(4, d), 0);
        tv.setLayoutParams(lp);
        tv.setClickable(true);
        final TextView self = tv;
        tv.setOnClickListener(v -> {
            if ("quiet".equals(id)) {
                boolean on = !Policy.isQuiet(c);
                Policy.setQuiet(c, on);
                styleQuiet(self, on, d);
                // 发光反馈后收起面板，避免之后拖动桌宠时按钮一直亮着
                h.removeCallbacks(autoHide);
                h.postDelayed(this::dismiss, 450);
                return;
            }
            dismiss();
            if (act != null) act.run(id);
        });
        row.addView(tv);
    }

    private void styleQuiet(TextView tv, boolean on, float d) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(13, d));
        bg.setColor(on ? 0xFF6B4CFF : 0xFF1E2538);
        bg.setStroke(dp(1, d), on ? 0xAAFFFFFF : 0x22FFFFFF);
        tv.setBackground(bg);
        tv.setTextColor(on ? 0xFFFFFFFF : 0xFFE6ECFA);
        if (on) tv.setShadowLayer(dp(9, d), 0, 0, 0xCCB9A6FF);
        else tv.setShadowLayer(0, 0, 0, 0);
    }

    private static int dp(float v, float d) {
        return (int) (v * d + 0.5f);
    }
}
