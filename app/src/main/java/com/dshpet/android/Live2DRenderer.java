package com.dshpet.android;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.opengl.GLUtils;

import com.live2d.sdk.cubism.core.ICubismLogger;
import com.live2d.sdk.cubism.framework.CubismFramework;
import com.live2d.sdk.cubism.framework.CubismFrameworkConfig;
import com.live2d.sdk.cubism.framework.ICubismLoadFileFunction;
import com.live2d.sdk.cubism.framework.math.CubismMatrix44;
import com.live2d.sdk.cubism.framework.rendering.android.CubismOffscreenManagerAndroid;
import com.live2d.sdk.cubism.framework.rendering.android.CubismShaderAndroid;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;

/**
 * Live2D 离屏渲染器：独立线程 + EGL pbuffer + GLES2，把模型画成 Bitmap 交给 PetView。
 * 外面只看 frame()——拿到的最新一帧，可以在 UI 线程直接 drawBitmap。
 *
 * 做成离屏的原因：桌宠窗口是普通的悬浮 View，不适合塞 GLSurfaceView（透明、层级、
 * 生命周期都不可控），回读成 Bitmap 后原来的绘制/拖动/缩放逻辑一行都不用改。
 */
public final class Live2DRenderer {
    /** Core 的 .so 只有 arm64/x86/x86_64，32 位 ARM 设备直接降级。 */
    private static final boolean LOADED = loadCore();

    private static final String SHADER_PREFIX = "com/live2d/sdk/cubism/framework/shaders/";
    private static final Object FW_LOCK = new Object();
    private static Context appCtx;
    private static boolean frameworkReady;

    private static final int TARGET_FPS = 30;
    private static final int MAX_RENDER_HEIGHT = 640;
    private static volatile boolean memoryGraphVisible;

    /** 识海占用前台 GPU 时临时降低桌宠离屏帧率，离开或 Activity 暂停后恢复。 */
    public static void setMemoryGraphVisible(boolean visible) {
        memoryGraphVisible = visible;
    }

    /**
     * 导入时的缩略图渲染和桌宠渲染共用 Cubism 的 shader / 离屏 FBO 单例，
     * 而两者各自有独立的 EGL 上下文，单例不能同时被两个上下文使用。
     * 所以截图期间让正在跑的桌宠渲染线程先停帧，截图结束后它会自动重建 shader。
     */
    private static volatile int externalHold;

    private final File dir;
    private final String model3;
    private final Object frameLock = new Object();

    private volatile Bitmap front;
    private volatile Bitmap back;
    private volatile ByteBuffer pixels;
    private volatile int rw;
    private volatile int rh;
    private volatile int reqW;
    private volatile int reqH;
    private volatile boolean sizeDirty;
    private volatile boolean running = true;
    private volatile boolean released;
    private volatile boolean ready;
    private volatile boolean failed;
    /** 桌宠常驻渲染线程需要给截图让路；截图自己的渲染线程不受 externalHold 影响。 */
    private volatile boolean pausable = true;
    private volatile float canvasRatio = 1.5f;

    private Thread thread;
    private volatile Live2DModel model;
    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;
    private EGLConfig eglConfig;

    public Live2DRenderer(Context ctx, File dir, String model3) {
        this.dir = dir;
        this.model3 = model3;
        if (ctx != null) appCtx = ctx.getApplicationContext();
    }

    public static boolean supported() {
        return LOADED;
    }

