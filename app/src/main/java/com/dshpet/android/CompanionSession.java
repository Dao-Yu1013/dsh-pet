package com.dshpet.android;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Explicitly enabled or user-approved, time-limited app exploration driven by accessibility. */
final class CompanionSession {
    private static final String FILE = "companion_session.json";
    private static final String IDLE_FILE = "free_action_idle.json";
    private static final long IDLE_REQUIRED_MS = 10 * 60 * 1000L;
    private static final long IDLE_PROMPT_GAP_MS = 30 * 60 * 1000L;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Pattern DURATION = Pattern.compile("(\\d{1,2}|半|十[一二三四五六七八九]?|[二三四五六七八九]十[一二三四五六七八九]?|一|两|二|三|四|五|六|七|八|九)\\s*(分钟|分|小时|钟头)");
    private static Runnable ticker;
    private static Context app;
    private static volatile boolean decisionPending;

    private CompanionSession() { }

    /** Unlocked, awake, accessibility connected, and no real touch for ten minutes. */
    static boolean phoneIdle(Context c) {
        if (c == null || !Cfg.load(c).b("freeAction", false) || !A11y.ready() || !A11y.awake(c)) {
            resetIdleSince(c);
            return false;
        }
        String pkg = A11y.foregroundPackage();
        if (pkg.isEmpty() || Scene.chatting() || Scene.spoken()) {
            resetIdleSince(c);
            return false;
        }
        long now = System.currentTimeMillis();
        long last = Math.max(A11y.lastPhoneInteractionAt(), Interact.lastUserAt(c));
        JSONObject idle = Sg.readObj(c, IDLE_FILE);
        long since = idle.optLong("idleSince", 0);
        if (since <= 0) since = now; // first observation after boot/unlock starts a fresh quiet timer
        else if (last > since) since = last;
        if (since != idle.optLong("idleSince", 0)) {
            try { idle.put("idleSince", since); Sg.writeObj(c, IDLE_FILE, idle); }
            catch (Throwable ignored) { }
        }
        return now - since >= IDLE_REQUIRED_MS;
    }

    /** Lightweight minute ticker calls this; no AI request is made until the phone has really stayed idle. */
    static boolean freeActionPromptDue(Context c) {
        if (c == null || !Cfg.load(c).b("freeAction", false)
                || !Cfg.load(c).aiReady() || !phoneIdle(c) || !Policy.decideFreeAction(c).ok) return false;
        if (isRunning(c) || hasPending(c)) return false;
        if (!Curiosity.hasFreeActionCandidate(c)) return false;
        JSONObject idle = Sg.readObj(c, IDLE_FILE);
        return System.currentTimeMillis() - idle.optLong("lastPromptAt", 0) >= IDLE_PROMPT_GAP_MS;
    }

    /** Recheck the pending destination after the model replies and before showing the launch notice. */
    static boolean pendingFreeActionStartAllowed(Context c) {
        return c != null && Cfg.load(c).b("freeAction", false) && !isRunning(c)
                && phoneIdle(c) && Policy.decideFreeAction(c).ok
                && Curiosity.pendingFreeAction(c) != null;
    }

    static void markFreeActionPrompt(Context c) {
        if (c == null) return;
        try {
            JSONObject idle = Sg.readObj(c, IDLE_FILE);
            idle.put("lastPromptAt", System.currentTimeMillis());
            Sg.writeObj(c, IDLE_FILE, idle);
        } catch (Throwable ignored) { }
    }

    static void resetIdleClock(Context c) {
        if (c == null) return;
        try {
            JSONObject idle = Sg.readObj(c, IDLE_FILE);
            idle.put("idleSince", System.currentTimeMillis());
            Sg.writeObj(c, IDLE_FILE, idle);
        } catch (Throwable ignored) { }
    }

    private static void resetIdleSince(Context c) {
        if (c == null) return;
        try {
            JSONObject idle = Sg.readObj(c, IDLE_FILE);
            if (idle.optLong("idleSince", 0) != 0) {
                idle.put("idleSince", 0L);
                Sg.writeObj(c, IDLE_FILE, idle);
            }
        } catch (Throwable ignored) { }
    }

    /** A user-selected, non-system app that is suitable for bounded exploration. */
    static boolean freeActionAppAllowed(Context c, JSONObject appInfo) {
        if (c == null || appInfo == null || appInfo.optBoolean("system", false)) return false;
        String pkg = appInfo.optString("pkg", "").trim().toLowerCase(java.util.Locale.ROOT);
        String name = appInfo.optString("name", "").trim().toLowerCase(java.util.Locale.ROOT);
        if (pkg.isEmpty() || pkg.equals(c.getPackageName().toLowerCase(java.util.Locale.ROOT))) return false;
        String[] blockedPackages = {"com.android.settings", "com.android.contacts", "com.android.dialer",
                "com.android.phone", "com.android.messaging", "com.android.permissioncontroller",
                "com.google.android.permissioncontroller", "com.android.packageinstaller", "com.android.vending",
                "com.tencent.mm", "com.tencent.mobileqq", "com.eg.android.alipaygphone",
                "com.android.providers.contacts", "com.taobao.taobao", "com.jingdong.app.mall",
                "com.xunmeng.pinduoduo", "com.sankuai.meituan", "me.ele", "com.sdu.didi.psnger",
                "com.unionpay", "com.tencent.wework", "com.alibaba.android.rimet", "com.bytedance.ee.lark",
                "org.telegram.messenger", "com.whatsapp", "com.discord", "com.facebook.orca",
                "com.facebook.katana", "com.instagram.android", "com.sina.weibo"};
        for (String blocked : blockedPackages) if (pkg.equals(blocked) || pkg.startsWith(blocked + ".")) return false;
        String[] blockedNames = {"设置", "通讯录", "电话", "拨号", "短信", "信息", "通讯", "聊天", "邮件", "邮箱",
                "银行", "网银", "金融", "理财", "财富", "保险", "信用", "支付", "钱包", "银联", "云闪付", "花呗", "借呗",
                "支付宝", "微信", "应用商店", "软件商店", "密码管理", "身份验证", "验证码",
                "购物", "商城", "电商", "外卖", "打车", "证券", "交易", "投资", "贷款", "淘宝", "京东",
                "拼多多", "美团", "饿了么", "滴滴", "闲鱼", "转转", "信用卡", "股票", "基金",
                "shop", "store", "bank", "wallet", "payment", "finance", "trading", "shopping",
                "amazon", "aliexpress", "ebay", "paypal", "venmo", "cash app", "coinbase", "binance", "uber", "lyft",
                "messenger", "messages", "mail", "email", "chat", "teams", "slack", "signal", "line", "kakao",
                "telegram", "whatsapp", "discord"};
        for (String blocked : blockedNames) if (name.contains(blocked)) return false;
        return true;
    }

