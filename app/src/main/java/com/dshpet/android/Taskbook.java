package com.dshpet.android;

import android.content.Context;
import android.database.Cursor;
import android.provider.CalendarContract;

import org.json.JSONArray;
import org.json.JSONObject;

/** 待办、专注记录、日程冲突与会前/会后提醒。 */
public final class Taskbook {
    private Taskbook() {
    }

    public static JSONArray list(Context c) {
        return Sg.readArr(c, "tasks.json");
    }

    public static JSONObject add(Context c, String title, String detail, long due, int priority, String source) {
        JSONObject out = new JSONObject();
        try {
            if (title == null || title.trim().isEmpty()) return out;
            JSONArray a = list(c);
            JSONObject o = new JSONObject();
            o.put("id", "t" + System.currentTimeMillis() + "_" + (int) (Math.random() * 999));
            o.put("title", title.trim());
            o.put("detail", detail == null ? "" : detail);
            o.put("status", "todo");
            o.put("due", due);
            o.put("priority", Math.max(0, Math.min(2, priority)));
            o.put("tags", new JSONArray());
            o.put("source", source == null ? "manual" : source);
            o.put("created", System.currentTimeMillis());
            o.put("doneAt", 0);
            a.put(o);
            Sg.writeArr(c, "tasks.json", a);
            out = o;
            Bus.emit("tasks", new JSONObject());
        } catch (Throwable ignored) {
        }
        return out;
    }

    public static void update(Context c, String id, JSONObject fields) {
        if (fields == null) return;
        JSONArray a = list(c);
        for (int k = 0; k < a.length(); k++) {
            JSONObject o = a.optJSONObject(k);
            if (o == null || !id.equals(o.optString("id"))) continue;
            java.util.Iterator<String> it = fields.keys();
            while (it.hasNext()) {
                String key = it.next();
                try {
                    o.put(key, fields.opt(key));
                } catch (Throwable ignored) {
                }
            }
            Sg.writeArr(c, "tasks.json", a);
            Bus.emit("tasks", new JSONObject());
            return;
        }
    }

    public static void done(Context c, String id) {
        try {
            JSONObject o = new JSONObject();
            o.put("status", "done");
            o.put("doneAt", System.currentTimeMillis());
            update(c, id, o);
        } catch (Throwable ignored) {
        }
    }

    public static void remove(Context c, String id) {
        JSONArray a = list(c);
        JSONArray b = new JSONArray();
        for (int k = 0; k < a.length(); k++) {
            JSONObject o = a.optJSONObject(k);
            if (o != null && !id.equals(o.optString("id"))) b.put(o);
        }
        Sg.writeArr(c, "tasks.json", b);
    }

    public static JSONObject active(Context c) {
        return Sg.readObj(c, "focus_state.json");
    }

    public static void startFocus(Context c, String taskId, String title, int minutes) {
        JSONObject o = new JSONObject();
        try {
            o.put("taskId", taskId == null ? "" : taskId);
            o.put("title", title == null ? "" : title);
            o.put("start", System.currentTimeMillis());
            o.put("minutes", Math.max(1, minutes));
            Sg.writeObj(c, "focus_state.json", o);
        } catch (Throwable ignored) {
        }
    }

    public static JSONObject finishFocus(Context c, boolean completed) {
        JSONObject out = new JSONObject();
        try {
            JSONObject s = active(c);
            if (s.length() == 0) return out;
            long start = s.optLong("start", 0);
            if (start <= 0) return out;
            long end = System.currentTimeMillis();
            int min = (int) Math.max(0, (end - start) / 60000L);
            JSONObject log = new JSONObject();
            log.put("start", start);
            log.put("end", end);
            log.put("minutes", min);
            log.put("taskId", s.optString("taskId", ""));
            log.put("title", s.optString("title", ""));
            log.put("completed", completed);
            Sg.appendLine(c, "focus.jsonl", log);
            out = log;
            Sg.writeObj(c, "focus_state.json", new JSONObject());
            if (completed && !s.optString("taskId", "").isEmpty()) done(c, s.optString("taskId"));
        } catch (Throwable ignored) {
        }
        return out;
    }

