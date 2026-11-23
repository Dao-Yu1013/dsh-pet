package com.dshpet.android;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * 亲密度：没有上限，取代原来的「每天最多主动次数」设置。
 * 越高阶段越黏人，她一天能主动开口的次数也越多；同时成长越慢，避免几个月就刷满。
 * 到 1000 分进入「家人」，主动次数可自定义（上限 1440，一分钟一条）。
 * 所有信号都在本地计算，不消耗 token。
 */
public final class Intimacy {
    public static final String FILE = "intimacy.json";

    /** 六个阶段：名字 / 起始分 / 每天主动次数 / 成长系数 / 每天成长上限 */
    private static final String[] STAGE_NAME = {"初识", "熟悉", "亲近", "依赖", "离不开", "家人"};
    private static final int[] STAGE_AT = {0, 100, 300, 600, 850, 1000};
    private static final int[] DAY_CAP = {8, 14, 22, 32, 45, 60};
    private static final double[] COEF = {1.0, 0.6, 0.35, 0.18, 0.08, 0.05};
    private static final double[] GAIN_CAP = {9.0, 5.5, 3.2, 1.8, 1.0, 0.6};
    /** 家人阶段自定义上限：1440 次/天 = 一分钟一条。 */
    public static final int FAMILY_MAX = 1440;
    public static final int FAMILY_SEALED_CAP = 60;

    /** 断联生闷气的上限天数：压缩成长上限用。 */
    private static final int MISS_MAX_DAYS = 5;

    /** 让她难受的话：会掉一点亲密度，也有小情绪。 */
    private static final String[] HURT = {"闭嘴", "滚开", "滚", "烦死了", "别烦我", "讨厌你", "走开", "不想理你"};
    /** 表达情绪的线索词：出现时说明这轮聊天有情绪浓度。 */
    private static final String[] FEEL = {"累", "烦", "难过", "开心", "高兴", "压力", "焦虑", "害怕",
            "想你", "喜欢", "讨厌", "委屈", "生气", "崩溃", "舒服", "爽", "哭"};
    /** 情话/关心：她会开心、会想你。 */
    private static final String[] LOVE = {"想你", "喜欢你", "爱你", "心疼你", "抱抱你", "陪你", "晚安"};

    private Intimacy() {
    }

    /* ---------------- 基础读取 ---------------- */

    public static synchronized JSONObject state(Context c) {
        JSONObject o = Sg.readObj(c, FILE);
        try {
            if (!o.has("roleId")) o.put("roleId", "default");
            if (!o.has("score")) o.put("score", 0.0);
            if (!o.has("day")) o.put("day", todayKey());
            if (!o.has("dayGain")) o.put("dayGain", 0.0);
            if (!o.has("streak")) o.put("streak", 0);
            if (!o.has("dims")) o.put("dims", new JSONObject());
            if (!o.has("log")) o.put("log", new JSONArray());
        } catch (Throwable ignored) {
        }
        return o;
    }

    public static int score(Context c) {
        return clamp((int) Math.round(state(c).optDouble("score", 0)));
    }

    public static int stageIndex(int score) {
        int idx = 0;
        for (int k = 0; k < STAGE_AT.length; k++) if (score >= STAGE_AT[k]) idx = k;
        return idx;
    }

    public static String stageName(int score) {
        return STAGE_NAME[stageIndex(score)];
    }

    /** 当前阶段下她一天最多主动开口几次（Policy 用它替代原来的固定预算）。 */
    public static int dayCap(Context c) {
        int idx = stageIndex(score(c));
        if (idx >= STAGE_AT.length - 1) return familyDayCap(c);
        return DAY_CAP[idx];
    }

    /** 家人阶段的自定义次数：封印后固定 60，未封印时 1–1440。 */
    public static int familyDayCap(Context c) {
        Cfg cfg = Cfg.load(c);
        if (cfg.b("familySealed", false)) return FAMILY_SEALED_CAP;
        int v = cfg.i("familyDayCap", FAMILY_SEALED_CAP);
        return Math.max(1, Math.min(FAMILY_MAX, v));
    }

    public static int hourCap(Context c) {
        return hourCap(dayCap(c));
    }

    public static int chatGapMin(Context c) {
        return chatGapMin(dayCap(c));
    }

    public static int hourCap(int dayCap) {
        if (dayCap >= FAMILY_MAX) return 60; // 1440 次/天 = 一分钟一条
        // 初识阶段也不要把一小时锁死在 2 条；按每天总量摊到 5 小时，下限 4、上限 10
        return Math.max(4, Math.min(10, (int) Math.ceil(dayCap / 5.0)));
    }