    private static boolean loadCore() {
        try {
            System.loadLibrary("Live2DCubismCoreJNI");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 启动渲染线程。w/h 是桌宠窗口像素尺寸（会按上限等比缩小，保证回读开销可控）。 */
    public void start(int w, int h) {
        int[] s = fit(w, h);
        reqW = s[0];
        reqH = s[1];
        if (!LOADED) {
            failed = true;
            return;
        }
        if (thread != null) return;
        thread = new Thread(this::loop, "dsh-live2d");
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        thread.start();
    }

    /** 窗口尺寸变了：下一次循环里重建 pbuffer。 */
    public void setSize(int w, int h) {
        int[] s = fit(w, h);
        if (s[0] == reqW && s[1] == reqH) return;
        reqW = s[0];
        reqH = s[1];
        sizeDirty = true;
    }

    /** 最新一帧；还没准备好时返回 null。返回的 Bitmap 由渲染器持有，不要 recycle。 */
    public Bitmap frame() {
        synchronized (frameLock) {
            return front;
        }
    }

    public boolean isReady() {
        return ready;
    }

    public boolean isFailed() {
        return failed;
    }

    public float canvasRatio() {
        return canvasRatio;
    }

    /* ---------------- 动作代理（UI 线程只记命令，渲染线程执行，避免动 motionManager） ---------------- */

    private volatile int cmdMotion;
    private volatile String cmdPlay;
    private volatile int cmdExpression = -1;

    public void idle() {
        cmdMotion = 1;
    }

    public void tap() {
        cmdMotion = 2;
    }

    public void playByName(String name) {
        cmdPlay = name;
    }

    public void expression(int index) {
        cmdExpression = index;
    }

    public void look(float x, float y) {
        cmdLookX = x;
        cmdLookY = y;
        hasCmdLook = true;
    }

    private volatile float cmdLookX, cmdLookY;
    private volatile boolean hasCmdLook;

    private void applyCommands() {
        Live2DModel m = model;
        if (m == null) return;
        int motion = cmdMotion;
        cmdMotion = 0;
        if (motion == 1) m.idle();
        else if (motion == 2) m.tap();
        String play = cmdPlay;
        cmdPlay = null;
        if (play != null) m.playByName(play);
        int expr = cmdExpression;
        cmdExpression = -1;
        if (expr >= 0) m.expression(expr);
        if (hasCmdLook) {
            hasCmdLook = false;
            m.look(cmdLookX, cmdLookY);
        }
    }

    public void release() {
        running = false;
        released = true;
        Thread t = thread;
        if (t != null && t != Thread.currentThread()) {
            try {
                t.join(1500);
            } catch (Throwable ignored) {
            }
        }
        thread = null;
        synchronized (frameLock) {
            front = null;
            back = null;
        }
    }

    /* ---------------- 渲染线程 ---------------- */

    private void loop() {
        try {
            if (!ensureFramework()) throw new IllegalStateException("Live2D 初始化失败");
            initEgl(reqW, reqH);
            rw = reqW;
            rh = reqH;
            createTargets();
            model = new Live2DModel();
            model.load(dir, model3, rw, rh);
            canvasRatio = model.canvasRatio();
        } catch (Throwable t) {
            failed = true;
            diag("live2d init " + t);
            shutdownEgl();
            return;
        }
        CubismMatrix44 projection = CubismMatrix44.create();
        long last = System.nanoTime();
        try {
            while (running) {
                // 截图线程正在用 GL：先停帧，保住它画到一半时候的单例状态
                if (pausable && externalHold > 0) {
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException e) {
                        break;
                    }
                    last = System.nanoTime();
                    continue;
                }
                if (sizeDirty) {
                    sizeDirty = false;
                    try {
                        resizeEgl();
                    } catch (Throwable t) {
                        failed = true;
                        break;
                    }
                }
                long now = System.nanoTime();
                float dt = Math.min(0.05f, (now - last) / 1_000_000_000f);
                last = now;
                try {
                    applyCommands();
                    renderOnce(projection, Math.max(0.001f, dt));
                } catch (Throwable t) {
                    failed = true;
                    diag("live2d frame " + t);
                    break;
                }
                long used = System.nanoTime() - now;
                int targetFps = memoryGraphVisible ? 15 : TARGET_FPS;
                long sleep = 1_000_000_000L / targetFps - used;
                if (sleep > 1_000_000L) {
                    try {
                        Thread.sleep(sleep / 1_000_000L);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }
        } finally {
            if (model != null) {
                model.close();
                model = null;
            }
            shutdownEgl();
        }
    }

    private void renderOnce(CubismMatrix44 projection, float dt) {
        CubismOffscreenManagerAndroid.getInstance().beginFrameProcess();
        GLES20.glViewport(0, 0, rw, rh);
        GLES20.glClearColor(0f, 0f, 0f, 0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

        model.update(dt);
        model.draw(projection, rw, rh);

        CubismOffscreenManagerAndroid.getInstance().endFrameProcess();
        CubismOffscreenManagerAndroid.getInstance().releaseStaleRenderTextures();

        ByteBuffer buf = pixels;
        Bitmap bmp = back;
        if (buf == null || bmp == null) return;
        buf.rewind();
        GLES20.glReadPixels(0, 0, rw, rh, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf);
        buf.rewind();
        bmp.copyPixelsFromBuffer(buf);
        synchronized (frameLock) {
            Bitmap old = front;
            front = bmp;
            back = old;
        }
        ready = true;
    }

    private void createTargets() {
        pixels = ByteBuffer.allocateDirect(rw * rh * 4);
        synchronized (frameLock) {
            // 两张都要建：回读在 back 上做，画完再和 front 交换；
            // 只建一张的话第二次循环 back 变 null，之后就再也不刷新了。
            front = Bitmap.createBitmap(rw, rh, Bitmap.Config.ARGB_8888);
            back = Bitmap.createBitmap(rw, rh, Bitmap.Config.ARGB_8888);
        }
        ready = false;
    }

    private void resizeEgl() {
        int w = reqW, h = reqH;
        EGLSurface surface = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig,
                new int[]{EGL14.EGL_WIDTH, w, EGL14.EGL_HEIGHT, h, EGL14.EGL_NONE}, 0);
        if (surface == EGL14.EGL_NO_SURFACE) throw new IllegalStateException("pbuffer 创建失败");
        EGL14.eglMakeCurrent(eglDisplay, surface, surface, eglContext);
        if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface);
        eglSurface = surface;
        rw = w;
        rh = h;
        createTargets();
    }

    /* ---------------- EGL ---------------- */

    private void initEgl(int w, int h) {
        EGLDisplay display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (display == EGL14.EGL_NO_DISPLAY) throw new IllegalStateException("eglGetDisplay 失败");
        int[] ver = new int[2];
        if (!EGL14.eglInitialize(display, ver, 0, ver, 1)) throw new IllegalStateException("eglInitialize 失败");
        eglDisplay = display;

        EGLConfig config = chooseConfig(display, true);
        if (config == null) config = chooseConfig(display, false);
        if (config == null) throw new IllegalStateException("没有可用的 EGL 配置");
        eglConfig = config;

        EGLContext context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
        if (context == EGL14.EGL_NO_CONTEXT) throw new IllegalStateException("eglCreateContext 失败");
        eglContext = context;

        EGLSurface surface = EGL14.eglCreatePbufferSurface(display, config,
                new int[]{EGL14.EGL_WIDTH, Math.max(1, w), EGL14.EGL_HEIGHT, Math.max(1, h), EGL14.EGL_NONE}, 0);
        if (surface == EGL14.EGL_NO_SURFACE) throw new IllegalStateException("eglCreatePbufferSurface 失败");
        eglSurface = surface;

        if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
            throw new IllegalStateException("eglMakeCurrent 失败");
        }

        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glDisable(GLES20.GL_CULL_FACE);
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);

        resetSharedGlState();
    }

