package com.dshpet.android;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ImageDecoder;
import android.graphics.Paint;
import android.graphics.Region;
import android.graphics.RectF;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 桌宠状态机 + 动画 + 物理。 */
public final class Pet {
    public interface Host {
        void onWindow(int x, int y, int w, int h);

        void onAction(String slug, String name);

        void onPosition(double x, double y, int facing);

        void onLongPress();

        void onTap();

        void onDoubleTap();

        /** 拖动结束（只拖不甩）。 */
        void onDragEnd();

        /** 立刻催一帧：空闲时帧循环是 500ms 一次，拖动开始时不等下一帧。 */
        void wake();
    }

    private enum Mode {IDLE, DRAG, MOVE, RUN}

    private static final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Context ctx;
    private final PetConf conf;
    private final PetConf.Spec spec;
    private final Host host;
    private final float density;
    private final PetView view;
    private final Random rnd = new Random();

    private AnimatedImageDrawable current;
    private String currentSlug;
    private int loadToken;
    private Mode mode = Mode.IDLE;
    private int facing = 1;
    private boolean forcedNoMirror;
    private int sizePx, heightPx;
    /** 悬浮窗相对「可视框」左右被裁掉的透明边；窗口只覆盖真正能看见的像素 */
    private int padL, padR, winW;
    private double skinCL = 0.03, skinCR = 0.97;
    private double x, y;
    private boolean dragging, pressed, longPressFired, locked, alive = true;
    private boolean peek;
    private int peekSide = 1;
    private double runFromX, runToX, runBaseY;
    private long runStart;
    private long runDuration = 1200;
    private Bitmap peekBmp;
    private int idleHeightPx = 36;
    /** Stable alpha silhouette from standing/idle art; action frames never enlarge the touch area. */
    private static final int TOUCH_MASK_W = 128, TOUCH_MASK_H = 192;
    private byte[] bodyTouchMask;
    private final Paint peekPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF peekDst = new RectF();
    private double downRawX, downRawY, downAt;
    private long lastTapAt;
    private double dragTargetX, dragTargetY, dragVX, dragVY, grabDX, grabDY;
    private double moveFromX, moveToX;
    private long moveStart, moveDuration = 6000, lastDragAnimAt;
    private double squashAmount = 0.55;
    private long squashAt;
    private boolean squashActive;

    /* ---------------- 立绘皮肤 ---------------- */
    /** 立绘模式：自定义静态立绘 / 序列帧动图；false 时沿用原版 106 逐帧动画。 */
    private boolean skinMode;
    private SkinStore.SkinDef skinDef;
    /** 0 = 站姿，1..6 依次对应 happy/sad/angry/surprised/shy/confused。 */
    private final Bitmap[] skinFrames = new Bitmap[7];
    /** 序列帧皮肤的所有帧（统一画布），按 fps 循环播放。 */
    private Bitmap[] animFrames = new Bitmap[0];
    private int animFps = 12;
    private long animStart;
    private String animLoadedId = "";
    /** Live2D 模型：离屏渲染线程，frame() 拿到最新一帧。 */
    private Live2DRenderer live2d;
    private String live2dId = "";
    private int skinFace;
    private long skinFaceUntil;
    private final Paint skinPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF skinDst = new RectF();
    private int skinDecodeW;
    private float skinHeightRatio = 9f / 16f;
    private int skinAction;
    private long skinActionAt;
    private boolean paused;

    /* ---------------- 聊天框状态 ---------------- */
    private boolean chatOpen;

    private final Runnable longRunnable = new Runnable() {
        @Override
        public void run() {
            if (!pressed || dragging) return;
            longPressFired = true;
            host.onLongPress();
        }
    };

    private final Runnable peekAnim = new Runnable() {
        @Override
        public void run() {
            if (!alive || !peek) return;
            view.invalidate();
            view.postDelayed(this, 33);
        }
    };

    /** 立绘 30fps 自绘循环：漂浮、呼吸、走路摆动都靠它。 */
    private final Runnable skinAnim = new Runnable() {
        @Override
        public void run() {
            if (!alive || paused || !skinMode) return;
            if (skinFaceUntil > 0 && SystemClock.uptimeMillis() >= skinFaceUntil) {
                skinFace = 0;
                skinFaceUntil = 0;
            }
            view.invalidate();
            view.postDelayed(this, 33);
        }
    };

    /** 立绘待机的小表情 / 小动作，不再走 106 个逐帧动作。 */
    private final Runnable skinIdleTick = new Runnable() {
        @Override
        public void run() {
            if (!alive || paused || !skinMode) return;
            if (mode == Mode.IDLE && !peek && !dragging && !chatOpen && !locked) {
                if (live2d != null) {
                    if (rnd.nextFloat() < 0.62f) live2d.expression(1 + rnd.nextInt(6));
                    else live2d.idle();
                } else if (rnd.nextFloat() < 0.62f) {
                    showFace(1 + rnd.nextInt(6), 1500 + rnd.nextInt(1800));
                } else {
                    startSkinAction(rnd.nextInt(2) + 1);
                }
            }
            view.postDelayed(this, 2800 + rnd.nextInt(3800));
        }
    };

    private void startLoops() {
        if (!alive || paused) return;
        view.removeCallbacks(skinAnim);
        view.removeCallbacks(skinIdleTick);
        if (skinMode && !peek) {
            view.post(skinAnim);
            view.postDelayed(skinIdleTick, 2600 + rnd.nextInt(2200));
        }
    }

    private void showFace(int idx, long ms) {
        if (!skinMode || skinDef == null) return;
        idx = Math.max(0, Math.min(6, idx));
        if (live2d != null) {
            if (idx > 0) live2d.expression(idx);
            view.invalidate();
            return;
        }
        if (idx > 0) ensureSkinFace(idx);
        skinFace = idx;
        skinFaceUntil = SystemClock.uptimeMillis() + Math.max(400, ms);
        view.invalidate();
    }

    private void startSkinAction(int kind) {
        if (live2d != null) {
            live2d.idle();
            return;
        }
        skinAction = kind;
        skinActionAt = SystemClock.uptimeMillis();
        view.invalidate();
    }

    /** 32 位设备没有 Live2D 的 .so：选过 Live2D 皮肤就静默退回原版，别让桌宠空着。 */
    private SkinStore.SkinDef resolveSkin() {
        SkinStore.SkinDef d = SkinStore.current(ctx);
        if (d != null && d.live2d && !Live2DRenderer.supported()) {
            SkinStore.select(ctx, SkinStore.ORIGINAL);
            d = SkinStore.current(ctx);
        }
        return d;
    }

    public Pet(Context c, PetConf conf, PetConf.Spec spec, Host host) {
        this.ctx = c;
        this.conf = conf;
        this.spec = spec;
        this.host = host;
        this.density = c.getResources().getDisplayMetrics().density;
        this.skinDef = resolveSkin();
        this.skinMode = skinDef != null && !skinDef.original;
        if (skinMode) this.skinHeightRatio = skinDef.heightRatio;
        this.view = new PetView(this, c);
        applyScale(150, false);
    }

    public View view() {
        return view;
    }

    public int sizePx() {
        return sizePx;
    }

    public int heightPx() {
        return heightPx;
    }