    /** Apps allowed for autonomous exploration: an empty list means all apps that pass the local safety filter. */
    static boolean autonomousTargetAllowed(Context c, JSONObject appInfo) {
        Cfg cfg = Cfg.load(c);
        return autonomousTargetAllowed(c, appInfo, cfg, cfg.arr("freeActionApps"));
    }

    static boolean autonomousTargetAllowed(Context c, JSONObject appInfo, Cfg cfg, JSONArray selected) {
        if (!freeActionAppAllowed(c, appInfo)) return false;
        String pkg = appInfo.optString("pkg", "");
        if (selected.length() > 0) {
            for (int i = 0; i < selected.length(); i++) if (pkg.equals(selected.optString(i, ""))) return true;
            return false;
        }
        return true;
    }

    /** A generated proactive line may start a bounded session only when free action is enabled and idle is real. */
    static boolean startFreeAction(Context c, String utterance) {
        if (c == null || utterance == null || !phoneIdle(c)
                || !Policy.decideFreeAction(c).ok) return false;
        String existing = Sg.readObj(c, FILE).optString("state", "");
        if ("asked".equals(existing) || "running".equals(existing)) return false;
        String s = utterance.replaceAll("\\s+", "");
        JSONObject target = Curiosity.pendingFreeAction(c);
        if (target == null) {
            if (s.contains("吗") || s.contains("？") || s.contains("?")) return false;
            if (!(s.contains("我去") || s.contains("我想去") || s.contains("我先去"))) return false;
            if (!(s.contains("刷") || s.contains("逛") || s.contains("看看") || s.contains("玩"))) return false;
            target = findSupportedApp(c, s);
        }
        if (target == null) return false;
        JSONObject p = new JSONObject();
        try {
            int minutes = duration(s);
            p.put("pkg", target.optString("pkg"));
            p.put("name", target.optString("name"));
            p.put("minutes", Math.min(60, Math.max(1, minutes <= 0 ? 30 : minutes)));
            p.put("kind", sessionKind(c, target, s));
            ExperienceLog.record(c, "wanted", target.optString("name"), "自由行动开启且手机空闲，她决定去看看");
            ExperienceLog.record(c, "consented", target.optString("name"), "用户已开启自由行动，且满足空闲条件");
            if (launchSession(c, p, true)) {
                markFreeActionPrompt(c);
                Curiosity.consumePendingFreeAction(c);
                return true;
            }
            Curiosity.clearPendingFreeAction(c);
            ExperienceLog.record(c, "failed", target.optString("name"), "系统未能启动这次自由行动");
            PetSvc.sayPlugin(c, "我刚才没能打开「" + target.optString("name") + "」，这次就先不去了。");
            return false;
        } catch (Throwable ignored) { return false; }
    }

    /** Make sure the pet really announces the selected destination before the app is opened. */
    static String freeActionAnnouncement(Context c, String generated) {
        if (c == null || !Cfg.load(c).b("freeAction", false) || !phoneIdle(c)) {
            Curiosity.clearPendingFreeAction(c);
            return generated == null ? "" : generated;
        }
        JSONObject target = Curiosity.pendingFreeAction(c);
        if (target == null) return generated == null ? "" : generated;
        String name = target.optString("name", "").trim();
        String text = generated == null ? "" : Sticker.strip(generated).trim();
        boolean announces = !name.isEmpty() && text.contains(name)
                && hasAny(text, "我去", "我想去", "我先去")
                && hasAny(text, "看看", "逛", "刷", "玩")
                && !hasAny(text, "吗", "？", "?");
        if (announces) return generated;
        return "我想去「" + name + "」里逛一会儿，看看有没有新鲜事；你随时叫我回来。";
    }

    static boolean hasPending(Context c) {
        String state = Sg.readObj(c, FILE).optString("state", "");
        return "asked".equals(state) || "running".equals(state);
    }

    static boolean isRunning(Context c) {
        return c != null && "running".equals(Sg.readObj(c, FILE).optString("state", ""));
    }

    static void onPetServiceStopped(Context c) {
        if (c == null) return;
        if (isRunning(c)) finish(c, "completed", "桌宠服务已停止，探索会话结束", false);
        Curiosity.clearPendingFreeAction(c);
    }

    static boolean stopByDoubleTap(Context c) {
        if (!isRunning(c)) return false;
        finish(c, "completed", "用户双击桌宠主动停止", false);
        return true;
    }

    /** Adds grounded role-play context while a time-limited visit is in progress. */
    static String chatPrompt(Context c) {
        if (c == null) return "";
        JSONObject p = Sg.readObj(c, FILE);
        if (!"running".equals(p.optString("state"))) return "";
        String name = p.optString("name", "那个应用");
        String kind = p.optString("kind", "browse");
        long remaining = Math.max(0L, p.optLong("endAt", 0) - System.currentTimeMillis());
        String feeling = "game".equals(kind) ? "刚玩得正起劲" : "刚看得正起劲";
        String activity = "game".equals(kind) ? "游戏体验" : ("explore".equals(kind) ? "自主探索" : "浏览");
        return "她现在确实正在「" + name + "」里进行限时" + activity
                + "，剩余约 " + Math.max(1, (remaining + 59999L) / 60000L) + " 分钟。用户这时发来消息，像被轻轻叫住一样自然接话；"
                + "可以带一句“" + feeling + "呢，你找我呀？”，然后优先认真回答用户当前的问题，不要只顾角色扮演。"
                + "语气按角色性格调整，不要抱怨、内疚诱导或催用户；不要声称看到了尚未被截图分析确认的具体内容。"
                + "会话期间只用悬浮气泡分享实际观察到的心得，不发送其他即兴气泡；用户双击桌宠即可停止并留下心得卡片。\n";
    }

    /** A direct timed command from the user is itself explicit consent to start the session. */
    static boolean handleDirect(Context c, String text, Ai.Cb cb) {
        if (c == null || text == null || cb == null) return false;
        JSONObject current = Sg.readObj(c, FILE);
        if ("running".equals(current.optString("state"))) return false;
        String s = text.replaceAll("\\s+", "");
        if (hasAny(s, "不去", "别去", "不要", "不许", "不能", "别刷", "不刷", "不想")) return false;
        if (!(s.contains("刷") || s.contains("逛") || s.contains("看看") || s.contains("去玩"))) return false;
        int minutes = duration(s);
        JSONObject target = findSupportedApp(c, s);
        if (minutes <= 0 || target == null) return false;
        if ("asked".equals(current.optString("state"))) clear(c);
        JSONObject requested = new JSONObject();
        try {
            requested.put("pkg", target.optString("pkg"));
            requested.put("name", target.optString("name"));
            requested.put("minutes", Math.min(60, minutes));
            requested.put("kind", sessionKind(c, target, s));
            ExperienceLog.record(c, "wanted", target.optString("name"), "用户明确提出限时浏览/游戏请求");
            return startSession(c, text, requested, cb);
        } catch (Throwable ignored) { return false; }
    }

