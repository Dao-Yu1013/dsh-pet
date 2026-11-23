package com.dshpet.android.plugin;

import android.content.Context;
import android.graphics.Bitmap;

/**
 * 给原生插件的宿主接口。
 * 注意：插件和 App 跑在同一个进程、拥有同样的权限，它能通过 context() 直接读写 App 私有目录、
 * 联网、发 Intent，所以要只安装自己信得过的插件。
 */
public interface Host {
    int API_VERSION = 2;
    interface ScreenshotCallback {
        void onResult(Bitmap bitmap, String error);
    }
    interface ScreenTextCallback {
        void onResult(String text, String error);
    }
    /** App 的 Context（Application 级）。可以直接用它做文件、网络、startActivity 等操作。 */
    Context context();

    /** 让她主动说一句：进聊天记录、弹气泡，并留一条通知。 */
    void say(String line);

    /** 发一条通知，不打扰桌宠气泡。 */
    void notify(String title, String text);

    /** 读插件自己的持久化配置（存在 S-G/plugins/<id>/store.json）。 */
    String get(String key, String def);

    /** 写插件自己的持久化配置。 */
    void set(String key, String value);

    /** 弹一个 Toast（会切回主线程）。 */
    void toast(String msg);

    /** 写日志，tag 是 DshDexPlugin。 */
    void log(String msg);

    /** Open an installed application by package name. Returns false if unavailable. */
    default boolean openApp(String packageName) { return false; }

    /** Accessibility actions. These are available only while the user's accessibility service is enabled. */
    default boolean tap(float x, float y) { return false; }
    default boolean swipe(float x1, float y1, float x2, float y2, long durationMs) { return false; }
    default boolean back() { return false; }
    default boolean home() { return false; }
    default boolean tapText(String visibleText) { return false; }
    default boolean hasAccessibility() { return false; }
    default String visibleUi() { return ""; }
    /** 当前无障碍活动窗口的包名；为空表示暂时无法确认前台应用。 */
    default String foregroundPackage() { return ""; }
    /** 使用应用现有的无障碍截屏能力；截图结果按系统可见画面返回。 */
    default void screenshot(ScreenshotCallback callback) {
        if (callback != null) callback.onResult(null, "当前宿主不支持截屏");
    }
    /** 截取当前屏幕并交给已配置的视觉模型分析；插件应只在用户明确启动的任务中调用。 */
    default void analyzeScreen(String instruction, ScreenTextCallback callback) {
        if (callback != null) callback.onResult("", "当前宿主不支持屏幕分析");
    }

    /** Record a real session transition; use observed only when screen analysis returned evidence. */
    default void recordExperience(String state, String target, String detail) { }
}
