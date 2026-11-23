package com.dshpet.android;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;

/**
 * 代码插件沙箱：在独立的 WebView 里执行插件 JS。
 * 插件能做三件事：
 * 1. 读上下文（屏幕文字、前台应用、时间、无聊程度、情绪）；
 * 2. 通过宿主桥做通用操作（打开应用、点按、滑动、返回、截屏、问她自己的模型、存自己的数据）；
 * 3. 回传一句话或动作。
 * 桥上只开放这里明确列出的原子能力，插件仍然拿不到 App 的文件、网络和任意原生接口。
 */
public final class PluginCode {
    public interface Cb {
        void done(String line, String action, String type);

        /** 插件没跑起来时回传原因，供她解释给用户听。 */
        default void fail(String reason) {
        }
    }

    private static final long TIMEOUT = 1800L;
    /** 有异步操作（截屏 / AI / 网络）在飞时，等待回调的时间上限。 */
    private static final long WAIT = 45000L;
    /** 聊天触发时最多等这么久；定时插件允许更久（要看好几条内容）。 */
    private static final long BUDGET_CHAT = 25000L;
    private static final long BUDGET_TICK = 300000L;
    private static final long BUDGET_DRY_RUN = 8000L;
    private static final int MAX_HTTP = 6;
    private static final int MAX_BODY = 256 * 1024;
    private static volatile boolean busy;

    private PluginCode() {
    }

    public static boolean hasCode(JSONObject manifest) {
        if (manifest == null) return false;
        return !manifest.optString("code", "").trim().isEmpty()
                || (!DexPlugins.isNative(manifest) && !manifest.optString("main", "").trim().isEmpty());
    }

    /** 读取插件代码：优先 manifest 内联 code，其次 main.js 文件。 */
    public static String code(Context c, JSONObject manifest, java.io.File dir) {
        if (manifest == null) return "";
        String inline = manifest.optString("code", "");
        if (!inline.trim().isEmpty()) return inline;
        String main = manifest.optString("main", "main.js").trim();
        try {
            java.io.File root = dir.getCanonicalFile();
            java.io.File file = new java.io.File(root, main).getCanonicalFile();
            if (!file.getPath().startsWith(root.getPath() + java.io.File.separator)
                    || !file.isFile() || file.length() > 65536L) return "";
            return Sg.read(file, "");
        } catch (Throwable ignored) { return ""; }
    }

    public static boolean isBusy() {
        return busy;
    }

    public static void runCode(Context c, String id, String code, String text, String pkg,
                               java.util.List<String> hosts, Cb cb) {
        exec(c, id, code, contextJson(c, text, pkg), hosts, "onEvent", BUDGET_CHAT, cb);
    }

    /** 定时后台插件：由服务每分钟问一次，插件自己判断要不要动。 */
    public static void runTick(Context c, String id, String code,
                               java.util.List<String> hosts, Cb cb) {
        exec(c, id, code, contextJson(c, Bus.screenText, Bus.currentPackage),
                hosts, "onTick", BUDGET_TICK, cb, false);
    }

    /** Run an imported JS plugin with a synthetic context and a host bridge that blocks every side effect. */
    public static void dryRun(Context c, String id, String code, boolean tick, Cb cb) {
        JSONObject sample = new JSONObject();
        try {
            sample.put("text", "dry-run sample event");
            sample.put("pkg", "");
            sample.put("screen", "");
            sample.put("hour", 12);
            sample.put("idleMin", 0);
            sample.put("mood", "");
            sample.put("night", false);
            sample.put("quiet", false);
            sample.put("awake", true);
            sample.put("a11y", false);
            sample.put("w", 360);
            sample.put("h", 800);
            sample.put("landscape", false);
            sample.put("battery", 50);
            sample.put("charging", false);
            sample.put("net", "none");
        } catch (Throwable ignored) { }
        exec(c, id, code, sample, new java.util.ArrayList<>(),
                tick ? "onTick" : "onEvent", BUDGET_DRY_RUN, cb, true);
    }

