package com.dshpet.android;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

/** Local app-discovery memory used to give proactive conversation a concrete curiosity topic. */
public final class Curiosity {
    private static final String FILE = "curiosity_apps.json";
    private static final long ASK_GAP_MS = 6 * 60 * 60 * 1000L;
    private static final long SAME_APP_GAP_MS = 7 * 86400000L;
    private static final long FREE_APP_GAP_MS = 6 * 60 * 60 * 1000L;

    private Curiosity() { }

    /** Supplies at most one installed app name to a proactive prompt; never claims it was explored. */
    public static String prompt(Context c) {
        if (c == null || !A11y.ready()) return "";
        Cfg cfg = Cfg.load(c);
        boolean freeAction = cfg.b("freeAction", false);
        boolean phoneIdle = CompanionSession.phoneIdle(c);
        if (!cfg.b("watchApp", true) && !(freeAction && phoneIdle)) return "";
        if (freeAction && !phoneIdle) {
            return "自由行动开关已开启，但手机现在不满足空闲条件；不要提议离开当前应用或开始自动行动。可以依据当前屏幕自然陪他一起看，或者安静陪伴。\n";
        }
        if (freeAction && phoneIdle && !CompanionSession.freeActionPromptDue(c)) return "";
        JSONObject state = Sg.readObj(c, FILE);
        long now = System.currentTimeMillis();
        if (!(freeAction && phoneIdle) && now - state.optLong("lastPromptAt", 0) < ASK_GAP_MS) return "";
        JSONArray apps = Fg.apps(c);
        JSONArray configuredFreeApps = cfg.arr("freeActionApps");
        java.util.HashSet<String> selectedFreeApps = new java.util.HashSet<>();
        for (int i = 0; i < configuredFreeApps.length(); i++) {
            String selected = configuredFreeApps.optString(i, "").trim();
            if (!selected.isEmpty()) selectedFreeApps.add(selected);
        }
        JSONObject today = Usage.state(c).optJSONObject("today");
        JSONArray usage = today == null ? new JSONArray() : today.optJSONArray("apps");
        java.util.HashMap<String, Integer> minutes = new java.util.HashMap<>();
        for (int i = 0; usage != null && i < usage.length(); i++) {
            JSONObject used = usage.optJSONObject(i);
            if (used != null) minutes.put(used.optString("pkg", ""), used.optInt("min", 0));
        }
        JSONObject asked = state.optJSONObject("asked");
        if (asked == null) asked = new JSONObject();
        JSONObject candidate = null;
        long oldest = Long.MAX_VALUE;
        int bestMinutes = -1;
        for (int i = 0; i < apps.length(); i++) {
            JSONObject app = apps.optJSONObject(i);
            if (app == null || !CompanionSession.freeActionAppAllowed(c, app)) continue;
            String pkg = app.optString("pkg", "").trim();
            String name = app.optString("name", "").trim();
            if (pkg.isEmpty() || name.isEmpty() || pkg.equals(c.getPackageName())) continue;
            String low = name.toLowerCase(java.util.Locale.ROOT);
            boolean browseInterest = Scene.looksLikeVideoPkg(pkg) || cfg.isGame(pkg)
                    || low.contains("视频") || low.contains("抖音")
                    || low.contains("哔哩") || low.contains("快手") || low.contains("小红书");
            if (freeAction && phoneIdle ? !CompanionSession.autonomousTargetAllowed(c, app, cfg, configuredFreeApps)
                    : !browseInterest) continue;
            long lastAsked = asked.optLong(pkg, 0);
            if (now - lastAsked < (freeAction && phoneIdle ? FREE_APP_GAP_MS : SAME_APP_GAP_MS)) continue;
            int usedMinutes = minutes.containsKey(pkg) ? minutes.get(pkg) : 0;
            if (usedMinutes > bestMinutes || (usedMinutes == bestMinutes && lastAsked < oldest)) {
                candidate = app;
                oldest = lastAsked;
                bestMinutes = usedMinutes;
            }
        }
        if (candidate == null) return "";
        String pkg = candidate.optString("pkg", "");
        String name = candidate.optString("name", "");
        try {
            long previousAskedAt = asked.optLong(pkg, 0);
            long previousPromptAt = state.optLong("lastPromptAt", 0);
            asked.put(pkg, now);
            state.put("asked", asked);
            state.put("lastPromptAt", now);
            if (freeAction && phoneIdle) {
                state.put("pendingFreeAction", new JSONObject().put("pkg", pkg)
                        .put("name", name).put("ts", now)
                        .put("previousAskedAt", previousAskedAt)
                        .put("previousPromptAt", previousPromptAt));
            }
            Sg.writeObj(c, FILE, state);
        } catch (Throwable ignored) { }
        String intro = selectedFreeApps.isEmpty() || selectedFreeApps.contains(pkg)
                ? "这是自由行动允许探索的安全应用「" + name + "」。先根据实际读取到的当前页面决定下一步，不要预先声称看过什么。"
                : "她发现手机装有应用「" + name + "」。她目前还不知道里面具体有什么，要先实际打开并读取页面。";
        if (freeAction && phoneIdle) {
            return intro + "自由行动已开启，而且手机亮屏解锁、连续十分钟没有触控。现在可以在允许范围内的应用「" + name
                    + "」里自主探索 20 到 60 分钟；先告知用户，告知消息确实显示后再开始。可尝试安全的页面导航、滑动和返回；不能发送、发布、点赞、评论、购买、支付、删除、登录、授权或修改设置。"
                    + "没有真实打开应用和读取屏幕前，不要说已经进去看过或学到了什么。\n";
        }
        return intro + "如果这次主动聊天自然合适，可以结合自己的性格，具体问一句“我想去「" + name
                + "」看看十分钟，可以吗？”只有用户明确答应后才行动；对方拒绝或不回答就收住。"
                + "没有真实打开应用和读取屏幕前，不要说已经进去看过或学到了什么。\n";
    }

