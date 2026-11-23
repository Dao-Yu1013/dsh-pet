package com.dshpet.android;

import android.content.Context;

import java.util.Calendar;

/**
 * 本地价值评估：判断这次主动开口是否「有明确价值」。
 * 全部本地计算，不调用模型；只用于闲聊，其他本地触发器仍走各自冷却。
 */
public final class Value {
    /** 云端生成门槛；低于它的 55–69 分只允许本地短句。 */
    public static final int AI_SCORE = 70;
    /** 低于这个分数直接沉默。 */
    public static final int LOCAL_SCORE = 55;

    private Value() {
    }

    /** 设置页「主动程度」：0 安静 / 1 适中 / 2 黏人。 */
    public static int aiScore(Context c) {
        int lv = Cfg.load(c).i("proactiveLevel", 1);
        return lv <= 0 ? 80 : lv >= 2 ? 60 : AI_SCORE;
    }

    public static int localScore(Context c) {
        int lv = Cfg.load(c).i("proactiveLevel", 1);
        return lv <= 0 ? 65 : lv >= 2 ? 45 : LOCAL_SCORE;
    }

    /**
     * 闲聊价值分 0–100。
     * U 紧急度 / N 新鲜度 / R 相关性 / F 时机，减去连续未回应惩罚。
     */
    public static int chatScore(Context c) {
        long now = System.currentTimeMillis();
        long lastChat = Interact.lastTypeAt(c, "chat");
        long gap = lastChat <= 0 ? Long.MAX_VALUE : now - lastChat;

        int urgency = gap >= 4 * 3600000L ? 10 : 0;
        int novelty;
        if (lastChat <= 0 || gap >= 24 * 3600000L) novelty = 20;
        else if (gap >= 6 * 3600000L) novelty = 15;
        else if (gap >= 3 * 3600000L) novelty = 10;
        else if (gap >= 90 * 60000L) novelty = 5;
        else novelty = 0;

        int relevance = 0;
        try {
            // 有到期的计划/牵挂时，优先接这个话头
            if (!SelfMem.promptRelevant(c, "", 1).isEmpty()) relevance += 15;
        } catch (Throwable ignored) {
        }
        if (Bus.musicPlaying) relevance += 10;
        if (!Bus.currentPackage.isEmpty()) relevance += 5;
        if (!Bus.screenText.isEmpty()) relevance += 5;
        long lastUser = Interact.lastUserAt(c);
        if (lastUser > 0 && now - lastUser < 3 * 3600000L) relevance += 10;
        if (relevance > 25) relevance = 25;

        int fit = 5;
        int score = Intimacy.score(c);
        if (score >= 100) fit += 5;
        if (score >= 300) fit += 5;
        if (greetingWindow()) fit += 5;
        if (gap >= 4 * 3600000L) fit += 5;
        if (fit > 15) fit = 15;

        int penalty = Interact.unansweredChat(c) * 8;
        if (penalty > 24) penalty = 24;

        int v = urgency + novelty + relevance + fit - penalty;
        return Math.max(0, Math.min(100, v));
    }

    /** 55–69 分时用本地短句，不调用云端。 */
    public static String localLine(Context c) {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (h < 11) {
            return Care.pick(new String[]{
                    "早，今天也慢慢来。",
                    "我在这儿，你忙你的就好。",
                    "记得吃点东西再忙。",
            });
        }
        if (h < 18) {
            return Care.pick(new String[]{
                    "我在这儿，不用回我。",
                    "今天也慢慢来，别急。",
                    "忙到一半也可以歇一下。",
            });
        }
        return Care.pick(new String[]{
                "忙完就歇会儿，我在。",
                "今天辛苦了，不用急着说话。",
                "我安静待着，你想说再说。",
        });
    }

    private static boolean greetingWindow() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return (h >= 6 && h < 11) || (h >= 17 && h < 23);
    }
}
