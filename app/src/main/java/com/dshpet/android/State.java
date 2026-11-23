package com.dshpet.android;

import android.content.Context;

import org.json.JSONObject;

/**
 * 临时情绪层：和长期性格（Traits）分开。
 * 被骂只会让她生气（临时），不会让她变成傲娇（长期）。
 * 情绪会随时间自然回落，不会一直憋着。
 */
public final class State {
    private static final String FILE = "state.json";
    /** 超过 1 小时没变化，情绪开始回落。 */
    private static final long DECAY_AFTER_MS = 60 * 60 * 1000L;
    /** 每小时回落到中心的比例。 */
    private static final double DECAY_RATE = 0.2;

    public static final String MOOD_CALM = "平静";
    public static final String MOOD_HAPPY = "开心";
    public static final String MOOD_EXCITED = "兴奋";
    public static final String MOOD_SATISFIED = "满足";
    public static final String MOOD_RELIEF = "安心";
    public static final String MOOD_PROUD = "骄傲";
    public static final String MOOD_SHY = "害羞";
    public static final String MOOD_EXCITED_LOVE = "心动";
    public static final String MOOD_MISS = "想你";
    public static final String MOOD_TENDER = "心疼";
    public static final String MOOD_WORRIED = "担心";
    public static final String MOOD_NEEDED = "被需要";
    public static final String MOOD_SAD = "失落";
    public static final String MOOD_SULK = "委屈";
    public static final String MOOD_TIRED = "累";
    public static final String MOOD_SLEEPY = "困";
    public static final String MOOD_ANNOYED = "烦躁";
    public static final String MOOD_ANGRY = "生气";
    public static final String MOOD_NERVOUS = "紧张";
    public static final String MOOD_PLAYFUL = "顽皮";
    public static final String MOOD_SMUG = "得意";

    private State() {
    }

    public static synchronized JSONObject load(Context c) {
        JSONObject o = Sg.readObj(c, FILE);
        try {
            if (!o.has("roleId")) o.put("roleId", "default");
            if (!o.has("mood")) o.put("mood", MOOD_CALM);
            if (!o.has("moodScore")) o.put("moodScore", 0.0);
            if (!o.has("moodTs")) o.put("moodTs", 0L);
            if (!o.has("diaryPeek")) o.put("diaryPeek", false);
            if (!o.has("diaryPeekTs")) o.put("diaryPeekTs", 0L);
        } catch (Throwable ignored) {
        }
        return o;
    }

    /** 调整情绪分数：正=开心/想你，负=生气/低落/害羞。 */
    public static synchronized void bumpMood(Context c, double delta, String mood) {
        bumpMood(c, delta, mood, "");
    }

    /** 带原因的情绪变化；原因只用于提示词与 UI，不做诊断。 */
    public static synchronized void bumpMood(Context c, double delta, String mood, String cause) {
        JSONObject o = load(c);
        try {
            double score = o.optDouble("moodScore", 0);
            score = Math.max(-100, Math.min(100, score + delta));
            o.put("moodScore", score);
            if (mood != null && !mood.isEmpty()) o.put("mood", mood);
            if (cause != null && !cause.isEmpty()) o.put("moodCause", cause);
            if (MOOD_CALM.equals(mood)) o.put("moodCause", "");
            o.put("moodTs", System.currentTimeMillis());
            Sg.writeObj(c, FILE, o);
        } catch (Throwable ignored) {
        }
    }

    /** 重置为平静：性格重置时清掉临时情绪，避免把旧情绪带进新基线。 */
    public static synchronized void toCalm(Context c) {
        JSONObject o = load(c);
        try {
            o.put("mood", MOOD_CALM);
            o.put("moodScore", 0.0);
            o.put("moodCause", "");
            o.put("moodTs", System.currentTimeMillis());
            Sg.writeObj(c, FILE, o);
        } catch (Throwable ignored) {
        }
    }

    /** 标记日记被偷看（下次对话注入事件）。只有超过 7 天才再次触发，避免每天出现。 */
    public static synchronized boolean setDiaryPeek(Context c) {
        JSONObject o = load(c);
        try {
            long last = o.optLong("diaryPeekTs", 0);
            if (last > 0 && System.currentTimeMillis() - last < 7 * 24 * 60 * 60 * 1000L) return false;
            o.put("diaryPeek", true);
            o.put("diaryPeekTs", System.currentTimeMillis());
            o.put("mood", MOOD_SHY);
            double score = o.optDouble("moodScore", 0);
            o.put("moodScore", Math.max(-100, score - 10));
            o.put("moodTs", System.currentTimeMillis());
            Sg.writeObj(c, FILE, o);
            Bus.emit("diaryPeek", new JSONObject());
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 是否有未消费的偷看日记事件。 */
    public static boolean hasDiaryPeek(Context c) {
        return load(c).optBoolean("diaryPeek", false);
    }

    /** 消费掉偷看事件（注入 prompt 后调用）。 */
    public static synchronized void consumeDiaryPeek(Context c) {
        JSONObject o = load(c);
        if (!o.optBoolean("diaryPeek", false)) return;
        try {
            o.put("diaryPeek", false);
            Sg.writeObj(c, FILE, o);
        } catch (Throwable ignored) {
        }
    }

    /** 情绪自然回落：距上次变化超过 1 小时，向 0 回落。 */
    private static void decay(JSONObject o) {
        try {
            long ts = o.optLong("moodTs", 0);
            if (ts <= 0) return;
            long now = System.currentTimeMillis();
            long gap = now - ts;
            if (gap < DECAY_AFTER_MS) return;
            double score = o.optDouble("moodScore", 0);
            if (Math.abs(score) < 1) {
                o.put("moodScore", 0);
                o.put("mood", MOOD_CALM);
                return;
            }
            double hours = gap / (60.0 * 60 * 1000);
            double factor = Math.pow(1 - DECAY_RATE, hours);
            score = score * factor;
            o.put("moodScore", score);
            if (Math.abs(score) < 15) o.put("mood", MOOD_CALM);
            o.put("moodTs", now);
        } catch (Throwable ignored) {
        }
    }

    /** 写进系统提示的情绪状态。 */
    public static String prompt(Context c) {
        JSONObject o = load(c);
        decay(o);
        try {
            double score = o.optDouble("moodScore", 0);
            StringBuilder sb = new StringBuilder();
            boolean peeked = o.optBoolean("diaryPeek", false);
            if (peeked) {
                sb.append("注意：他刚才偷看了你的日记。你感到害羞和有点生气，请自然地表达出来，不要假装不知道。\n");
                o.put("diaryPeek", false); // 注入后立即消费
            }
            if (Math.abs(score) > 15) {
                String mood = o.optString("mood", MOOD_CALM);
                int intensity = (int) Math.min(100, Math.abs(score));
                sb.append("你现在的心情：").append(mood).append("（强度 ").append(intensity).append("/100）");
                String cause = o.optString("moodCause", "");
                if (!cause.isEmpty()) sb.append("，原因：").append(cause);
                sb.append("。\n");
            }
            // 保存回落后的状态
            Sg.writeObj(c, FILE, o);
            return sb.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    public static JSONObject summary(Context c) {
        JSONObject o = load(c);
        decay(o);
        JSONObject out = new JSONObject();
        try {
            out.put("mood", o.optString("mood", MOOD_CALM));
            out.put("moodScore", Math.round(o.optDouble("moodScore", 0)));
            out.put("diaryPeek", o.optBoolean("diaryPeek", false));
            Sg.writeObj(c, FILE, o);
        } catch (Throwable ignored) {
        }
        return out;
    }
}