    /** 悬浮窗保留完整绘制画布；触摸区域另由站姿身体轮廓精确限制。 */
    public int windowX() {
        return (int) x + padL;
    }

    public int windowW() {
        return winW;
    }

    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    public int facing() {
        return facing;
    }

    public void setLocked(boolean v) {
        locked = v;
        if (v) {
            dragging = false;
            dragVX = 0;
            dragVY = 0;
            // 锁定后立刻停在原地，不再继续自主移动
            moveToX = x;
            moveFromX = x;
            if (mode == Mode.MOVE || mode == Mode.DRAG) mode = Mode.IDLE;
            push();
            view.invalidate();
        }
    }

    public void setPosition(double px, double py) {
        x = px;
        y = py;
        clamp();
        push();
    }

    public void applyScale(int sizeDp) {
        applyScale(sizeDp, true);
    }

    private void applyScale(int sizeDp, boolean push) {
        sizePx = Math.max(64, (int) (sizeDp * density));
        idleHeightPx = Math.max(36, (int) (sizePx * (skinMode ? skinHeightRatio : 9f / 16f)));
        heightPx = peek ? peekHeight() : idleHeightPx;
        layoutBox();
        clamp();
        if (peek) placePeek();
        if (push) push();
        if (skinMode) reloadSkin(false);
    }

    private int peekHeight() {
        // 探头只露头与扶墙的手，按桌宠大小等比缩放且明显小于站姿
        float k = skinMode ? 0.60f : 0.48f;
        return Math.max((int) (density * 34), (int) (sizePx * k));
    }

    private void resizeView() {
        ViewGroup.LayoutParams lp = view.getLayoutParams();
        if (lp != null) {
            lp.width = winW;
            lp.height = heightPx;
            view.setLayoutParams(lp);
        }
    }

    /**
     * 结算绘制窗口。除贴边探头外始终保留完整源画布；触摸命中在 bodyMaskContains 中
     * 独立按站姿身体剪影判断，因此动作伸展和粒子不会扩大点击范围。
     */
    private void layoutBox() {
        int pl, pr;
        if (peek) {
            int vw = sizePx;
            if (peekBmp != null && peekBmp.getHeight() > 0) {
                vw = (int) Math.round(heightPx * peekBmp.getWidth() / (double) peekBmp.getHeight());
            }
            vw = Math.max(1, Math.min(sizePx, vw));
            pl = peekSide < 0 ? 0 : sizePx - vw;
            pr = peekSide < 0 ? sizePx - vw : 0;
        } else {
            // 显示窗口始终保留完整素材画布。静态站姿的透明边界只用于贴边和触摸命中，
            // 不能拿来裁动作帧、Live2D 摆动或动态皮肤伸出的头发/道具。
            pl = 0;
            pr = 0;
        }
        padL = Math.max(0, Math.min(sizePx - 1, pl));
        padR = Math.max(0, Math.min(sizePx - 1 - padL, pr));
        winW = Math.max(1, sizePx - padL - padR);
        resizeView();
        updateTouchableRegion();
    }

    /** 自定义立绘透明边界用于贴边定位和无遮罩时的命中回退，不裁绘制窗口。 */
    private void measureSkinContent() {
        skinCL = 0.03;
        skinCR = 0.97;
        if (skinFrames == null || skinFrames.length == 0) return;
        // 所有帧取并集：只量第一帧会让伸胳膊/跳起的动作帧被窗口切掉
        int step0 = 0;
        int gLeft = Integer.MAX_VALUE, gRight = -1, gW = 0;
        for (Bitmap b : skinFrames) {
            if (b == null) continue;
            int w = b.getWidth(), h = b.getHeight();
            if (w <= 0 || h <= 0) continue;
            int step = Math.max(1, Math.min(w, h) / 48);
            int left = w, right = -1;
            for (int px = 0; px < w; px += step) {
                for (int py = 0; py < h; py += step) {
                    if ((b.getPixel(px, py) >>> 24) > 24) {
                        if (px < left) left = px;
                        if (px > right) right = px;
                        break;
                    }
                }
            }
            if (right <= left) continue;
            if (w != gW && gRight >= 0) continue;
            gW = w;
            if (left < gLeft) gLeft = left;
            if (right > gRight) gRight = right;
            step0 = Math.max(step0, step);
        }
        if (gRight <= gLeft) return;
        skinCL = Math.max(0, gLeft / (double) gW - 0.01);
        skinCR = Math.min(1, (gRight + step0) / (double) gW + 0.01);
    }

    private void ensurePeekBitmap() {
        if (peekBmp != null) return;
        if (skinMode) {
            peekBmp = makeSkinPeek();
            updateTouchableRegion();
            return;
        }
        try (InputStream in = ctx.getAssets().open("webp/peek_right.png")) {
            peekBmp = BitmapFactory.decodeStream(in);
        } catch (Throwable ignored) {
        }
        updateTouchableRegion();
    }

