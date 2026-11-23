package com.dshpet.android;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/** 双击桌宠弹出的动作选择面板：竖列分类 → 竖列动作。 */
public final class ActionPanel {
    public interface Act {
        void run(String slug);
    }

    private final Context c;
    private final WindowManager wm;
    private final PetConf conf;
    private final Act act;
    private View root;
    private LinearLayout content;
    private TextView title;
    private TextView back;

    public ActionPanel(Context c, WindowManager wm, PetConf conf, Act act) {
        this.c = c;
        this.wm = wm;
        this.conf = conf;
        this.act = act;
    }

    public boolean visible() {
        return root != null;
    }

    public void show(int px, int py, int pw, int ph) {
        dismiss();
        float d = c.getResources().getDisplayMetrics().density;
        DisplayMetrics dm = c.getResources().getDisplayMetrics();
        int sw = dm.widthPixels, sh = dm.heightPixels;

        FrameLayout cover = new FrameLayout(c);
        cover.setBackgroundColor(0x00000000);
        cover.setOnClickListener(v -> dismiss());

        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(8, d), dp(8, d), dp(8, d), dp(8, d));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xF2161C2E, 0xF20B0F18});
        bg.setCornerRadius(dp(20, d));
        bg.setStroke(dp(1, d), 0x558B6CFF);
        card.setBackground(bg);
        card.setOnClickListener(v -> {
        });

        LinearLayout head = new LinearLayout(c);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(0, 0, 0, dp(8, d));

        back = new TextView(c);
        back.setText("‹");
        back.setTextColor(0xFFE6ECFA);
        back.setTextSize(22);
        back.setIncludeFontPadding(false);
        back.setGravity(Gravity.CENTER);
        back.setPadding(0, 0, 0, 0);
        back.setBackground(roundBg(0xFF262E42, 0x22FFFFFF, 13, d));
        back.setClickable(true);
        back.setVisibility(View.GONE);
        back.setOnClickListener(v -> showCategories(d));
        head.addView(back, new LinearLayout.LayoutParams(dp(28, d), dp(28, d)));

        title = new TextView(c);
        title.setText("选择动作");
        title.setTextColor(0xFFF0F4FF);
        title.setTextSize(12);
        title.setSingleLine(true);
        title.setPadding(dp(6, d), 0, dp(4, d), 0);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView close = new TextView(c);
        close.setText("×");
        close.setTextColor(0xFFE6ECFA);
        close.setTextSize(18);
        close.setIncludeFontPadding(false);
        close.setGravity(Gravity.CENTER);
        close.setBackground(roundBg(0xFF262E42, 0x22FFFFFF, 13, d));
        close.setClickable(true);
        close.setOnClickListener(v -> dismiss());
        head.addView(close, new LinearLayout.LayoutParams(dp(28, d), dp(28, d)));
        card.addView(head);

        ScrollView scroll = new ScrollView(c);
        scroll.setVerticalScrollBarEnabled(false);
        content = new LinearLayout(c);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        int cw = Math.min(dp(185, d), sw - dp(16, d));
        int safeTop = dp(44, d);
        int spaceAbove = py - safeTop - dp(12, d);
        int spaceBelow = sh - (py + ph) - dp(12, d);
        boolean above = spaceAbove >= spaceBelow;
        int avail = Math.max(spaceAbove, spaceBelow);
        int cardH = Math.min(dp(430, d), Math.max(dp(230, d), avail));
        if (cardH > avail) cardH = Math.max(dp(180, d), avail);

        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(cw, cardH);
        cp.gravity = Gravity.TOP | Gravity.LEFT;
        int left = Math.max(dp(6, d), Math.min(sw - cw - dp(6, d), px + pw / 2 - cw / 2));
        int top = above ? py - cardH - dp(12, d) : py + ph + dp(12, d);
        top = Math.max(safeTop, Math.min(sh - cardH - dp(8, d), top));
        cp.leftMargin = left;
        cp.topMargin = top;
        cover.addView(card, cp);

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
            showCategories(d);
        } catch (Throwable ignored) {
        }
    }

    private void showCategories(float d) {
        if (content == null) return;
        back.setVisibility(View.GONE);
        title.setText("选择动作");
        content.removeAllViews();
        for (int k = 0; k < conf.cats.size(); k++) {
            final int idx = k;
            PetConf.Cat cat = conf.cats.get(k);
            TextView row = row(cat.id, d);
            row.setOnClickListener(v -> showActions(idx, d));
            content.addView(row);
        }
        TextView reader = row("陪伴阅读", d);
        reader.setTextColor(0xFFB9A6FF);
        reader.setOnClickListener(v -> {
            dismiss();
            if (act != null) act.run("__reader");
        });
        content.addView(reader);
    }

    private void showActions(int idx, float d) {
        if (content == null || idx < 0 || idx >= conf.cats.size()) return;
        back.setVisibility(View.VISIBLE);
        PetConf.Cat cat = conf.cats.get(idx);
        title.setText(cat.id);
        content.removeAllViews();
        List<String> list = cat.actions;
        for (int k = 0; k < list.size(); k++) {
            final String slug = list.get(k);
            if (slug == null || slug.trim().isEmpty()) continue;
            TextView row = row(conf.nameOf(slug), d);
            row.setTextSize(11.5f);
            row.setOnClickListener(v -> {
                dismiss();
                if (act != null) act.run(slug);
            });
            content.addView(row);
        }
        TextView reader = row("陪伴阅读", d);
        reader.setTextColor(0xFFB9A6FF);
        reader.setOnClickListener(v -> {
            dismiss();
            if (act != null) act.run("__reader");
        });
        content.addView(reader);
    }

    private TextView row(String label, float d) {
        TextView tv = new TextView(c);
        tv.setText(label == null ? "" : label);
        tv.setTextColor(0xFFE6ECFA);
        tv.setTextSize(11.5f);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setGravity(Gravity.CENTER_VERTICAL);
        tv.setPadding(dp(8, d), 0, dp(8, d), 0);
        tv.setBackground(roundBg(0xFF1E2538, 0x22FFFFFF, 12, d));
        tv.setClickable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(38, d));
        // 纵向排列的动作按钮之间留 8dp，别贴在一起
        lp.bottomMargin = dp(8, d);
        tv.setLayoutParams(lp);
        return tv;
    }

    private GradientDrawable roundBg(int color, int stroke, float radius, float d) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(radius, d));
        g.setColor(color);
        g.setStroke(dp(1, d), stroke);
        return g;
    }

    public void dismiss() {
        if (root == null) return;
        try {
            wm.removeView(root);
        } catch (Throwable ignored) {
        }
        root = null;
        content = null;
        title = null;
        back = null;
    }

    private static int dp(float v, float d) {
        return (int) (v * d + 0.5f);
    }
}
