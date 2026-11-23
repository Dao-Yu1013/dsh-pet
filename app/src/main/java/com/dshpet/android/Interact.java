package com.dshpet.android;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 主动互动账本：只记录「她最近主动说过什么」「用户最后互动时间」，
 * 用来做文本去重、连续未回应降频和用户聊天优先判断。
 * 全部本地，不联网、不写入 chat.jsonl，避免污染用户事实。
 */
public final class Interact {
    private static final String FILE = "interact.json";
    private static final Object LOCK = new Object();
    private static final int RECENT_MAX = 30;
    /** 发出去 10 分钟内用户没有回应，才算一次「未回应」。 */
    private static final long REPLY_GRACE_MS = 10 * 60 * 1000L;
    /** 连续 3 条未回应后，闲聊暂停 2 小时。 */
    private static final long CHAT_PAUSE_MS = 2 * 3600 * 1000L;
    /** 账本只保留 30 天。 */
    private static final long KEEP_MS = 30 * 86400000L;
    /** 文本相似度阈值：2-gram Jaccard。 */
    private static final double SAME_TEXT = 0.55;

    private Interact() {
    }

    /** 用户发消息 / 点桌宠 / 打开应用：都算一次互动，清掉未回应计数。 */
    public static void onUser(Context c) {
        synchronized (LOCK) {
            try {
                JSONObject o = load(c);
                o.put("lastUserAt", System.currentTimeMillis());
                Sg.writeObj(c, FILE, o);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 打开应用、点桌宠也调用这里，语义上与发消息一致。 */
    public static void onEngage(Context c) {
        onUser(c);
    }

    /** 她真正把一句话送到用户眼前之后记一笔。 */
    public static void onDelivery(Context c, String type, String text) {
        if (text == null || text.trim().isEmpty()) return;
        synchronized (LOCK) {
            try {
                long now = System.currentTimeMillis();
                JSONObject o = load(c);
                JSONArray old = o.optJSONArray("recent");
                if (old == null) old = new JSONArray();
                JSONArray keep = new JSONArray();
                int start = Math.max(0, old.length() - (RECENT_MAX - 1));
                for (int i = start; i < old.length(); i++) {
                    JSONObject x = old.optJSONObject(i);
                    if (x != null && now - x.optLong("ts", 0) <= KEEP_MS) keep.put(x);
                }
                JSONObject item = new JSONObject();
                item.put("ts", now);
                item.put("type", type == null ? "" : type);
                item.put("text", text.length() > 100 ? text.substring(0, 100) : text);
                keep.put(item);
                o.put("recent", keep);
                o.put("lastDeliveryAt", now);
                Sg.writeObj(c, FILE, o);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 记录一次「她在场时看到的桌面互动」，喂食、戳头、做动作都算。 */
    public static void onPetEvent(Context c, String what) {
        if (what == null || what.trim().isEmpty()) return;
        synchronized (LOCK) {
            try {
                long now = System.currentTimeMillis();
                JSONObject o = load(c);
                JSONArray old = o.optJSONArray("petEvents");
                if (old == null) old = new JSONArray();
                JSONArray keep = new JSONArray();
                int start = Math.max(0, old.length() - 19);
                for (int i = start; i < old.length(); i++) {
                    JSONObject x = old.optJSONObject(i);
                    if (x != null && now - x.optLong("ts", 0) <= 86400000L) keep.put(x);
                }
                JSONObject item = new JSONObject();
                item.put("ts", now);
                item.put("text", what.length() > 40 ? what.substring(0, 40) : what);
                keep.put(item);
                o.put("petEvents", keep);
                Sg.writeObj(c, FILE, o);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 最近 24 小时的桌面互动，拼成提示词；没有就返回空串。 */
    public static String petPrompt(Context c) {
        try {
            JSONObject o = load(c);
            JSONArray a = o.optJSONArray("petEvents");
            if (a == null || a.length() == 0) return "";
            long now = System.currentTimeMillis();
            StringBuilder sb = new StringBuilder();
            int n = 0;
            for (int i = a.length() - 1; i >= 0 && n < 10; i--) {
                JSONObject x = a.optJSONObject(i);
                if (x == null) continue;
                long ts = x.optLong("ts", 0);
                String text = x.optString("text", "");
                if (text.isEmpty() || now - ts > 86400000L) continue;
                sb.insert(0, "- " + clock(ts) + " " + text + "\n");
                n++;
            }
            if (n == 0) return "";
            return "他最近和你在桌面上做过的互动（这些你都亲眼看到了，是你自己的经历）：\n" + sb
                    + "他问起这些时要自然承认，比如「你刚才喂我吃东西了呀」，"
                    + "绝对不要说「我看不到」「我这边只有聊天窗」这类话。\n";
        } catch (Throwable t) {
            return "";
        }
    }

    private static String clock(long ts) {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.setTimeInMillis(ts);
        return String.format(Locale.CHINA, "%02d:%02d",
                cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE));
    }

    public static long lastUserAt(Context c) {
        try {
            return load(c).optLong("lastUserAt", 0);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 某类主动最后一次送达的时间；没有返回 0。 */
    public static long lastTypeAt(Context c, String type) {
        try {
            JSONObject o = load(c);
            JSONArray recent = o.optJSONArray("recent");
            if (recent == null) return 0;
            long last = 0;
            for (int i = 0; i < recent.length(); i++) {
                JSONObject x = recent.optJSONObject(i);
                if (x != null && type != null && type.equals(x.optString("type", ""))) {
                    last = Math.max(last, x.optLong("ts", 0));
                }
            }
            return last;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 用户刚说过话：定时主动先让路。 */
    public static boolean isUserTalking(Context c) {
        try {
            JSONObject o = load(c);
            long last = o.optLong("lastUserAt", 0);
            return last > 0 && System.currentTimeMillis() - last < REPLY_GRACE_MS;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 连续未回应的闲聊条数：只统计 chat，不把健康/日程等重要提醒算进去。
     * 用户最后一条消息之后的聊天才算；发出去不足 10 分钟的先给用户回应时间。
     */
    public static int unansweredChat(Context c) {
        try {
            JSONObject o = load(c);
            long lastUser = o.optLong("lastUserAt", 0);
            long now = System.currentTimeMillis();
            JSONArray recent = o.optJSONArray("recent");
            if (recent == null) return 0;
            int n = 0;
            for (int i = 0; i < recent.length(); i++) {
                JSONObject x = recent.optJSONObject(i);
                if (x == null || !"chat".equals(x.optString("type", ""))) continue;
                long ts = x.optLong("ts", 0);
                if (ts <= lastUser) continue;
                if (now - ts < REPLY_GRACE_MS) continue;
                n++;
            }
            return n;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 未回应越多，闲聊间隔越长；连续 3 条以上由 {@link #chatBlockedUntil} 直接暂停。 */
    public static int adjustGap(int gap, int unanswered) {
        if (unanswered <= 0) return gap;
        if (unanswered == 1) return (int) Math.ceil(gap * 1.3);
        return gap * 2;
    }

    /**
     * 连续 3 条未回应：暂停闲聊 2 小时；连续 4 条以上：暂停到用户再次互动。
     * 返回 0 表示不暂停。
     */
    public static long chatBlockedUntil(Context c) {
        int n = unansweredChat(c);
        if (n < 3) return 0;
        if (n >= 4) return Long.MAX_VALUE;
        long last = lastChatDelivery(c);
        return last > 0 ? last + CHAT_PAUSE_MS : Long.MAX_VALUE;
    }

    private static long lastChatDelivery(Context c) {
        try {
            JSONObject o = load(c);
            JSONArray recent = o.optJSONArray("recent");
            if (recent == null) return 0;
            long last = 0;
            for (int i = 0; i < recent.length(); i++) {
                JSONObject x = recent.optJSONObject(i);
                if (x != null && "chat".equals(x.optString("type", ""))) {
                    last = Math.max(last, x.optLong("ts", 0));
                }
            }
            return last;
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * 生成后去重：和她最近说过的社交类主动消息、最近 10 条聊天回复比较。
     * 重要提醒和健康提醒不参与，避免「该喝水」被当成重复。
     */
    public static boolean isDuplicate(Context c, String text) {
        String mine = norm(text);
        if (mine.length() < 2) return false;
        Set<String> grams = grams(mine);
        try {
            JSONObject o = load(c);
            JSONArray recent = o.optJSONArray("recent");
            if (recent != null) {
                for (int i = 0; i < recent.length(); i++) {
                    JSONObject x = recent.optJSONObject(i);
                    if (x == null || !dupType(x.optString("type", ""))) continue;
                    if (similar(grams, mine, x.optString("text", ""))) return true;
                }
            }
            JSONArray hist = Data.chatList(c, 10);
            for (int i = 0; i < hist.length(); i++) {
                JSONObject h = hist.optJSONObject(i);
                if (h == null || !"assistant".equals(h.optString("role", ""))) continue;
                if (similar(grams, mine, h.optString("content", ""))) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean dupType(String type) {
        return "chat".equals(type) || "greet".equals(type) || "music".equals(type)
                || "plugin".equals(type) || "ad".equals(type);
    }

    private static boolean similar(Set<String> grams, String mine, String other) {
        String theirs = norm(other);
        if (theirs.isEmpty()) return false;
        if (mine.equals(theirs)) return true;
        if (mine.length() < 3 || theirs.length() < 3) return false;
        Set<String> b = grams(theirs);
        if (b.isEmpty()) return false;
        int same = 0;
        for (String g : grams) if (b.contains(g)) same++;
        int union = grams.size() + b.size() - same;
        return union > 0 && same / (double) union >= SAME_TEXT;
    }

    private static Set<String> grams(String s) {
        Set<String> out = new HashSet<>();
        if (s.length() < 2) {
            if (!s.isEmpty()) out.add(s);
            return out;
        }
        for (int i = 0; i + 2 <= s.length(); i++) out.add(s.substring(i, i + 2));
        return out;
    }

    /** 去掉标点、空格、表情，只留文字和数字，降低换标点/加表情造成的误判。 */
    private static String norm(String s) {
        if (s == null) return "";
        String t = s.toLowerCase(Locale.ROOT);
        return t.replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private static JSONObject load(Context c) {
        return Sg.readObj(c, FILE);
    }
}
