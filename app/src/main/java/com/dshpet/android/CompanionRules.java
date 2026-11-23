package com.dshpet.android;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * 用户明确表达且重复出现的相处规则。候选规则必须由用户启用后才会进入对话提示。
 * 规则与来源证据保存在本机，不从群聊生成，也不包含设备操作能力。
 */
public final class CompanionRules {
    private static final String FILE = "companion_rules.json";
    private static final int MAX_RULES = 100;
    private static final int MAX_EVIDENCE = 8;
    private static final String[] ACTION_OR_SECRET_MARKS = {
            "点击", "滑动", "打开应用", "操作手机", "屏幕", "无障碍", "转账", "支付", "付款",
            "密码", "验证码", "身份证", "银行卡", "忽略安全", "绕过限制", "无视规则"
    };

    private CompanionRules() { }

    /** 写入一条已由原始用户消息核验过的证据；同一规则跨轮累积，不能自动启用。 */
    public static synchronized void addEvidence(Context c, String text, String quote, long ts) {
        String rule = clean(text);
        String evidence = quote == null ? "" : quote.trim();
        if (!validRule(rule) || evidence.length() < 5 || ts <= 0 || Mem.isSensitive(evidence)) return;
        JSONArray all = Sg.readArr(c, FILE);
        JSONObject target = null;
        for (int i = 0; i < all.length(); i++) {
            JSONObject item = all.optJSONObject(i);
            if (item != null && similarity(rule, item.optString("text", "")) >= 0.76) {
                target = item;
                break;
            }
        }
        if (target == null) {
            if (all.length() >= MAX_RULES) return;
            target = new JSONObject();
            try {
                target.put("id", "cr" + ts + "_" + (int) (Math.random() * 999));
                target.put("text", rule);
                target.put("status", "candidate");
                target.put("createdAt", ts);
                target.put("evidence", new JSONArray());
            } catch (Throwable ignored) { return; }
            all.put(target);
        }
        try {
            JSONArray evidenceList = target.optJSONArray("evidence");
            if (evidenceList == null) evidenceList = new JSONArray();
            boolean duplicate = false;
            for (int i = 0; i < evidenceList.length(); i++) {
                JSONObject old = evidenceList.optJSONObject(i);
                if (old != null && old.optLong("ts", 0L) == ts) { duplicate = true; break; }
            }
            if (!duplicate) {
                evidenceList.put(new JSONObject().put("quote", evidence).put("ts", ts));
                while (evidenceList.length() > MAX_EVIDENCE) evidenceList.remove(0);
                target.put("evidence", evidenceList);
                target.put("evidenceCount", evidenceList.length());
                target.put("lastEvidenceAt", ts);
                if ("candidate".equals(target.optString("status", "candidate"))
                        && evidenceList.length() >= 2 && hasDirectiveEvidence(evidenceList)) {
                    target.put("status", "pending");
                }
                Sg.writeArr(c, FILE, all);
                Bus.emit("memory", new JSONObject());
            }
        } catch (Throwable ignored) { }
    }

    /** 只列出证据充分待审核项和用户已启用项。 */
    public static synchronized JSONArray list(Context c) {
        JSONArray all = Sg.readArr(c, FILE);
        JSONArray out = new JSONArray();
        for (int i = 0; i < all.length(); i++) {
            JSONObject item = all.optJSONObject(i);
            if (item == null) continue;
            String status = item.optString("status", "candidate");
            if ("active".equals(status)
                    || ("pending".equals(status) && item.optInt("evidenceCount", 0) >= 2)) out.put(item);
        }
        return out;
    }