    /** The pending choice binds the pre-action announcement to the app actually opened. */
    static JSONObject pendingFreeAction(Context c) {
        if (c == null) return null;
        JSONObject item = Sg.readObj(c, FILE).optJSONObject("pendingFreeAction");
        if (item == null || System.currentTimeMillis() - item.optLong("ts", 0) > 10 * 60 * 1000L) return null;
        String pkg = item.optString("pkg", "");
        JSONObject app = findAllowedApp(c, pkg);
        return app != null && CompanionSession.autonomousTargetAllowed(c, app) ? app : null;
    }

    private static JSONObject findAllowedApp(Context c, String pkg) {
        JSONArray apps = Fg.apps(c);
        for (int i = 0; i < apps.length(); i++) {
            JSONObject app = apps.optJSONObject(i);
            if (app != null && pkg.equals(app.optString("pkg", ""))
                    && CompanionSession.freeActionAppAllowed(c, app)) return app;
        }
        return null;
    }

    static boolean hasFreeActionCandidate(Context c) {
        if (c == null) return false;
        Cfg cfg = Cfg.load(c);
        JSONArray selected = cfg.arr("freeActionApps");
        JSONObject state = Sg.readObj(c, FILE);
        JSONObject asked = state.optJSONObject("asked");
        long now = System.currentTimeMillis();
        JSONArray apps = Fg.apps(c);
        for (int i = 0; i < apps.length(); i++) {
            JSONObject app = apps.optJSONObject(i);
            if (!CompanionSession.freeActionAppAllowed(c, app)) continue;
            String pkg = app.optString("pkg", "");
            if (!CompanionSession.autonomousTargetAllowed(c, app, cfg, selected)) continue;
            long gap = FREE_APP_GAP_MS;
            if (asked == null || now - asked.optLong(pkg, 0) >= gap) return true;
        }
        return false;
    }

    static void clearPendingFreeAction(Context c) {
        if (c == null) return;
        try {
            JSONObject state = Sg.readObj(c, FILE);
            JSONObject pending = state.optJSONObject("pendingFreeAction");
            if (pending != null) {
                String pkg = pending.optString("pkg", "");
                JSONObject asked = state.optJSONObject("asked");
                if (asked != null && !pkg.isEmpty() && asked.optLong(pkg, 0) == pending.optLong("ts", -1)) {
                    long previous = pending.optLong("previousAskedAt", 0);
                    if (previous > 0) asked.put(pkg, previous); else asked.remove(pkg);
                    state.put("asked", asked);
                }
                if (state.optLong("lastPromptAt", 0) == pending.optLong("ts", -1))
                    state.put("lastPromptAt", pending.optLong("previousPromptAt", 0));
            }
            state.remove("pendingFreeAction");
            Sg.writeObj(c, FILE, state);
        } catch (Throwable ignored) { }
    }

    static void consumePendingFreeAction(Context c) {
        if (c == null) return;
        try {
            JSONObject state = Sg.readObj(c, FILE);
            state.remove("pendingFreeAction");
            Sg.writeObj(c, FILE, state);
        } catch (Throwable ignored) { }
    }
}