    static void resume(Context c) {
        if (c == null) return;
        JSONObject p = Sg.readObj(c, FILE);
        if (!"running".equals(p.optString("state"))) return;
        app = c.getApplicationContext();
        if (System.currentTimeMillis() >= p.optLong("endAt", 0)) {
            finish(app, "completed", "服务恢复时发现约定时长已结束", true);
            PetSvc.sayPlugin(app, completionLine(p));
        } else {
            if (p.optBoolean("autonomous", false)) PetSvc.setFreeActionScreenOn(c, true);
            startTicker();
        }
    }

    /** Called only for an actual proactive utterance that asks permission to browse a supported installed app. */
    static void captureAsk(Context c, String utterance) {
        if (c == null || utterance == null || !A11y.ready()) return;
        String s = utterance.replaceAll("\\s+", "");
        if (!(s.contains("可以吗") || s.contains("行吗") || s.contains("好不好"))) return;
        if (!(s.contains("刷") || s.contains("逛") || s.contains("看看") || s.contains("玩"))) return;
        JSONObject target = findSupportedApp(c, s);
        if (target == null) return;
        int minutes = duration(s);
        if (minutes <= 0) minutes = 10;
        JSONObject pending = new JSONObject();
        try {
            pending.put("state", "asked");
            pending.put("ts", System.currentTimeMillis());
            pending.put("pkg", target.optString("pkg"));
            pending.put("name", target.optString("name"));
            pending.put("minutes", Math.min(60, Math.max(1, minutes)));
            pending.put("kind", sessionKind(c, target, s));
            Sg.writeObj(c, FILE, pending);
            ExperienceLog.record(c, "wanted", target.optString("name"), "她想去看看这个应用");
            ExperienceLog.record(c, "asked", target.optString("name"), "已征求限时浏览同意，等待用户答复");
        } catch (Throwable ignored) { }
    }

    /** Handles only a pending consent response or stop request. */
    static boolean handle(Context c, String text, Ai.Cb cb) {
        if (c == null || text == null || cb == null) return false;
        JSONObject p = Sg.readObj(c, FILE);
        String state = p.optString("state", "");
        String s = text.trim().replaceAll("\\s+", "").replaceAll("[，。！？!?、]+$", "");
        if ("running".equals(state) && isStop(s)) {
            String reply = stopReply(p);
            finish(c, "completed", "用户要求停止", true);
            localReply(c, text, reply, cb);
            return true;
        }
        if ("running".equals(state)) {
            // Pause scrolling while she answers a message in the floating chat.
            try {
                p.put("pauseUntil", System.currentTimeMillis() + 45000L);
                Sg.writeObj(c, FILE, p);
            } catch (Throwable ignored) { }
        }
        if (!"asked".equals(state) || System.currentTimeMillis() - p.optLong("ts", 0) > 15 * 60 * 1000L) return false;
        if (isNo(s)) {
            ExperienceLog.record(c, "declined", p.optString("name"), "用户没有同意，未打开应用");
            clear(c);
            localReply(c, text, "好，那我先不去了。", cb);
            return true;
        }
        if (!isYes(s)) {
            // Any other reply is not consent; clear the one-shot ask so a later unrelated "好" cannot start it.
            clear(c);
            return false;
        }
        return startSession(c, text, p, cb);
    }

    private static boolean startSession(Context c, String text, JSONObject p, Ai.Cb cb) {
        String name = p.optString("name", "应用");
        int minutes = Math.max(1, Math.min(60, p.optInt("minutes", 10)));
        String kind = p.optString("kind", "browse");
        if (!A11y.ready()) {
            ExperienceLog.record(c, "failed", name, "无障碍服务不可用，未执行");
            clear(c);
            localReply(c, text, "我想去的，不过无障碍服务没连上，所以这次没有打开应用。", cb);
            return true;
        }
        ExperienceLog.record(c, "consented", name, "用户明确同意浏览 " + minutes + " 分钟");
        if (!launchSession(c, p, false)) {
            ExperienceLog.record(c, "failed", name, "系统没有成功打开应用");
            clear(c);
            localReply(c, text, "我没能打开「" + name + "」，这次没刷成。", cb);
            return true;
        }
        localReply(c, text, "好，那我去「" + name + "」看看，" + minutes + " 分钟后我自己回来。你还可以用悬浮聊天框找我；说“别刷了”我就停。", cb);
        return true;
    }

    private static boolean launchSession(Context c, JSONObject request, boolean autonomous) {
        String pkg = request.optString("pkg", "");
        String name = request.optString("name", "应用");
        int minutes = Math.max(1, Math.min(60, request.optInt("minutes", 10)));
        String kind = request.optString("kind", "browse");
        if (!A11y.ready()) return false;
        if (!pkg.equals(A11y.foregroundPackage()) && !A11y.openApp(c, pkg)) return false;
        JSONObject running = new JSONObject();
        try {
            JSONObject plan = new JSONObject();
            plan.put("version", 2);
            plan.put("target", pkg);
            plan.put("workflow", "game".equals(kind) ? "observe_game"
                    : ("reading".equals(kind) ? "read_content" : "scroll_content"));
            plan.put("durationMin", minutes);
            // Each action is now preceded by a fresh screen observation and a model decision.
            // Keep the feed moving at a human pace while leaving time for vision inference.
            plan.put("decisionEverySec", Math.max(30, Math.min(120,
                    Cfg.load(c).i("freeActionIntervalSec", 60))));
            plan.put("stopOnAppChange", true);
            plan.put("returnHome", true);
            running.put("state", "running"); running.put("pkg", pkg); running.put("name", name);
            running.put("kind", kind);
            running.put("autonomous", autonomous);
            running.put("startedAt", System.currentTimeMillis());
            running.put("humanTouchAtStart", Math.max(A11y.lastPhoneInteractionAt(), Interact.lastUserAt(c)));
            running.put("lastAutoActionAt", System.currentTimeMillis());
            running.put("endAt", System.currentTimeMillis() + minutes * 60000L);
            running.put("plan", plan);
            Sg.writeObj(c, FILE, running);
        } catch (Throwable ignored) { }
        JSONObject stored = Sg.readObj(c, FILE);
        if (!"running".equals(stored.optString("state"))
                || !pkg.equals(stored.optString("pkg"))) {
            try { A11y.home(); } catch (Throwable ignored) { }
            return false;
        }
        app = c.getApplicationContext();
        ExperienceLog.record(c, "started", name, autonomous
                ? "空闲时已打开应用；会限时浏览并在结束时返回桌面"
                : "应用已打开；会限时浏览并在结束时返回桌面");
        if (autonomous) PetSvc.setFreeActionScreenOn(c, true);
        startTicker();
        return true;
    }

