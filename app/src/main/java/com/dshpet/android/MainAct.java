package com.dshpet.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** 唯一的 Activity：WebView 控制台 + 原生桥。 */
public final class MainAct extends Activity {
    private static final int RQ_RULES = 1002;
    private static final int RQ_EXPORT = 1003;
    private static final int RQ_IMPORT = 1004;
    private static final int RQ_PLUGIN = 1005;
    private static final int RQ_BACKUP = 1006;
    private static final int RQ_RESTORE = 1007;
    private static final int RQ_BOOK = 1010;
    private static final int RQ_BOOK_COVER = 1011;
    private static final int RQ_BOOK_EXPORT = 1012;
    private static final int RQ_BOOK_IMPORT = 1013;
    private static final int RQ_BOOK_NOTES = 1014;
    private static final int RQ_STICKER = 1015;
    private static final int RQ_SKIN = 1016;
    private static final int RQ_SKIN_MULTI = 1017;
    private static final int RQ_SKIN_ZIP = 1018;
    private static final int RQ_SKIN_EXPORT = 1019;
    private static final int RQ_SKIN_ANIM = 1020;
    private static final int RQ_LIVE2D = 1021;
    private static final int RQ_STICKER_BATCH = 1022;
    private static final int RQ_STICKER_REPLACE = 1023;
    private static final int RQ_CHAT_FILE = 1024;
    private static final int RQ_CHAT_CAMERA = 1025;
    private static final int RQ_LONG_DOC = 1026;
    private static final int RQ_ASTRBOT_PLUGIN = 1027;
    private static final int RQ_QQ_PERSONA_IMPORT = 1028;
    private static final int RQ_PROFILE_AVATAR = 1029;
    private static final int RQ_COMFY_WORKFLOW_IMPORT = 1030;
    private static final int RQ_CHAT_VIDEO = 1031;
    private static final int RQ_PERM = 2001;

    private String pendingPass = "";
    private String pendingMode = "replace";
    private String pendingBookId = "";
    private String pendingStickerId = "";
    private String pendingLongDocId = "";
    private String pendingLongDocFormat = "";

    /** 阅读中拦截音量键翻页；离开阅读页立即恢复系统音量键 */
    private volatile boolean readerKeys;

