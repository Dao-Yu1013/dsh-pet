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
 * QQ 群聊记忆的独立打包层：把各群已经压缩好的摘要汇总到 group_memory.json。
 * 只存群摘要，不存群原始聊天，也不存任何私聊内容。
 * 读取方向是单向的：私聊/桌面聊天可以从这里按话题取群记忆，群聊只用自己的群摘要，不能读私聊。
 */
public final class GroupMemory {
    private static final String FILE = "group_memory.json";
    private static final int MAX_ITEMS = 200;
    private static final int MAX_TEXT_CHARS = 420;
    private static final int MAX_PROMPT_CHARS = 900;
    private static final int MAX_PER_GROUP = 2;

    /** 双字切分时不参与匹配的常见虚词。 */
    private static final Set<String> STOP = new HashSet<String>() {{
        add("我们"); add("你们"); add("他们"); add("什么"); add("怎么"); add("这个"); add("那个");
        add("就是"); add("还是"); add("可以"); add("然后"); add("现在"); add("今天"); add("昨天");
        add("明天"); add("一个"); add("没有"); add("知道"); add("觉得"); add("应该"); add("可能");
    }};

    private GroupMemory() { }

    /** 群摘要落盘后调用；同一段摘要不重复写，最多保留 MAX_ITEMS 条。 */
    static synchronized void add(Context c, String groupId, String groupName, String text) {
        if (c == null || text == null) return;
        String body = text.trim();
        if (body.isEmpty() || body.contains("[[无摘要]]")) return;
        if (body.length() > MAX_TEXT_CHARS) body = body.substring(0, MAX_TEXT_CHARS).trim();
        if (body.isEmpty()) return;
        String gid = groupId == null ? "" : groupId.trim();
        String gname = groupName == null ? "" : groupName.trim();
        try {
            JSONArray arr = Sg.readArr(c, FILE);
            JSONArray out = new JSONArray();
            int start = Math.max(0, arr.length() - MAX_ITEMS + 1);
            for (int k = start; k < arr.length(); k++) {
                JSONObject o = arr.optJSONObject(k);
                if (o == null) continue;
                // 同一条摘要重复出现（例如同一群会话被重复收尾）时只保留一次
                if (body.equals(o.optString("text", "")) && gid.equals(o.optString("groupId", ""))) return;
                out.put(o);
            }
            out.put(new JSONObject()
                    .put("groupId", gid)
                    .put("groupName", gname.isEmpty() ? gid : gname)
                    .put("ts", System.currentTimeMillis())
                    .put("text", body));
            Sg.writeArr(c, FILE, out);
        } catch (Throwable ignored) { }
    }

    /**
     * 给单聊/桌面聊天用的群记忆片段。按当前话题的 2 字片段命中筛选，
     * 没有相关话题时返回空，不硬塞旧群事。群聊自己不会调用这里。
     */
    static String prompt(Context c, String query, int limit) {
        if (c == null) return "";
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) return "";
        List<String> grams = grams(q);
        if (grams.isEmpty()) return "";
        JSONArray arr = Sg.readArr(c, FILE);
        if (arr.length() == 0) return "";
        List<Hit> hits = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (int k = 0; k < arr.length(); k++) {
            JSONObject o = arr.optJSONObject(k);
            if (o == null) continue;
            String text = o.optString("text", "");
            if (text.isEmpty()) continue;
            String hay = text + " " + o.optString("groupName", "");
            int matched = 0;
            for (String g : grams) {
                if (hay.contains(g)) matched++;
            }
            if (matched == 0) continue;
            double relevance = Math.min(1.0, matched / (double) Math.max(1, Math.min(grams.size(), 6)));
            long ts = o.optLong("ts", 0L);
            // 相关度优先，其次新近程度
            double recency = ts <= 0 ? 0 : Math.max(0, 1.0 - (now - ts) / (1000.0 * 60 * 60 * 24 * 180));
            hits.add(new Hit(o, 0.75 * relevance + 0.25 * recency));
        }
        if (hits.isEmpty()) return "";
        hits.sort((x, y) -> Double.compare(y.score, x.score));
        StringBuilder sb = new StringBuilder();
        sb.append("你在 QQ 群里了解到的事（来自群聊，不是他和你的一对一聊天；只有当前话题确实相关时才自然提起，"
                + "要说清是在群里看到的，别当成他的原话，也别列举）：\n");
        Set<String> used = new HashSet<>();
        java.util.Map<String, Integer> perGroup = new java.util.HashMap<>();
        int added = 0;
        for (Hit h : hits) {
            if (added >= limit) break;
            String gid = h.o.optString("groupId", "");
            if (gid.isEmpty()) gid = h.o.optString("groupName", "");
            int n = perGroup.containsKey(gid) ? perGroup.get(gid) : 0;
            if (n >= MAX_PER_GROUP) continue;
            String text = h.o.optString("text", "").trim();
            if (!used.add(text)) continue;
            perGroup.put(gid, n + 1);
            String line = "- 群「" + safe(h.o.optString("groupName", gid)) + "」·"
                    + date(h.o.optLong("ts", 0L)) + "：" + text + "\n";
            if (sb.length() + line.length() > MAX_PROMPT_CHARS) break;
            sb.append(line);
            added++;
        }
        return added == 0 ? "" : sb.toString();
    }

    private static final class Hit {
        final JSONObject o;
        final double score;

        Hit(JSONObject o, double score) {
            this.o = o;
            this.score = score;
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
        out.addAll(set);
        return out;
    }

    private static String safe(String v) {
        String s = v == null ? "" : v.replace('\n', ' ').trim();
        return s.length() > 40 ? s.substring(0, 40) : s;
    }

    private static String date(long ts) {
        if (ts <= 0) return "";
        return new SimpleDateFormat("M月d日", Locale.CHINA).format(new Date(ts));
    }
}