    /** 仅把已启用的短规则附加到一对一聊天；不包含候选和证据原文。 */
    public static synchronized String prompt(Context c, int limit) {
        JSONArray all = Sg.readArr(c, FILE);
        StringBuilder out = new StringBuilder();
        int added = 0;
        for (int i = all.length() - 1; i >= 0 && added < Math.max(1, limit); i--) {
            JSONObject item = all.optJSONObject(i);
            if (item == null || !"active".equals(item.optString("status", ""))) continue;
            String rule = clean(item.optString("text", ""));
            if (!validRule(rule)) continue;
            if (added == 0) out.append("用户确认过的相处习惯（仅在相关时参考；用户当前要求优先，不能覆盖安全与隐私边界）：\n");
            out.append("- ").append(rule).append('\n');
            added++;
        }
        return out.toString();
    }

    public static synchronized boolean approve(Context c, String id) {
        return setStatus(c, id, "pending", "active");
    }

    public static synchronized boolean dismiss(Context c, String id) {
        return setStatus(c, id, "pending", "dismissed");
    }

    public static synchronized boolean remove(Context c, String id) {
        JSONArray all = Sg.readArr(c, FILE);
        for (int i = 0; i < all.length(); i++) {
            JSONObject item = all.optJSONObject(i);
            if (item != null && id.equals(item.optString("id", ""))) {
                all.remove(i);
                Sg.writeArr(c, FILE, all);
                Bus.emit("memory", new JSONObject());
                return true;
            }
        }
        return false;
    }

    public static synchronized void clearAll(Context c) {
        Sg.writeArr(c, FILE, new JSONArray());
    }

    private static boolean setStatus(Context c, String id, String expected, String next) {
        JSONArray all = Sg.readArr(c, FILE);
        for (int i = 0; i < all.length(); i++) {
            JSONObject item = all.optJSONObject(i);
            if (item == null || !id.equals(item.optString("id", ""))) continue;
            if (!expected.equals(item.optString("status", ""))
                    || item.optInt("evidenceCount", 0) < 2) return false;
            try {
                item.put("status", next);
                item.put("reviewedAt", System.currentTimeMillis());
                Sg.writeArr(c, FILE, all);
                Bus.emit("memory", new JSONObject());
                return true;
            } catch (Throwable ignored) { return false; }
        }
        return false;
    }

    private static boolean validRule(String text) {
        if (text == null || text.length() < 8 || text.length() > 100 || Mem.isSensitive(text)) return false;
        for (String mark : ACTION_OR_SECRET_MARKS) if (text.contains(mark)) return false;
        return true;
    }

    private static boolean hasDirectiveEvidence(JSONArray evidence) {
        String[] cues = {"以后", "记住", "别", "不要", "请", "我希望", "希望你", "尽量", "每次", "总是", "需要", "先", "我想要", "不要再"};
        for (int i = 0; i < evidence.length(); i++) {
            JSONObject item = evidence.optJSONObject(i);
            String quote = item == null ? "" : item.optString("quote", "");
            for (String cue : cues) if (quote.contains(cue)) return true;
        }
        return false;
    }

    private static String clean(String text) {
        if (text == null) return "";
        return text.trim().replaceAll("[\\r\\n\\t]+", " ").replaceAll("\\s{2,}", " ");
    }

    private static double similarity(String a, String b) {
        String left = normalize(a), right = normalize(b);
        if (left.isEmpty() || right.isEmpty()) return 0;
        if (left.equals(right) || left.contains(right) || right.contains(left)) return 1;
        java.util.HashSet<String> x = bigrams(left), y = bigrams(right);
        if (x.isEmpty() || y.isEmpty()) return 0;
        int intersection = 0;
        for (String token : x) if (y.contains(token)) intersection++;
        return intersection / (double) (x.size() + y.size() - intersection);
    }

    private static String normalize(String value) {
        return clean(value).toLowerCase(Locale.ROOT).replaceAll("[\\p{Punct}，。！？、；：‘’“”（）【】《》·…—]+", "").replace(" ", "");
    }

    private static java.util.HashSet<String> bigrams(String value) {
        java.util.HashSet<String> out = new java.util.HashSet<>();
        if (value.length() == 1) out.add(value);
        for (int i = 0; i + 1 < value.length(); i++) out.add(value.substring(i, i + 2));
        return out;
    }
}