    private WebView web;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    // 网络/模型调用可能等待几十秒；与普通状态读取、设置保存分队列，避免慢回复阻塞整页操作。
    // 仍保持单线程，避免同时启动多个高耗时请求增加耗电或争用同一会话状态。
    private final ExecutorService slowWorker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "DshSlowBridge");
        t.setDaemon(true);
        return t;
    });
    // AstrBot plugin installs can take several minutes; keep them from queuing
    // behind ordinary WebView bridge calls on the app's shared worker.
    private final ExecutorService astrbotWorker = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "DshAstrBotApi");
        t.setDaemon(true);
        return t;
    });
    private String pendingPage;
    private String pendingReaderBookId;
    private String pendingPerm;
    private Bus.Sink sink;
    private boolean loaded;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setBackgroundDrawableResource(android.R.color.black);
        getWindow().setStatusBarColor(0x00000000);
        getWindow().setNavigationBarColor(0xFF07090F);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        // 完整性检查：so 缺失、签名不符、调试环境 → 只显示原生提示页，
        // 不加载控制台、不碰用户数据
        String bad = Secure.problem(this, true);
        if (!bad.isEmpty()) {
            showBlocked(bad);
            return;
        }
        web = new WebView(this);
        web.setBackgroundColor(0xFF07090F);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setTextZoom(100);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                return assetWebp(req == null ? null : req.getUrl().toString());
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                loaded = true;
                if (pendingPage != null) {
                    String p = pendingPage;
                    pendingPage = null;
                    view.evaluateJavascript("window.__goto&&window.__goto(" + JSONObject.quote(p) + ")", null);
                }
                if (pendingReaderBookId != null && !pendingReaderBookId.isEmpty()) {
                    String id = pendingReaderBookId;
                    pendingReaderBookId = null;
                    view.evaluateJavascript("window.__openBookFromHost&&window.__openBookFromHost("
                            + JSONObject.quote(id) + ")", null);
                }
            }
        });
        web.addJavascriptInterface(new Bridge(), "Dsh");
        // 统一由前端按系统 IME inset 定位输入区，避免 adjustResize 与 CSS 双重补偿。
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            // 挂在 decorView 上，避免影响 WebView 自己处理别的内边距
            getWindow().getDecorView().setOnApplyWindowInsetsListener((v, insets) -> {
                int ime = insets.getInsets(android.view.WindowInsets.Type.ime()).bottom;
                float dp = ime / getResources().getDisplayMetrics().density;
                try {
                    JSONObject o = new JSONObject();
                    o.put("h", Math.round(dp));
                    event("kb", o);
                } catch (Throwable ignored) {
                }
                return insets;
            });
        }
        setContentView(web);
        if (getIntent() != null) {
            pendingPage = getIntent().getStringExtra("page");
            pendingReaderBookId = getIntent().getStringExtra("readerBookId");
            if (pendingReaderBookId != null && !pendingReaderBookId.isEmpty()) pendingPage = "readerShelf";
            handleShare(getIntent());
        }
        loadUi();
        try {
            // 原生插件跟着 App 启动一起拉起；停用/删除的在这里被清掉
            DexPlugins.sync(this);
        } catch (Throwable ignored) {
        }
        sink = (event, data) -> h.post(() -> {
            if (!loaded || web == null) return;
            web.evaluateJavascript("window.__event&&window.__event(" + JSONObject.quote(event) + "," + data + ")", null);
        });
    }

    /**
     * 把页面对 webp 的请求直接映射到 assets/webp/。
     * 加固包的前端是从私有目录以 file:// 加载的，此时再去请求 file:///android_asset 会被 WebView
     * 当成跨源请求拦掉，表现为立绘和头像全部只剩 alt 文本。这里由原生直接供流，绕开同源限制，
     * 加固包与快速包都适用。
     */
    private WebResourceResponse assetWebp(String url) {
        if (url == null || url.isEmpty()) return null;
        int at = url.indexOf("/webp/");
        if (at < 0) return null;
        String name = url.substring(at + 6);
        if (name.isEmpty() || name.startsWith(".")) return null;
        // 只允许单层文件名，避免目录穿越
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            boolean ok = (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')
                    || (ch >= '0' && ch <= '9') || ch == '_' || ch == '-' || ch == '.';
            if (!ok) return null;
        }
        String lower = name.toLowerCase();
        String mime = lower.endsWith(".png") ? "image/png"
                : (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) ? "image/jpeg"
                : "image/webp";
        try {
            return new WebResourceResponse(mime, null, getAssets().open("webp/" + name));
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 加载控制台：加固包从解密目录读 index.html，用 loadDataWithBaseURL 挂到该目录，
     * 这样 app.js / app.css / ../webp 的相对路径仍然成立；-Fast 包没有 sec.bin，
     * 自动退回原来的 file:///android_asset 方式。
     */
    private void loadUi() {
        boolean sealed = WebUI.sealed(this);
        try {
            File dir = WebUI.prepare(this);
            String html = WebUI.readText(this, "ui/index.html");
            if (dir != null && !html.isEmpty()) {
                // sec.bin 里的条目名带 ui/ 前缀，解密后实际落在 files/ui/ui/ 下；
                // baseUrl 必须是 index.html 所在的真实目录，否则 app.js / app.css 会 404，只剩无样式骨架
                String base = "file://" + new File(dir, "ui").getAbsolutePath() + "/";
                web.loadDataWithBaseURL(base, html, "text/html", "UTF-8", null);
                return;
            }
        } catch (Throwable ignored) {
        }
        // 加固包里资源是密文，解不出来就别给用户一片空白，直接说清楚
        if (sealed) {
            showBlocked("前端资源解密失败，安装包可能已损坏或被改写。");
            return;
        }
        web.loadUrl("file:///android_asset/ui/index.html");
    }

    /** 环境异常时的原生提示页（不依赖 WebView，也不读取用户数据）。 */
    private void showBlocked(String reason) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(0xFF07090F);
        int pad = (int) (getResources().getDisplayMetrics().density * 28);
        box.setPadding(pad, pad, pad, pad);
        TextView title = new TextView(this);
        title.setText("无法启动");
        title.setTextColor(0xFFEEF2FF);
        title.setTextSize(20);
        title.setGravity(Gravity.CENTER);
        TextView msg = new TextView(this);
        msg.setText(reason + "\n\n请使用官方渠道的安装包；你的 S-G 数据没有被改动。");
        msg.setTextColor(0xFF8B96AD);
        msg.setTextSize(13);
        msg.setGravity(Gravity.CENTER);
        msg.setPadding(0, pad / 2, 0, 0);
        box.addView(title);
        box.addView(msg);
        setContentView(box);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 打开应用也算一次互动：主动闲聊先让路，未回应计数清零
        long prevUserAt = 0;
        try {
            prevUserAt = Interact.lastUserAt(this);
            Interact.onEngage(this);
        } catch (Throwable ignored) {
        }
        // 本地问候：4 小时间隔、每天最多 2 次，只在应用回到前台时触发
        try {
            Greet.maybe(this, prevUserAt);
        } catch (Throwable ignored) {
        }
        // 重新打开应用即视为解锁，避免桌宠一直半透明又拖不动
        try {
            Cfg cfg = Cfg.load(this);
            if (cfg.b("locked", false)) {
                cfg.set("locked", false);
                cfg.save(this);
                startService(new Intent(this, PetSvc.class).setAction(PetSvc.A_LOCK).putExtra("lock", false));
                h.postDelayed(this::emitState, 500);
            }
        } catch (Throwable ignored) {
        }
        Bus.add(sink);
        if (loaded && web != null) web.evaluateJavascript("window.__event&&window.__event('resume',{})", null);
    }

    @Override
    protected void onPause() {
        Bus.remove(sink);
        Live2DRenderer.setMemoryGraphVisible(false);
        if (web != null) web.evaluateJavascript("window.__event&&window.__event('hostPause',{})", null);
        super.onPause();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String bookId = intent == null ? null : intent.getStringExtra("readerBookId");
        if (bookId != null && !bookId.isEmpty()) {
            if (loaded && web != null) {
                web.evaluateJavascript("window.__goto&&window.__goto('readerShelf')", null);
                web.evaluateJavascript("window.__openBookFromHost&&window.__openBookFromHost("
                        + JSONObject.quote(bookId) + ")", null);
            } else {
                pendingPage = "readerShelf";
                pendingReaderBookId = bookId;
            }
        }
        if ((bookId == null || bookId.isEmpty()) && intent != null && intent.getStringExtra("page") != null) {
            String p = intent.getStringExtra("page");
            if (loaded) web.evaluateJavascript("window.__goto&&window.__goto(" + JSONObject.quote(p) + ")", null);
            else pendingPage = p;
        }
        if (intent != null) handleShare(intent);
    }

    private void handleShare(Intent intent) {
        String action = intent.getAction();
        if (!Intent.ACTION_SEND.equals(action) && !Intent.ACTION_PROCESS_TEXT.equals(action)) return;
        Uri stream = null;
        try {
            stream = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        } catch (Throwable ignored) {
        }
        if (stream != null) {
            final Uri uri = stream;
            pendingPage = "readerShelf";
            worker.execute(() -> {
                JSONObject r = Books.importUri(this, uri);
                event("books", Books.list(this));
                event("toast", msgJson(r.optBoolean("ok") ? "书籍已导入书架" : r.optString("error", "导入失败")));
            });
            return;
        }
        String text = intent.getStringExtra(Intent.EXTRA_TEXT);
        if (text == null) text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT) == null ? null
                : intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT).toString();
        if (text == null || text.trim().isEmpty()) return;
        JSONObject o = Clip.add(this, Clip.mask(text), "share", "", "share");
        pendingPage = "clip";
        event("clip", o);
    }

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent e) {
        // 只在阅读页拦截音量键：音量减=下一页，音量加=上一页
        if (readerKeys && web != null) {
            int code = e.getKeyCode();
            if (code == android.view.KeyEvent.KEYCODE_VOLUME_DOWN || code == android.view.KeyEvent.KEYCODE_VOLUME_UP) {
                if (e.getAction() == android.view.KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                    int dir = code == android.view.KeyEvent.KEYCODE_VOLUME_DOWN ? 1 : -1;
                    web.evaluateJavascript("window.__readerKey&&window.__readerKey(" + dir + ")", null);
                }
                return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    public void onBackPressed() {
        if (web == null) {
            super.onBackPressed();
            return;
        }
        web.evaluateJavascript("(window.__back&&window.__back())?'true':'false'", new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String value) {
                if (!"\"true\"".equals(value)) finish();
            }
        });
    }

    /**
     * 家人彩蛋：先封印配置，再走完整条时序。
     * 时序必须在原生层编排——控制台 finish 后 JS 会销毁，临时放大/恢复只能交给 PetSvc。
     */
    private void sealFamily() {
        try {
            Cfg cfg = Cfg.load(this);
            cfg.set("familySealed", true);
            cfg.set("devLocked", true);
            cfg.set("familyDayCap", Intimacy.FAMILY_SEALED_CAP);
            cfg.save(this);
            showFamilyBang();
            PetSvc.grow(this, Cfg.MAX_SIZE, 9000L); // 气泡结束后恢复原尺寸
            h.postDelayed(() -> PetSvc.sayImportant(this, "我一直在"), 1500);
            h.postDelayed(() -> {
                if (isFinishing()) return;
                finish();
            }, 2000);
        } catch (Throwable ignored) {
        }
    }

    /** 全屏红色感叹号：显示 1.4 秒后自动移除。 */
    private void showFamilyBang() {
        try {
            TextView t = new TextView(this);
            t.setText("!");
            t.setTextColor(0xFFFF2F3E);
            t.setTextSize(220);
            t.setGravity(Gravity.CENTER);
            t.setBackgroundColor(0xF2000000);
            android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT);
            addContentView(t, lp);
            h.postDelayed(() -> {
                try {
                    android.view.ViewGroup parent = (android.view.ViewGroup) t.getParent();
                    if (parent != null) parent.removeView(t);
                } catch (Throwable ignored) {
                }
            }, 1400);
        } catch (Throwable ignored) {
        }
    }

    private void result(String id, JSONObject o) {
        h.post(() -> {
            if (web == null) return;
            web.evaluateJavascript("window.__nativeResult&&window.__nativeResult(" + JSONObject.quote(id) + "," + o + ")", null);
        });
    }

    private void event(String name, JSONObject o) {
        h.post(() -> {
            if (web == null) return;
            web.evaluateJavascript("window.__event&&window.__event(" + JSONObject.quote(name) + "," + o + ")", null);
        });
    }

    private JSONObject dispatch(String method, JSONObject a) {
        try {
            switch (method) {
                case "boot":
                    return boot();
                case "qqLinkStatus":
                    return QQOneBot.refreshStatus(this);
                case "qqPersonas":
                    return QQPersonas.state(this);
                case "qqPersonaSave":
                    return QQPersonas.save(this, a.optString("id", ""), a.optString("name", ""),
                            a.optString("prompt", ""), "manual");
                case "qqPersonaDelete":
                    return QQPersonas.delete(this, a.optString("id", ""));
                case "qqPersonaSelect":
                    return QQPersonas.select(this, a.optString("id", ""));
                case "qqPersonaImport": {
                    Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    pick.addCategory(Intent.CATEGORY_OPENABLE);
                    pick.setType("*/*");
                    pick.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "text/plain", "text/markdown", "image/png"});
                    startActivityForResult(pick, RQ_QQ_PERSONA_IMPORT);
                    return pending("请选择 Character Card JSON、PNG 或 TXT/MD 人设卡");
                }
                case "comfyUiWorkflowImport": {
                    Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    pick.addCategory(Intent.CATEGORY_OPENABLE);
                    pick.setType("*/*");
                    pick.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "text/plain", "application/octet-stream"});
                    startActivityForResult(pick, RQ_COMFY_WORKFLOW_IMPORT);
                    return pending("请选择 ComfyUI API 工作流 JSON 文件");
                }
                case "qqLinkSave": {
                    Cfg cfg = Cfg.load(this);
                    String url = a.optString("url", cfg.str("qqOnebotUrl", "")).trim();
                    String allowed = a.optString("allowedUserId", cfg.str("qqAllowedUserId", "")).trim();
                    String groupIds = a.optString("groupIds", cfg.str("qqGroupIds", "")).trim();
                    String groupMode = a.optString("groupMode", cfg.str("qqGroupMode", "mention")).trim();
                    boolean groupAstrBot = a.optBoolean("groupAstrBot", cfg.b("qqGroupAstrBot", false));
                    boolean privateAstrBot = a.optBoolean("privateAstrBot", cfg.b("qqPrivateAstrBot", false));
                    String groupPersonaId = a.optString("personaId", cfg.str("qqGroupPersonaId", "")).trim();
                    String token = a.optString("token", "").trim();
                    if (url.length() > 2048 || url.contains("\r") || url.contains("\n")) {
                        return err("连接地址过长或格式不正确");
                    }
                    if (!url.isEmpty() && !(url.startsWith("ws://") || url.startsWith("wss://"))) {
                        return err("连接地址要以 ws:// 或 wss:// 开头");
                    }
                    if (token.length() > 512 || token.contains("\r") || token.contains("\n")) {
                        return err("OneBot Token 不能超过 512 个字符，也不能包含换行");
                    }
                    if (!allowed.isEmpty() && !allowed.matches("[0-9]{5,12}")) {
                        return err("私聊 QQ 号应为 5 到 12 位数字");
                    }
                    if (!QQOneBot.validGroupIds(groupIds)) return err("群号请填写 5 到 15 位数字，最多 20 个，用逗号分隔");
                    if (!QQPersonas.contains(this, groupPersonaId)) return err("所选群聊人设卡已不存在，请重新选择");
                    if (groupAstrBot && !AstrBotBridge.agentEnabled(this))
                        return err("请先到「功能中心 → 插件」连接并启用 AstrBot 智能体，再开启群聊插件模式");
                    if (privateAstrBot && !AstrBotBridge.pluginCommandsAvailable(this))
                        return err("请先到「功能中心 → 插件」连接 AstrBot（保存地址和 Key），再开启私聊插件模式");
                    if (!"mention".equals(groupMode) && !"participate".equals(groupMode)) groupMode = "mention";
                    cfg.set("qqOnebotUrl", url);
                    cfg.set("qqAllowedUserId", allowed);
                    cfg.set("qqGroupIds", QQOneBot.normalizeGroupIds(groupIds));
                    cfg.set("qqGroupMode", groupMode);
                    cfg.set("qqGroupAstrBot", groupAstrBot);
                    cfg.set("qqPrivateAstrBot", privateAstrBot);
                    cfg.set("qqGroupPersonaId", groupPersonaId);
                    if (!token.isEmpty()) cfg.set("qqOnebotToken", token);
                    cfg.save(this);
                    if (cfg.b("qqLinkEnabled", false)) {
                        QQOneBot.sync(this);
                        PetSvc.start(this);
                    }
                    return ok().put("status", QQOneBot.status(this));
                }
                case "qqLinkConnect": {
                    Cfg cfg = Cfg.load(this);
                    boolean remoteGroupReady = cfg.b("qqGroupAstrBot", false) && AstrBotBridge.agentEnabled(this);
                    boolean remotePrivateReady = cfg.b("qqPrivateAstrBot", false) && AstrBotBridge.pluginCommandsAvailable(this);
                    if (!cfg.aiReady() && !remoteGroupReady && !remotePrivateReady)
                        return err("先在「设置 → 智能核心」配置聊天 API Key，或先启用群聊 AstrBot 智能体 / 私聊插件模式");
                    if (cfg.str("qqOnebotUrl", "").trim().isEmpty()
                            || cfg.str("qqOnebotToken", "").trim().isEmpty()
                            || !QQOneBot.hasTarget(cfg)) {
                        return err("先保存 NapCat 地址、OneBot Token，并至少填写一个允许私聊 QQ 号或群号");
                    }
                    cfg.set("qqLinkEnabled", true);
                    cfg.save(this);
                    PetSvc.start(this);
                    return ok().put("status", QQOneBot.status(this));
                }
                case "qqLinkDisconnect": {
                    Cfg cfg = Cfg.load(this);
                    cfg.set("qqLinkEnabled", false);
                    cfg.save(this);
                    QQOneBot.stopAndSummarize();
                    if (cfg.b("running", false)) PetSvc.send(this, PetSvc.A_CONFIG, null, null);
                    else PetSvc.send(this, PetSvc.A_STOP, null, null);
                    return ok().put("status", QQOneBot.status(this));
                }
                case "saveCfg": {
                    Cfg cfg = Cfg.load(this);
                    JSONObject before = cfg.json();
                    JSONObject o = a.optJSONObject("json");
                    cfg.merge(o);
                    // 用户手动关掉一键静默时，同时清掉「静默 1 小时/到明天」的定时静默
                    if (o != null && o.has("polQuiet") && !o.optBoolean("polQuiet", false)) {
                        cfg.set("polQuietUntil", 0L);
                    }
                    cfg.save(this);
                    // 保活开关：关掉就撤掉看门狗，打开且桌宠在跑就重新挂上
                    if (o != null && o.has("keepAlive")) {
                        if (o.optBoolean("keepAlive", true) && cfg.b("running", false)) {
                            Alive.schedule(this);
                        } else {
                            Alive.cancel(this);
                        }
                    }
                    // 应用感知：记下她注意到的设置变化，用于聊天接话
                    if (o != null) {
                        try {
                            StringBuilder changed = new StringBuilder();
                            java.util.Iterator<String> it = o.keys();
                            int n = 0;
                            while (it.hasNext() && n < 3) {
                                String k = it.next();
                                Object oldV = before.opt(k);
                                Object newV = o.opt(k);
                                if (oldV != null && String.valueOf(oldV).equals(String.valueOf(newV))) continue;
                                String label = InApp.cfgLabel(k);
                                if (changed.length() > 0) changed.append('、');
                                changed.append(label);
                                n++;
                            }
                            if (changed.length() > 0) InApp.track(this, "设置", "改设置", changed.toString());
                        } catch (Throwable ignored) {
                        }
                    }
                    A11y.reload();
                    String apply = a.optString("apply", "general");
                    if (cfg.b("running", false)) {
                        if ("size".equals(apply)) PetSvc.send(this, PetSvc.A_SIZE, null, null);
                        else if ("opacity".equals(apply)) PetSvc.send(this, PetSvc.A_OPACITY, null, null);
                        else if ("timer".equals(apply)) PetSvc.send(this, PetSvc.A_CONFIG, null, null);
                        else PetSvc.send(this, PetSvc.A_RELOAD, null, null);
                    }
                    return ok();
                }
                case "startPet": {
                    if (!Settings.canDrawOverlays(this)) {
                        Perm.open(this, "overlay");
                        return err("请先授予悬浮窗权限");
                    }
                    Cfg cfg = Cfg.load(this);
                    cfg.set("running", true);
                    cfg.save(this);
                    PetSvc.start(this);
                    h.postDelayed(this::emitState, 600);
                    return ok();
                }
                case "stopPet": {
                    Cfg cfg = Cfg.load(this);
                    cfg.set("running", false);
                    cfg.save(this);
                    PetSvc.send(this, PetSvc.A_STOP, null, null);
                    h.postDelayed(this::emitState, 600);
                    return ok();
                }
                case "setLock": {
                    boolean lock = a.optBoolean("lock", false);
                    Cfg cfg = Cfg.load(this);
                    cfg.set("locked", lock);
                    cfg.save(this);
                    if (cfg.b("running", false)) {
                        Intent i = new Intent(this, PetSvc.class).setAction(PetSvc.A_LOCK).putExtra("lock", lock);
                        try {
                            startService(i);
                        } catch (Throwable ignored) {
                        }
                    }
                    h.postDelayed(this::emitState, 400);
                    return ok();
                }
                case "say": {
                    PetSvc.send(this, PetSvc.A_SAY, "text", a.optString("text", ""));
                    return ok();
                }
                case "familySeal": {
                    sealFamily();
                    return ok();
                }
                case "preview": {
                    PetSvc.send(this, PetSvc.A_PREVIEW, "slug", a.optString("slug", ""));
                    return ok();
                }
                case "state": {
                    emitState();
                    return stateObj();
                }
                case "proactiveNow": {
                    if (!Cfg.load(this).aiReady()) return err("先绑定 API Key");
                    CountDownLatch latch = new CountDownLatch(1);
                    final String[] out = {null};
                    Brain.proactive(this, new Ai.Cb() {
                        @Override
                        public void ok(String t) {
                            out[0] = t;
                            latch.countDown();
                        }

                        @Override
                        public void fail(String e) {
                            out[0] = "[ERR]" + e;
                            latch.countDown();
                        }
                    });
                    if (!latch.await(60, TimeUnit.SECONDS)) return err("回复超时");
                    String t = out[0];
                    if (t != null && t.startsWith("[ERR]")) return err(t.substring(5));
                    PetSvc.send(this, PetSvc.A_SAY, "text", t);
                    JSONObject r = ok();
                    r.put("text", t);
                    return r;
                }
                case "chat": {
                    String text = a.optString("text", "");
                    final String streamId = a.optString("streamId", "");
                    final JSONObject quote = a.optJSONObject("quote");
                    final boolean hasQuote = quote != null && !quote.optString("content", "").trim().isEmpty();
                    String fileText = a.optString("fileText", "");
                    String fileName = a.optString("fileName", "");
                    String fileId = a.optString("fileId", "");
                    final boolean hasFile = !fileId.isEmpty();
                    if (hasFile) {
                        try { fileText = ChatFiles.readText(this, fileId); }
                        catch (Throwable failure) { return err(failure.getMessage() == null ? "读取文档失败，请重新上传" : failure.getMessage()); }
                    }
                    String videoId = a.optString("videoId", "");
                    final boolean hasVideo = !videoId.isEmpty();
                    final File videoFile = hasVideo ? ChatVideos.file(this, videoId) : null;
                    if (hasVideo && videoFile == null) return err("暂存的视频已过期或不存在，请重新选择后发送。");
                    final String videoName = hasVideo ? ChatVideos.name(this, videoId) : "";
                    final String videoMime = hasVideo ? ChatVideos.mimeType(this, videoId) : "";
                    final boolean hasAttachment = hasFile || hasVideo;
                    if (hasFile && text.trim().isEmpty()) text = "请帮我总结并分析这个文件。";
                    if (hasVideo && text.trim().isEmpty()) text = "请完整观看这段视频，概括画面变化、声音和对白，并回答我注意到的重点。";
                    final boolean voiceReply = Voice.wantsVoiceReply(text);
                    JSONObject drawStatus = hasQuote ? null : StickerPainter.statusFor(this, text);
                    if (drawStatus != null) {
                        String reply = drawStatus.optString("text", "我还在画，先给你看当前草稿。");
                        String name = drawStatus.optString("name", "");
                        Data.chatAppend(this, "user", text);
                        Data.chatAppend(this, "assistant", reply);
                        if (!name.isEmpty()) {
                            Data.chatAppend(this, "assistant", "[[表情:" + name + "]]");
                            try {
                                Bus.emit("chatSticker", new JSONObject().put("name", name));
                            } catch (Throwable ignored) {
                            }
                        }
                        return ok().put("text", reply);
                    }
                    // 聊天页发的图片：跳过本地问答/插件链路，直接走带图聊天
                    String imageData = a.optString("image", "");
                    final Bitmap picked = decodeDataUrl(imageData);
                    if (!imageData.trim().isEmpty() && picked == null) {
                        return err("图片附件没有成功读取，这次没有发送给 AI。请移除这张图片后重新选择，再发送。");
                    }
                    if (picked == null && !hasAttachment && !hasQuote) {
                        String consentReply = NearbySearch.handleConsent(this, text);
                        if (!consentReply.isEmpty()) {
                            Data.chatAppend(this, "user", text);
                            Data.chatAppend(this, "assistant", consentReply);
                            PetSvc.send(this, PetSvc.A_SAY, "text", consentReply);
                            if (!voiceReply) Voice.speak(this, consentReply, true);
                            return ok().put("text", consentReply).put("local", true);
                        }
                    }
                    if (picked == null && !hasAttachment && !hasQuote && DshBoxBridge.wantsRemote(text)) {
                        String reply;
                        try {
                            JSONObject result = DshBoxBridge.analyze(this, DshBoxBridge.remotePrompt(text));
                            reply = result.optString("text", "DSHBox 没有返回分析内容。");
                        } catch (Throwable t) {
                            reply = t.getMessage() == null ? "DSHBox 当前不可用，请先检查插件页里的连接状态。" : t.getMessage();
                        }
                        Data.chatAppend(this, "user", text);
                        Data.chatAppend(this, "assistant", reply);
                        PetSvc.send(this, PetSvc.A_SAY, "text", reply);
                        if (!voiceReply) Voice.speak(this, reply, true);
                        return ok().put("text", reply).put("local", true);
                    }
                    // 统一由模型结合当前消息和近期上下文选动作，避免各模块用关键词抢话题。
                    final String intent = picked == null && !hasAttachment && !hasQuote
                            ? IntentRouter.route(this, text) : IntentRouter.CHAT;
                    if (Ai.isStreamCancelled(streamId)) return err("已停止生成");
                    boolean travelPlan = IntentRouter.TRAVEL.equals(intent);
                    boolean weatherAsk = IntentRouter.WEATHER.equals(intent);
                    boolean nearbyFood = IntentRouter.NEARBY_FOOD.equals(intent);
                    boolean nearbyPlace = IntentRouter.NEARBY_PLACE.equals(intent);
                    boolean nearbyShop = IntentRouter.NEARBY_SHOP.equals(intent);
                    boolean nearbyAsk = nearbyFood || nearbyPlace || nearbyShop;
                    String nearbyKind = nearbyPlace ? "place" : nearbyShop ? "shop" : "food";
                    // 工具结果带明确状态和来源；工具失败时直接说明，不让模型补造地点或天气。
                    final JSONObject weatherResult = weatherAsk || travelPlan
                            ? Weather.lookupForChat(this) : null;
                    final JSONObject nearbyResult = nearbyAsk ? NearbySearch.lookupForChat(this, nearbyKind) : null;
                    final JSONObject toolResult = weatherResult != null ? weatherResult : nearbyResult;
                    final String toolEvidence = toolResult == null ? "" : toolResult.toString();
                    if ((weatherAsk && weatherResult != null && !weatherResult.optBoolean("ok", false))
                            || (nearbyAsk && nearbyResult != null && !nearbyResult.optBoolean("ok", false))) {
                        JSONObject failedTool = weatherAsk ? weatherResult : nearbyResult;
                        String fallback = weatherAsk ? "天气这次没查成功，稍后再试一次吧。" : "附近地点这次没查成功，我不想编一个给你。";
                        String reply = failedTool == null ? fallback : failedTool.optString("error", fallback);
                        Data.chatAppend(this, "user", text);
                        JSONObject extra = new JSONObject();
                        if (voiceReply) {
                            extra.put("voice", true);
                            extra.put("voiceText", reply);
                        }
                        Data.chatAppend(this, "assistant", reply, "", "", voiceReply ? extra : null);
                        PetSvc.send(this, PetSvc.A_SAY, "text", reply);
                        if (!voiceReply) Voice.speak(this, reply, true);
                        return ok().put("text", reply).put("local", true).put("voiceReply", voiceReply);
                    }
                    if (picked == null && !hasQuote && IntentRouter.DRAW.equals(intent)) {
                        Data.chatAppend(this, "user", text);
                        StickerPainter.startDirect(this, text);
                        return ok().put("text", "我开始画啦，画好会自己发给你。你也可以随时问我画到哪了。")
                                .put("local", true);
                    }
                    if (picked == null && !hasAttachment && !hasQuote && AdRules.isAdRuleCreationRequest(text)) {
                        String reply = AdRules.enableBuiltInFromChat(this);
                        Data.chatAppend(this, "user", text);
                        Data.chatAppend(this, "assistant", reply);
                        PetSvc.send(this, PetSvc.A_SAY, "text", reply);
                        if (!voiceReply) Voice.speak(this, reply, true);
                        return ok().put("text", reply).put("local", true).put("voiceReply", voiceReply);
                    }
                    // 明确要求写插件时优先进入插件生成器，不能被“看屏幕/识别屏幕”抢走。
                    final boolean pluginIntent = picked == null && !hasAttachment && IntentRouter.PLUGIN.equals(intent);
                    boolean screenAsk = picked == null && !hasAttachment && !pluginIntent && IntentRouter.SCREEN.equals(intent);
                    if (picked == null && !hasAttachment && !hasQuote) {
                        final String[] directReply = {""};
                        boolean directHandled = CompanionSession.handleDirect(this, text, new Ai.Cb() {
                            @Override public void ok(String value) { directReply[0] = value; }
                            @Override public void fail(String error) { directReply[0] = error; }
                        });
                        if (directHandled) {
                            String answer = directReply[0];
                            PetSvc.send(this, PetSvc.A_SAY, "text", answer);
                            if (!voiceReply) Voice.speak(this, answer, true);
                            return ok().put("text", answer).put("local", true);
                        }
                    }
                    if (picked == null && !hasAttachment && !hasQuote && CompanionSession.hasPending(this)) {
                        final String[] sessionReply = {""};
                        boolean handled = CompanionSession.handle(this, text, new Ai.Cb() {
                            @Override public void ok(String value) { sessionReply[0] = value; }
                            @Override public void fail(String error) { sessionReply[0] = error; }
                        });
                        if (handled) {
                            String answer = sessionReply[0];
                            PetSvc.send(this, PetSvc.A_SAY, "text", answer);
                            if (!voiceReply) Voice.speak(this, answer, true);
                            return ok().put("text", answer).put("local", true);
                        }
                    }
                    if (picked == null && !hasAttachment && !hasQuote && ReaderFlow.hasPending(this)) {
                        final String[] readerReply = {""};
                        boolean handled = ReaderFlow.handle(this, text, new Ai.Cb() {
                            @Override public void ok(String value) { readerReply[0] = value; }
                            @Override public void fail(String error) { readerReply[0] = error; }
                        });
                        if (handled) {
                            String answer = readerReply[0];
                            PetSvc.send(this, PetSvc.A_SAY, "text", answer);
                            if (!voiceReply) Voice.speak(this, answer, true);
                            return ok().put("text", answer).put("local", true);
                        }
                    }
                    if (screenAsk) {
                        // 问屏幕内容时只即时读一次，平时不抓
                        final String[] screen = {""};
                        final boolean[] serviceOn = {false};
                        CountDownLatch shotLatch = new CountDownLatch(1);
                        A11y.readNow((shotText, svc) -> {
                            screen[0] = shotText;
                            serviceOn[0] = svc;
                            shotLatch.countDown();
                        });
                        try {
                            shotLatch.await(2, TimeUnit.SECONDS);
                        } catch (Throwable ignored) {
                        }
                        String noScreen = null;
                        if (!serviceOn[0]) {
                            noScreen = A11y.get() == null
                                    ? "我现在看不到屏幕，需要先在「权限体检」里开启无障碍服务。"
                                    : "这次没读到屏幕，再问我一次试试。";
                        } else if (screen[0] == null || screen[0].trim().isEmpty()) {
                            // 在应用自己的聊天页里，前台就是本应用，读不到别的应用；这里说清楚，
                            // 否则用户会误以为是模型不支持看图。
                            noScreen = "你现在就在我自己的聊天页里，屏幕上只有咱们的对话，读不到别的应用。"
                                    + "想让我看屏幕，就把聊天窗收起来、切到那个应用再问我。"
                                    + "当前模型：" + Cfg.load(this).str("model", "deepseek-chat") + "。";
                        }
                        if (noScreen != null) {
                            if (IntentRouter.SCREEN.equals(intent)) {
                                Data.chatAppend(this, "user", text);
                                JSONObject extra = new JSONObject();
                                if (voiceReply) {
                                    extra.put("voice", true);
                                    extra.put("voiceText", noScreen);
                                }
                                Data.chatAppend(this, "assistant", noScreen, "", "", voiceReply ? extra : null);
                                PetSvc.send(this, PetSvc.A_SAY, "text", noScreen);
                                if (!voiceReply) Voice.speak(this, noScreen, true);
                                return ok().put("text", noScreen).put("local", true)
                                        .put("voiceReply", voiceReply);
                            }
                            // 宽泛问法读不到屏幕时回退普通聊天，避免把「这个功能是什么」答成看不到屏幕
                            screenAsk = false;
                        }
                    }
                    if (picked == null && !hasAttachment && !screenAsk && !pluginIntent
                            && IntentRouter.LOCAL.equals(intent) && Cfg.load(this).b("localNlu", true)) {
                        JSONObject n = Nlu.handle(this, text);
                        if (n.optBoolean("handled", false)) {
                            String reply = n.optString("reply", "好。");
                            String link = n.optString("link", "");
                            String linkText = n.optString("linkText", "");
                            Data.chatAppend(this, "user", text);
                            JSONObject extra = new JSONObject();
                            if (voiceReply) {
                                extra.put("voice", true);
                                extra.put("voiceText", reply);
                            }
                            Data.chatAppend(this, "assistant", reply, link, linkText, voiceReply ? extra : null);
                            PetSvc.send(this, PetSvc.A_SAY, "text", reply);
                            if (!voiceReply) Voice.speak(this, reply, true);
                            JSONObject r = ok().put("text", reply).put("local", true)
                                    .put("voiceReply", voiceReply);
                            if (!link.isEmpty()) r.put("link", link).put("linkText", linkText);
                            return r;
                        }
                    }
                    CountDownLatch latch = new CountDownLatch(1);
                    final String[] out = {null};
                    // 让她在聊天里现写插件：先生成草稿，用户回「装上」才真正安装
                    if (pluginIntent) {
                        PluginGen.talk(this, text, new Ai.Cb() {
                            @Override
                            public void ok(String t) {
                                out[0] = t;
                                latch.countDown();
                            }

                            @Override
                            public void fail(String e) {
                                out[0] = "[ERR]" + e;
                                latch.countDown();
                            }
                        });
                        if (!latch.await(45, TimeUnit.SECONDS)) return err("插件处理超时");
                        String pr = out[0] == null ? "" : out[0];
                        if (pr.startsWith("[ERR]")) pr = pr.substring(5);
                        if (pr.trim().isEmpty()) pr = "这个我先不会，换个说法再试试。";
                        Data.chatAppend(this, "user", text);
                        Data.chatAppend(this, "assistant", pr);
                        PetSvc.send(this, PetSvc.A_SAY, "text", pr);
                        Voice.speak(this, pr, true);
                        return ok().put("text", pr);
                    }
                    Ai.Cb chatCb = new Ai.Cb() {
                        @Override
                        public void progress(String delta) {
                            try {
                                event("chatDelta", new JSONObject().put("streamId", streamId).put("delta", delta));
                            } catch (Throwable ignored) { }
                        }

                        @Override
                        public void ok(String t) {
                            out[0] = t;
                            latch.countDown();
                        }

                        @Override
                        public void fail(String e) {
                            out[0] = "[ERR]" + e;
                            latch.countDown();
                        }
                    };
                    if (hasFile) {
                        if (hasQuote) Brain.chatDocumentWithQuote(this, text, fileName, fileId, fileText, quote, chatCb);
                        else Brain.chatDocument(this, text, fileName, fileId, fileText, chatCb);
                    } else if (hasVideo) {
                        Brain.chatVideo(this, text, videoName, videoId, videoFile, videoMime, quote, chatCb);
                    } else if (picked != null) {
                        if (hasQuote) Brain.chatExtraWithQuote(this, text.trim().isEmpty() ? "看看这张图。" : text, "", picked, quote, chatCb);
                        else Brain.chatExtra(this, text.trim().isEmpty() ? "看看这张图。" : text, "", picked, chatCb);
                    } else if (!toolEvidence.isEmpty()) {
                        if (hasQuote) Brain.chatWithEvidenceAndQuote(this, text, toolEvidence, streamId, quote, chatCb);
                        else Brain.chatWithEvidence(this, text, toolEvidence, streamId, chatCb);
                    } else {
                        if (hasQuote) Brain.chatWithQuote(this, text, IntentRouter.WRITE.equals(intent), streamId, quote, chatCb);
                        else Brain.chat(this, text, IntentRouter.WRITE.equals(intent), streamId, chatCb);
                    }
                    long waitSeconds = hasVideo ? 30L * 60L : (picked == null && !hasFile ? 180L : 60L);
                    if (!latch.await(waitSeconds, TimeUnit.SECONDS)) return err(hasVideo
                            ? "视频上传或理解仍在进行，等待超时；请检查网络后重新发送。"
                            : "回复超时");
                    String t = out[0];
                    if (t != null && t.startsWith("[ERR]")) return err(t.substring(5));
                    if (LongDocs.isCardMarker(t)) return ok().put("text", t);
                    // 表情标记交给前端解析成图片，朗读和桌宠气泡用去掉标记的文本
                    String spoken = Sticker.strip(t);
                    // 只发一张表情时文字是空的，此时只走前端事件，不要冒空气泡
                    if (!spoken.isEmpty()) {
                        PetSvc.send(this, PetSvc.A_SAY, "text", spoken);
                        if (!voiceReply) Voice.speak(this, spoken, true);
                    }
                    JSONObject r = ok();
                    r.put("text", t);
                    r.put("voiceReply", voiceReply);
                    return r;
                }
                case "history": {
                    JSONObject r = ok();
                    // 顺手清掉超过一个月的聊天图片，再返回历史
                    Data.cleanChatImages(this);
                    JSONObject page = Data.chatPage(this, a.optLong("cursor", -1), 60);
                    r.put("messages", page.optJSONArray("messages"));
                    r.put("cursor", page.optLong("cursor", 0));
                    r.put("hasMore", page.optBoolean("hasMore", false));
                    r.put("greeting", Data.persona(this).optString("greeting", ""));
                    return r;
                }
                case "longDocInfo":
                    return LongDocs.info(this, a.optString("id", ""));
                case "longDocGet":
                    return LongDocs.get(this, a.optString("id", ""));
                case "longDocExport": {
                    pendingLongDocId = a.optString("id", "");
                    pendingLongDocFormat = a.optString("format", "md");
                    JSONObject info = LongDocs.info(this, pendingLongDocId);
                    if (!info.optBoolean("ok", false) || info.optBoolean("expired", true)) return err("文档已过期或找不到");
                    String format = pendingLongDocFormat.toLowerCase(java.util.Locale.ROOT).replace(".", "");
                    if (!("md".equals(format) || "txt".equals(format) || "jar".equals(format))) return err("仅支持 MD、TXT 和 JAR");
                    pickCreateLongDoc(sanitize(info.optString("title", "长文档")) + "." + format, format);
                    return pending("请选择文件保存位置…");
                }
                case "chatPickImage": {
                    // 聊天页选图：走和悬浮窗同一个透明中转页，选完把 base64 回给前端预览
                    PickAct.open(this, bmp -> {
                        JSONObject out = new JSONObject();
                        if (bmp != null) {
                            String data = Brain.imageData(bmp);
                            if (!data.isEmpty()) {
                                try {
                                    out.put("data", data);
                                } catch (Throwable ignored) {
                                }
                            }
                        } else {
                            try {
                                out.put("cancel", true);
                            } catch (Throwable ignored) {
                            }
                        }
                        event("chatImage", out);
                    });
                    return ok();
                }
                case "chatTakePhoto": {
                    try {
                        Intent camera = new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE);
                        if (camera.resolveActivity(getPackageManager()) == null) return err("没有可用的相机应用");
                        startActivityForResult(camera, RQ_CHAT_CAMERA);
                        return ok();
                    } catch (Throwable t) { return err("无法打开相机"); }
                }
                case "chatPickFile": {
                    try {
                        Intent file = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        file.addCategory(Intent.CATEGORY_OPENABLE);
                        file.setType("*/*");
                        file.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"text/*", "application/json", "application/xml",
                                "application/pdf", "application/msword", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                "application/vnd.ms-excel", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                "application/vnd.ms-powerpoint", "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                                "application/vnd.oasis.opendocument.text", "application/vnd.oasis.opendocument.spreadsheet",
                                "application/vnd.oasis.opendocument.presentation"});
                        startActivityForResult(file, RQ_CHAT_FILE);
                        return ok();
                    } catch (Throwable t) { return err("无法打开文件选择器"); }
                }
                case "chatPickVideo": {
                    try {
                        Intent video = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        video.addCategory(Intent.CATEGORY_OPENABLE);
                        video.setType("video/*");
                        startActivityForResult(video, RQ_CHAT_VIDEO);
                        return ok();
                    } catch (Throwable t) { return err("无法打开视频选择器"); }
                }
                case "chatVideoDelete": {
                    ChatVideos.delete(this, a.optString("id", ""));
                    return ok();
                }
                case "chatSeen": {
                    // 已经进了聊天页，主动消息通知就撤掉
                    PetSvc.clearSayNotif(this);
                    return ok();
                }
                case "clearChat": {
                    Data.chatClear(this);
                    return ok();
                }
                case "personaSave": {
                    // 人设只允许改名字：说话风格/开场白/边界由 PersonaAuto 每天自动演化
                    JSONObject in = a.optJSONObject("persona");
                    String name = in == null ? "" : in.optString("name", "").trim();
                    if (name.isEmpty()) return err("名字不能为空");
                    JSONObject np;
                    synchronized (Data.personaLock()) {
                        JSONObject cur = Data.persona(this);
                        cur.put("name", name);
                        Data.savePersona(this, cur);
                        np = Data.persona(this);
                    }
                    event("persona", np);
                    return ok().put("persona", np);
                }
                case "profileSave": {
                    JSONObject patch = a.optJSONObject("profile");
                    if (patch == null) return err("没有要保存的资料");
                    JSONObject profile = Data.profile(this);
                    java.util.Iterator<String> fields = patch.keys();
                    while (fields.hasNext()) {
                        String key = fields.next();
                        if ("nickname".equals(key)) {
                            String name = patch.optString(key, "").trim();
                            if (name.length() > 40) name = name.substring(0, 40);
                            profile.put(key, name);
                        } else if ("avatarData".equals(key)) {
                            String avatar = patch.optString(key, "");
                            if (avatar.length() > 300000 || (!avatar.isEmpty()
                                    && !avatar.startsWith("data:image/jpeg;base64,"))) {
                                return err("头像格式或大小不受支持，请重新选择一张图片");
                            }
                            profile.put(key, avatar);
                        } else {
                            profile.put(key, patch.opt(key));
                        }
                    }
                    Data.saveProfile(this, profile);
                    return ok().put("profile", profile);
                }
                case "profileAvatarPick": {
                    Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    pick.addCategory(Intent.CATEGORY_OPENABLE);
                    pick.setType("image/*");
                    startActivityForResult(pick, RQ_PROFILE_AVATAR);
                    return pending("请选择一张头像图片");
                }
                case "bookList":
                    return Books.list(this);
                case "bookGet":
                    return Books.get(this, a.optString("id", ""));
                case "bookImport":
                    pickBooks();
                    return pending("正在选择书籍…");
                case "bookImportUrl":
                    return Books.importUrl(this, a.optString("url", ""));
                case "bookScan":
                    return Books.scan(this);
                case "bookUpdate":
                    return Books.update(this, a.optString("id", ""), a.optJSONObject("patch"));
                case "bookDelete":
                    return Books.delete(this, a.optString("id", ""));
                case "bookCoverPick":
                    pendingBookId = a.optString("id", "");
                    pick(RQ_BOOK_COVER, "image/*");
                    return pending("正在选择封面…");
                case "bookExport":
                    pendingBookId = a.optString("id", "");
                    pickCreate(RQ_BOOK_EXPORT, sanitize(a.optString("title", "book")) + ".zip");
                    return pending("正在选择保存位置…");
                case "bookExportAll":
                    pendingBookId = "";
                    pickCreate(RQ_BOOK_EXPORT, "bookshelf.zip");
                    return pending("正在选择保存位置…");
                case "bookRestore":
                    pick(RQ_BOOK_IMPORT, "application/zip");
                    return pending("正在选择书架备份…");
                case "bookNotesExport":
                    pendingBookId = a.optString("id", "");
                    pickCreateText(RQ_BOOK_NOTES, sanitize(a.optString("title", "book")) + "-notes.txt");
                    return pending("正在选择笔记保存位置…");
                case "bookProgress":
                    return Books.progress(this, a.optString("id", ""), a.optInt("chapter", 0),
                            a.optDouble("percent", 0), a.optDouble("scroll", 0), a.optInt("seconds", 0));
                case "bookNotes":
                    return ok().put("notes", Books.notes(this, a.optString("id", "")));
                case "bookNoteAdd":
                    return Books.addNote(this, a.optString("id", ""), a.optInt("chapter", 0),
                            a.optString("type", "note"), a.optString("quote", ""),
                            a.optString("note", ""), a.optInt("offset", 0));
                case "bookNoteDel":
                    return Books.deleteNote(this, a.optString("id", ""));
                case "bookPage":
                    return Books.page(this, a.optString("id", ""), a.optInt("page", 0));
                case "bookConfig":
                    return ok().put("config", Books.readerCfg(this));
                case "bookConfigSave":
                    return Books.saveReaderCfg(this, a.optJSONObject("config"));
                case "bookTts":
                    Voice.speakForce(this, a.optString("text", ""), (float) a.optDouble("rate", 1));
                    return ok();
                case "bookTtsStop":
                    Voice.stopSpeak();
                    return ok();
                case "readingMode": {
                    final boolean on = a.optBoolean("on", false);
                    readerKeys = on;
                    // 阅读时保持屏幕常亮，退出阅读立即恢复系统熄屏策略
                    if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    PetSvc.send(this, PetSvc.A_READING, "on", on ? "1" : "0");
                    return ok();
                }
                case "immersive": {
                    // 阅读页沉浸式：隐藏系统状态栏与导航栏（用户上下滑仍可临时唤出）
                    final boolean on = a.optBoolean("on", false);
                    h.post(() -> {
                        try {
                            View d = getWindow().getDecorView();
                            int base = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
                            if (on) {
                                base |= View.SYSTEM_UI_FLAG_FULLSCREEN
                                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
                            }
                            d.setSystemUiVisibility(base);
                        } catch (Throwable ignored) {
                        }
                    });
                    return ok();
                }
                case "bookLookup": {
                    String q = a.optString("text", "").trim();
                    if (q.isEmpty()) return err("没有选中文字");
                    if (!Cfg.load(this).aiReady()) return err("先绑定 API Key 才能词典/翻译");
                    boolean trans = "translate".equals(a.optString("mode", "dict"));
                    JSONArray msgs = new JSONArray();
                    JSONObject sys = new JSONObject();
                    sys.put("role", "system");
                    sys.put("content", trans
                            ? "你是翻译助手。把用户给出的文本翻译成简体中文；若已是中文则翻译成英文。只输出译文，不要解释。"
                            : "你是汉语词典。解释用户给出的词语：读音、词性、释义，生僻字给出拼音与常见用法。不超过 120 字。");
                    msgs.put(sys);
                    JSONObject u = new JSONObject();
                    u.put("role", "user");
                    u.put("content", q.length() > 800 ? q.substring(0, 800) : q);
                    msgs.put(u);
                    CountDownLatch latch = new CountDownLatch(1);
                    final String[] out = {null};
                    Ai.rawChat(this, msgs, 400, new Ai.Cb() {
                        @Override
                        public void ok(String text) {
                            out[0] = text;
                            latch.countDown();
                        }

                        @Override
                        public void fail(String error) {
                            out[0] = "[ERR]" + error;
                            latch.countDown();
                        }
                    });
                    if (!latch.await(45, TimeUnit.SECONDS)) return err("查询超时");
                    String text = out[0];
                    if (text == null) return err("查询失败");
                    if (text.startsWith("[ERR]")) return err(text.substring(5));
                    return ok().put("text", text);
                }
                case "memories": {
                    JSONObject r = ok();
                    r.put("memories", Data.memories(this));
                    return r;
                }
                case "memoryAdd": {
                    Data.addMemory(this, a.optString("text", ""), "manual");
                    event("memory", new JSONObject());
                    return ok();
                }
                case "memoryDel": {
                    Data.deleteMemory(this, a.optString("id", ""));
                    event("memory", new JSONObject());
                    return ok();
                }
                case "memoryClearAll": {
                    Mem.clearAll(this);
                    event("memory", new JSONObject());
                    return ok();
                }
                case "memoryExtract": {
                    Brain.extractMemory(this, true);
                    return pending("正在整理记忆…");
                }
                case "actions": {
                    JSONObject r = ok();
                    r.put("catalog", catalog());
                    return r;
                }
                case "actionSave": {
                    Data.saveActionLine(this, a.optString("slug", ""), a.optString("line", ""), a.optBoolean("ai", false));
                    return ok();
                }
                case "cancelChat": {
                    Ai.cancelStream(a.optString("streamId", ""));
                    return ok();
                }
                case "chatInterrupted": {
                    Brain.saveInterruptedReply(this, a.optString("text", ""));
                    return ok();
                }
                case "apps": {
                    JSONObject r = ok();
                    r.put("apps", Fg.apps(this));
                    return r;
                }
                case "freeActionAppOptions": {
                    JSONArray safe = new JSONArray();
                    JSONArray installed = Fg.apps(this);
                    for (int i = 0; i < installed.length(); i++) {
                        JSONObject item = installed.optJSONObject(i);
                        if (CompanionSession.freeActionAppAllowed(this, item)) safe.put(item);
                    }
                    return ok().put("apps", safe);
                }
                case "freeActionAppsSave": {
                    JSONArray requested = a.optJSONArray("apps");
                    JSONArray installed = Fg.apps(this);
                    JSONArray safe = new JSONArray();
                    java.util.HashSet<String> allowed = new java.util.HashSet<>();
                    for (int i = 0; i < installed.length(); i++) {
                        JSONObject item = installed.optJSONObject(i);
                        if (CompanionSession.freeActionAppAllowed(this, item)) {
                            allowed.add(item.optString("pkg", ""));
                        }
                    }
                    java.util.HashSet<String> seen = new java.util.HashSet<>();
                    if (requested != null) for (int i = 0; i < requested.length(); i++) {
                        String pkg = requested.optString(i, "").trim();
                        if (!pkg.isEmpty() && allowed.contains(pkg) && seen.add(pkg)) safe.put(pkg);
                    }
                    Cfg cfg = Cfg.load(this);
                    cfg.set("freeActionApps", safe);
                    cfg.save(this);
                    return ok().put("count", safe.length());
                }
                case "gamesSave": {
                    Cfg cfg = Cfg.load(this);
                    cfg.set("games", a.optJSONArray("games") == null ? new JSONArray() : a.optJSONArray("games"));
                    cfg.save(this);
                    A11y.reload();
                    if (cfg.b("running", false)) PetSvc.send(this, PetSvc.A_RELOAD, null, null);
                    return ok();
                }
                case "notifyAppsSave": {
                    Cfg cfg = Cfg.load(this);
                    cfg.set("notifyApps", a.optJSONArray("apps") == null ? new JSONArray() : a.optJSONArray("apps"));
                    cfg.save(this);
                    return ok();
                }
                case "adSummary": {
                    return AdRules.summary(this);
                }
                case "adImportText": {
                    JSONObject r = AdRules.importText(this, a.optString("text", ""));
                    r.put("ok", true);
                    return r;
                }
                case "adImportFile": {
                    pick(RQ_RULES, "*/*");
                    return pending("正在选择规则文件…");
                }
                case "adClear": {
                    AdRules.importText(this, "");
                    return ok();
                }
                case "adLines":
                    return ok().put("lines", Data.adLines(this));
                case "adSaveLines": {
                    Data.saveAdLines(this, a.optJSONArray("lines") == null ? new JSONArray() : a.optJSONArray("lines"));
                    return ok();
                }
                case "adEnabled": {
                    Cfg cfg = Cfg.load(this);
                    cfg.set("adEnabled", a.optBoolean("enabled", false));
                    cfg.save(this);
                    A11y.reload();
                    return ok();
                }
                case "reminders": {
                    JSONObject r = ok();
                    r.put("reminders", Data.reminders(this));
                    r.put("unread", Bus.unreadCount);
                    return r;
                }
                case "reminderAdd": {
                    JSONObject extra = new JSONObject();
                    long at = a.optLong("at", 0);
                    if (at > 0) extra.put("at", at);
                    Data.addReminder(this, a.optString("type", "manual"), a.optString("title", ""),
                            a.optString("detail", ""), "manual:" + System.currentTimeMillis(), extra);
                    event("reminders", new JSONObject());
                    return ok();
                }
                case "reminderDel": {
                    Data.removeReminder(this, a.optString("id", ""));
                    event("reminders", new JSONObject());
                    return ok();
                }
                case "calendarSync": {
                    if (!Perm.calendar(this)) {
                        h.post(() -> requestPerms(new String[]{"android.permission.READ_CALENDAR"}));
                        return err("需要日历权限");
                    }
                    int n = Cal.sync(this);
                    JSONObject r = ok();
                    r.put("count", n);
                    return r;
                }
                case "perms":
                    return ok().put("perms", Perm.status(this));
                case "openPerm": {
                    String kind = a.optString("kind", "app");
                    // 已经授予的运行时权限，再调 requestPermissions 系统不会弹窗，
                    // 表现就是「关闭」点了没反应；这时只能带用户去系统设置里手动关。
                    if ("notif".equals(kind)) {
                        if (Perm.notif(this)) {
                            if (!Perm.open(this, "notif")) return err("无法打开系统设置，请手动关闭");
                        } else requestPerms(new String[]{"android.permission.POST_NOTIFICATIONS"});
                    } else if ("calendar".equals(kind)) {
                        if (Perm.calendar(this)) Perm.open(this, "app");
                        else requestPerms(new String[]{"android.permission.READ_CALENDAR"});
                    } else if ("record".equals(kind)) {
                        if (Perm.record(this)) Perm.open(this, "record");
                        else requestPerms(new String[]{"android.permission.RECORD_AUDIO"});
                    } else if ("location".equals(kind)) {
                        if (Perm.location(this)) Perm.open(this, "location");
                        else requestPerms(new String[]{"android.permission.ACCESS_FINE_LOCATION",
                                "android.permission.ACCESS_COARSE_LOCATION"});
                    } else if ("backgroundLocation".equals(kind)) {
                        if (Perm.backgroundLocation(this)) Perm.open(this, "backgroundLocation");
                        else if (android.os.Build.VERSION.SDK_INT >= 29) requestPerms(new String[]{"android.permission.ACCESS_BACKGROUND_LOCATION"});
                        else Perm.open(this, "app");
                    } else if ("files".equals(kind)) {
                        if (android.os.Build.VERSION.SDK_INT >= 30) Perm.open(this, "files");
                        else requestPerms(new String[]{"android.permission.WRITE_EXTERNAL_STORAGE"});
                    } else if (!Perm.open(this, kind)) return err("无法打开系统设置，请手动授权");
                    return ok();
                }
                case "openUrl": {
                    String url = a.optString("url", "");
                    if (url.isEmpty()) return err("链接为空");
                    try {
                        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(i);
                        return ok();
                    } catch (Throwable t) {
                        return err("没有可打开该链接的应用");
                    }
                }
                case "requestPerm": {
                    String kind = a.optString("kind", "notif");
                    if ("notif".equals(kind)) requestPerms(new String[]{"android.permission.POST_NOTIFICATIONS"});
                    else if ("calendar".equals(kind)) requestPerms(new String[]{"android.permission.READ_CALENDAR"});
                    else if ("record".equals(kind)) requestPerms(new String[]{"android.permission.RECORD_AUDIO"});
                    else if ("location".equals(kind)) requestPerms(new String[]{"android.permission.ACCESS_FINE_LOCATION",
                            "android.permission.ACCESS_COARSE_LOCATION"});
                    else if ("backgroundLocation".equals(kind)) {
                        if (android.os.Build.VERSION.SDK_INT >= 29) requestPerms(new String[]{"android.permission.ACCESS_BACKGROUND_LOCATION"});
                        else Perm.open(this, "app");
                    } else if ("storage".equals(kind)) {
                        if (android.os.Build.VERSION.SDK_INT >= 30) Perm.open(this, "files");
                        else requestPerms(new String[]{"android.permission.WRITE_EXTERNAL_STORAGE"});
                    } else if (!Perm.open(this, kind)) return err("无法打开系统设置，请手动授权");
                    return ok();
                }
                case "balance": {
                    CountDownLatch latch = new CountDownLatch(1);
                    final String[] out = {null};
                    Ai.balance(this, new Ai.Cb() {
                        @Override
                        public void ok(String t) {
                            out[0] = t;
                            latch.countDown();
                        }

                        @Override
                        public void fail(String e) {
                            out[0] = "[ERR]" + e;
                            latch.countDown();
                        }
                    });
                    if (!latch.await(20, TimeUnit.SECONDS)) return err("查询超时");
                    if (out[0] != null && out[0].startsWith("[ERR]")) return err(out[0].substring(5));
                    return ok().put("text", out[0]);
                }
                case "balanceVision": {
                    CountDownLatch latch = new CountDownLatch(1);
                    final String[] out = {null};
                    Ai.balanceVision(this, new Ai.Cb() {
                        @Override
                        public void ok(String t) {
                            out[0] = t;
                            latch.countDown();
                        }

                        @Override
                        public void fail(String e) {
                            out[0] = "[ERR]" + e;
                            latch.countDown();
                        }
                    });
                    if (!latch.await(20, TimeUnit.SECONDS)) return err("查询超时");
                    if (out[0] != null && out[0].startsWith("[ERR]")) return err(out[0].substring(5));
                    return ok().put("text", out[0]);
                }
                case "music": {
                    Music.refresh(this);
                    JSONObject r = ok();
                    r.put("title", Music.title());
                    r.put("artist", Music.artist());
                    r.put("playing", Music.playing());
                    r.put("position", Music.position());
                    r.put("duration", Music.duration());
                    r.put("lines", Music.lines() == null ? 0 : Music.lines().size());
                    r.put("app", Music.sourceApp());
                    r.put("pkg", Music.sourcePkg());
                    return r;
                }
                case "wave": {
                    JSONObject r = ok();
                    if (!Music.playing()) {
                        Wave.stop();
                        r.put("on", false);
                        return r;
                    }
                    if (Wave.hasPermission(this)) {
                        int sid = Wave.sessionId(this, Music.sourcePkg());
                        if (sid > 0) Wave.ensure(this, sid);
                    }
                    r.put("on", Wave.fresh());
                    r.put("levels", Wave.levels());
                    r.put("granted", Wave.hasPermission(this));
                    return r;
                }
                case "pomoState":
                    return ok().put("pomo", PetSvc.pomoState());
                case "pomoStart": {
                    Cfg cfg = Cfg.load(this);
                    if (!cfg.b("running", false) || !PetSvc.running) return err("先把桌宠放出来");
                    Intent i = new Intent(this, PetSvc.class).setAction(PetSvc.A_POMO_START)
                            .putExtra("work", a.optInt("work", 25))
                            .putExtra("rest", a.optInt("rest", 5));
                    try {
                        startService(i);
                    } catch (Throwable ignored) {
                    }
                    return ok();
                }
                case "pomoStop": {
                    PetSvc.send(this, PetSvc.A_POMO_STOP, null, null);
                    return ok();
                }
                case "context": {
                    JSONObject r = ok();
                    r.put("pkg", Bus.currentPackage);
                    r.put("text", Bus.screenText.length() > 400 ? Bus.screenText.substring(0, 400) : Bus.screenText);
                    r.put("music", Music.playing() ? Music.title() : "");
                    return r;
                }
                case "export": {
                    pickCreate(RQ_EXPORT, "S-G-backup.zip");
                    return pending("正在导出…");
                }
                case "importZip": {
                    pick(RQ_IMPORT, "application/zip");
                    return pending("正在选择备份…");
                }
                case "deleteAllData": {
                    // 先停服务：运行中的 PetSvc / 通知监听可能还在写 S-G
                    if (PetSvc.running) {
                        PetSvc.send(this, PetSvc.A_STOP, null, null);
                        long deadline = System.currentTimeMillis() + 6000;
                        while (PetSvc.running && System.currentTimeMillis() < deadline) {
                            try {
                                Thread.sleep(50);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        }
                        if (PetSvc.running) return err("桌宠还没停下来，请稍后重试");
                        try {
                            Thread.sleep(200);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    Sg.deleteAll(this);
                    Cfg cfg = Cfg.load(this);
                    cfg.set("running", false);
                    cfg.save(this);
                    A11y.reload();
                    return ok();
                }
                case "storagePath": {
                    JSONObject r = ok();
                    r.put("path", Sg.dir(this).getAbsolutePath());
                    r.put("public", false);
                    r.put("publicPath", Perm.allFilesPath());
                    return r;
                }
                /* ---------- 主动策略 ---------- */
                case "policy":
                    return ok().put("policy", Policy.summary(this)).put("json", Policy.json(this));
                case "policySave":
                    Policy.save(this, a.optJSONObject("json"));
                    return ok().put("policy", Policy.summary(this));
                case "policyPause": {
                    if (a.optBoolean("morning", false)) Policy.pauseUntilMorning(this);
                    else Policy.pauseFor(this, Math.max(1, Math.min(1440, a.optInt("minutes", 60))));
                    return ok().put("policy", Policy.summary(this));
                }
                case "policyClear":
                    Policy.clearLog(this);
                    return ok();
                case "health":
                    return ok().put("health", Perm.health(this));
                case "usage":
                    return ok().put("usage", Usage.state(this));
                /* ---------- 记账 ---------- */
                case "ledger":
                    return ok().put("ledger", Ledger.state(this));
                case "ledgerAdd": {
                    JSONObject lr = Ledger.add(this, a.optDouble("amount", 0),
                            a.optString("type", "expense"), a.optString("cat", "其他"),
                            a.optString("note", ""), "manual", "");
                    String over = lr.optString("over", "");
                    if (!over.isEmpty()) PetSvc.send(this, PetSvc.A_SAY, "text", over);
                    if (lr.optBoolean("ok", false)) {
                        InApp.track(this, "记账", a.optString("cat", "其他") + " "
                                + Ledger.fmt(a.optDouble("amount", 0)) + " 元", a.optString("note", ""));
                    }
                    return ok().put("result", lr).put("ledger", Ledger.state(this));
                }
                case "ledgerDel":
                    Ledger.delete(this, a.optString("id", ""));
                    return ok().put("ledger", Ledger.state(this));
                case "ledgerCfg":
                    Ledger.saveCfg(this, a.optDouble("monthly", 0), a.optBoolean("auto", true));
                    return ok().put("ledger", Ledger.state(this));
                /* ---------- 应用感知 ---------- */
                case "monitor": {
                    JSONObject r = ok().put("monitor", InApp.summary(this));
                    r.put("pkg", Bus.currentPackage);
                    String pkgName = Bus.currentPackage;
                    try {
                        android.content.pm.PackageManager pm = getPackageManager();
                        pkgName = pm.getApplicationLabel(pm.getApplicationInfo(Bus.currentPackage, 0)).toString();
                    } catch (Throwable ignored) {
                    }
                    r.put("pkgName", pkgName);
                    String st = Bus.screenText == null ? "" : Bus.screenText;
                    if (st.length() > 240) st = st.substring(0, 240);
                    r.put("text", st);
                    return r;
                }
                case "activity":
                    InApp.track(this, "功能", "打开页面", a.optString("name", ""));
                    return ok();
                case "monitorClear":
                    InApp.clear(this);
                    return ok().put("monitor", InApp.summary(this));
                /* ---------- 语音 ---------- */
                case "speak":
                    Voice.speak(this, a.optString("text", ""), true);
                    return ok();
                case "chatVoice":
                    Voice.speakChat(this, a.optString("text", ""));
                    return ok();
                case "tts":
                    return ok().put("tts", Voice.state(this)).put("voices", Voice.voices(this));
                case "ttsSave":
                    Voice.save(this, a.optJSONObject("json"));
                    return ok().put("tts", Voice.state(this));
                case "ttsTest":
                    Voice.test(this, a.optString("text", "朗读已开启，我会这样念给你听。"));
                    return ok();
                case "weatherCheck":
                    Weather.check(this, true);
                    return ok();
                case "weather":
                    return ok().put("weather", Weather.state(this));
                case "asrStart": {
                    Voice.start(this, new Voice.Cb() {
                        @Override
                        public void text(String text, boolean isFinal) {
                            JSONObject o = new JSONObject();
                            try {
                                o.put("text", text);
                                o.put("final", isFinal);
                            } catch (Throwable ignored) {
                            }
                            event("asr", o);
                        }

                        @Override
                        public void error(String msg) {
                            event("asr", msgJson(msg));
                        }
                    });
                    return ok();
                }
                case "asrStop":
                    Voice.stopAsr();
                    return ok();
                case "asrFinish":
                    Voice.finishAsr();
                    return ok();
                /* ---------- 剪贴板 ---------- */
                case "clips":
                    return ok().put("clips", Clip.list(this));
                case "clipCapture":
                    return Clip.capture(this);
                case "clipAdd":
                    return Clip.add(this, a.optString("text", ""), a.optString("kind", "text"),
                            a.optString("path", ""), a.optString("source", "manual"));
                case "clipDel":
                    Clip.delete(this, a.optString("id", ""));
                    return ok();
                case "clipClear":
                    Clip.clear(this);
                    return ok();
                case "clipNote":
                    Clip.saveNote(this, a.optString("text", ""));
                    return ok();
                case "clipAct": {
                    CountDownLatch latch = new CountDownLatch(1);
                    final String[] out = {null};
                    Clip.act(this, a.optString("text", ""), a.optString("action", "summary"), new Ai.Cb() {
                        @Override
                        public void ok(String t) {
                            out[0] = t;
                            latch.countDown();
                        }

                        @Override
                        public void fail(String e) {
                            out[0] = "[ERR]" + e;
                            latch.countDown();
                        }
                    });
                    if (!latch.await(60, TimeUnit.SECONDS)) return err("处理超时");
                    if (out[0] != null && out[0].startsWith("[ERR]")) return err(out[0].substring(5));
                    return ok().put("text", out[0]);
                }
                /* ---------- 备份与迁移 ---------- */
                case "backupExport":
                    pendingPass = a.optString("password", "");
                    pickCreate(RQ_BACKUP, "S-G-" + Sg.now().substring(0, 10) + ".zip");
                    return pending("正在导出备份…");
                case "backupPreview":
                    pendingPass = a.optString("password", "");
                    pendingMode = "preview";
                    pick(RQ_RESTORE, "*/*");
                    return pending("正在选择备份…");
                case "backupApply":
                    pendingPass = a.optString("password", "");
                    pendingMode = "merge".equals(a.optString("mode")) ? "merge" : "replace";
                    pick(RQ_RESTORE, "*/*");
                    return pending("正在选择备份…");
                case "migrations":
                    return ok().put("migrations", Backup.migrations(this));
                /* ---------- 任务与专注 ---------- */
                case "tasks":
                    return ok().put("tasks", Taskbook.list(this)).put("focus", Taskbook.active(this))
                            .put("summary", Taskbook.focusSummary(this, 7)).put("events", Taskbook.events(this, 48))
                            .put("conflicts", Taskbook.conflicts(this));
                case "taskAdd":
                    return ok().put("task", Taskbook.add(this, a.optString("title", ""), a.optString("detail", ""),
                            a.optLong("due", 0), a.optInt("priority", 1), a.optString("source", "manual")));
                case "taskUpdate":
                    Taskbook.update(this, a.optString("id", ""), a.optJSONObject("fields"));
                    return ok();
                case "taskDone":
                    Taskbook.done(this, a.optString("id", ""));
                    return ok();
                case "taskDel":
                    Taskbook.remove(this, a.optString("id", ""));
                    return ok();
                case "focusStart": {
                    Intent i = new Intent(this, PetSvc.class).setAction(PetSvc.A_POMO_START)
                            .putExtra("work", a.optInt("minutes", 25))
                            .putExtra("rest", a.optInt("rest", 5))
                            .putExtra("taskId", a.optString("taskId", ""))
                            .putExtra("title", a.optString("title", ""));
                    try {
                        startService(i);
                    } catch (Throwable ignored) {
                    }
                    return ok();
                }
                case "focusStop":
                    PetSvc.send(this, PetSvc.A_POMO_STOP, null, null);
                    return ok();
                case "focusLog":
                    return ok().put("log", Taskbook.focusLog(this, 14)).put("summary", Taskbook.focusSummary(this, 14));
                /* ---------- 记忆 ---------- */
                case "memGraphData":
                    return ok().put("graph", Mem.graphData(this));
                case "memoryGraphMode":
                    Live2DRenderer.setMemoryGraphVisible(a.optBoolean("on", false));
                    return ok();
                case "memList":
                    return ok().put("memories", Mem.listFiltered(this, a.optString("query", ""),
                            a.optString("type", "all"), a.optString("status", ""),
                            a.optString("category", "all"), a.optBoolean("archived", false)))
                            .put("summary", Mem.summary(this));
                case "memGet": {
                    String mid = a.optString("id", "");
                    JSONObject mo = Mem.get(this, mid);
                    if (mo == null) return err("记忆不存在");
                    return ok().put("memory", mo).put("history", Mem.history(this, mid));
                }
                case "memAdd": {
                    JSONObject cand = new JSONObject();
                    try {
                        String text = a.optString("text", "");
                        cand.put("text", text);
                        cand.put("sourceQuote", a.optString("sourceQuote", text));
                        cand.put("sourceRole", "manual");
                        cand.put("origin", "manual");
                        cand.put("confidence", 1.0);
                        cand.put("subject", "user");
                        cand.put("pinned", false);
                        if (!a.optString("type", "").isEmpty()) cand.put("type", a.optString("type", ""));
                        if (!a.optString("category", "").isEmpty()) cand.put("category", a.optString("category", ""));
                        if (a.optJSONArray("tags") != null) cand.put("tags", a.optJSONArray("tags"));
                    } catch (Throwable ignored) {
                    }
                    String nid = Mem.addCandidate(this, cand);
                    return nid == null || nid.isEmpty() ? err("内容不合适，至少写 2 个字") : ok().put("id", nid);
                }
                case "memEdit": {
                    JSONObject patch = a.optJSONObject("patch");
                    if (!Mem.update(this, a.optString("id", ""), patch, "user")) return err("修改失败");
                    return ok();
                }
                case "memPin":
                    Mem.pinById(this, a.optString("id", ""), a.optBoolean("pinned", true));
                    return ok();
                case "memDel":
                    return Mem.delete(this, a.optString("id", "")) ? ok() : err("记忆不存在");
                case "memArchive":
                    Mem.archive(this, a.optString("id", ""), a.optBoolean("archived", true),
                            a.optString("reason", "user"));
                    return ok();
                case "memMerge":
                    return ok().put("merged", Mem.mergeSmart(this,
                            a.optJSONArray("ids") == null ? new JSONArray() : a.optJSONArray("ids")));
                case "memSummary":
                    return ok().put("summary", Mem.summary(this)).put("due", Mem.reviewDue(this))
                            .put("items", Mem.review(this));
                case "memReviewed":
                    Mem.markReviewed(this);
                    return ok();
                case "companionRules":
                    return ok().put("rules", CompanionRules.list(this));
                case "companionRuleApprove":
                    return CompanionRules.approve(this, a.optString("id", ""))
                            ? ok() : err("这条习惯还没有足够的重复证据");
                case "companionRuleDismiss":
                    return CompanionRules.dismiss(this, a.optString("id", ""))
                            ? ok() : err("候选已变化，请刷新后再试");
                case "companionRuleDelete":
                    return CompanionRules.remove(this, a.optString("id", ""))
                            ? ok() : err("这条相处习惯不存在");
                /* ---------- 关系：亲密度 / 性格 / 日记 / 共同经历 / 她的牵挂 ---------- */
                case "relations": {
                    JSONObject r = ok();
                    Recap.ensureFirstChat(this);
                    r.put("intimacy", Intimacy.summary(this));
                    r.put("traits", Traits.summary(this));
                    r.put("diary", Diary.summary(this));
                    r.put("timeline", Timeline.summary(this));
                    r.put("caring", SelfMem.summary(this));
                    r.put("projects", Recap.projects(this, 3));
                    r.put("emotion", State.summary(this));
                    return r;
                }
                case "momentAdd": {
                    long refTs = a.optLong("refTs", 0);
                    String text = a.optString("text", "").trim();
                    // 聊天标记可能是一整段长回复：帖子本身不截断，只截进入时间线的摘要
                    if (refTs > 0 && text.length() > 120) text = text.substring(0, 117) + "…";
                    if (text.length() < 2 || text.length() > 120) return err("写 2–120 个字");
                    String day = a.optString("day", "");
                    if (!day.isEmpty() && !day.matches("\\d{4}-\\d{2}-\\d{2}")) return err("日期格式不对");
                    String refRole = a.optString("refRole", "");
                    String source = refTs > 0 ? "user_mark_chat" : "user_mark_manual";
                    String key = refTs > 0
                            ? "moment:chat:" + refTs + ":" + (refRole.isEmpty() ? "user" : refRole)
                            : "moment:manual:" + System.currentTimeMillis();
                    JSONObject extra = new JSONObject();
                    try {
                        extra.put("source", source);
                        extra.put("editable", true);
                        extra.put("confidence", 1.0);
                        if (refTs > 0) {
                            extra.put("refTs", refTs);
                            extra.put("refRole", refRole.isEmpty() ? "user" : refRole);
                            // 标记旧聊天时，按那句话发生的时间入档，而不是今天
                            extra.put("ts", refTs);
                        }
                        if (!day.isEmpty()) extra.put("day", day);
                        extra.put("note", a.optString("note", ""));
                    } catch (Throwable ignored) {
                    }
                    Timeline.add(this, key, "moment", text, extra);
                    JSONObject r = ok();
                    r.put("key", key);
                    r.put("timeline", Timeline.summary(this));
                    return r;
                }
                case "momentEdit": {
                    if (!Timeline.update(this, a.optString("key", ""), a.optJSONObject("patch")))
                        return err("这条记录不能编辑");
                    return ok().put("timeline", Timeline.summary(this));
                }
                case "momentDel": {
                    if (!Timeline.remove(this, a.optString("key", ""))) return err("记录不存在");
                    return ok().put("timeline", Timeline.summary(this));
                }
                case "recapGet": {
                    JSONObject r = ok();
                    r.put("recap", Recap.get(this, a.optString("period", "7d")));
                    return r;
                }
                case "recapSay": {
                    final String period = a.optString("period", "7d");
                    final JSONObject data = Recap.get(this, period);
                    final CountDownLatch latch = new CountDownLatch(1);
                    final String[] out = {data.optString("text", "")};
                    Recap.say(this, data, new Ai.Cb() {
                        @Override
                        public void ok(String text) {
                            out[0] = text == null || text.trim().isEmpty() ? data.optString("text", "") : text.trim();
                            latch.countDown();
                        }

                        @Override
                        public void fail(String err) {
                            latch.countDown();
                        }
                    });
                    try {
                        if (!latch.await(30, TimeUnit.SECONDS)) return err("润色超时");
                    } catch (Throwable ignored) {
                    }
                    return ok().put("text", out[0]).put("local", data.optString("text", ""));
                }
                case "doneConfirm": {
                    if (!SelfMem.confirmDone(this, a.optString("id", ""), a.optBoolean("done", true)))
                        return err("这条已经处理过了");
                    return ok().put("caring", SelfMem.summary(this))
                            .put("timeline", Timeline.summary(this));
                }
                case "traitsReset": {
                    boolean done = Traits.reset(this);
                    if (!done) return err("性格重置失败");
                    JSONObject r = ok();
                    r.put("traits", Traits.summary(this));
                    r.put("persona", Data.persona(this));
                    r.put("emotion", State.summary(this));
                    return r;
                }
                case "emotion": {
                    JSONObject re = ok();
                    re.put("emotion", State.summary(this));
                    return re;
                }
                case "diaryWrite":
                    Diary.generateNow(this);
                    return ok();
                case "caringDel":
                    SelfMem.remove(this, a.optString("id", ""));
                    return ok();
                /* ---------- 首次表单 / 偷看日记 / 模型导入导出 ---------- */
                case "onboardingSave": {
                    if (!Contract.canEditSeed(this)) return err("已经填写过，不能修改");
                    String userName = a.optString("userName", "");
                    String herName = a.optString("herName", "");
                    String tone = a.optString("tone", "软萌");
                    String relation = a.optString("relation", "朋友");
                    String relationId = a.optString("relationId", Contract.relationId(relation));
                    String relationGroup = a.optString("relationGroup", Contract.relationGroup(relation));
                    String relationLabel = a.optString("relationLabel", relation);
                    String customRelation = a.optString("customRelation", "");
                    if ("custom".equals(relationId)) {
                        String s = Persona.sanitizeRelation(customRelation);
                        if (s.isEmpty()) return err("关系称呼需要 2–12 个字，且不能包含指令");
                        relationLabel = s;
                        relation = s;
                    }
                    String archetypeId = a.optString("archetypeId", "natural");
                    String toneId = a.optString("toneId", Contract.toneId(tone));
                    int seed = a.optInt("seed", (int) (System.currentTimeMillis() % 1000000));
                    JSONObject sd = new JSONObject();
                    try {
                        sd.put("relationGroup", relationGroup);
                        sd.put("relationId", relationId);
                        sd.put("relationLabel", relationLabel);
                        sd.put("customRelation", customRelation);
                        sd.put("archetypeId", archetypeId);
                        sd.put("toneId", toneId);
                        sd.put("seed", seed);
                    } catch (Throwable ignored) {
                    }
                    Contract.saveSeed(this, userName, herName, tone, relation, sd);
                    // 同步写进 persona 骨架、用户资料与初始性格
                    try {
                        synchronized (Data.personaLock()) {
                            JSONObject p = Data.persona(this);
                            if (herName != null && !herName.trim().isEmpty()) p.put("name", herName.trim());
                            Data.savePersona(this, p);
                        }
                    } catch (Throwable ignored) {
                    }
                    Persona.applySeed(this, sd, "natural".equals(archetypeId));
                    JSONObject u = Data.profile(this);
                    try {
                        if (userName != null && !userName.trim().isEmpty()) u.put("nickname", userName.trim());
                        Data.saveProfile(this, u);
                    } catch (Throwable ignored) {
                    }
                    event("contract", Contract.summary(this));
                    event("persona", Data.persona(this));
                    return ok().put("contract", Contract.summary(this)).put("seed", Persona.summary(this));
                }
                case "personaPresets":
                    return ok().put("presets", Persona.presets(this));
                case "onboardingSkip": {
                    // 旧版用户的人格升级表单也允许跳过；已经锁定且不是升级表单的才拒绝
                    if (Contract.isSet(this) && !Contract.needsSeed(this)) return err("已经填写过，不能修改");
                    Contract.skip(this);
                    event("contract", Contract.summary(this));
                    return ok().put("contract", Contract.summary(this));
                }
                case "diaryPeek":
                    return ok().put("peeked", State.setDiaryPeek(this));
                case "openPetChat":
                    if (!PetSvc.running) return err("桌宠还没启动");
                    PetSvc.send(this, PetSvc.A_CHAT, null, null);
                    return ok();
                case "modelExport": {
                    Cfg cfg = Cfg.load(this);
                    JSONObject m = new JSONObject();
                    try {
                        m.put("apiKey", cfg.apiKey());
                        m.put("baseUrl", cfg.baseUrl());
                        m.put("model", cfg.str("model", "deepseek-chat"));
                        m.put("visionApiKey", cfg.str("visionApiKey", ""));
                        m.put("visionBaseUrl", cfg.str("visionBaseUrl", ""));
                        m.put("visionModel", cfg.str("visionModel", ""));
                        m.put("geminiVideoApiKey", cfg.str("geminiVideoApiKey", ""));
                        m.put("geminiVideoModel", cfg.str("geminiVideoModel", "gemini-3.8-flash"));
                        m.put("imageGenProvider", cfg.str("imageGenProvider", "openai"));
                        m.put("imageGenApiKey", cfg.str("imageGenApiKey", ""));
                        m.put("imageGenBaseUrl", cfg.str("imageGenBaseUrl", ""));
                        m.put("imageGenModel", cfg.str("imageGenModel", ""));
                        m.put("comfyUiBaseUrl", cfg.str("comfyUiBaseUrl", ""));
                        m.put("comfyUiWorkflow", cfg.str("comfyUiWorkflow", ""));
                        m.put("comfyUiPromptNodeIds", cfg.str("comfyUiPromptNodeIds", ""));
                        m.put("balancePath", cfg.str("balancePath", ""));
                        m.put("visionBalancePath", cfg.str("visionBalancePath", ""));
                        m.put("name", a.optString("name", "我的模型"));
                        m.put("exportedAt", Sg.now());
                    } catch (Throwable ignored) {
                    }
                    return ok().put("model", m);
                }
                case "modelImport": {
                    JSONObject m = a.optJSONObject("model");
                    if (m == null) return err("模型配置为空");
                    Cfg cfg = Cfg.load(this);
                    try {
                        if (m.has("apiKey")) cfg.set("apiKey", m.optString("apiKey", ""));
                        if (m.has("baseUrl")) cfg.set("baseUrl", m.optString("baseUrl", ""));
                        if (m.has("model")) cfg.set("model", m.optString("model", ""));
                        if (m.has("visionApiKey")) cfg.set("visionApiKey", m.optString("visionApiKey", ""));
                        if (m.has("visionBaseUrl")) cfg.set("visionBaseUrl", m.optString("visionBaseUrl", ""));
                        if (m.has("visionModel")) cfg.set("visionModel", m.optString("visionModel", ""));
                        if (m.has("geminiVideoApiKey")) cfg.set("geminiVideoApiKey", m.optString("geminiVideoApiKey", ""));
                        if (m.has("geminiVideoModel")) cfg.set("geminiVideoModel", m.optString("geminiVideoModel", "gemini-3.8-flash"));
                        if (m.has("imageGenProvider")) cfg.set("imageGenProvider", m.optString("imageGenProvider", "openai"));
                        if (m.has("imageGenApiKey")) cfg.set("imageGenApiKey", m.optString("imageGenApiKey", ""));
                        if (m.has("imageGenBaseUrl")) cfg.set("imageGenBaseUrl", m.optString("imageGenBaseUrl", ""));
                        if (m.has("imageGenModel")) cfg.set("imageGenModel", m.optString("imageGenModel", ""));
                        if (m.has("comfyUiBaseUrl")) cfg.set("comfyUiBaseUrl", m.optString("comfyUiBaseUrl", ""));
                        if (m.has("comfyUiWorkflow")) cfg.set("comfyUiWorkflow", m.optString("comfyUiWorkflow", ""));
                        if (m.has("comfyUiPromptNodeIds")) cfg.set("comfyUiPromptNodeIds", m.optString("comfyUiPromptNodeIds", ""));
                        if (m.has("balancePath")) cfg.set("balancePath", m.optString("balancePath", ""));
                        if (m.has("visionBalancePath")) cfg.set("visionBalancePath", m.optString("visionBalancePath", ""));
                        cfg.save(this);
                    } catch (Throwable ignored) {
                    }
                    return ok().put("cfg", cfg.json());
                }
                /* ---------- 周报 / 心情 ---------- */
                case "report":
                    return Report.weekly(this);
                case "moods":
                    return ok().put("moods", MoodLog.list(this, 120)).put("recent", MoodLog.recent(this));
                case "moodLog":
                    MoodLog.log(this, a.optString("mood", ""), a.optString("note", ""));
                    return ok();
                /* ---------- 通知摘要 ---------- */
                case "notifList":
                    return ok().put("list", NotifyStore.list(this)).put("group", NotifyStore.group(this))
                            .put("unread", NotifyStore.unread(this)).put("muted", Cfg.load(this).arr("notifMuted"))
                            .put("ai", NotifAi.labels(this));
                case "notifRead":
                    NotifyStore.markRead(this, a.optString("key", ""));
                    return ok();
                case "notifAppRead":
                    NotifyStore.markAppRead(this, a.optString("pkg", ""));
                    return ok();
                case "notifClear":
                    NotifyStore.clear(this);
                    return ok();
                case "notifMute":
                    NotifyStore.mute(this, a.optString("pkg", ""), a.optBoolean("on", true));
                    return ok();
                case "notifSnooze":
                    return NotifyStore.snooze(this, a.optString("key", ""), a.optInt("minutes", 10));
                case "notifToTask":
                    return NotifyStore.toTask(this, a.optString("key", ""));
                case "notifAi":
                    // 先把已有标签返回，模型分析完成后通过 notifAi 事件再推一次
                    return ok().put("ai", NotifAi.analyze(this));
                /* ---------- 位置 ---------- */
                case "geoState":
                    return ok().put("geo", Geo.state(this));
                case "geoSave":
                    Geo.save(this, a.optJSONObject("json"));
                    return ok().put("geo", Geo.state(this));
                case "geoPoint":
                    return Geo.setPoint(this, a.optString("kind", "home"), a.optString("name", ""));
                case "geoDelete":
                    Geo.deletePlace(this, a.optString("id", ""));
                    return ok().put("geo", Geo.state(this));
                /* ---------- 健康动作 ---------- */
                case "wellness":
                    return ok().put("well", Wellness.state(this)).put("steps", Wellness.steps());
                case "wellnessSave":
                    Wellness.save(this, a.optJSONObject("json"));
                    return ok();
                case "wellnessLog":
                    Wellness.log(this, a.optString("type", ""), a.optString("action", "done"));
                    return ok();
                /* ---------- 音乐增强 ---------- */
                case "musicx":
                    return ok().put("musicx", MusicX.state(this));
                case "musicxNoise": {
                    String kind = a.optString("kind", "");
                    Cfg cfg = Cfg.load(this);
                    if (kind.isEmpty() || "off".equals(kind)) {
                        MusicX.stopNoise();
                        kind = "";
                    } else {
                        MusicX.startNoise(kind);
                    }
                    cfg.set("whiteNoise", kind);
                    cfg.save(this);
                    return ok();
                }
                case "musicxNext":
                    return ok().put("ok", MusicX.next());
                case "musicxPrev":
                    return ok().put("ok", MusicX.prev());
                case "musicxTr": {
                    CountDownLatch latch = new CountDownLatch(1);
                    final String[] out = {null};
                    MusicX.translateCurrent(this, new Ai.Cb() {
                        @Override
                        public void ok(String t) {
                            out[0] = t;
                            latch.countDown();
                        }

                        @Override
                        public void fail(String e) {
                            out[0] = "[ERR]" + e;
                            latch.countDown();
                        }
                    });
                    if (!latch.await(45, TimeUnit.SECONDS)) return err("翻译超时");
                    if (out[0] != null && out[0].startsWith("[ERR]")) return err(out[0].substring(5));
                    if (out[0] == null || out[0].trim().isEmpty()) return err("没有翻译结果");
                    return ok().put("text", out[0]);
                }
                /* ---------- 插件 ---------- */
                case "dshboxStatus":
                    return DshBoxBridge.status(this);
                case "dshboxAnalyze":
                    return DshBoxBridge.analyze(this, a.optString("text", ""));
                case "astrbotConfig":
                    return AstrBotBridge.config(this);
                case "astrbotApiConfigure":
                    return AstrBotBridge.configureApi(this, a.optString("baseUrl", ""), a.optString("apiKey", ""));
                case "astrbotAgentEnable":
                    return AstrBotBridge.setAgentEnabled(this, a.optBoolean("enabled", false));
                case "astrbotMobileToolsEnable":
                    return AstrBotBridge.setMobileToolsEnabled(this, a.optBoolean("enabled", false));
                case "astrbotMobileBridgeStatus":
                    return AstrBotBridge.mobileBridgeStatus(this);
                case "astrbotMobilePluginExport":
                    pickCreate(RQ_ASTRBOT_PLUGIN, "astrbot_plugin_dsh_mobile.zip");
                    return pending("请选择保存位置；导出的插件需复制到 AstrBot 电脑端安装");
                case "astrbotApiPlugins":
                    return AstrBotBridge.apiPlugins(this);
                case "astrbotApiMarket":
                    return AstrBotBridge.apiMarket(this, a.optInt("page", 1), a.optString("keyword", ""));
                case "astrbotApiInstallGithub":
                    return AstrBotBridge.installGithubPlugin(this, a.optString("repository", ""), a.optString("pluginId", ""),
                            a.optString("registryUrl", ""), a.optString("downloadUrl", ""));
                case "astrbotApiVerifyInstall":
                    return AstrBotBridge.verifyPluginInstall(this, a.optString("repository", ""), a.optString("pluginId", ""));
                case "astrbotPluginEnable":
                    return AstrBotBridge.setPluginEnabled(this, a.optString("pluginName", a.optString("pluginId", "")),
                            a.optBoolean("enabled", true));
                case "plugins":
                    return ok().put("plugins", Plugins.list(this));
                case "pluginImport":
                    pick(RQ_PLUGIN, "*/*");
                    return pending("正在选择插件包…");
                case "pluginToggle": {
                    String pluginId = a.optString("id", "");
                    boolean pluginOn = a.optBoolean("on", true);
                    if (pluginOn) {
                        String blockReason = Plugins.enableBlockReason(this, pluginId);
                        if (!blockReason.isEmpty()) return err(blockReason);
                        if (Plugins.isNativePlugin(this, pluginId)
                                && !a.optBoolean("nativeConfirmed", false)) {
                            return err("原生插件需要先在界面确认它会与桌宠同进程运行，并拥有相同权限。");
                        }
                    }
                    Plugins.toggle(this, pluginId, pluginOn);
                    return ok();
                }
                case "pluginDel":
                    Plugins.remove(this, a.optString("id", ""));
                    return ok();
                case "pluginQuick":
                    return ok().put("quick", Plugins.quick(this, a.optString("id", ""), a.optString("quickId", "")));
                /* ---------- 表情包 ---------- */
                case "stickerList":
                    return ok().put("stickers", Sticker.list(this));
                case "stickerImport":
                    pick(RQ_STICKER, "image/*");
                    return pending("正在选择表情图片…");
                case "stickerDel":
                    Sticker.delete(this, a.optString("id", ""));
                    return ok().put("stickers", Sticker.list(this));
                case "stickerData": {
                    Bitmap sb = Sticker.bitmap(this, a.optString("name", ""));
                    return ok().put("data", sb == null ? "" : Brain.imageData(sb));
                }
                case "stickerBatchImport":
                    pickMulti(RQ_STICKER_BATCH);
                    return pending("请选择多张表情图片…");
                case "stickerReplace":
                    pendingStickerId = a.optString("id", "");
                    pick(RQ_STICKER_REPLACE, "image/*");
                    return pending("请选择新的图片…");
                case "stickerDescribe":
                    Sticker.describeOne(this, a.optString("id", ""), (desc, err) -> {
                        event("stickers", new JSONObject());
                        event("toast", msgJson(err.isEmpty() ? "已重新识别：" + desc : err));
                    });
                    return pending("正在重新识别…");
                case "stickerDescribeAll":
                    Sticker.describeAll(this, stickerBatchCb());
                    return pending("正在逐个识别场景…");
                case "stickerSetDesc":
                    Sticker.setDesc(this, a.optString("id", ""), a.optString("desc", ""));
                    return ok().put("stickers", Sticker.list(this));
                /* ---------- 角色换装 ---------- */
                case "skins":
                    return ok().put("skins", SkinStore.list(this))
                            .put("skinId", SkinStore.current(this).id)
                            .put("live2dSupported", Live2DRenderer.supported());
                case "skinSelect": {
                    SkinStore.select(this, a.optString("id", ""));
                    SkinStore.SkinDef picked = SkinStore.current(this);
                    if (picked != null && picked.live2d && !Live2DRenderer.supported()) {
                        SkinStore.select(this, SkinStore.ORIGINAL);
                        return err("这个安装包是 32 位，无法渲染 Live2D 模型");
                    }
                    if (picked != null) InApp.track(this, "功能", "换肤", picked.name);
                    if (Cfg.load(this).b("running", false)) PetSvc.send(this, PetSvc.A_SKIN, null, null);
                    return ok().put("skinId", picked == null ? "original" : picked.id);
                }
                case "skinImport":
                    pick(RQ_SKIN, "image/*");
                    return pending("正在选择皮肤图片…");
                case "skinImportMulti":
                    pickMulti(RQ_SKIN_MULTI);
                    return pending("正在选择多张图片…");
                case "skinImportZip":
                    pickZip(RQ_SKIN_ZIP);
                    return pending("正在选择皮肤包…");
                case "skinImportAnim":
                    pickZip(RQ_SKIN_ANIM);
                    return pending("正在选择序列帧皮肤包…");
                case "skinImportLive2d":
                    pickZip(RQ_LIVE2D);
                    return pending("正在选择 Live2D 模型包…");
                case "skinExportZip":
                    pickCreate(RQ_SKIN_EXPORT, "DSH皮肤包.zip");
                    return pending("正在导出皮肤包…");
                case "skinData": {
                    SkinStore.SkinDef d = SkinStore.get(this, a.optString("id", ""));
                    Bitmap b = a.optBoolean("thumb", false)
                            ? SkinStore.loadThumb(this, d) : SkinStore.loadStand(this, d);
                    String data = b == null ? "" : SkinStore.dataUrl(b);
                    if (b != null) b.recycle();
                    return ok().put("data", data);
                }
                case "skinDel": {
                    SkinStore.delete(this, a.optString("id", ""));
                    if (Cfg.load(this).b("running", false)) PetSvc.send(this, PetSvc.A_SKIN, null, null);
                    return ok().put("skins", SkinStore.list(this))
                            .put("skinId", SkinStore.current(this).id);
                }
                /* ---------- 跨设备同步 ---------- */
                case "syncState":
                    return ok().put("sync", Sync.state(this));
                case "syncSave":
                    Sync.save(this, a.optJSONObject("json"));
                    return ok();
                case "syncTest":
                case "syncUpload":
                case "syncDownload": {
                    final String op = method;
                    CountDownLatch latch = new CountDownLatch(1);
                    final String[] out = {null};
                    Sync.Cb cb = new Sync.Cb() {
                        @Override
                        public void ok(String msg) {
                            out[0] = msg;
                            latch.countDown();
                        }

                        @Override
                        public void fail(String msg) {
                            out[0] = "[ERR]" + msg;
                            latch.countDown();
                        }
                    };
                    if ("syncTest".equals(op)) Sync.test(this, cb);
                    else if ("syncUpload".equals(op)) Sync.upload(this, cb);
                    else Sync.download(this, cb);
                    if (!latch.await(120, TimeUnit.SECONDS)) return err("同步超时");
                    if (out[0] != null && out[0].startsWith("[ERR]")) return err(out[0].substring(5));
                    return ok().put("message", out[0]);
                }
                /* ---------- 广告调试 / 开发者 ---------- */
                case "adTestMode": {
                    Cfg cfg = Cfg.load(this);
                    cfg.set("adTest", a.optBoolean("on", false));
                    cfg.save(this);
                    return ok();
                }
                case "adAppToggle":
                    AdRules.setAppEnabled(this, a.optString("pkg", ""), a.optBoolean("on", true));
                    return ok();
                case "adTestRun":
                    return ok().put("result", A11y.testRules(a.optString("pkg", "")));
                case "adLog":
                    return ok().put("log", AdRules.recentLog(this, 80));
                case "devState": {
                    JSONObject r = ok();
                    r.put("version", Ver.NAME);
                    r.put("prompt", Brain.systemPrompt(this, false));
                    r.put("cfgPath", Sg.dir(this).getAbsolutePath());
                    r.put("policy", Policy.summary(this));
                    r.put("notif", NotifyStore.summary(this));
                    r.put("ad", AdRules.summary(this));
                    return r;
                }
                case "diagExport": {
                    JSONObject r = ok();
                    JSONObject d = new JSONObject();
                    d.put("version", "2.1.0");
                    d.put("path", Sg.dir(this).getAbsolutePath());
                    d.put("perms", Perm.status(this));
                    d.put("state", stateObj());
                    d.put("policy", Policy.summary(this));
                    d.put("moods", MoodLog.list(this, 30));
                    r.put("diag", d);
                    Sg.writeObj(this, "diag_report.json", d);
                    return r;
                }
                default:
                    return err("未知方法 " + method);
            }
        } catch (Throwable t) {
            if ("chat".equals(method)) return err(Ai.userFacingError(t.getMessage()));
            return err(t.getClass().getSimpleName() + ": " + (t.getMessage() == null ? "" : t.getMessage()));
        }
    }

    private JSONObject boot() {
        Cfg cfg = Cfg.load(this);
        try { ChatFiles.cleanup(this); } catch (Throwable ignored) { }
        try { ChatVideos.cleanup(this); } catch (Throwable ignored) { }
        try { LongDocs.cleanup(this); } catch (Throwable ignored) { }
        try {
            Music.refresh(this);
        } catch (Throwable ignored) {
        }
        JSONObject r = new JSONObject();
        try {
            try {
                Plugins.ensurePresets(this);
            } catch (Throwable ignored) {
            }
            try {
                Data.upgradePersona(this);
            } catch (Throwable ignored) {
            }
            r.put("ok", true);
            r.put("cfg", cfg.json());
            r.put("qqPersonas", QQPersonas.state(this));
            r.put("persona", Data.persona(this));
            r.put("personaSeed", Persona.summary(this));
            r.put("profile", Data.profile(this));
            r.put("perms", Perm.status(this));
            r.put("state", stateObj());
            r.put("stats", Data.stats(this));
            r.put("mood", Data.mood(this));
            r.put("days", Data.days(this));
            r.put("catalog", catalog());
            r.put("memories", Data.memories(this));
            r.put("companionRules", CompanionRules.list(this));
            r.put("reminders", Data.reminders(this));
            r.put("ad", AdRules.summary(this));
            r.put("adLines", Data.adLines(this));
            r.put("music", musicObj());
            r.put("chat", Data.chatList(this, 60));
            r.put("storage", new JSONObject().put("path", Sg.dir(this).getAbsolutePath())
                    .put("public", false).put("publicPath", Perm.allFilesPath()));
            r.put("unread", Bus.unreadCount);
            r.put("pomo", PetSvc.pomoState());
            r.put("version", Ver.NAME);
            r.put("policy", Policy.summary(this));
            r.put("policyJson", Policy.json(this));
            r.put("health", Perm.health(this));
            r.put("plugins", Plugins.list(this));
            r.put("stickers", Sticker.list(this));
            r.put("tasks", Taskbook.list(this));
            r.put("taskFocus", Taskbook.active(this));
            r.put("taskSummary", Taskbook.focusSummary(this, 7));
            r.put("events", Taskbook.events(this, 48));
            r.put("notif", NotifyStore.summary(this));
            r.put("notifList", NotifyStore.group(this));
            r.put("geo", Geo.state(this));
            r.put("well", Wellness.state(this));
            r.put("wellSteps", Wellness.steps());
            r.put("musicx", MusicX.state(this));
            r.put("sync", Sync.state(this));
            r.put("tts", Voice.state(this));
            r.put("voices", Voice.voices(this));
            r.put("clips", Clip.list(this));
            r.put("moods", MoodLog.list(this, 60));
            r.put("memSummary", Mem.summary(this));
            r.put("memReviewDue", Mem.reviewDue(this));
            // 关系与她的内心：亲密度、性格演化、日记、共同经历、她自己惦记的事
            r.put("intimacy", Intimacy.summary(this));
            r.put("traits", Traits.summary(this));
            r.put("diary", Diary.summary(this));
            r.put("timeline", Timeline.summary(this));
            r.put("caring", SelfMem.summary(this));
            // 初始契约（首次表单）与临时情绪
            r.put("contract", Contract.summary(this));
            r.put("emotion", State.summary(this));
            r.put("report", Sg.readObj(this, "report_last.json"));
            Voice.init(this);
        } catch (Throwable ignored) {
        }
        return r;
    }

    private JSONObject stateObj() {
        JSONObject o = new JSONObject();
        try {
            Cfg cfg = Cfg.load(this);
            o.put("ok", true);
            o.put("running", cfg.b("running", false) && PetSvc.running);
            o.put("locked", cfg.b("locked", false));
            o.put("size", cfg.sizeDp());
            o.put("opacity", cfg.opacity());
            o.put("pkg", Bus.currentPackage);
            String st = Bus.screenText;
            if (st.length() > 220) st = st.substring(0, 220);
            o.put("screenText", st);
            o.put("music", musicObj());
            o.put("days", Data.days(this));
            o.put("affection", Data.affection(this));
        } catch (Throwable ignored) {
        }
        return o;
    }

    private JSONObject musicObj() {
        JSONObject m = new JSONObject();
        try {
            m.put("title", Music.title());
            m.put("artist", Music.artist());
            m.put("playing", Music.playing());
            m.put("lines", Music.lines() == null ? 0 : Music.lines().size());
            m.put("position", Music.position());
            m.put("app", Music.sourceApp());
            m.put("lyricSource", Music.lyricSource());
            m.put("lyricStatus", MusicX.lyricStatus(this));
            m.put("lyric", Music.currentLineText());
            // 诊断：这台手机到底读到什么，用来排查华为/iQOO 不显示歌词的问题
            m.put("diag", Music.diag(this));
        } catch (Throwable ignored) {
        }
        return m;
    }

    private void emitState() {
        event("state", stateObj());
    }

    private JSONArray catalog() {
        PetConf conf = PetConf.load(this);
        JSONObject lines = Data.actionLines(this);
        java.util.HashSet<String> used = new java.util.HashSet<>();
        JSONArray cats = new JSONArray();
        for (PetConf.Cat c : conf.cats) {
            JSONArray acts = new JSONArray();
            for (String slug : c.actions) {
                used.add(slug);
                acts.put(actionObj(slug, conf.nameOf(slug), lines));
            }
            if (acts.length() > 0) {
                try {
                    cats.put(new JSONObject().put("id", c.id).put("weight", c.weight).put("actions", acts));
                } catch (Throwable ignored) {
                }
            }
        }
        JSONArray base = new JSONArray();
        java.util.List<String> basics = new java.util.ArrayList<>();
        basics.addAll(conf.idle);
        basics.addAll(conf.turn);
        basics.addAll(conf.drag);
        basics.addAll(conf.clicks);
        for (PetConf.Move m : conf.moves) basics.add(m.slug);
        for (String slug : basics) {
            used.add(slug);
            base.put(actionObj(slug, conf.nameOf(slug), lines));
        }
        if (base.length() > 0) {
            try {
                cats.put(new JSONObject().put("id", "基础动作").put("weight", 0).put("actions", base));
            } catch (Throwable ignored) {
            }
        }
        JSONArray other = new JSONArray();
        for (String slug : conf.names.keySet()) {
            if (!used.contains(slug)) other.put(actionObj(slug, conf.nameOf(slug), lines));
        }
        if (other.length() > 0) {
            try {
                cats.put(new JSONObject().put("id", "未分类").put("weight", 0).put("actions", other));
            } catch (Throwable ignored) {
            }
        }
        return cats;
    }

    private JSONObject actionObj(String slug, String name, JSONObject lines) {
        JSONObject o = new JSONObject();
        try {
            JSONObject l = lines.optJSONObject(slug);
            o.put("slug", slug);
            o.put("name", name);
            o.put("line", l == null ? "" : l.optString("line", ""));
            o.put("ai", l != null && l.optBoolean("ai", false));
        } catch (Throwable ignored) {
        }
        return o;
    }

    private JSONObject ok() {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", true);
        } catch (Throwable ignored) {
        }
        return o;
    }

    private JSONObject err(String msg) {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", false);
            o.put("error", msg == null ? "出错了" : msg);
        } catch (Throwable ignored) {
        }
        return o;
    }

    /** 首次表单的语调选项 → 说话风格描述。 */
    private static String toneStyle(String tone) {
        if (tone == null) tone = "";
        switch (tone) {
            case "直爽":
                return "中文口语，短句，直接爽快，不拐弯抹角，有话直说。";
            case "冷淡":
                return "中文口语，短句，语气平淡，话不多，不主动展开，但不是没感情。";
            case "俏皮":
                return "中文口语，短句，爱开玩笑、爱逗人，带点调皮的语气词。";
            case "软萌":
            default:
                return "中文口语，短句，带点语气词，软乎乎的，像真人聊天。";
        }
    }

    private JSONObject pending(String msg) {
        JSONObject o = ok();
        try {
            o.put("pending", true);
            o.put("message", msg);
        } catch (Throwable ignored) {
        }
        return o;
    }

    private static JSONObject msgJson(String msg) {
        JSONObject o = new JSONObject();
        try {
            o.put("message", msg);
        } catch (Throwable ignored) {
        }
        return o;
    }

    private void requestPerms(String[] p) {
        if (android.os.Build.VERSION.SDK_INT >= 23) requestPermissions(p, RQ_PERM);
    }

    /** 将用户选中的图片裁切为轻量方形头像，并编码为本机资料字段。 */
    private static String profileAvatarData(Bitmap source) {
        if (source == null || source.getWidth() <= 0 || source.getHeight() <= 0) return "";
        Bitmap square = null;
        Bitmap output = null;
        try {
            int side = Math.min(source.getWidth(), source.getHeight());
            square = Bitmap.createBitmap(source, (source.getWidth() - side) / 2,
                    (source.getHeight() - side) / 2, side, side);
            output = Bitmap.createScaledBitmap(square, 256, 256, true);
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            if (!output.compress(Bitmap.CompressFormat.JPEG, 84, bytes)) return "";
            byte[] encoded = bytes.toByteArray();
            if (encoded.length > 220000) {
                bytes.reset();
                if (!output.compress(Bitmap.CompressFormat.JPEG, 68, bytes)) return "";
                encoded = bytes.toByteArray();
            }
            if (encoded.length == 0 || encoded.length > 220000) return "";
            return "data:image/jpeg;base64," + android.util.Base64.encodeToString(encoded, android.util.Base64.NO_WRAP);
        } catch (Throwable ignored) {
            return "";
        } finally {
            if (output != null && output != square && output != source && !output.isRecycled()) output.recycle();
            if (square != null && square != source && !square.isRecycled()) square.recycle();
            if (!source.isRecycled()) source.recycle();
        }
    }

    private Bitmap decodeDataUrl(String s) {
        if (s == null || s.trim().isEmpty()) return null;
        try {
            String raw = s.trim();
            int comma = raw.indexOf(',');
            if (comma >= 0) raw = raw.substring(comma + 1);
            byte[] b = android.util.Base64.decode(raw, android.util.Base64.DEFAULT);
            if (b.length == 0) return null;
            return android.graphics.BitmapFactory.decodeByteArray(b, 0, b.length);
        } catch (Throwable t) {
            return null;
        }
    }

    private void pick(int code, String mime) {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType(mime);
            startActivityForResult(i, code);
        } catch (Throwable t) {
            event("error", new JSONObject());
        }
    }

    /** 一次选多张图，走 SAF 的多选（所有版本都支持）。 */
    private void pickMulti(int code) {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("image/*");
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(i, code);
        } catch (Throwable t) {
            event("error", msgJson("无法打开图片选择器"));
        }
    }

    /** 选皮肤包（zip）；有些文件管理器会把 zip 报成 octet-stream，这里一起放行。 */
    private void pickZip(int code) {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                    "application/zip", "application/x-zip-compressed", "application/octet-stream"});
            startActivityForResult(i, code);
        } catch (Throwable t) {
            event("error", msgJson("无法打开文件选择器"));
        }
    }

    private String displayName(Uri u) {
        try (android.database.Cursor cur = getContentResolver().query(u, null, null, null, null)) {
            if (cur != null && cur.moveToFirst()) {
                int i = cur.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (i >= 0) return cur.getString(i);
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    /** 批量导入多张图：文件名当皮肤名，逐张抠图保存。 */
    private void importSkinUris(final List<Uri> uris) {
        if (uris == null || uris.isEmpty()) return;
        event("toast", msgJson("正在处理 " + uris.size() + " 张图片…"));
        worker.execute(() -> {
            int okCount = 0;
            for (Uri u : uris) {
                Bitmap b = PickAct.decode(this, u, 1600);
                if (b == null) continue;
                String nm = skinNameOf(displayName(u));
                JSONObject r = SkinStore.importCustom(this, nm, b);
                if (!b.isRecycled()) b.recycle();
                if (r.optBoolean("ok", false)) okCount++;
            }
            final int n = okCount;
            runOnUiThread(() -> {
                event("skins", skinPayload());
                event("toast", msgJson(n > 0 ? "已导入 " + n + " 套皮肤" : "没有导入成功，检查图片是否白底"));
                if (n > 0 && Cfg.load(this).b("running", false)) PetSvc.send(this, PetSvc.A_SKIN, null, null);
            });
        });
    }

    private static String skinNameOf(String file) {
        String s = file == null ? "" : file.trim();
        int dot = s.lastIndexOf('.');
        if (dot > 0) s = s.substring(0, dot);
        s = s.replace("[", "").replace("]", "").trim();
        if (s.isEmpty()) s = "导入皮肤";
        return s.length() > 12 ? s.substring(0, 12) : s;
    }

    /** 选图后先让用户确认名字和场景；描述留空可选让 AI 看图识别。 */
    private void askStickerSave(Bitmap bmp) {
        int pad = (int) (getResources().getDisplayMetrics().density * 16);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);

        final EditText name = new EditText(this);
        name.setHint("名字，例如：开心");
        name.setText("表情" + (Sticker.list(this).length() + 1));
        name.setSingleLine(true);
        box.addView(name);

        final EditText desc = new EditText(this);
        desc.setHint("适合什么场景，可留空");
        desc.setSingleLine(true);
        box.addView(desc);

        final CheckBox ai = new CheckBox(this);
        ai.setText("让 AI 看图识别适用场景（少量 token）");
        ai.setChecked(Cfg.load(this).aiReady());
        box.addView(ai);

        new AlertDialog.Builder(this)
                .setTitle("保存表情包")
                .setView(box)
                .setPositiveButton("保存", (d, w) -> saveSticker(
                        name.getText().toString(), desc.getText().toString(), ai.isChecked(), bmp))
                .setNegativeButton("取消", (d, w) -> {
                    if (bmp != null && !bmp.isRecycled()) bmp.recycle();
                })
                .show();
    }

    /** 选图后先确认皮肤名字，再交给 SkinStore 抠图入库。 */
    private void askSkinSave(Bitmap bmp) {
        int pad = (int) (getResources().getDisplayMetrics().density * 16);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);

        final EditText name = new EditText(this);
        name.setHint("皮肤名字");
        name.setText("我的皮肤");
        name.setSingleLine(true);
        box.addView(name);

        TextView hint = new TextView(this);
        hint.setText("白底图会自动抠图；建议竖图、角色完整、背景干净。");
        hint.setTextSize(12);
        hint.setTextColor(0xFF8A94A8);
        hint.setPadding(0, pad / 2, 0, 0);
        box.addView(hint);

        new AlertDialog.Builder(this)
                .setTitle("导入皮肤")
                .setView(box)
                .setPositiveButton("导入", (d, w) -> saveSkin(name.getText().toString(), bmp))
                .setNegativeButton("取消", (d, w) -> {
                    if (bmp != null && !bmp.isRecycled()) bmp.recycle();
                })
                .show();
    }

    private void saveSkin(String name, Bitmap bmp) {
        worker.execute(() -> {
            JSONObject r = SkinStore.importCustom(this, name, bmp);
            if (r.optBoolean("ok", false)) {
                event("skins", skinPayload());
                event("toast", msgJson("皮肤已导入并换上"));
                if (Cfg.load(this).b("running", false)) PetSvc.send(this, PetSvc.A_SKIN, null, null);
            } else {
                event("error", msgJson(r.optString("error", "导入失败")));
            }
            if (bmp != null && !bmp.isRecycled()) bmp.recycle();
        });
    }

    /** 换装页的统一数据：皮肤列表 + 当前选中。 */
    private JSONObject skinPayload() {
        JSONObject o = new JSONObject();
        try {
            o.put("skins", SkinStore.list(this));
            o.put("skinId", SkinStore.current(this).id);
            o.put("live2dSupported", Live2DRenderer.supported());
        } catch (Throwable ignored) {
        }
        return o;
    }

    /** 批量识别的进度/结果都回传给前端，用来显示“3/8”和最后汇总。 */
    private Sticker.BatchCb stickerBatchCb() {
        return new Sticker.BatchCb() {
            @Override
            public void progress(int done, int total, String name) {
                try {
                    JSONObject o = new JSONObject();
                    o.put("done", done);
                    o.put("total", total);
                    o.put("name", name);
                    event("stickerProg", o);
                } catch (Throwable ignored) {
                }
            }

            @Override
            public void done(int ok, int fail) {
                event("stickers", new JSONObject());
                try {
                    JSONObject o = new JSONObject();
                    o.put("ok", ok);
                    o.put("fail", fail);
                    event("stickerBatchDone", o);
                } catch (Throwable ignored) {
                }
                event("toast", msgJson("识别完成：成功 " + ok + " 张"
                        + (fail > 0 ? "，失败 " + fail + " 张" : "")));
            }
        };
    }

    private void saveSticker(String name, String desc, boolean autoAi, Bitmap bmp) {
        String nm = name == null ? "" : name.trim();
        nm = nm.replace("[", "").replace("]", "").replace("\n", " ").trim();
        if (nm.isEmpty()) nm = "表情" + (Sticker.list(this).length() + 1);
        if (nm.length() > 12) nm = nm.substring(0, 12);
        String ds = desc == null ? "" : desc.trim();
        final String fn = nm;
        final boolean wantAi = autoAi && ds.isEmpty();
        worker.execute(() -> {
            JSONObject r = Sticker.add(this, fn, ds, bmp);
            event("stickers", r);
            if (r.optBoolean("ok", false)) {
                event("toast", msgJson(r.optBoolean("new", true) ? "表情包已保存" : "已更新同名表情包"));
                InApp.track(this, "功能", "上传表情包", fn);
                if (wantAi) Sticker.autoDescribe(this, fn, bmp);
            } else {
                event("error", msgJson(r.optString("error", "保存失败")));
            }
            if (bmp != null && !bmp.isRecycled()) bmp.recycle();
        });
    }

    private void pickBooks() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                    "text/plain", "application/epub+zip", "application/pdf",
                    "application/x-mobipocket-ebook", "application/vnd.amazon.ebook",
                    "application/octet-stream", "application/zip"});
            startActivityForResult(i, RQ_BOOK);
        } catch (Throwable t) {
            event("error", msgJson("无法打开文件选择器"));
        }
    }

    private static String sanitize(String s) {
        String x = s == null ? "book" : s.replaceAll("[\\\\/:*?\"<>|\\s]+", "_").trim();
        return x.isEmpty() ? "book" : (x.length() > 60 ? x.substring(0, 60) : x);
    }

    private void pickCreate(int code, String name) {
        try {
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/zip");
            i.putExtra(Intent.EXTRA_TITLE, name);
            startActivityForResult(i, code);
        } catch (Throwable ignored) {
        }
    }

    private void pickCreateText(int code, String name) {
        try {
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_TITLE, name);
            startActivityForResult(i, code);
        } catch (Throwable ignored) {
        }
    }

    private void pickCreateLongDoc(String name, String format) {
        try {
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("jar".equals(format) ? "application/java-archive"
                    : "md".equals(format) ? "text/markdown" : "text/plain");
            i.putExtra(Intent.EXTRA_TITLE, name);
            startActivityForResult(i, RQ_LONG_DOC);
        } catch (Throwable t) {
            pendingLongDocId = "";
            pendingLongDocFormat = "";
            event("error", msgJson("无法打开保存位置"));
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == RQ_CHAT_CAMERA) {
            JSONObject out = new JSONObject();
            if (res == RESULT_OK && data != null && data.getExtras() != null) {
                Object image = data.getExtras().get("data");
                if (image instanceof Bitmap) {
                    String encoded = Brain.imageData((Bitmap) image);
                    if (!encoded.isEmpty()) try { out.put("data", encoded); } catch (Throwable ignored) { }
                }
            } else try { out.put("cancel", true); } catch (Throwable ignored) { }
            event("chatImage", out);
            return;
        }
        if (req == RQ_CHAT_FILE) {
            if (res != RESULT_OK || data == null || data.getData() == null) return;
            Uri uri = data.getData();
            worker.execute(() -> {
                JSONObject out = ChatFiles.importUri(this, uri);
                event("chatTextFile", out);
            });
            return;
        }
        if (req == RQ_CHAT_VIDEO) {
            if (res != RESULT_OK || data == null || data.getData() == null) return;
            Uri uri = data.getData();
            worker.execute(() -> {
                JSONObject out = ChatVideos.importUri(this, uri);
                event("chatVideo", out);
            });
            return;
        }
        if (req == RQ_LONG_DOC) {
            final String id = pendingLongDocId;
            final String format = pendingLongDocFormat;
            pendingLongDocId = "";
            pendingLongDocFormat = "";
            if (res != RESULT_OK || data == null || data.getData() == null) return;
            final Uri destination = data.getData();
            worker.execute(() -> {
                boolean saved = LongDocs.export(this, id, format, destination);
                event(saved ? "toast" : "error", msgJson(saved ? "文件已保存" : "保存失败，文档可能已过期"));
            });
            return;
        }
        if (req == RQ_QQ_PERSONA_IMPORT) {
            if (res != RESULT_OK || data == null || data.getData() == null) return;
            Uri source = data.getData();
            worker.execute(() -> {
                JSONObject imported = QQPersonas.importUri(this, source, displayName(source));
                event("qqPersonaImported", imported);
            });
            return;
        }
        if (req == RQ_COMFY_WORKFLOW_IMPORT) {
            if (res != RESULT_OK || data == null || data.getData() == null) return;
            Uri source = data.getData();
            worker.execute(() -> {
                JSONObject result = new JSONObject();
                try {
                    String workflow;
                    try (InputStream input = getContentResolver().openInputStream(source);
                         ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                        if (input == null) throw new Exception("无法打开所选文件");
                        byte[] buffer = new byte[8192];
                        int count;
                        while ((count = input.read(buffer)) != -1) {
                            if (output.size() + count > 1024 * 1024) throw new Exception("工作流文件超过 1 MB");
                            output.write(buffer, 0, count);
                        }
                        workflow = new String(output.toByteArray(), StandardCharsets.UTF_8);
                        if (!workflow.isEmpty() && workflow.charAt(0) == '\uFEFF') workflow = workflow.substring(1);
                        workflow = workflow.trim();
                    }
                    JSONObject parsed = new JSONObject(workflow);
                    if (parsed.length() == 0 || parsed.has("nodes")) {
                        throw new Exception("这不是 API 格式工作流，请在 ComfyUI 导出 API 格式 JSON");
                    }
                    result.put("ok", true).put("workflow", workflow).put("name", displayName(source));
                } catch (Throwable error) {
                    String message = error.getMessage() == null ? "工作流文件读取失败" : error.getMessage();
                    try { result.put("ok", false).put("error", message); } catch (Throwable ignored) { }
                }
                event("comfyWorkflowImported", result);
            });
            return;
        }
        if (req == RQ_PROFILE_AVATAR) {
            if (res != RESULT_OK || data == null || data.getData() == null) return;
            Uri source = data.getData();
            worker.execute(() -> {
                Bitmap bitmap = PickAct.decode(this, source, 512);
                String encoded = profileAvatarData(bitmap);
                JSONObject result = new JSONObject();
                try {
                    if (encoded.isEmpty()) result.put("ok", false).put("error", "这张图片读取失败，请换一张再试");
                    else result.put("ok", true).put("data", encoded);
                } catch (Throwable ignored) { }
                event("profileAvatarPicked", result);
            });
            return;
        }
        if (req == RQ_ASTRBOT_PLUGIN) {
            if (res != RESULT_OK || data == null || data.getData() == null) return;
            Uri destination = data.getData();
            worker.execute(() -> {
                boolean saved = exportAstrbotMobilePlugin(destination);
                event(saved ? "toast" : "error", msgJson(saved
                        ? "已保存 DSH 手机桥接插件包；请传到电脑并安装到 AstrBot"
                        : "导出 DSH 手机桥接插件失败"));
            });
            return;
        }
        if (res != RESULT_OK || data == null) return;
        if (req == RQ_BOOK) {
            importBookUris(data);
            return;
        }
        if (req == RQ_STICKER) {
            final Uri u = data.getData();
            if (u == null) return;
            worker.execute(() -> {
                final Bitmap bmp = PickAct.decode(this, u, 480);
                runOnUiThread(() -> {
                    if (bmp == null) {
                        event("error", msgJson("图片读取失败"));
                        return;
                    }
                    askStickerSave(bmp);
                });
            });
            return;
        }
        if (req == RQ_STICKER_BATCH) {
            java.util.ArrayList<Uri> uris = new java.util.ArrayList<>();
            android.content.ClipData cd = data.getClipData();
            if (cd != null) {
                for (int i = 0; i < cd.getItemCount(); i++) {
                    Uri u = cd.getItemAt(i).getUri();
                    if (u != null) uris.add(u);
                }
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
            if (uris.isEmpty()) return;
            final java.util.List<Uri> picked = uris.subList(0, Math.min(uris.size(), 20));
            worker.execute(() -> {
                int n = 0;
                java.util.ArrayList<String> importedIds = new java.util.ArrayList<>();
                for (Uri u : picked) {
                    Bitmap bmp = PickAct.decode(this, u, 480);
                    if (bmp == null) continue;
                    String nm = Sticker.uniqueName(this, "表情");
                    JSONObject r = Sticker.add(this, nm, "", bmp);
                    if (!bmp.isRecycled()) bmp.recycle();
                    if (r.optBoolean("ok", false)) {
                        n++;
                        String id = r.optString("id", "");
                        if (!id.isEmpty()) importedIds.add(id);
                    }
                }
                final int okN = n;
                final java.util.ArrayList<String> idsToDescribe = importedIds;
                final boolean ai = Cfg.load(this).aiReady();
                event("stickers", new JSONObject());
                event("toast", msgJson(okN == 0 ? "没有导入成功，检查图片格式"
                        : "已导入 " + okN + " 张" + (ai ? "，正在识别场景…" : "")));
                if (!idsToDescribe.isEmpty() && ai) Sticker.describeIds(this, idsToDescribe, stickerBatchCb());
            });
            return;
        }
        if (req == RQ_STICKER_REPLACE) {
            final String id = pendingStickerId;
            pendingStickerId = "";
            final Uri u = data.getData();
            if (u == null) return;
            worker.execute(() -> {
                Bitmap bmp = PickAct.decode(this, u, 480);
                if (bmp == null) {
                    event("error", msgJson("图片读取失败"));
                    return;
                }
                boolean ok = Sticker.replace(this, id, bmp);
                if (!bmp.isRecycled()) bmp.recycle();
                event("stickers", new JSONObject());
                event("toast", msgJson(ok ? "图片已更换" : "替换失败"));
            });
            return;
        }
        if (req == RQ_SKIN) {
            final Uri u = data.getData();
            if (u == null) return;
            worker.execute(() -> {
                final Bitmap bmp = PickAct.decode(this, u, 1600);
                runOnUiThread(() -> {
                    if (bmp == null) {
                        event("error", msgJson("图片读取失败"));
                        return;
                    }
                    askSkinSave(bmp);
                });
            });
            return;
        }
        if (req == RQ_SKIN_MULTI) {
            java.util.ArrayList<Uri> list = new java.util.ArrayList<>();
            android.content.ClipData cd = data == null ? null : data.getClipData();
            if (cd != null) {
                for (int i = 0; i < cd.getItemCount(); i++) list.add(cd.getItemAt(i).getUri());
            } else if (data != null && data.getData() != null) {
                list.add(data.getData());
            }
            importSkinUris(list);
            return;
        }
        if (req == RQ_SKIN_ZIP) {
            final Uri u = data == null ? null : data.getData();
            if (u == null) return;
            worker.execute(() -> {
                final int n = SkinZip.importZip(this, u);
                runOnUiThread(() -> {
                    event("skins", skinPayload());
                    event("toast", msgJson(n > 0 ? "皮肤包已导入 " + n + " 套" : "皮肤包里没有可用的图片"));
                    if (n > 0 && Cfg.load(this).b("running", false)) PetSvc.send(this, PetSvc.A_SKIN, null, null);
                });
            });
            return;
        }
        if (req == RQ_SKIN_ANIM) {
            final Uri u = data == null ? null : data.getData();
            if (u == null) return;
            final String fileName = displayName(u);
            worker.execute(() -> {
                final JSONObject r = SkinZip.importAnim(this, u, skinNameOf(fileName));
                runOnUiThread(() -> {
                    event("skins", skinPayload());
                    event("toast", msgJson(r.optBoolean("ok", false)
                            ? "已导入动图皮肤「" + r.optString("name", "新皮肤") + "」共 " + r.optInt("count", 0) + " 帧"
                            : r.optString("error", "导入失败")));
                    if (r.optBoolean("ok", false) && Cfg.load(this).b("running", false)) {
                        PetSvc.send(this, PetSvc.A_SKIN, null, null);
                    }
                });
            });
            return;
        }
        if (req == RQ_LIVE2D) {
            final Uri u = data == null ? null : data.getData();
            if (u == null) return;
            final String fileName = displayName(u);
            worker.execute(() -> {
                final JSONObject r = SkinZip.importLive2d(this, u, skinNameOf(fileName));
                runOnUiThread(() -> {
                    event("skins", skinPayload());
                    event(r.optBoolean("ok", false) ? "toast" : "error", msgJson(r.optBoolean("ok", false)
                            ? "已导入 Live2D 模型「" + r.optString("name", "新皮肤") + "」"
                            : r.optString("error", "导入失败")));
                    if (r.optBoolean("ok", false) && Cfg.load(this).b("running", false)) {
                        PetSvc.send(this, PetSvc.A_SKIN, null, null);
                    }
                });
            });
            return;
        }
        if (req == RQ_SKIN_EXPORT) {
            final Uri u = data == null ? null : data.getData();
            if (u == null) return;
            worker.execute(() -> {
                final int n = SkinZip.exportTo(this, u);
                runOnUiThread(() -> event("toast", msgJson(
                        n > 0 ? "已导出 " + n + " 套自定义皮肤" : "还没有自定义皮肤可导出")));
            });
            return;
        }
        if (data.getData() == null) return;
        final Uri uri = data.getData();
        if (req == RQ_RULES) {
            worker.execute(() -> {
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                    String text = new String(bo.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
                    JSONObject r = AdRules.importText(this, text);
                    r.put("ok", true);
                    event("adrules", r);
                    event("toast", msgJson("规则已导入"));
                } catch (Throwable t) {
                    event("error", msgJson("读取规则失败"));
                }
            });
        } else if (req == RQ_EXPORT) {
            worker.execute(() -> {
                try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                    Sg.zipDir(Sg.dir(this), out);
                    event("toast", msgJson("已导出 S-G 备份"));
                } catch (Throwable t) {
                    event("error", msgJson("导出失败"));
                }
            });
        } else if (req == RQ_IMPORT) {
            worker.execute(() -> {
                java.io.File tmp = new java.io.File(getCacheDir(), "import.zip");
                try (InputStream in = getContentResolver().openInputStream(uri);
                     OutputStream out = new java.io.FileOutputStream(tmp)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    // 走和恢复备份同一条事务路径：停服务 → 临时目录解压 → 校验 → 原子替换
                    JSONObject r = Backup.apply(this, tmp, "", "replace");
                    if (!r.optBoolean("ok", false)) {
                        event("error", msgJson(r.optString("error", "导入失败")));
                        return;
                    }
                    A11y.reload();
                    event("toast", msgJson("备份已导入，重新载入中"));
                    event("imported", new JSONObject());
                } catch (Throwable t) {
                    event("error", msgJson("导入失败"));
                } finally {
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                }
            });
        } else if (req == RQ_PLUGIN) {
            worker.execute(() -> {
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    JSONObject r = Plugins.install(this, in);
                    r.put("ok", !r.has("error"));
                    event("plugins", r);
                    event("toast", msgJson(r.has("error") ? r.optString("error") : "已导入，正在检查；插件保持关闭"));
                } catch (Throwable t) {
                    event("error", msgJson("插件安装失败"));
                }
            });
        } else if (req == RQ_BACKUP) {
            worker.execute(() -> {
                java.io.File tmp = new java.io.File(getCacheDir(), "backup.zip");
                try {
                    try (OutputStream fo = new java.io.FileOutputStream(tmp)) {
                        Backup.exportTo(this, fo, pendingPass);
                    }
                    try (InputStream in = new java.io.FileInputStream(tmp);
                         OutputStream out = getContentResolver().openOutputStream(uri)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    }
                    event("toast", msgJson(pendingPass.isEmpty() ? "备份已导出" : "加密备份已导出"));
                } catch (Throwable t) {
                    event("error", msgJson("导出失败：" + (t.getMessage() == null ? "" : t.getMessage())));
                } finally {
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                }
            });
        } else if (req == RQ_RESTORE) {
            final String mode = pendingMode;
            final String pass = pendingPass;
            worker.execute(() -> {
                java.io.File tmp = new java.io.File(getCacheDir(), "restore.zip");
                try (InputStream in = getContentResolver().openInputStream(uri);
                     OutputStream out = new java.io.FileOutputStream(tmp)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                } catch (Throwable t) {
                    event("error", msgJson("读取备份失败"));
                    return;
                }
                if (!Backup.isEncrypted(tmp) && !pass.isEmpty()) {
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                    event("error", msgJson("这是未加密备份，请清空密码再试"));
                    return;
                }
                if ("preview".equals(mode)) {
                    JSONObject p = Backup.preview(this, tmp, pass);
                    event("backupPreview", p);
                } else {
                    JSONObject r = Backup.apply(this, tmp, pass, mode);
                    event("backupApplied", r);
                    // Backup.apply 内部已停过服务并在成功后按原状态拉起，这里只重载无障碍
                    if (r.optBoolean("ok", false)) A11y.reload();
                }
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            });
        } else if (req == RQ_BOOK_COVER) {
            final String id = pendingBookId;
            worker.execute(() -> {
                JSONObject r = Books.setCover(this, id, uri);
                event("toast", msgJson(r.optBoolean("ok") ? "封面已更新" : r.optString("error", "封面失败")));
                event("books", Books.list(this));
            });
        } else if (req == RQ_BOOK_EXPORT) {
            final String id = pendingBookId;
            worker.execute(() -> {
                try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                    if (id == null || id.isEmpty()) Books.exportAll(this, out);
                    else Books.exportBook(this, id, out);
                    event("toast", msgJson("书籍已导出"));
                } catch (Throwable t) {
                    event("error", msgJson("导出失败"));
                }
            });
        } else if (req == RQ_BOOK_IMPORT) {
            worker.execute(() -> {
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    JSONObject r = Books.restore(this, in);
                    event("books", Books.list(this));
                    event("toast", msgJson(r.optBoolean("ok") ? "已恢复 " + r.optInt("count", 0) + " 本书" : r.optString("error", "恢复失败")));
                } catch (Throwable t) {
                    event("error", msgJson("恢复失败"));
                }
            });
        } else if (req == RQ_BOOK_NOTES) {
            final String id = pendingBookId;
            worker.execute(() -> {
                try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                    Books.exportNotes(this, id, out);
                    event("toast", msgJson("笔记已导出"));
                } catch (Throwable t) {
                    event("error", msgJson("导出笔记失败"));
                }
            });
        }
    }

    private boolean exportAstrbotMobilePlugin(Uri destination) {
        if (destination == null) return false;
        String[] files = {"metadata.yaml", "main.py", "README.md"};
        try (OutputStream raw = getContentResolver().openOutputStream(destination)) {
            if (raw == null) return false;
            try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(raw)) {
                byte[] buffer = new byte[8192];
                for (String name : files) {
                    zip.putNextEntry(new java.util.zip.ZipEntry("astrbot_plugin_dsh_mobile/" + name));
                    try (InputStream in = getAssets().open("astrbot_plugin_dsh_mobile/" + name)) {
                        int read;
                        while ((read = in.read(buffer)) >= 0) zip.write(buffer, 0, read);
                    }
                    zip.closeEntry();
                }
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private void importBookUris(Intent data) {
        final List<Uri> uris = new ArrayList<>();
        try {
            android.content.ClipData clip = data.getClipData();
            if (clip != null) {
                for (int k = 0; k < clip.getItemCount(); k++) {
                    Uri u = clip.getItemAt(k).getUri();
                    if (u != null) uris.add(u);
                }
            }
            if (uris.isEmpty() && data.getData() != null) uris.add(data.getData());
        } catch (Throwable ignored) {
        }
        if (uris.isEmpty()) return;
        worker.execute(() -> {
            int ok = 0, fail = 0;
            JSONArray metas = new JSONArray();
            StringBuilder errors = new StringBuilder();
            for (Uri u : uris) {
                JSONObject r = Books.importUri(this, u);
                if (r.optBoolean("ok")) {
                    ok++;
                    JSONObject m = r.optJSONObject("meta");
                    if (m != null) metas.put(m);
                } else {
                    fail++;
                    if (errors.length() < 80) errors.append(r.optString("error", "失败")).append(' ');
                }
            }
            JSONObject out = new JSONObject();
            try {
                out.put("ok", true);
                out.put("imported", ok);
                out.put("failed", fail);
                out.put("books", metas);
                out.put("message", fail == 0 ? "已导入 " + ok + " 本" : "导入 " + ok + " 本，失败 " + fail + " 本 " + errors);
                out.put("all", Books.list(this));
            } catch (Throwable ignored) {
            }
            event("books", out);
            event("toast", msgJson(out.optString("message", "导入完成")));
        });
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] grant) {
        super.onRequestPermissionsResult(req, perms, grant);
        event("perms", Perm.status(this));
    }

    private final class Bridge {
        @JavascriptInterface
        public void call(String id, String method, String args) {
            JSONObject a;
            try {
                a = args == null || args.trim().isEmpty() ? new JSONObject() : new JSONObject(args);
            } catch (Throwable t) {
                a = new JSONObject();
            }
            final JSONObject argv = a;
            if (isUiMethod(method)) {
                runOnUiThread(() -> result(id, dispatch(method, argv)));
                return;
            }
            if (isSlowMethod(method)) {
                slowWorker.execute(() -> result(id, dispatch(method, argv)));
                return;
            }
            if (isAstrBotMethod(method)) {
                astrbotWorker.execute(() -> result(id, dispatch(method, argv)));
                return;
            }
            worker.execute(() -> result(id, dispatch(method, argv)));
        }

        @JavascriptInterface
        public String version() {
            return Ver.NAME;
        }
    }

    private static boolean isUiMethod(String m) {
        return "adImportFile".equals(m) || "export".equals(m)
                || "importZip".equals(m) || "openPerm".equals(m) || "requestPerm".equals(m)
                || "startPet".equals(m) || "stopPet".equals(m) || "setLock".equals(m)
                || "pluginImport".equals(m) || "backupExport".equals(m)
                || "backupPreview".equals(m) || "backupApply".equals(m) || "asrStart".equals(m)
                || "asrStop".equals(m) || "clipCapture".equals(m)
                || "openUrl".equals(m) || "bookImport".equals(m) || "bookCoverPick".equals(m)
                || "bookExport".equals(m) || "bookExportAll".equals(m) || "bookRestore".equals(m)
                || "bookNotesExport".equals(m) || "readingMode".equals(m) || "familySeal".equals(m)
                || "stickerImport".equals(m) || "skinImport".equals(m)
                || "skinImportMulti".equals(m) || "skinImportZip".equals(m)
                || "skinImportAnim".equals(m) || "skinImportLive2d".equals(m) || "skinExportZip".equals(m)
                || "astrbotMobilePluginExport".equals(m)
                  || "qqPersonaImport".equals(m)
                || "comfyUiWorkflowImport".equals(m)
                || "profileAvatarPick".equals(m)
                || "chatPickImage".equals(m) || "chatTakePhoto".equals(m) || "chatPickFile".equals(m)
                || "chatPickVideo".equals(m)
                || "longDocExport".equals(m) || "cancelChat".equals(m);
    }

    private static boolean isAstrBotMethod(String m) {
        return "astrbotConfig".equals(m) || "astrbotApiConfigure".equals(m)
                || "astrbotAgentEnable".equals(m) || "astrbotApiPlugins".equals(m)
                || "astrbotMobileToolsEnable".equals(m) || "astrbotMobileBridgeStatus".equals(m)
                || "astrbotApiMarket".equals(m) || "astrbotApiInstallGithub".equals(m)
                || "astrbotApiVerifyInstall".equals(m) || "astrbotPluginEnable".equals(m);
    }

    private static boolean isSlowMethod(String m) {
        return "chat".equals(m) || "proactiveNow".equals(m) || "bookLookup".equals(m)
                || "balance".equals(m) || "balanceVision".equals(m) || "clipAct".equals(m)
                || "recapSay".equals(m) || "musicxTr".equals(m) || "syncTest".equals(m)
                || "syncUpload".equals(m) || "syncDownload".equals(m) || "dshboxAnalyze".equals(m)
                || "calendarSync".equals(m) || "adTestRun".equals(m)
                || "bookImportUrl".equals(m) || "bookScan".equals(m);
    }

    @Override
    protected void onDestroy() {
        Live2DRenderer.setMemoryGraphVisible(false);
        try {
            web.removeJavascriptInterface("Dsh");
            web.destroy();
        } catch (Throwable ignored) {
        }
        web = null;
        worker.shutdownNow();
        slowWorker.shutdownNow();
        astrbotWorker.shutdownNow();
        super.onDestroy();
    }
}
