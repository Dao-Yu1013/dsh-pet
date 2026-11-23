package com.dshpet.android;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.Calendar;

/** 主动行为策略：打扰等级、每日预算、场景规则、可解释记录。 */
public final class Policy {
    public static final class Decision {
        public final boolean ok;
        public final int level;
        public final String reason;
        public final String mode;

        Decision(boolean ok, int level, String reason, String mode) {
            this.ok = ok;
            this.level = level;
            this.reason = reason;
            this.mode = mode;
        }
    }

    private static final Object LOCK = new Object();
    /** 被拦截日志聚合：同一类型/原因 30 分钟内只落一条，避免每 20 分钟写一次盘。 */
    private static String lastBlockKey = "";
    private static long lastBlockAt = 0;
    private static int skippedBlocks = 0;

    private Policy() {
    }

    /** 判断一次主动行为是否放行。type: chat/reminder/plan/travel/parcel/health/music/ad/focus/geo/plugin */
    public static Decision decide(Context c, String type, int wantLevel) {
        Cfg cfg = Cfg.load(c);
        int want = Math.max(1, Math.min(3, wantLevel));
        boolean important = isImportant(type);
        String mode = mode(c, cfg);

        if (!catEnabled(cfg, type)) return new Decision(false, 0, "该类主动已关闭", mode);

        // 一键静默（永久或定时）：按界面的承诺只保留重要提醒的气泡，不再朗读
        if (quietNow(cfg)) {
            if (!important) return new Decision(false, 0, "全局静音", mode);
            return new Decision(true, 1, "全局静音，只保留重要提醒气泡", "quiet");
        }

        // 游戏：社交类完全静默，重要提醒只出气泡，不朗读；健康提示不拦
        if ("game".equals(mode)) {
            if (!important && !"health".equals(type)) return new Decision(false, 0, "游戏模式静默", mode);
            return new Decision(true, 1, "游戏模式只保留重要提醒气泡", mode);
        }

        int level = want;
        StringBuilder why = new StringBuilder();

        // 专注流程本身的「开始/结束」提示：属于功能反馈，不按普通社交拦掉
        if ("focus".equals(type)) {
            if (level > 1) level = 1;
            why.append("专注流程提示只出气泡；");
        } else if ("focus".equals(mode) && cfg.b("polFocusImportant", true)) {
            // 专注：默认只保留重要提醒气泡；健康提示例外
            if (!important && !"health".equals(type)) return new Decision(false, 0, "专注模式只放行重要提醒", mode);
            if (level > 1) level = 1;
            why.append("专注模式只保留重要提醒气泡；");
        }

        // 视频：社交类静默，重要提醒只出气泡（不额外轮询，读媒体会话现有状态）
        if (!"focus".equals(mode) && !"focus".equals(type) && Scene.video(c)) {
            if (!important && !"health".equals(type)) {
                return new Decision(false, 0, "正在看视频，先不打扰", mode);
            }
            if (level > 1) level = 1;
            why.append("视频中只保留重要提醒气泡；");
        }

        // 夜间：社交类不再放行；重要提醒按用户设置只出气泡或保留原等级
        if ("night".equals(mode)) {
            if (!important && !"health".equals(type)) return new Decision(false, 0, "夜间不闲聊", mode);
            if (cfg.b("polNightBubbleOnly", true) && level > 1) {
                level = 1;
                why.append("夜间重要提醒只显示气泡；");
            }
        }

        // 听小说/有声书时不适合主动闲聊；用户正在聊天应用里也不插话
        if (!important && !"health".equals(type)) {
            if (Scene.spoken()) return new Decision(false, 0, "正在听小说/有声书，先不打扰", mode);
            if (Scene.chatting()) return new Decision(false, 0, "你正在聊天，先不打扰", mode);
        }

        // 心情低落：尊重「参考心情」开关，只降低等级，不直接封死
        if (cfg.b("polMoodAffect", true) && "low".equals(MoodLog.recent(c))) {
            if (!important && level > 1) {
                level = 1;
                why.append("心情低落，降低打扰；");
            }
        }

        if (!important && !"greet".equals(type)) {
            JSONObject st = state(c);
            int dayCap = Intimacy.dayCap(c);
            int hour = nowHour();
            // 健康固定每小时 12 次上限，不占闲聊预算，也不受全天平均分配限制
            if ("health".equals(type)) {
                int hc = st.optInt("healthHour", -1) == hour ? st.optInt("healthHourCount", 0) : 0;
                if (hc >= 12) return new Decision(false, 0, "健康提醒这一小时够了（" + hc + "/12）", mode);
                why.append("健康独立配额 ").append(hc + 1).append("/12；");
                return new Decision(true, level, why.toString(), mode);
            }
            // 屏幕闲聊按 proactiveMin 的节奏出现，只保留「连续主动没回应就暂停」
            if ("chat".equals(type)) {
                int unanswered = Interact.unansweredChat(c);
                long blockedUntil = Interact.chatBlockedUntil(c);
                if (blockedUntil > System.currentTimeMillis()) {
                    if (blockedUntil == Long.MAX_VALUE) {
                        return new Decision(false, 0, "连续主动没得到回应，先安静等他开口", mode);
                    }
                    return new Decision(false, 0, "连续 3 条没回应，暂停闲聊 2 小时", mode);
                }
                if (unanswered > 0) {
                    why.append("已连续 ").append(unanswered).append(" 条没回应；");
                }
                why.append("屏幕闲聊按自己的节奏；");
                return new Decision(true, level, why.toString(), mode);
            }
            // 只有动作碎碎念还有配额，且随亲密度提高
            if ("action".equals(type)) {
                int cap = Intimacy.actionCap(dayCap);
                int ac = st.optInt("actionHour", -1) == hour ? st.optInt("actionHourCount", 0) : 0;
                if (ac >= cap) return new Decision(false, 0, "动作碎碎念这一小时够了（" + ac + "/" + cap + "）", mode);
                why.append("动作碎碎念 ").append(ac + 1).append('/').append(cap).append("；");
                return new Decision(true, level, why.toString(), mode);
            }
            // 音乐、天气、位置、插件、应用感知、广告都按各自的节奏，
            // 不再吃「每天/每小时主动预算」，避免被平均分配误拦
            why.append("按自己的节奏；");
            return new Decision(true, level, why.toString(), mode);
        } else if ("greet".equals(type)) {
            // 问候有自己的 4 小时/每天 2 次限制，不占闲聊预算，避免一开应用就被额度挡住
            why.append("问候不占闲聊预算；");
        } else {
            why.append("重要提醒不占预算；");
        }
        if (why.length() == 0) why.append("符合当前场景；");
        why.setLength(why.length() - 1);
        return new Decision(true, level, why.toString(), mode);
    }

