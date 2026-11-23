package com.dshpet.android;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 她自己的记忆：不是“用户是什么样”，而是“她答应过什么、在意什么”。
 * 用户说了「明天要……」这类计划会被记下，之后聊到相关内容就算兑现，给亲密度加分。
 * 全部本地规则提取，不消耗 token。
 */
public final class SelfMem {
    private static final String FILE = "self_memory.json";
    private static final int MAX_ITEMS = 120;

    /** 她做出承诺的说法。 */
    private static final String[] HER_PROMISE = {"我会", "我答应", "我陪你", "记得提醒你", "一定提醒你", "我帮你记着"};
    /** 用户在未来时里提到的安排。 */
    private static final String[] FUTURE = {"明天", "后天", "下周", "下个月", "今晚", "待会", "一会"};
    private static final String[] TODO = {"要", "去", "见", "做", "开", "交", "考试", "面试", "体检", "出差", "看", "约"};
    /** 计划里不参与匹配的虚词。 */
    private static final Set<String> NOISE = new HashSet<String>() {{
        add("明天"); add("后天"); add("下周"); add("下个月"); add("今晚"); add("待会"); add("一会");
        add("我要"); add("我去"); add("我得"); add("可能"); add("应该"); add("然后"); add("还有");
    }};
    /** 痛点关键词：他反复提到这些，她会惦记。 */
    private static final String[] CONCERN = {"加班", "好累", "失眠", "睡不着", "头疼", "感冒", "发烧",
            "焦虑", "压力大", "不想活", "撑不住", "没钱", "被骂", "被开除", "分手"};
    /** 改善信号：提到这些说明痛点缓解了，可以结案。 */
    private static final String[] RELIEF = {"不加班", "休息好了", "没事了", "好多了", "痊愈", "不疼了", "睡好了"};
    /** 用户表达负能量时，即使没有字面重合，也应把她惦记的事带回来。 */
    private static final String[] EMOTION = {"累", "烦", "难受", "难过", "压力", "焦虑", "撑不住",
            "没力气", "崩溃", "不开心", "失眠", "睡不着", "想哭"};

    private SelfMem() {
    }

    public static synchronized JSONArray list(Context c) {
        JSONArray a = Sg.readArr(c, FILE);
        boolean changed = false;
        long now = System.currentTimeMillis();
        for (int k = 0; k < a.length(); k++) {
            JSONObject o = a.optJSONObject(k);
            if (o == null) continue;
            try {
                if (!o.has("status")) {
                    o.put("status", o.optBoolean("done", false) ? "done" : "active");
                    changed = true;
                }
                if (!o.has("confidence")) {
                    o.put("confidence", "promise".equals(o.optString("kind")) ? 0.6
                            : "plan".equals(o.optString("kind")) ? 0.8 : 0.6);
                    changed = true;
                }
                if (!o.has("validFrom")) {
                    o.put("validFrom", o.optLong("ts", now));
                    changed = true;
                }
                if (!o.has("expiresAt")) {
                    o.put("expiresAt", 0L);
                    changed = true;
                }
                if (!o.has("sourceQuote")) {
                    o.put("sourceQuote", "");
                    changed = true;
                }
                if (!o.has("evidenceCount")) {
                    o.put("evidenceCount", 1);
                    changed = true;
                }
                if (!o.has("doneConfirmed")) {
                    o.put("doneConfirmed", false);
                    changed = true;
                }
                if (!o.has("doneDismissed")) {
                    o.put("doneDismissed", false);
                    changed = true;
                }
            } catch (Throwable ignored) {
            }
        }
        if (changed) Sg.writeArr(c, FILE, a);
        return a;
    }

    public static String kindName(String kind) {
        if ("promise".equals(kind)) return "她答应过的事";
        if ("plan".equals(kind)) return "你提过的安排";
        if ("like".equals(kind)) return "她自己的喜好";
        if ("concern".equals(kind)) return "她惦记的事";
        return "她记得的事";
    }

    public static synchronized void add(Context c, String text, String kind) {
        addFull(c, text, kind, defaultConfidence(kind), 0L, "");
    }

