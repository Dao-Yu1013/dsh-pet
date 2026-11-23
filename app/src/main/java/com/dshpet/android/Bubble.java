package com.dshpet.android;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 桌宠气泡：普通说话气泡 + 独立样式的实时提醒气泡。 */
public final class Bubble {
    private final Context ctx;
    private final WindowManager wm;
    private final Handler h = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private LinearLayout col;
    private TextView tag;
    private TextView body;
    private ImageView sticker;
    private Bitmap currentSticker;
    private WindowManager.LayoutParams lp;
    private Runnable hideRunnable;
    private boolean showing;
    private boolean message;
    private boolean alertStyle;
    private int placed;
    private String sourcePkg = "";
    private boolean preferSide;
    private float scale = 1f;
    private long lastPlaceAt;
    private int lastAnchorX = -9999, lastAnchorY = -9999, lastAnchorW = -1;

    /** 气泡缩放上限：桌宠超过约 180dp 后，气泡固定不再变大。 */
    private static final float MAX_SCALE = 1.2f;
    /** 快递/外卖/叫车/消息这类提醒气泡只停留 3 秒，不再常驻。 */
    private static final long ALERT_MS = 3000L;

    private Runnable tapAction;

    /** 普通气泡点一下的动作；提醒气泡仍只负责收起。 */
    public void setTapAction(Runnable r) {
        tapAction = r;
    }

    public Bubble(Context c) {
        ctx = c;
        wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
    }

    public boolean isShowing() {
        return showing && root != null;
    }

    /** 提醒气泡是否正好在桌宠头顶，用于让普通气泡避开。 */
    public boolean placedAbove() {
        return showing && placed == 0;
    }

    /** 普通说话/歌词气泡。 */
    public void show(String text, int ax, int ay, int aw, int ah, int durationMs) {
        show(text, ax, ay, aw, ah, durationMs, false);
    }

    /** side=true 时优先飘在身体两侧，用于让开常驻的提醒气泡。 */
    public void show(String text, int ax, int ay, int aw, int ah, int durationMs, boolean side) {
        if (text == null || wm == null) return;
        String name = Sticker.usableNameIn(text, ctx);
        currentSticker = name.isEmpty() ? null : Sticker.bitmap(ctx, name);
        String visibleText = Sticker.strip(text);
        if ((visibleText == null || visibleText.trim().isEmpty()) && currentSticker == null) return;
        cancelHide();
        message = false;
        sourcePkg = "";
        prepare(false, 0, "", visibleText, ax, ay, aw, ah, side);
        attach(false);
        if (durationMs > 0) scheduleHide(durationMs);
    }

    /** 实时提醒气泡：配色随类型变化、点一下收起、只留 3 秒后自动消失。 */
    public void showAlert(String type, String tagText, String text, String pkg,
                          int ax, int ay, int aw, int ah, boolean side) {
        if (text == null || text.trim().isEmpty() || wm == null) return;
        cancelHide();
        currentSticker = null;
        message = "msg".equals(type);
        sourcePkg = pkg == null ? "" : pkg;
        prepare(true, accentFor(type, sourcePkg), tagText, text, ax, ay, aw, ah, side);
        attach(true);
        scheduleHide(ALERT_MS);
    }

    /** 打开对应的聊天应用后，消息类气泡自动消失。 */
    public void dismissForApp(String pkg) {
        if (!isShowing() || !message || pkg == null || pkg.isEmpty()) return;
        if (pkg.equals(sourcePkg)) hideNow();
    }

    /** 桌宠移动时让常驻提醒跟着走，避免气泡漂在原地。 */
    public void reposition(int ax, int ay, int aw, int ah) {
        if (!isShowing() || root == null || lp == null) return;
        long now = SystemClock.uptimeMillis();
        final float d = ctx.getResources().getDisplayMetrics().density;
        // Keep visible speech bubbles attached during direct finger-drag without
        // issuing a WindowManager update for every tiny rendering tick.
        if (now - lastPlaceAt < 32L) return;
        if (Math.abs(ax - lastAnchorX) < dp(3, d) && Math.abs(ay - lastAnchorY) < dp(3, d) && aw == lastAnchorW) return;
        lastPlaceAt = now;
        lastAnchorX = ax;
        lastAnchorY = ay;
        lastAnchorW = aw;
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        place(dm, root.getMeasuredWidth(), root.getMeasuredHeight(), ax, ay, aw, ah, preferSide);
        try {
            wm.updateViewLayout(root, lp);
        } catch (Throwable ignored) {
        }
    }