    /** 放行后计数并写日志。 */
    public static void record(Context c, String type, int level, String reason, String text) {
        record(c, type, level, reason, text, 0);
    }

    /** AI 返回用量时一并记录准确 token 数；本地决策仍记为 0（界面不显示）。 */
    public static void record(Context c, String type, int level, String reason, String text, int tokens) {
        synchronized (LOCK) {
            JSONObject st = state(c);
            String day = dayKey();
            if (!day.equals(st.optString("day", ""))) {
                st = new JSONObject();
                try {
                    st.put("day", day);
                    st.put("total", 0);
                    st.put("byCat", new JSONObject());
                    st.put("hour", -1);
                    st.put("hourCount", 0);
                    st.put("healthHour", -1);
                    st.put("healthHourCount", 0);
                    st.put("musicHour", -1);
                    st.put("musicHourCount", 0);
                    st.put("actionHour", -1);
                    st.put("actionHourCount", 0);
                    st.put("v", 3);
                } catch (Throwable ignored) {
                }
            }
            int hour = nowHour();
            try {
                boolean important = isImportant(type);
                if ("music".equals(type)) {
                    int oldMusicHour = st.optInt("musicHour", -1);
                    int musicHourCount = oldMusicHour == hour ? st.optInt("musicHourCount", 0) : 0;
                    st.put("musicHour", hour);
                    st.put("musicHourCount", musicHourCount + 1);
                    JSONObject by = st.optJSONObject("byCat");
                    if (by == null) by = new JSONObject();
                    by.put("music", by.optInt("music", 0) + 1);
                    st.put("byCat", by);
                    // 音乐不增加 total/hourCount：它有自己的节奏
                } else if ("health".equals(type)) {
                    int oldHealthHour = st.optInt("healthHour", -1);
                    int healthHourCount = oldHealthHour == hour ? st.optInt("healthHourCount", 0) : 0;
                    st.put("healthHour", hour);
                    st.put("healthHourCount", healthHourCount + 1);
                    JSONObject by = st.optJSONObject("byCat");
                    if (by == null) by = new JSONObject();
                    by.put("health", by.optInt("health", 0) + 1);
                    st.put("byCat", by);
                    // 健康也不占闲聊预算
                } else if ("action".equals(type)) {
                    int oldActionHour = st.optInt("actionHour", -1);
                    int actionHourCount = oldActionHour == hour ? st.optInt("actionHourCount", 0) : 0;
                    st.put("actionHour", hour);
                    st.put("actionHourCount", actionHourCount + 1);
                    JSONObject by = st.optJSONObject("byCat");
                    if (by == null) by = new JSONObject();
                    by.put("action", by.optInt("action", 0) + 1);
                    st.put("byCat", by);
                } else if ("chat".equals(type)) {
                    JSONObject by = st.optJSONObject("byCat");
                    if (by == null) by = new JSONObject();
                    by.put("chat", by.optInt("chat", 0) + 1);
                    st.put("byCat", by);
                    // 屏幕闲聊不占日/小时预算，只记录最近一次时间
                } else if (!important && !"greet".equals(type)) {
                    int total = st.optInt("total", 0) + 1;
                    st.put("total", total);
                    int oldHour = st.optInt("hour", -1);
                    int hc = oldHour == hour ? st.optInt("hourCount", 0) : 0;
                    st.put("hour", hour);
                    st.put("hourCount", hc + 1);
                    JSONObject by = st.optJSONObject("byCat");
                    if (by == null) by = new JSONObject();
                    by.put(type, by.optInt(type, 0) + 1);
                    st.put("byCat", by);
                }
                if ("chat".equals(type)) st.put("lastChatAt", System.currentTimeMillis());
                if ("weather".equals(type)) st.put("lastWeatherAt", System.currentTimeMillis());
                st.put("lastAt", System.currentTimeMillis());
            } catch (Throwable ignored) {
            }
            Sg.writeObj(c, "policy.json", st);

            JSONObject log = new JSONObject();
            try {
                log.put("ts", System.currentTimeMillis());
                log.put("type", type);
                log.put("level", level);
                log.put("ok", true);
                log.put("why", reason);
                log.put("text", text == null ? "" : text.length() > 80 ? text.substring(0, 80) : text);
                log.put("mode", mode(c, Cfg.load(c)));
                if (tokens > 0) log.put("tokens", tokens);
            } catch (Throwable ignored) {
            }
            appendLog(c, log);
        }
    }

