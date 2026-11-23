package com.dshpet.android;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.File;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** On-demand HTTP bridge to a user-managed remote AstrBot instance. No resident socket or runtime. */
public final class AstrBotBridge {
    private static final String PREFS = "astrbot_remote";
    /**
     * 远端会话版本：v2 起关闭推理展示。旧版本里 AstrBot 把“🤔 思考…”整块存进了会话历史，
     * 模型会照着旧回复继续模仿思考格式，所以升级后换一次会话，让历史从干净状态重建。
     */
    private static final int REMOTE_CONTEXT_VERSION = 2;
    private static final int MAX_RESPONSE = 4 * 1024 * 1024;
    private static final int MAX_ATTACHMENT_BYTES = 8 * 1024 * 1024;
    private static final int MARKET_MAX_RESPONSE = 12 * 1024 * 1024;
    private static final int MARKET_PAGE_SIZE = 40;
    private static final long MARKET_CACHE_TTL = 5 * 60 * 1000L;
    private static final Semaphore CHAT_SLOTS = new Semaphore(1);
    private static final Semaphore GROUP_CHAT_SLOTS = new Semaphore(2);
    private static final Semaphore PLUGIN_SLOTS = new Semaphore(2);
    private static final Object IDENTITY_LOCK = new Object();
    private static final Object MARKET_LOCK = new Object();
    private static final ConcurrentHashMap<String, MobilePollSession> MOBILE_SESSIONS = new ConcurrentHashMap<>();
    private static final String MOBILE_PLUGIN = "astrbot_plugin_dsh_mobile";
    private static volatile JSONArray MARKET_CACHE;
    private static volatile long MARKET_CACHE_AT;
    private static volatile String MARKET_CACHE_BASE = "";

    private AstrBotBridge() { }

    public static JSONObject config(Context context) {
        JSONObject out = new JSONObject();
        try {
            cleanupLegacyLocalRuntime(context);
            android.content.SharedPreferences prefs = prefs(context);
            String base = prefs.getString("base", "");
            boolean configured = !base.isEmpty() && !prefs.getString("key", "").isEmpty();
            out.put("ok", true).put("configured", configured).put("baseUrl", base)
                    .put("agentEnabled", prefs.getBoolean("agentEnabled", false))
                    .put("mobileToolsEnabled", prefs.getBoolean("mobileToolsEnabled", false))
                    .put("remoteFatal", LAST_REMOTE_FATAL);
        } catch (Throwable t) {
            try { out.put("ok", false).put("error", "读取 AstrBot 设置失败"); } catch (Throwable ignored) { }
        }
        return out;
    }

    public static JSONObject configureApi(Context context, String baseUrl, String apiKey) {
        JSONObject out = new JSONObject();
        try {
            cleanupLegacyLocalRuntime(context);
            String base = normalizeBaseUrl(baseUrl);
            android.content.SharedPreferences prefs = prefs(context);
            String oldBase = prefs.getString("base", "");
            String key = clean(apiKey);
            if (key.isEmpty()) {
                if (!base.equals(oldBase)) throw new IllegalArgumentException("更换 AstrBot 服务地址时，请填写新服务对应的 OpenAPI Key");
                key = prefs.getString("key", "");
            }
            if (key.length() < 8 || key.length() > 512) throw new IllegalArgumentException("AstrBot OpenAPI Key 长度无效");
            android.content.SharedPreferences.Editor editor = prefs.edit().putString("base", base).putString("key", key);
            invalidateMarketCache();
            if (!base.equals(oldBase)) {
                editor.remove("sessionMemoryHash").remove("sessionMemorySynced")
                        .putString("session", UUID.randomUUID().toString()).putBoolean("agentEnabled", false)
                        .putBoolean("mobileToolsEnabled", false)
                        .putInt("remoteContextVersion", REMOTE_CONTEXT_VERSION);
                stopMobileSessions();
            }
            editor.apply();
            out.put("ok", true).put("message", "远端 AstrBot 地址已保存；请点“检查连接”确认权限和网络。");
        } catch (Throwable t) {
            try { out.put("ok", false).put("error", t.getMessage() == null ? "AstrBot 配置无效" : t.getMessage()); }
            catch (Throwable ignored) { }
        }
        return out;
    }

    public static JSONObject setAgentEnabled(Context context, boolean enabled) {
        JSONObject out = new JSONObject();
        try {
            if (enabled && !isConfigured(context)) throw new IllegalStateException("请先连接远端 AstrBot 并保存 OpenAPI Key");
            SharedPreferences.Editor editor = prefs(context).edit().putBoolean("agentEnabled", enabled);
            if (!enabled) editor.putBoolean("mobileToolsEnabled", false);
            editor.apply();
            if (!enabled) stopMobileSessions();
            out.put("ok", true).put("agentEnabled", enabled);
        } catch (Throwable t) {
            try { out.put("ok", false).put("error", t.getMessage() == null ? "无法切换 AstrBot Agent" : t.getMessage()); }
            catch (Throwable ignored) { }
        }
        return out;
    }

    public static boolean agentEnabled(Context context) {
        return isConfigured(context) && prefs(context).getBoolean("agentEnabled", false);
    }

    /** 只要 AstrBot 地址与 Key 已保存，/ 开头的插件指令就可以直发，不要求用户额外开启远端智能体。 */
    public static boolean pluginCommandsAvailable(Context context) {
        return isConfigured(context);
    }

    public static JSONObject mobileBridgeStatus(Context context) {
        return apiRequest(context, "/api/v1/plugins/extensions/" + MOBILE_PLUGIN + "/status", null);
    }

    /** 最近一次“远端没配对话模型”类致命错误；聊天成功后清空。供插件页如实警告，避免桥接全绿但聊天必败的假象。 */
    private static volatile String LAST_REMOTE_FATAL = "";

    public static String lastRemoteFatal() {
        return LAST_REMOTE_FATAL;
    }

    /**
     * AstrBot 默认唤醒前缀是 “/”。指令类插件只有在消息以该前缀开头时才会被命令过滤器命中，
     * 所以这类消息不能再附加桌宠的时间、记忆或工具提示，否则装好的插件也不会响应。
     */
    public static boolean isPluginCommand(String text) {
        String t = clean(text);
        return !t.isEmpty() && t.startsWith("/");
    }

    /**
     * AstrBot 4.29.3 的 result_decorate 在开启推理展示时，会把整段推理作为普通文本插到回复最前面：
     * "🤔 思考: {推理内容}\n\n────\n"。它是一条 Plain 组件，chain_type 也是 plain，
     * 所以按 chain_type 过滤抓不住，必须按这个固定格式整块删掉。
     */
    private static final java.util.regex.Pattern INJECTED_REASONING = java.util.regex.Pattern.compile(
            "(?ims)^[ \\t]*(?:🤔|💭)?[ \\t]*(?:思考|thinking|think)[ \\t]*[:：].*?\\n[ \\t]*\\n[ \\t]*[─—\\-–_=]{3,}[ \\t]*(?:\\r?\\n|$)");

    /** 远端转发来的模型推理/思考行不是桌宠的话，也不能发去 QQ；逐行剥离。 */
    static boolean isReasoningLine(String line) {
        if (line == null) return false;
        String t = line.trim();
        if (t.isEmpty()) return false;
        String lower = t.toLowerCase(Locale.ROOT);
        return t.startsWith("🤔 思考") || t.startsWith("🤔思考") || t.startsWith("思考:") || t.startsWith("思考：")
                || lower.startsWith("thinking:") || lower.startsWith("think:");
    }

    static String stripReasoning(String text) {
        if (text == null || text.isEmpty()) return text;
        String value = stripInjectedReasoning(text);
        StringBuilder out = new StringBuilder();
        for (String line : value.split("\r?\n", -1)) {
            // 先去掉行首的时间标记再判断：远端可能把标记和“思考”写在同一行，
            // 否则判不出思考行，去标记后仍会漏出“🤔 思考：…”残句。
            String v = Brain.stripHistoryStamp(line);
            if (isReasoningLine(v)) continue;
            if (out.length() > 0) out.append('\n');
            out.append(v);
        }
        return out.toString().trim();
    }