    /** 立绘扒边：裁出头块，按探头方向旋转 90°，再去掉透明边。 */
    private Bitmap makeSkinPeek() {
        Bitmap base = baseBitmap();
        if (base == null || skinDef == null) return null;
        try {
            int bw = base.getWidth(), bh = base.getHeight();
            int x0 = Math.max(0, (int) (skinDef.hx0 * bw));
            int x1 = Math.min(bw, Math.max(x0 + 1, (int) (skinDef.hx1 * bw)));
            int y0 = Math.max(0, (int) (skinDef.hy0 * bh));
            int y1 = Math.min(bh, Math.max(y0 + 1, (int) (skinDef.hy1 * bh)));
            Bitmap head = Bitmap.createBitmap(base, x0, y0, x1 - x0, y1 - y0);
            head = trimAlpha(head);
            android.graphics.Matrix m = new android.graphics.Matrix();
            m.postRotate(peekSide < 0 ? -90f : 90f);
            Bitmap rot = Bitmap.createBitmap(head, 0, 0, head.getWidth(), head.getHeight(), m, true);
            if (rot != head) head.recycle();
            return rot;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 去掉四周透明留白，避免旋转后头块旁边出现大片空白。 */
    private static Bitmap trimAlpha(Bitmap src) {
        int w = src.getWidth(), h = src.getHeight();
        int[] px = new int[w * h];
        src.getPixels(px, 0, w, 0, 0, w, h);
        int minX = w, minY = h, maxX = -1, maxY = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if ((px[y * w + x] >>> 24) > 16) {
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }
        if (maxX < minX || maxY < minY) return src;
        if (minX == 0 && minY == 0 && maxX == w - 1 && maxY == h - 1) return src;
        Bitmap out = Bitmap.createBitmap(src, minX, minY, maxX - minX + 1, maxY - minY + 1);
        if (out != src) src.recycle();
        return out;
    }

    /* ---------------- 皮肤加载 ---------------- */

    private void reloadSkin(boolean force) {
        if (!skinMode || skinDef == null) return;
        if (skinDef.live2d) {
            skinDecodeW = sizePx;
            ensureLive2d();
            loadLive2dTouchMask(skinDef.id, skinDef.standPath, sizePx);
            return;
        }
        if (!force && skinDecodeW > 0 && Math.abs(sizePx - skinDecodeW) < skinDecodeW * 0.35f) return;
        skinDecodeW = sizePx;
        if (skinDef.anim) {
            loadAnimFrames();
            return;
        }
        for (int i = 1; i < skinFrames.length; i++) recycle(skinFrames[i]);
        recycle(skinFrames[0]);
        skinFrames[0] = decodeSkin(skinDef.standPath, sizePx);
        setBodyTouchMask(skinFrames[0]);
        skinFace = 0;
        skinFaceUntil = 0;
        measureSkinContent();
        layoutBox();
        push();
        if (peekBmp != null) {
            recycle(peekBmp);
            peekBmp = null;
            if (peek) ensurePeekBitmap();
        }
        view.invalidate();
    }

    private Bitmap decodeSkin(String path, int targetW) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            if (skinDef.custom) {
                BitmapFactory.decodeFile(path, bounds);
            } else {
                try (InputStream in = ctx.getAssets().open(path)) {
                    BitmapFactory.decodeStream(in, null, bounds);
                }
            }
            if (bounds.outWidth <= 0) return null;
            int sample = 1;
            int need = Math.max(240, targetW);
            while (bounds.outWidth / (sample * 2) >= need) sample *= 2;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            if (skinDef.custom) return BitmapFactory.decodeFile(path, o);
            try (InputStream in = ctx.getAssets().open(path)) {
                return BitmapFactory.decodeStream(in, null, o);
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private void ensureSkinFace(int idx) {
        if (!skinMode || skinDef == null || idx <= 0 || idx >= skinFrames.length) return;
        if (skinDef.custom || skinDef.anim || skinDef.live2d || idx > skinDef.facePaths.length) return;
        if (skinFrames[idx] != null) return;
        final String path = skinDef.facePaths[idx - 1];
        final int targetW = sizePx;
        io.execute(() -> {
            Bitmap b = decodeSkin(path, targetW);
            if (b == null) return;
            view.post(() -> {
                if (!alive || !skinMode || skinDef == null) return;
                if (idx >= skinDef.facePaths.length || !path.equals(skinDef.facePaths[idx - 1])) return;
                if (skinFrames[idx] == null) skinFrames[idx] = b;
                else recycle(b);
                view.invalidate();
            });
        });
    }

    /** 启动/复用 Live2D 渲染线程；尺寸变化只通知不重建。 */
    private void ensureLive2d() {
        if (skinDef == null || !skinDef.live2d || !Live2DRenderer.supported()) return;
        if (live2d != null && skinDef.id.equals(live2dId)) {
            live2d.setSize(sizePx, idleHeightPx);
            return;
        }
        releaseLive2d();
        live2dId = skinDef.id;
        Live2DRenderer r = new Live2DRenderer(ctx, new java.io.File(skinDef.dir), skinDef.model3);
        live2d = r;
        r.start(sizePx, idleHeightPx);
    }

    private void releaseLive2d() {
        Live2DRenderer r = live2d;
        live2d = null;
        live2dId = "";
        if (r != null) r.release();
    }

    /** 序列帧皮肤：把同一套的所有帧一次性读进内存，之后纯画 bitmap，避免逐帧解码卡顿。 */
    private void loadAnimFrames() {
        if (skinDef == null) return;
        final String id = skinDef.id;
        if (id.equals(animLoadedId) && animFrames.length > 0) {
            view.invalidate();
            return;
        }
        animLoadedId = "";
        recycleAnimFrames();
        final String[] paths = skinDef.framePaths;
        final int fps = skinDef.fps;
        io.execute(() -> {
            Bitmap[] raw = new Bitmap[paths.length];
            int n = 0;
            for (int i = 0; i < paths.length; i++) {
                raw[i] = BitmapFactory.decodeFile(paths[i]);
                if (raw[i] != null) n++;
            }
            Bitmap[] frames = new Bitmap[n];
            int k = 0;
            for (Bitmap b : raw) {
                if (b != null) frames[k++] = b;
            }
            final Bitmap[] done = frames;
            view.post(() -> {
                if (!alive || !skinMode || skinDef == null || !id.equals(skinDef.id)) {
                    for (Bitmap b : done) recycle(b);
                    return;
                }
                recycleAnimFrames();
                animFrames = done;
                animFps = fps;
                animStart = SystemClock.uptimeMillis();
                animLoadedId = id;
                setBodyTouchMask(animFrames.length > 0 ? animFrames[0] : null);
                if (peek) {
                    recycle(peekBmp);
                    peekBmp = null;
                    ensurePeekBitmap();
                }
                view.invalidate();
            });
        });
    }

    /** The Live2D thumbnail is its stable standing silhouette, never an action-frame crop. */
    private void loadLive2dTouchMask(String skinId, String path, int targetW) {
        if (path == null || path.isEmpty()) return;
        io.execute(() -> {
            Bitmap bitmap = decodeSkin(path, targetW);
            if (bitmap == null) return;
            view.post(() -> {
                if (!alive || skinDef == null || !skinId.equals(skinDef.id)) {
                    recycle(bitmap);
                    return;
                }
                setBodyTouchMask(bitmap);
                recycle(bitmap);
            });
        });
    }

    /** Store a small alpha silhouette instead of treating the whole animation canvas as tappable. */
    private void setBodyTouchMask(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled() || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) {
            bodyTouchMask = null;
            updateTouchableRegion();
            return;
        }
        byte[] mask = new byte[TOUCH_MASK_W * TOUCH_MASK_H];
        int width = bitmap.getWidth(), height = bitmap.getHeight();
        for (int y = 0; y < TOUCH_MASK_H; y++) {
            int sy = Math.min(height - 1, (int) ((y + 0.5f) * height / TOUCH_MASK_H));
            for (int x = 0; x < TOUCH_MASK_W; x++) {
                int sx = Math.min(width - 1, (int) ((x + 0.5f) * width / TOUCH_MASK_W));
                mask[y * TOUCH_MASK_W + x] = (byte) ((bitmap.getPixel(sx, sy) >>> 24) >= 28 ? 1 : 0);
            }
        }
        bodyTouchMask = mask;
        updateTouchableRegion();
    }

    /** Capture the first frame of a standing clip only. Click/tap/action clips keep this same hit shape. */
    private void setBodyTouchMask(Drawable drawable) {
        if (drawable == null) return;
        Bitmap bitmap = null;
        try {
            bitmap = Bitmap.createBitmap(TOUCH_MASK_W, TOUCH_MASK_H, Bitmap.Config.ARGB_8888);
            drawable.setBounds(0, 0, TOUCH_MASK_W, TOUCH_MASK_H);
            drawable.draw(new Canvas(bitmap));
            setBodyTouchMask(bitmap);
        } catch (Throwable ignored) {
            bodyTouchMask = null;
            updateTouchableRegion();
        } finally {
            recycle(bitmap);
        }
    }

    private boolean bodyMaskContains(float x, float y) {
        if (x < 0f || x >= 1f || y < 0f || y >= 1f) return false;
        byte[] mask = bodyTouchMask;
        if (mask == null || mask.length != TOUCH_MASK_W * TOUCH_MASK_H) {
            double left = skinMode ? skinCL : CONTENT_L;
            double right = skinMode ? skinCR : CONTENT_R;
            return x >= left && x <= right && y >= 0.035f && y <= 0.985f;
        }
        int mx = Math.min(TOUCH_MASK_W - 1, (int) (x * TOUCH_MASK_W));
        int my = Math.min(TOUCH_MASK_H - 1, (int) (y * TOUCH_MASK_H));
        return mask[my * TOUCH_MASK_W + mx] != 0;
    }

    private boolean touchesPetBody(float localX, float localY) {
        if (peek) {
            ensurePeekBitmap();
            if (peekBmp == null || peekBmp.getWidth() <= 0 || peekBmp.getHeight() <= 0) return false;
            float width = Math.max(1f, heightPx * peekBmp.getWidth() / (float) peekBmp.getHeight());
            float x = Math.max(0f, Math.min(0.999f, localX / width));
            if (!skinMode && peekSide < 0) x = 1f - x;
            float y = Math.max(0f, Math.min(0.999f, localY / Math.max(1, heightPx)));
            int px = Math.max(0, Math.min(peekBmp.getWidth() - 1, (int) (x * peekBmp.getWidth())));
            int py = Math.max(0, Math.min(peekBmp.getHeight() - 1, (int) (y * peekBmp.getHeight())));
            return (peekBmp.getPixel(px, py) >>> 24) >= 28;
        }
        float x = (localX + padL) / Math.max(1f, sizePx);
        if (!forcedNoMirror && facing < 0) x = 1f - x;
        float y = localY / Math.max(1f, heightPx);
        if (squashActive) {
            double scale = Phys.squashScale(Math.max(0, Math.min(1,
                    (SystemClock.uptimeMillis() - squashAt) / 220.0)), squashAmount);
            if (scale > 0.01) y = (float) (1.0 + (y - 1.0) / scale);
        }
        return bodyMaskContains(x, y);
    }

    /** Android 13+ can pass overlay touches through outside the standing silhouette itself. */
    private void updateTouchableRegion() {
        if (Build.VERSION.SDK_INT < 33 || !alive) return;
        view.post(() -> {
            if (!alive || Build.VERSION.SDK_INT < 33) return;
            try {
                // This interface exists only from API 33. Reflect it so the application
                // can still be compiled against its existing Android API surface.
                Class<?> surfaceType = Class.forName("android.view.AttachedSurfaceControl");
                Method rootMethod = View.class.getMethod("getRootSurfaceControl");
                Object surface = rootMethod.invoke(view);
                if (surface == null || view.getWidth() <= 0 || view.getHeight() <= 0) return;
                Region region = new Region();
                if (peek && peekBmp != null && !peekBmp.isRecycled()) {
                    float displayW = heightPx * peekBmp.getWidth() / (float) peekBmp.getHeight();
                    for (int row = 0; row < TOUCH_MASK_H; row++) {
                        int py = Math.min(peekBmp.getHeight() - 1,
                                (int) ((row + 0.5f) * peekBmp.getHeight() / TOUCH_MASK_H));
                        int runStart = -1;
                        for (int col = 0; col <= TOUCH_MASK_W; col++) {
                            boolean opaque = false;
                            if (col < TOUCH_MASK_W) {
                                float u = (col + 0.5f) / TOUCH_MASK_W;
                                if (!skinMode && peekSide < 0) u = 1f - u;
                                int px = Math.min(peekBmp.getWidth() - 1, (int) (u * peekBmp.getWidth()));
                                opaque = (peekBmp.getPixel(px, py) >>> 24) >= 28;
                            }
                            if (opaque && runStart < 0) runStart = col;
                            if (!opaque && runStart >= 0) {
                                int left = Math.max(0, (int) (runStart * displayW / TOUCH_MASK_W));
                                int right = Math.min(view.getWidth(), (int) Math.ceil(col * displayW / TOUCH_MASK_W));
                                int top = (int) (row * view.getHeight() / (float) TOUCH_MASK_H);
                                int bottom = (int) Math.ceil((row + 1) * view.getHeight() / (float) TOUCH_MASK_H);
                                if (right > left && bottom > top) region.op(left, top, right, bottom, Region.Op.UNION);
                                runStart = -1;
                            }
                        }
                    }
                } else if (bodyTouchMask != null && bodyTouchMask.length == TOUCH_MASK_W * TOUCH_MASK_H) {
                    for (int row = 0; row < TOUCH_MASK_H; row++) {
                        int runStart = -1;
                        for (int col = 0; col <= TOUCH_MASK_W; col++) {
                            boolean opaque = col < TOUCH_MASK_W && bodyTouchMask[row * TOUCH_MASK_W + col] != 0;
                            if (opaque && runStart < 0) runStart = col;
                            if (!opaque && runStart >= 0) {
                                int left = Math.max(0, (int) (runStart * sizePx / (float) TOUCH_MASK_W) - padL);
                                int right = Math.min(view.getWidth(), (int) Math.ceil(col * sizePx / (float) TOUCH_MASK_W) - padL);
                                int top = (int) (row * view.getHeight() / (float) TOUCH_MASK_H);
                                int bottom = (int) Math.ceil((row + 1) * view.getHeight() / (float) TOUCH_MASK_H);
                                if (right > left && bottom > top) region.op(left, top, right, bottom, Region.Op.UNION);
                                runStart = -1;
                            }
                        }
                    }
                } else {
                    float left = (float) ((skinMode ? skinCL : CONTENT_L) * sizePx - padL);
                    float right = (float) ((skinMode ? skinCR : CONTENT_R) * sizePx - padL);
                    region.set((int) left, (int) (view.getHeight() * 0.035f), (int) right,
                            (int) (view.getHeight() * 0.985f));
                }
                Method setTouchableRegion = surfaceType.getMethod("setTouchableRegion", Region.class);
                setTouchableRegion.invoke(surface, region);
            } catch (Throwable ignored) {
                // View-level silhouette hit-testing remains active if this device lacks an attached surface.
            }
        });
    }

    private void recycleAnimFrames() {
        for (Bitmap b : animFrames) recycle(b);
        animFrames = new Bitmap[0];
        animLoadedId = "";
    }

    /** 序列帧当前该显示哪一帧；静态立绘沿用表情帧；Live2D 用渲染线程的最新一帧。 */
    private Bitmap skinNow() {
        if (live2d != null) return live2d.frame();
        if (skinDef != null && skinDef.anim) {
            int n = animFrames.length;
            if (n == 0) return null;
            int fps = Math.max(1, Math.min(30, animFps));
            int idx = (int) ((SystemClock.uptimeMillis() - animStart) * fps / 1000L) % n;
            return animFrames[idx];
        }
        if (skinFace > 0 && skinFace < skinFrames.length && skinFrames[skinFace] != null) {
            return skinFrames[skinFace];
        }
        return skinFrames[0];
    }

    /** 探头/缩略图用的底图：序列帧取第一帧，静态立绘取站姿，Live2D 取当前帧。 */
    private Bitmap baseBitmap() {
        if (live2d != null) return live2d.frame();
        if (skinDef != null && skinDef.anim) {
            return animFrames.length > 0 ? animFrames[0] : null;
        }
        return skinFrames[0];
    }

    private static void recycle(Bitmap b) {
        if (b == null) return;
        try {
            b.recycle();
        } catch (Throwable ignored) {
        }
    }

    public void start() {
        Cfg cfg = Cfg.load(ctx);
        int sx = cfg.i("posX", -1);
        int sy = cfg.i("posY", -1);
        // 允许贴左边缘时的负坐标，只有 (-1,-1) 才是「从未移动过」的默认值
        if (!(sx == -1 && sy == -1)) {
            x = sx;
            y = sy;
            clamp();
        } else {
            double[] p = anchor();
            x = p[0];
            y = p[1];
        }
        facing = cfg.i("facing", 1) == -1 ? -1 : 1;
        push();
        host.onPosition(x, y, facing);
        playIdle();
        startLoops();
    }

    /** 皮肤热切换：MainAct 选中后通过 PetSvc.A_SKIN 调到这里。 */
    public void applySkin() {
        releaseLive2d();
        SkinStore.SkinDef d = resolveSkin();
        skinDef = d;
        skinMode = d != null && !d.original;
        bodyTouchMask = null;
        skinHeightRatio = skinMode ? d.heightRatio : 9f / 16f;
        skinFace = 0;
        skinFaceUntil = 0;
        skinAction = 0;
        recycleAnimFrames();
        if (!skinMode) {
            for (int i = 0; i < skinFrames.length; i++) {
                recycle(skinFrames[i]);
                skinFrames[i] = null;
            }
            skinDecodeW = 0;
        } else {
            skinDecodeW = 0;
        }
        if (peekBmp != null) {
            recycle(peekBmp);
            peekBmp = null;
        }
        if (skinMode) {
            // 切到立绘后停掉原版逐帧动画，避免它在后台继续驱动重绘
            loadToken++;
            AnimatedImageDrawable old = current;
            if (old != null) {
                try {
                    old.stop();
                } catch (Throwable ignored) {
                }
            }
            current = null;
        }
        playIdle();
        applyScale(Cfg.load(ctx).sizeDp());
        if (peek) {
            ensurePeekBitmap();
            placePeek();
        }
        view.invalidate();
        startLoops();
    }

    /** 聊天框打开期间不做待机小动作。 */
    public void setChatOpen(boolean v) {
        chatOpen = v;
        if (!v) startLoops();
    }

    /** 聊天框打开时，把可见角色挪到聊天框左下角。 */
    public void placeAtCorner(int visibleLeft, int topY) {
        if (!alive) return;
        restoreIdleHeight();
        double nx = visibleLeft - sizePx * contentL();
        boolean moved = Math.abs(nx - x) > 1.5 || Math.abs(topY - y) > 1.5;
        peek = false;
        dragging = false;
        dragVX = 0;
        dragVY = 0;
        mode = Mode.IDLE;
        x = nx;
        y = topY;
        clamp();
        facing = 1;
        forcedNoMirror = false;
        push();
        host.onPosition(x, y, facing);
        if (moved) playIdle();
    }

    public void moveTo(double nx, double ny) {
        if (!alive) return;
        restoreIdleHeight();
        peek = false;
        dragging = false;
        mode = Mode.IDLE;
        x = nx;
        y = ny;
        clamp();
        push();
        host.onPosition(x, y, facing);
        playIdle();
    }

    public void resetPosition() {
        mode = Mode.IDLE;
        restoreIdleHeight();
        peek = false;
        double[] p = anchor();
        x = p[0];
        y = p[1];
        push();
        host.onPosition(x, y, facing);
        playIdle();
    }

    public void flip() {
        facing = facing == 1 ? -1 : 1;
        forcedNoMirror = false;
        view.invalidate();
        host.onPosition(x, y, facing);
    }

    public void onScreenChanged() {
        if (peek) placePeek();
        else clamp();
        push();
    }

    public void refreshWindow() {
        push();
    }

    private void restoreIdleHeight() {
        if (heightPx == idleHeightPx) return;
        double bottom = y + heightPx;
        heightPx = idleHeightPx;
        layoutBox();
        y = bottom - heightPx;
    }

    public void release() {
        alive = false;
        bodyTouchMask = null;
        loadToken++;
        releaseLive2d();
        if (peekBmp != null) {
            try {
                peekBmp.recycle();
            } catch (Throwable ignored) {
            }
            peekBmp = null;
        }
        for (int i = 0; i < skinFrames.length; i++) {
            recycle(skinFrames[i]);
            skinFrames[i] = null;
        }
        recycleAnimFrames();
        AnimatedImageDrawable d = current;
        if (d != null) {
            try {
                d.stop();
            } catch (Throwable ignored) {
            }
        }
        current = null;
        view.removeCallbacks(longRunnable);
        view.removeCallbacks(peekAnim);
        view.removeCallbacks(skinAnim);
        view.removeCallbacks(skinIdleTick);
    }

    private double[] anchor() {
        int[] ss = screenSize();
        int sw = ss[0];
        int sh = ss[1];
        double mx = spec.marginX * density;
        double my = spec.marginY * density;
        double rx = sw - sizePx * contentR() - spec.marginX * density;
        double by = sh - heightPx - spec.marginY * density;
        switch (spec.corner) {
            case "top-left":
                return new double[]{mx, my};
            case "bottom-left":
                return new double[]{mx, by};
            case "bottom-right":
                return new double[]{rx, by};
            default:
                return new double[]{rx, my};
        }
    }

    /** 素材画布里角色本体只占中间约三分之一，贴边要按本体边缘计算。 */
    private static final double CONTENT_L = 0.331;
    private static final double CONTENT_R = 0.669;
    /** 允许本体再向屏幕边缘多贴一点，消除视觉上的空隙。 */
    private static final double EDGE_L = 0.36;
    private static final double EDGE_R = 0.64;

    /** 立绘几乎占满整幅图，命中区和贴边边界按新的比例算。 */
    private double contentL() {
        return skinMode ? skinCL : CONTENT_L;
    }

    private double contentR() {
        return skinMode ? skinCR : CONTENT_R;
    }

    private double edgeL() {
        return skinMode ? 0.02 : EDGE_L;
    }

    private double edgeR() {
        return skinMode ? 0.98 : EDGE_R;
    }

    private Phys.Bounds bounds() {
        int[] ss = screenSize();
        int sw = ss[0];
        int sh = ss[1];
        return new Phys.Bounds(-sizePx * edgeL(), 0, sw - sizePx * edgeR(), sh - heightPx);
    }

    /** 旋转后仍取当前真实屏幕尺寸，避免拿旧方向的分辨率算出屏幕外坐标。 */
    private int[] screenSize() {
        return Screen.size(ctx);
    }

    /** 长按面板「藏起来」：自己跑到最近的一侧再探头。 */
    public void hideToEdge() {
        if (!alive) return;
        double sw = screenSize()[0];
        peekSide = (x + sizePx / 2.0) < sw / 2.0 ? -1 : 1;
        double target = peekSide < 0 ? bounds().minX : bounds().maxX;
        dragging = false;
        dragVX = 0;
        dragVY = 0;
        restoreIdleHeight();
        peek = false;
        view.removeCallbacks(peekAnim);
        mode = Mode.RUN;
        runFromX = x;
        runToX = target;
        runBaseY = y;
        runStart = SystemClock.uptimeMillis();
        double dist = Math.abs(target - x);
        runDuration = Math.max(1400, Math.min(2200, (long) (dist / (density * 260.0) * 1000)));
        facing = peekSide < 0 ? -1 : 1;
        forcedNoMirror = false;
        view.invalidate();
        play("a102");
        host.onPosition(x, y, facing);
    }

    private void beginPeek(int side) {
        peek = true;
        peekSide = side;
        facing = side < 0 ? 1 : -1;
        ensurePeekBitmap();
        double bottom = y + heightPx;
        heightPx = peekHeight();
        layoutBox();
        y = bottom - heightPx;
        placePeek();
        if (skinMode) {
            skinFace = 0;
            skinFaceUntil = 0;
        } else {
            playIdle();
        }
        view.removeCallbacks(skinAnim);
        view.removeCallbacks(skinIdleTick);
        view.removeCallbacks(peekAnim);
        view.post(peekAnim);
    }

    /** 从贴边探头状态出来。 */
    public void showFromEdge() {
        if (!peek) return;
        view.removeCallbacks(peekAnim);
        exitPeek();
        playIdle();
        startLoops();
    }

    public void pausePeek() {
        paused = true;
        view.removeCallbacks(peekAnim);
        view.removeCallbacks(skinAnim);
        view.removeCallbacks(skinIdleTick);
    }

    public void resumePeek() {
        if (!alive) return;
        paused = false;
        if (peek) {
            view.removeCallbacks(peekAnim);
            view.post(peekAnim);
        }
        startLoops();
    }

    private void placePeek() {
        if (!peek) return;
        int[] ss = screenSize();
        double sw = ss[0];
        double sh = ss[1];
        x = peekSide < 0 ? 0 : sw - sizePx;
        y = Math.max(0, Math.min(sh - heightPx, y));
        push();
        view.invalidate();
    }

    private void exitPeek() {
        if (!peek) return;
        peek = false;
        view.removeCallbacks(peekAnim);
        double sw = screenSize()[0];
        double bottom = y + heightPx;
        heightPx = idleHeightPx;
        layoutBox();
        y = bottom - heightPx;
        x = peekSide < 0 ? -sizePx * contentL() : sw - sizePx * contentR();
        clamp();
        push();
        view.invalidate();
        if (skinMode && !paused) {
            view.removeCallbacks(skinAnim);
            view.post(skinAnim);
        }
    }

    private void clamp() {
        Phys.Bounds b = bounds();
        x = Math.max(b.minX, Math.min(b.maxX, x));
        y = Math.max(b.minY, Math.min(b.maxY, y));
    }

    private void push() {
        // 交给系统的窗口矩形只覆盖角色实体，旁边的透明区域不参与触摸
        host.onWindow(windowX(), (int) y, winW, heightPx);
    }

    private double now() {
        return SystemClock.uptimeMillis();
    }

    private void squash(double amount) {
        squashAmount = amount;
        squashAt = SystemClock.uptimeMillis();
        squashActive = true;
        view.invalidate();
    }

    private void play(String slug) {
        play(slug, null);
    }

    private void play(final String slug, final Runnable onEnd) {
        if (!alive) return;
        final boolean standingClip = conf.idle.contains(slug);
        if (slug == null || slug.isEmpty()) {
            if (onEnd != null) onEnd.run();
            return;
        }
        if (live2d != null) {
            live2d.playByName(conf.nameOf(slug));
            int face = SkinStore.faceForName(conf.nameOf(slug));
            if (face > 0) live2d.expression(face);
            if (onEnd != null) {
                view.postDelayed(() -> {
                    if (alive) onEnd.run();
                }, 700 + rnd.nextInt(500));
            }
            return;
        }
        if (skinMode) {
            int face = SkinStore.faceForName(conf.nameOf(slug));
            if (face == 0 && rnd.nextFloat() < 0.5f) face = 1;
            showFace(face, 700 + rnd.nextInt(900));
            startSkinAction(rnd.nextInt(2) + 1);
            if (onEnd != null) {
                view.postDelayed(() -> {
                    if (alive) onEnd.run();
                }, 620 + rnd.nextInt(420));
            }
            return;
        }
        loadToken++;
        final int token = loadToken;
        io.execute(() -> {
            AnimatedImageDrawable d = null;
            try {
                ImageDecoder.Source src = ImageDecoder.createSource(ctx.getAssets(), "webp/" + slug + ".webp");
                Drawable dr = ImageDecoder.decodeDrawable(src);
                if (dr instanceof AnimatedImageDrawable) d = (AnimatedImageDrawable) dr;
            } catch (Throwable t) {
                Sg.write(new java.io.File(Sg.dir(ctx), "diag.txt"), Sg.now() + " decode " + slug + " " + t + "\n");
            }
            final AnimatedImageDrawable drawable = d;
            view.post(() -> {
                if (token != loadToken || !alive) return;
                if (drawable == null) {
                    if (onEnd != null) view.postDelayed(onEnd, 900);
                    return;
                }
                try {
                    // repeatCount 是「第一遍之后再重复几次」：1 会播两遍，这里必须单遍
                    drawable.setRepeatCount(0);
                    drawable.setCallback(view);
                    AnimatedImageDrawable old = current;
                    if (old != null) old.stop();
                    current = drawable;
                    currentSlug = slug;
                    if (standingClip) setBodyTouchMask(drawable);
                    drawable.start();
                    view.invalidate();
                } catch (Throwable ignored) {
                }
                if (onEnd != null) waitEnd(drawable, token, onEnd);
            });
        });
    }

    private void waitEnd(final AnimatedImageDrawable d, final int token, final Runnable onEnd) {
        view.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (token != loadToken || !alive) return;
                boolean running;
                try {
                    running = d.isRunning();
                } catch (Throwable t) {
                    running = false;
                }
                if (running) view.postDelayed(this, 120);
                else {
                    if (onEnd != null) onEnd.run();
                }
            }
        }, 180);
    }

    private void playIdle() {
        mode = Mode.IDLE;
        forcedNoMirror = false;
        if (skinMode) {
            skinFace = 0;
            skinFaceUntil = 0;
            skinAction = 0;
            view.invalidate();
            if (!paused && !peek) {
                view.removeCallbacks(skinAnim);
                view.post(skinAnim);
                view.removeCallbacks(skinIdleTick);
                view.postDelayed(skinIdleTick, 2600 + rnd.nextInt(2200));
            }
            return;
        }
        if (conf.idle.isEmpty()) {
            playRandomAction();
            return;
        }
        play(conf.idle.get(rnd.nextInt(conf.idle.size())), this::scheduleNext);
    }

    private void playTurn() {
        facing = -facing;
        forcedNoMirror = false;
        view.invalidate();
        host.onPosition(x, y, facing);
        if (skinMode) {
            showFace(1 + rnd.nextInt(6), 1100);
            startSkinAction(2);
            return;
        }
        if (conf.turn.isEmpty()) {
            playIdle();
            return;
        }
        play(conf.turn.get(rnd.nextInt(conf.turn.size())), this::scheduleNext);
    }

    private void playDragAnim() {
        if (skinMode) {
            if (dragging) startSkinAction(2);
            return;
        }
        if (conf.drag.isEmpty()) return;
        lastDragAnimAt = SystemClock.uptimeMillis();
        play(conf.drag.get(rnd.nextInt(conf.drag.size())));
    }

    private void playRandomAction() {
        PetConf.Cat cat = pickCat();
        if (cat == null || cat.actions.isEmpty()) {
            playIdle();
            return;
        }
        forcedNoMirror = cat.noMirror;
        String slug = cat.actions.get(rnd.nextInt(cat.actions.size()));
        host.onAction(slug, conf.nameOf(slug));
        if (skinMode) {
            play(slug);
            return;
        }
        play(slug, () -> {
            forcedNoMirror = false;
            scheduleNext();
        });
    }

    private PetConf.Cat pickCat() {
        // 饿了就更容易去找吃的
        if (Data.hunger(ctx) >= 70 && rnd.nextDouble() < 0.5) {
            for (PetConf.Cat c : conf.cats) {
                if ("吃什么".equals(c.id) && !c.actions.isEmpty()) return c;
            }
        }
        int total = 0;
        for (PetConf.Cat c : conf.cats) total += Math.max(0, c.weight);
        if (total <= 0) return conf.cats.isEmpty() ? null : conf.cats.get(rnd.nextInt(conf.cats.size()));
        int n = rnd.nextInt(total);
        for (PetConf.Cat c : conf.cats) {
            if (c.weight > 0) {
                n -= c.weight;
                if (n < 0) return c;
            }
        }
        return conf.cats.get(conf.cats.size() - 1);
    }

    private void scheduleNext() {
        // 立绘模式由 skinIdleTick 独立安排，不走 106 动作权重
        if (skinMode) return;
        if (mode == Mode.MOVE || mode == Mode.RUN) return;
        if (peek) {
            playIdle();
            return;
        }
        // 只在原地换动作/转身：桌宠不再自己横向移动，位置只由拖动决定
        int wi = Math.max(0, conf.wIdle), wt = Math.max(0, conf.wTurn);
        int n = rnd.nextInt(Math.max(wi + wt + 80, 1));
        if (n < wi) {
            playIdle();
        } else if (n - wi < wt) {
            playTurn();
        } else {
            playRandomAction();
        }
    }

    public void playAction(String slug) {
        if (slug == null || slug.isEmpty()) return;
        if (!alive || mode == Mode.RUN) return;
        mode = Mode.IDLE;
        dragging = false;
        pressed = false;
        forcedNoMirror = false;
        host.onAction(slug, conf.nameOf(slug));
        play(slug, this::scheduleNext);
    }

    public boolean isPlaying(String slug) {
        AnimatedImageDrawable d = current;
        return slug != null && slug.equals(currentSlug) && d != null && d.isRunning();
    }

    public boolean isDragging() {
        return dragging || mode == Mode.DRAG;
    }

    public boolean isBusy() {
        return mode == Mode.RUN;
    }

    private void onTap() {
        squash(0.55);
        host.onTap();
        if (live2d != null) {
            live2d.tap();
            if (rnd.nextFloat() < 0.6f) live2d.expression(1 + rnd.nextInt(6));
            return;
        }
        if (skinMode) {
            showFace(1 + rnd.nextInt(6), 1300);
            startSkinAction(1);
            return;
        }
        if (conf.clicks.isEmpty()) {
            playIdle();
            return;
        }
        mode = Mode.IDLE;
        forcedNoMirror = false;
        String slug = conf.clicks.get(rnd.nextInt(conf.clicks.size()));
        host.onAction(slug, conf.nameOf(slug));
        play(slug, this::scheduleNext);
    }

    public boolean tick() {
        long ms = SystemClock.uptimeMillis();
        boolean active = false;
        switch (mode) {
            case DRAG: {
                // 1:1 跟手：不再用弹簧去追手指，拖动跟不上手会被误以为卡
                x = dragTargetX;
                y = dragTargetY;
                dragVX = 0;
                dragVY = 0;
                clamp();
                push();
                host.onPosition(x, y, facing);
                view.invalidate();
                if (!isPlaying(currentSlug) && SystemClock.uptimeMillis() - lastDragAnimAt > 900) playDragAnim();
                active = true;
                break;
            }
            case MOVE: {
                double u = Math.max(0, Math.min(1, (ms - moveStart) / moveDuration));
                double e;
                if (u < 0.2) {
                    double t = u / 0.2;
                    e = t * t * (3 - 2 * t) * 0.2;
                } else if (u > 0.8) {
                    double t = (1 - u) / 0.2;
                    e = 1 - (t * t * (3 - 2 * t) * 0.2);
                } else {
                    e = u;
                }
                x = moveFromX + (moveToX - moveFromX) * e;
                push();
                active = u < 1;
                if (u >= 1) {
                    x = moveToX;
                    push();
                    host.onPosition(x, y, facing);
                    mode = Mode.IDLE;
                    playIdle();
                }
                break;
            }
            case RUN: {
                double u = Math.max(0, Math.min(1, (ms - runStart) / (double) runDuration));
                x = runFromX + (runToX - runFromX) * u;
                y = runBaseY - Math.abs(Math.sin(u * Math.PI * 3)) * density * 6;
                push();
                host.onPosition(x, y, facing);
                if (u >= 1) {
                    y = runBaseY;
                    mode = Mode.IDLE;
                    beginPeek(peekSide);
                }
                active = u < 1;
                break;
            }
            default:
                break;
        }
        if (squashActive) {
            if ((ms - squashAt) / 220.0 >= 1) squashActive = false;
            view.invalidate();
            active = true;
        }
        return active;
    }

    private final class PetView extends View implements Drawable.Callback {
        PetView(Pet p, Context c) {
            super(c);
            setWillNotDraw(false);
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            updateTouchableRegion();
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            updateTouchableRegion();
        }

        @Override
        public void invalidateDrawable(Drawable who) {
            invalidate();
        }

        @Override
        public void scheduleDrawable(Drawable who, Runnable what, long when) {
            postDelayed(what, when);
        }

        @Override
        public void unscheduleDrawable(Drawable who, Runnable what) {
            removeCallbacks(what);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            // 窗口被裁成只剩实体宽度，绘制仍按完整可视框计算：向左平移被裁掉的透明边
            canvas.save();
            canvas.translate(-padL, 0);
            drawSelf(canvas, sizePx, getHeight());
            canvas.restore();
        }

        private void drawSelf(Canvas canvas, float w, float h) {
            if (peek) {
                ensurePeekBitmap();
                if (peekBmp != null) {
                    drawPeek(canvas, w, h);
                    return;
                }
            }
            if (skinMode) {
                drawSkin(canvas, w, h);
                return;
            }
            AnimatedImageDrawable d = current;
            if (d == null) return;
            boolean mirror = !forcedNoMirror && facing < 0;
            double scale = 1;
            if (squashActive) {
                scale = Phys.squashScale(Math.max(0, Math.min(1, (SystemClock.uptimeMillis() - squashAt) / 220.0)), squashAmount);
            }
            canvas.save();
            if (dragging) {
                long ms = SystemClock.uptimeMillis();
                float dx = (float) (Math.sin(ms / 150.0) * density * 1.6);
                float dy = (float) (Math.sin(ms / 190.0) * density * 2.6);
                canvas.translate(dx, dy);
                canvas.rotate((float) (Math.sin(ms / 210.0) * 2.2), w / 2f, h);
            }
            canvas.translate(w / 2f, h);
            canvas.scale(mirror ? -1f : 1f, (float) scale);
            canvas.translate(-w / 2f, -h);
            d.setBounds(0, 0, (int) w, (int) h);
            d.draw(canvas);
            canvas.restore();
        }

        /** 立绘：漂浮 + 呼吸 + 走动摆 + 点击小跳，全部程序化。 */
        private void drawSkin(Canvas canvas, float w, float h) {
            Bitmap bmp = skinNow();
            if (bmp == null) return;
            long ms = SystemClock.uptimeMillis();
            boolean mirror = !forcedNoMirror && facing < 0;
            float scale = 1f;
            if (squashActive) {
                scale = (float) Phys.squashScale(Math.max(0, Math.min(1,
                        (ms - squashAt) / 220.0)), squashAmount);
            }
            float bob = (float) (Math.sin(ms / 760.0) * density * 2.2);
            float sway = 0f;
            float hop = 0f;
            if (mode == Mode.MOVE || mode == Mode.RUN) {
                bob = (float) (-Math.abs(Math.sin(ms / 150.0)) * density * 3.4);
                sway = (float) (Math.sin(ms / 140.0) * 2.6);
            }
            long act = ms - skinActionAt;
            if (skinAction == 1 && act < 640) {
                hop -= (float) (Math.sin(act / 640.0 * Math.PI) * density * 12);
            } else if (skinAction == 2 && act < 900) {
                sway = (float) (Math.sin(act / 45.0) * 3.2);
            }
            canvas.save();
            if (dragging) {
                float dx = (float) (Math.sin(ms / 150.0) * density * 1.6);
                float dy = (float) (Math.sin(ms / 190.0) * density * 2.6);
                canvas.translate(dx, dy + bob + hop);
                canvas.rotate((float) (Math.sin(ms / 210.0) * 2.6), w / 2f, h);
            } else {
                canvas.translate(0, bob + hop);
            }
            canvas.rotate(sway, w / 2f, h);
            canvas.translate(w / 2f, h);
            canvas.scale(mirror ? -1f : 1f, scale);
            canvas.translate(-w / 2f, -h);
            skinDst.set(0, 0, w, h);
            canvas.drawBitmap(bmp, null, skinDst, skinPaint);
            canvas.restore();
        }

        private void drawPeek(Canvas canvas, float w, float h) {
            float vw = h * peekBmp.getWidth() / (float) peekBmp.getHeight();
            long ms = SystemClock.uptimeMillis();
            float bob = (float) (Math.sin(ms / 620.0) * density * 2.0);
            float breathe = (float) (1.0 + Math.sin(ms / 820.0) * 0.018);
            float rot = (float) (Math.sin(ms / 960.0) * 1.8);
            float dx = (float) (Math.sin(ms / 1100.0) * density * 1.4);
            canvas.save();
            canvas.translate(peekSide < 0 ? -dx : dx, bob);
            canvas.rotate(rot, w / 2f, h);
            canvas.scale(breathe, breathe, w / 2f, h);
            if (skinMode) {
                // 立绘头块已按方向旋转好，不再镜像
                float left = peekSide < 0 ? 0 : w - vw;
                peekDst.set(left, 0, left + vw, h);
            } else {
                if (peekSide < 0) canvas.scale(-1f, 1f, w / 2f, h / 2f);
                peekDst.set(w - vw, 0, w, h);
            }
            canvas.drawBitmap(peekBmp, null, peekDst, peekPaint);
            canvas.restore();
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            double rx = e.getRawX(), ry = e.getRawY();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    // 整幅动画继续显示动作，但只有站姿剪影本身能触发桌宠。
                    if (!touchesPetBody(e.getX(), e.getY())) return false;
                    exitPeek();
                    downRawX = rx;
                    downRawY = ry;
                    downAt = now();
                    grabDX = e.getX() + padL;
                    grabDY = e.getY();
                    dragging = false;
                    pressed = true;
                    longPressFired = false;
                    removeCallbacks(longRunnable);
                    postDelayed(longRunnable, 450);
                    mode = Mode.IDLE;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    // 长按已经弹出面板后，手指再动不应该把桌宠拖走（横屏时很容易被误拖飘走）
                    if (longPressFired) return true;
                    double slop = density * 5;
                    boolean started = false;
                    if (!dragging && Math.hypot(rx - downRawX, ry - downRawY) > slop && !locked) {
                        dragging = true;
                        pressed = false;
                        started = true;
                        removeCallbacks(longRunnable);
                        playDragAnim();
                    }
                    if (!dragging) return true;
                    mode = Mode.DRAG;
                    dragTargetX = rx - grabDX;
                    dragTargetY = ry - grabDY;
                    dragVX = 0;
                    dragVY = 0;
                    if (dragTargetX > x + 2) {
                        facing = 1;
                        forcedNoMirror = false;
                    } else if (dragTargetX < x - 2) {
                        facing = -1;
                        forcedNoMirror = false;
                    }
                    if (started) {
                        // 起手这一帧直接跟上手指，不等空闲的 500ms 帧循环
                        x = dragTargetX;
                        y = dragTargetY;
                        clamp();
                        push();
                        host.onPosition(x, y, facing);
                        view.invalidate();
                        host.wake();
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    pressed = false;
                    removeCallbacks(longRunnable);
                    // 横屏时按住可能被系统手势抢走事件流，450ms 的计时器会被取消；
                    // 抬起时只要按住够久且没拖动，就补发一次长按。
                    if (!longPressFired && !dragging && downAt > 0
                            && SystemClock.uptimeMillis() - (long) downAt >= 450) {
                        downAt = 0;
                        dragging = false;
                        host.onLongPress();
                        return true;
                    }
                    downAt = 0;
                    if (longPressFired) {
                        longPressFired = false;
                        dragging = false;
                        return true;
                    }
                    if (dragging) {
                        dragging = false;
                        // 只保留拖动：松手就停在原地，不再抛出去，也不再撞墙反弹
                        mode = Mode.IDLE;
                        clamp();
                        push();
                        host.onPosition(x, y, facing);
                        host.onDragEnd();
                        playIdle();
                    } else {
                        long t = SystemClock.uptimeMillis();
                        if (t - lastTapAt > 40 && t - lastTapAt < 380) {
                            lastTapAt = 0;
                            host.onDoubleTap();
                        } else {
                            lastTapAt = t;
                            onTap();
                        }
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    removeCallbacks(longRunnable);
                    if (!longPressFired && !dragging && downAt > 0
                            && SystemClock.uptimeMillis() - (long) downAt >= 450) {
                        downAt = 0;
                        pressed = false;
                        dragging = false;
                        host.onLongPress();
                        return true;
                    }
                    downAt = 0;
                    pressed = false;
                    dragging = false;
                    if (mode == Mode.DRAG) {
                        mode = Mode.IDLE;
                        playIdle();
                    }
                    return true;
                default:
                    return super.onTouchEvent(e);
            }
        }
    }
}