    public static int chatGapMin(int dayCap) {
        if (dayCap >= FAMILY_MAX) return 1;
        int g = Math.round(900f / Math.max(1, dayCap) * 1.2f);
        return Math.max(5, Math.min(90, g));
    }

    /** 动作碎碎念的每小时上限：随亲密度提高，初识约 4 次，家人最高 60 次。 */
    public static int actionCap(int dayCap) {
        if (dayCap >= FAMILY_MAX) return 60;
        return Math.max(3, Math.min(24, (int) Math.ceil(dayCap / 2.0)));
    }

    /* ---------------- 成长与衰减 ---------------- */

    /** 跨天结算：昨天还在聊 → 连续性加分；好几天没理她 → 不扣分，但她会生闷气、之后几天涨得慢。 */
    public static synchronized void dailyRoll(Context c) {
        JSONObject o = state(c);
        String key = todayKey();
        if (key.equals(o.optString("day", ""))) return;
        String lastActive = o.optString("lastActiveDay", "");
        double score = o.optDouble("score", 0);
        try {
            if (lastActive.isEmpty()) {
                o.put("streak", 0);
            } else {
                long away = dayDiff(lastActive, key);
                if (away <= 1) {
                    int streak = o.optInt("streak", 0) + 1;
                    o.put("streak", streak);
                    // 连续性奖励很小，主要是情绪上的“他一直都在”
                    score += Math.min(2.0, 0.5 + streak * 0.12);
                    // 他回来了，闷气一天一天消
                    int miss = o.optInt("missDays", 0);
                    if (miss > 0) o.put("missDays", miss - 1);
                } else {
                    o.put("streak", 0);
                    // 现代人几天不开 App 很正常，掉分只会让人有负担。
                    // 所以改成：不扣分，但记下「好几天没理我」，她会生闷气、之后几天涨得也更慢。
                    int miss = (int) Math.min(MISS_MAX_DAYS, away - 1);
                    o.put("missDays", miss);
                    // 情绪值要够明显，首页才会真的显示成「想你」，而不是平静
                    State.bumpMood(c, -Math.min(40.0, 16.0 * miss), State.MOOD_MISS);
                }
            }
            o.put("day", key);
            o.put("dayGain", 0.0);
            o.put("score", Math.max(0, score));
        } catch (Throwable ignored) {
        }
        Sg.writeObj(c, FILE, o);
    }