    /**
     * 整块删除 AstrBot 注入的推理段落。只删除“标记行 + 空行 + 分隔线”这样完整的块，
     * 找不到分隔线时只丢掉标记行，避免把真正的回复当成思考删掉。
     */
    static String stripInjectedReasoning(String text) {
        if (text == null || text.isEmpty()) return text;
        String value = text.replace("\r\n", "\n").replace('\r', '\n');
        String previous;
        do {
            previous = value;
            value = INJECTED_REASONING.matcher(value).replaceAll("");
        } while (!value.equals(previous));
        return value.trim();
    }

    /** 识别远端 AstrBot 把自身致命配置错误（未配对话模型等）当回复正文返回的情况；这类文字不能当桌宠的话。 */
    static String remoteConfigFatal(String answer) {
        if (answer == null) return null;
        String s = answer.replace("\r", "").trim();
        if (s.isEmpty()) return null;
        if (s.contains("未找到任何可用的对话模型") || s.contains("请先在 WebUI 中配置并启用可用模型")) return s;
        return null;
    }

    public static JSONObject setMobileToolsEnabled(Context context, boolean enabled) {
        JSONObject out = new JSONObject();
        try {
            if (enabled && !agentEnabled(context)) throw new IllegalStateException("请先启用远端 AstrBot 智能体");
            if (enabled) {
                JSONObject status = mobileBridgeStatus(context);
                if (!status.optBoolean("ok") || !status.optBoolean("available"))
                    throw new IllegalStateException(status.optString("error", "AstrBot 未安装 DSH 手机桥接插件"));
            }
            prefs(context).edit().putBoolean("mobileToolsEnabled", enabled).apply();
            if (!enabled) stopMobileSessions();
            out.put("ok", true).put("mobileToolsEnabled", enabled);
        } catch (Throwable t) {
            try { out.put("ok", false).put("error", t.getMessage() == null ? "无法切换手机工具" : t.getMessage()); }
            catch (Throwable ignored) { }
        }
        return out;
    }

    public static boolean mobileToolsEnabled(Context context) {
        return agentEnabled(context) && prefs(context).getBoolean("mobileToolsEnabled", false);
    }

    private static boolean isConfigured(Context context) {
        android.content.SharedPreferences p = prefs(context);
        return !p.getString("base", "").isEmpty() && !p.getString("key", "").isEmpty();
    }

