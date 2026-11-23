package com.dshpet.android;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

/** 久坐拉伸、喝水、护眼提醒，带 20 秒带做动作。 */
public final class Wellness {
    private Wellness() {
    }

    public static JSONObject tick(Context c) {
        JSONObject out = new JSONObject();
        try {
            Cfg cfg = Cfg.load(c);
            if (!cfg.b("wellEnabled", true)) return out;
            if (cfg.b("wellQuietNight", true) && Policy.nightNow(cfg)) return out;
            long now = System.currentTimeMillis();
            JSONObject st = Sg.readObj(c, "well_state.json");
            long last = st.optLong("lastTick", now);
            if (last <= 0 || now - last > 5 * 60000L) {
                st.put("lastTick", now);
                if (!st.has("sit")) st.put("sit", now);
                if (!st.has("water")) st.put("water", now);
                if (!st.has("eye")) st.put("eye", now);
                Sg.writeObj(c, "well_state.json", st);
                return out;
            }
            String type = "";
            if (now - st.optLong("sit", now) >= Math.max(20, cfg.i("wellSitMin", 50)) * 60000L) {
                st.put("sit", now);
                type = "stretch";
            } else if (now - st.optLong("water", now) >= Math.max(20, cfg.i("wellWaterMin", 60)) * 60000L) {
                st.put("water", now);
                type = "water";
            } else if (now - st.optLong("eye", now) >= Math.max(15, cfg.i("wellEyeMin", 40)) * 60000L) {
                st.put("eye", now);
                type = "eye";
            }
            st.put("lastTick", now);
            Sg.writeObj(c, "well_state.json", st);
            if (!type.isEmpty()) out.put("type", type);
        } catch (Throwable ignored) {
        }
        return out;
    }

    public static void log(Context c, String type, String action) {
        try {
            JSONObject o = new JSONObject();
            o.put("ts", System.currentTimeMillis());
            o.put("type", type);
            o.put("action", action == null ? "" : action);
            Sg.appendLine(c, "wellness.jsonl", o);
        } catch (Throwable ignored) {
        }
    }

    public static JSONObject stats(Context c, int days) {
        JSONObject o = new JSONObject();
        try {
            long min = System.currentTimeMillis() - Math.max(1, days) * 86400000L;
            String s = Sg.read(Sg.file(c, "wellness.jsonl"), "");
            for (String line : s.split("\n")) {
                if (line.trim().isEmpty()) continue;
                JSONObject e = new JSONObject(line);
                if (e.optLong("ts", 0) < min) continue;
                String type = e.optString("type", "");
                o.put(type, o.optInt(type, 0) + 1);
            }
        } catch (Throwable ignored) {
        }
        return o;
    }

    public static JSONArray steps() {
        JSONArray a = new JSONArray();
        try {
            JSONObject s1 = new JSONObject();
            s1.put("text", "站起来，跟我伸个懒腰");
            s1.put("action", "a005");
            s1.put("sec", 7);
            a.put(s1);
            JSONObject s2 = new JSONObject();
            s2.put("text", "慢慢转一下脖子，别用力");
            s2.put("action", "a006");
            s2.put("sec", 7);
            a.put(s2);
            JSONObject s3 = new JSONObject();
            s3.put("text", "扩扩胸，深呼吸，好了");
            s3.put("action", "a007");
            s3.put("sec", 7);
            a.put(s3);
        } catch (Throwable ignored) {
        }
        return a;
    }

    public static JSONObject state(Context c) {
        Cfg cfg = Cfg.load(c);
        JSONObject o = new JSONObject();
        try {
            String[] keys = {"wellEnabled", "wellSitMin", "wellWaterMin", "wellEyeMin", "wellQuietNight"};
            for (String k : keys) o.put(k, cfg.json().opt(k));
            o.put("stats", stats(c, 7));
            o.put("last", Sg.readObj(c, "well_state.json"));
        } catch (Throwable ignored) {
        }
        return o;
    }

    public static void save(Context c, JSONObject o) {
        if (o == null) return;
        Cfg cfg = Cfg.load(c);
        String[] keys = {"wellEnabled", "wellSitMin", "wellWaterMin", "wellEyeMin", "wellQuietNight"};
        for (String k : keys) if (o.has(k)) cfg.set(k, o.opt(k));
        cfg.save(c);
    }
}