    public static JSONArray focusLog(Context c, int days) {
        JSONArray out = new JSONArray();
        try {
            long min = System.currentTimeMillis() - days * 86400000L;
            String s = Sg.read(Sg.file(c, "focus.jsonl"), "");
            for (String line : s.split("\n")) {
                if (line.trim().isEmpty()) continue;
                try {
                    JSONObject o = new JSONObject(line);
                    if (o.optLong("start", 0) >= min) out.put(o);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    public static JSONObject focusSummary(Context c, int days) {
        JSONObject o = new JSONObject();
        try {
            JSONArray a = focusLog(c, days);
            int total = 0, sessions = 0;
            for (int k = 0; k < a.length(); k++) {
                JSONObject x = a.optJSONObject(k);
                if (x == null) continue;
                total += x.optInt("minutes", 0);
                sessions++;
            }
            o.put("minutes", total);
            o.put("sessions", sessions);
            o.put("active", active(c));
        } catch (Throwable ignored) {
        }
        return o;
    }

    /** 所有后台与界面读取都复用每日缓存，避免页面刷新时反复访问系统日历。 */
    public static JSONArray events(Context c, int hours) {
        JSONArray all = dailyEvents(c);
        JSONArray out = new JSONArray();
        long now = System.currentTimeMillis();
        long end = now + Math.max(1, hours) * 3600000L;
        for (int i = 0; i < all.length(); i++) {
            JSONObject o = all.optJSONObject(i);
            if (o == null) continue;
            long st = o.optLong("start", 0);
            if (st >= now - 1800000L && st <= end) out.put(o);
        }
        return out;
    }

    /**
     * 日历每天读取一次并由 Cal.sync 生成缓存，界面与后台提醒共用。
     */
    private static JSONArray dailyEvents(Context c) {
        String today = Sg.now().substring(0, 10);
        JSONObject cc = Sg.readObj(c, "cal_cache_day.json");
        JSONArray list = cc.optJSONArray("list");
        if (list != null && cc.optInt("version", 0) == 2 && today.equals(cc.optString("date", ""))) return list;
        return list != null && cc.optInt("version", 0) == 2 && today.equals(cc.optString("date", "")) ? list : new JSONArray();
    }

    private static JSONArray query(Context c, int hours) {
        JSONArray out = new JSONArray();
        if (!Perm.calendar(c)) return out;
        Cursor cur = null;
        try {
            long now = System.currentTimeMillis();
            long end = now + Math.max(1, hours) * 3600000L;
            String[] proj = {CalendarContract.Events._ID, CalendarContract.Events.TITLE,
                    CalendarContract.Events.DESCRIPTION, CalendarContract.Events.DTSTART,
                    CalendarContract.Events.DTEND, CalendarContract.Events.EVENT_LOCATION};
            cur = c.getContentResolver().query(CalendarContract.Events.CONTENT_URI, proj,
                    CalendarContract.Events.DTSTART + ">=? AND " + CalendarContract.Events.DTSTART + "<=?",
                    new String[]{String.valueOf(now - 1800000L), String.valueOf(end)},
                    CalendarContract.Events.DTSTART + " ASC");
            if (cur == null) return out;
            while (cur.moveToNext() && out.length() < 30) {
                JSONObject o = new JSONObject();
                o.put("id", cur.getString(0));
                o.put("title", cur.getString(1) == null ? "日程" : cur.getString(1));
                o.put("detail", cur.getString(2) == null ? "" : cur.getString(2));
                o.put("start", cur.getLong(3));
                o.put("end", cur.getLong(4));
                o.put("place", cur.getString(5) == null ? "" : cur.getString(5));
                out.put(o);
            }
        } catch (Throwable ignored) {
        } finally {
            if (cur != null) cur.close();
        }
        return out;
    }

    public static JSONArray conflicts(Context c) {
        return events(c, 1);
    }

    /** 每分钟调用：会前 5 分钟、会后 10 分钟各提醒一次。 */
    public static JSONObject meetingTick(Context c) {
        JSONObject out = new JSONObject();
        if (!Perm.calendar(c) || !Cfg.load(c).b("meetRemind", true)) return out;
        try {
            JSONArray ev = events(c, 2);
            JSONArray sent = Sg.readArr(c, "meet_notified.json");
            long now = System.currentTimeMillis();
            for (int k = 0; k < ev.length(); k++) {
                JSONObject e = ev.optJSONObject(k);
                if (e == null) continue;
                String id = e.optString("id", "");
                long start = e.optLong("start", 0);
                long end = e.optLong("end", 0);
                String beforeKey = "b:" + id + ":" + start;
                String afterKey = "a:" + id + ":" + end;
                if (start > now && start - now <= 5 * 60000L && !has(sent, beforeKey)) {
                    sent.put(beforeKey);
                    String title = e.optString("title", "");
                    if (title.isEmpty()) title = "接下来的安排";
                    int minutes = Math.max(1, (int) ((start - now) / 60000L));
                    out.put("type", "before");
                    out.put("title", title);
                    out.put("minutes", minutes);
                    out.put("text", minutes + " 分钟后是「" + title + "」，准备一下。");
                    out.put("key", beforeKey);
                    save(c, sent);
                    return out;
                }
                if (end > 0 && end < now && now - end <= 10 * 60000L && !has(sent, afterKey)) {
                    sent.put(afterKey);
                    String title = e.optString("title", "");
                    if (title.isEmpty()) title = "刚才的安排";
                    out.put("type", "after");
                    out.put("title", title);
                    out.put("text", "「" + title + "」结束了，先缓一口气。");
                    out.put("key", afterKey);
                    save(c, sent);
                    return out;
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static boolean has(JSONArray a, String key) {
        for (int k = 0; k < a.length(); k++) if (key.equals(a.optString(k))) return true;
        return false;
    }

    private static void save(Context c, JSONArray a) {
        while (a.length() > 80) {
            JSONArray b = new JSONArray();
            for (int k = 1; k < a.length(); k++) b.put(a.opt(k));
            a = b;
        }
        try {
            Sg.writeArr(c, "meet_notified.json", a);
        } catch (Throwable ignored) {
        }
    }
}