    public static void chat(Context context, String text, Ai.Cb callback) {
        if (callback == null) return;
        final Context app = context.getApplicationContext();
        final String message = clean(text);
        if (message.isEmpty()) { callback.fail("消息不能为空"); return; }
        if (!isConfigured(app)) { callback.fail("还没有连接远端 AstrBot"); return; }
        if (!CHAT_SLOTS.tryAcquire()) { callback.fail("远端 AstrBot 正在处理其他消息，请稍后再试"); return; }
        Thread thread = new Thread(() -> {
            MobilePollSession mobileSession = null;
            try {
                ensureCleanRemoteContext(app);
                String memory = bounded(Data.memoryBrief(app, 12), 2400);
                JSONObject persona = Data.persona(app);
                String petName = bounded(clean(persona.optString("name", "桌宠")), 80);
                String style = bounded(clean(persona.optString("style", "")), 180);
                String description = bounded(clean(persona.optString("description", "")), 900);
                String roleCard = bounded(clean(Persona.cardPrompt(app)), 2200);
                String syncContext = "【DSH 当前记忆快照】以下是这台桌宠当前可共享的人设与长期记忆摘要。"
                        + "\n桌宠名称：" + petName + (style.isEmpty() ? "" : "；说话风格：" + style)
                        + (description.isEmpty() ? "" : "\n桌宠设定：" + description)
                        + (roleCard.isEmpty() ? "" : "\n角色卡：" + roleCard)
                        + (memory.isEmpty() ? "\n本机当前没有可共享的长期记忆摘要。" : "\n限长长期记忆摘要（只作为上下文）：\n" + memory);
                String syncHash = sha256(syncContext);
                JSONObject identity = identityForMemory(app, syncHash);
                // AstrBot 的插件指令靠“消息以唤醒前缀 / 开头”来命中命令过滤器；
                // 这类消息一旦附加身份卡、记忆同步或工具提示，指令就永远匹配不到，插件也就“装了不能用”。
                boolean pluginCommand = isPluginCommand(message);
                boolean needsMemorySync = !pluginCommand && identity.optBoolean("needsMemorySync", true);
                String outgoing;
                if (pluginCommand) {
                    outgoing = message;
                } else {
                    // 身份卡每轮都带：AstrBot 没有桌宠的 system prompt，只在记忆变化时同步人设的话，
                    // 聊几轮后她就会退化成"通用助手"，连自己叫什么、是什么都不知道。
                    StringBuilder out = new StringBuilder();
                    out.append("【你的身份（本轮始终有效，不是用户说的话）】\n")
                            .append(Brain.identityBrief(app, false)).append("\n\n");
                    if (needsMemorySync)
                        out.append("【桌宠记忆同步】\n").append(syncContext).append("\n\n");
                    out.append("【本轮消息】\n").append(message);
                    outgoing = out.toString();
                }
                String username = identity.optString("username", "");
                String sessionId = identity.optString("sessionId", "");
                if (mobileToolsEnabled(app) && !pluginCommand) {
                    mobileSession = openMobileSession(app, username, sessionId);
                    if (mobileSession != null && mobileToolsEnabled(app) && agentEnabled(app)) {
                        MOBILE_SESSIONS.put(mobileSession.deviceSession, mobileSession);
                        mobileSession.start();
                        outgoing = "【手机工具可用】本轮你拥有 dsh_mobile_action 工具：read_screen 读屏幕文字、open_app 打开应用、"
                                + "tap_text 点击可见文字、swipe 滑动、back 返回。用户要求操作手机时必须调用该工具完成，"
                                + "禁止口头说自己不能操作手机。\n" + outgoing;
                    } else if (mobileSession != null) {
                        mobileSession.stop(true);
                        mobileSession = null;
                    } else {
                        outgoing = "【手机端工具通道目前未就绪；本轮不能读取屏幕或执行手机操作。】\n" + outgoing;
                    }
                }
                JSONObject result = apiChat(app, username, sessionId, outgoing);
                if (!result.optBoolean("ok")) {
                    callback.fail(result.optString("error", "AstrBot Agent 请求失败"));
                    return;
                }
                String answer = stripReasoning(extractAnswer(result));
                String fatal = remoteConfigFatal(answer);
                if (fatal != null) {
                    LAST_REMOTE_FATAL = fatal;
                    callback.fail(fatal);
                    return;
                }
                if (answer.trim().isEmpty()) callback.fail("AstrBot 已返回响应，但没有可显示的文字内容");
                else {
                    LAST_REMOTE_FATAL = "";
                    if (needsMemorySync) markMemorySynced(app, identity.optString("sessionId", ""));
                    callback.ok(answer.trim());
                }
            } catch (Throwable t) {
                callback.fail(t.getMessage() == null ? "AstrBot Agent 请求失败" : t.getMessage());
            } finally {
                if (mobileSession != null) {
                    mobileSession.stop(true);
                    MOBILE_SESSIONS.remove(mobileSession.deviceSession, mobileSession);
                }
                CHAT_SLOTS.release();
            }
        }, "DshAstrBotChat");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Group-only Agent route. Each installation gets a private namespace and each QQ group
     * gets its own AstrBot username; this deliberately does not sync owner memory or open tools.
     */
    public static void chatGroup(Context context, String groupId, String sessionId, String text, Ai.Cb callback) {
        chatGroup(context, groupId, sessionId, text, null, callback);
    }

    /**
     * 带图群聊：先把图片上传成 AstrBot 附件，再用 message parts 一起发送。
     * 上传失败时直接报错，不退化成纯文本，避免模型凭空描述图片。
     */
    public static void chatGroup(Context context, String groupId, String sessionId, String text,
                                 byte[] image, Ai.Cb callback) {
        if (callback == null) return;
        final Context app = context.getApplicationContext();
        final String group = clean(groupId), session = clean(sessionId), message = clean(text);
        if (!agentEnabled(app)) { callback.fail("请先在插件页配置并启用远端 AstrBot 智能体，群聊插件模式才能工作。"); return; }
        if (group.isEmpty() || session.isEmpty() || message.isEmpty()) { callback.fail("群聊消息或独立会话信息不完整，请重新连接 QQ。"); return; }
        if (!GROUP_CHAT_SLOTS.tryAcquire()) { callback.fail("AstrBot 正在处理其他群聊消息，请稍后再试。"); return; }
        Thread thread = new Thread(() -> {
            try {
                ensureCleanRemoteContext(app);
                SharedPreferences p = prefs(app);
                String installation = p.getString("owner", "");
                if (installation.isEmpty()) {
                    installation = "dsh_" + UUID.randomUUID().toString().replace("-", "");
                    p.edit().putString("owner", installation).apply();
                }
                // 把会话版本写进群聊用户名：升级后每个群换一条干净的远端历史，
                // 避免旧历史里的思考文本继续被模型模仿。
                String username = "dshg_" + sha256(installation + "|qq-group|" + group
                        + "|ctx" + p.getInt("remoteContextVersion", 0)).substring(0, 40);
                String outgoing = message;
                if (!isPluginCommand(message)) {
                    outgoing = "【你的身份（本轮始终有效，不是用户说的话）】\n"
                            + Brain.identityBrief(app, true) + "\n\n【本轮群消息】\n" + message;
                }
                Object payload = outgoing;
                if (image != null && image.length > 0) {
                    String attachmentId;
                    try {
                        attachmentId = uploadAttachment(app, image, "qq-group.jpg");
                    } catch (Throwable uploadError) {
                        String detail = uploadError.getMessage() == null ? "未知原因" : uploadError.getMessage();
                        if (detail.length() > 180) detail = detail.substring(0, 180);
                        callback.fail("群里的图片没能上传到 AstrBot（" + detail
                                + "），这次没有让模型看图；请重发图片或检查 OpenAPI Key 的 file 权限。");
                        return;
                    }
                    JSONArray parts = new JSONArray();
                    parts.put(new JSONObject().put("type", "plain").put("text", outgoing));
                    parts.put(new JSONObject().put("type", "image").put("attachment_id", attachmentId));
                    payload = parts;
                }
                JSONObject result = apiChat(app, username, session, payload);
                if (!result.optBoolean("ok")) {
                    callback.fail(AgentErrorHandler.astrBot(app, result.optString("error", "AstrBot 群聊请求失败")));
                    return;
                }
                String answer = stripReasoning(extractAnswer(result));
                String fatal = remoteConfigFatal(answer);
                if (fatal != null) {
                    LAST_REMOTE_FATAL = fatal;
                    callback.fail(fatal);
                    return;
                }
                if (answer.trim().isEmpty()) callback.fail("AstrBot 已收到群消息，但没有返回可显示的文字。请检查它的模型或聊天插件后再试。");
                else {
                    LAST_REMOTE_FATAL = "";
                    callback.ok(answer.trim());
                }
            } catch (Throwable t) {
                callback.fail(AgentErrorHandler.astrBot(app, t.getMessage()));
            } finally { GROUP_CHAT_SLOTS.release(); }
        }, "DshAstrBotGroupChat");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * QQ 私聊插件指令：只转发 / 开头的命令，使用独立会话，不注入身份卡、不挂手机工具，
     * 因此不会把私聊普通聊天或手机操作能力带到 QQ。
     */
    public static void chatPluginCommand(Context context, String channel, String text, Ai.Cb callback) {
        if (callback == null) return;
        final Context app = context.getApplicationContext();
        final String route = clean(channel), message = clean(text);
        if (!isConfigured(app)) { callback.fail("还没有连接远端 AstrBot，请先到插件页配置地址和 Key。"); return; }
        if (route.isEmpty() || message.isEmpty() || !isPluginCommand(message)) { callback.fail("这条消息不是可执行的插件指令。"); return; }
        if (!PLUGIN_SLOTS.tryAcquire()) { callback.fail("AstrBot 正在处理其他插件指令，请稍后再试。"); return; }
        Thread thread = new Thread(() -> {
            try {
                ensureCleanRemoteContext(app);
                SharedPreferences p = prefs(app);
                String installation = p.getString("owner", "");
                if (installation.isEmpty()) {
                    installation = "dsh_" + UUID.randomUUID().toString().replace("-", "");
                    p.edit().putString("owner", installation).apply();
                }
                String ctx = "|ctx" + p.getInt("remoteContextVersion", 0);
                String username = "dshc_" + sha256(installation + "|" + route + ctx).substring(0, 40);
                String session = "dsh-cmd-" + sha256(installation + "|" + route + ctx).substring(0, 24);
                JSONObject result = apiChat(app, username, session, message);
                if (!result.optBoolean("ok")) {
                    callback.fail(AgentErrorHandler.astrBot(app, result.optString("error", "AstrBot 插件指令请求失败")));
                    return;
                }
                String answer = stripReasoning(extractAnswer(result));
                String fatal = remoteConfigFatal(answer);
                if (fatal != null) {
                    LAST_REMOTE_FATAL = fatal;
                    callback.fail(fatal);
                    return;
                }
                if (answer.trim().isEmpty()) callback.fail("AstrBot 已收到指令，但没有返回可显示的文字。请确认插件已启用。");
                else {
                    LAST_REMOTE_FATAL = "";
                    callback.ok(answer.trim());
                }
            } catch (Throwable t) {
                callback.fail(AgentErrorHandler.astrBot(app, t.getMessage()));
            } finally { PLUGIN_SLOTS.release(); }
        }, "DshAstrBotPlugin");
        thread.setDaemon(true);
        thread.start();
    }

    private static MobilePollSession openMobileSession(Context context, String username, String sessionId) {
        JSONObject status = mobileBridgeStatus(context);
        if (!status.optBoolean("ok") || !status.optBoolean("available")) return null;
        String deviceSession = UUID.randomUUID().toString();
        try {
            JSONObject body = new JSONObject().put("username", username).put("session_id", sessionId)
                    .put("device_session", deviceSession).put("protocol", 1);
            // mobilePath() 已经带了 /session/ 前缀，这里只能传动作名，
            // 否则会拼成 .../session/session/open（插件没有该路由，会话永远建不起来）。
            JSONObject opened = apiRequest(context, mobilePath("open"), body);
            if (!opened.optBoolean("ok") || !opened.optBoolean("ready")) return null;
            return new MobilePollSession(context.getApplicationContext(), username, sessionId, deviceSession);
        } catch (Throwable ignored) { return null; }
    }

    private static String mobilePath(String method) {
        return "/api/v1/plugins/extensions/" + MOBILE_PLUGIN + "/session/" + method;
    }

    private static void stopMobileSessions() {
        for (MobilePollSession session : MOBILE_SESSIONS.values()) {
            if (session != null) session.stop(true);
        }
        MOBILE_SESSIONS.clear();
    }

    private static final class MobilePollSession {
        final Context context;
        final String username;
        final String sessionId;
        final String deviceSession;
        final AtomicBoolean running = new AtomicBoolean(true);
        final AtomicReference<HttpURLConnection> activeConnection = new AtomicReference<>();
        volatile Thread thread;

        MobilePollSession(Context context, String username, String sessionId, String deviceSession) {
            this.context = context;
            this.username = username;
            this.sessionId = sessionId;
            this.deviceSession = deviceSession;
        }

        void start() {
            thread = new Thread(this::poll, "DshAstrBotMobileTools");
            thread.setDaemon(true);
            thread.start();
        }

        void poll() {
            while (running.get() && mobileToolsEnabled(context) && agentEnabled(context)) {
                JSONObject request;
                try {
                    request = new JSONObject().put("username", username).put("session_id", sessionId)
                            .put("device_session", deviceSession).put("wait_seconds", 20);
                } catch (Throwable ignored) { break; }
                JSONObject response = apiRequest(context, mobilePath("next"), request, activeConnection);
                if (!running.get()) break;
                if (!response.optBoolean("ok")) break;
                JSONObject command = response.optJSONObject("command");
                if (!response.optBoolean("available") || command == null) continue;
                deliver(command);
            }
        }

        void deliver(JSONObject command) {
            JSONObject result;
            try {
                JSONObject confirmation = MobileActionExecutor.confirmation(context, command);
                String action = command.optString("action", "");
                if ("read_screen".equals(action)) {
                    String expectedPackage = confirmation.optString("targetPackage", "");
                    if (expectedPackage.isEmpty()) {
                        result = new JSONObject().put("ok", false)
                                .put("message", "无法确认当前前台应用；没有读取屏幕");
                        postMobileResult(command, result);
                        return;
                    }
                    JSONObject args = command.optJSONObject("args");
                    if (args == null) {
                        args = new JSONObject();
                        command.put("args", args);
                    }
                    args.put("target_package", expectedPackage);
                }
                boolean approved = PetSvc.requestRemoteActionApproval(confirmation, 25000L);
                if (approved) result = MobileActionExecutor.execute(context, command);
                else result = new JSONObject().put("ok", false)
                        .put("message", "用户未在桌宠前台确认；手机未执行该操作");
                postMobileResult(command, result);
            } catch (Throwable ignored) {
                try {
                    postMobileResult(command, new JSONObject().put("ok", false).put("message", "手机端确认或执行失败"));
                } catch (Throwable ignoredAgain) { }
            }
        }

        void postMobileResult(JSONObject command, JSONObject result) throws Exception {
            JSONObject body = new JSONObject().put("username", username).put("device_session", deviceSession)
                    .put("request_id", command.optString("request_id", "")).put("result", result);
            apiRequest(context, mobilePath("result"), body);
        }

        void stop(boolean notifyServer) {
            if (!running.getAndSet(false)) return;
            HttpURLConnection connection = activeConnection.getAndSet(null);
            if (connection != null) connection.disconnect();
            Thread worker = thread;
            if (worker != null) worker.interrupt();
            if (notifyServer) {
                try {
                    apiRequest(context, mobilePath("close"), new JSONObject()
                            .put("username", username).put("session_id", sessionId)
                            .put("device_session", deviceSession));
                } catch (Throwable ignored) { }
            }
        }
    }

    /**
     * 一次性清理被推理文本污染的远端会话：只换会话 ID，不删除 AstrBot 的插件或配置。
     * 每轮的本地记忆快照仍会重新发过去，所以桌宠的人设和记忆不会因此丢失。
     */
    private static void ensureCleanRemoteContext(Context context) {
        SharedPreferences p = prefs(context);
        if (p.getInt("remoteContextVersion", 0) >= REMOTE_CONTEXT_VERSION) return;
        p.edit().putString("session", UUID.randomUUID().toString())
                .remove("sessionMemoryHash").remove("sessionMemorySynced")
                .putInt("remoteContextVersion", REMOTE_CONTEXT_VERSION).apply();
    }

    /** Keep one stable WebChat session; send updated memory/persona snapshots into that session. */
    private static JSONObject identityForMemory(Context context, String memoryHash) {        synchronized (IDENTITY_LOCK) {
            SharedPreferences p = prefs(context);
            String owner = p.getString("owner", "");
            String session = p.getString("session", "");
            if (owner.isEmpty()) owner = "dsh_" + UUID.randomUUID().toString().replace("-", "");
            if (session.isEmpty()) session = UUID.randomUUID().toString();
            String sessionHash = p.getString("sessionMemoryHash", "");
            boolean synced = p.getBoolean("sessionMemorySynced", false);
            if (!memoryHash.equals(sessionHash)) {
                sessionHash = memoryHash;
                synced = false;
            }
            p.edit().putString("owner", owner).putString("session", session)
                    .putString("sessionMemoryHash", sessionHash).putBoolean("sessionMemorySynced", synced)
                    .remove("syncedMemoryHash").apply();
            try {
                return new JSONObject().put("username", owner).put("sessionId", session)
                        .put("needsMemorySync", !synced);
            } catch (Throwable ignored) { return new JSONObject(); }
        }
    }

    private static void markMemorySynced(Context context, String completedSession) {
        synchronized (IDENTITY_LOCK) {
            SharedPreferences p = prefs(context);
            if (completedSession.equals(p.getString("session", "")))
                p.edit().putBoolean("sessionMemorySynced", true).apply();
        }
    }

    public static JSONObject apiPlugins(Context context) {
        return apiRequest(context, "/api/v1/plugins", null);
    }

    /**
     * 启用/停用 AstrBot 插件。4.29.3 用 PATCH /api/v1/plugins/enabled，按插件模块名（不是 author/name）定位，
     * 启用会同时重新加载插件；这是“装完插件到底能不能用”的最后一步。
     */
    public static JSONObject setPluginEnabled(Context context, String pluginName, boolean enabled) {
        try {
            String name = clean(pluginName);
            if (name.isEmpty()) throw new IllegalArgumentException("插件名称不能为空");
            JSONObject body = new JSONObject().put("plugin_id", name).put("enabled", enabled);
            JSONObject result = apiRequest(context, "/api/v1/plugins/enabled", body, "PATCH", null);
            if (!result.optBoolean("ok"))
                throw new IllegalStateException(result.optString("error", enabled ? "启用插件失败" : "停用插件失败"));
            result.put("pluginName", name).put("enabled", enabled);
            return result;
        } catch (Throwable t) {
            return error(t.getMessage() == null ? "AstrBot 未能切换插件状态" : t.getMessage());
        }
    }

    /**
     * 读取 AstrBot 插件市场。4.29.3 的市场接口只返回一份以 “author/name” 为键的全量字典，
     * 且忽略 page/keyword 参数，因此搜索与分页都在本机完成，并缓存全量结果避免反复下载数 MB 数据。
     */
    public static JSONObject apiMarket(Context context, int page, String keyword) {
        try {
            JSONArray registry = marketRegistry(context);
            String term = clean(keyword).toLowerCase(Locale.ROOT);
            JSONArray filtered = new JSONArray();
            for (int i = 0; i < registry.length(); i++) {
                JSONObject entry = registry.optJSONObject(i);
                if (entry == null) continue;
                if (term.isEmpty() || marketEntryMatches(entry, term)) filtered.put(entry);
            }
            int total = filtered.length();
            int pages = Math.max(1, (total + MARKET_PAGE_SIZE - 1) / MARKET_PAGE_SIZE);
            int safePage = Math.max(1, Math.min(pages, page));
            int start = (safePage - 1) * MARKET_PAGE_SIZE;
            JSONArray rows = new JSONArray();
            for (int i = start; i < total && i < start + MARKET_PAGE_SIZE; i++) rows.put(filtered.opt(i));
            JSONObject out = new JSONObject().put("ok", true).put("plugins", rows).put("items", rows)
                    .put("total", total).put("page", safePage).put("page_size", MARKET_PAGE_SIZE)
                    .put("keyword", clean(keyword));
            JSONObject sources = pluginSources(context);
            if (sources != null) {
                out.put("sources", sources.optJSONArray("sources"));
                out.put("default_registry", sources.optBoolean("default_registry"));
            }
            return out;
        } catch (Throwable t) {
            return error(t.getMessage() == null ? "AstrBot 市场读取失败" : t.getMessage());
        }
    }

    private static JSONArray marketRegistry(Context context) throws Exception {
        String base = prefs(context).getString("base", "");
        synchronized (MARKET_LOCK) {
            if (MARKET_CACHE != null && base.equals(MARKET_CACHE_BASE)
                    && System.currentTimeMillis() - MARKET_CACHE_AT < MARKET_CACHE_TTL) return MARKET_CACHE;
        }
        JSONObject response = apiRequest(context, "/api/v1/plugins/market", null);
        if (!response.optBoolean("ok"))
            throw new IllegalStateException(response.optString("error", "AstrBot 市场读取失败"));
        Object raw = response.opt("data");
        if (raw == null) raw = response.opt("plugins");
        JSONArray all = normalizeMarketData(raw);
        synchronized (MARKET_LOCK) {
            MARKET_CACHE = all;
            MARKET_CACHE_AT = System.currentTimeMillis();
            MARKET_CACHE_BASE = base;
        }
        return all;
    }

    /** 把市场 data 归一化为数组：支持字典（带 $meta 键）与旧版数组两种形态。 */
    private static JSONArray normalizeMarketData(Object raw) throws Exception {
        JSONArray out = new JSONArray();
        if (raw instanceof JSONArray) {
            JSONArray array = (JSONArray) raw;
            for (int i = 0; i < array.length(); i++) {
                JSONObject entry = array.optJSONObject(i);
                if (entry != null) out.put(normalizeMarketEntry(entry, ""));
            }
            return out;
        }
        if (!(raw instanceof JSONObject)) return out;
        JSONObject data = (JSONObject) raw;
        java.util.Iterator<String> keys = data.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if ("$meta".equals(key)) continue;
            JSONObject entry = data.optJSONObject(key);
            if (entry == null) continue;
            out.put(normalizeMarketEntry(entry, key));
        }
        return out;
    }

    private static JSONObject normalizeMarketEntry(JSONObject entry, String key) throws Exception {
        JSONObject copy = new JSONObject(entry.toString());
        String id = clean(copy.optString("market_plugin_id", ""));
        if (!validPluginId(id) && key.contains("/")) id = key;
        String name = clean(copy.optString("name", ""));
        if (name.isEmpty() && !key.contains("/")) name = key;
        if (!name.isEmpty()) copy.put("name", name);
        if (validPluginId(id)) copy.put("market_plugin_id", id);
        return copy;
    }

    private static boolean marketEntryMatches(JSONObject entry, String term) {
        String[] fields = {"name", "display_name", "desc", "author", "market_plugin_id", "repo", "category"};
        for (String field : fields) {
            if (clean(entry.optString(field, "")).toLowerCase(Locale.ROOT).contains(term)) return true;
        }
        return false;
    }

    /** 读取自定义插件来源，仅用于展示；默认官方市场没有自定义来源。 */
    private static JSONObject pluginSources(Context context) {
        try {
            JSONObject result = apiRequest(context, "/api/v1/plugin-sources", null);
            if (!result.optBoolean("ok")) return null;
            JSONArray sources = result.optJSONArray("sources");
            JSONObject data = result.optJSONObject("data");
            if (sources == null && data != null) sources = data.optJSONArray("sources");
            JSONObject out = new JSONObject();
            out.put("sources", sources == null ? new JSONArray() : sources);
            out.put("default_registry", sources == null || sources.length() == 0);
            return out;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void invalidateMarketCache() {
        synchronized (MARKET_LOCK) {
            MARKET_CACHE = null;
            MARKET_CACHE_AT = 0;
            MARKET_CACHE_BASE = "";
        }
    }

    private static boolean validPluginId(String value) {
        return value != null && value.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+");
    }

    public static JSONObject installGithubPlugin(Context context, String repository, String pluginId, String registryUrl, String downloadUrl) {
        String repo = clean(repository);
        if (!validInstallRepo(repo)) return error("只允许安装标准 GitHub 仓库地址");
        try {
            String safePluginId = clean(pluginId);
            if (!safePluginId.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))
                throw new IllegalArgumentException("市场记录缺少有效的 author/name 插件身份");
            // AstrBot 4.29.3 只有在 install_method=market 时才会按市场条目解析仓库与下载地址，
            // 并把安装来源持久化为 market；否则 market_plugin_id / registry_url 都会被忽略。
            JSONObject body = new JSONObject()
                    .put("repository", githubRepositoryRoot(repo))
                    .put("install_method", "market")
                    .put("market_plugin_id", safePluginId)
                    .put("ignore_version_check", false);
            String registry = clean(registryUrl);
            if (!registry.isEmpty()) {
                if (!registry.matches("https://[^\\s]+")) throw new IllegalArgumentException("插件市场来源必须为 HTTPS");
                body.put("registry_url", registry);
            }
            String download = clean(downloadUrl);
            if (!download.isEmpty()) {
                if (!download.matches("https://[^\\s]+")) throw new IllegalArgumentException("插件下载地址必须为 HTTPS");
                body.put("download_url", download);
            }
            JSONObject result = apiRequest(context, "/api/v1/plugins/install/github", body);
            if (!result.optBoolean("ok")) return result;
            if (result.has("success") && !result.optBoolean("success", true))
                return error(result.optString("message", result.optString("error", "AstrBot 未接受插件安装请求")));
            JSONObject installed = apiPlugins(context);
            JSONObject failed = apiRequest(context, "/api/v1/plugins/failed", null);
            String verify = verifyInstallState(installed, failed, repo, safePluginId);
            // 官方 WebUI 安装成功后会再调一次启用接口；不启用的话插件只是“装上了”，指令和工具都不会生效。
            String activation = "unknown", activationError = "";
            JSONObject record = matchingPluginRecord(installed, repo, safePluginId);
            if (record != null) {
                String moduleName = clean(record.optString("name", record.optString("root_dir_name", "")));
                boolean stateKnown = record.has("activated") || record.has("is_active") || record.has("enabled");
                if (!moduleName.isEmpty() && stateKnown && !isPluginActive(record)) {
                    JSONObject enable = setPluginEnabled(context, moduleName, true);
                    if (enable.optBoolean("ok")) {
                        activation = "enabled";
                        installed = apiPlugins(context);
                        failed = apiRequest(context, "/api/v1/plugins/failed", null);
                        verify = verifyInstallState(installed, failed, repo, safePluginId);
                    } else {
                        activation = "failed";
                        activationError = enable.optString("error", "启用插件失败");
                    }
                } else if (stateKnown) {
                    activation = "already";
                }
            }
            result.put("installedState", installed).put("failedState", failed)
                    .put("installAccepted", true).put("verification", verify)
                    .put("activation", activation).put("activationError", activationError)
                    .put("repository", repo).put("pluginId", safePluginId);
            if ("failed".equals(verify)) result.put("failedMessage", pluginFailureMessage(failed, repo, safePluginId));
            return result;
        } catch (Throwable t) { return error(t.getMessage() == null ? "插件安装失败" : t.getMessage()); }
    }

    public static JSONObject verifyPluginInstall(Context context, String repository, String pluginId) {
        String repo = clean(repository);
        if (!validInstallRepo(repo)) return error("插件仓库地址无效，无法重新核验");
        JSONObject installed = apiPlugins(context);
        JSONObject failed = apiRequest(context, "/api/v1/plugins/failed", null);
        JSONObject result = new JSONObject();
        try {
            String verify = verifyInstallState(installed, failed, repo, clean(pluginId));
            result.put("ok", true).put("verification", verify).put("installedState", installed).put("failedState", failed);
            if ("failed".equals(verify)) result.put("failedMessage", pluginFailureMessage(failed, repo, clean(pluginId)));
        } catch (Throwable t) {
            try { result.put("ok", false).put("error", t.getMessage() == null ? "无法核验 AstrBot 插件状态" : t.getMessage()); }
            catch (Throwable ignored) { }
        }
        return result;
    }

    private static JSONObject apiChat(Context context, String username, String sessionId, String text) {
        return apiChat(context, username, sessionId, (Object) text);
    }

    /** message 可以是纯文本，也可以是 AstrBot WebChat 支持的 parts 数组（文本 + 图片附件）。 */
    private static JSONObject apiChat(Context context, String username, String sessionId, Object message) {
        try {
            String user = clean(username), session = clean(sessionId);
            if (user.isEmpty() || session.isEmpty())
                throw new IllegalArgumentException("用户名、会话 ID 和消息不能为空");
            if (user.length() > 80 || session.length() > 120)
                throw new IllegalArgumentException("聊天字段超出长度限制");
            Object payload;
            if (message instanceof JSONArray) {
                JSONArray parts = (JSONArray) message;
                int chars = 0;
                for (int i = 0; i < parts.length(); i++) {
                    JSONObject part = parts.optJSONObject(i);
                    if (part != null && "plain".equals(part.optString("type", "")))
                        chars += part.optString("text", "").length();
                }
                if (parts.length() == 0 || chars > 16000)
                    throw new IllegalArgumentException("聊天字段超出长度限制");
                payload = parts;
            } else {
                String text = clean(message == null ? "" : String.valueOf(message));
                if (text.isEmpty()) throw new IllegalArgumentException("用户名、会话 ID 和消息不能为空");
                if (text.length() > 16000) throw new IllegalArgumentException("聊天字段超出长度限制");
                payload = text;
            }
            JSONObject body = new JSONObject().put("username", user).put("session_id", session)
                    .put("message", payload).put("enable_streaming", false)
                    // AstrBot 默认 enable_reasoning=true，会把整段推理拼进回复正文；桌宠不需要推理展示。
                    .put("flags", new JSONObject().put("enable_reasoning", false));
            return apiRequest(context, "/api/v1/chat", body);
        } catch (Throwable t) {
            return error(t.getMessage() == null ? "AstrBot 请求参数无效" : t.getMessage());
        }
    }

    /** 上传图片附件到 AstrBot（POST /api/v1/file），返回 attachment_id 供 message parts 引用。 */
    private static String uploadAttachment(Context context, byte[] image, String filename) throws Exception {
        if (image == null || image.length == 0) throw new IllegalStateException("图片数据为空");
        if (image.length > MAX_ATTACHMENT_BYTES) throw new IllegalStateException("图片超过上传上限");
        SharedPreferences p = prefs(context);
        String base = p.getString("base", ""), key = p.getString("key", "");
        if (base.isEmpty() || key.isEmpty())
            throw new IllegalStateException("请先配置远端 AstrBot 地址和 OpenAPI Key");
        if (!normalizeBaseUrl(base).equals(base)) throw new SecurityException("AstrBot 服务地址无效");
        String boundary = "----DshPet" + UUID.randomUUID().toString().replace("-", "");
        String safeName = filename == null || filename.trim().isEmpty() ? "qq.jpg" : filename.trim();
        byte[] head = ("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + safeName + "\"\r\n"
                + "Content-Type: image/jpeg\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        HttpURLConnection connection = (HttpURLConnection) new URL(base + "/api/v1/file").openConnection();
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(30000);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Authorization", "Bearer " + key);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "DSH-Pet/2.6.0");
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode((long) head.length + image.length + tail.length);
            try (OutputStream stream = connection.getOutputStream()) {
                stream.write(head);
                stream.write(image);
                stream.write(tail);
            }
            int code = connection.getResponseCode();
            InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String response = new String(readBounded(stream, MAX_RESPONSE), StandardCharsets.UTF_8).trim();
            if (code >= 300 && code < 400)
                throw new IllegalStateException("AstrBot 返回重定向；为防止 API Key 被转发，已停止上传");
            if (code < 200 || code >= 300) throw new IllegalStateException(httpError(code, response));
            JSONObject parsed = new JSONObject(response);
            String status = clean(parsed.optString("status", ""));
            if ("error".equalsIgnoreCase(status) || "warning".equalsIgnoreCase(status)) {
                String message = clean(parsed.optString("message", ""));
                if (message.isEmpty()) message = clean(parsed.optString("error", "AstrBot 图片上传失败"));
                throw new IllegalStateException(message);
            }
            JSONObject data = parsed.optJSONObject("data");
            String attachmentId = data == null ? ""
                    : clean(data.optString("attachment_id", data.optString("id", "")));
            if (attachmentId.isEmpty()) throw new IllegalStateException("AstrBot 没有返回图片附件 ID");
            return attachmentId;
        } finally {
            connection.disconnect();
        }
    }

    private static JSONObject apiRequest(Context context, String path, JSONObject body) {
        return apiRequest(context, path, body, null, null);
    }

    private static JSONObject apiRequest(Context context, String path, JSONObject body,
                                         AtomicReference<HttpURLConnection> activeConnection) {
        return apiRequest(context, path, body, null, activeConnection);
    }

    private static JSONObject apiRequest(Context context, String path, JSONObject body, String method,
                                         AtomicReference<HttpURLConnection> activeConnection) {
        HttpURLConnection connection = null;
        try {
            android.content.SharedPreferences p = prefs(context);
            String base = p.getString("base", ""), key = p.getString("key", "");
            if (base.isEmpty() || key.isEmpty()) throw new IllegalStateException("请先配置远端 AstrBot 地址和 OpenAPI Key");
            URL url = new URL(base + path);
            if (!normalizeBaseUrl(base).equals(base)) throw new SecurityException("AstrBot 服务地址无效");
            connection = (HttpURLConnection) url.openConnection();
            if (activeConnection != null) activeConnection.set(connection);
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(path.endsWith("/chat") ? 150000
                    : (path.endsWith("/session/next") ? 27000
                    : (path.contains("/plugins/install/") ? 300000
                    : (path.contains("/plugins/market") ? 60000 : 15000))));
            connection.setRequestProperty("Authorization", "Bearer " + key);
            connection.setRequestProperty("Accept", path.endsWith("/chat") ? "text/event-stream, application/json" : "application/json");
            connection.setRequestProperty("User-Agent", "DSH-Pet/2.6.0");
            if (body != null || (method != null && !"GET".equalsIgnoreCase(method))) {
                connection.setRequestMethod(method == null ? "POST" : method.toUpperCase(Locale.ROOT));
            }
            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                try (OutputStream stream = connection.getOutputStream()) {
                    stream.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = connection.getResponseCode();
            InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            byte[] bytes = readBounded(stream, path.contains("/plugins/market") ? MARKET_MAX_RESPONSE : MAX_RESPONSE);
            String response = new String(bytes, StandardCharsets.UTF_8).trim();
            if (code >= 300 && code < 400) throw new IllegalStateException("AstrBot 返回重定向；为防止 API Key 被转发，已停止请求");
            if (code < 200 || code >= 300) throw new IllegalStateException(httpError(code, response));
            JSONObject parsed;
            if (path.endsWith("/chat") && (response.startsWith("data:") || response.contains("\ndata:"))) {
                String answer = parseSseAnswer(response);
                parsed = new JSONObject().put("text", answer);
                if (answer.isEmpty()) parsed.put("rawSse", response.substring(0, Math.min(4000, response.length())));
            } else if (response.startsWith("[")) {
                JSONArray array = new JSONArray(response);
                parsed = new JSONObject();
                if (path.endsWith("/plugins") || path.contains("/plugins/market")) parsed.put("plugins", array);
                else parsed.put("data", array);
            } else {
                parsed = new JSONObject(response);
            }
            String status = clean(parsed.optString("status", ""));
            if ("error".equalsIgnoreCase(status) || "warning".equalsIgnoreCase(status)) {
                String message = clean(parsed.optString("message", ""));
                if (message.isEmpty()) message = clean(parsed.optString("error", "AstrBot API 返回失败"));
                throw new IllegalStateException(("warning".equalsIgnoreCase(status) ? "AstrBot 警告：" : "") + message);
            }
            if ((parsed.has("ok") && !parsed.optBoolean("ok", true))
                    || (parsed.has("success") && !parsed.optBoolean("success", true)))
                throw new IllegalStateException(parsed.optString("message", parsed.optString("error", "AstrBot API 返回失败")));
            normalizeListResponse(parsed, path);
            if (!parsed.has("ok")) parsed.put("ok", true);
            return parsed;
        } catch (Throwable t) { return error(t.getMessage() == null ? "AstrBot API 请求失败" : t.getMessage()); }
        finally {
            if (activeConnection != null && connection != null) activeConnection.compareAndSet(connection, null);
            if (connection != null) connection.disconnect();
        }
    }

    private static byte[] readBounded(InputStream input, int max) throws Exception {
        if (input == null) return new byte[0];
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int n;
            while ((n = in.read(buffer)) >= 0) {
                if (out.size() + n > max) throw new IllegalStateException("AstrBot 响应超过 " + (max / (1024 * 1024)) + " MB");
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }

    private static void normalizeListResponse(JSONObject parsed, String path) throws Exception {
        boolean pluginList = path.endsWith("/plugins") || path.contains("/plugins/market");
        if (!pluginList) return;
        if (!parsed.has("plugins") && parsed.opt("data") instanceof JSONArray) parsed.put("plugins", parsed.getJSONArray("data"));
        if (!parsed.has("plugins")) {
            JSONObject data = parsed.optJSONObject("data");
            if (data != null) {
                JSONArray rows = data.optJSONArray("plugins");
                if (rows == null) rows = data.optJSONArray("items");
                if (rows != null) parsed.put("plugins", rows);
                if (!parsed.has("total") && data.has("total")) parsed.put("total", data.optInt("total"));
                if (!parsed.has("page") && data.has("page")) parsed.put("page", data.optInt("page"));
            }
        }
    }

    private static String parseSseAnswer(String raw) {
        StringBuilder chunks = new StringBuilder();
        String finalText = "";
        String streamError = "";
        String finalMessages = "";
        for (String line : raw.split("\\r?\\n")) {
            if (!line.startsWith("data:")) continue;
            String data = line.substring(5).trim();
            if (data.isEmpty() || "[DONE]".equals(data)) continue;
            try {
                JSONObject event = new JSONObject(data);
                String type = event.optString("type", "").toLowerCase(Locale.ROOT);
                // AstrBot 会把工具调用、工具结果和思考过程也塞进 webchat 流，供官方 WebUI 渲染成卡片。
                // 桌宠这里没有卡片，只能当成正文，于是会冒出 JSON 和半截思考。按 chain_type 丢弃。
                String chainType = event.optString("chain_type", "").toLowerCase(Locale.ROOT);
                if (chainType.contains("tool_call") || chainType.contains("agent_stats")
                        || chainType.contains("reason") || chainType.contains("think")) continue;
                // 少数接口把推理帧的 chain_type 留空，只在 type 上标 reasoning/thinking；
                // 这类帧同样不能进正文。
                if (type.contains("reason") || type.contains("think")) continue;
                JSONArray messages = event.optJSONArray("messages");
                if (messages == null) {
                    JSONObject nestedData = event.optJSONObject("data");
                    if (nestedData != null) messages = nestedData.optJSONArray("messages");
                }
                if (messages != null) finalMessages = flattenMessages(messages);
                String text = event.optString("text", event.optString("content", ""));
                Object rawPayload = event.opt("data");
                if (text.isEmpty() && rawPayload instanceof String) text = (String) rawPayload;
                JSONObject nested = event.optJSONObject("data");
                if (nested != null && text.isEmpty()) text = nested.optString("text", nested.optString("content", ""));
                JSONObject delta = event.optJSONObject("delta");
                if (delta != null && text.isEmpty()) text = delta.optString("content", "");
                if (text.isEmpty() && nested != null) {
                    JSONObject nestedDelta = nested.optJSONObject("delta");
                    if (nestedDelta != null) text = nestedDelta.optString("content", "");
                }
                if (text.isEmpty()) {
                    JSONArray choices = event.optJSONArray("choices");
                    JSONObject first = choices == null ? null : choices.optJSONObject(0);
                    JSONObject choiceDelta = first == null ? null : first.optJSONObject("delta");
                    if (choiceDelta != null) text = choiceDelta.optString("content", "");
                }
                if ("error".equals(type)) {
                    streamError = text.isEmpty() ? "AstrBot SSE 返回错误" : text;
                    break;
                }
                if (text.isEmpty()) continue;
                if (type.contains("final") || type.contains("complete") || type.contains("finish") || type.contains("response") || "end".equals(type)) {
                    finalText = text;
                } else if ("plain".equals(type) || type.contains("delta") || type.contains("llm") || type.contains("chunk")) {
                    if (!type.contains("reason") && !type.contains("think")) chunks.append(text);
                } else if (chunks.length() == 0) {
                    finalText = text;
                }
            } catch (Throwable ignored) { }
        }
        if (!streamError.isEmpty()) throw new IllegalStateException(streamError);
        String answer = !finalMessages.isEmpty() ? finalMessages
                : (!finalText.isEmpty() ? finalText : chunks.toString());
        // 流式分片里可能夹着 AstrBot 注入的整块推理，返回前统一清掉，聊天和 QQ 都走这里。
        return stripInjectedReasoning(answer);
    }

    private static String extractAnswer(JSONObject result) {
        JSONArray messages = result.optJSONArray("messages");
        if (messages != null) {
            String flattened = flattenMessages(messages);
            if (!flattened.isEmpty()) return flattened;
        }
        String text = clean(result.optString("text", result.optString("content", result.optString("response", ""))));
        if (!text.isEmpty()) return text;
        Object rawMessage = result.opt("message");
        if (rawMessage instanceof JSONArray) {
            String flattened = flattenMessages((JSONArray) rawMessage);
            if (!flattened.isEmpty()) return flattened;
        } else if (rawMessage instanceof JSONObject) {
            JSONObject messageObject = (JSONObject) rawMessage;
            String content = clean(messageObject.optString("content", messageObject.optString("text", "")));
            if (!content.isEmpty()) return content;
        }
        JSONArray choices = result.optJSONArray("choices");
        JSONObject first = choices == null ? null : choices.optJSONObject(0);
        JSONObject message = first == null ? null : first.optJSONObject("message");
        if (message != null) return clean(message.optString("content", ""));
        JSONObject data = result.optJSONObject("data");
        if (data != null) {
            JSONArray nestedMessages = data.optJSONArray("messages");
            if (nestedMessages != null) {
                String flattened = flattenMessages(nestedMessages);
                if (!flattened.isEmpty()) return flattened;
            }
            Object nestedMessage = data.opt("message");
            if (nestedMessage instanceof JSONArray) {
                String flattened = flattenMessages((JSONArray) nestedMessage);
                if (!flattened.isEmpty()) return flattened;
            }
            return clean(data.optString("text", data.optString("content", "")));
        }
        return "";
    }

    /** Flatten AstrBot message-chain responses while retaining separate message bubbles. */
    private static String flattenMessages(JSONArray messages) {
        if (messages == null) return "";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < messages.length(); i++) {
            Object item = messages.opt(i);
            String value = "";
            if (item instanceof String) value = clean((String) item);
            else if (item instanceof JSONObject) {
                JSONObject part = (JSONObject) item;
                String type = part.optString("type", "plain").toLowerCase(Locale.ROOT);
                if (type.contains("reason") || type.contains("think")) continue;
                if ("plain".equals(type) || "text".equals(type) || type.isEmpty()) {
                    value = clean(part.optString("text", part.optString("content", part.optString("data", ""))));
                    if (value.isEmpty()) {
                        JSONObject nested = part.optJSONObject("data");
                        if (nested != null) value = clean(nested.optString("text", nested.optString("content", "")));
                    }
                }
            }
            if (value.isEmpty() || isReasoningLine(value)) continue;
            if (out.length() > 0) out.append('\n');
            out.append(value);
        }
        return out.toString().trim();
    }

    private static String normalizeBaseUrl(String value) {
        String raw = clean(value);
        if (raw.isEmpty() || raw.length() > 500) throw new IllegalArgumentException("请输入 AstrBot 服务地址");
        try {
            URI uri = new URI(raw);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!("https".equals(scheme) || "http".equals(scheme))) throw new IllegalArgumentException("地址必须使用 HTTPS，局域网内也可使用 HTTP");
            if (uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null || uri.getHost() == null)
                throw new IllegalArgumentException("服务地址不能包含账号、查询参数或片段");
            String path = uri.getPath();
            if (path != null && !path.isEmpty() && !"/".equals(path)) throw new IllegalArgumentException("服务地址只填写主机和端口，不要附加路径");
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            if ("http".equals(scheme) && !isLocalHost(host)) throw new IllegalArgumentException("非局域网服务必须使用 HTTPS，避免明文传输 API Key");
            int port = uri.getPort();
            String base = scheme + "://" + host + (port > 0 ? ":" + port : "");
            return base;
        } catch (IllegalArgumentException e) { throw e; }
        catch (Throwable t) { throw new IllegalArgumentException("AstrBot 服务地址格式无效"); }
    }

    private static boolean isLocalHost(String host) {
        if ("localhost".equals(host) || host.endsWith(".localhost")) return true;
        try {
            if (host.indexOf(':') >= 0) return InetAddress.getByName(host).isLoopbackAddress() || InetAddress.getByName(host).isSiteLocalAddress() || InetAddress.getByName(host).isLinkLocalAddress();
            String[] p = host.split("\\.");
            if (p.length != 4) return false;
            int a = Integer.parseInt(p[0]), b = Integer.parseInt(p[1]);
            for (String part : p) { int octet = Integer.parseInt(part); if (octet < 0 || octet > 255) return false; }
            return a == 10 || a == 127 || a == 192 && b == 168 || a == 172 && b >= 16 && b <= 31 || a == 169 && b == 254;
        } catch (Throwable ignored) { return false; }
    }

    private static String httpError(int code, String response) {
        if (code == 401) return "AstrBot API Key 无效或已过期（HTTP 401）";
        if (code == 403) return "AstrBot API Key 缺少所需权限（HTTP 403）；插件页需要 plugin，聊天需要 chat";
        return "AstrBot API HTTP " + code + (response.isEmpty() ? "" : "：" + response.substring(0, Math.min(240, response.length())));
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte item : digest) out.append(String.format(Locale.ROOT, "%02x", item & 0xff));
            return out.toString();
        } catch (Throwable ignored) { return Integer.toHexString(value.hashCode()); }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Remove the abandoned inert local runtime cache and its obsolete loopback API credential once. */
    private static void cleanupLegacyLocalRuntime(Context context) {
        SharedPreferences state = context.getSharedPreferences("astrbot_remote_migration", Context.MODE_PRIVATE);
        if (state.getBoolean("legacyRuntimeRemoved", false)) return;
        boolean removed = false;
        try {
            File appRoot = Sg.dir(context).getCanonicalFile();
            File legacy = new File(appRoot, "astrbot-runtime").getCanonicalFile();
            if (legacy.toPath().startsWith(appRoot.toPath()) && !legacy.equals(appRoot)) removed = deleteLegacyTree(legacy, appRoot);
            else removed = true;
        } catch (Throwable ignored) { }
        context.getSharedPreferences("astrbot_local_api", Context.MODE_PRIVATE).edit().clear().commit();
        if (removed) state.edit().putBoolean("legacyRuntimeRemoved", true).commit();
    }

    private static boolean deleteLegacyTree(File file, File appRoot) throws Exception {
        if (file == null || !file.exists()) return true;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) return false;
            for (File child : children) {
                File canonical = child.getCanonicalFile();
                if (!canonical.toPath().startsWith(appRoot.toPath()) || !deleteLegacyTree(canonical, appRoot)) return false;
            }
        }
        return file.delete() || !file.exists();
    }

    private static boolean isPluginActive(JSONObject plugin) {
        if (plugin.has("activated")) return plugin.optBoolean("activated", false);
        if (plugin.has("is_active")) return plugin.optBoolean("is_active", false);
        if (plugin.has("enabled")) return plugin.optBoolean("enabled", false);
        return true;
    }

    private static String verifyInstallState(JSONObject installed, JSONObject failed, String repository, String pluginId) {
        if (containsPlugin(failed, repository, pluginId)) return "failed";
        if (!containsPlugin(installed, repository, pluginId)) return "pending";
        JSONObject plugin = matchingPluginRecord(installed, repository, pluginId);
        if (plugin == null) return "installed";
        if (plugin.optBoolean("is_failed") || !clean(plugin.optString("error", "")).isEmpty()) return "failed";
        // AstrBot 4.29.3 的已安装插件记录用 activated 表示是否启用；旧版本/兼容字段为 is_active、enabled。
        if (plugin.has("activated")) return plugin.optBoolean("activated", false) ? "loaded" : "disabled";
        if (plugin.has("is_active")) return plugin.optBoolean("is_active", false) ? "loaded" : "disabled";
        if (plugin.has("enabled")) return plugin.optBoolean("enabled", false) ? "loaded" : "disabled";
        return "installed";
    }

    private static String pluginFailureMessage(JSONObject failed, String repository, String pluginId) {
        JSONArray list = pluginArray(failed);
        String expected = clean(pluginId).toLowerCase(Locale.ROOT);
        String repo = githubRepositoryRoot(repository).toLowerCase(Locale.ROOT);
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject item = list.optJSONObject(i);
            if (item == null || !pluginRecordMatches(item, repo, expected)) continue;
            String message = clean(item.optString("error", item.optString("message", item.optString("reason", ""))));
            if (!message.isEmpty()) return bounded(message, 500);
        }
        return "AstrBot 报告插件加载失败，但未提供失败详情";
    }

    private static boolean containsPlugin(JSONObject state, String repository, String pluginId) {
        JSONArray list = pluginArray(state);
        if (list == null) return false;
        String expected = clean(pluginId).toLowerCase(Locale.ROOT);
        String repo = githubRepositoryRoot(repository).toLowerCase(Locale.ROOT);
        for (int i = 0; i < list.length(); i++) {
            if (pluginRecordMatches(list.optJSONObject(i), repo, expected)) return true;
        }
        return false;
    }

    private static boolean pluginRecordMatches(JSONObject item, String repo, String expectedId) {
        if (item == null) return false;
        if (!expectedId.isEmpty() && canonicalPluginId(item).equals(expectedId)) return true;
        String source = githubRepositoryRoot(clean(item.optString("repo", item.optString("repository", item.optString("url", ""))))).toLowerCase(Locale.ROOT);
        if (!source.isEmpty() && !repo.isEmpty() && source.equals(repo)) return true;
        JSONObject installSource = item.optJSONObject("install_source");
        if (installSource == null) return false;
        String marketId = clean(installSource.optString("market_plugin_id", "")).toLowerCase(Locale.ROOT);
        if (!expectedId.isEmpty() && marketId.equals(expectedId)) return true;
        String sourceRepo = githubRepositoryRoot(clean(installSource.optString("repo", installSource.optString("repository", "")))).toLowerCase(Locale.ROOT);
        return !sourceRepo.isEmpty() && !repo.isEmpty() && sourceRepo.equals(repo);
    }

    private static JSONObject matchingPluginRecord(JSONObject state, String repository, String pluginId) {
        JSONArray list = pluginArray(state);
        String expected = clean(pluginId).toLowerCase(Locale.ROOT);
        String repo = githubRepositoryRoot(repository).toLowerCase(Locale.ROOT);
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject item = list.optJSONObject(i);
            if (pluginRecordMatches(item, repo, expected)) return item;
        }
        return null;
    }

    private static JSONArray pluginArray(JSONObject state) {
        if (state == null) return null;
        JSONArray list = state.optJSONArray("plugins");
        if (list == null) list = state.optJSONArray("failed");
        if (list == null) list = state.optJSONArray("items");
        JSONObject data = state.optJSONObject("data");
        if (list == null && data != null) {
            list = data.optJSONArray("plugins");
            if (list == null) list = data.optJSONArray("failed");
            if (list == null) list = data.optJSONArray("items");
            if (list == null) list = objectValues(data);
        }
        return list;
    }

    /** AstrBot 的失败插件列表是字典（key = 插件目录名），需要展开成数组。 */
    private static JSONArray objectValues(JSONObject object) {
        JSONArray out = new JSONArray();
        java.util.Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if ("$meta".equals(key)) continue;
            JSONObject value = object.optJSONObject(key);
            if (value == null) continue;
            try { if (!value.has("root_dir_name")) value.put("root_dir_name", key); } catch (Throwable ignored) { }
            out.put(value);
        }
        return out.length() == 0 ? null : out;
    }

    private static String canonicalPluginId(JSONObject plugin) {
        String id = clean(plugin.optString("plugin_id", plugin.optString("id", "")));
        if (validPluginId(id)) return id.toLowerCase(Locale.ROOT);
        String marketId = clean(plugin.optString("market_plugin_id", ""));
        if (validPluginId(marketId)) return marketId.toLowerCase(Locale.ROOT);
        JSONObject installSource = plugin.optJSONObject("install_source");
        if (installSource != null) {
            String sourceId = clean(installSource.optString("market_plugin_id", ""));
            if (validPluginId(sourceId)) return sourceId.toLowerCase(Locale.ROOT);
        }
        String dir = clean(plugin.optString("root_dir_name", ""));
        if (validPluginId(dir)) return dir.toLowerCase(Locale.ROOT);
        JSONObject meta = plugin.optJSONObject("metadata");
        if (meta == null) meta = plugin.optJSONObject("meta");
        String author = clean(plugin.optString("author", meta == null ? "" : meta.optString("author", "")));
        String name = clean(plugin.optString("name", meta == null ? "" : meta.optString("name", "")));
        if (author.matches("[A-Za-z0-9_.-]+") && name.matches("[A-Za-z0-9_.-]+")) return (author + "/" + name).toLowerCase(Locale.ROOT);
        return "";
    }

    private static java.util.regex.Matcher githubRepositoryMatcher(String repository) {
        return java.util.regex.Pattern.compile("^(https://github\\.com/[A-Za-z0-9_-]+/[A-Za-z0-9_.-]+)(?:\\.git)?(?:/tree/([A-Za-z0-9_-]+))?$")
                .matcher(clean(repository));
    }
    private static boolean validInstallRepo(String repository) { return githubRepositoryMatcher(repository).matches(); }
    private static String githubRepositoryRoot(String repository) {
        java.util.regex.Matcher matcher = githubRepositoryMatcher(repository);
        return matcher.matches() ? matcher.group(1) : clean(repository).replaceAll("\\.git$", "").replaceAll("/tree/[A-Za-z0-9_-]+$", "");
    }
    private static String clean(String value) { return value == null ? "" : value.trim(); }
    private static String bounded(String value, int limit) {
        if (value == null) return "";
        return value.length() <= limit ? value : value.substring(0, limit);
    }
    private static JSONObject error(String message) {
        try { return new JSONObject().put("ok", false).put("error", message == null ? "AstrBot 请求失败" : message); }
        catch (Throwable ignored) { return new JSONObject(); }
    }
}
