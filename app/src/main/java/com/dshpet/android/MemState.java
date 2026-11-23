package com.dshpet.android;

import android.content.Context;

import org.json.JSONObject;

/** 记忆提炼的游标与节流状态。 */
public final class MemState {
    private static final String FILE = "mem_state.json";

    private MemState() {
    }

    public static synchronized JSONObject load(Context c) {
        JSONObject o = Sg.readObj(c, FILE);
        try {
            if (!o.has("schema")) o.put("schema", Mem.SCHEMA);
            if (!o.has("lastExtractTs")) o.put("lastExtractTs", 0L);
            if (!o.has("lastAttemptTs")) o.put("lastAttemptTs", 0L);
        } catch (Throwable ignored) {
        }
        return o;
    }

    public static long lastExtractTs(Context c) {
        return load(c).optLong("lastExtractTs", 0L);
    }

    public static void setLastExtractTs(Context c, long ts) {
        JSONObject o = load(c);
        try {
            o.put("lastExtractTs", ts);
            Sg.writeObj(c, FILE, o);
        } catch (Throwable ignored) {
        }
    }

    public static long lastAttemptTs(Context c) {
        return load(c).optLong("lastAttemptTs", 0L);
    }

    public static void setLastAttemptTs(Context c, long ts) {
        JSONObject o = load(c);
        try {
            o.put("lastAttemptTs", ts);
            Sg.writeObj(c, FILE, o);
        } catch (Throwable ignored) {
        }
    }
}