    public void hideNow() {
        cancelHide();
        removeView();
    }

    private void prepare(boolean alert, int accent, String tagText, String text,
                         int ax, int ay, int aw, int ah, boolean side) {
        final float d = ctx.getResources().getDisplayMetrics().density;
        // 气泡随桌宠大小缩放，但设上限：桌宠再大，气泡也不跟着继续变大
        scale = Math.max(0.65f, Math.min(MAX_SCALE, aw / (150f * d)));
        preferSide = side;
        alertStyle = alert;
        ensure(d);
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        int maxW = Math.min((int) (270 * d * scale), Math.max(dp(80, d), dm.widthPixels - dp(12, d)));
        int padH = dp(15 * scale, d);
        int padV = dp(9 * scale, d);
        col.setPadding(padH, padV, padH, padV);
        body.setTextSize(13f * scale);
        body.setTextColor(alert ? 0xFFF8FAFF : 0xFFEEF3FF);
        body.setMaxWidth(Math.max(dp(60, d), maxW - padH * 2 - dp(2, d)));
        int safeHeight = Math.max(dp(80, d), dm.heightPixels - dp(36, d));
        int lineHeight = Math.max(dp(18, d), (int) (body.getTextSize() * 1.55f));
        body.setMaxLines(Math.max(3, (safeHeight - padV * 2) / lineHeight));
        body.setEllipsize(android.text.TextUtils.TruncateAt.END);
        body.setText(text);
        body.setVisibility(text == null || text.trim().isEmpty() ? View.GONE : View.VISIBLE);
        if (currentSticker != null) {
            sticker.setImageBitmap(currentSticker);
            sticker.setVisibility(View.VISIBLE);
        } else {
            sticker.setImageDrawable(null);
            sticker.setVisibility(View.GONE);
        }
        tag.setTextSize(10.5f * scale);
        tag.setTextColor(accent == 0 ? 0xFF9FC2FF : accent);
        if (alert && tagText != null && !tagText.isEmpty()) {
            tag.setText(tagText);
            tag.setVisibility(View.VISIBLE);
        } else {
            tag.setText("");
            tag.setVisibility(View.GONE);
        }
        BubbleBg bg = new BubbleBg(
                alert ? argb(0xF4, mix(0xFF0D131D, accent, 0.30f)) : 0xFA18202E,
                alert ? argb(0xF4, mix(0xFF0D131D, accent, 0.10f)) : 0xFA0B101A,
                alert ? argb(0x88, accent) : 0x33FFFFFF,
                accent,
                alert,
                dp((alert ? 18 : 17) * scale, d),
                dp(alert ? 1.2f : 1f, d),
                dp(3.4f, d),
                dp(1, d),
                dp(9 * scale, d));
        root.setBackground(bg);
        root.measure(View.MeasureSpec.makeMeasureSpec(maxW, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        lastPlaceAt = SystemClock.uptimeMillis();
        lastAnchorX = ax;
        lastAnchorY = ay;
        lastAnchorW = aw;
        place(dm, root.getMeasuredWidth(), root.getMeasuredHeight(), ax, ay, aw, ah, side);
    }

    private void ensure(float d) {
        if (root != null) return;
        root = new FrameLayout(ctx);
        col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        tag = new TextView(ctx);
        tag.setLetterSpacing(0.04f);
        tag.setIncludeFontPadding(false);
        sticker = new ImageView(ctx);
        sticker.setScaleType(ImageView.ScaleType.FIT_CENTER);
        sticker.setAdjustViewBounds(true);
        sticker.setMaxWidth(dp(104, d));
        sticker.setMaxHeight(dp(104, d));
        android.graphics.drawable.GradientDrawable stickerBg = new android.graphics.drawable.GradientDrawable();
        stickerBg.setColor(0x33101828);
        stickerBg.setCornerRadius(dp(10, d));
        sticker.setBackground(stickerBg);
        sticker.setPadding(dp(3, d), dp(3, d), dp(3, d), dp(3, d));
        LinearLayout.LayoutParams stickerLp = new LinearLayout.LayoutParams(dp(92, d), dp(92, d));
        stickerLp.bottomMargin = dp(4, d);
        sticker.setVisibility(View.GONE);
        col.addView(sticker, stickerLp);
        body = new TextView(ctx);
        body.setLineSpacing(dp(3, d), 1f);
        body.setIncludeFontPadding(false);
        col.addView(tag, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        bodyLp.topMargin = dp(3, d);
        col.addView(body, bodyLp);
        root.addView(col, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT));

        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                2038,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        root.setOnClickListener(v -> {
            hideNow();
            if (!alertStyle && tapAction != null) tapAction.run();
        });
    }

    private void place(DisplayMetrics dm, int w, int hgt, int ax, int ay, int aw, int ah, boolean side) {
        float d = dm.density;
        int gap = (int) (dp(10, d) * scale);
        int bodyL = ax + (int) (aw * 0.331);
        int bodyR = ax + (int) (aw * 0.669);
        int rightSpace = dm.widthPixels - bodyR - gap - dp(6, d);
        int leftSpace = bodyL - gap - dp(6, d);
        boolean fitsR = rightSpace >= w;
        boolean fitsL = leftSpace >= w;
        boolean canAbove = ay - hgt - gap >= dp(24, d);
        boolean canBelow = ay + ah + gap + hgt <= dm.heightPixels - dp(6, d);
        int x = ax + aw / 2 - w / 2;
        int y = ay - hgt - gap;
        placed = 0;
        if (alertStyle) {
            // 提醒气泡优先贴身体两侧，绝不平铺在桌宠身上
            if (fitsR || fitsL) {
                if (fitsR && (!fitsL || rightSpace >= leftSpace)) {
                    x = bodyR + gap;
                    placed = 1;
                } else {
                    x = bodyL - gap - w;
                    placed = -1;
                }
                y = ay + ah / 2 - hgt / 2;
            } else if (canBelow) {
                y = ay + ah + gap;
                placed = 2;
            }
        } else if (side && (fitsR || fitsL)) {
            if (fitsR && (!fitsL || rightSpace >= leftSpace)) {
                x = bodyR + gap;
                placed = 1;
            } else {
                x = bodyL - gap - w;
                placed = -1;
            }
            y = ay + ah / 2 - hgt / 2;
        } else if (!canAbove) {
            if (fitsR || fitsL) {
                if (fitsR && (!fitsL || rightSpace >= leftSpace)) {
                    x = bodyR + gap;
                    placed = 1;
                } else {
                    x = bodyL - gap - w;
                    placed = -1;
                }
                y = ay + ah / 2 - hgt / 2;
            } else if (canBelow) {
                y = ay + ah + gap;
                placed = 2;
            }
        }
        lp.x = Math.max(dp(6, d), Math.min(dm.widthPixels - w - dp(6, d), x));
        lp.y = Math.max(dp(24, d), Math.min(dm.heightPixels - hgt - dp(6, d), y));
        lp.width = WindowManager.LayoutParams.WRAP_CONTENT;
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
    }

    private void attach(boolean alert) {
        if (root == null || lp == null) return;
        if (alert) lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        else lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        root.setClickable(alert);
        try {
            if (root.getParent() == null) wm.addView(root, lp);
            else {
                root.animate().cancel();
                wm.updateViewLayout(root, lp);
            }
            root.animate().cancel();
            root.setAlpha(0f);
            root.setScaleX(0.93f);
            root.setScaleY(0.93f);
            root.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(170).start();
        } catch (Throwable ignored) {
        }
        showing = true;
    }

    private void scheduleHide(long ms) {
        cancelHide();
        hideRunnable = this::fadeOut;
        h.postDelayed(hideRunnable, Math.max(1500, ms));
    }

    private void fadeOut() {
        hideRunnable = null;
        if (root == null) return;
        try {
            root.animate().alpha(0f).scaleX(0.96f).scaleY(0.96f).setDuration(220)
                    .withEndAction(this::removeView).start();
        } catch (Throwable t) {
            removeView();
        }
    }

    private void cancelHide() {
        if (hideRunnable != null) {
            h.removeCallbacks(hideRunnable);
            hideRunnable = null;
        }
    }

    private void removeView() {
        showing = false;
        if (root != null) {
            try {
                root.animate().cancel();
                if (root.getParent() != null) wm.removeView(root);
            } catch (Throwable ignored) {
            }
        }
        root = null;
        col = null;
        tag = null;
        sticker = null;
        body = null;
        currentSticker = null;
        lp = null;
        lastAnchorX = -9999;
        lastAnchorY = -9999;
        lastAnchorW = -1;
    }

    private static final class BubbleBg extends Drawable {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private final int topColor, bottomColor, strokeColor, accent;
        private final boolean showBar;
        private final float radius, stroke, barW, barLeft, barInset;

        BubbleBg(int topColor, int bottomColor, int strokeColor, int accent, boolean showBar,
                 float radius, float stroke, float barW, float barLeft, float barInset) {
            this.topColor = topColor;
            this.bottomColor = bottomColor;
            this.strokeColor = strokeColor;
            this.accent = accent;
            this.showBar = showBar;
            this.radius = radius;
            this.stroke = stroke;
            this.barW = barW;
            this.barLeft = barLeft;
            this.barInset = barInset;
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            if (b.isEmpty()) return;
            float inset = stroke / 2f;
            fill.setStyle(Paint.Style.FILL);
            fill.setShader(new LinearGradient(0, b.top, 0, b.bottom, topColor, bottomColor, Shader.TileMode.CLAMP));
            box.set(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset);
            canvas.drawRoundRect(box, radius, radius, fill);
            fill.setShader(null);
            if (showBar && accent != 0) {
                float l = b.left + barLeft;
                float t = b.top + barInset;
                float r = l + barW;
                float bt = b.bottom - barInset;
                if (bt > t && r > l) {
                    fill.setColor(accent);
                    box.set(l, t, r, bt);
                    canvas.drawRoundRect(box, barW / 2f, barW / 2f, fill);
                }
            }
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(stroke);
            line.setColor(strokeColor);
            box.set(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset);
            canvas.drawRoundRect(box, radius, radius, line);
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    private static int accentFor(String type, String pkg) {
        if ("parcel".equals(type)) return 0xFFFFB84D;
        if ("food".equals(type)) return 0xFFFF8A4C;
        if ("travel".equals(type)) return 0xFFB48CFF;
        if ("weather".equals(type)) return 0xFF7CD8FF;
        if (pkg != null) {
            if (pkg.equals("com.tencent.mm")) return 0xFF5BD08A;
            if (pkg.startsWith("com.tencent.mobileqq") || pkg.startsWith("com.tencent.tim")) return 0xFF5FA8FF;
            if (pkg.contains("mms") || pkg.contains("messaging")) return 0xFF6FD3FF;
        }
        return 0xFF6FA8FF;
    }

    private static int mix(int base, int accent, float f) {
        int r = (int) (Color.red(base) + (Color.red(accent) - Color.red(base)) * f);
        int g = (int) (Color.green(base) + (Color.green(accent) - Color.green(base)) * f);
        int b = (int) (Color.blue(base) + (Color.blue(accent) - Color.blue(base)) * f);
        return Color.rgb(r, g, b);
    }

    private static int argb(int alpha, int rgb) {
        return (alpha << 24) | (rgb & 0xFFFFFF);
    }

    private static int dp(float v, float d) {
        return (int) (v * d + 0.5f);
    }
}