    public static synchronized void addFull(Context c, String text, String kind,
                                            double confidence, long expiresAt, String quote) {
        if (text == null) return;
        String t = text.trim();
        if (t.length() < 3 || t.length() > 80) return;
        JSONArray a = list(c);
        for (int k = 0; k < a.length(); k++) {
            JSONObject o = a.optJSONObject(k);
            if (o == null || !t.equals(o.optString("text", ""))) continue;
            try {
                o.put("ts", System.currentTimeMillis());
                o.put("confidence", Math.min(1.0, Math.max(o.optDouble("confidence", 0.5),
                        confidence) + ("concern".equals(kind) ? 0.1 : 0)));
                if (expiresAt > 0) o.put("expiresAt", Math.max(o.optLong("expiresAt", 0), expiresAt));
                if (o.optString("sourceQuote", "").isEmpty()) o.put("sourceQuote", quote == null ? "" : quote);
                o.put("evidenceCount", o.optInt("evidenceCount", 1) + 1);
                if (o.optBoolean("done", false)) {
                    o.put("done", false);
                    o.put("status", "active");
                }
                Sg.writeArr(c, FILE, a);
            } catch (Throwable ignored) {
            }
            return;
        }
        try {
            JSONObject o = new JSONObject();
            o.put("id", "s" + System.currentTimeMillis() + "_" + (int) (Math.random() * 999));
            o.put("text", t);
            o.put("kind", kind == null || kind.isEmpty() ? "note" : kind);
            o.put("done", false);
            o.put("status", "active");
            o.put("confidence", Math.max(0, Math.min(1, confidence)));
            o.put("validFrom", System.currentTimeMillis());
            o.put("expiresAt", expiresAt);
            o.put("sourceQuote", quote == null ? "" : (quote.length() > 200 ? quote.substring(0, 200) : quote));
            o.put("evidenceCount", 1);
            o.put("ts", System.currentTimeMillis());
            a.put(o);
            if (a.length() > MAX_ITEMS) a = trim(a);
            Sg.writeArr(c, FILE, a);
        } catch (Throwable ignored) {
        }
    }

    private static JSONArray trim(JSONArray a) {
        JSONArray live = new JSONArray();
        JSONArray done = new JSONArray();
        for (int k = 0; k < a.length(); k++) {
            JSONObject o = a.optJSONObject(k);
            if (o == null) continue;
            if (o.optBoolean("done", false)) done.put(o);
            else live.put(o);
        }
        JSONArray out = new JSONArray();
        int keepLive = Math.min(live.length(), MAX_ITEMS - 10);
        for (int k = Math.max(0, live.length() - keepLive); k < live.length(); k++) out.put(live.opt(k));
        int room = MAX_ITEMS - out.length();
        for (int k = Math.max(0, done.length() - room); k < done.length(); k++) out.put(done.opt(k));
        return out;
    }

    /** 从她自己的回复里提取承诺。 */
    public static void observeHer(Context c, String reply) {
        if (reply == null || reply.isEmpty()) return;
        if (!containsAny(reply, HER_PROMISE)) return;
        String sentence = pick(reply, HER_PROMISE);
        if (sentence.length() > 60) sentence = sentence.substring(0, 60);
        addFull(c, sentence, "promise", 0.6, System.currentTimeMillis() + 30 * 86400000L, reply);
    }

    /**
     * 从用户的话里提取「未来安排」并记下；同时检查之前的安排是否已经兑现。
     * 兑现不是靠 AI 判断，而是靠后续对话里的关键词重合本地匹配。
     */
    public static void observeUser(Context c, String text) {
        if (text == null || text.trim().isEmpty()) return;
        String t = text.trim();
        fulfill(c, t);
        if (question(t)) return;
        boolean firstPerson = t.contains("我") || t.contains("俺") || t.contains("咱");
        if (firstPerson && containsAny(t, FUTURE) && containsAny(t, TODO)) {
            String sentence = pick(t, FUTURE);
            if (sentence.length() >= 4 && sentence.length() <= 60) {
                addFull(c, sentence, "plan", 0.8, expiresFor(t), t);
            }
        }
        if (containsAny(t, CONCERN) && !containsAny(t, RELIEF)) {
            String sentence = pick(t, CONCERN);
            if (sentence.length() >= 2 && sentence.length() <= 50) {
                addFull(c, sentence, "concern", 0.55, System.currentTimeMillis() + 7 * 86400000L, t);
            }
        }
    }

