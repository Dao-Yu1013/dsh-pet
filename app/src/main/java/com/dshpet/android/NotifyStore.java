package com.dshpet.android;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

/** 通知摘要：按应用聚合、稍后提醒、静音应用、转待办。 */
public final class NotifyStore {
    private static final int MAX = 200;

    private NotifyStore() {
    }

    public static synchronized void capture(Context c, String pkg, String app, String title, String text, String kind) {
        try {
            JSONArray a = Sg.readArr(c, "notifications.json");
            JSONObject o = new JSONObject();
            o.put("key", pkg + ":" + System.currentTimeMillis() + ":" + (int) (Math.random() * 999));
            o.put("pkg", pkg == null ? "" : pkg);
            o.put("app", app == null ? "" : app);
            o.put("title", title == null ? "" : title);
            o.put("text", text == null ? "" : text);
            o.put("kind", kind == null ? "msg" : kind);
            o.put("ts", System.currentTimeMillis());
            o.put("read", false);
            JSONArray b = new JSONArray();
            b.put(o);
            for (int k = 0; k < a.length() && b.length() < MAX; k++) b.put(a.opt(k));
            Sg.writeArr(c, "notifications.json", b);
            Bus.unreadCount = unread(c);
                Bus.emit("notif", groupsJson(c));
        } catch (Throwable ignored) {
        }
    }

    public static JSONArray list(Context c) {
        return Sg.readArr(c, "notifications.json");
    }

    public static int unread(Context c) {
        int n = 0;
        JSONArray a = list(c);
        for (int k = 0; k < a.length(); k++) {
            JSONObject o = a.optJSONObject(k);
            if (o != null && !o.optBoolean("read", false)) n++;
        }
        return n;
    }

    private static JSONObject groupsJson(Context c) {
        JSONObject o = new JSONObject();
        try {
            o.put("groups", group(c));
        } catch (Throwable ignored) {
        }
        return o;
    }

    public static JSONArray group(Context c) {
        JSONArray out = new JSONArray();
        try {
            JSONArray a = list(c);
            java.util.LinkedHashMap<String, JSONObject> map = new java.util.LinkedHashMap<>();
            for (int k = 0; k < a.length(); k++) {
                JSONObject o = a.optJSONObject(k);
                if (o == null || o.optBoolean("read", false)) continue;
                String pkg = o.optString("pkg", "");
                JSONObject g = map.get(pkg);
                if (g == null) {
                    g = new JSONObject();
                    g.put("pkg", pkg);
                    g.put("app", o.optString("app", pkg));
                    g.put("count", 0);
                    g.put("latest", o.optString("title", ""));
                    g.put("ts", o.optLong("ts", 0));
                    g.put("kind", o.optString("kind", "msg"));
                    map.put(pkg, g);
                }
                g.put("count", g.optInt("count", 0) + 1);
                if (o.optLong("ts", 0) >= g.optLong("ts", 0)) {
                    g.put("latest", o.optString("title", ""));
                    g.put("ts", o.optLong("ts", 0));
                    g.put("kind", o.optString("kind", "msg"));
                }
            }
            for (JSONObject g : map.values()) out.put(g);
        } catch (Throwable ignored) {
        }
        return out;
    }

    public static void markRead(Context c, String key) {
        JSONArray a = list(c);
        for (int k = 0; k < a.length(); k++) {
            JSONObject o = a.optJSONObject(k);
            if (o == null || !key.equals(o.optString("key"))) continue;
            try {
                o.put("read", true);
                Sg.writeArr(c, "notifications.json", a);
                Bus.unreadCount = unread(c);
                    Bus.emit("notif", groupsJson(c));
            } catch (Throwable ignored) {
            }
            return;
        }
    }

    public static void markAppRead(Context c, String pkg) {
        JSONArray a = list(c);
        for (int k = 0; k < a.length(); k++) {
            JSONObject o = a.optJSONObject(k);
            if (o == null || !pkg.equals(o.optString("pkg"))) continue;
            try {
                o.put("read", true);
            } catch (Throwable ignored) {
            }
        }
        Sg.writeArr(c, "notifications.json", a);
        Bus.unreadCount = unread(c);
        Bus.emit("notif", groupsJson(c));
    }

