package com.dshpet.android;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 共同经历时间线：里程碑、用户标记的重要时刻、静默回顾快照。
 *
 * 只追加普通事件；删除/编辑会原子重写整个文件。
 * 旧记录只有 key/type/text/ts，新字段全部按缺省值读取，不迁移、不删除。
 */
public final class Timeline {
    private static final String FILE = "timeline.jsonl";
    private static final int[] DAY_MARKS = {1, 7, 30, 100, 200, 365, 500, 730, 1000};
    private static final int[] CHAT_MARKS = {1, 100, 500, 1000, 5000, 10000};
    private static final Object LOCK = new Object();

    private Timeline() {
    }

    public static JSONArray list(Context c, int limit) {
        JSONArray out = new JSONArray();
        synchronized (LOCK) {
            List<JSONObject> all = readAll(c);
            int n = 0;
            for (int k = all.size() - 1; k >= 0 && n < limit; k--) {
                out.put(all.get(k));
                n++;
            }
        }
        return out;
    }

    /** 指定时间范围内的记录，最新在前。 */
    public static JSONArray between(Context c, long from, long to, int limit) {
        JSONArray out = new JSONArray();
        synchronized (LOCK) {
            List<JSONObject> all = readAll(c);
            for (int k = all.size() - 1; k >= 0 && out.length() < limit; k--) {
                JSONObject o = all.get(k);
                long ts = o.optLong("ts", 0);
                if (ts < from || (to > 0 && ts >= to)) continue;
                out.put(o);
            }
        }
        return out;
    }

    /** 记录一条，key 相同则跳过。 */
    public static void add(Context c, String key, String type, String text) {
        add(c, key, type, text, null);
    }