    /** 如果新消息里出现了某条安排的多个关键词，就认为兑现了；痛点必须命中自身关键词。 */
    private static void fulfill(Context c, String text) {
        JSONArray a = list(c);
        boolean changed = false;
        boolean relief = containsAny(text, RELIEF);
        for (int k = 0; k < a.length(); k++) {
            JSONObject o = a.optJSONObject(k);
            if (o == null || o.optBoolean("done", false)) continue;
            String kind = o.optString("kind", "");
            String item = o.optString("text", "");
            List<String> grams = grams(item);
            if (grams.isEmpty()) continue;
            int hit = 0;
            for (String g : grams) if (text.contains(g)) hit++;
            if ("plan".equals(kind)) {
                int need = grams.size() <= 4 ? 1 : 2;
                if (hit >= need) {
                    try {
                        o.put("done", true);
                        o.put("status", "done");
                        o.put("doneAt", System.currentTimeMillis());
                        changed = true;
                        Intimacy.gain(c, 1.2, "聊到了之前提过的事");
                    } catch (Throwable ignored) {
                    }
                }
            } else if ("concern".equals(kind)) {
                // 必须命中这个痛点本身，再出现改善信号才结案；
                // 一句“睡好了”不能关掉“加班”“焦虑”等其它痛点
                if (relief && hit >= 1) {
                    try {
                        o.put("done", true);
                        o.put("status", "done");
                        o.put("doneAt", System.currentTimeMillis());
                        changed = true;
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        if (changed) Sg.writeArr(c, FILE, a);
    }

    /** 计划里的实词 2 字片段，去掉时间词与虚词。 */
    private static List<String> grams(String text) {
        List<String> out = new ArrayList<>();
        Set<String> set = new LinkedHashSet<>();
        String clean = text.replaceAll("[\\s，。！？、,.!?~～:：;；\"'“”‘’()（）\\[\\]【】]", "");
        for (int k = 0; k + 2 <= clean.length() && set.size() < 12; k++) {
            String g = clean.substring(k, k + 2);
            if (NOISE.contains(g)) continue;
            set.add(g);
        }
        out.addAll(set);
        return out;
    }

    /** 取出包含关键词的那一小句（截到标点为止），让记忆读起来像人话。 */
    private static String pick(String text, String[] keys) {
        int at = -1;
        for (String k : keys) {
            int i = text.indexOf(k);
            if (i >= 0 && (at < 0 || i < at)) at = i;
        }
        if (at < 0) at = 0;
        int start = Math.max(0, at - 8);
        int end = text.length();
        for (int k = at; k < text.length(); k++) {
            char ch = text.charAt(k);
            if (ch == '。' || ch == '！' || ch == '？' || ch == '；' || ch == '\n') {
                end = k;
                break;
            }
        }
        String s = text.substring(start, Math.min(text.length(), end)).trim();
        return s.replaceAll("^[，,。.]", "").trim();
    }

    /** 兼容旧调用：没有 query 时只取今天到期的计划和明确的高置信度痛点。 */
    public static String prompt(Context c, int limit) {
        return promptRelevant(c, "", limit);
    }

    /**
     * 只注入与当前话题相关的计划/承诺/痛点，最多 limit 条；不相关就不出现。
     */
    public static String promptRelevant(Context c, String query, int limit) {
        JSONArray a = list(c);
        long now = System.currentTimeMillis();
        String q = query == null ? "" : query.trim();
        List<String> qg = grams(q);
        boolean emotion = containsAny(q, EMOTION);
        List<JSONObject> pick = new ArrayList<>();
        List<Double> scores = new ArrayList<>();
        for (int k = 0; k < a.length(); k++) {
            JSONObject o = a.optJSONObject(k);
            if (o == null || o.optBoolean("done", false)) continue;
            if ("done".equals(o.optString("status", "active"))) continue;
            long exp = o.optLong("expiresAt", 0);
            if (exp > 0 && exp < now) continue;
            double score = 0;
            if (q.isEmpty()) {
                long due = exp > 0 ? exp - now : Long.MAX_VALUE;
                if (("plan".equals(o.optString("kind")) && due <= 3 * 86400000L)
                        || ("concern".equals(o.optString("kind")) && o.optDouble("confidence", 0.5) >= 0.7)) {
                    score = 1;
                }
            } else {
                int hit = 0;
                String text = o.optString("text", "");
                for (String g : qg) if (text.contains(g)) hit++;
                if (hit > 0) score = hit;
                if ("concern".equals(o.optString("kind")) && emotion) score += 0.6;
                if ("plan".equals(o.optString("kind")) && exp > 0 && exp - now <= 3 * 86400000L) score += 0.5;
            }
            if (score <= 0) continue;
            pick.add(o);
            scores.add(score);
        }
        for (int i = 0; i < pick.size(); i++) {
            for (int j = i + 1; j < pick.size(); j++) {
                if (scores.get(j) > scores.get(i)) {
                    JSONObject to = pick.get(i);
                    pick.set(i, pick.get(j));
                    pick.set(j, to);
                    Double ts = scores.get(i);
                    scores.set(i, scores.get(j));
                    scores.set(j, ts);
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (JSONObject o : pick) {
            if (n >= limit) break;
            long ts = o.optLong("ts", o.optLong("validFrom", 0L));
            if (ts > 0L) {
                String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date(ts));
                sb.append("- [最近确认于：").append(stamp).append("] ");
            } else {
                sb.append("- ");
            }
            sb.append(kindName(o.optString("kind", ""))).append("：")
                    .append(o.optString("text", "")).append('\n');
            n++;
        }
        if (sb.length() == 0) return "";
        return "你自己记着的事（只在与当前话题相关时用你自己的口吻提，不要当用户原话）：\n" + sb;
    }

    /** 自动判定完成、但用户还没确认的事项；最多返回 5 条，避免变成待办列表。 */
    public static JSONArray pendingDone(Context c) {
        JSONArray out = new JSONArray();
        try {
            JSONArray a = list(c);
            for (int k = a.length() - 1; k >= 0 && out.length() < 5; k--) {
                JSONObject o = a.optJSONObject(k);
                if (o == null) continue;
                if (!o.optBoolean("done", false)) continue;
                if (o.optBoolean("doneConfirmed", false) || o.optBoolean("doneDismissed", false)) continue;
                if (o.optLong("doneAt", 0) <= 0) continue;
                out.put(o);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /**
     * 用户确认/否认自动完成。
     * 确认后才写入共同经历；否认只隐藏待确认项，不改原文。
     */
    public static synchronized boolean confirmDone(Context c, String id, boolean done) {
        if (id == null || id.isEmpty()) return false;
        JSONArray a = list(c);
        boolean hit = false;
        for (int k = 0; k < a.length(); k++) {
            JSONObject o = a.optJSONObject(k);
            if (o == null || !id.equals(o.optString("id", ""))) continue;
            if (!o.optBoolean("done", false)) return false;
            try {
                if (done) {
                    o.put("doneConfirmed", true);
                    o.put("confirmedAt", System.currentTimeMillis());
                    JSONObject extra = new JSONObject();
                    extra.put("source", "plan_confirmed");
                    extra.put("editable", false);
                    extra.put("confidence", 1.0);
                    extra.put("refId", id);
                    extra.put("ts", o.optLong("doneAt", System.currentTimeMillis()));
                    Timeline.add(c, "done:" + id, "moment",
                            o.optString("text", ""), extra);
                } else {
                    o.put("doneDismissed", true);
                }
                hit = true;
            } catch (Throwable ignored) {
            }
            break;
        }
        if (hit) Sg.writeArr(c, FILE, a);
        return hit;
    }

    public static JSONObject summary(Context c) {
        JSONObject o = new JSONObject();
        try {
            JSONArray a = list(c);
            JSONArray live = new JSONArray();
            int done = 0;
            for (int k = a.length() - 1; k >= 0; k--) {
                JSONObject it = a.optJSONObject(k);
                if (it == null) continue;
                if (it.optBoolean("done", false)) done++;
                else live.put(it);
            }
            o.put("items", live);
            o.put("total", live.length());
            o.put("done", done);
            o.put("pendingDone", pendingDone(c));
        } catch (Throwable ignored) {
        }
        return o;
    }

    public static synchronized void remove(Context c, String id) {
        JSONArray a = list(c);
        JSONArray b = new JSONArray();
        for (int k = 0; k < a.length(); k++) {
            JSONObject o = a.optJSONObject(k);
            if (o != null && !id.equals(o.optString("id", ""))) b.put(o);
        }
        Sg.writeArr(c, FILE, b);
    }

    private static double defaultConfidence(String kind) {
        if ("plan".equals(kind)) return 0.8;
        if ("concern".equals(kind)) return 0.55;
        return 0.6;
    }

    private static long expiresFor(String text) {
        long now = System.currentTimeMillis();
        if (text.contains("明天")) return now + 86400000L;
        if (text.contains("后天")) return now + 2 * 86400000L;
        if (text.contains("下周")) return now + 7 * 86400000L;
        if (text.contains("下个月")) return now + 30 * 86400000L;
        if (text.contains("今晚") || text.contains("待会") || text.contains("一会")) return now + 86400000L;
        return now + 3 * 86400000L;
    }

    private static boolean question(String t) {
        if (t == null) return false;
        String s = t.trim();
        return s.endsWith("?") || s.endsWith("？") || s.endsWith("吗") || s.endsWith("呢")
                || s.contains("是不是") || s.contains("要不要") || s.contains("能不能");
    }

    private static boolean containsAny(String s, String[] arr) {
        if (s == null) return false;
        for (String k : arr) if (s.contains(k)) return true;
        return false;
    }
}
