package com.dshpet.android;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Local evidence log for companion-led app sessions. Entries distinguish plans from verified observations. */
public final class ExperienceLog {
    private static final String FILE = "companion_experiences.json";
    private static final int MAX = 300;
    private static final Object LOCK = new Object();

    private ExperienceLog() { }

    public static void record(Context c, String state, String target, String detail) {
        if (c == null || !valid(state)) return;
        try {
            JSONObject item = new JSONObject();
            item.put("ts", System.currentTimeMillis());
            item.put("state", state);
            item.put("target", clean(target, 100));
            item.put("detail", clean(detail, 1200));
            synchronized (LOCK) {
                JSONArray old = Sg.readArr(c, FILE);
                JSONArray next = new JSONArray();
                for (int i = Math.max(0, old.length() - MAX + 1); i < old.length(); i++) {
                    JSONObject keep = old.optJSONObject(i);
                    if (keep != null) next.put(keep);
                }
                next.put(item);
                Sg.writeArr(c, FILE, next);
            }
        } catch (Throwable ignored) { }
    }

    /** Only verified observations are exposed as things she has seen or learned. */
    public static String prompt(Context c) {
        if (c == null || !Cfg.load(c).b("watchApp", true)) return "";
        try {
            JSONArray a = Sg.readArr(c, FILE);
            StringBuilder out = new StringBuilder();
            int n = 0;
            for (int i = a.length() - 1; i >= 0 && n < 4; i--) {
                JSONObject e = a.optJSONObject(i);
                if (e == null || !"observed".equals(e.optString("state"))) continue;
                String target = e.optString("target", "").trim();
                String detail = e.optString("detail", "").trim();
                if (target.isEmpty() || detail.isEmpty()) continue;
                if (n++ == 0) out.append("你有这些经过实际屏幕读取确认的经历（只可说看到了这些，不代表看完或完全学会）：\n");
                out.append("- ").append(target).append("：").append(detail).append('\n');
            }
            return out.toString();
        } catch (Throwable ignored) { return ""; }
    }

    /**
     * Read-only, privacy-filtered verified observations for the 3D memory field.
     * Plans, failed attempts and unverified intentions are deliberately excluded.
     */
    public static JSONArray graphNodes(Context c, int limit) {
        JSONArray out = new JSONArray();
        if (c == null || limit <= 0) return out;
        try {
            JSONArray entries = Sg.readArr(c, FILE);
            Set<String> seen = new HashSet<>();
            for (int i = entries.length() - 1; i >= 0 && out.length() < Math.min(12, limit); i--) {
                JSONObject entry = entries.optJSONObject(i);
                if (entry == null || !"observed".equals(entry.optString("state", ""))) continue;
                String target = clean(entry.optString("target", ""), 100);
                String detail = clean(entry.optString("detail", ""), 360);
                if (target.isEmpty() || detail.isEmpty() || looksSensitive(target + " " + detail)) continue;
                String dedupe = (target + "\n" + detail).toLowerCase(Locale.ROOT);
                if (!seen.add(dedupe)) continue;

                long ts = entry.optLong("ts", 0L);
                JSONObject node = new JSONObject();
                node.put("id", "experience:" + Long.toString(Math.max(0L, ts), 36) + ":"
                        + Integer.toHexString(dedupe.hashCode()));
                node.put("kind", "experience");
                node.put("type", "event");
                node.put("typeLabel", "真实经历");
                node.put("label", target.length() > 22 ? target.substring(0, 21) + "…" : target);
                node.put("text", target + " · " + detail);
                node.put("target", target);
                node.put("origin", "verified_observation");
                node.put("confidence", 1.0);
                node.put("updatedAt", ts);
                node.put("createdAt", ts);
                node.put("val", 7);
                node.put("tags", new JSONArray());
                out.put(node);
            }
        } catch (Throwable ignored) { }
        return out;
    }

    private static boolean looksSensitive(String text) {
        String value = text == null ? "" : text.toLowerCase(Locale.ROOT);
        return value.contains("验证码") || value.contains("密码") || value.contains("身份证")
                || value.contains("银行卡") || value.contains("卡号") || value.contains("cvv")
                || value.contains("银行密码") || value.contains("api key") || value.contains("token")
                || value.matches(".*(?<!\\d)1[3-9]\\d{9}(?!\\d).*")
                || value.matches(".*[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}.*")
                || value.matches(".*(?<!\\d)\\d{17}[\\dx](?!\\d).*")
                || value.matches(".*(?<!\\d)\\d{16,19}(?!\\d).*");
    }

    private static boolean valid(String state) {
        return "wanted".equals(state) || "asked".equals(state) || "consented".equals(state)
                || "started".equals(state) || "observed".equals(state)
                || "declined".equals(state) || "failed".equals(state) || "completed".equals(state);
    }

    private static String clean(String s, int max) {
        if (s == null) return "";
        String t = s.replace('\r', ' ').replace('\n', ' ').trim();
        return t.length() > max ? t.substring(0, max) : t;
    }
}