    private static void startTicker() {
        stopTimers();
        ticker = new Runnable() {
            @Override public void run() {
                Context c = app;
                if (c == null) return;
                JSONObject p = Sg.readObj(c, FILE);
                if (!"running".equals(p.optString("state"))) return;
                if (p.optBoolean("autonomous", false) && !Cfg.load(c).b("freeAction", false)) {
                    finish(c, "completed", "自由行动开关已关闭，提前结束", true);
                    return;
                }
                JSONObject plan = p.optJSONObject("plan");
                if (plan == null) plan = new JSONObject();
                if (System.currentTimeMillis() >= p.optLong("endAt", 0)) {
                    finish(c, "completed", "到达约定时长，已返回桌面", true);
                    PetSvc.sayPlugin(c, completionLine(p));
                    return;
                }
                if (!A11y.awake(c)) {
                    finish(c, "completed", "屏幕熄灭或设备锁定，停止探索以免后台误操作", false);
                    return;
                }
                long pauseUntil = p.optLong("pauseUntil", 0);
                if (pauseUntil > System.currentTimeMillis()) {
                    MAIN.postDelayed(this, Math.min(5000L, pauseUntil - System.currentTimeMillis()));
                    return;
                }
                long lastHumanTouch = Math.max(A11y.lastPhoneInteractionAt(), Interact.lastUserAt(c));
                if (lastHumanTouch > p.optLong("humanTouchAtStart", 0)
                        && lastHumanTouch > p.optLong("lastAutoActionAt", p.optLong("startedAt", 0))) {
                    finish(c, "completed", "检测到用户重新操作手机，停止并交还控制权", false);
                    return;
                }
                String expected = p.optString("pkg", "");
                String foreground = A11y.foregroundPackage();
                if (foreground.isEmpty()) {
                    finish(c, "failed", "无法确认当前前台应用；停止操作以免误触其他应用", false);
                    return;
                }
                if (!expected.isEmpty() && !expected.equals(foreground)) {
                    boolean failedToLaunch = System.currentTimeMillis() - p.optLong("startedAt", 0) < 15000L;
                    finish(c, failedToLaunch ? "failed" : "completed",
                            failedToLaunch ? "打开后未能确认目标应用处于前台" : "前台应用已切走，结束浏览", false);
                    return;
                }
                int configuredScanSec = Cfg.load(c).i("freeActionIntervalSec", plan.optInt("decisionEverySec", 60));
                long every = Math.max(30, Math.min(120, configuredScanSec)) * 1000L;
                long next = p.optLong("nextDecisionAt", p.optLong("lastDecisionAt", 0) + every);
                if (!decisionPending && System.currentTimeMillis() >= next)
                    decideNext(c, p.optString("name"), p.optString("pkg"), p.optLong("startedAt", 0));
                MAIN.postDelayed(this, 2500L);
            }
        };
        MAIN.postDelayed(ticker, 3500L);
    }

    /** Observe the live screen, ask the configured model for one bounded decision, then verify state before acting. */
    private static synchronized void decideNext(Context c, String name, String expected, long sessionId) {
        if (decisionPending || !isRunning(c)) return;
        JSONObject current = Sg.readObj(c, FILE);
        if (current.optLong("startedAt", 0) != sessionId || !expected.equals(A11y.foregroundPackage())) return;
        decisionPending = true;
        try { current.put("lastDecisionAt", System.currentTimeMillis()); Sg.writeObj(c, FILE, current); }
        catch (Throwable ignored) { }
        String visible = A11y.currentText();
        if (visible != null) visible = visible.trim();
        // Video feeds often expose only button labels to accessibility. Those labels are
        // not evidence of what the video contains, so always ask the vision model for a
        // fresh frame during video browsing. Reader pages can still use their real text.
        boolean videoFeed = "browse".equals(current.optString("kind", "browse"));
        if (!videoFeed && visible != null && visible.length() >= 24) {
            requestDecision(c, name, expected, sessionId, visible, null);
            return;
        }
        if (!Cfg.load(c).visionReady()) {
            failObservation(c, name, expected, sessionId,
                    "没有可用的视觉模型，且无障碍文字不足以理解当前页面");
            return; // No visual/text evidence: do not pretend to understand or act blindly.
        }
        final String screenText = visible;
        A11y.screenshot(new A11y.ShotCb() {
            @Override public void ok(android.graphics.Bitmap bitmap) {
                try { requestDecision(c, name, expected, sessionId, screenText, bitmap); }
                catch (Throwable ignored) { decisionPending = false; }
            }
            @Override public void fail(String error) {
                failObservation(c, name, expected, sessionId, "截图失败：" + error);
            }
        });
    }

    private static void requestDecision(Context c, String name, String expected, long sessionId,
                                        String visible, android.graphics.Bitmap bitmap) {
        try {
            String kind = Sg.readObj(c, FILE).optString("kind", "browse");
            JSONArray messages = new JSONArray();
            messages.put(new JSONObject().put("role", "system").put("content",
                    "你是用户明确授权的限时手机探索伙伴。结合当前截图和无障碍可访问文字，每轮最多做一个小动作。"
                            + "只允许 action 为 swipe_up、swipe_down、swipe_left、swipe_right、tap_text、back、open_app、wait、stop。"
                            + "tap_text 必须填 label，且只能使用下面屏幕可访问文字里完整出现的普通导航文字；若文字不明确或只能从图片猜位置，就不要点击。"
                            + "open_app 必须填 app，且只能从用户允许的应用列表里选；不可打开列表以外的应用。"
                            + "绝不发送消息、发布内容、点赞、评论、关注、转发、收藏、购买、支付、下单、订阅、充值、删除、提交、登录、授权或修改设置；遇到这类按钮一律 wait 或 stop。"
                            + "遇到登录、验证码、权限、支付、发布、删除、隐私或其他需要用户决定的页面时，选 wait；持续无法安全继续时选 stop。"
                            + "发现值得分享且能从屏幕直接确认的内容时，把一条简短自然心得放进 insight，否则为空。"
                            + "每轮还要自行判断要不要和用户说一句：只有出现新鲜、有意思、能自然联系到用户的内容时才填 message；大多数轮次留空，避免每次识别都说话。"
                            + "可以结合确实看到的主题，偶尔好奇地问用户是否也喜欢；不要猜用户正在做什么，不要重复问，也不要为了互动硬找话题。"
                            + "心得 insight 用于记忆，message 才是此刻显示给用户的气泡，两者可以分别为空。"
                            + "不能声称已经学会或看完；只把实际观察到的一句话心得放进 insight。"
                            + "只返回 JSON：{\"action\":\"swipe_up|swipe_down|swipe_left|swipe_right|tap_text|back|open_app|wait|stop\",\"label\":\"仅 tap_text 使用\",\"app\":\"仅 open_app 使用\",\"insight\":\"...\",\"message\":\"...\"}。"));
            String task;
            if ("browse".equals(kind)) {
                task = "这是视频/内容流；结合画面或文字选择轻量浏览动作，通常可以上滑看新内容，也可在明显的内容分类导航间横滑。不要为追求动作而操作。";
            } else if ("reading".equals(kind)) {
                task = "这是阅读应用；先读当前可访问正文，必要时向下滑动阅读下一段。不要跳出书籍去购买、登录或改设置。";
            } else if ("game".equals(kind)) {
                task = "这是游戏应用；可以在屏幕文字明确且安全时点普通的开始/继续/菜单导航，也可滑动；不要进行充值、购买、广告跳转或社交操作。若游戏关键操作无法从无障碍文字确认，就只观察或停止。";
            } else {
                task = "这是用户专门允许探索的应用；根据当前内容自主探索可见页面，可安全地打开普通内容入口、浏览和返回。需要换地方时，只能从下面的允许列表选择。";
            }
            String prompt = "当前应用：" + name + "。" + task
                    + "\n允许她切换到的应用：" + freeActionTargetsDescription(c, expected)
                    + (visible == null || visible.isEmpty() ? "" : "\n屏幕可访问文字：\n" + visible.substring(0, Math.min(2600, visible.length())));
            if (bitmap == null) {
                messages.put(new JSONObject().put("role", "user").put("content", prompt));
                Ai.rawChat(c, messages, 220, 0.2, decisionCallback(c, name, expected, sessionId, visible));
            } else {
                JSONArray parts = new JSONArray();
                parts.put(new JSONObject().put("type", "text").put("text", prompt));
                parts.put(new JSONObject().put("type", "image_url").put("image_url",
                        new JSONObject().put("url", Brain.imageData(bitmap))));
                messages.put(new JSONObject().put("role", "user").put("content", parts));
                Ai.rawChatVision(c, messages, 220, 0.2, decisionCallback(c, name, expected, sessionId, visible));
            }
        } catch (Throwable ignored) { decisionPending = false; }
        finally { if (bitmap != null) try { bitmap.recycle(); } catch (Throwable ignored) { } }
    }

