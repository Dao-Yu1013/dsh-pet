package com.dshpet.android;

import android.content.Context;
import android.database.Cursor;
import android.provider.CalendarContract;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;

/** 读取日历里的出行/日程，生成提醒。 */
public final class Cal {
    /** 每天最多主动提醒几次日程。 */
    private static final int DAILY_TIP_MAX = 5;

    private Cal() {
    }

    public static synchronized int sync(Context c) {
        if (!Perm.calendar(c)) return 0;
        int n = 0;
        JSONArray cachedEvents = new JSONArray();
        try {
            long now = System.currentTimeMillis();
            long end = now + 14L * 86400000L;
            String[] proj = {CalendarContract.Events._ID, CalendarContract.Events.TITLE,
                    CalendarContract.Events.DESCRIPTION, CalendarContract.Events.DTSTART,
                    CalendarContract.Events.DTEND, CalendarContract.Events.EVENT_LOCATION};
            // 只取有效事件：已删除、隐藏、已取消的日程不进入提醒
            String where = CalendarContract.Events.DELETED + "=0 AND "
                    + CalendarContract.Events.VISIBLE + "=1 AND "
                    + CalendarContract.Events.STATUS + "!=" + CalendarContract.Events.STATUS_CANCELED
                    + " AND " + CalendarContract.Events.DTSTART + ">=? AND "
                    + CalendarContract.Events.DTSTART + "<=?";
            Cursor cur = c.getContentResolver().query(CalendarContract.Events.CONTENT_URI, proj,
                    where,
                    new String[]{String.valueOf(now - 86400000L), String.valueOf(end)},
                    CalendarContract.Events.DTSTART + " ASC");
            if (cur == null) {
                writeDailyCache(c, cachedEvents);
                return 0;
            }
            while (cur.moveToNext() && n < 40) {
                String id = cur.getString(0);
                String title = cur.getString(1) == null ? "日程" : cur.getString(1);
                String desc = cur.getString(2) == null ? "" : cur.getString(2);
                long ts = cur.getLong(3);
                JSONObject cached = new JSONObject();
                cached.put("id", id);
                cached.put("title", title);
                cached.put("detail", desc);
                cached.put("start", ts);
                cached.put("end", cur.getLong(4));
                cached.put("place", cur.getString(5) == null ? "" : cur.getString(5));
                cachedEvents.put(cached);
                String all = title + " " + desc;
                boolean travel = all.contains("航班") || all.contains("高铁") || all.contains("火车")
                        || all.contains("动车") || all.contains("酒店") || all.contains("行程")
                        || all.contains("登机") || all.contains("出发") || all.contains("车次");
                String type = travel ? "travel" : "plan";
                String when = new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US)
                        .format(new java.util.Date(ts));
                JSONObject extra = new JSONObject();
                extra.put("at", ts);
                extra.put("cal", true);
                Data.addReminder(c, type, travel ? "出行计划 · " + title : "日程 · " + title,
                        when + (desc.isEmpty() ? "" : " · " + desc), "cal:" + id, extra);
                n++;
            }
            cur.close();
            writeDailyCache(c, cachedEvents);
            if (n > 0) Bus.emit("reminders", new JSONObject());
        } catch (Throwable ignored) {
            writeDailyCache(c, cachedEvents);
        }
        return n;
    }

    /** 每天只同步一次：跨天后第一次调用执行。 */
    public static synchronized boolean dailySync(Context c) {
        String today = Sg.now().substring(0, 10);
        JSONObject st = Sg.readObj(c, "cal_sync.json");
        // Treat the daily attempt as a one-shot transaction. A service restart or concurrent
        // midnight callback must not repeat the CalendarProvider query when a cache write fails.
        if (today.equals(st.optString("attemptDate", ""))) return false;
        try {
            st.put("attemptDate", today);
            st.put("date", today);
            Sg.writeObj(c, "cal_sync.json", st);
        } catch (Throwable ignored) {
        }
        sync(c);
        return true;
    }

    private static void writeDailyCache(Context c, JSONArray events) {
        try {
            JSONObject cache = new JSONObject();
            cache.put("version", 2);
            cache.put("date", Sg.now().substring(0, 10));
            cache.put("list", events == null ? new JSONArray() : events);
            Sg.writeObj(c, "cal_cache_day.json", cache);
        } catch (Throwable ignored) { }
    }

    /**
     * 生成一条日程提醒话术：优先提醒期限最近的，带日期和「还有几天」，风格随机。
     * 一天最多 {@link #DAILY_TIP_MAX} 次，同一条当天不重复。没有可提醒的返回空串。
     */
    public static String tip(Context c) {
        String today = Sg.now().substring(0, 10);
        JSONObject st = Sg.readObj(c, "cal_tip.json");
        boolean sameDay = today.equals(st.optString("date", ""));
        int used = sameDay ? st.optInt("count", 0) : 0;
        if (used >= DAILY_TIP_MAX) return "";
        JSONArray told = sameDay ? st.optJSONArray("ids") : null;
        if (told == null) told = new JSONArray();

        JSONArray a = Data.reminders(c);
        long now = System.currentTimeMillis();
        JSONObject best = null;
        long bestAt = Long.MAX_VALUE;
        for (int k = 0; k < a.length(); k++) {
            JSONObject o = a.optJSONObject(k);
            if (o == null) continue;
            String key = o.optString("key", "");
            if (!key.startsWith("cal:")) continue;
            long at = o.optLong("at", 0);
            if (at <= 0 || at < now - 3600000L) continue;
            if (toldContains(told, key)) continue;
            if (at < bestAt) {
                bestAt = at;
                best = o;
            }
        }
        if (best == null) return "";

        try {
            st.put("date", today);
            st.put("count", used + 1);
            told.put(best.optString("key"));
            st.put("ids", told);
            Sg.writeObj(c, "cal_tip.json", st);
        } catch (Throwable ignored) {
        }
        return phrase(best, bestAt, now);
    }

    private static boolean toldContains(JSONArray a, String key) {
        for (int k = 0; k < a.length(); k++) {
            if (key.equals(a.optString(k))) return true;
        }
        return false;
    }

    private static String phrase(JSONObject o, long at, long now) {
        String title = o.optString("title", "");
        title = title.replace("日程 · ", "").replace("出行计划 · ", "").trim();
        if (title.isEmpty()) title = "安排";
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(at);
        int month = cal.get(Calendar.MONTH) + 1;
        int day = cal.get(Calendar.DAY_OF_MONTH);
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        String date = month + "月" + day + "号";
        long days = (long) Math.ceil((at - now) / 86400000.0);
        String left;
        if (days <= 0) left = "就是今天";
        else if (days == 1) left = "还有 1 天";
        else left = "还有 " + days + " 天";
        String time = hour >= 12 ? date + "下午" : date + "上午";
        return Care.pick(new String[]{
                "看到你 " + date + " 要去" + title + "，" + left + "，记得早点安排哦",
                "嘿，" + date + " 的" + title + "正在向你招手，" + left + "，别到时候手忙脚乱",
                date + " 有" + title + "，" + left + "——准备好了吗？我看有点悬",
                "你 " + date + " 有个" + title + "，" + left + "，有人陪你去吗？没有的话我在",
                "我帮你翻了翻日程：" + date + " " + title + "，" + left + "，别忘了",
                "提醒一下，" + left + "就是" + title + "了，日期是 " + date + "，别记错日子",
                time + "的" + title + "，" + left + "，需要我帮你想想怎么安排吗",
        });
    }

    public static boolean enabled(Context c) {
        return Cfg.load(c).b("travelEnabled", true);
    }

    public static int dailyLimit() {
        return DAILY_TIP_MAX;
    }
}
