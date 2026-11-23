package com.dshpet.android;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

/** 轻量心情日志：只影响语气与打扰策略，不做任何诊断。 */
public final class MoodLog {
    private MoodLog() {
    }

    public static final String[] ALL = {"happy", "calm", "tired", "annoyed", "focused"};

    public static String label(String m) {
        if ("happy".equals(m)) return "开心";
        if ("calm".equals(m)) return "平静";
        if ("tired".equals(m)) return "累";
        if ("annoyed".equals(m)) return "烦";
        if ("focused".equals(m)) return "专注";
        return "未标记";
    }

    public static void log(Context c, String mood, String note) {
        if (mood == null || mood.isEmpty()) return;
        try {
            JSONObject o = new JSONObject();
            o.put("ts", System.currentTimeMillis());
            o.put("mood", mood);
            o.put("note", note == null ? "" : note);
            Sg.appendLine(c, "mood.jsonl", o);
            Bus.emit("moodlog", o);
        } catch (Throwable ignored) {
        }
    }

    /** 最近 4 小时的心情；tired/annoyed 返回 low，其余返回空。 */
    public static String recent(Context c) {
        try {
            JSONArray a = list(c, 30);
            long min = System.currentTimeMillis() - 4 * 3600000L;
            String last = "";
            for (int k = a.length() - 1; k >= 0; k--) {
                JSONObject o = a.optJSONObject(k);
                if (o == null) continue;
                if (o.optLong("ts", 0) < min) break;
                last = o.optString("mood", "");
                break;
            }
            return "tired".equals(last) || "annoyed".equals(last) ? "low" : "";
        } catch (Throwable t) {
            return "";
        }
    }

    public static JSONArray list(Context c, int limit) {
        JSONArray out = new JSONArray();
        try {
            String s = Sg.read(Sg.file(c, "mood.jsonl"), "");
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

    public static JSONObject summary(Context c) {
        JSONObject o = new JSONObject();
        try {
            JSONArray a = list(c, 200);
            long dayStart = todayStart();
            JSONObject counts = new JSONObject();
            String latest = "";
            long latestTs = 0;
            int today = 0;
            for (int k = 0; k < a.length(); k++) {
                JSONObject m = a.optJSONObject(k);
                if (m == null) continue;
                String mood = m.optString("mood", "");
                if (m.optLong("ts", 0) >= dayStart) {
                    counts.put(mood, counts.optInt(mood, 0) + 1);
                    today++;
                    if (m.optLong("ts", 0) > latestTs) {
                        latestTs = m.optLong("ts", 0);
                        latest = mood;
                    }
                }
            }
            o.put("today", today);
            o.put("counts", counts);
            o.put("latest", latest);
            o.put("latestLabel", label(latest));
            o.put("items", a);
        } catch (Throwable ignored) {
        }
        return o;
    }

    public static JSONObject week(Context c) {
        JSONObject o = new JSONObject();
        try {
            JSONArray a = list(c, 500);
            long start = System.currentTimeMillis() - 7 * 86400000L;
            JSONObject counts = new JSONObject();
            for (int k = 0; k < a.length(); k++) {
                JSONObject m = a.optJSONObject(k);
                if (m == null || m.optLong("ts", 0) < start) continue;
                String mood = m.optString("mood", "");
                counts.put(mood, counts.optInt(mood, 0) + 1);
            }
            o.put("counts", counts);
        } catch (Throwable ignored) {
        }
        return o;
    }

    private static long todayStart() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.set(java.util.Calendar.HOUR_OF_DAY, 0);
        c.set(java.util.Calendar.MINUTE, 0);
        c.set(java.util.Calendar.SECOND, 0);
        c.set(java.util.Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }
}
