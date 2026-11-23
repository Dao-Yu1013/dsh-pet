package com.dshpet.android;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import org.json.JSONObject;

/** 无障碍：广告跳过 + 当前应用/屏幕文字情境。 */
public final class A11y extends AccessibilityService {
    private static volatile A11y instance;
    private static volatile boolean adEnabled;
    private static volatile boolean screenCtx;
    private static volatile int screenScanSec = 3;
    private static volatile long lastPhoneInteractionAt;
    private static volatile long ignoreAutomationEventsUntil;
    private long lastTextAt;
    private String lastPkg = "";

    public static A11y get() {
        return instance;
    }

    public interface ShotCb {
        void ok(Bitmap bmp);

        void fail(String msg);
    }

    /** Android 11+ 通过无障碍直接截屏，无需 MediaProjection 授权。 */
    public static void screenshot(ShotCb cb) {
        shot(cb, 0, true);
    }

    /** Capture while the caller keeps its own overlay hidden through visual analysis. */
    public static void screenshotWhileOverlayHidden(ShotCb cb) {
        shot(cb, 0, false);
    }

    /** 截屏偶发失败时重试一次：系统有最小间隔限制，连续截图容易报错。 */
    private static void shot(final ShotCb cb, final int attempt, final boolean manageOverlay) {
        if (cb == null) return;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            new Handler(Looper.getMainLooper()).post(() -> shot(cb, attempt, manageOverlay));
            return;
        }
        final A11y a = instance;
        if (a == null) {
            cb.fail("请先在权限体检里开启无障碍服务");
            return;
        }
        if (Build.VERSION.SDK_INT < 30) {
            cb.fail("系统版本过低，无法截屏");
            return;
        }
        final boolean restoreOverlay = manageOverlay && attempt == 0
                && PetSvc.setChatOverlayVisibleForObservation(false);
        Runnable capture = () -> takeShot(a, cb, attempt, restoreOverlay, manageOverlay);
        if (restoreOverlay) new Handler(Looper.getMainLooper()).postDelayed(capture, 300L);
        else capture.run();
    }

    private static void takeShot(final A11y a, final ShotCb cb, final int attempt,
                                 final boolean restoreOverlay, final boolean manageOverlay) {
        int displayId = Display.DEFAULT_DISPLAY;
        try {
            Display d = a.getDisplay();
            if (d != null) displayId = d.getDisplayId();
        } catch (Throwable ignored) {
        }
        try {
            a.takeScreenshot(displayId, a.getMainExecutor(),
                    new AccessibilityService.TakeScreenshotCallback() {
                        @Override
                        public void onSuccess(AccessibilityService.ScreenshotResult result) {
                            Bitmap copy = null;
                            HardwareBuffer hb = null;
                            try {
                                hb = result.getHardwareBuffer();
                                Bitmap bmp = Bitmap.wrapHardwareBuffer(hb, result.getColorSpace());
                                if (bmp != null) {
                                    copy = bmp.copy(Bitmap.Config.ARGB_8888, false);
                                }
                            } catch (Throwable ignored) {
                            } finally {
                                try {
                                    if (hb != null) hb.close();
                                } catch (Throwable ignored) {
                                }
                            }
                            restoreObservedOverlay(restoreOverlay);
                            if (copy == null) cb.fail("截屏失败");
                            else cb.ok(copy);
                        }

                        @Override
                        public void onFailure(int errorCode) {
                            if (attempt < 1 && retryable(errorCode)) {
                                new Handler(Looper.getMainLooper()).postDelayed(
                                        () -> takeShot(a, cb, attempt + 1, restoreOverlay, manageOverlay), 650L);
                            } else {
                                restoreObservedOverlay(restoreOverlay);
                                cb.fail("截屏失败（" + errorCode + "）");
                            }
                        }
                    });
        } catch (Throwable t) {
            restoreObservedOverlay(restoreOverlay);
            cb.fail("截屏失败");
        }
    }

    private static void restoreObservedOverlay(boolean restore) {
        if (!restore) return;
        new Handler(Looper.getMainLooper()).postDelayed(
                () -> PetSvc.setChatOverlayVisibleForObservation(true), 120L);
    }

    private static boolean retryable(int code) {
        return code == ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR
                || code == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT
                || code == ERROR_TAKE_SCREENSHOT_INVALID_WINDOW;
    }

    /* ---------------- 手势与应用操作 ---------------- */

    /** 无障碍服务是否已连上：手势和截屏都依赖它。 */
    public static boolean ready() {
        return instance != null;
    }

    /** Ignore the accessibility events generated by the current self action, not the user's next touch. */
    public static void markAutonomousAction() {
        ignoreAutomationEventsUntil = System.currentTimeMillis() + 1800L;
    }

    /** 轻点屏幕坐标。 */
    public static boolean tap(float x, float y) {
        Path p = new Path();
        p.moveTo(x, y);
        return gesture(p, 1);
    }

    /** 滑动：用于短视频上滑、小说翻页这类轻操作。 */
    public static boolean swipe(float x1, float y1, float x2, float y2, long ms) {
        Path p = new Path();
        p.moveTo(x1, y1);
        p.lineTo(x2, y2);
        return gesture(p, Math.max(80L, ms));
    }

    private static boolean gesture(Path p, long ms) {
        A11y a = instance;
        if (a == null || Build.VERSION.SDK_INT < 24) return false;
        final java.util.concurrent.atomic.AtomicBoolean accepted =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        Runnable dispatch = () -> {
            boolean hidden = PetSvc.setChatOverlayVisibleForObservation(false);
            try {
                GestureDescription.Builder b = new GestureDescription.Builder();
                b.addStroke(new GestureDescription.StrokeDescription(p, 0, ms));
                boolean ok = a.dispatchGesture(b.build(), new GestureResultCallback() {
                    @Override public void onCompleted(GestureDescription gestureDescription) {
                        restoreObservedOverlay(hidden);
                    }
                    @Override public void onCancelled(GestureDescription gestureDescription) {
                        restoreObservedOverlay(hidden);
                    }
                }, new Handler(Looper.getMainLooper()));
                accepted.set(ok);
                if (!ok) restoreObservedOverlay(hidden);
            } catch (Throwable t) {
                restoreObservedOverlay(hidden);
            } finally {
                done.countDown();
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            // Let the IME finish closing after the floating chat is hidden before swiping.
            new Handler(Looper.getMainLooper()).postDelayed(dispatch, 220L);
            return true;
        }
        new Handler(Looper.getMainLooper()).postDelayed(dispatch, 220L);
        try {
            done.await(1200L, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return accepted.get();
    }

    public static boolean back() {
        A11y a = instance;
        if (a == null) return false;
        try {
            return a.performGlobalAction(GLOBAL_ACTION_BACK);
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean home() {
        A11y a = instance;
        if (a == null) return false;
        try {
            return a.performGlobalAction(GLOBAL_ACTION_HOME);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 打开指定应用；本应用有悬浮窗权限，后台也能正常拉起。 */
    public static boolean openApp(Context c, String pkg) {
        if (c == null || pkg == null || pkg.isEmpty()) return false;
        try {
            Intent i = c.getPackageManager().getLaunchIntentForPackage(pkg);
            if (i == null) return false;
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            c.startActivity(i);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 按文字点一下：逐级向上找可点击节点，找不到就点文字节点本身。
     * 只用于「换一换」「下一页」这类明确文字入口，不做点赞评论。
     */
    public static boolean tapText(String text) {
        A11y a = instance;
        if (a == null || text == null || text.isEmpty()) return false;
        try {
            AccessibilityNodeInfo root = a.getRootInActiveWindow();
            if (root == null) return false;
            java.util.List<AccessibilityNodeInfo> hits = root.findAccessibilityNodeInfosByText(text);
            if (hits == null) return false;
            for (AccessibilityNodeInfo hit : hits) {
                AccessibilityNodeInfo n = hit;
                for (int up = 0; n != null && up < 12; up++) {
                    try {
                        if (n.isClickable() && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                            return true;
                        }
                        n = n.getParent();
                    } catch (Throwable ignored) {
                        break;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * Remote-agent variant: only click one exact, currently visible label in the expected app.
     * This deliberately rejects ambiguous matches and never accepts coordinates from a server.
     */
    public static boolean tapTextExact(String visibleText, String expectedPackage) {
        A11y a = instance;
        String wanted = visibleText == null ? "" : visibleText.trim();
        String expected = expectedPackage == null ? "" : expectedPackage.trim();
        if (a == null || wanted.isEmpty() || expected.isEmpty()
                || wanted.length() > 64 || !expected.equals(foregroundPackage())) return false;
        try {
            AccessibilityNodeInfo root = a.getRootInActiveWindow();
            if (root == null) return false;
            CharSequence rootPackage = root.getPackageName();
            if (rootPackage == null || !expected.equals(rootPackage.toString())) return false;
            java.util.List<AccessibilityNodeInfo> hits = root.findAccessibilityNodeInfosByText(wanted);
            if (hits == null || hits.isEmpty()) return false;
            java.util.Map<String, AccessibilityNodeInfo> candidates = new java.util.LinkedHashMap<>();
            for (AccessibilityNodeInfo hit : hits) {
                if (hit == null || !wanted.equals(nodeLabel(hit))) continue;
                AccessibilityNodeInfo node = hit;
                for (int up = 0; node != null && up < 10; up++) {
                    CharSequence pkg = node.getPackageName();
                    if (pkg != null && !expected.equals(pkg.toString())) break;
                    if (node.isClickable()) {
                        android.graphics.Rect bounds = new android.graphics.Rect();
                        node.getBoundsInScreen(bounds);
                        String key = bounds.flattenToString() + "|" + node.getClassName();
                        candidates.put(key, node);
                        break;
                    }
                    node = node.getParent();
                }
            }
            if (candidates.size() != 1 || !expected.equals(foregroundPackage())) return false;
            AccessibilityNodeInfo target = candidates.values().iterator().next();
            return target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String nodeLabel(AccessibilityNodeInfo node) {
        if (node == null) return "";
        CharSequence label = node.getText();
        if (label == null || label.length() == 0) label = node.getContentDescription();
        return label == null ? "" : label.toString().trim();
    }

    /** A narrow remote scroll primitive; direction and distance are fixed locally. */
    public static boolean swipeVertical(String direction, String expectedPackage) {
        A11y a = instance;
        String expected = expectedPackage == null ? "" : expectedPackage.trim();
        String dir = direction == null ? "" : direction.trim().toLowerCase(java.util.Locale.ROOT);
        if (a == null || expected.isEmpty() || (!"up".equals(dir) && !"down".equals(dir))
                || !expected.equals(foregroundPackage())) return false;
        try {
            android.util.DisplayMetrics metrics = a.getResources().getDisplayMetrics();
            float x = Math.max(1, metrics.widthPixels) * 0.5f;
            float top = Math.max(1, metrics.heightPixels) * 0.28f;
            float bottom = Math.max(1, metrics.heightPixels) * 0.72f;
            if ("up".equals(dir)) return swipe(x, bottom, x, top, 380L);
            return swipe(x, top, x, bottom, 380L);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 取当前应用窗口里的文字，供截屏提问时作为图片内容参考。 */
    public static String currentText() {
        A11y a = instance;
        if (a == null) return Bus.screenText;
        try {
            java.util.List<AccessibilityWindowInfo> ws = a.getWindows();
            if (ws != null) {
                for (AccessibilityWindowInfo w : ws) {
                    if (w == null || w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                    AccessibilityNodeInfo root = w.getRoot();
                    if (root == null) continue;
                    CharSequence p = root.getPackageName();
                    if (p != null && p.toString().equals(a.getPackageName())) continue;
                    StringBuilder all = new StringBuilder();
                    // 先定向找链接/网盘等关键词，避免长页面被采集上限截断
                    String[] keys = {"http", "链接", "提取码", "网盘", "网址"};
                    for (String key : keys) {
                        try {
                            java.util.List<AccessibilityNodeInfo> hits = root.findAccessibilityNodeInfosByText(key);
                            if (hits == null) continue;
                            for (AccessibilityNodeInfo hit : hits) {
                                CharSequence t = hit.getText();
                                if (t == null) t = hit.getContentDescription();
                                if (t == null || t.length() == 0) continue;
                                String s = t.toString();
                                if (all.indexOf(s) >= 0) continue;
                                all.append(s).append(' ');
                                if (all.length() > 1000) break;
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                    StringBuilder sb = new StringBuilder();
                    a.collect(root, sb, 0, new int[]{0},
                            READ_MAX_DEPTH, READ_MAX_NODES, READ_MAX_CHARS, new java.util.HashSet<>());
                    String normal = sb.toString().trim();
                    if (!normal.isEmpty()) {
                        if (all.length() > 0) all.append('\n');
                        all.append(normal);
                    }
                    String t = all.toString().trim();
                    if (!t.isEmpty()) return t.length() > 2400 ? t.substring(0, 2400) : t;
                }
            }
        } catch (Throwable ignored) {
        }
        return Bus.screenText;
    }

    /** 当前无障碍活动窗口所属应用；无法确认时返回空串，供插件验证目标应用。 */
    public static String foregroundPackage() {
        A11y a = instance;
        if (a == null) return "";
        try {
            AccessibilityNodeInfo root = a.getRootInActiveWindow();
            if (root != null) {
                CharSequence pkg = root.getPackageName();
                String active = pkg == null ? "" : pkg.toString();
                if (!active.isEmpty() && !active.equals(a.getPackageName())) return active;
            }
        } catch (Throwable ignored) {
        }
        // 悬浮聊天框可能成为 active window；此时退回无障碍事件最近观察到的外部应用。
        String observed = Bus.currentPackage;
        return observed == null || observed.equals(a.getPackageName()) ? "" : observed;
    }

    /**
     * 当前界面有哪些能点的入口：每行「文字｜是否可点｜中心坐标」。
     * 插件用来自主判断登录页、青少年模式、广告弹窗该点「关闭」还是「返回」。
     */
    public static String uiTexts() {
        A11y a = instance;
        if (a == null) return "";
        String targetPackage = foregroundPackage();
        StringBuilder sb = new StringBuilder();
        try {
            java.util.List<AccessibilityWindowInfo> ws = a.getWindows();
            if (ws == null) return "";
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (AccessibilityWindowInfo w : ws) {
                if (w == null) continue;
                int type = w.getType();
                // Ignore DSH's own floating chat and the IME/system windows. They can cover
                // the target app and otherwise dominate the text returned to a plugin.
                if (type != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                AccessibilityNodeInfo root = w.getRoot();
                if (root == null) continue;
                CharSequence pkg = root.getPackageName();
                String windowPackage = pkg == null ? "" : pkg.toString();
                if (windowPackage.isEmpty() || windowPackage.equals(a.getPackageName())) continue;
                if (!targetPackage.isEmpty() && !targetPackage.equals(windowPackage)) continue;
                uiCollect(root, sb, 0, new int[]{0}, seen);
                if (sb.length() > 3000) break;
            }
        } catch (Throwable ignored) {
        }
        return sb.toString().trim();
    }

    /** Remote-agent screen reader: text only, with no bounds, clickability, or icon coordinates. */
    public static String visibleTextForRemote(String expectedPackage) {
        A11y a = instance;
        String expected = expectedPackage == null ? "" : expectedPackage.trim();
        if (a == null || expected.isEmpty() || !expected.equals(foregroundPackage())) return "";
        StringBuilder out = new StringBuilder();
        try {
            java.util.List<AccessibilityWindowInfo> windows = a.getWindows();
            if (windows == null) return "";
            java.util.Set<String> seen = new java.util.LinkedHashSet<>();
            int[] count = {0};
            for (AccessibilityWindowInfo window : windows) {
                if (window == null || window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                AccessibilityNodeInfo root = window.getRoot();
                if (root == null) continue;
                CharSequence packageName = root.getPackageName();
                if (packageName == null || !expected.equals(packageName.toString())) continue;
                remoteTextCollect(root, out, seen, count, 0);
                if (out.length() >= 1800 || count[0] >= 120) break;
            }
            if (!expected.equals(foregroundPackage())) return "";
        } catch (Throwable ignored) {
            return "";
        }
        return out.toString().trim();
    }

    private static void remoteTextCollect(AccessibilityNodeInfo node, StringBuilder out,
                                          java.util.Set<String> seen, int[] count, int depth) {
        if (node == null || depth > 24 || count[0] >= 120 || out.length() >= 1800) return;
        count[0]++;
        try {
            if (node.isVisibleToUser() && !node.isPassword()) {
                CharSequence label = node.getText();
                if (label == null || label.toString().trim().isEmpty()) label = node.getContentDescription();
                String text = label == null ? "" : label.toString().trim()
                        .replace('\r', ' ').replace('\n', ' ');
                if (text.length() > 160) text = text.substring(0, 160);
                if (!text.isEmpty() && seen.add(text)) {
                    int remaining = 1800 - out.length();
                    if (remaining > 0) out.append(text, 0, Math.min(text.length(), remaining)).append('\n');
                }
            }
            for (int i = 0, children = node.getChildCount(); i < children && count[0] < 120; i++)
                remoteTextCollect(node.getChild(i), out, seen, count, depth + 1);
        } catch (Throwable ignored) { }
    }

    private static void uiCollect(AccessibilityNodeInfo n, StringBuilder sb, int depth, int[] count,
                                  java.util.Set<String> seen) {
        if (n == null || depth > 24 || count[0] > 90 || sb.length() > 3000) return;
        count[0]++;
        try {
            if (n.isVisibleToUser() && !n.isPassword()) {
                CharSequence t = n.getText();
                if (t == null || t.toString().trim().isEmpty()) t = n.getContentDescription();
                String s = t == null ? "" : t.toString().trim().replace('\n', ' ');
                boolean click = n.isClickable();
                AccessibilityNodeInfo cur = n;
                for (int up = 0; !click && cur != null && up < 4; up++) {
                    cur = cur.getParent();
                    if (cur != null && cur.isClickable()) click = true;
                }
                android.graphics.Rect r = new android.graphics.Rect();
                n.getBoundsInScreen(r);
                if (s.length() > 40) s = s.substring(0, 40);
                // 没文字的纯图标只在可点且尺寸正常时收进来，用来找关闭/返回按钮
                if (s.isEmpty() && !click) {
                    // 跳过纯布局节点
                } else if (s.isEmpty()) {
                    if (!r.isEmpty() && r.width() > 8 && r.height() > 8) {
                        String key = "icon:" + (r.centerX() / 40) + ":" + (r.centerY() / 40);
                        if (seen.add(key)) {
                            sb.append("图标|1|").append(r.centerX()).append(',').append(r.centerY()).append('\n');
                        }
                    }
                } else {
                    String key = s.length() > 16 ? s.substring(0, 16) : s;
                    if (seen.add(key)) {
                        sb.append(s).append('|').append(click ? 1 : 0).append('|')
                                .append(r.isEmpty() ? "-1,-1" : r.centerX() + "," + r.centerY())
                                .append('\n');
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        int c = 0;
        try {
            c = n.getChildCount();
        } catch (Throwable ignored) {
        }
        for (int i = 0; i < c; i++) {
            try {
                uiCollect(n.getChild(i), sb, depth + 1, count, seen);
            } catch (Throwable ignored) {
            }
        }
    }

    public static void reload() {
        A11y a = instance;
        if (a == null) return;
        Cfg c = Cfg.load(a);
        adEnabled = c.b("adEnabled", false);
        screenCtx = c.b("screenContext", true);
        // 屏幕识别下限 3 秒，再短会明显耗电
        screenScanSec = Math.max(3, Math.min(30, c.i("screenScanSec", 3)));
        AdRules.load(a);
    }

    /** 只有亮屏且没锁屏时才允许读屏幕文字；锁屏/灭屏不识别、不计时。 */
    public static boolean awake(Context c) {
        try {
            android.os.PowerManager pm = (android.os.PowerManager)
                    c.getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isInteractive()) return false;
            android.app.KeyguardManager km = (android.app.KeyguardManager)
                    c.getSystemService(Context.KEYGUARD_SERVICE);
            return km == null || !km.isKeyguardLocked();
        } catch (Throwable t) {
            return true;
        }
    }

    /** 前台是否真的开着某个应用；桌面/锁屏不算，避免在桌面上定时读图标文字。 */
    public static boolean hasForegroundApp(Context c) {
        String pkg = Bus.currentPackage;
        if (pkg == null || pkg.isEmpty()) return false;
        try {
            android.content.Intent home = new android.content.Intent(android.content.Intent.ACTION_MAIN)
                    .addCategory(android.content.Intent.CATEGORY_HOME);
            android.content.pm.ResolveInfo ri = c.getPackageManager().resolveActivity(home, 0);
            if (ri != null && ri.activityInfo != null && pkg.equals(ri.activityInfo.packageName)) {
                return false;
            }
        } catch (Throwable ignored) {
        }
        return true;
    }

    public interface ReadCb {
        /** serviceOn=false 表示无障碍没连上；text 为空表示当前窗口没有可读文字。 */
        void done(String text, boolean serviceOn);
    }

    /**
     * 用户问到屏幕时即时读一次当前应用窗口文字。
     * 不轮询、不常驻，只在这一个调用里读；结果同时更新 Bus，供紧接着的 AI 提示词使用。
     */
    public static void readNow(final ReadCb cb) {
        final A11y a = instance;
        if (a == null) {
            if (cb != null) cb.done("", false);
            return;
        }
        new Handler(Looper.getMainLooper()).post(() -> {
            if (!awake(a)) {
                if (cb != null) cb.done("", true);
                return;
            }
            String[] pkg = {""};
            String out = readWindows(a, pkg);
            if (!pkg[0].isEmpty() || !out.isEmpty()) {
                if (out.length() > 2000) out = out.substring(0, 2000);
                // 没有可读文字时也要清空旧文字：换到视频/游戏后不能再用上一个应用的内容
                Bus.screenText = out;
                if (!pkg[0].isEmpty()) {
                    Bus.currentPackage = pkg[0];
                    Bus.screenPkg = pkg[0];
                }
                Bus.screenForce = true;
            }
            if (cb != null) cb.done(out, true);
        });
    }

    /**
     * 主动链路用的同步刷新：只更新 Bus.screenText，不设置「用户刚问过屏幕」的标记。
     * 只能在主线程调用；没有读到文字时保留上一次的内容。
     */
    public static String refreshNow() {
        A11y a = instance;
        if (a == null) return Bus.screenText;
        if (Looper.myLooper() != Looper.getMainLooper()) return Bus.screenText;
        if (!awake(a) || !hasForegroundApp(a)) return Bus.screenText;
        String[] pkg = {""};
        String out = readWindows(a, pkg);
        if (!pkg[0].isEmpty() || !out.isEmpty()) {
            if (out.length() > 2000) out = out.substring(0, 2000);
            Bus.screenText = out;
            if (!pkg[0].isEmpty()) {
                Bus.currentPackage = pkg[0];
                Bus.screenPkg = pkg[0];
            }
        }
        return out;
    }

    /** 按需读屏时的采集上限：比后台轮询宽得多，尽量把整页文字读全。 */
    private static final int READ_MAX_DEPTH = 32;
    private static final int READ_MAX_NODES = 600;
    private static final int READ_MAX_CHARS = 3000;

    /** 读当前应用窗口的文字；跳过本应用自己的界面。 */
    private static String readWindows(A11y a, String[] pkgOut) {
        StringBuilder out = new StringBuilder();
        java.util.Set<String> seen = new java.util.HashSet<>();
        String pkg = "";
        String firstPkg = "";
        try {
            java.util.List<AccessibilityWindowInfo> ws = a.getWindows();
            if (ws != null) {
                for (AccessibilityWindowInfo w : ws) {
                    if (w == null || w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                    AccessibilityNodeInfo root = w.getRoot();
                    if (root == null) continue;
                    CharSequence p = root.getPackageName();
                    String pn = p == null ? "" : p.toString();
                    // 跳过自己：聊天窗悬浮在其他应用上，真正的屏幕内容在下面那一层
                    if (pn.equals(a.getPackageName())) continue;
                    if (firstPkg.isEmpty()) firstPkg = pn;
                    StringBuilder sb = new StringBuilder();
                    a.collect(root, sb, 0, new int[]{0},
                            READ_MAX_DEPTH, READ_MAX_NODES, READ_MAX_CHARS, seen);
                    String t = sb.toString().trim();
                    if (t.isEmpty()) continue;
                    if (!pn.isEmpty()) pkg = pn;
                    if (out.length() > 0) out.append('\n');
                    out.append(t);
                    if (out.length() >= READ_MAX_CHARS) break;
                }
            }
            // 有些机型 getWindows() 拿不到应用窗口，退回活动窗口再试一次
            if (out.length() == 0) {
                AccessibilityNodeInfo root = a.getRootInActiveWindow();
                if (root != null) {
                    CharSequence p = root.getPackageName();
                    String pn = p == null ? "" : p.toString();
                    if (!pn.equals(a.getPackageName())) {
                        if (firstPkg.isEmpty()) firstPkg = pn;
                        StringBuilder sb = new StringBuilder();
                        a.collect(root, sb, 0, new int[]{0},
                                READ_MAX_DEPTH, READ_MAX_NODES, READ_MAX_CHARS, seen);
                        String t = sb.toString().trim();
                        if (!t.isEmpty()) {
                            out.append(t);
                            if (!pn.isEmpty()) pkg = pn;
                        }
                    }
                }
            }
            // 视频/游戏这类画面没有可读文字，但也要记住「现在在看的是哪个应用」，
            // 否则会把上一个应用的旧文字当成当前屏幕内容。
            if (pkg.isEmpty()) pkg = firstPkg;
        } catch (Throwable ignored) {
        }
        String s = out.toString().trim();
        if (s.length() > READ_MAX_CHARS) s = s.substring(0, READ_MAX_CHARS);
        if (pkgOut != null && pkgOut.length > 0) pkgOut[0] = pkg;
        return s;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        CompanionSession.resetIdleClock(this);
        try {
            reload();
        } catch (Throwable ignored) {
        }
        try {
            Bus.emit("a11y", new JSONObject());
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null) return;
        int type = e.getEventType();
        if (System.currentTimeMillis() > ignoreAutomationEventsUntil
                && type == AccessibilityEvent.TYPE_TOUCH_INTERACTION_START) {
            lastPhoneInteractionAt = System.currentTimeMillis();
        }
        String pkg = e.getPackageName() == null ? "" : e.getPackageName().toString();
        if (pkg.isEmpty() || pkg.equals(getPackageName())) return;
        if (!pkg.equals(lastPkg)) {
            lastPkg = pkg;
            Bus.currentPackage = pkg;
            Bus.screenText = "";
            Bus.screenPkg = pkg;
            Bus.lastA11yTs = System.currentTimeMillis();
            try {
                JSONObject o = new JSONObject();
                o.put("pkg", pkg);
                Bus.emit("app", o);
            } catch (Throwable ignored) {
            }
        }
        long now = System.currentTimeMillis();
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                && type != AccessibilityEvent.TYPE_VIEW_CLICKED) return;
        if (!screenCtx && !adEnabled) return;
        AccessibilityNodeInfo root = null;
        try {
            root = getRootInActiveWindow();
        } catch (Throwable ignored) {
        }
        if (root == null) return;
        if (screenCtx && now - lastTextAt > screenScanSec * 1000L && awake(this) && hasForegroundApp(this)) {
            lastTextAt = now;
            try {
                StringBuilder sb = new StringBuilder();
                collect(root, sb, 0, new int[]{0});
                String text = sb.toString().trim();
                if (text.length() > 600) text = text.substring(0, 600);
                if (!text.equals(Bus.screenText)) {
                    Bus.screenText = text;
                    JSONObject o = new JSONObject();
                    o.put("pkg", pkg);
                    o.put("text", text.length() > 120 ? text.substring(0, 120) : text);
                    Bus.emit("context", o);
                }
            } catch (Throwable ignored) {
            }
        }
        if (adEnabled) {
            try {
                AdRules.handle(this, root, pkg);
            } catch (Throwable ignored) {
            }
        }
    }

    /** Last real touch interaction observed on the device; 0 means not yet observed. */
    public static long lastPhoneInteractionAt() {
        return lastPhoneInteractionAt;
    }

    /** 广告规则测试：只匹配不点击。 */
    public static JSONObject testRules(String pkg) {
        A11y a = instance;
        if (a == null) return new JSONObject();
        try {
            AccessibilityNodeInfo root = a.getRootInActiveWindow();
            return AdRules.test(a, root, pkg == null || pkg.isEmpty() ? Bus.currentPackage : pkg);
        } catch (Throwable t) {
            return new JSONObject();
        }
    }

    private void collect(AccessibilityNodeInfo n, StringBuilder sb, int depth, int[] count) {
        collect(n, sb, depth, count, 18, 110, 800, null);
    }

    private void collect(AccessibilityNodeInfo n, StringBuilder sb, int depth, int[] count,
                         int maxDepth, int maxNodes, int maxChars, java.util.Set<String> seen) {
        if (n == null || depth > maxDepth || count[0] > maxNodes || sb.length() > maxChars) return;
        count[0]++;
        try {
            if (n.isPassword()) return;
            appendText(sb, n.getText(), seen);
            appendText(sb, n.getContentDescription(), seen);
            appendText(sb, n.getHintText(), seen);
        } catch (Throwable ignored) {
        }
        int c = 0;
        try {
            c = n.getChildCount();
        } catch (Throwable ignored) {
        }
        for (int i = 0; i < c; i++) {
            try {
                collect(n.getChild(i), sb, depth + 1, count, maxDepth, maxNodes, maxChars, seen);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 去重后追加节点文字：同一段文案在父子节点重复出现时只留一份。 */
    private static void appendText(StringBuilder sb, CharSequence t, java.util.Set<String> seen) {
        if (t == null) return;
        String s = t.toString().trim();
        if (s.isEmpty()) return;
        if (seen != null) {
            String key = s.length() > 40 ? s.substring(0, 40) : s;
            if (!seen.add(key)) return;
        }
        sb.append(s).append(' ');
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }
}