    /**
     * Cubism 的 shader / 离屏 FBO 都是进程级单例，且和 GL 上下文绑定。
     * 换上下文（新建渲染器、截图渲染器启动）时必须先扔掉，让下一次 getInstance() 在当前上下文里重建。
     */
    private static void resetSharedGlState() {
        try {
            CubismShaderAndroid.deleteInstance();
            CubismShaderAndroid.getInstance();
        } catch (Throwable ignored) {
        }
        try {
            CubismOffscreenManagerAndroid.releaseInstance();
            CubismOffscreenManagerAndroid.getInstance();
        } catch (Throwable ignored) {
        }
    }

    private static EGLConfig chooseConfig(EGLDisplay display, boolean alpha) {
        int[] attrs = alpha
                ? new int[]{
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE}
                : new int[]{
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_NONE};
        EGLConfig[] configs = new EGLConfig[1];
        int[] count = new int[1];
        if (!EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, count, 0)) return null;
        return count[0] > 0 ? configs[0] : null;
    }

    private void shutdownEgl() {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) return;
        // 上下文还在当前线程上时清理单例；另一个渲染器下次会用自己的上下文重建
        try {
            CubismShaderAndroid.deleteInstance();
        } catch (Throwable ignored) {
        }
        try {
            CubismOffscreenManagerAndroid.releaseInstance();
        } catch (Throwable ignored) {
        }
        EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
        if (eglSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(eglDisplay, eglSurface);
            eglSurface = EGL14.EGL_NO_SURFACE;
        }
        if (eglContext != EGL14.EGL_NO_CONTEXT) {
            EGL14.eglDestroyContext(eglDisplay, eglContext);
            eglContext = EGL14.EGL_NO_CONTEXT;
        }
        EGL14.eglTerminate(eglDisplay);
        eglDisplay = EGL14.EGL_NO_DISPLAY;
        pixels = null;
        synchronized (frameLock) {
            front = null;
            back = null;
        }
    }

    /* ---------------- Cubism 框架 ---------------- */

    private static boolean ensureFramework() {
        if (!LOADED) return false;
        synchronized (FW_LOCK) {
            if (frameworkReady) return true;
            try {
                CubismFramework.Option option = new CubismFramework.Option();
                option.logFunction = new ICubismLogger() {
                    @Override
                    public void print(String message) {
                    }
                };
                option.loggingLevel = CubismFrameworkConfig.LogLevel.ERROR;
                option.loadFileFunction = new ICubismLoadFileFunction() {
                    @Override
                    public byte[] load(String path) {
                        return loadAssetOrFile(path);
                    }
                };
                CubismFramework.cleanUp();
                CubismFramework.startUp(option);
                CubismFramework.initialize();
                frameworkReady = true;
                return true;
            } catch (Throwable t) {
                diag("live2d framework " + t);
                return false;
            }
        }
    }

    /** shader 从 assets 读，模型文件是绝对路径，直接读 File。 */
    private static byte[] loadAssetOrFile(String path) {
        if (path == null) return null;
        if (path.startsWith(SHADER_PREFIX) && appCtx != null) {
            try (InputStream in = appCtx.getAssets().open(path)) {
                return readAll(in);
            } catch (Throwable t) {
                return null;
            }
        }
        return readFile(new File(path));
    }

    static byte[] readFile(File f) {
        if (f == null || !f.isFile()) return null;
        try (InputStream in = new FileInputStream(f)) {
            return readAll(in);
        } catch (Throwable t) {
            return null;
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        while ((r = in.read(buf)) > 0) bo.write(buf, 0, r);
        return bo.toByteArray();
    }

    /* ---------------- 纹理 ---------------- */

    /** 在当前 GL 上下文里建纹理；失败返回 0。 */
    static int loadTexture(String path) {
        Bitmap b;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            o.inPremultiplied = true;
            b = BitmapFactory.decodeFile(path, o);
        } catch (Throwable t) {
            return 0;
        }
        if (b == null) return 0;
        int[] ids = new int[1];
        try {
            GLES20.glGenTextures(1, ids, 0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0]);
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0);
            GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        } catch (Throwable t) {
            return 0;
        } finally {
            try {
                b.recycle();
            } catch (Throwable ignored) {
            }
        }
        return ids[0];
    }

    private static void diag(String text) {
        if (appCtx == null) return;
        try {
            Sg.write(new File(Sg.dir(appCtx), "diag.txt"), Sg.now() + " " + text + "\n");
        } catch (Throwable ignored) {
        }
    }

    private static int[] fit(int w, int h) {
        w = Math.max(64, w);
        h = Math.max(64, h);
        if (h > MAX_RENDER_HEIGHT) {
            w = Math.max(1, Math.round(w * (MAX_RENDER_HEIGHT / (float) h)));
            h = MAX_RENDER_HEIGHT;
        }
        return new int[]{w, h};
    }

    /* ---------------- 导入时截一张缩略图 ---------------- */

    /**
     * 在后台线程载入模型、渲染一帧、裁掉透明边当缩略图。
     * ratioOut[0] 回传画布高宽比，用来决定桌宠窗口高度。
     */
    public static Bitmap snapshot(Context ctx, File dir, String model3, float[] ratioOut) {
        if (!LOADED || dir == null) return null;
        // 先让正在跑的桌宠渲染线程停帧，避免两个 GL 上下文抢同一套 Cubism 单例
        externalHold++;
        // 给正在跑的那一帧一点时间收尾，别在它画到一半时把 shader 单例删掉
        try {
            Thread.sleep(60);
        } catch (InterruptedException ignored) {
        }
        Live2DRenderer r = new Live2DRenderer(ctx, dir, model3);
        r.pausable = false;
        Bitmap out = null;
        try {
            r.start(512, 512);
            long end = System.currentTimeMillis() + 5000;
            // 必须等第一次真正画完（ready），否则拿到的是刚分配的空 Bitmap
            while (System.currentTimeMillis() < end && !r.failed) {
                Bitmap b = r.frame();
                if (b != null && r.isReady()) {
                    out = b.copy(Bitmap.Config.ARGB_8888, false);
                    break;
                }
                try {
                    Thread.sleep(60);
                } catch (InterruptedException e) {
                    break;
                }
            }
            if (ratioOut != null && ratioOut.length > 0) ratioOut[0] = r.canvasRatio();
        } finally {
            r.release();
            externalHold--;
        }
        return trim(out);
    }

    private static Bitmap trim(Bitmap src) {
        if (src == null) return null;
        int w = src.getWidth(), h = src.getHeight();
        int[] px = new int[w * h];
        src.getPixels(px, 0, w, 0, 0, w, h);
        int minX = w, minY = h, maxX = -1, maxY = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if ((px[y * w + x] >>> 24) > 12) {
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }
        if (maxX < minX || maxY < minY) return src;
        Bitmap out = Bitmap.createBitmap(src, minX, minY, maxX - minX + 1, maxY - minY + 1);
        if (out != src) src.recycle();
        return out;
    }
}