    private static Ai.Cb decisionCallback(Context c, String name, String expected, long sessionId, String visible) {
        return new Ai.Cb() {
            @Override public void ok(String text) {
                MAIN.post(() -> {
                    long nextDelayMs = 12000L;
                    try {
                        JSONObject p = Sg.readObj(c, FILE);
                        if (!"running".equals(p.optString("state")) || p.optLong("startedAt", 0) != sessionId
                                || !expected.equals(A11y.foregroundPackage())
                                || p.optLong("pauseUntil", 0) > System.currentTimeMillis()) return;
                        JSONObject result = parseDecision(text);
                        if (result == null) {
                            failObservation(c, name, expected, sessionId, "视觉模型没有返回可执行的浏览判断");
                            return;
                        }
                        String insight = result.optString("insight", "").trim();
                        if (!insight.isEmpty() && !insight.contains("[[不分享]]") && insight.length() <= 120)
                            rememberInsight(c, name, insight);
                        String message = result.optString("message", "").trim();
                        if (message.length() > 100) message = message.substring(0, 100);
                        if (!message.isEmpty() && !message.contains("[[不分享]]"))
                            maybeShareSessionMessage(c, message);
                        String action = result.optString("action", "wait");
                        if ("stop".equals(action)) {
                            finish(c, "completed", "模型判断当前内容需要停止浏览", true);
                        } else {
                            boolean acted = performDecisionAction(c, p, name, expected, action,
                                    result.optString("label", ""), result.optString("app", ""), visible);
                            int configured = Cfg.load(c).i("freeActionIntervalSec", 60);
                            long configuredMs = Math.max(30000, Math.min(120000, configured * 1000L));
                            nextDelayMs = acted ? configuredMs : Math.min(240000L, configuredMs * 2L);
                        }
                    } catch (Throwable ignored) { }
                    finally {
                        try {
                            JSONObject latest = Sg.readObj(c, FILE);
                            if ("running".equals(latest.optString("state"))
                                    && latest.optLong("startedAt", 0) == sessionId) {
                                latest.put("nextDecisionAt", System.currentTimeMillis() + nextDelayMs);
                                Sg.writeObj(c, FILE, latest);
                            }
                        } catch (Throwable ignored) { }
                        decisionPending = false;
                    }
                });
            }
            @Override public void fail(String error) {
                failObservation(c, name, expected, sessionId, "画面分析失败：" + error);
            }
        };
    }

    /** Execute only fixed gestures or a unique visible navigation label in the current target app. */
    private static boolean performDecisionAction(Context c, JSONObject session, String name,
                                                String expected, String action, String label, String appName, String visible) {
        if (!isRunning(c) || !expected.equals(A11y.foregroundPackage())
                || Sg.readObj(c, FILE).optLong("pauseUntil", 0) > System.currentTimeMillis()) return false;
        boolean moved = false;
        try {
            DisplayMetrics dm = c.getResources().getDisplayMetrics();
            float w = dm.widthPixels, h = dm.heightPixels;
            if (!"wait".equals(action)) {
                JSONObject latest = Sg.readObj(c, FILE);
                latest.put("lastAutoActionAt", System.currentTimeMillis());
                Sg.writeObj(c, FILE, latest);
                if (!"open_app".equals(action)) A11y.markAutonomousAction();
            }
            if ("swipe_up".equals(action)) moved = A11y.swipe(w * 0.52f, h * 0.76f, w * 0.52f, h * 0.30f, 420);
            else if ("swipe_down".equals(action)) moved = A11y.swipe(w * 0.52f, h * 0.30f, w * 0.52f, h * 0.76f, 420);
            else if ("swipe_left".equals(action)) moved = A11y.swipe(w * 0.74f, h * 0.52f, w * 0.28f, h * 0.52f, 420);
            else if ("swipe_right".equals(action)) moved = A11y.swipe(w * 0.28f, h * 0.52f, w * 0.74f, h * 0.52f, 420);
            else if ("back".equals(action)) moved = A11y.back();
            else if ("tap_text".equals(action) && safeTapLabel(label, visible))
                moved = A11y.tapTextExact(label.trim(), expected);
            else if ("open_app".equals(action)) moved = openAllowedApp(c, expected, appName);
        } catch (Throwable ignored) { }
        if ("wait".equals(action)) return false;
        if (moved) ExperienceLog.record(c, "action", name,
                "观察当前页面后执行了安全导航动作：" + action + ("tap_text".equals(action) ? "（" + label.trim() + "）" : ""));
        else ExperienceLog.record(c, "failed", name, "建议的安全导航动作未执行：" + action);
        return moved;
    }