    /** ctx 是她做判断的依据：屏幕、应用、时间、无聊程度、情绪、屏幕尺寸。 */
    private static JSONObject contextJson(Context c, String text, String pkg) {
        JSONObject o = new JSONObject();
        try {
            o.put("text", text == null ? "" : (text.length() > 300 ? text.substring(0, 300) : text));
            o.put("pkg", pkg == null ? "" : pkg);
            o.put("hour", java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY));
            String screen = Bus.screenText == null ? ""
                    : (Bus.screenText.length() > 200 ? Bus.screenText.substring(0, 200) : Bus.screenText);
            o.put("screen", screen);
            long last = Interact.lastUserAt(c);
            o.put("idleMin", last <= 0 ? -1 : (System.currentTimeMillis() - last) / 60000L);
            o.put("mood", State.load(c).optString("mood", ""));
            Cfg cfg = Cfg.load(c);
            o.put("night", Policy.nightNow(cfg));
            o.put("quiet", Policy.isQuiet(c));
            o.put("awake", A11y.awake(c));
            o.put("a11y", A11y.ready());
            android.util.DisplayMetrics dm = c.getResources().getDisplayMetrics();
            o.put("w", dm.widthPixels);
            o.put("h", dm.heightPixels);
            o.put("landscape", c.getResources().getConfiguration().orientation
                    == android.content.res.Configuration.ORIENTATION_LANDSCAPE);
            // 电量 / 充电 / 网络：插件据此决定「只在充电和 Wi-Fi 时刷」
            try {
                android.content.Intent bt = c.registerReceiver(null,
                        new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
                int level = bt == null ? -1 : bt.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1);
                int scale = bt == null ? -1 : bt.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1);
                o.put("battery", level >= 0 && scale > 0 ? level * 100 / scale : -1);
                int st = bt == null ? -1 : bt.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1);
                o.put("charging", st == android.os.BatteryManager.BATTERY_STATUS_CHARGING
                        || st == android.os.BatteryManager.BATTERY_STATUS_FULL);
            } catch (Throwable ignored) {
            }
            try {
                android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                        c.getSystemService(Context.CONNECTIVITY_SERVICE);
                android.net.NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
                String net = "none";
                if (ni != null && ni.isConnected()) {
                    if (ni.getType() == android.net.ConnectivityManager.TYPE_WIFI) net = "wifi";
                    else if (ni.getType() == android.net.ConnectivityManager.TYPE_MOBILE) net = "cellular";
                    else net = "other";
                }
                o.put("net", net);
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
        }
        return o;
    }

    private static void exec(Context c, String id, String code, JSONObject ctx,
                             java.util.List<String> hosts, String entry, long budget, Cb cb) {
        exec(c, id, code, ctx, hosts, entry, budget, cb, false);
    }

    private static void exec(Context c, String id, String code, JSONObject ctx,
                             java.util.List<String> hosts, String entry, long budget, Cb cb, boolean dryRun) {
        if (cb == null) return;
        if (code == null || code.trim().isEmpty()) {
            cb.done("", "", "");
            cb.fail("这个插件没有可执行的代码");
            return;
        }
        synchronized (PluginCode.class) {
            if (busy) {
                cb.done("", "", "");
                cb.fail("我正忙着跑另一个插件，这次先跳过");
                return;
            }
            busy = true;
        }
        final java.util.List<String> allow = hosts == null
                ? new java.util.ArrayList<>() : hosts;
        Handler h = new Handler(Looper.getMainLooper());
        final long startedAt = System.currentTimeMillis();
        final String pid = id == null ? "" : id;
        h.post(() -> {
            final WebView wv;
            try {
                wv = new WebView(c.getApplicationContext());
            } catch (Throwable t) {
                busy = false;
                cb.done("", "", "");
                return;
            }
            final boolean[] finished = {false};
            final Runnable[] wait = new Runnable[1];
            wait[0] = () -> finish(wv, cb, "", "", "", finished,
                    "插件运行超时：可能卡在死循环，或一直在等截屏/模型返回");
            // 插件在等截图 / AI / 网络时不能按 1.8 秒就杀，把等待往后挪；总预算仍有上限
            Runnable extend = () -> {
                synchronized (finished) {
                    if (finished[0]) return;
                }
                h.removeCallbacks(wait[0]);
                long left = startedAt + budget - System.currentTimeMillis();
                if (left <= 0) {
                    finish(wv, cb, "", "", "", finished, "插件等太久还没结束，被强制停下");
                    return;
                }
                h.postDelayed(wait[0], Math.min(WAIT, left));
            };
            try {
                WebSettings s = wv.getSettings();
                s.setJavaScriptEnabled(true);
                s.setAllowFileAccess(false);
                s.setAllowContentAccess(false);
                s.setBlockNetworkLoads(true);
                s.setDomStorageEnabled(false);
                s.setDatabaseEnabled(false);
                s.setGeolocationEnabled(false);
                s.setCacheMode(WebSettings.LOAD_NO_CACHE);
                s.setSupportZoom(false);
                wv.setBackgroundColor(Color.TRANSPARENT);
                wv.addJavascriptInterface(
                        new Bridge(c, pid, wv, cb, finished, allow, extend, dryRun), "__native");
                wv.setWebViewClient(new WebViewClient() {
                    @Override
                    public void onPageFinished(WebView view, String url) {
                        try {
                            // 先单独校验语法：语法错的代码整段注入会直接静默失败，先在这里拿到行号原因
                            String check = "(function(){try{new Function(" + JSONObject.quote(code)
                                    + ");window.__dshOk=1;}catch(e){"
                                    + "try{__native.fail('插件代码有语法错误：'+(e&&e.message?e.message:e));}"
                                    + "catch(_){}}})()";
                            view.evaluateJavascript(check, v -> {
                                if (v == null || !v.contains("1")) return;
                                try {
                                    view.evaluateJavascript(wrapper(code, ctx.toString(), entry), null);
                                } catch (Throwable t) {
                                    finish(wv, cb, "", "", "", finished, "插件代码注入失败");
                                }
                            });
                        } catch (Throwable t) {
                            finish(wv, cb, "", "", "", finished, "插件代码注入失败");
                        }
                    }
                });
                wv.loadDataWithBaseURL(null, "<!doctype html><meta charset=\"utf-8\"><body></body>",
                        "text/html", "UTF-8", null);
                h.postDelayed(wait[0], TIMEOUT);
            } catch (Throwable t) {
                finish(wv, cb, "", "", "", finished, "沙箱启动失败：" + (t.getMessage() == null ? "未知原因" : t.getMessage()));
            }
        });
    }

    @SuppressLint("JavascriptInterface")
    private static final class Bridge {
        private final Context c;
        private final String pid;
        private final WebView wv;
        private final Cb cb;
        private final boolean[] finished;
        private final java.util.List<String> hosts;
        private final Runnable extend;
        private final boolean dryRun;
        private int used;
        private JSONObject storeObj;

        Bridge(Context c, String pid, WebView wv, Cb cb, boolean[] finished,
               java.util.List<String> hosts, Runnable extend, boolean dryRun) {
            this.c = c;
            this.pid = pid == null || pid.trim().isEmpty() ? "plugin" : pid.trim();
            this.wv = wv;
            this.cb = cb;
            this.finished = finished;
            this.hosts = hosts;
            this.extend = extend;
            this.dryRun = dryRun;
        }

        @JavascriptInterface
        public void done(String json) {
            String line = "", action = "", type = "plugin";
            try {
                JSONObject o = new JSONObject(json == null || json.trim().isEmpty() ? "{}" : json);
                line = o.optString("say", o.optString("line", "")).trim();
                action = o.optString("action", "").trim();
                type = o.optString("type", "plugin").trim();
                if (line.length() > 120) line = line.substring(0, 120);
                if (action.length() > 24) action = action.substring(0, 24);
                if (type.length() > 16) type = type.substring(0, 16);
            } catch (Throwable ignored) {
            }
            final String l = line, a = action, t = type;
            // JS 桥回调在后台线程，WebView 的销毁必须回主线程
            new Handler(Looper.getMainLooper()).post(() -> finish(wv, cb, l, a, t, finished));
        }

        /** 插件跑不下去时由 JS 主动上报：记下原因并结束，让她能解释给用户听。 */
        @JavascriptInterface
        public void fail(String msg) {
            String r = msg == null ? "" : msg.trim();
            if (r.isEmpty()) r = "插件运行出错";
            if (r.length() > 400) r = r.substring(0, 400);
            if (!dryRun) recordError(c, pid, r);
            final String reason = r;
            new Handler(Looper.getMainLooper())
                    .post(() -> finish(wv, cb, "", "", "", finished, reason));
        }

        /** 单个能力没成功（比如没开辅助功能）：只记原因，不打断插件。 */
        @JavascriptInterface
        public void note(String msg) {
            if (!dryRun) recordError(c, pid, msg == null ? "" : msg.trim());
        }

        /* ---------------- 通用原生能力 ---------------- */

        @JavascriptInterface
        public boolean openApp(String pkg) {
            if (dryRun) return false;
            return A11y.openApp(c, pkg == null ? "" : pkg.trim());
        }

        @JavascriptInterface
        public boolean tap(double x, double y) {
            if (dryRun) return false;
            return A11y.tap((float) x, (float) y);
        }

        @JavascriptInterface
        public boolean swipe(double x1, double y1, double x2, double y2, double ms) {
            if (dryRun) return false;
            return A11y.swipe((float) x1, (float) y1, (float) x2, (float) y2, (long) ms);
        }

        @JavascriptInterface
        public boolean back() {
            if (dryRun) return false;
            return A11y.back();
        }

        @JavascriptInterface
        public boolean home() {
            if (dryRun) return false;
            return A11y.home();
        }

        @JavascriptInterface
        public boolean tapText(String text) {
            if (dryRun) return false;
            return A11y.tapText(text == null ? "" : text);
        }

        /** 当前界面可点的入口清单，js 侧用 ui() 取。 */
        @JavascriptInterface
        public String ui() {
            if (dryRun) return "";
            return A11y.uiTexts();
        }

        /** 截屏：回传压缩后的 data URL，js 侧用 await shot() 取。 */
        @JavascriptInterface
        public void screenshot(String rid) {
            final String id = rid == null ? "" : rid;
            if (dryRun) { replyShot(id, "", "沙箱试跑不会读取真实屏幕"); return; }
            extend.run();
            A11y.screenshot(new A11y.ShotCb() {
                @Override
                public void ok(Bitmap bmp) {
                    String url = "";
                    try {
                        Bitmap scaled = bmp;
                        int max = Math.max(bmp.getWidth(), bmp.getHeight());
                        if (max > 900) {
                            float r = 900f / max;
                            scaled = Bitmap.createScaledBitmap(bmp,
                                    Math.max(1, (int) (bmp.getWidth() * r)),
                                    Math.max(1, (int) (bmp.getHeight() * r)), true);
                        }
                        url = Brain.imageData(scaled);
                        if (scaled != bmp) scaled.recycle();
                        bmp.recycle();
                    } catch (Throwable ignored) {
                    }
                    replyShot(id, url, "");
                }

                @Override
                public void fail(String msg) {
                    replyShot(id, "", msg == null || msg.trim().isEmpty()
                            ? "截屏失败（没开辅助功能，或系统不允许截屏）" : msg);
                }
            });
        }

        /** 让插件调用她自己的视觉/文本模型：图片传 shot() 的结果即可。 */
        @JavascriptInterface
        public void ai(String rid, String prompt, String image) {
            final String id = rid == null ? "" : rid;
            final String p = prompt == null ? "" : prompt.trim();
            final String img = image == null ? "" : image.trim();
            if (dryRun) { replyAi(id, false, "", "沙箱试跑不会调用模型或发送网络请求"); return; }
            if (p.isEmpty()) {
                replyAi(id, false, "", "插件调用 ai() 时没有给提示词");
                return;
            }
            extend.run();
            JSONArray msgs = new JSONArray();
            JSONArray txt = new JSONArray();
            try {
                JSONObject u = new JSONObject();
                u.put("role", "user");
                JSONObject plain = new JSONObject();
                plain.put("role", "user");
                plain.put("content", p);
                if (img.isEmpty()) {
                    u.put("content", p);
                } else {
                    JSONArray parts = new JSONArray();
                    JSONObject t = new JSONObject();
                    t.put("type", "text");
                    t.put("text", p);
                    parts.put(t);
                    JSONObject pic = new JSONObject();
                    pic.put("type", "image_url");
                    JSONObject url = new JSONObject();
                    url.put("url", img);
                    pic.put("image_url", url);
                    parts.put(pic);
                    u.put("content", parts);
                }
                msgs.put(u);
                txt.put(plain);
            } catch (Throwable ignored) {
            }
            if (!img.isEmpty()) {
                // 0 = 不设 max_tokens 上限，插件拿到的理解结果不会被截断
                Ai.rawChatVision(c, msgs, 0, 0.7, new Ai.Cb() {
                    @Override
                    public void ok(String text) {
                        replyAi(id, true, text, "");
                    }

                    @Override
                    public void fail(String err) {
                        Ai.rawChat(c, txt, 500, 0.7, new Ai.Cb() {
                            @Override
                            public void ok(String text) {
                                replyAi(id, true, text, "");
                            }

                            @Override
                            public void fail(String err2) {
                                replyAi(id, false, "", "调模型失败：" + (err2 == null ? "未知原因" : err2));
                            }
                        });
                    }
                });
            } else {
                Ai.rawChat(c, txt, 500, 0.7, new Ai.Cb() {
                    @Override
                    public void ok(String text) {
                        replyAi(id, true, text, "");
                    }

                    @Override
                    public void fail(String err) {
                        replyAi(id, false, "", "调模型失败：" + (err == null ? "未知原因" : err));
                    }
                });
            }
        }

        /** 插件自己的持久化：存在 S-G/plugins/<id>/store.json。 */
        @JavascriptInterface
        public synchronized String load(String key, String def) {
            if (dryRun) return def == null ? "" : def;
            try {
                return store().optString(key == null ? "" : key, def == null ? "" : def);
            } catch (Throwable t) {
                return def == null ? "" : def;
            }
        }

        @JavascriptInterface
        public synchronized void save(String key, String value) {
            if (dryRun) return;
            try {
                store().put(key == null ? "" : key, value == null ? "" : value);
                Sg.write(storeFile(), store().toString());
            } catch (Throwable ignored) {
            }
        }

        /** 把这次看到的东西写进她的长期记忆。 */
        @JavascriptInterface
        public void remember(String text) {
            if (dryRun) return;
            try {
                String t = text == null ? "" : text.trim();
                if (t.isEmpty()) return;
                Mem.add(c, t, "生活", null, "plugin", 0.7);
            } catch (Throwable ignored) {
            }
        }

        private File storeFile() {
            File d = new File(Plugins.dir(c), pid.replaceAll("[^A-Za-z0-9_-]", ""));
            if (!d.exists()) //noinspection ResultOfMethodCallIgnored
                d.mkdirs();
            return new File(d, "store.json");
        }

        private JSONObject store() {
            if (storeObj == null) {
                try {
                    String s = Sg.read(storeFile(), "{}");
                    storeObj = new JSONObject(s.trim().isEmpty() ? "{}" : s);
                } catch (Throwable t) {
                    storeObj = new JSONObject();
                }
            }
            return storeObj;
        }

        /* ---------------- 联网（白名单） ---------------- */

        /**
         * 插件联网入口：只有 https、且域名必须在插件清单 hosts 里。
         * 请求由原生代发（绕开沙箱的断网限制与 CORS），结果异步回传 __httpDone。
         */
        @JavascriptInterface
        public void http(String id, String url, String method, String body, String headers) {
            final String rid = id == null ? "" : id;
            if (dryRun) { reply(rid, 0, "沙箱试跑已拦截网络请求"); return; }
            synchronized (this) {
                if (used++ >= MAX_HTTP) {
                    reply(rid, 0, "插件请求次数超过上限");
                    return;
                }
            }
            if (!allowed(url)) {
                reply(rid, 0, "域名不在插件白名单里（manifest.json 的 hosts）");
                return;
            }
            extend.run();
            final String u = url.trim();
            final String m = method == null || method.trim().isEmpty()
                    ? "GET" : method.trim().toUpperCase(java.util.Locale.ROOT);
            final String bd = body == null ? "" : body;
            final String hd = headers == null ? "" : headers;
            new Thread(() -> {
                int status = 0;
                String text;
                try {
                    java.net.HttpURLConnection conn =
                            (java.net.HttpURLConnection) new java.net.URL(u).openConnection();
                    // 不自动跟随跳转：否则白名单域名可以 302 到任意地址，绕开限制
                    conn.setInstanceFollowRedirects(false);
                    conn.setConnectTimeout(6000);
                    conn.setReadTimeout(10000);
                    conn.setRequestMethod(m);
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) DshPet");
                    conn.setRequestProperty("Accept", "*/*");
                    try {
                        JSONObject hjo = new JSONObject(hd.trim().isEmpty() ? "{}" : hd);
                        java.util.Iterator<String> it = hjo.keys();
                        while (it.hasNext()) {
                            String k = it.next();
                            conn.setRequestProperty(k, hjo.optString(k, ""));
                        }
                    } catch (Throwable ignored) {
                    }
                    if (!bd.isEmpty()) {
                        conn.setDoOutput(true);
                        byte[] raw = bd.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        conn.setFixedLengthStreamingMode(raw.length);
                        try (java.io.OutputStream os = conn.getOutputStream()) {
                            os.write(raw);
                        }
                    }
                    status = conn.getResponseCode();
                    java.io.InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
                    text = "";
                    if (in != null) {
                        try (java.io.InputStream is = in) {
                            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                            byte[] buf = new byte[8192];
                            int n, total = 0;
                            while ((n = is.read(buf)) > 0) {
                                total += n;
                                if (total > MAX_BODY) break;
                                bo.write(buf, 0, n);
                            }
                            text = new String(bo.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
                        }
                    }
                } catch (Throwable t) {
                    status = 0;
                    text = t.getMessage() == null ? "请求失败" : t.getMessage();
                }
                reply(rid, status, text);
            }, "dsh-plugin-http").start();
        }

        /** 域名白名单：清单里写 api.example.com 时，子域名 api.x.example.com 也放行。 */
        private boolean allowed(String url) {
            if (url == null) return false;
            String u = url.trim();
            if (!u.startsWith("https://")) return false;
            String host;
            try {
                host = new java.net.URL(u).getHost();
            } catch (Throwable t) {
                return false;
            }
            if (host == null || host.isEmpty()) return false;
            host = host.toLowerCase(java.util.Locale.ROOT);
            for (String w : hosts) {
                String x = w == null ? "" : w.trim().toLowerCase(java.util.Locale.ROOT);
                int at = x.indexOf("://");
                if (at >= 0) x = x.substring(at + 3);
                int slash = x.indexOf('/');
                if (slash >= 0) x = x.substring(0, slash);
                if (x.isEmpty()) continue;
                if (host.equals(x) || host.endsWith("." + x)) return true;
            }
            return false;
        }

        private void reply(String id, int status, String body) {
            final String js = "window.__httpDone&&window.__httpDone(" + JSONObject.quote(id) + ","
                    + status + "," + JSONObject.quote(body == null ? "" : body) + ")";
            eval(js);
        }

        private void replyShot(String id, String url, String why) {
            final String js = "window.__shotDone&&window.__shotDone(" + JSONObject.quote(id) + ","
                    + (url == null || url.isEmpty() ? 0 : 1) + ","
                    + JSONObject.quote(url == null ? "" : url) + ","
                    + JSONObject.quote(why == null ? "" : why) + ")";
            eval(js);
        }

        private void replyAi(String id, boolean ok, String text, String why) {
            final String js = "window.__aiDone&&window.__aiDone(" + JSONObject.quote(id) + ","
                    + (ok ? 1 : 0) + ","
                    + JSONObject.quote(text == null ? "" : text) + ","
                    + JSONObject.quote(why == null ? "" : why) + ")";
            eval(js);
        }

        private void eval(String js) {
            try {
                wv.post(() -> {
                    try {
                        wv.evaluateJavascript(js, null);
                    } catch (Throwable ignored) {
                    }
                });
            } catch (Throwable ignored) {
            }
        }
    }

    private static void finish(WebView wv, Cb cb, String line, String action, String type,
                               boolean[] finished) {
        finish(wv, cb, line, action, type, finished, "");
    }

    private static void finish(WebView wv, Cb cb, String line, String action, String type,
                               boolean[] finished, String reason) {
        synchronized (finished) {
            if (finished[0]) return;
            finished[0] = true;
        }
        busy = false;
        try {
            wv.removeJavascriptInterface("__native");
            wv.loadUrl("about:blank");
            wv.destroy();
        } catch (Throwable ignored) {
        }
        try {
            cb.done(line == null ? "" : line, action == null ? "" : action,
                    type == null || type.isEmpty() ? "plugin" : type);
        } catch (Throwable ignored) {
        }
        if (reason != null && !reason.trim().isEmpty()) {
            try {
                cb.fail(reason.trim());
            } catch (Throwable ignored) {
            }
        }
    }

    /** 把这次失败的原因记到插件目录，之后她解释「为什么跑不了」时能看到。 */
    private static void recordError(Context c, String pid, String reason) {
        String r = reason == null ? "" : reason.trim();
        if (r.isEmpty()) return;
        try {
            String id = pid == null ? "" : pid.replaceAll("[^A-Za-z0-9_-]", "");
            if (id.isEmpty()) return;
            File d = new File(Plugins.dir(c), id);
            // 只给已存在的插件记，不乱建目录
            if (!d.isDirectory()) return;
            JSONObject o = new JSONObject();
            o.put("at", System.currentTimeMillis());
            o.put("reason", r.length() > 400 ? r.substring(0, 400) : r);
            Sg.write(new File(d, "last_error.json"), o.toString());
        } catch (Throwable ignored) {
        }
    }

    /**
     * 插件入口：onEvent(ctx)（聊天/屏幕触发）或 onTick(ctx)（定时后台）。
     * 返回 {say, action, type}；同时注入宿主能力，见上面 Bridge。
     */
    private static String wrapper(String code, String ctxJson, String entry) {
        return "(function(){try{var __ctx=" + ctxJson + ";"
                + "var __w={},__n=0;"
                + "function __msg(e){try{if(e&&e.stack){return String(e.stack).split('\\n').slice(0,2).join(' | ');}"
                + "if(e&&e.message){return String(e.message);}return String(e);}catch(_){return '未知错误';}}"
                + "function note(m){try{__native.note(String(m||''));}catch(e){}}"
                + "function fail(m){try{__native.fail(String(m||'插件运行出错'));}catch(e){}}"
                + "function http(u,o){o=o||{};return new Promise(function(res,rej){"
                + "var id='h'+(++__n);"
                + "__w[id]=function(ok,st,b){if(ok){res({ok:st>=200&&st<300,status:st,body:b||''});}"
                + "else{rej(new Error(b||'请求失败'));}};"
                + "try{__native.http(id,String(u||''),String(o.method||'GET'),"
                + "o.body==null?'':String(o.body),JSON.stringify(o.headers||{}));}"
                + "catch(e){delete __w[id];rej(e);}});}"
                + "window.__httpDone=function(id,st,b){var f=__w[id];if(f){delete __w[id];f(st>0,st,b||'');}};"
                + "var __cb={},__seq=0;"
                + "function __wait(m,args){return new Promise(function(res){"
                + "var id='a'+(++__seq);"
                + "__cb[id]=function(ok,v,why){if(ok){res(v);}else{note(why||(m+' 没有成功'));res('');}};"
                + "try{__native[m].apply(__native,[id].concat(args||[]));}"
                + "catch(e){delete __cb[id];note('调用 '+m+'() 出错：'+__msg(e));res('');}});}"
                + "window.__shotDone=function(id,ok,url,why){var f=__cb[id];if(f){delete __cb[id];f(!!ok,url||'',why||'');}};"
                + "window.__aiDone=function(id,ok,t,why){var f=__cb[id];if(f){delete __cb[id];f(!!ok,t||'',why||'');}};"
                + "function shot(){return __wait('screenshot',[]);}"
                + "function ai(p,img){return __wait('ai',[String(p||''),String(img||'')]);}"
                + "function openApp(p){var r=__native.openApp(String(p||''));"
                + "if(!r)note('打不开应用 '+p+'（没装这个应用，或辅助功能没开）');return r;}"
                + "function tap(x,y){var r=__native.tap(Number(x)||0,Number(y)||0);"
                + "if(!r)note('点按没执行（辅助功能没开）');return r;}"
                + "function swipe(a,b,c,d,e){var r=__native.swipe(Number(a)||0,Number(b)||0,"
                + "Number(c)||0,Number(d)||0,Number(e)||600);"
                + "if(!r)note('滑动没执行（辅助功能没开）');return r;}"
                + "function back(){var r=__native.back();if(!r)note('返回没执行（辅助功能没开）');return r;}"
                + "function home(){var r=__native.home();if(!r)note('回桌面没执行（辅助功能没开）');return r;}"
                + "function tapText(t){var r=__native.tapText(String(t||''));"
                + "if(!r)note('屏幕上没找到「'+t+'」，或辅助功能没开');return r;}"
                + "function ui(){try{return __native.ui()||'';}catch(e){return '';}}"
                + "function sleep(ms){return new Promise(function(r){setTimeout(function(){r();},Number(ms)||0);});}"
                + "function save(k,v){try{__native.save(String(k),String(v==null?'':v));}catch(e){}}"
                + "function load(k,d){try{var v=__native.load(String(k),String(d==null?'':d));"
                + "return v==null?String(d==null?'':d):String(v);}catch(e){return String(d==null?'':d);}}"
                + "function remember(t){try{__native.remember(String(t||''));}catch(e){}}"
                + "try{window.onerror=function(m,s,l){fail('运行出错：'+m+'（第'+l+'行）');return true;};"
                + "window.onunhandledrejection=function(ev){var r=ev&&ev.reason;"
                + "fail('运行出错：'+(r&&r.message?r.message:String(r||'未处理的异步错误')));};}catch(e){}"
                + code
                + "\n;var __f=" + ("onTick".equals(entry)
                ? "(typeof onTick==='function'?onTick:null)"
                : "(typeof onEvent==='function'?onEvent:(typeof run==='function'?run:null))")
                + ";if(!__f){fail('插件里没有" + ("onTick".equals(entry) ? "onTick" : "onEvent") + " 入口函数');return;}"
                + "var __fin=function(r){if(r&&typeof r==='object'){__native.done(JSON.stringify(r));}"
                + "else{__native.done('');}};"
                + "try{var __r=__f(__ctx);"
                + "if(__r&&typeof __r.then==='function'){__r.then(__fin).catch(function(e){fail('运行出错：'+__msg(e));});}"
                + "else{__fin(__r);}"
                + "}catch(e){fail('运行出错：'+__msg(e));}"
                + "}catch(e){try{__native.fail('插件初始化失败：'+String(e));}catch(_){}}})();";
    }
}
