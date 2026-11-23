package com.dshpet.android;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 本地记忆检索：只从长期记忆里挑与当前话题真正相关的事实。
 * 不包含日记/她的印象/承诺（那些由 SelfMem/Diary 按需单独取），
 * 不相关时返回空，绝不把一批高权重旧事强行塞进提示词。
 */
public final class Recall {
    private Recall() {
    }

    private static final class Item {
        String id;
        String text;
        String type;
        String quote;
        String tags;
        double weight;
        double confidence;
        long confirmedAt;
        long validFrom;
        long expiresAt;
        double score;
        double relevance;
    }

    /** 给 prompt 用的格式化结果；没有相关记忆时返回空串。 */
    public static String prompt(Context c, String query, int limit) {
        JSONArray a = rank(c, query, limit);
        if (a.length() == 0) return "";
        boolean exact = exactAsk(query);
        StringBuilder sb = new StringBuilder("此刻你想起的事（只在与当前话题相关时自然提起）：\n");
        JSONArray used = new JSONArray();
        for (int k = 0; k < a.length(); k++) {
            JSONObject o = a.optJSONObject(k);
            if (o == null) continue;
            String type = o.optString("type", "fact");
            String label = typeLabel(type);
            String date = "";
            long ts = o.optLong("validFrom", 0);
            if (ts > 0) date = "·记录于 " + shortDate(ts);
            sb.append("- [").append(label).append(date).append("] ");
            if (exact && !o.optString("quote", "").isEmpty()) {
                sb.append("原话：“").append(o.optString("quote", "")).append("”");
            } else {
                sb.append(o.optString("text", ""));
            }
            sb.append('\n');
            used.put(o.optString("id", ""));
        }
        Mem.recordUse(c, used);
        return sb.toString();
    }

    /**
     * 返回按相关度排序的条目。query 为空（主动说话）时也返回空：
     * 没有话题就不要硬提过去。
     */
    public static JSONArray rank(Context c, String query, int limit) {
        JSONArray out = new JSONArray();
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) return out;
        boolean exact = exactAsk(q);
        List<String> grams = grams(q);
        if (grams.isEmpty() && !exact) return out;
        List<Item> pool = collect(c);
        if (pool.isEmpty()) return out;