    private static boolean safeTapLabel(String label, String visible) {
        if (label == null || visible == null) return false;
        String text = label.trim();
        if (text.isEmpty() || text.length() > 48 || !visible.contains(text)) return false;
        String low = text.toLowerCase(java.util.Locale.ROOT);
        String[] blocked = {"发送", "私信", "发布", "评论", "点赞", "喜欢", "关注", "转发", "分享", "收藏",
                "购买", "下单", "结算", "支付", "付款", "充值", "打赏", "订阅", "续费", "删除", "清空", "卸载", "安装",
                "登录", "注册", "验证码", "密码", "授权", "允许", "同意", "确认", "确定", "提交", "保存", "银行卡", "转账",
                "下一步", "继续办理", "立即开通", "开通会员", "确认订单", "去付款", "去结算", "领券", "领取", "兑换", "解锁",
                "send", "message", "post", "publish", "comment", "like", "follow", "share", "buy", "purchase", "pay", "checkout",
                "delete", "clear", "install", "uninstall", "login", "sign in", "password", "permission", "allow", "confirm", "submit", "save",
                "subscribe", "renew", "transfer", "order", "redeem", "claim", "activate", "upgrade", "membership"};
        for (String word : blocked) if (low.contains(word)) return false;
        return true;
    }

    private static String freeActionTargetsDescription(Context c, String currentPkg) {
        JSONArray apps = Fg.apps(c);
        Cfg cfg = Cfg.load(c);
        JSONArray selected = cfg.arr("freeActionApps");
        StringBuilder out = new StringBuilder();
        int shown = 0;
        java.util.HashSet<String> names = new java.util.HashSet<>();
        for (int i = 0; i < apps.length() && shown < 12; i++) {
            JSONObject appInfo = apps.optJSONObject(i);
            if (!autonomousTargetAllowed(c, appInfo, cfg, selected)) continue;
            String pkg = appInfo.optString("pkg", "");
            String name = appInfo.optString("name", "").trim();
            if (pkg.equals(currentPkg) || name.isEmpty() || !names.add(name)) continue;
            if (out.length() > 0) out.append("、");
            out.append(name);
            shown++;
        }
        return out.length() == 0 ? "无；留在当前应用" : out.toString();
    }

    /** Switch only to one uniquely named, non-system application in the explicit exploration scope. */
    private static boolean openAllowedApp(Context c, String currentPkg, String appName) {
        String wanted = appName == null ? "" : appName.trim();
        if (wanted.isEmpty() || wanted.length() > 60) return false;
        JSONObject target = null;
        JSONArray apps = Fg.apps(c);
        for (int i = 0; i < apps.length(); i++) {
            JSONObject item = apps.optJSONObject(i);
            if (item == null || !wanted.equals(item.optString("name", "").trim())
                    || !autonomousTargetAllowed(c, item)
                    || currentPkg.equals(item.optString("pkg", ""))) continue;
            if (target != null) return false;
            target = item;
        }
        if (target == null) return false;
        String pkg = target.optString("pkg", "");
        if (!A11y.openApp(c, pkg)) return false;
        JSONObject p = Sg.readObj(c, FILE);
        if (!"running".equals(p.optString("state"))) return false;
        String name = target.optString("name", "应用");
        try {
            p.put("pkg", pkg);
            p.put("name", name);
            p.put("kind", sessionKind(c, target, ""));
            p.put("lastAutoActionAt", System.currentTimeMillis());
            p.put("nextDecisionAt", System.currentTimeMillis() + 15000L);
            Sg.writeObj(c, FILE, p);
        } catch (Throwable ignored) { return false; }
        ExperienceLog.record(c, "action", name, "她按允许列表切换到了这个应用继续探索");
        return true;
    }

    /** Stop explicitly when evidence/action generation fails instead of silently idling for the whole session. */
    private static void failObservation(Context c, String name, String expected, long sessionId, String reason) {
        MAIN.post(() -> {
            try {
                JSONObject p = Sg.readObj(c, FILE);
                if (!"running".equals(p.optString("state"))
                        || p.optLong("startedAt", 0) != sessionId
                        || !expected.equals(p.optString("pkg"))) return;
                decisionPending = false;
                finish(c, "failed", reason, false);
                PetSvc.sayPlugin(c, "我刚才没能可靠看清这个页面，先停下来了，没有继续乱操作。你可以检查屏幕识别或视觉模型后再让我继续。");
            } catch (Throwable ignored) { decisionPending = false; }
        });
    }

    private static JSONObject parseDecision(String raw) {
        if (raw == null) return null;
        String s = raw.trim().replaceAll("^```(?:json)?\\s*", "").replaceAll("\\s*```$", "");
        int start = s.indexOf('{'), end = s.lastIndexOf('}');
        if (start >= 0 && end > start) try {
            JSONObject o = new JSONObject(s.substring(start, end + 1));
            String action = o.optString("action", "wait");
            if (!allowedAction(action)) return null;
            return o;
        } catch (Throwable ignored) { }
        // Some compatible chat endpoints wrap JSON in prose or emit a bare action.
        // Recover only an explicit allowed action; never infer a gesture from free text.
        java.util.regex.Matcher action = Pattern.compile("(?i)[\\\"']?action[\\\"']?\\s*[:=]\\s*[\\\"']?(swipe_up|swipe_down|swipe_left|swipe_right|tap_text|back|open_app|wait|stop)").matcher(s);
        boolean found = action.find();
        if (!found) {
            action = Pattern.compile("(?i)\\b(swipe_up|swipe_down|swipe_left|swipe_right|tap_text|back|open_app|wait|stop)\\b").matcher(s);
            found = action.find();
        }
        if (!found) return null;
        String value = action.group(action.groupCount());
        try { return new JSONObject().put("action", value); }
        catch (Throwable ignored) { return null; }
    }

    private static boolean allowedAction(String action) {
        return "swipe_up".equals(action) || "swipe_down".equals(action)
                || "swipe_left".equals(action) || "swipe_right".equals(action)
                || "tap_text".equals(action) || "back".equals(action)
                || "open_app".equals(action)
                || "wait".equals(action) || "stop".equals(action);
    }

