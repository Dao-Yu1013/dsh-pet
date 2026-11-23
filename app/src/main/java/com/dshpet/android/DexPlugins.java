package com.dshpet.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import com.dshpet.android.plugin.Host;
import com.dshpet.android.plugin.PluginEntry;

import org.json.JSONObject;
import org.json.JSONArray;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 原生（Dex）插件运行时。
 * 清单里写了 main 的插件会把 plugin.dex / plugin.apk 直接加载进 App 进程，
 * 拿到真实 Context —— 能读写 S-G、联网、开应用、发通知，和 App 本身权限一样。
 * 这类插件没有沙箱，只应安装自己信任的包。
 */
public final class DexPlugins {
    private static final String TAG = "DshDexPlugin";
    private static final int NID_PLUGIN = 24179;
    private static final Map<String, PluginEntry> RUNNING = new HashMap<>();
    private static final Map<String, String> RUNTIME_ERRORS = new HashMap<>();
    private static final Object LOCK = new Object();
    private static final ExecutorService EVENTS = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "dsh-dex-events");
        t.setDaemon(true);
        return t;
    });

    public static String runtimeState(String id) {
        synchronized (LOCK) {
            return RUNNING.containsKey(id) ? "running"
                    : (RUNTIME_ERRORS.containsKey(id) ? "error" : "stopped");
        }
    }

    public static String runtimeError(String id) {
        synchronized (LOCK) {
            return RUNTIME_ERRORS.get(id);
        }
    }

    /** Queue a user message for each enabled native plugin without blocking the chat thread. */
    public static void dispatchMessage(Context c, String text) {
        if (c == null || text == null || text.trim().isEmpty()) return;
        final Context app = c.getApplicationContext() == null ? c : c.getApplicationContext();
        final Map<String, PluginEntry> snapshot;
        synchronized (LOCK) {
            snapshot = new HashMap<>(RUNNING);
        }
        for (Map.Entry<String, PluginEntry> entry : snapshot.entrySet()) {
            final String id = entry.getKey();
            final PluginEntry plugin = entry.getValue();
            EVENTS.execute(() -> {
                if (!Plugins.enabled(app, id)) return;
                try {
                    plugin.onMessage(new HostImpl(app, id), text);
                } catch (Throwable t) {
                    Log.w(TAG, "onMessage failed: " + id, t);
                    synchronized (LOCK) {
                        RUNTIME_ERRORS.put(id, t.getMessage() == null
                                ? t.getClass().getSimpleName() : t.getMessage());
                    }
                    Bus.emit("plugins", new JSONObject());
                }
            });
        }
    }

    /** Queue a scheduled tick for every running native plugin. */
    public static void dispatchTick(Context c) {
        if (c == null) return;
        final Context app = c.getApplicationContext() == null ? c : c.getApplicationContext();
        final Map<String, PluginEntry> snapshot;
        synchronized (LOCK) {
            snapshot = new HashMap<>(RUNNING);
        }
        for (Map.Entry<String, PluginEntry> entry : snapshot.entrySet()) {
            final String id = entry.getKey();
            final PluginEntry plugin = entry.getValue();
            EVENTS.execute(() -> {
                if (!Plugins.enabled(app, id)) return;
                try {
                    plugin.onTick(new HostImpl(app, id));
                } catch (Throwable t) {
                    Log.w(TAG, "onTick failed: " + id, t);
                    synchronized (LOCK) {
                        RUNTIME_ERRORS.put(id, t.getMessage() == null
                                ? t.getClass().getSimpleName() : t.getMessage());
                    }
                    Bus.emit("plugins", new JSONObject());
                }
            });
        }
    }

    private DexPlugins() {
    }

    /** Dotted Java class names (or an explicit flag) are native; main.js remains a JS entry. */
    public static boolean isNative(JSONObject m) {
        if (m == null) return false;
        if (m.optBoolean("native", false)) return true;
        String main = m.optString("main", "").trim();
        if (main.isEmpty() || main.endsWith(".js") || main.endsWith(".mjs")
                || main.contains("/") || main.contains("\\")) return false;
        return main.matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+");
    }

    /** 让运行中的插件和「已装 + 已启用」的清单保持一致。onEnable 意义上的 start 只在这里触发。 */
    public static void sync(Context c) {
        if (c == null) return;
        final Context app = c.getApplicationContext() == null ? c : c.getApplicationContext();
        new Thread(() -> {
            synchronized (LOCK) {
                try {
                    syncNow(app);
                } catch (Throwable t) {
                    Log.w(TAG, "同步失败", t);
                }
            }
        }, "dsh-dex-sync").start();
    }

    private static void syncNow(Context c) {
        File root = Plugins.dir(c);
        File[] fs = root.listFiles();
        Set<String> alive = new HashSet<>();
        if (fs != null) {
            for (File d : fs) {
                if (!d.isDirectory()) continue;
                JSONObject m = manifest(d);
                if (!isNative(m)) continue;
                String id = d.getName();
                alive.add(id);
                if (!Plugins.enabled(c, id)) {
                    stop(id);
                    continue;
                }
                if (!RUNNING.containsKey(id)) start(c, id, d, m);
            }
        }
        for (String id : new ArrayList<>(RUNNING.keySet())) {
            if (!alive.contains(id)) stop(id);
        }
    }

    /** 只跑插件生命周期；停用后 dex 仍留在内存里，但不再有任何引用。 */
    static void stop(String id) {
        PluginEntry p;
        synchronized (LOCK) {
            p = RUNNING.remove(id);
        }
        if (p == null) return;
        try {
            p.stop();
        } catch (Throwable t) {
            Log.w(TAG, "stop 失败：" + id, t);
        }
    }

    private static void start(Context c, String id, File d, JSONObject m) {
        synchronized (LOCK) {
            RUNTIME_ERRORS.remove(id);
        }
        String main = m.optString("main", "").trim();
        String file = m.optString("file", "").trim();
        if (file.isEmpty()) {
            for (String name : new String[]{"plugin.dex", "plugin.apk", "classes.dex"}) {
                if (new File(d, name).isFile()) {
                    file = name;
                    break;
                }
            }
        }
        try {
            if (main.isEmpty()) throw new Exception("清单缺少 main（入口类）");
            if (file.isEmpty()) throw new Exception("插件目录里没有 plugin.dex / plugin.apk");
            File dex = new File(d, file);
            if (!dex.isFile()) throw new Exception("找不到 " + file);
            // Android 14+ 要求动态加载的 dex 必须是只读文件，否则直接抛 SecurityException
            try {
                dex.setReadOnly();
            } catch (Throwable ignored) {
            }
            File opt = new File(c.getCodeCacheDir(), "dexplugin/" + id);
            if (!opt.exists() && !opt.mkdirs()) throw new Exception("无法创建 dexopt 目录");
            dalvik.system.DexClassLoader loader = new dalvik.system.DexClassLoader(
                    dex.getAbsolutePath(), opt.getAbsolutePath(), null, DexPlugins.class.getClassLoader());
            Class<?> cls = Class.forName(main, true, loader);
            Object o = cls.getDeclaredConstructor().newInstance();
            if (!(o instanceof PluginEntry)) throw new Exception(main + " 没有实现 PluginEntry");
            PluginEntry p = (PluginEntry) o;
            synchronized (LOCK) {
                RUNNING.put(id, p);
            }
            p.start(new HostImpl(c, id));
            synchronized (LOCK) {
                RUNTIME_ERRORS.remove(id);
            }
            Log.i(TAG, "已启动 " + id + " (" + main + ")");
        } catch (Throwable t) {
            synchronized (LOCK) {
                RUNNING.remove(id);
                RUNTIME_ERRORS.put(id, t.getMessage() == null
                        ? t.getClass().getSimpleName() : t.getMessage());
            }
            Log.w(TAG, "启动失败：" + id, t);
            Bus.emit("plugins", new JSONObject());
        }
    }

    private static JSONObject manifest(File d) {
        try {
            String s = Sg.read(new File(d, "manifest.json"), "");
            if (s.trim().isEmpty()) s = Sg.read(new File(d, "plugin.json"), "");
            if (s.trim().isEmpty()) return null;
            JSONObject o = new JSONObject(s);
            if (o.optJSONObject("plugin") != null) o = o.getJSONObject("plugin");
            return o;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 只给插件用的一层薄封装：Context + 说话 + 通知 + 自己的小配置。 */
    private static final class HostImpl implements Host {
        private final Context c;
        private final File store;
        private final Object io = new Object();

        HostImpl(Context app, String id) {
            c = app;
            store = new File(Plugins.dir(app), id + "/store.json");
        }

        @Override
        public Context context() {
            return c;
        }

        @Override
        public boolean openApp(String packageName) {
            String pkg = packageName == null ? "" : packageName.trim();
            boolean opened = A11y.openApp(c, pkg);
            ExperienceLog.record(c, opened ? "started" : "failed", pkg,
                    opened ? "已发起打开应用" : "系统未能打开应用");
            return opened;
        }

        @Override
        public boolean tap(float x, float y) {
            return A11y.tap(x, y);
        }

        @Override
        public boolean swipe(float x1, float y1, float x2, float y2, long durationMs) {
            return A11y.swipe(x1, y1, x2, y2, durationMs);
        }

        @Override
        public boolean back() {
            return A11y.back();
        }

        @Override
        public boolean home() {
            return A11y.home();
        }

        @Override
        public boolean tapText(String visibleText) {
            return A11y.tapText(visibleText == null ? "" : visibleText);
        }

        @Override
        public boolean hasAccessibility() {
            return A11y.ready();
        }

        @Override
        public String visibleUi() {
            return A11y.uiTexts();
        }

        @Override
        public String foregroundPackage() {
            return A11y.foregroundPackage();
        }

        @Override
        public void screenshot(Host.ScreenshotCallback callback) {
            if (callback == null) return;
            A11y.screenshot(new A11y.ShotCb() {
                @Override
                public void ok(android.graphics.Bitmap bmp) {
                    callback.onResult(bmp, "");
                }

                @Override
                public void fail(String msg) {
                    callback.onResult(null, msg == null ? "截屏失败" : msg);
                }
            });
        }

        @Override
        public void analyzeScreen(String instruction, Host.ScreenTextCallback callback) {
            if (callback == null) return;
            A11y.screenshot(new A11y.ShotCb() {
                @Override
                public void ok(android.graphics.Bitmap bmp) {
                    try {
                        String image = Brain.imageData(bmp);
                        if (image.isEmpty()) {
                            callback.onResult("", "无法读取当前画面");
                            return;
                        }
                        JSONArray messages = new JSONArray();
                        JSONObject system = new JSONObject();
                        system.put("role", "system");
                        system.put("content", "你只分析这张当前屏幕截图。区分看得见的事实和推测；看不清就明确说看不清；"
                                + "不要声称视频已完整播放或内容已全部看完。简洁描述可见主题和文字。");
                        messages.put(system);
                        JSONArray parts = new JSONArray();
                        JSONObject ask = new JSONObject();
                        ask.put("type", "text");
                        ask.put("text", instruction == null || instruction.trim().isEmpty()
                                ? "概括屏幕上当前可见的主要内容。" : instruction.trim());
                        parts.put(ask);
                        JSONObject pic = new JSONObject();
                        pic.put("type", "image_url");
                        JSONObject url = new JSONObject();
                        url.put("url", image);
                        pic.put("image_url", url);
                        parts.put(pic);
                        JSONObject user = new JSONObject();
                        user.put("role", "user");
                        user.put("content", parts);
                        messages.put(user);
                        Ai.rawChatVision(c, messages, 220, new Ai.Cb() {
                            @Override
                            public void ok(String text) {
                                String target = A11y.foregroundPackage();
                                ExperienceLog.record(c, "observed", target,
                                        text == null ? "" : text);
                                InterestProfile.learned(c, target, text);
                                callback.onResult(text, "");
                            }

                            @Override
                            public void fail(String error) {
                                callback.onResult("", error == null ? "屏幕分析失败" : error);
                            }
                        });
                    } catch (Throwable t) {
                        callback.onResult("", "屏幕分析失败");
                    } finally {
                        try { bmp.recycle(); } catch (Throwable ignored) { }
                    }
                }

                @Override
                public void fail(String msg) {
                    callback.onResult("", msg == null ? "截屏失败" : msg);
                }
            });
        }

        @Override
        public void say(String line) {
            if (line == null || line.trim().isEmpty()) return;
            PetSvc.sayPlugin(c, line.trim());
        }

        @Override
        public void notify(String title, String text) {
            post(c, title, text);
        }

        @Override
        public String get(String key, String def) {
            String d = def == null ? "" : def;
            if (key == null || key.isEmpty()) return d;
            synchronized (io) {
                try {
                    return read().optString(key, d);
                } catch (Throwable t) {
                    return d;
                }
            }
        }

        @Override
        public void set(String key, String value) {
            if (key == null || key.isEmpty()) return;
            synchronized (io) {
                try {
                    JSONObject o = read();
                    o.put(key, value == null ? "" : value);
                    Sg.write(store, o.toString());
                } catch (Throwable ignored) {
                }
            }
        }

        @Override
        public void recordExperience(String state, String target, String detail) {
            ExperienceLog.record(c, state, target, detail);
        }

        @Override
        public void toast(String msg) {
            if (msg == null || msg.isEmpty()) return;
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    Toast.makeText(c, msg, Toast.LENGTH_SHORT).show();
                } catch (Throwable ignored) {
                }
            });
        }

        @Override
        public void log(String msg) {
            Log.i(TAG, msg == null ? "" : msg);
        }

        private JSONObject read() {
            try {
                String s = Sg.read(store, "");
                return s.trim().isEmpty() ? new JSONObject() : new JSONObject(s);
            } catch (Throwable t) {
                return new JSONObject();
            }
        }
    }

    /** 插件自己的通知：走「她主动发的消息」通道，独立 id，不覆盖主动消息。 */
    private static void post(Context c, String title, String text) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (nm.getNotificationChannel(PetSvc.CH_MSG) == null) {
                NotificationChannel ch = new NotificationChannel(PetSvc.CH_MSG, "她主动发的消息",
                        NotificationManager.IMPORTANCE_DEFAULT);
                ch.setShowBadge(true);
                nm.createNotificationChannel(ch);
            }
            String body = text == null ? "" : text;
            Intent i = new Intent(c, MainAct.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("page", "chat");
            PendingIntent open = PendingIntent.getActivity(c, 22, i, 201326592);
            Notification n = new Notification.Builder(c, PetSvc.CH_MSG)
                    .setContentTitle(title == null || title.trim().isEmpty() ? "插件消息" : title)
                    .setContentText(body)
                    .setStyle(new Notification.BigTextStyle().bigText(body))
                    .setSmallIcon(R.drawable.ic_launcher)
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .setShowWhen(true)
                    .setLocalOnly(true)
                    .setVisibility(Notification.VISIBILITY_PRIVATE)
                    .build();
            nm.notify(NID_PLUGIN, n);
        } catch (Throwable ignored) {
        }
    }
}