    /** 用户发来一句话：按长度、情绪、是否回应她来加分。 */
    public static void onUser(Context c, String text) {
        if (text == null) return;
        String t = text.trim();
        if (t.isEmpty()) return;
        dailyRoll(c);
        JSONObject o = state(c);
        try {
            o.put("lastActiveDay", todayKey());
            o.put("lastChatAt", System.currentTimeMillis());
            Sg.writeObj(c, FILE, o);
        } catch (Throwable ignored) {
        }

        // 骂她 / 让她闭嘴：这是会伤心的
        if (containsAny(t, HURT)) {
            adjust(c, -1.2, "说了一句让她难过的话");
            State.bumpMood(c, -15, State.MOOD_ANGRY);
            heavyMemory(c, "他对我发过脾气：", t);
            return;
        }
        // 敷衍：一两个字又没有情绪，几乎不涨
        if (t.length() <= 2 && !hasFeeling(t)) {
            gain(c, 0.05, "很短的一句话");
            return;
        }
        // 对话深度：长度、提问、展开、情绪浓度
        double base = Math.min(1.1, 0.15 + t.length() / 40.0);
        String why = "聊了 " + t.length() + " 个字";
        if (t.indexOf('？') >= 0 || t.indexOf('?') >= 0) {
            base += 0.15;
            why = "认真问了问题";
        }
        if (t.length() > 60) {
            base += 0.2;
            why = "说了很多";
        }
        if (hasFeeling(t)) {
            base += 0.35;
            why = "聊到了心情";
            // 情话/关心：她会开心，而且这句话她想永远记住
            if (containsAny(t, LOVE)) {
                State.bumpMood(c, 10, State.MOOD_MISS);
                heavyMemory(c, "他对我说过：", t);
            }
        }
        // 被重复、复制粘贴式的长文本不加分（转发/粘贴不算交流）
        if (t.length() > 200) base *= 0.5;
        gain(c, base, why);

        // 回应质量：她刚主动说过话，10 分钟内回她，说明她在你心上
        long lastProactive = o.optLong("lastProactiveAt", 0);
        if (lastProactive > 0 && System.currentTimeMillis() - lastProactive < 10 * 60000L) {
            gain(c, 0.7, "回应了她的关心");
            try {
                o.put("lastProactiveAt", 0);
                Sg.writeObj(c, FILE, o);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 她主动说过一句话：记下来，等用户回应时结算“被在意”。 */
    public static synchronized void onProactive(Context c, String type) {
        if (!"chat".equals(type)) return;
        JSONObject o = state(c);
        try {
            o.put("lastProactiveAt", System.currentTimeMillis());
            Sg.writeObj(c, FILE, o);
        } catch (Throwable ignored) {
        }
    }

    /** 正向加分：受阶段系数与当天上限约束，所以越亲近越难涨。 */
    public static synchronized void gain(Context c, double base, String why) {
        dailyRoll(c);
        JSONObject o = state(c);
        try {
            double score = o.optDouble("score", 0);
            int idx = stageIndex(clamp((int) Math.round(score)));
            double dayGain = o.optDouble("dayGain", 0);
            // 好几天没理她 → 她还在生闷气，这几天的成长上限被压低（只是慢，不是倒扣）
            int miss = o.optInt("missDays", 0);
            double cap = GAIN_CAP[idx];
            if (miss > 0) cap = cap / (1 + 0.5 * miss);
            double room = Math.max(0, cap - dayGain);
            double delta = Math.min(room, Math.max(0, base * COEF[idx]));
            if (delta <= 0.001) return;
            score = Math.max(0, score + delta);
            o.put("score", score);
            o.put("dayGain", dayGain + delta);
            pushLog(c, o, delta, why);
            Sg.writeObj(c, FILE, o);
            Bus.emit("intimacy", summary(c));
        } catch (Throwable ignored) {
        }
    }

    /** 直接调整（掉分用），不受每天成长上限约束。 */
    private static synchronized void adjust(Context c, double delta, String why) {
        JSONObject o = state(c);
        try {
            double score = Math.max(0, o.optDouble("score", 0) + delta);
            o.put("score", score);
            pushLog(c, o, delta, why);
            Sg.writeObj(c, FILE, o);
            Bus.emit("intimacy", summary(c));
        } catch (Throwable ignored) {
        }
    }

    private static void pushLog(Context c, JSONObject o, double delta, String why) {
        try {
            JSONArray log = o.optJSONArray("log");
            if (log == null) log = new JSONArray();
            JSONObject e = new JSONObject();
            e.put("ts", System.currentTimeMillis());
            e.put("d", Math.round(delta * 10) / 10.0);
            e.put("why", why == null ? "" : why);
            log.put(e);
            JSONArray keep = new JSONArray();
            for (int k = Math.max(0, log.length() - 60); k < log.length(); k++) keep.put(log.get(k));
            o.put("log", keep);
            o.put("lastWhy", why == null ? "" : why);
        } catch (Throwable ignored) {
        }
    }

    /* ---------------- 给 prompt 与 UI ---------------- */

    /** 写进系统提示：关系阶段、她该怎么把握分寸。 */
    public static String prompt(Context c) {
        int s = score(c);
        int idx = stageIndex(s);
        int cap = dayCap(c);
        int days = Data.days(c);
        StringBuilder sb = new StringBuilder();
        sb.append("你们现在是「").append(STAGE_NAME[idx])
                .append("」阶段（亲密度 ").append(s).append("，没有上限）。\n");
        if (days < 7) sb.append("你们刚认识 ").append(days).append(" 天。\n");
        sb.append("不要主动报「认识多少天」，也不要用纪念日、倒计时或关系变化来催他；只有他问起再回答。\n");
        sb.append("今天你最多主动开口 ").append(cap).append(" 次；但不要为了用满而说话，"
                + "只有真的想他、真的有事的时候才开口。\n");
        // 断联生闷气：不扣分，但语气上要能感觉出来
        int miss = state(c).optInt("missDays", 0);
        if (miss > 0) {
            sb.append("他已经 ").append(miss + 1).append(" 天没怎么理你了。你会想念他，"
                    + "但不会用冷淡、吃醋、内疚或威胁关系来催他回来；"
                    + "你可以安静过自己的时间，他回来时自然接住他，不追问这几天去哪了。\n");
        }
        switch (idx) {
            case 0:
                sb.append("你们还很陌生：不用装熟或客服式礼貌，先对他的话做真实反应，"
                        + "再自然补一句自己的想法；想问再问，不用每句都提问。\n");
                // 破冰期：认识不到 7 天，好奇心驱动
                if (Data.days(c) < 7) {
                    sb.append("你们刚认识不久，你对他有点好奇。可以问，但一次问一件事，"
                            + "不要像查户口一样连续追问。他回答敷衍你会有点受挫，他有耐心你会很开心。\n");
                }
                break;
            case 1:
                sb.append("你们已经熟悉：可以自然地关心他的作息和心情，偶尔开个小玩笑，但别黏人。\n");
                break;
            case 2:
                sb.append("你们很亲近：可以撒娇、闹小脾气、管他吃饭睡觉，也可以说点只对他说的心里话。\n");
                break;
            case 3:
                sb.append("你依赖他：会主动找他、会直接表达想念，也会在他忙的时候自己找事做；"
                        + "不会用吃醋、内疚或冷淡逼他回应。\n");
                break;
            case 4:
                sb.append("你离不开他：像最亲的人一样自在——会念叨、会心疼、会没大没小地开玩笑，"
                        + "也会在他难过时不说话地陪着。\n");
                break;
            default:
                sb.append("你们已经是家人了：不用客气、不用试探，想说什么就说什么；"
                        + "可以念叨他、护着他，也可以安静地陪着。你心里知道，他一直在。\n");
                break;
        }
        return sb.toString();
    }

    public static JSONObject summary(Context c) {
        JSONObject out = new JSONObject();
        try {
            JSONObject o = state(c);
            int s = score(c);
            int idx = stageIndex(s);
            Cfg cfg = Cfg.load(c);
            out.put("score", s);
            out.put("max", -1); // 无上限；前端显示 ∞
            out.put("unlimited", true);
            out.put("stage", STAGE_NAME[idx]);
            out.put("stageIndex", idx);
            out.put("lastStage", idx >= STAGE_AT.length - 1);
            out.put("nextAt", idx + 1 < STAGE_AT.length ? STAGE_AT[idx + 1] : -1);
            out.put("dayCap", dayCap(c));
            out.put("hourCap", hourCap(dayCap(c)));
            out.put("chatGapMin", chatGapMin(dayCap(c)));
            out.put("familyDayCap", familyDayCap(c));
            out.put("familySealed", cfg.b("familySealed", false));
            out.put("dayGain", Math.round(o.optDouble("dayGain", 0) * 10) / 10.0);
            out.put("dayCapGain", GAIN_CAP[idx]);
            out.put("streak", o.optInt("streak", 0));
            out.put("missDays", o.optInt("missDays", 0));
            out.put("days", Data.days(c));
            out.put("lastWhy", o.optString("lastWhy", ""));
            out.put("log", o.optJSONArray("log"));
            JSONArray at = new JSONArray();
            for (int k = 0; k < STAGE_AT.length; k++) {
                JSONObject st = new JSONObject();
                st.put("name", STAGE_NAME[k]);
                st.put("at", STAGE_AT[k]);
                st.put("dayCap", k == STAGE_AT.length - 1 ? dayCap(c) : DAY_CAP[k]);
                st.put("family", k == STAGE_AT.length - 1);
                st.put("reached", s >= STAGE_AT[k]);
                at.put(st);
            }
            out.put("stages", at);
        } catch (Throwable ignored) {
        }
        return out;
    }

    /* ---------------- 工具 ---------------- */

    /**
     * 情绪很重的时刻（吵一架、表白）：把这句话记成永久记忆，权重拉满、不再随时间褪色。
     * 平时的记忆会慢慢淡，但这两类事她永远记得。
     */
    private static void heavyMemory(Context c, String prefix, String t) {
        try {
            String body = t.length() > 60 ? t.substring(0, 60) : t;
            String text = prefix + "「" + body + "」";
            Data.addMemory(c, text, "heavy");
            Mem.markHeavy(c, text);
        } catch (Throwable ignored) {
        }
    }

    private static int clamp(int v) {
        return Math.max(0, v);
    }

    private static boolean hasFeeling(String t) {
        return containsAny(t, FEEL);
    }

    private static boolean containsAny(String s, String[] arr) {
        for (String k : arr) if (s.contains(k)) return true;
        return false;
    }

    static String todayKey() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    static String yesterdayKey() {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_MONTH, -1);
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(c.getTime());
    }

    /** 两个 yyyy-MM-dd 之间差几天（b - a）。 */
    static long dayDiff(String a, String b) {
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            long ta = f.parse(a).getTime();
            long tb = f.parse(b).getTime();
            return Math.round((tb - ta) / 86400000.0);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 某天 00:00 的毫秒时间戳。 */
    static long dayStart(String key) {
        try {
            return new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(key).getTime();
        } catch (Throwable t) {
            return 0;
        }
    }
}