    private static void analyze(Context c, String name) {
        JSONObject p = Sg.readObj(c, FILE);
        if (!"running".equals(p.optString("state"))) return;
        String foreground = A11y.foregroundPackage();
        if (foreground.isEmpty() || !foreground.equals(p.optString("pkg", ""))) return;
        String visibleText = A11y.currentText();
        if (visibleText != null && visibleText.trim().length() >= 24) {
            analyzeVisibleText(c, name, visibleText.trim());
            return;
        }
        A11y.screenshot(new A11y.ShotCb() {
            @Override public void ok(android.graphics.Bitmap bitmap) {
                try {
                    JSONArray msgs = new JSONArray();
                    msgs.put(new JSONObject().put("role", "system").put("content",
                            "只根据这张当前屏幕里实际可见的内容，分享一个简短、具体、像朋友随口说出的心得。"
                                    + "不要复述整段画面，不要编造；画面没内容或看不清时只输出[[不分享]]。"));
                    JSONArray parts = new JSONArray();
                    parts.put(new JSONObject().put("type", "text").put("text", "用一句话说说你对当前画面最有意思的发现或感受；没有值得分享的就输出[[不分享]]。"));
                    parts.put(new JSONObject().put("type", "image_url").put("image_url",
                            new JSONObject().put("url", Brain.imageData(bitmap))));
                    msgs.put(new JSONObject().put("role", "user").put("content", parts));
                    Ai.rawChatVision(c, msgs, 140, new Ai.Cb() {
                        @Override public void ok(String text) {
                            if (text != null && !text.trim().isEmpty() && !text.contains("[[不分享]]")) {
                                storeInsight(c, name, text.trim());
                            }
                        }
                        @Override public void fail(String error) {
                            ExperienceLog.record(c, "failed", name, "截屏已取得，但视觉分析失败；未记录为已了解");
                        }
                    });
                } catch (Throwable ignored) {
                    ExperienceLog.record(c, "failed", name, "截图分析请求未能提交");
                } finally { try { bitmap.recycle(); } catch (Throwable ignored) { } }
            }
            @Override public void fail(String error) {
                ExperienceLog.record(c, "failed", name, "未能取得截图；未记录为已了解");
            }
        });
    }

    private static void analyzeVisibleText(Context c, String name, String visibleText) {
        JSONArray msgs = new JSONArray();
        try {
            msgs.put(new JSONObject().put("role", "system").put("content",
                    "你正和用户一起浏览当前应用。下面是无障碍读取到的当前前台界面文字，可能包含按钮和描述。"
                            + "只根据材料分享一个具体、简短、自然的心得；不要编造，若没有实质内容只输出[[不分享]]。"));
            msgs.put(new JSONObject().put("role", "user").put("content",
                    visibleText.length() > 2200 ? visibleText.substring(0, 2200) : visibleText));
        } catch (Throwable ignored) { return; }
        Ai.rawChat(c, msgs, 120, 0.7, new Ai.Cb() {
            @Override public void ok(String text) {
                if (text != null && !text.trim().isEmpty() && !text.contains("[[不分享]]"))
                    storeInsight(c, name, text.trim());
            }
            @Override public void fail(String error) { }
        });
    }

    private static void storeInsight(Context c, String name, String insight) {
        rememberInsight(c, name, insight);
        PetSvc.showSessionInsight(c, insight);
    }

    private static void rememberInsight(Context c, String name, String insight) {
        if (!isRunning(c)) return;
        ExperienceLog.record(c, "observed", name, insight);
        InterestProfile.learned(c, name, insight);
        appendInsight(c, name, insight);
    }

    /** Keep observation-driven conversation occasional even if a model overproduces messages. */
    private static synchronized void maybeShareSessionMessage(Context c, String message) {
        if (!isRunning(c)) return;
        JSONObject p = Sg.readObj(c, FILE);
        long now = System.currentTimeMillis();
        if (now - p.optLong("lastMessageAt", 0) < 60_000L) return;
        try { p.put("lastMessageAt", now); Sg.writeObj(c, FILE, p); }
        catch (Throwable ignored) { return; }
        PetSvc.showSessionInsight(c, message);
    }

    private static void finish(Context c, String state, String reason, boolean returnHome) {
        JSONObject p = Sg.readObj(c, FILE);
        String name = p.optString("name", "应用");
        stopTimers();
        PetSvc.setFreeActionScreenOn(c, false);
        if (returnHome) try { A11y.home(); } catch (Throwable ignored) { }
        JSONArray insights = p.optJSONArray("insights");
        if (insights != null && insights.length() > 0) publishInsightCard(c, name, insights);
        try { Sg.writeObj(c, FILE, new JSONObject().put("state", state).put("name", name)); }
        catch (Throwable ignored) { }
        ExperienceLog.record(c, "completed".equals(state) ? "completed" : "failed", name, reason);
        if (insights != null && insights.length() > 0) Diary.maybeGenerate(c);
    }

    private static synchronized void appendInsight(Context c, String name, String insight) {
        try {
            JSONObject p = Sg.readObj(c, FILE);
            if (!"running".equals(p.optString("state")) || !name.equals(p.optString("name"))) return;
            JSONArray old = p.optJSONArray("insights");
            JSONArray next = new JSONArray();
            if (old != null) for (int i = Math.max(0, old.length() - 11); i < old.length(); i++) {
                String prior = old.optString(i, "");
                if (!prior.equals(insight)) next.put(prior);
            }
            next.put(insight);
            p.put("insights", next);
            Sg.writeObj(c, FILE, p);
        } catch (Throwable ignored) { }
    }

    private static void publishInsightCard(Context c, String name, JSONArray insights) {
        try {
            JSONArray items = new JSONArray();
            StringBuilder remembered = new StringBuilder();
            for (int i = 0; i < insights.length(); i++) {
                String item = insights.optString(i, "").trim();
                if (item.isEmpty()) continue;
                items.put(item);
                if (remembered.length() > 0) remembered.append("；");
                remembered.append(item);
            }
            if (items.length() == 0) return;
            String title = "和你一起看「" + name + "」时的心得";
            String content = "刚才一起看了「" + name + "」，我记下了 " + items.length()
                    + " 条心得。点开卡片看看，想聊哪一条都可以。";
            JSONObject extra = new JSONObject();
            extra.put("cardType", "session_insights");
            extra.put("cardTitle", title);
            extra.put("cardItems", items);
            Data.chatAppend(c, "assistant", content, "", "", extra);
            JSONObject event = new JSONObject();
            event.put("role", "assistant"); event.put("content", content);
            event.put("ts", System.currentTimeMillis()); event.put("cardType", "session_insights");
            event.put("cardTitle", title); event.put("cardItems", items);
            Bus.emit("sessionInsightCard", event);
            ExperienceLog.record(c, "observed", name, remembered.toString());
        } catch (Throwable ignored) { }
    }

    private static String stopReply(JSONObject session) {
        String kind = session.optString("kind", "browse");
        long total = Math.max(1L, session.optLong("endAt", 0) - session.optLong("startedAt", 0));
        long elapsed = Math.max(0L, System.currentTimeMillis() - session.optLong("startedAt", 0));
        boolean engaged = elapsed * 3L >= total;
        if ("game".equals(kind)) return engaged
                ? "刚玩得正起劲呢，不过你叫我我就先停啦。怎么啦？"
                : "好啦，我先不玩了。你找我呀？";
        return engaged
                ? "正看得高兴呢，不过我先停下来陪你。怎么啦？"
                : "好，我先停下来听你说。怎么啦？";
    }

