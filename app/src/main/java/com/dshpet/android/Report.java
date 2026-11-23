package com.dshpet.android;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

/** 每周陪伴报告：全部本地统计，不制造焦虑。 */
public final class Report {
    private Report() {
    }

    public static JSONObject weekly(Context c) {
        JSONObject r = new JSONObject();
        try {
            long start = System.currentTimeMillis() - 7 * 86400000L;
            JSONObject focus = Taskbook.focusSummary(c, 7);
            int focusMin = focus.optInt("minutes", 0);
            int focusSessions = focus.optInt("sessions", 0);

            JSONObject well = Wellness.stats(c, 7);
            int sits = well.optInt("stretch", 0);
            int water = well.optInt("water", 0);
            int eyes = well.optInt("eye", 0);

            int chats = countSince(c, "chat.jsonl", start, "role", "user");
            int ads = countSince(c, "adlog.jsonl", start, null, null);
            JSONObject mood = MoodLog.week(c);
            JSONObject moodCounts = mood.optJSONObject("counts");
            if (moodCounts == null) moodCounts = new JSONObject();
            String moodTop = topKey(moodCounts);

            JSONArray music = topMusic(c, start);
            JSONArray topics = topics(c, start);
            JSONObject policy = Policy.summary(c);

            JSONObject o = new JSONObject();
            o.put("from", start);
            o.put("to", System.currentTimeMillis());
            o.put("focusMin", focusMin);
            o.put("focusSessions", focusSessions);
            o.put("stretch", sits);
            o.put("water", water);
            o.put("eye", eyes);
            o.put("chats", chats);
            o.put("ads", ads);
            o.put("mood", moodCounts);
            o.put("moodTop", moodTop);
            o.put("music", music);
            o.put("topics", topics);
            o.put("proactive", policy.optInt("total", 0));
            o.put("text", text(c, focusMin, focusSessions, sits, water, eyes, chats, ads, moodTop, music, topics));
            String name = Data.persona(c).optString("name", "桌宠");
            o.put("name", name);
            o.put("savedAt", System.currentTimeMillis());
            Sg.writeObj(c, "report_last.json", o);
            Sg.writeObj(c, "reports/week-" + weekKey() + ".json", o);
            r = o;
        } catch (Throwable t) {
            try {
                r.put("error", t.getMessage() == null ? "统计失败" : t.getMessage());
            } catch (Throwable ignored) {
            }
        }
        return r;
    }

    private static String text(Context c, int focusMin, int sessions, int sits, int water, int eyes,
                               int chats, int ads, String moodTop, JSONArray music, JSONArray topics) {
        String name = Data.persona(c).optString("name", "我");
        StringBuilder sb = new StringBuilder();
        if (focusMin > 0) {
            sb.append("这周你专注了 ").append(focusMin).append(" 分钟，一共 ").append(sessions).append(" 段。");
        } else {
            sb.append("这周还没有完整的专注记录，不急，想开始时我陪你。");
        }
        if (sits + water + eyes > 0) {
            sb.append("我提醒你活动了 ").append(sits).append(" 次，喝水 ").append(water)
                    .append(" 次，护眼 ").append(eyes).append(" 次。");
        }
        if (music.length() > 0) {
            sb.append("听得最多的是《").append(music.optJSONObject(0).optString("title", "")).append("》。");
        }
        if (!"未标记".equals(moodTop) && !moodTop.isEmpty()) {
            sb.append("心情出现最多的是「").append(moodTop).append("」。");
        }
        if (chats > 0) sb.append("我们聊了 ").append(chats).append(" 句。");
        if (ads > 0) sb.append("顺手帮你跳过了 ").append(ads).append(" 次广告。");
        if (topics.length() > 0) {
            sb.append("你最近常聊到「").append(topics.optJSONObject(0).optString("name", "")).append("」。");
        }
        sb.append("下周也按你的节奏来，我在。");
        return sb.toString().replace("我", name.equals("我") ? "我" : name);
    }

    private static int countSince(Context c, String name, long start, String key, String value) {
        try {
            String s = Sg.read(Sg.file(c, name), "");
            int n = 0;
            for (String line : s.split("\n")) {
                if (line.trim().isEmpty()) continue;
                try {
                    JSONObject o = new JSONObject(line);
                    if (o.optLong("ts", o.optLong("start", 0)) < start) continue;
                    if (key != null && !value.equals(o.optString(key))) continue;
                    n++;
                } catch (Throwable ignored) {
                }
            }
            return n;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static JSONArray topMusic(Context c, long start) {
        JSONArray out = new JSONArray();
        try {
            java.util.HashMap<String, Integer> map = new java.util.HashMap<>();
            String s = Sg.read(Sg.file(c, "music_log.jsonl"), "");
            for (String line : s.split("\n")) {
                if (line.trim().isEmpty()) continue;
                JSONObject o = new JSONObject(line);
                if (o.optLong("ts", 0) < start) continue;
                String t = o.optString("title", "");
                if (t.isEmpty()) continue;
                map.put(t, map.getOrDefault(t, 0) + 1);
            }
            java.util.List<java.util.Map.Entry<String, Integer>> list = new java.util.ArrayList<>(map.entrySet());
            list.sort((a, b) -> b.getValue() - a.getValue());
            for (int k = 0; k < list.size() && k < 5; k++) {
                JSONObject o = new JSONObject();
                o.put("title", list.get(k).getKey());
                o.put("count", list.get(k).getValue());
                out.put(o);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static JSONArray topics(Context c, long start) {
        String[][] map = {
                {"工作", "工作", "项目", "客户", "会议", "加班", "需求", "代码", "上线"},
                {"学习", "学习", "考试", "课", "书", "复习", "论文"},
                {"情绪", "累", "烦", "开心", "难过", "压力", "焦虑", "心情"},
                {"生活", "吃", "睡", "快递", "天气", "家里", "周末"},
                {"娱乐", "游戏", "音乐", "电影", "剧", "视频", "动漫"},
                {"健康", "运动", "健身", "跑步", "喝水", "眼睛", "颈椎", "体检"}
        };
        JSONArray out = new JSONArray();
        try {
            int[] counts = new int[map.length];
            String s = Sg.read(Sg.file(c, "chat.jsonl"), "");
            for (String line : s.split("\n")) {
                if (line.trim().isEmpty()) continue;
                JSONObject o = new JSONObject(line);
                if (o.optLong("ts", 0) < start || !"user".equals(o.optString("role"))) continue;
                String t = o.optString("content", "");
                for (int k = 0; k < map.length; k++) {
                    for (int j = 1; j < map[k].length; j++) {
                        if (t.contains(map[k][j])) {
                            counts[k]++;
                            break;
                        }
                    }
                }
            }
            for (int k = 0; k < map.length; k++) {
                if (counts[k] == 0) continue;
                JSONObject o = new JSONObject();
                o.put("name", map[k][0]);
                o.put("count", counts[k]);
                out.put(o);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static String topKey(JSONObject o) {
        String best = "";
        int max = 0;
        java.util.Iterator<String> it = o.keys();
        while (it.hasNext()) {
            String k = it.next();
            int v = o.optInt(k, 0);
            if (v > max) {
                max = v;
                best = k;
            }
        }
        return best.isEmpty() ? "未标记" : MoodLog.label(best);
    }

    private static String weekKey() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        return c.get(java.util.Calendar.YEAR) + "-" + c.get(java.util.Calendar.WEEK_OF_YEAR);
    }
}