        // IDF：在 active 记忆集合里统计 2-gram 文档频率
        Map<String, Integer> df = new HashMap<>();
        for (Item it : pool) {
            Set<String> gs = new HashSet<>(grams(it.text + " " + (it.tags == null ? "" : it.tags)));
            for (String g : gs) df.put(g, (df.containsKey(g) ? df.get(g) : 0) + 1);
        }
        int n = Math.max(1, pool.size());
        double queryWeight = 0;
        double maxQueryWeight = 0;
        Map<String, Double> qw = new HashMap<>();
        for (String g : grams) {
            int d = df.containsKey(g) ? df.get(g) : 0;
            double w = Math.log(1 + n / (double) Math.max(1, d));
            qw.put(g, w);
            queryWeight += w;
            maxQueryWeight = Math.max(maxQueryWeight, w);
        }
        long now = System.currentTimeMillis();
        List<Item> hits = new ArrayList<>();
        for (Item it : pool) {
            double hitW = 0;
            double maxHitW = 0;
            String hay = it.text + " " + (it.quote == null ? "" : it.quote) + " "
                    + (it.tags == null ? "" : it.tags);
            for (String g : grams) {
                if (!hay.contains(g)) continue;
                double w = qw.containsKey(g) ? qw.get(g) : 1.0;
                hitW += w;
                maxHitW = Math.max(maxHitW, w);
            }
            double coverage = queryWeight <= 0 ? 0 : hitW / queryWeight;
            double strength = maxQueryWeight <= 0 ? 0 : maxHitW / maxQueryWeight;
            // 一个强主题词命中就值得召回；常见碎片词则由 IDF 压低
            double relevance = 0.55 * strength + 0.45 * coverage;
            // 精确回忆必须找到原话，找不到就不返回
            if (exact && (it.quote == null || it.quote.isEmpty())) continue;
            if (relevance < (exact ? 0.12 : 0.18)) {
                // 「继续/上次/进度」这类话，允许把活跃项目/计划接回来
                if (!(continuation(q) && ("project".equals(it.type) || "plan".equals(it.type)))) continue;
            }
            double importance = Math.min(1.0, it.weight / 2.5) + (it.confidence >= 0.9 ? 0.1 : 0);
            double recency = recency(it, now);
            double typePriority = typePriority(it.type);
            it.relevance = relevance;
            it.score = 0.45 * relevance + 0.20 * importance + 0.15 * it.confidence
                    + 0.10 * recency + 0.10 * typePriority;
            if (it.score < 0.35) continue;
            hits.add(it);
        }
        hits.sort((x, y) -> Double.compare(y.score, x.score));
        Set<String> seen = new HashSet<>();
        for (Item it : hits) {
            if (out.length() >= Math.max(1, limit)) break;
            if (it.text == null || it.text.trim().isEmpty()) continue;
            if (!seen.add(it.text)) continue;
            try {
                JSONObject o = new JSONObject();
                o.put("id", it.id);
                o.put("text", it.text);
                o.put("type", it.type);
                o.put("quote", it.quote == null ? "" : it.quote);
                o.put("validFrom", it.validFrom);
                o.put("score", Math.round(it.score * 100) / 100.0);
                out.put(o);
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    private static List<Item> collect(Context c) {
        List<Item> out = new ArrayList<>();
        JSONArray mem = Mem.normalize(c);
        for (int k = 0; k < mem.length(); k++) {
            JSONObject o = mem.optJSONObject(k);
            if (o == null) continue;
            if (!"active".equals(o.optString("status", "active"))) continue;
            if ("secret".equals(o.optString("sensitivity", "normal"))) continue;
            if ("observation".equals(o.optString("type", "fact"))) continue;
            long exp = o.optLong("expiresAt", 0);
            if (exp > 0 && exp < System.currentTimeMillis()) continue;
            Item it = new Item();
            it.id = o.optString("id", "");
            it.text = o.optString("text", "");
            it.type = o.optString("type", "fact");
            it.quote = o.optString("sourceQuote", "");
            JSONArray tags = o.optJSONArray("tags");
            if (tags != null) {
                StringBuilder sb = new StringBuilder();
                for (int j = 0; j < tags.length(); j++) sb.append(tags.optString(j)).append(' ');
                it.tags = sb.toString();
            }
            it.weight = o.optDouble("weight", 1.0);
            it.confidence = o.optDouble("confidence", 0.7);
            it.confirmedAt = o.optLong("lastConfirmedAt", o.optLong("ts", 0));
            it.validFrom = o.optLong("validFrom", o.optLong("ts", 0));
            it.expiresAt = exp;
            out.add(it);
        }
        return out;
    }

    private static double recency(Item it, long now) {
        long base = it.confirmedAt > 0 ? it.confirmedAt : it.validFrom;
        if (base <= 0) return 0.3;
        double days = Math.max(0, (now - base) / 86400000.0);
        double half = "plan".equals(it.type) || "concern".equals(it.type) ? 30
                : "event".equals(it.type) ? 60 : 120;
        return Math.exp(-days / half);
    }

    private static double typePriority(String type) {
        if ("project".equals(type) || "person".equals(type)) return 1.0;
        if ("plan".equals(type) || "preference".equals(type)) return 0.9;
        if ("fact".equals(type)) return 0.8;
        if ("concern".equals(type)) return 0.75;
        return 0.6;
    }

    private static String typeLabel(String type) {
        return Mem.typeName(type);
    }

    private static boolean exactAsk(String q) {
        if (q == null) return false;
        return q.contains("记得") || q.contains("之前") || q.contains("上次")
                || q.contains("原话") || q.contains("说过") || q.contains("哪一天");
    }

    /** 用户在接续之前的事：继续、上次、进度、做到哪。 */
    private static boolean continuation(String q) {
        if (q == null) return false;
        return q.contains("继续") || q.contains("接着") || q.contains("上次")
                || q.contains("之前") || q.contains("进度") || q.contains("做到哪")
                || q.contains("后来") || q.contains("还没做完");
    }

    private static String shortDate(long ts) {
        try {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA);
            return f.format(new java.util.Date(ts));
        } catch (Throwable t) {
            return "";
        }
    }

    /** 把一句话切成中文 2 字片段，作为本地检索的“词”。 */
    private static List<String> grams(String q) {
        List<String> out = new ArrayList<>();
        if (q == null || q.isEmpty()) return out;
        Set<String> set = new LinkedHashSet<>();
        String clean = q.replaceAll("[\\s，。！？、,.!?~～:：;；\"'“”‘’()（）\\[\\]【】]", "");
        if (clean.length() < 2) return out;
        for (int k = 0; k + 2 <= clean.length() && set.size() < 40; k++) {
            String g = clean.substring(k, k + 2);
            if (STOP.contains(g)) continue;
            set.add(g);
        }
        negateFix(set, clean);
        out.addAll(set);
        return out;
    }

    /** 否定前缀修正：避免「我不开心」拆出「开心」，召回一堆开心的记忆。 */
    private static void negateFix(Set<String> set, String clean) {
        for (String neg : NEG) {
            int at = clean.indexOf(neg);
            while (at >= 0) {
                String tail = clean.substring(at + neg.length());
                for (String pos : POS_FEEL) {
                    if (!tail.startsWith(pos)) continue;
                    set.remove(pos);
                    set.add(neg + pos);
                    set.add("不开心");
                    set.add("心情不好");
                    break;
                }
                at = clean.indexOf(neg, at + 1);
            }
        }
    }

    private static final String[] NEG = {"不", "没", "别", "无"};
    private static final String[] POS_FEEL = {"开心", "高兴", "舒服", "爽", "顺利", "满意", "快乐", "不错", "好受"};

    private static final Set<String> STOP = new HashSet<String>() {{
        add("什么"); add("怎么"); add("这个"); add("那个"); add("我们"); add("你们");
        add("他们"); add("现在"); add("今天"); add("明天"); add("昨天"); add("一个");
        add("可以"); add("没有"); add("就是"); add("还是"); add("因为"); add("所以");
        add("但是"); add("如果"); add("知道"); add("觉得"); add("时候"); add("一下");
        add("有点"); add("已经"); add("不是"); add("真的"); add("东西"); add("事情");
    }};
}