    private static String completionLine(JSONObject session) {
        String kind = session.optString("kind", "browse");
        long total = Math.max(1L, session.optLong("endAt", 0) - session.optLong("startedAt", 0));
        long elapsed = Math.max(0L, System.currentTimeMillis() - session.optLong("startedAt", 0));
        boolean engaged = elapsed * 3L >= total;
        if ("game".equals(kind)) return engaged
                ? "时间到啦，我还没玩够呢……不过说好到点就回来，我回来陪你啦。"
                : "游戏时间到啦，我先回来找你。";
        return engaged
                ? "时间过得好快，我还意犹未尽呢……先回来陪你啦。"
                : "我约好的浏览时间到啦，先回来找你。";
    }

    private static void stopTimers() {
        if (ticker != null) MAIN.removeCallbacks(ticker);
        ticker = null;
    }

    private static void clear(Context c) {
        stopTimers();
        try { Sg.writeObj(c, FILE, new JSONObject()); } catch (Throwable ignored) { }
    }

    private static JSONObject findSupportedApp(Context c, String text) {
        JSONArray apps = Fg.apps(c);
        JSONArray selectedApps = Cfg.load(c).arr("freeActionApps");
        for (int i = 0; i < apps.length(); i++) {
            JSONObject a = apps.optJSONObject(i);
            if (!freeActionAppAllowed(c, a)) continue;
            String pkg = a.optString("pkg", "").toLowerCase(java.util.Locale.ROOT);
            String name = a.optString("name", "");
            String low = name.toLowerCase(java.util.Locale.ROOT);
            boolean selected = false;
            for (int j = 0; j < selectedApps.length(); j++)
                if (pkg.equals(selectedApps.optString(j, "").toLowerCase(java.util.Locale.ROOT))) selected = true;
            boolean readingIntent = hasAny(text, "小说", "阅读", "读书", "看书");
            boolean readingApp = hasAny(low, "小说", "阅读", "读书", "书城", "阅读器", "kindle", "reader", "novel", "book");
            boolean curated = Scene.looksLikeVideoPkg(pkg) || Cfg.load(c).isGame(pkg)
                    || (readingIntent && (readingApp || hasAny(pkg, "reader", "novel", "book", "kindle")))
                    || hasAny(low, "抖音", "哔哩", "b站", "视频", "快手", "西瓜", "优酷", "爱奇艺", "腾讯视频", "小红书", "游戏");
            boolean restricted = Cfg.load(c).b("freeAction", false) && selectedApps.length() > 0;
            boolean supported = restricted ? selected : curated;
            if (supported && !name.isEmpty() && (text.contains(name) || aliasMatch(c, text, pkg, low))) return a;
        }
        return null;
    }

    private static boolean aliasMatch(Context c, String s, String pkg, String name) {
        return (hasAny(s, "小说", "阅读", "读书", "看书")
                    && (hasAny(pkg, "reader", "novel", "book", "kindle")
                        || hasAny(name, "小说", "阅读", "读书", "书城", "阅读器", "kindle", "reader", "novel", "book")))
                || (s.contains("抖音") && (pkg.contains("douyin") || name.contains("抖音")))
                || ((s.contains("哔站") || s.contains("b站")) && (pkg.contains("bilibili") || name.contains("哔哩")))
                || (s.contains("快手") && (pkg.contains("kuaishou") || name.contains("快手")))
                || (s.contains("视频") && Scene.looksLikeVideoPkg(pkg))
                || (s.contains("游戏") && Cfg.load(c).isGame(pkg));
    }

    private static String sessionKind(Context c, JSONObject target, String request) {
        Cfg cfg = Cfg.load(c);
        String pkg = target.optString("pkg", "").toLowerCase(java.util.Locale.ROOT);
        String name = target.optString("name", "").toLowerCase(java.util.Locale.ROOT);
        if (cfg.isGame(pkg)) return "game";
        if (hasAny(request, "小说", "阅读", "读书", "看书")
                || hasAny(name, "小说", "阅读", "读书", "书城", "阅读器", "kindle", "reader", "novel", "book")) return "reading";
        if (Scene.looksLikeVideoPkg(pkg)
                || hasAny(name, "抖音", "哔哩", "b站", "视频", "快手", "西瓜", "优酷", "爱奇艺", "腾讯视频", "小红书")) return "browse";
        if (isSelectedFreeActionApp(c, target.optString("pkg", ""))) return "explore";
        return "browse";
    }

    private static boolean isSelectedFreeActionApp(Context c, String pkg) {
        JSONArray selected = Cfg.load(c).arr("freeActionApps");
        for (int i = 0; i < selected.length(); i++) if (pkg.equals(selected.optString(i, ""))) return true;
        return false;
    }

    private static int duration(String text) {
        Matcher m = DURATION.matcher(text);
        if (!m.find()) return 0;
        String n = m.group(1);
        if ("半".equals(n)) return 30;
        int value;
        try { value = Integer.parseInt(n); }
        catch (Throwable ignored) { value = chineseNumber(n); }
        if (value <= 0) return 0;
        String unit = m.group(2);
        return unit.contains("小时") || unit.contains("钟头") ? value * 60 : value;
    }

    private static int chineseNumber(String s) {
        if (s == null || s.isEmpty()) return 0;
        if (s.startsWith("十")) return 10 + (s.length() > 1 ? chineseDigit(s.charAt(1)) : 0);
        int at = s.indexOf('十');
        if (at > 0) return chineseDigit(s.charAt(0)) * 10 + (at + 1 < s.length() ? chineseDigit(s.charAt(at + 1)) : 0);
        return chineseDigit(s.charAt(0));
    }

    private static int chineseDigit(char c) {
        switch (c) {
            case '一': case '两': return 1;
            case '二': return 2; case '三': return 3; case '四': return 4; case '五': return 5;
            case '六': return 6; case '七': return 7; case '八': return 8; case '九': return 9;
            default: return 0;
        }
    }

    private static boolean isYes(String s) {
        return "好".equals(s) || "行".equals(s) || "可以".equals(s) || "同意".equals(s)
                || "好啊".equals(s) || "好呀".equals(s) || "去吧".equals(s) || "准了".equals(s)
                || "你去".equals(s) || s.startsWith("可以，") || s.startsWith("好啊，")
                || s.startsWith("好呀，") || s.startsWith("去吧，") || s.startsWith("同意，");
    }
    private static boolean isNo(String s) { return hasAny(s, "不行", "不可以", "别去", "不准", "算了", "不用", "不要", "不去了", "不想"); }
    private static boolean isStop(String s) { return hasAny(s, "别刷了", "停下来", "停止", "回来", "先别看", "不刷了"); }
    private static boolean hasAny(String s, String... xs) { for (String x : xs) if (s.contains(x)) return true; return false; }

    private static void localReply(Context c, String user, String answer, Ai.Cb cb) {
        Data.chatAppend(c, "user", user);
        Data.chatAppend(c, "assistant", answer);
        cb.ok(answer);
    }
}