    /**
     * 记录一条带来源的 event。
     * extra 可包含 source/refTs/refRole/note/confidence/editable/day/period 等字段。
     */
    public static void add(Context c, String key, String type, String text, JSONObject extra) {
        if (key == null || key.isEmpty() || text == null || text.isEmpty()) return;
        synchronized (LOCK) {
            if (exists(c, key)) return;
            try {
                JSONObject o = new JSONObject();
                o.put("key", key);
                o.put("type", type == null ? "event" : type);
                o.put("text", text);
                long ts = System.currentTimeMillis();
                if (extra != null && extra.has("ts")) ts = extra.optLong("ts", ts);
                if (extra != null && extra.has("day")) {
                    String d = extra.optString("day", "");
                    if (d.matches("\\d{4}-\\d{2}-\\d{2}")) ts = dayStart(d) + 43200000L;
                }
                o.put("ts", ts);
                o.put("day", fullDay(ts));
                if (extra != null) {
                    java.util.Iterator<String> it = extra.keys();
                    while (it.hasNext()) {
                        String k = it.next();
                        if ("key".equals(k) || "type".equals(k) || "text".equals(k) || "ts".equals(k)
                                || "day".equals(k)) continue;
                        o.put(k, extra.opt(k));
                    }
                }
                if (!o.has("source")) o.put("source", "system");
                if (!o.has("confidence")) o.put("confidence", 1.0);
                if (!o.has("editable")) o.put("editable", false);
                Sg.appendLine(c, FILE, o);
                Bus.emit("timeline", o);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 删除一条记录；不存在的 key 直接返回 false。 */
    public static boolean remove(Context c, String key) {
        if (key == null || key.isEmpty()) return false;
        synchronized (LOCK) {
            List<JSONObject> all = readAll(c);
            List<JSONObject> keep = new ArrayList<>();
            boolean hit = false;
            for (JSONObject o : all) {
                if (key.equals(o.optString("key", ""))) {
                    hit = true;
                    continue;
                }
                keep.add(o);
            }
            if (!hit) return false;
            writeAll(c, keep);
            Bus.emit("timeline", new JSONObject());
            return true;
        }
    }

    /** 只允许编辑用户手动记录；自动记录返回 false。 */
    public static boolean update(Context c, String key, JSONObject patch) {
        if (key == null || key.isEmpty() || patch == null) return false;
        synchronized (LOCK) {
            List<JSONObject> all = readAll(c);
            boolean hit = false;
            for (JSONObject o : all) {
                if (!key.equals(o.optString("key", ""))) continue;
                if (!o.optBoolean("editable", false)) return false;
                if (patch.has("text")) {
                    String t = patch.optString("text", "").trim();
                    if (t.length() < 2 || t.length() > 120) return false;
                    try {
                        o.put("text", t);
                    } catch (Throwable ignored) {
                    }
                }
                if (patch.has("note")) {
                    String n = patch.optString("note", "").trim();
                    if (n.length() > 160) n = n.substring(0, 160);
                    try {
                        o.put("note", n);
                    } catch (Throwable ignored) {
                    }
                }
                if (patch.has("day")) {
                    String d = patch.optString("day", "");
                    if (!d.matches("\\d{4}-\\d{2}-\\d{2}")) return false;
                    try {
                        o.put("day", d);
                        o.put("ts", dayStart(d) + 43200000L);
                    } catch (Throwable ignored) {
                    }
                }
                try {
                    o.put("updatedAt", System.currentTimeMillis());
                } catch (Throwable ignored) {
                }
                hit = true;
                break;
            }
            if (hit) writeAll(c, all);
            return hit;
        }
    }

    /** 真实条数，不按 80 截断。 */
    public static int count(Context c) {
        synchronized (LOCK) {
            return readAll(c).size();
        }
    }

    private static boolean exists(Context c, String key) {
        for (JSONObject o : readAll(c)) {
            if (key.equals(o.optString("key", ""))) return true;
        }
        return false;
    }

    /**
     * 检查里程碑。只负责入库，返回空字符串：不再因纪念日主动播报。
     * 保留返回值是为了兼容旧调用。
     */
    public static String touch(Context c) {
        synchronized (LOCK) {
            int days = Data.days(c);
            for (int mark : DAY_MARKS) {
                if (days == mark) {
                    add(c, "day" + mark, "milestone", "我们认识第 " + mark + " 天了。", extra("milestone"));
                }
            }
            int chats = Data.chatCount(c);
            for (int mark : CHAT_MARKS) {
                if (chats >= mark) {
                    add(c, "chat" + mark, "milestone", "你和我说过第 " + mark + " 句话了。", extra("milestone"));
                }
            }
        }
        return "";
    }

    private static JSONObject extra(String source) {
        JSONObject o = new JSONObject();
        try {
            o.put("source", source);
            o.put("editable", false);
            o.put("confidence", 1.0);
        } catch (Throwable ignored) {
        }
        return o;
    }

    /** 仅当用户在问过去时，带 1–2 条相关重要时刻；其他时候返回空。 */
    public static String promptRelevant(Context c, String query, int limit) {
        String q = query == null ? "" : query.trim();
        if (q.isEmpty() || !asksPast(q)) return "";
        List<JSONObject> all;
        synchronized (LOCK) {
            all = readAll(c);
        }
        List<JSONObject> hits = new ArrayList<>();
        for (int k = all.size() - 1; k >= 0 && hits.size() < limit; k--) {
            JSONObject o = all.get(k);
            if (!"moment".equals(o.optString("type", "")) && !"milestone".equals(o.optString("type", ""))) continue;
            String text = o.optString("text", "");
            if (text.isEmpty()) continue;
            if (overlap(q, text) <= 0) continue;
            hits.add(o);
        }
        if (hits.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("你们共同经历过的事（只在相关时提起，不要主动罗列）：\n");
        for (JSONObject o : hits) {
            sb.append("- ").append(o.optString("day", "")).append("：")
                    .append(o.optString("text", "")).append('\n');
        }
        return sb.toString();
    }

    private static boolean asksPast(String q) {
        String[] keys = {"第一次", "以前", "之前", "上次", "记得", "重要", "一起做", "那时"};
        for (String k : keys) if (q.contains(k)) return true;
        return false;
    }

    private static int overlap(String a, String b) {
        String ca = a.replaceAll("[\\s，。！？、,.!?~～:：;；\"'“”‘’()（）\\[\\]【】]", "");
        String cb = b.replaceAll("[\\s，。！？、,.!?~～:：;；\"'“”‘’()（）\\[\\]【】]", "");
        int hit = 0;
        for (int k = 0; k + 2 <= ca.length(); k++) {
            if (cb.contains(ca.substring(k, k + 2))) hit++;
        }
        return hit;
    }

    public static JSONObject summary(Context c) {
        JSONObject o = new JSONObject();
        try {
            List<JSONObject> all;
            synchronized (LOCK) {
                all = readAll(c);
            }
            JSONArray items = new JSONArray();
            int total = 0;
            for (int k = 0; k < all.size(); k++) {
                if (!"recap".equals(all.get(k).optString("type", ""))) total++;
            }
            for (int k = all.size() - 1; k >= 0 && items.length() < 100; k--) {
                items.put(all.get(k));
            }
            JSONArray recaps = new JSONArray();
            for (int k = all.size() - 1; k >= 0 && recaps.length() < 3; k--) {
                JSONObject it = all.get(k);
                if ("recap".equals(it.optString("type", ""))) recaps.put(it);
            }
            o.put("items", items);
            o.put("total", total);
            o.put("recaps", recaps);
        } catch (Throwable ignored) {
        }
        return o;
    }

    /* ---------------- 文件读写 ---------------- */

    private static List<JSONObject> readAll(Context c) {
        List<JSONObject> out = new ArrayList<>();
        try {
            String s = Sg.read(Sg.file(c, FILE), "");
            for (String line : s.split("\n")) {
                if (line.trim().isEmpty()) continue;
                try {
                    out.add(new JSONObject(line));
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static void writeAll(Context c, List<JSONObject> all) {
        StringBuilder sb = new StringBuilder();
        for (JSONObject o : all) sb.append(o.toString()).append('\n');
        Sg.write(Sg.file(c, FILE), sb.toString());
    }

    private static String fullDay(long ts) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(ts));
    }

    private static long dayStart(String d) {
        try {
            return new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(d).getTime();
        } catch (Throwable t) {
            return 0;
        }
    }
}