    /** 被拦截时也记录，方便解释“为什么没说”。 */
    public static void recordBlocked(Context c, String type, String reason, String text) {
        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            // 原因里的数字会变（例如间隔 87/88 分钟），聚合时只看括号前的主因
            String main = reason == null ? "" : reason.split("（")[0];
            String key = (type == null ? "" : type) + "|" + main;
            if (key.equals(lastBlockKey) && now - lastBlockAt < 30 * 60000L) {
                skippedBlocks++;
                return;
            }
            lastBlockKey = key;
            lastBlockAt = now;
            JSONObject log = new JSONObject();
            try {
                log.put("ts", now);
                log.put("type", type);
                log.put("level", 0);
                log.put("ok", false);
                log.put("why", reason);
                log.put("text", text == null ? "" : text.length() > 60 ? text.substring(0, 60) : text);
                log.put("mode", mode(c, Cfg.load(c)));
                if (skippedBlocks > 0) {
                    log.put("skipped", skippedBlocks);
                    skippedBlocks = 0;
                }
            } catch (Throwable ignored) {
            }
            appendLog(c, log);
        }
    }

    private static void appendLog(Context c, JSONObject o) {
        try {
            File f = Sg.file(c, "proactive.jsonl");
            Sg.appendLine(c, "proactive.jsonl", o);
            if (f.length() > 240 * 1024) {
                String[] lines = Sg.read(f, "").split("\n");
                StringBuilder sb = new StringBuilder();
                int start = Math.max(0, lines.length - 400);
                for (int k = start; k < lines.length; k++) {
                    if (lines[k].trim().isEmpty()) continue;
                    sb.append(lines[k]).append('\n');
                }
                Sg.write(f, sb.toString());
            }
        } catch (Throwable ignored) {
        }
    }

    public static JSONObject state(Context c) {
        JSONObject st = Sg.readObj(c, "policy.json");
        try {
            if (st.optInt("v", 0) < 3) {
                // v3：小时上限与闲聊间隔改为自动分配，并清掉旧口径累积的计数
                st.put("total", 0);
                st.put("hourCount", 0);
                st.put("healthHourCount", 0);
                st.put("byCat", new JSONObject());
                st.put("v", 3);
                Sg.writeObj(c, "policy.json", st);
                Sg.write(Sg.file(c, "proactive.jsonl"), "");
            }
        } catch (Throwable ignored) {
        }
        return st;
    }

    public static JSONObject summary(Context c) {
        Cfg cfg = Cfg.load(c);
        JSONObject o = new JSONObject();
        try {
            JSONObject st = state(c);
            String day = dayKey();
            boolean today = day.equals(st.optString("day", ""));
            o.put("day", day);
            o.put("total", today ? st.optInt("total", 0) : 0);
            // 主动次数改为按亲密度自动决定，这里同步回传 / 每小时上限与闲聊间隔
            int dayCap = Intimacy.dayCap(c);
            o.put("budgetDay", dayCap);
            o.put("budgetHour", Intimacy.hourCap(dayCap));
            // 现在唯一还有配额的主动类型是动作碎碎念，随亲密度提高
            o.put("actionCap", Intimacy.actionCap(dayCap));
            o.put("actionHour", st.optInt("actionHour", -1) == nowHour() ? st.optInt("actionHourCount", 0) : 0);
            // 屏幕闲聊现在跟主动检查间隔（proactiveMin）一致，不再有额外的 90 分钟闲聊间隔
            o.put("chatGapMin", Math.max(3, Math.min(120, cfg.i("proactiveMin", 20))));
            o.put("expected", expectedByNow(dayCap));
            o.put("intimacy", Intimacy.summary(c));
            o.put("byCat", today && st.optJSONObject("byCat") != null ? st.optJSONObject("byCat") : new JSONObject());
            o.put("hourCount", st.optInt("hour", -1) == nowHour() ? st.optInt("hourCount", 0) : 0);
            o.put("mode", mode(c, cfg));
            o.put("quiet", quietNow(cfg));
            o.put("quietUntil", quietUntil(c));
            o.put("focus", focusRunning());
            o.put("game", gameNow(c, cfg));
            o.put("night", nightNow(cfg));
            o.put("mood", MoodLog.recent(c));
            o.put("preview", preview(c));
            o.put("sources", sources(c));
            o.put("log", recentLog(c, 60));
        } catch (Throwable ignored) {
        }
        return o;
    }

    /**
     * 给设置页的解释：如果现在想闲聊，会不会开口、为什么、下一次大约什么时候再看。
     * 只做本地判断，不发消息、不记账。
     */
    public static JSONObject preview(Context c) {
        JSONObject o = new JSONObject();
        try {
            Cfg cfg = Cfg.load(c);
            String mode = mode(c, cfg);
            Decision d = decide(c, "chat", 2);
            int value = Value.chatScore(c);
            boolean ok = d.ok && value >= Value.localScore(c);
            String reason;
            if (!d.ok) reason = d.reason;
            else if (value < Value.localScore(c)) reason = "暂时没有明确的开口理由（价值 " + value + "）";
            else reason = d.reason;
            o.put("ok", ok);
            o.put("reason", reason);
            o.put("mode", mode);
            o.put("value", value);
            o.put("unanswered", Interact.unansweredChat(c));
            o.put("quiet", quietNow(cfg));
            o.put("video", Scene.video(c));
            int next = Math.max(3, Math.min(120, cfg.i("proactiveMin", 20)));
            if (ok) next = 0;
            else if (nightNow(cfg)) next = Math.max(5, Math.min(480, nightRemainingMin(cfg) + 5));
            else if (Scene.video(c) || "game".equals(mode) || "focus".equals(mode)) next = Math.max(30, next);
            else if (Interact.isUserTalking(c)) next = Math.max(30, next);
            else if (quietNow(cfg)) next = Math.max(30, next);
            o.put("nextMin", next);
        } catch (Throwable ignored) {
        }
        return o;
    }

    /** 设置页的「她参考了哪些信息」：只展示数据来源状态，不展示屏幕原文。 */
    public static JSONArray sources(Context c) {
        JSONArray a = new JSONArray();
        try {
            Cfg cfg = Cfg.load(c);
            source(a, "时间与时段", "始终可用", true, "早晚、夜间、专注时段");
            boolean app = Perm.a11y(c) || Perm.usage(c);
            source(a, "当前应用", app ? "已授权" : "未授权", app, "前台应用与游戏识别");
            boolean screen = cfg.b("screenContext", true) && Perm.a11y(c);
            source(a, "屏幕文字", screen ? "已开启" : "未开启", screen, "只用于理解情境，日志不保存原文");
            boolean music = Perm.notifyListener(c);
            source(a, "音乐与媒体", music ? "已授权" : "未授权", music, "读取媒体会话，不读歌词内容");
            boolean cal = cfg.b("calEnabled", true) && Perm.calendar(c);
            source(a, "日历与提醒", cal ? "已开启" : "未开启", cal, "日程、会前提醒与手动提醒");
            boolean geo = cfg.b("geoEnabled", false) && Perm.location(c);
            source(a, "位置", geo ? "已开启" : "未开启", geo, "到家/到公司，不上传轨迹");
            source(a, "电量", "可用", true, "仅在桌宠服务运行时读取");
            source(a, "关系、心情与记忆", "本地", true, "来自 S-G，不上传");
        } catch (Throwable ignored) {
        }
        return a;
    }

    private static void source(JSONArray a, String name, String status, boolean on, String note) {
        try {
            JSONObject o = new JSONObject();
            o.put("name", name);
            o.put("status", status);
            o.put("on", on);
            o.put("note", note);
            a.put(o);
        } catch (Throwable ignored) {
        }
    }

    private static boolean quietNow(Cfg cfg) {
        try {
            return cfg.b("polQuiet", false) || cfg.l("polQuietUntil", 0L) > System.currentTimeMillis();
        } catch (Throwable t) {
            return false;
        }
    }

    public static long quietUntil(Context c) {
        try {
            return Cfg.load(c).l("polQuietUntil", 0L);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 快速静默：从当前时间起暂停 minutes 分钟。 */
    public static void pauseFor(Context c, int minutes) {
        Cfg cfg = Cfg.load(c);
        cfg.set("polQuietUntil", System.currentTimeMillis() + Math.max(1, minutes) * 60000L);
        cfg.save(c);
        Bus.emit("quiet", new JSONObject());
    }

    /** 快速静默到下一个早上 7 点。 */
    public static void pauseUntilMorning(Context c) {
        Calendar cal = Calendar.getInstance();
        int h = cal.get(Calendar.HOUR_OF_DAY);
        if (h >= 7) cal.add(Calendar.DAY_OF_YEAR, 1);
        cal.set(Calendar.HOUR_OF_DAY, 7);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        Cfg cfg = Cfg.load(c);
        cfg.set("polQuietUntil", cal.getTimeInMillis());
        cfg.save(c);
        Bus.emit("quiet", new JSONObject());
    }

    public static JSONArray recentLog(Context c, int limit) {
        JSONArray out = new JSONArray();
        try {
            String s = Sg.read(Sg.file(c, "proactive.jsonl"), "");
            String[] lines = s.split("\n");
            int n = 0;
            for (int k = lines.length - 1; k >= 0 && n < limit; k--) {
                if (lines[k].trim().isEmpty()) continue;
                try {
                    out.put(new JSONObject(lines[k]));
                    n++;
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    public static JSONObject json(Context c) {
        Cfg cfg = Cfg.load(c);
        JSONObject o = new JSONObject();
        try {
            String[] keys = {"polQuiet", "polNightStart", "polNightEnd",
                    "polNightBubbleOnly", "polCatChat", "polCatReminder", "polCatHealth", "polCatMusic",
                    "polCatAd", "polCatFocus", "polCatGeo", "polCatPlugin", "polFocusImportant", "polMoodAffect"};
            for (String k : keys) o.put(k, cfg.json().opt(k));
        } catch (Throwable ignored) {
        }
        return o;
    }

    public static void save(Context c, JSONObject o) {
        if (o == null) return;
        Cfg cfg = Cfg.load(c);
        String[] keys = {"polQuiet", "polNightStart", "polNightEnd",
                "polNightBubbleOnly", "polCatChat", "polCatReminder", "polCatHealth", "polCatMusic",
                "polCatAd", "polCatFocus", "polCatGeo", "polCatPlugin", "polFocusImportant", "polMoodAffect"};
        for (String k : keys) {
            if (o.has(k)) cfg.set(k, o.opt(k));
        }
        cfg.save(c);
    }

    public static void setQuiet(Context c, boolean quiet) {
        Cfg cfg = Cfg.load(c);
        cfg.set("polQuiet", quiet);
        cfg.save(c);
        Bus.emit("quiet", new JSONObject());
    }

    public static boolean isQuiet(Context c) {
        return quietNow(Cfg.load(c));
    }

    public static void clearLog(Context c) {
        Sg.write(Sg.file(c, "proactive.jsonl"), "");
    }

    public static String mode(Context c, Cfg cfg) {
        if (gameNow(c, cfg)) return "game";
        if (focusRunning()) return "focus";
        if (nightNow(cfg)) return "night";
        return "normal";
    }

    private static boolean gameNow(Context c, Cfg cfg) {
        return cfg.b("gameEnabled", true) && cfg.isGame(Bus.currentPackage);
    }

    public static boolean focusRunning() {
        try {
            JSONObject p = PetSvc.pomoState();
            return p.optBoolean("running", false) && "work".equals(p.optString("phase", ""));
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean nightNow(Cfg cfg) {
        try {
            int now = nowMin();
            int s = min(cfg.str("polNightStart", "23:00"));
            int e = min(cfg.str("polNightEnd", "07:00"));
            if (s == e) return false;
            if (s < e) return now >= s && now < e;
            return now >= s || now < e;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean catEnabled(Cfg cfg, String type) {
        if ("plan".equals(type) || "travel".equals(type) || "parcel".equals(type)) return true;
        if ("reminder".equals(type)) return cfg.b("polCatReminder", true);
        if ("health".equals(type)) return cfg.b("polCatHealth", true);
        if ("music".equals(type)) return cfg.b("polCatMusic", true);
        if ("ad".equals(type)) return cfg.b("polCatAd", true);
        if ("focus".equals(type)) return cfg.b("polCatFocus", true);
        if ("geo".equals(type)) return cfg.b("polCatGeo", true);
        if ("plugin".equals(type)) return cfg.b("polCatPlugin", true);
        if ("weather".equals(type)) return cfg.b("weatherEnabled", true);
        if ("greet".equals(type)) return cfg.b("polCatChat", true);
        return cfg.b("polCatChat", true);
    }

    /**
     * 用户单独开启的自由行动：不受游戏/视频场景的闲聊拦截，但仍遵守总静音、
     * 聊天类别、专注和夜间策略。该入口只放行启动提示，不给主动朗读权限。
     */
    public static Decision decideFreeAction(Context c) {
        Cfg cfg = Cfg.load(c);
        String mode = mode(c, cfg);
        if (!catEnabled(cfg, "chat"))
            return new Decision(false, 0, "主动聊天类别已关闭", mode);
        if (quietNow(cfg))
            return new Decision(false, 0, "全局静音期间不启动自由行动", "quiet");
        if (focusRunning())
            return new Decision(false, 0, "专注期间不启动自由行动", "focus");
        if (nightNow(cfg))
            return new Decision(false, 0, "夜间时段不启动自由行动", "night");
        if (Scene.chatting() || Scene.spoken())
            return new Decision(false, 0, "用户正在聊天或听语音内容", mode);
        return new Decision(true, 1, "用户已开启自由行动；仅显示启动提示，不朗读", "free_action");
    }

    public static boolean isImportantType(String type) {
        return isImportant(type);
    }

    private static boolean isImportant(String type) {
        return "plan".equals(type) || "travel".equals(type) || "parcel".equals(type)
                || "reminder".equals(type) || "geo".equals(type);
    }

    /** 打扰优先级：0=关键，1=重要，2=社交。用于夜间/游戏/专注时区分放行。 */
    public static int priority(String type) {
        if ("reminder".equals(type) || "plan".equals(type)) return 0;
        if ("travel".equals(type) || "parcel".equals(type) || "geo".equals(type)) return 1;
        return 2;
    }

    public static String priorityName(int p) {
        return p <= 0 ? "关键" : p == 1 ? "重要" : "社交";
    }

    private static String dayKey() {
        Calendar c = Calendar.getInstance();
        return c.get(Calendar.YEAR) + "-" + (c.get(Calendar.MONTH) + 1) + "-" + c.get(Calendar.DAY_OF_MONTH);
    }

    private static int nowHour() {
        return Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
    }

    /** 有效时段 08:00–23:00 内按比例放行，避免上午用完全天额度。 */
    private static int expectedByNow(int dayCap) {
        int now = nowMin();
        int start = 8 * 60;
        int end = 23 * 60;
        if (now < start) return 1;
        if (now >= end) return dayCap;
        int n = (int) Math.ceil(dayCap * (now - start) / (double) (end - start));
        return Math.max(1, Math.min(dayCap, n));
    }

    private static int nowMin() {
        Calendar c = Calendar.getInstance();
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
    }

    /** 距离夜间时段结束还有多少分钟；不在夜间时返回 0。调度用它把唤醒推到天亮后。 */
    public static int nightRemainingMin(Cfg cfg) {
        try {
            if (!nightNow(cfg)) return 0;
            int now = nowMin();
            int end = min(cfg.str("polNightEnd", "07:00"));
            int diff = end - now;
            if (diff <= 0) diff += 1440;
            return diff;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int min(String s) {
        try {
            String[] p = s.split(":");
            return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
        } catch (Throwable t) {
            return 0;
        }
    }

    public static String levelName(int level) {
        switch (level) {
            case 3:
                return "通知+声音";
            case 2:
                return "气泡+通知";
            case 1:
                return "仅气泡";
            default:
                return "静默";
        }
    }

    public static String typeName(String type) {
        if ("chat".equals(type)) return "闲聊";
        if ("reminder".equals(type)) return "提醒";
        if ("plan".equals(type)) return "日程";
        if ("travel".equals(type)) return "出行";
        if ("parcel".equals(type)) return "快递";
        if ("health".equals(type)) return "健康";
        if ("music".equals(type)) return "音乐";
        if ("ad".equals(type)) return "广告";
        if ("focus".equals(type)) return "专注";
        if ("geo".equals(type)) return "位置";
        if ("plugin".equals(type)) return "插件";
        if ("weather".equals(type)) return "天气";
        if ("greet".equals(type)) return "问候";
        return type;
    }
}