    public static void removeKey(Context c, String key) {
        JSONArray a = list(c);
        JSONArray b = new JSONArray();
        for (int k = 0; k < a.length(); k++) {
            JSONObject o = a.optJSONObject(k);
            if (o != null && !key.equals(o.optString("key"))) b.put(o);
        }
        Sg.writeArr(c, "notifications.json", b);
        Bus.unreadCount = unread(c);
    }

    public static void clear(Context c) {
        Sg.writeArr(c, "notifications.json", new JSONArray());
        Bus.unreadCount = 0;
        Bus.emit("notif", groupsJson(c));
    }

    public static boolean muted(Context c, String pkg) {
        JSONArray a = Cfg.load(c).arr("notifMuted");
        for (int k = 0; k < a.length(); k++) if (pkg.equals(a.optString(k))) return true;
        return false;
    }

    public static void mute(Context c, String pkg, boolean on) {
        Cfg cfg = Cfg.load(c);
        JSONArray a = cfg.arr("notifMuted");
        JSONArray b = new JSONArray();
        boolean exists = false;
        for (int k = 0; k < a.length(); k++) {
            String p = a.optString(k);
            if (p.equals(pkg)) exists = true;
            if (!p.equals(pkg)) b.put(p);
        }
        if (on && !exists) b.put(pkg);
        cfg.set("notifMuted", b);
        cfg.save(c);
    }

    public static JSONObject snooze(Context c, String key, int minutes) {
        JSONObject out = new JSONObject();
        try {
            JSONArray a = list(c);
            JSONObject item = null;
            for (int k = 0; k < a.length(); k++) {
                JSONObject o = a.optJSONObject(k);
                if (o != null && key.equals(o.optString("key"))) {
                    item = o;
                    break;
                }
            }
            if (item == null) return out;
            JSONArray s = Sg.readArr(c, "snoozes.json");
            JSONObject o = new JSONObject();
            o.put("key", key);
            o.put("pkg", item.optString("pkg"));
            o.put("app", item.optString("app"));
            o.put("title", item.optString("title"));
            o.put("text", item.optString("text"));
            o.put("at", System.currentTimeMillis() + Math.max(1, minutes) * 60000L);
            s.put(o);
            Sg.writeArr(c, "snoozes.json", s);
            markRead(c, key);
            out = o;
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** 到点的稍后提醒，取出后从队列删除。 */
    public static JSONArray due(Context c) {
        JSONArray out = new JSONArray();
        try {
            long now = System.currentTimeMillis();
            JSONArray a = Sg.readArr(c, "snoozes.json");
            JSONArray keep = new JSONArray();
            for (int k = 0; k < a.length(); k++) {
                JSONObject o = a.optJSONObject(k);
                if (o == null) continue;
                if (o.optLong("at", 0) <= now) out.put(o);
                else keep.put(o);
            }
            if (out.length() > 0) Sg.writeArr(c, "snoozes.json", keep);
        } catch (Throwable ignored) {
        }
        return out;
    }

    public static JSONObject toTask(Context c, String key) {
        JSONObject out = new JSONObject();
        try {
            JSONArray a = list(c);
            for (int k = 0; k < a.length(); k++) {
                JSONObject o = a.optJSONObject(k);
                if (o == null || !key.equals(o.optString("key"))) continue;
                out = Taskbook.add(c, o.optString("title", "处理通知"),
                        o.optString("app", "") + "：" + o.optString("text", ""), 0, 1, "notif");
                markRead(c, key);
                break;
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    public static JSONObject summary(Context c) {
        JSONObject o = new JSONObject();
        try {
            JSONArray all = list(c);
            JSONArray unreadItems = new JSONArray();
            for (int k = 0; k < all.length(); k++) {
                JSONObject it = all.optJSONObject(k);
                if (it != null && !it.optBoolean("read", false)) unreadItems.put(it);
            }
            o.put("unread", unread(c));
            o.put("groups", group(c));
            o.put("items", unreadItems);
            o.put("muted", Cfg.load(c).arr("notifMuted"));
        } catch (Throwable ignored) {
        }
        return o;
    }
}
