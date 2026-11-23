package com.dshpet.android;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.SSLSocketFactory;

/** OneBot V11 forward-WebSocket connector for explicitly allowlisted private contacts and groups. */
final class QQOneBot {
    private static final Object LOCK = new Object();
    private static final Object SEND_LOCK = new Object();
    private static final Object GROUP_LOCK = new Object();
    private static final AtomicLong ECHO = new AtomicLong(1);
    private static final Random RANDOM = new Random();
    private static final LinkedHashSet<String> RECENT = new LinkedHashSet<>();
    private static final LinkedHashSet<String> activeGroupIds = new LinkedHashSet<>();
    private static final HashMap<String, Long> GROUP_BUSY = new HashMap<>();
    private static final HashMap<String, Long> GROUP_LAST_REPLY = new HashMap<>();
    private static final HashMap<String, ArrayDeque<PendingGroupMessage>> GROUP_PENDING = new HashMap<>();
    private static final ScheduledExecutorService REPLIES = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "DshQqReply");
        t.setDaemon(true);
        return t;
    });
    private static final ExecutorService QQ_IMAGES = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "DshQqImageVision");
        t.setDaemon(true);
        return t;
    });
    private static final int MAX_QQ_IMAGE_BYTES = 8 * 1024 * 1024;
    private static final int MAX_QQ_IMAGE_EDGE = 1600;

    private static final class PendingGroupMessage {
        final String groupName;
        final QQGroupChat.Incoming incoming;
        final boolean freeMode;
        final boolean groupAstrBot;
        final long generation;

        PendingGroupMessage(String groupName, QQGroupChat.Incoming incoming, boolean freeMode,
                            boolean groupAstrBot, long generation) {
            this.groupName = groupName;
            this.incoming = incoming;
            this.freeMode = freeMode;
            this.groupAstrBot = groupAstrBot;
            this.generation = generation;
        }
    }

    private static volatile Context app;
    private static volatile Thread worker;
    private static volatile boolean running;
    private static volatile long generation;
    private static volatile Socket socket;
    private static volatile OutputStream output;
    private static volatile String state = "未连接";
    private static volatile String lastError = "";
    private static volatile String groupReplyError = "";
    private static volatile String selfId = "";
    private static volatile String selfName = "";
    private static volatile String activeKey = "";
    private static volatile long lastAcceptedMessageAt;
    /** 本应用最近发过的文本（私聊/群聊气泡），用于识别机器人账号从别的客户端发出的消息。 */
    private static final ArrayDeque<String> RECENT_SENT = new ArrayDeque<>();
    /** 最近一次检测到“同一 QQ 机器人存在第二个应答客户端”的时间戳；0 表示未检测到。 */
    private static volatile long externalResponderAt = 0L;

    private QQOneBot() { }

    static void sync(Context context) {
        if (context == null) return;
        app = context.getApplicationContext();
        Cfg cfg = Cfg.load(app);
        if (!cfg.b("qqLinkEnabled", false)) {
            stopAndSummarize();
            return;
        }
        String url = cfg.str("qqOnebotUrl", "").trim();
        String token = cfg.str("qqOnebotToken", "").trim();
        String allow = cfg.str("qqAllowedUserId", "").trim();
        LinkedHashSet<String> groups = parseGroupIds(cfg.str("qqGroupIds", ""));
        String groupMode = "participate".equals(cfg.str("qqGroupMode", "mention")) ? "participate" : "mention";
        boolean groupAstrBot = cfg.b("qqGroupAstrBot", false);
        boolean privateAstrBot = cfg.b("qqPrivateAstrBot", false);
        String groupPersonaId = cfg.str("qqGroupPersonaId", "");
        // NapCat 与本机 AstrBot 运行管理器同机时，OneBot Token 通常为空；
        // 本机回环连接允许不填 Token，局域网地址仍建议设置 Token。
        if (url.isEmpty() || (!allow.isEmpty() && !isNumericId(allow))
                || (!isNumericId(allow) && groups.isEmpty())) {
            synchronized (LOCK) {
                LinkedHashSet<String> endingGroups = new LinkedHashSet<>(activeGroupIds);
                endingGroups.addAll(groups);
                QQGroupChat.closeActiveSessions(app, endingGroups);
                stopLocked();
                activeKey = "";
                activeGroupIds.clear();
            }
            setState("待补全 WebSocket 地址和私聊 QQ 号或群号", "");
            return;
        }
        QQGroupChat.resumeSessions(app, groups);
        String key = url + "\n" + token + "\n" + allow + "\n" + String.join(",", groups)
                + "\n" + groupMode + "\n" + groupAstrBot + "\n" + privateAstrBot + "\n" + groupPersonaId;
        synchronized (LOCK) {
            boolean sameConfig = key.equals(activeKey);
            if (running && sameConfig && worker != null && worker.isAlive()) return;
            if (!sameConfig && !activeGroupIds.isEmpty())
                QQGroupChat.closeActiveSessions(app, new LinkedHashSet<>(activeGroupIds));
            stopLocked();
            activeKey = key;
            activeGroupIds.clear();
            activeGroupIds.addAll(groups);
            running = true;
            long myGeneration = ++generation;
            state = "正在连接 NapCat 所在设备…";
            lastError = "";
            groupReplyError = "";
            Thread t = new Thread(() -> loop(url, token, allow, groups, groupMode, groupAstrBot,
                    privateAstrBot, myGeneration), "DshQqOneBot");
            t.setDaemon(true);
            worker = t;
            t.start();
        }
        emitStatus();
    }

    static void stop() {
        synchronized (LOCK) {
            stopLocked();
            state = "未连接";
            lastError = "";
            groupReplyError = "";
            selfId = "";
            selfName = "";
        }
        emitStatus();
    }

    static void stopAndSummarize() {
        Context current = app;
        LinkedHashSet<String> endingGroups;
        synchronized (LOCK) {
            endingGroups = new LinkedHashSet<>(activeGroupIds);
            stopLocked();
            activeKey = "";
            activeGroupIds.clear();
            state = "未连接";
            lastError = "";
            groupReplyError = "";
            selfId = "";
            selfName = "";
        }
        emitStatus();
        if (current == null) return;
        Cfg cfg = Cfg.load(current);
        endingGroups.addAll(parseGroupIds(cfg.str("qqGroupIds", "")));
        QQGroupChat.closeActiveSessions(current, endingGroups);
    }

    private static void stopLocked() {
        running = false;
        generation++;
        Thread t = worker;
        worker = null;
        if (t != null) t.interrupt();
        Socket s = socket;
        socket = null;
        output = null;
        if (s != null) try { s.close(); } catch (Throwable ignored) { }
        synchronized (GROUP_LOCK) {
            // Keep any in-flight group marked busy until its async model callback
            // releases it; a quick reconnect must not start a second chat in the same session.
            GROUP_LAST_REPLY.clear();
            GROUP_PENDING.clear();
        }
    }

    static JSONObject status(Context context) {
        if (context != null) app = context.getApplicationContext();
        JSONObject out = new JSONObject();
        try {
            out.put("enabled", Cfg.load(app == null ? context : app).b("qqLinkEnabled", false));
            out.put("connected", "已连接".equals(state));
            out.put("state", state);
            out.put("error", lastError);
            out.put("groupReplyError", groupReplyError);
            out.put("selfId", selfId);
            out.put("selfName", selfName);
            out.put("allowedUserId", Cfg.load(app == null ? context : app).str("qqAllowedUserId", ""));
            Cfg cfg = Cfg.load(app == null ? context : app);
            out.put("groupCount", parseGroupIds(cfg.str("qqGroupIds", "")).size());
            out.put("groupMode", "participate".equals(cfg.str("qqGroupMode", "mention")) ? "participate" : "mention");
            out.put("externalResponderAt", externalResponderAt);
        } catch (Throwable ignored) { }
        return out;
    }

    /** Refresh also asks NapCat for fresh login identity; status() itself stays read-only. */
    static JSONObject refreshStatus(Context context) {
        if (context != null) app = context.getApplicationContext();
        Socket connected = socket;
        if (running && connected != null && !connected.isClosed() && output != null) {
            sendAction("get_login_info", new JSONObject(), "login");
        }
        return status(context);
    }

    private static void loop(String url, String token, String allowedId, Set<String> groupIds,
                             String groupMode, boolean groupAstrBot, boolean privateAstrBot,
                             long myGeneration) {
        int failures = 0;
        while (isCurrent(myGeneration)) {
            try {
                setState("正在连接 NapCat 所在设备…", "");
                Socket connected = open(url, token);
                socket = connected;
                output = connected.getOutputStream();
                failures = 0;
                selfId = "";
                selfName = "";
                setState("已连接，正在确认 QQ 账号…", "");
                sendAction("get_login_info", new JSONObject(), "login");
                InputStream in = new BufferedInputStream(connected.getInputStream());
                while (isCurrent(myGeneration) && !connected.isClosed()) {
                    try {
                        Frame frame = readMessage(in);
                        if (frame == null) break;
                        if (frame.op == 0x9) {
                            sendFrame(0xA, frame.data);
                            continue;
                        }
                        if (frame.op == 0x8) break;
                        if (frame.op != 0x1) continue;
                        String json = new String(frame.data, StandardCharsets.UTF_8);
                        handle(json, allowedId, groupIds, groupMode, groupAstrBot, privateAstrBot, myGeneration);
                    } catch (java.net.SocketTimeoutException timeout) {
                        if (!isCurrent(myGeneration)) break;
                        // Keep the forward WS alive during long idle periods without frequent wakeups.
                        sendAction("get_login_info", new JSONObject(), "ping");
                    }
                }
                closeSocket(connected);
                if (isCurrent(myGeneration)) setState("NapCat 连接断开，准备重连…", "");
            } catch (Throwable e) {
                if (!isCurrent(myGeneration)) break;
                String detail = e.getMessage() == null ? "连接失败" : e.getMessage().trim();
                if (detail.length() > 100) detail = detail.substring(0, 100);
                setState("连接失败，正在重试", detail);
                closeSocket(socket);
            }
            if (!isCurrent(myGeneration)) break;
            failures = Math.min(6, failures + 1);
            try { Thread.sleep(Math.min(30000L, 2000L << Math.max(0, failures - 1))); }
            catch (InterruptedException ignored) { }
        }
    }

    private static Socket open(String address, String token) throws Exception {
        URI uri = new URI(address);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        boolean tls = "wss".equals(scheme);
        if (!tls && !"ws".equals(scheme)) throw new Exception("地址必须以 ws:// 或 wss:// 开头");
        String host = uri.getHost();
        if (host == null || host.trim().isEmpty()) throw new Exception("连接地址里没有 NapCat 设备 IP 或主机名");
        int port = uri.getPort() > 0 ? uri.getPort() : (tls ? 443 : 80);
        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) path = "/";
        if (uri.getRawQuery() != null && !uri.getRawQuery().isEmpty()) path += "?" + uri.getRawQuery();

        Socket s;
        if (tls) {
            s = SSLSocketFactory.getDefault().createSocket();
            s.connect(new InetSocketAddress(host, port), 10000);
            javax.net.ssl.SSLSocket ssl = (javax.net.ssl.SSLSocket) s;
            javax.net.ssl.SSLParameters params = ssl.getSSLParameters();
            params.setEndpointIdentificationAlgorithm("HTTPS");
            ssl.setSSLParameters(params);
            ssl.startHandshake();
        } else {
            s = new Socket();
            s.connect(new InetSocketAddress(host, port), 10000);
        }
        // Reply to protocol pings immediately; use a slower API heartbeat only
        // when the connection is otherwise completely idle.
        s.setSoTimeout(120000);
        String key = Base64.getEncoder().encodeToString(randomBytes(16));
        String hostHeader = host + ((tls && port == 443) || (!tls && port == 80) ? "" : ":" + port);
        String request = "GET " + path + " HTTP/1.1\r\n"
                + "Host: " + hostHeader + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + key + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n"
                + (token == null || token.isEmpty() ? "" : "Authorization: Bearer " + token + "\r\n")
                + "\r\n";
        OutputStream os = s.getOutputStream();
        os.write(request.getBytes(StandardCharsets.UTF_8));
        os.flush();
        InputStream in = new BufferedInputStream(s.getInputStream());
        String first = readLine(in);
        if (first == null || !first.contains(" 101 ")) {
            try { s.close(); } catch (Throwable ignored) { }
            if (first != null && (first.contains("401") || first.contains("403"))) {
                throw new Exception("NapCat 拒绝连接，请检查 OneBot Token");
            }
            throw new Exception("NapCat WebSocket 未升级成功" + (first == null ? "" : "（" + first.trim() + "）"));
        }
        String accept = "";
        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            int colon = line.indexOf(':');
            if (colon > 0 && "sec-websocket-accept".equalsIgnoreCase(line.substring(0, colon).trim())) {
                accept = line.substring(colon + 1).trim();
            }
        }
        String expected = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
        if (!expected.equals(accept)) {
            try { s.close(); } catch (Throwable ignored) { }
            throw new Exception("NapCat WebSocket 握手校验失败");
        }
        return s;
    }

    private static void handle(String json, String allowedId, Set<String> groupIds,
                               String groupMode, boolean groupAstrBot, boolean privateAstrBot,
                               long myGeneration) {
        try {
            JSONObject event = new JSONObject(json);
            if (event.has("echo")) {
                String echo = event.optString("echo", "");
                if (echo.startsWith("group-reply-")) {
                    if ("failed".equalsIgnoreCase(event.optString("status", ""))) {
                        setGroupReplyError("NapCat 没有发出群回复。请检查机器人是否仍在群内、是否被禁言或受群权限限制。");
                    } else if ("ok".equalsIgnoreCase(event.optString("status", ""))) {
                        setGroupReplyError("");
                    }
                    return;
                }
                // sendAction appends a sequence number (login-1); accept both old and sequenced echoes.
                if ("login".equals(echo) || echo.startsWith("login-")
                        || "ping".equals(echo) || echo.startsWith("ping-")) {
                    JSONObject data = event.optJSONObject("data");
                    if ("ok".equalsIgnoreCase(event.optString("status", "")) && data != null) {
                        selfId = data.optString("user_id", "");
                        selfName = data.optString("nickname", "");
                        setState("已连接", "");
                    } else if ("failed".equalsIgnoreCase(event.optString("status", ""))) {
                        setState("已连接，但读取 QQ 账号信息失败", event.optString("wording", ""));
                    }
                }
                return;
            }
            if (!"message".equals(event.optString("post_type", ""))) return;
            String eventUser = event.optString("user_id", "");
            boolean selfSent = "message_sent".equals(event.optString("sub_type", ""))
                    || (!selfId.isEmpty() && eventUser.equals(selfId));
            if (selfSent) {
                // 机器人账号从别的客户端（如 AstrBot 原生 OneBot 适配器）发言时，说明存在第二个应答者。
                noteExternalResponder(event);
                return;
            }
            if ("private".equals(event.optString("message_type", ""))) {
                handlePrivate(event, allowedId, privateAstrBot, myGeneration);
            } else if ("group".equals(event.optString("message_type", ""))) {
                handleGroup(event, groupIds, groupMode, groupAstrBot, myGeneration);
            }
        } catch (Throwable ignored) { }
    }

    private static void handlePrivate(JSONObject event, String allowedId, boolean privateAstrBot,
                                      long myGeneration) {
        String userId = event.optString("user_id", "");
        if (allowedId == null || allowedId.isEmpty() || !allowedId.equals(userId) || userId.equals(selfId)) return;
        IncomingMessage incoming = parseIncoming(event.opt("message"));
        String text = incoming.text.trim();
        if (incoming.hasImage && text.isEmpty()) text = "你发来了一张 QQ 表情或图片，请看图自然回应。";
        if (incoming.unavailableSticker && !incoming.hasImage && text.isEmpty()) text = "（收到一张 QQ 表情，但 NapCat 没有提供可读取的图片）";
        if (text.isEmpty() && !incoming.hasImage) return;
        if (!rememberEvent("private:" + userId, event, text)) return;
        long now = System.currentTimeMillis();
        if (now - lastAcceptedMessageAt < 1200L) return;
        lastAcceptedMessageAt = now;
        Context c = app;
        if (c == null || !isCurrent(myGeneration)) return;
        // 私聊插件模式：只把 / 开头的指令直发 AstrBot，普通聊天仍用手机本地模型。
        if (privateAstrBot && AstrBotBridge.isPluginCommand(text)
                && AstrBotBridge.pluginCommandsAvailable(c)) {
            final String command = text;
            AstrBotBridge.chatPluginCommand(c, "qq-private:" + userId, command, new Ai.Cb() {
                @Override public void progress(String delta) { }
                @Override public void ok(String reply) {
                    if (isCurrent(myGeneration)) sendReply(userId, reply);
                }
                @Override public void fail(String error) {
                    String safe = error == null || error.trim().isEmpty()
                            ? "插件指令这次没有执行成功，稍后再试。" : error.trim();
                    if (safe.length() > 350) safe = safe.substring(0, 350);
                    if (isCurrent(myGeneration)) sendReply(userId, safe);
                }
            });
            return;
        }
        if (incoming.hasImage || incoming.unavailableSticker) {
            final String prompt = text;
            final String imageUrl = incoming.imageUrl;
            final boolean unavailable = incoming.unavailableSticker && imageUrl.isEmpty();
            QQ_IMAGES.execute(() -> {
                if (!isCurrent(myGeneration)) return;
                Bitmap bitmap = imageUrl.isEmpty() ? null : downloadQqImage(c, imageUrl);
                if (bitmap == null) {
                    String failure = unavailable
                            ? "我收到你发来的 QQ 表情了，不过 NapCat 只给了表情编号，没有给图片文件，所以这次视觉模型看不到它。可以把表情另存为图片后再发。"
                            : "我收到你发来的 QQ 图片了，但这次没能从 NapCat 提供的链接读取图片；没有把它当纯文字猜。请重新发送，或检查 NapCat 有没有提供图片链接。";
                    Data.chatAppend(c, "user", prompt);
                    Data.chatAppend(c, "assistant", failure);
                    notifyLocalChatUpdated();
                    if (isCurrent(myGeneration)) sendReply(userId, failure);
                    return;
                }
                Brain.chatExternalImage(c, prompt, bitmap, new Ai.Cb() {
                    @Override public void progress(String delta) { }
                    @Override public void ok(String reply) {
                        try { bitmap.recycle(); } catch (Throwable ignored) { }
                        notifyLocalChatUpdated();
                        if (isCurrent(myGeneration)) sendReply(userId, reply);
                    }
                    @Override public void fail(String error) {
                        try { bitmap.recycle(); } catch (Throwable ignored) { }
                        String safe = error == null || error.trim().isEmpty()
                                ? "我收到了图片，但视觉识别这次失败了。请稍后重发。" : error.trim();
                        if (safe.length() > 350) safe = safe.substring(0, 350);
                        Data.chatAppend(c, "assistant", safe);
                        notifyLocalChatUpdated();
                        if (isCurrent(myGeneration)) sendReply(userId, safe);
                    }
                });
            });
            return;
        }
        Brain.chatExternal(c, text, new Ai.Cb() {
            @Override public void progress(String delta) { }
            @Override public void ok(String reply) {
                notifyLocalChatUpdated();
                if (isCurrent(myGeneration)) sendReply(userId, reply);
            }
            @Override public void fail(String error) {
                String safe = error == null || error.trim().isEmpty() ? "我刚才没接住这条消息，等会儿再试试。" : error.trim();
                if (safe.length() > 350) safe = safe.substring(0, 350);
                Data.chatAppend(c, "assistant", safe);
                notifyLocalChatUpdated();
                if (isCurrent(myGeneration)) sendReply(userId, safe);
            }
        });
    }

    private static void notifyLocalChatUpdated() {
        try { Bus.emit("qqChatUpdated", new JSONObject()); }
        catch (Throwable ignored) { }
    }

    private static void handleGroup(JSONObject event, Set<String> groupIds,
                                    String groupMode, boolean groupAstrBot, long myGeneration) {
        String groupId = event.optString("group_id", "");
        String userId = event.optString("user_id", "");
        if (groupIds == null || !groupIds.contains(groupId) || userId.isEmpty() || userId.equals(selfId)) return;
        Object rawMessage = event.opt("message");
        boolean mentioned = mentionsBot(rawMessage, selfId);
        boolean freeMode = "participate".equals(groupMode);
        IncomingMessage parsed = parseIncoming(rawMessage);
        String text = parsed.text;
        if (text.isEmpty() && parsed.hasImage) text = "（发来一张图片，请看图）";
        if (text.isEmpty() && parsed.unavailableSticker && !parsed.hasImage)
            text = "（收到一张 QQ 表情，但 NapCat 没有提供可读取的图片）";
        if (text.isEmpty()) return;
        String dedupe = parsed.hasImage ? text + "|img:" + parsed.imageUrl.hashCode() : text;
        if (!rememberEvent("group:" + groupId, event, dedupe)) return;
        JSONObject sender = event.optJSONObject("sender");
        String senderName = sender == null ? "" : sender.optString("card", "").trim();
        if (senderName.isEmpty() && sender != null) senderName = sender.optString("nickname", "").trim();
        if (senderName.isEmpty()) senderName = userId;
        String groupName = event.optString("group_name", "").trim();
        if (groupName.isEmpty()) groupName = groupId;
        Context c = app;
        if (c == null || !isCurrent(myGeneration)) return;
        QQGroupChat.Incoming incoming = new QQGroupChat.Incoming(senderName, text,
                parsed.imageUrl, parsed.hasImage);
        if (!freeMode && !mentioned) {
            QQGroupChat.remember(c, groupId, groupName, incoming);
            return;
        }
        synchronized (GROUP_LOCK) {
            if (GROUP_BUSY.containsKey(groupId)) {
                if (freeMode) {
                    ArrayDeque<PendingGroupMessage> pending = GROUP_PENDING.get(groupId);
                    if (pending == null) {
                        pending = new ArrayDeque<>();
                        GROUP_PENDING.put(groupId, pending);
                    }
                    pending.addLast(new PendingGroupMessage(groupName, incoming, true, groupAstrBot, myGeneration));
                } else {
                    QQGroupChat.remember(c, groupId, groupName, incoming);
                }
                return;
            }
            long now = System.currentTimeMillis();
            long previous = GROUP_LAST_REPLY.containsKey(groupId) ? GROUP_LAST_REPLY.get(groupId) : 0L;
            if (!freeMode && now - previous < 5000L) {
                QQGroupChat.remember(c, groupId, groupName, incoming);
                return;
            }
            GROUP_BUSY.put(groupId, myGeneration);
            if (!freeMode) GROUP_LAST_REPLY.put(groupId, now);
        }
        startGroupChat(groupId, groupName, incoming, freeMode, groupAstrBot, myGeneration);
    }

    private static void startGroupChat(String groupId, String groupName, QQGroupChat.Incoming incoming,
                                       boolean freeMode, boolean groupAstrBot, long myGeneration) {
        Context c = app;
        if (c == null || !isCurrent(myGeneration)) {
            releaseGroup(groupId, myGeneration);
            return;
        }
        if (!incoming.hasImage || incoming.imageUrl.isEmpty()) {
            runGroupChat(c, groupId, groupName, incoming, freeMode, groupAstrBot, null, myGeneration);
            return;
        }
        // 图片必须先下载成 Bitmap 才能交给视觉模型或上传 AstrBot；沿用私聊的单线程下载池。
        QQ_IMAGES.execute(() -> {
            if (!isCurrent(myGeneration)) {
                releaseGroup(groupId, myGeneration);
                return;
            }
            Bitmap bitmap = downloadQqImage(c, incoming.imageUrl);
            if (bitmap == null) {
                // 读不到图就不让模型猜：被 @ 时直接说明，自由参与模式只留一条“图片未读取”的上下文。
                if (freeMode) {
                    QQGroupChat.remember(c, groupId, groupName,
                            new QQGroupChat.Incoming(incoming.sender, "（发来一张图片，但图片读取失败，不要描述图片内容）"));
                    releaseGroup(groupId, myGeneration);
                } else {
                    if (isCurrent(myGeneration))
                        sendGroupReply(groupId, "群里这张图片我没能读取成功，可以重新发一次，或者直接告诉我图里是什么。", myGeneration);
                    releaseGroup(groupId, myGeneration);
                }
                return;
            }
            runGroupChat(c, groupId, groupName, incoming, freeMode, groupAstrBot, bitmap, myGeneration);
        });
    }

    private static void runGroupChat(Context c, String groupId, String groupName, QQGroupChat.Incoming incoming,
                                     boolean freeMode, boolean groupAstrBot, Bitmap image, long myGeneration) {
        String targetGroup = groupId;
        try {
            QQGroupChat.chat(c, groupId, groupName, freeMode, groupAstrBot,
                    Collections.singletonList(incoming), image, new Ai.Cb() {
                        @Override public void progress(String delta) { }

                        @Override public void ok(String reply) {
                            try {
                                if (isCurrent(myGeneration) && reply != null && !reply.trim().isEmpty()) {
                                    sendGroupReply(targetGroup, reply, myGeneration);
                                }
                            } finally { recycle(image); releaseGroup(targetGroup, myGeneration); }
                        }

                        @Override public void fail(String error) {
                            try {
                                String safe = AgentErrorHandler.groupChat(error);
                                if (isCurrent(myGeneration)) sendGroupReply(targetGroup, safe, myGeneration);
                            } finally { recycle(image); releaseGroup(targetGroup, myGeneration); }
                        }
                    });
        } catch (Throwable e) {
            recycle(image);
            releaseGroup(groupId, myGeneration);
        }
    }

    private static void recycle(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) return;
        try { bitmap.recycle(); } catch (Throwable ignored) { }
    }

    private static boolean rememberEvent(String scope, JSONObject event, String text) {
        String messageId = event.optString("message_id", "");
        String key = scope + ":" + messageId;
        if (messageId.isEmpty()) key += ":" + text.hashCode();
        synchronized (RECENT) {
            if (RECENT.contains(key)) return false;
            RECENT.add(key);
            while (RECENT.size() > 240) RECENT.remove(RECENT.iterator().next());
        }
        return true;
    }

    private static boolean mentionsBot(Object message, String botId) {
        if (botId == null || botId.isEmpty()) return false;
        if (message instanceof JSONArray) {
            JSONArray parts = (JSONArray) message;
            for (int i = 0; i < parts.length(); i++) {
                JSONObject segment = parts.optJSONObject(i);
                if (segment == null || !"at".equals(segment.optString("type", ""))) continue;
                JSONObject data = segment.optJSONObject("data");
                if (data != null && botId.equals(data.optString("qq", ""))) return true;
            }
        } else if (message instanceof String) {
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("(?i)\\[CQ:at,[^]]*?qq=" + java.util.regex.Pattern.quote(botId) + "(?:,|])")
                    .matcher((String) message);
            return matcher.find();
        }
        return false;
    }

    private static final class IncomingMessage {
        String text = "";
        String imageUrl = "";
        boolean hasImage;
        boolean unavailableSticker;
    }

    private static IncomingMessage parseIncoming(Object message) {
        IncomingMessage out = new IncomingMessage();
        StringBuilder text = new StringBuilder();
        if (message instanceof JSONArray) {
            JSONArray parts = (JSONArray) message;
            for (int i = 0; i < parts.length(); i++) {
                JSONObject segment = parts.optJSONObject(i);
                if (segment == null) continue;
                String type = segment.optString("type", "").toLowerCase(Locale.ROOT);
                JSONObject data = segment.optJSONObject("data");
                if ("text".equals(type) || "plain".equals(type)) {
                    if (data != null) text.append(data.optString("text", ""));
                    continue;
                }
                if ("image".equals(type) || "mface".equals(type) || "face".equals(type)) {
                    String url = data == null ? "" : decodeCQ(data.optString("url", data.optString("file", "")));
                    if (!url.isEmpty() && (url.startsWith("http://") || url.startsWith("https://")
                            || url.startsWith("data:image/") || url.startsWith("base64://"))) {
                        out.hasImage = true;
                        if (out.imageUrl.isEmpty()) out.imageUrl = url;
                    } else if ("face".equals(type) || "mface".equals(type)) {
                        out.unavailableSticker = true;
                    } else {
                        out.hasImage = true;
                    }
                }
            }
        } else if (message instanceof String) {
            String raw = (String) message;
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("(?i)\\[CQ:(image|mface|face),([^]]*)]").matcher(raw);
            while (matcher.find()) {
                String type = matcher.group(1).toLowerCase(Locale.ROOT);
                String attrs = matcher.group(2);
                String url = decodeCQ(cqValue(attrs, "url"));
                if (url.isEmpty()) url = decodeCQ(cqValue(attrs, "file"));
                if (!url.isEmpty() && (url.startsWith("http://") || url.startsWith("https://")
                        || url.startsWith("data:image/") || url.startsWith("base64://"))) {
                    out.hasImage = true;
                    if (out.imageUrl.isEmpty()) out.imageUrl = url;
                } else if ("face".equals(type) || "mface".equals(type)) {
                    out.unavailableSticker = true;
                } else {
                    out.hasImage = true;
                }
            }
            out.unavailableSticker |= raw.matches("(?is).*\\[CQ:(?:face|mface),[^]]*].*") && out.imageUrl.isEmpty();
            text.append(raw.replaceAll("(?i)\\[CQ:[^]]*]", ""));
        }
        out.text = decodeCQ(text.toString()).trim();
        return out;
    }

    private static String cqValue(String attributes, String key) {
        if (attributes == null) return "";
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?:^|,)" + java.util.regex.Pattern.quote(key) + "=([^,]*)", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(attributes);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String decodeCQ(String value) {
        if (value == null) return "";
        return value.replace("&amp;", "&").replace("&#44;", ",").replace("&#91;", "[")
                .replace("&#93;", "]").replace("&#10;", "\n").replace("&#13;", "\r").trim();
    }

    private static Bitmap downloadQqImage(Context context, String link) {
        try {
            byte[] bytes = readQqImage(context, link);
            if (bytes == null || bytes.length == 0 || bytes.length > MAX_QQ_IMAGE_BYTES) return null;
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            int sample = 1;
            while (bounds.outWidth / sample > MAX_QQ_IMAGE_EDGE || bounds.outHeight / sample > MAX_QQ_IMAGE_EDGE) sample <<= 1;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        } catch (Throwable ignored) { return null; }
    }

    private static byte[] readQqImage(Context context, String link) throws Exception {
        String value = decodeCQ(link);
        if (value.startsWith("data:image/")) {
            int comma = value.indexOf(',');
            if (comma < 0) return null;
            String encoded = value.substring(comma + 1);
            if (encoded.length() > (MAX_QQ_IMAGE_BYTES * 4L / 3L) + 8L) return null;
            byte[] bytes = Base64.getDecoder().decode(encoded);
            return bytes.length <= MAX_QQ_IMAGE_BYTES ? bytes : null;
        }
        if (value.startsWith("base64://")) {
            String encoded = value.substring("base64://".length());
            if (encoded.length() > (MAX_QQ_IMAGE_BYTES * 4L / 3L) + 8L) return null;
            byte[] bytes = Base64.getDecoder().decode(encoded);
            return bytes.length <= MAX_QQ_IMAGE_BYTES ? bytes : null;
        }
        URI current = new URI(value);
        for (int redirects = 0; redirects <= 3; redirects++) {
            if (!allowedQqImageUrl(context, current)) return null;
            HttpURLConnection connection = (HttpURLConnection) new URL(current.toString()).openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(7000);
            connection.setReadTimeout(12000);
            connection.setRequestProperty("User-Agent", "DSH-Pet/2.6.0 (QQ image reader)");
            connection.setRequestProperty("Accept", "image/*,application/octet-stream;q=0.8");
            try {
                int code = connection.getResponseCode();
                if (code >= 300 && code < 400) {
                    String location = connection.getHeaderField("Location");
                    if (location == null || redirects == 3) return null;
                    current = current.resolve(location);
                    continue;
                }
                if (code < 200 || code >= 300) return null;
                long length = connection.getContentLengthLong();
                if (length > MAX_QQ_IMAGE_BYTES) return null;
                String contentType = connection.getContentType();
                if (contentType != null && !contentType.toLowerCase(Locale.ROOT).startsWith("image/")
                        && !contentType.toLowerCase(Locale.ROOT).startsWith("application/octet-stream")) return null;
                try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    int n;
                    while ((n = in.read(buffer)) >= 0) {
                        if (out.size() + n > MAX_QQ_IMAGE_BYTES) return null;
                        out.write(buffer, 0, n);
                    }
                    return out.toByteArray();
                }
            } finally { connection.disconnect(); }
        }
        return null;
    }

    private static boolean allowedQqImageUrl(Context context, URI uri) {
        if (uri == null || uri.getHost() == null || uri.getUserInfo() != null || uri.getPort() > 65535) return false;
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"https".equals(scheme) && !"http".equals(scheme)) return false;
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        boolean qqHost = host.equals("qpic.cn") || host.endsWith(".qpic.cn")
                || host.equals("qq.com") || host.endsWith(".qq.com")
                || host.equals("qq.com.cn") || host.endsWith(".qq.com.cn")
                || host.equals("gtimg.cn") || host.endsWith(".gtimg.cn")
                || host.equals("gtimg.com") || host.endsWith(".gtimg.com")
                || host.equals("idqqimg.com") || host.endsWith(".idqqimg.com");
        String napcatHost = "";
        try { napcatHost = new URI(Cfg.load(context).str("qqOnebotUrl", "").trim()).getHost(); }
        catch (Throwable ignored) { }
        boolean napcat = napcatHost != null && !napcatHost.isEmpty() && host.equalsIgnoreCase(napcatHost)
                && !host.equals("localhost") && !host.startsWith("127.");
        return qqHost || napcat;
    }

    private static void releaseGroup(String groupId, long expectedGeneration) {
        PendingGroupMessage next = null;
        synchronized (GROUP_LOCK) {
            Long current = GROUP_BUSY.get(groupId);
            if (current != null && current == expectedGeneration) {
                ArrayDeque<PendingGroupMessage> pending = GROUP_PENDING.get(groupId);
                while (pending != null && !pending.isEmpty()) {
                    PendingGroupMessage candidate = pending.removeFirst();
                    if (isCurrent(candidate.generation)) {
                        next = candidate;
                        GROUP_BUSY.put(groupId, candidate.generation);
                        break;
                    }
                }
                if (pending != null && pending.isEmpty()) GROUP_PENDING.remove(groupId);
                if (next == null) {
                    GROUP_BUSY.remove(groupId);
                    GROUP_LAST_REPLY.put(groupId, System.currentTimeMillis());
                }
            }
        }
        if (next != null) startGroupChat(groupId, next.groupName, next.incoming,
                next.freeMode, next.groupAstrBot, next.generation);
    }

    private static String plainText(Object message) {
        if (message instanceof JSONArray) {
            JSONArray parts = (JSONArray) message;
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < parts.length(); i++) {
                JSONObject segment = parts.optJSONObject(i);
                if (segment == null) continue;
                String type = segment.optString("type", "");
                if ("image".equals(type) || "mface".equals(type)) {
                    if (out.length() > 0) out.append(' ');
                    out.append("（群友发来一张图片或表情）");
                    continue;
                }
                if (!"text".equals(type) && !"plain".equals(type)) continue;
                JSONObject data = segment.optJSONObject("data");
                if (data != null) out.append(data.optString("text", ""));
            }
            return out.toString().trim();
        }
        if (message instanceof String) {
            return ((String) message).replaceAll("(?i)\\[CQ:[^]]*]", "").trim();
        }
        return "";
    }

    private static void noteSent(String text) {
        if (text == null) return;
        String t = text.trim();
        if (t.isEmpty()) return;
        synchronized (RECENT_SENT) {
            RECENT_SENT.addLast(t);
            while (RECENT_SENT.size() > 24) RECENT_SENT.removeFirst();
        }
    }

    /**  bot 账号发言但文本不在本应用最近发送队列里 → 判定存在第二个应答客户端（双重人格来源）。 */
    private static void noteExternalResponder(JSONObject event) {
        String text = plainText(event.opt("message"));
        if (text == null) return;
        String t = text.trim();
        if (t.isEmpty()) return;
        synchronized (RECENT_SENT) {
            if (RECENT_SENT.contains(t)) return;
        }
        externalResponderAt = System.currentTimeMillis();
        emitStatus();
    }

    private static void sendReply(String userId, String reply) {
        String visible = Sticker.strip(reply).replaceAll("\\[\\[(?:动作|附件总结):[^\\]]*]]", "").trim();
        if (visible.isEmpty()) return;
        String[] bubbles = Brain.bubbles(visible);
        if (bubbles.length == 0) bubbles = new String[]{visible};
        int count = Math.min(8, bubbles.length);
        for (int i = 0; i < count; i++) {
            String part = bubbles[i];
            if (i == 7 && bubbles.length > 8) part += "\n" + String.join("\n", java.util.Arrays.copyOfRange(bubbles, 8, bubbles.length));
            if (part.length() > 3500) part = part.substring(0, 3500);
            final String text = part;
            noteSent(text);
            long delay = i == 0 ? 0 : 650L + RANDOM.nextInt(550);
            REPLIES.schedule(() -> {
                try {
                    JSONObject params = new JSONObject();
                    params.put("user_id", Long.parseLong(userId));
                    params.put("message", text);
                    params.put("auto_escape", true);
                    sendAction("send_private_msg", params, "reply");
                } catch (Throwable ignored) { }
            }, delay, TimeUnit.MILLISECONDS);
        }
    }

    private static void sendGroupReply(String groupId, String reply, long myGeneration) {
        if (reply == null || reply.trim().isEmpty()) return;
        String[] bubbles = Brain.bubbles(reply);
        if (bubbles.length == 0) bubbles = new String[]{reply.trim()};
        int count = Math.min(4, bubbles.length);
        for (int i = 0; i < count; i++) {
            String part = bubbles[i];
            if (i == 3 && bubbles.length > 4) {
                part += "\n" + String.join("\n", java.util.Arrays.copyOfRange(bubbles, 4, bubbles.length));
            }
            if (part.length() > 3500) part = part.substring(0, 3500);
            final String text = part;
            noteSent(text);
            long delay = i == 0 ? 0 : 650L + RANDOM.nextInt(550);
            REPLIES.schedule(() -> {
                if (!isCurrent(myGeneration)) return;
                try {
                    JSONObject params = new JSONObject();
                    params.put("group_id", Long.parseLong(groupId));
                    params.put("message", text);
                    params.put("auto_escape", true);
                    sendAction("send_group_msg", params, "group-reply");
                } catch (Throwable ignored) { }
            }, delay, TimeUnit.MILLISECONDS);
        }
    }

    private static void sendAction(String action, JSONObject params, String tag) {
        try {
            JSONObject request = new JSONObject();
            request.put("action", action);
            request.put("params", params);
            request.put("echo", tag + "-" + ECHO.getAndIncrement());
            sendText(request.toString());
        } catch (Throwable ignored) {
            if ("group-reply".equals(tag))
                setGroupReplyError("群回复没有送达 NapCat。请检查 QQ 连接状态后再试。");
        }
    }

    private static void setGroupReplyError(String message) {
        groupReplyError = message == null ? "" : message;
        emitStatus();
    }

    private static void sendText(String text) throws Exception {
        sendFrame(0x1, text.getBytes(StandardCharsets.UTF_8));
    }

    private static void sendFrame(int opcode, byte[] data) throws Exception {
        OutputStream os = output;
        if (os == null) throw new Exception("QQ WebSocket 尚未连接");
        synchronized (SEND_LOCK) {
            int len = data.length;
            ByteArrayOutputStream frame = new ByteArrayOutputStream(len + 14);
            frame.write(0x80 | opcode);
            if (len <= 125) frame.write(0x80 | len);
            else if (len <= 65535) {
                frame.write(0x80 | 126); frame.write((len >> 8) & 255); frame.write(len & 255);
            } else {
                frame.write(0x80 | 127);
                for (int i = 7; i >= 0; i--) frame.write((int) (((long) len) >> (8 * i)) & 255);
            }
            byte[] mask = randomBytes(4);
            frame.write(mask);
            for (int i = 0; i < len; i++) frame.write(data[i] ^ mask[i & 3]);
            os.write(frame.toByteArray());
            os.flush();
        }
    }

    private static Frame readMessage(InputStream in) throws Exception {
        Frame first = readFrame(in);
        if (first == null || first.op == 0x8 || first.op == 0x9 || first.op == 0xA) return first;
        if (first.op != 0x1 || first.fin) return first;
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        all.write(first.data);
        while (true) {
            Frame next = readFrame(in);
            if (next == null) return null;
            if (next.op == 0x9 || next.op == 0x8) return next;
            if (next.op == 0x0) all.write(next.data);
            if (next.fin) break;
            if (all.size() > 1000000) throw new Exception("QQ 消息过大");
        }
        Frame joined = new Frame();
        joined.op = 0x1;
        joined.fin = true;
        joined.data = all.toByteArray();
        return joined;
    }

    private static Frame readFrame(InputStream in) throws Exception {
        int b0 = in.read();
        if (b0 < 0) return null;
        int b1 = readByte(in);
        Frame frame = new Frame();
        frame.fin = (b0 & 0x80) != 0;
        frame.op = b0 & 0x0F;
        boolean masked = (b1 & 0x80) != 0;
        long len = b1 & 0x7F;
        if (len == 126) len = (readByte(in) << 8) | readByte(in);
        else if (len == 127) {
            len = 0;
            for (int i = 0; i < 8; i++) len = (len << 8) | readByte(in);
        }
        if (len < 0 || len > 1000000) throw new Exception("QQ 消息超过接收上限");
        byte[] mask = new byte[4];
        if (masked) readFully(in, mask);
        frame.data = new byte[(int) len];
        readFully(in, frame.data);
        if (masked) for (int i = 0; i < frame.data.length; i++) frame.data[i] ^= mask[i & 3];
        return frame;
    }

    private static int readByte(InputStream in) throws Exception {
        int value = in.read();
        if (value < 0) throw new Exception("QQ WebSocket 已断开");
        return value;
    }

    private static void readFully(InputStream in, byte[] data) throws Exception {
        int offset = 0;
        while (offset < data.length) {
            int n = in.read(data, offset, data.length - offset);
            if (n < 0) throw new Exception("QQ WebSocket 已断开");
            offset += n;
        }
    }

    private static String readLine(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (true) {
            int c = in.read();
            if (c < 0) return out.size() == 0 ? null : out.toString("UTF-8");
            if (c == '\n') {
                String line = out.toString("UTF-8");
                return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
            }
            if (out.size() > 8192) throw new Exception("NapCat 握手响应异常");
            out.write(c);
        }
    }

    private static byte[] randomBytes(int size) {
        byte[] out = new byte[size];
        synchronized (RANDOM) { RANDOM.nextBytes(out); }
        return out;
    }

    private static boolean isNumericId(String id) {
        return id != null && id.matches("[0-9]{5,12}");
    }

    static boolean validGroupIds(String value) {
        if (value == null || value.trim().isEmpty()) return true;
        String[] parts = value.trim().split("[,，;；\\s]+", -1);
        if (parts.length > 20) return false;
        for (String part : parts) if (!part.matches("[0-9]{5,15}")) return false;
        return true;
    }

    static String normalizeGroupIds(String value) {
        LinkedHashSet<String> ids = parseGroupIds(value);
        return String.join(",", ids);
    }

    private static LinkedHashSet<String> parseGroupIds(String value) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (value == null || value.trim().isEmpty()) return ids;
        for (String part : value.trim().split("[,，;；\\s]+")) {
            if (part.matches("[0-9]{5,15}")) ids.add(part);
            if (ids.size() >= 20) break;
        }
        return ids;
    }

    static boolean hasTarget(Cfg cfg) {
        if (cfg == null) return false;
        return isNumericId(cfg.str("qqAllowedUserId", "").trim())
                || !parseGroupIds(cfg.str("qqGroupIds", "")).isEmpty();
    }

    private static boolean isCurrent(long myGeneration) {
        return running && generation == myGeneration;
    }

    private static void closeSocket(Socket s) {
        if (s != null) try { s.close(); } catch (Throwable ignored) { }
        if (socket == s) {
            socket = null;
            output = null;
        }
    }

    private static void setState(String value, String error) {
        state = value == null ? "" : value;
        lastError = error == null ? "" : error;
        emitStatus();
    }

    private static void emitStatus() {
        Context c = app;
        if (c == null) return;
        try { Bus.emit("qqLink", status(c)); } catch (Throwable ignored) { }
    }

    private static final class Frame {
        int op;
        boolean fin;
        byte[] data;
    }
}
