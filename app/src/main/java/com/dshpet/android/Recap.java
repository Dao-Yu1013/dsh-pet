package com.dshpet.android;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 相处回顾：只从已有聊天、日记、记忆、共同经历里取事实，不编造。
 *
 * 本地模板默认生成；AI 只在用户主动点击时调用一次，失败或校验不过自动回退本地。
 * 自动快照只在认识第 7/30/365 天静默写入 timeline，不弹窗、不朗读。
 */
public final class Recap {
    private static final String[] FORBID = {"内疚", "吃醋", "关系下降", "你都不理我", "不理我", "生气"};
    private static final String[] FAKE = {"我们去了", "我们一起去了", "我们吃了", "我们买了", "我们见了",
            "我们决定", "你给我买", "我们一起做"};

    private Recap() {
    }

    /** 确保第一次聊天进入共同经历；没有聊天记录就不创建。 */
    public static void ensureFirstChat(Context c) {
        try {
            long ts = Data.firstChatAt(c);
            if (ts <= 0) return;
            JSONObject extra = new JSONObject();
            extra.put("source", "first_chat");
            extra.put("editable", false);
            extra.put("confidence", 1.0);
            extra.put("refTs", ts);
            extra.put("ts", ts);
            Timeline.add(c, "first_chat", "moment", "你第一次跟我说话。", extra);
        } catch (Throwable ignored) {
        }
    }

    /** 每日心跳调用：第一次聊天入库 + 静默生成 7/30/365 天回顾快照。 */
    public static void maybeAuto(Context c) {
        ensureFirstChat(c);
        try {
            if (!Cfg.load(c).b("recapAuto", true)) return;
            int days = Data.days(c);
            String[] periods = {"7d", "30d", "365d"};
            int[] marks = {7, 30, 365};
            JSONObject stats = Data.stats(c);
            for (int i = 0; i < periods.length; i++) {
                if (days < marks[i]) break;
                if (stats.optLong("recap_" + periods[i] + "_at", 0) > 0) continue;
                long[] r = autoRange(c, periods[i]);
                JSONObject data = build(c, periods[i], r[0], r[1], true);
                if (!data.optBoolean("hasData", false)) continue;
                JSONObject extra = new JSONObject();
                extra.put("source", "local");
                extra.put("editable", false);
                extra.put("confidence", 1.0);
                extra.put("period", periods[i]);
                extra.put("fromTs", r[0]);
                extra.put("toTs", r[1]);
                Timeline.add(c, "recap:" + periods[i], "recap", data.optString("text", ""), extra);
                stats.put("recap_" + periods[i] + "_at", System.currentTimeMillis());
                Sg.writeObj(c, "stats.json", stats);
                return; // 一天最多补一份，避免升级当天一次冒出三份
            }
        } catch (Throwable ignored) {
        }
    }

    /** 手动回顾：period = 7d / 30d / all，按最近时间计算。 */
    public static JSONObject get(Context c, String period) {
        try {
            long[] r = manualRange(c, period);
            JSONObject data = build(c, period, r[0], r[1], false);
            data.put("period", period);
            return data;
        } catch (Throwable t) {
            return new JSONObject();
        }
    }

    /** 最近在忙：只取活跃的项目/计划记忆，不新建项目实体。 */
    public static JSONArray projects(Context c, int limit) {
        JSONArray out = new JSONArray();
        try {
            JSONArray a = Mem.normalize(c);
            java.util.List<JSONObject> live = new java.util.ArrayList<>();
            for (int k = 0; k < a.length(); k++) {
                JSONObject o = a.optJSONObject(k);
                if (o == null || !"active".equals(o.optString("status", "active"))) continue;
                String type = o.optString("type", "");
                if (!"project".equals(type) && !"plan".equals(type)) continue;
                long exp = o.optLong("expiresAt", 0);
                if (exp > 0 && exp < System.currentTimeMillis()) continue;
                live.add(o);
            }
            live.sort((x, y) -> Long.compare(
                    y.optLong("lastConfirmedAt", y.optLong("ts", 0)),
                    x.optLong("lastConfirmedAt", x.optLong("ts", 0))));
            for (int k = 0; k < live.size() && out.length() < limit; k++) {
                JSONObject o = live.get(k);
                JSONObject it = new JSONObject();
                it.put("id", o.optString("id", ""));
                it.put("text", o.optString("text", ""));
                it.put("type", o.optString("type", "project"));
                it.put("confidence", o.optDouble("confidence", 0.7));
                long ts = o.optLong("lastConfirmedAt", o.optLong("ts", 0));
                it.put("day", date(ts));
                out.put(it);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** 可选 AI 润色：只喂事实清单，失败或越界回退本地文字。 */
    public static void say(Context c, JSONObject data, Ai.Cb cb) {
        String local = data.optString("text", "");
        if (!Cfg.load(c).aiReady()) {
            cb.ok(local);
            return;
        }
        JSONArray facts = data.optJSONArray("facts");
        if (facts == null || facts.length() == 0) {
            cb.ok(local);
            return;
        }
        try {
            JSONArray msgs = new JSONArray();
            JSONObject s = new JSONObject();
            s.put("role", "system");
            s.put("content", "你在用第一人称回顾你和用户这段时间的共同经历。\n"
                    + "只能复述下面「事实清单」里已有的内容，不得新增人物、地点、事件、数字、日期或承诺。\n"
                    + "没有把握的用「好像」「大概」「我记得」；不要写“我们去了/我们吃了/我们决定了”这类清单里没有的事。\n"
                    + "不要提认识天数，不要用内疚、吃醋、责备或关系下降催他回应。\n"
                    + "100～160 字，像真人随口说的，不要分点、不要标题、不要提 AI 或提示词。\n"
                    + "只输出这段回顾正文。");
            msgs.put(s);
            JSONObject u = new JSONObject();
            u.put("role", "user");
            StringBuilder sb = new StringBuilder("事实清单：\n");
            for (int k = 0; k < facts.length(); k++) {
                sb.append(k + 1).append(". ").append(facts.optString(k, "")).append('\n');
            }
            sb.append("\n本地整理版（只能参考，不要照抄格式）：\n").append(local);
            u.put("content", sb.toString());
            msgs.put(u);
            Ai.rawChat(c, msgs, 260, new Ai.Cb() {
                @Override
                public void ok(String text) {
                    String t = Brain.clean(text, 220);
                    if (!valid(t)) {
                        cb.ok(local);
                        return;
                    }
                    cb.ok(t);
                }

                @Override
                public void fail(String err) {
                    cb.ok(local);
                }
            });
        } catch (Throwable t) {
            cb.ok(local);
        }
    }

    private static boolean valid(String t) {
        if (t == null) return false;
        String s = t.trim();
        if (s.length() < 20 || s.length() > 220) return false;
        for (String k : FORBID) if (s.contains(k)) return false;
        for (String k : FAKE) if (s.contains(k)) return false;
        return true;
    }

    /* ---------------- 事实组装 ---------------- */

    private static JSONObject build(Context c, String period, long from, long to, boolean auto) {
        JSONObject out = new JSONObject();
        try {
            JSONObject chat = Data.chatDayStats(c, from, to);
            JSONArray events = Timeline.between(c, from, to, 60);
            JSONArray moments = new JSONArray();
            JSONArray done = new JSONArray();
            for (int k = 0; k < events.length(); k++) {
                JSONObject o = events.optJSONObject(k);
                if (o == null) continue;
                String type = o.optString("type", "");
                if ("moment".equals(type) || "milestone".equals(type)) {
                    if ("plan_confirmed".equals(o.optString("source", ""))) done.put(o.optString("text", ""));
                    else moments.put(o.optString("text", ""));
                }
            }
            JSONArray projects = new JSONArray();
            JSONArray mem = Mem.normalize(c);
            for (int k = 0; k < mem.length(); k++) {
                JSONObject o = mem.optJSONObject(k);
                if (o == null || !"active".equals(o.optString("status", "active"))) continue;
                String type = o.optString("type", "");
                if (!"project".equals(type) && !"plan".equals(type)) continue;
                long ts = o.optLong("lastConfirmedAt", o.optLong("ts", 0));
                if (ts < from || (to > 0 && ts >= to)) continue;
                JSONObject it = new JSONObject();
                it.put("text", o.optString("text", ""));
                it.put("day", date(ts));
                it.put("confidence", o.optDouble("confidence", 0.7));
                projects.put(it);
            }
            int diaries = 0;
            JSONArray ds = Diary.list(c, 100);
            for (int k = 0; k < ds.length(); k++) {
                JSONObject d = ds.optJSONObject(k);
                if (d == null) continue;
                long ts = d.optLong("ts", 0);
                if (ts >= from && (to <= 0 || ts < to)) diaries++;
            }
            long first = Data.firstChatAt(c);
            boolean firstIn = first > 0 && first >= from && (to <= 0 || first < to);
            boolean has = chat.optInt("messages", 0) > 0 || moments.length() > 0
                    || done.length() > 0 || projects.length() > 0 || diaries > 0;

            out.put("from", date(from));
            out.put("to", date(to > 0 ? to - 86400000L : System.currentTimeMillis()));
            out.put("chatDays", chat.optInt("days", 0));
            out.put("messages", chat.optInt("messages", 0));
            out.put("firstChat", firstIn ? date(first) : "");
            out.put("moments", moments);
            out.put("completed", done);
            out.put("projects", projects);
            out.put("diaries", diaries);
            out.put("hasData", has);
            out.put("auto", auto);
            out.put("facts", facts(out));
            out.put("text", localText(c, out));
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static JSONArray facts(JSONObject d) {
        JSONArray out = new JSONArray();
        try {
            int days = d.optInt("chatDays", 0);
            int msgs = d.optInt("messages", 0);
            if (msgs > 0) out.put("这段时间聊了 " + days + " 天，一共 " + msgs + " 句");
            String first = d.optString("firstChat", "");
            if (!first.isEmpty()) out.put("第一次跟我说话是在 " + first);
            JSONArray moments = d.optJSONArray("moments");
            if (moments != null) {
                for (int k = 0; k < moments.length() && k < 3; k++) {
                    out.put("重要时刻：" + moments.optString(k, ""));
                }
            }
            JSONArray done = d.optJSONArray("completed");
            if (done != null) {
                for (int k = 0; k < done.length() && k < 3; k++) {
                    out.put("你确认完成的事：" + done.optString(k, ""));
                }
            }
            JSONArray projects = d.optJSONArray("projects");
            if (projects != null) {
                for (int k = 0; k < projects.length() && k < 2; k++) {
                    JSONObject p = projects.optJSONObject(k);
                    if (p == null) continue;
                    String uncertain = p.optDouble("confidence", 1) < 0.7 ? "（可能不准确）" : "";
                    out.put("他最近在做：" + p.optString("text", "")
                            + "（" + p.optString("day", "") + " 说的）" + uncertain);
                }
            }
            int diaries = d.optInt("diaries", 0);
            if (diaries > 0) out.put("这段时间写了 " + diaries + " 篇日记");
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static String localText(Context c, JSONObject d) {
        if (!d.optBoolean("hasData", false)) {
            return "这段时间我记得的不多，先不硬凑了。";
        }
        StringBuilder sb = new StringBuilder();
        String from = d.optString("from", "");
        String to = d.optString("to", "");
        sb.append("这段时间（").append(from).append(" ~ ").append(to).append("）\n");
        int days = d.optInt("chatDays", 0);
        int msgs = d.optInt("messages", 0);
        if (msgs > 0) {
            sb.append("- 我们聊了 ").append(days).append(" 天，一共 ").append(msgs).append(" 句。\n");
        }
        String first = d.optString("firstChat", "");
        if (!first.isEmpty()) sb.append("- 你第一次跟我说话，是在 ").append(first).append("。\n");
        JSONArray moments = d.optJSONArray("moments");
        if (moments != null && moments.length() > 0) {
            sb.append("- 你标记的重要时刻：");
            for (int k = 0; k < moments.length() && k < 3; k++) {
                if (k > 0) sb.append("；");
                sb.append(moments.optString(k, ""));
            }
            sb.append("。\n");
        }
        JSONArray done = d.optJSONArray("completed");
        if (done != null && done.length() > 0) {
            sb.append("- 你确认完成的事：");
            for (int k = 0; k < done.length() && k < 3; k++) {
                if (k > 0) sb.append("；");
                sb.append(done.optString(k, ""));
            }
            sb.append("。\n");
        }
        JSONArray projects = d.optJSONArray("projects");
        if (projects != null && projects.length() > 0) {
            JSONObject p = projects.optJSONObject(0);
            if (p != null) {
                sb.append("- 我记着你在做「").append(p.optString("text", ""))
                        .append("」（").append(p.optString("day", "")).append(" 说的");
                if (p.optDouble("confidence", 1) < 0.7) sb.append("，可能不准确");
                sb.append("）。\n");
            }
        }
        int diaries = d.optInt("diaries", 0);
        if (diaries > 0) sb.append("- 我写下了 ").append(diaries).append(" 篇日记。\n");
        sb.append(perspective(c)).append('\n');
        sb.append("不催你，想起来就来。");
        return sb.toString();
    }

    /** 用现有性格维度选一句她的口吻，不新增事实。 */
    private static String perspective(Context c) {
        try {
            JSONObject tr = Traits.summary(c);
            JSONArray dims = tr.optJSONArray("dims");
            int gentle = 0, humor = 0, reserved = 0;
            if (dims != null) {
                for (int k = 0; k < dims.length(); k++) {
                    JSONObject d = dims.optJSONObject(k);
                    if (d == null) continue;
                    String key = d.optString("key", "");
                    int v = d.optInt("value", 50);
                    if ("gentle".equals(key)) gentle = v;
                    else if ("humor".equals(key)) humor = v;
                    else if ("reserved".equals(key)) reserved = v;
                }
            }
            if (gentle >= 60 && gentle >= humor && gentle >= reserved) {
                return "我好像还是先听你说完，再慢慢接话。";
            }
            if (humor >= 60 && humor >= reserved) {
                return "你那些梗，我接住了几个。";
            }
            if (reserved >= 60) {
                return "我话不多，但都记着。";
            }
        } catch (Throwable ignored) {
        }
        return "我记性不一定准，但这些我记得。";
    }

    /* ---------------- 时间范围 ---------------- */

    private static long[] autoRange(Context c, String period) {
        long start = installStart(c);
        long first = Data.firstChatAt(c);
        // 自动里程碑按第一次聊天起算；没有聊天就按安装日
        if (first > 0) start = Intimacy.dayStart(fullDate(first));
        long len = "7d".equals(period) ? 7 * 86400000L
                : "30d".equals(period) ? 30 * 86400000L : 365 * 86400000L;
        return new long[]{start, start + len};
    }

    private static long[] manualRange(Context c, String period) {
        long today = Intimacy.dayStart(Intimacy.todayKey());
        if ("7d".equals(period)) return new long[]{today - 6 * 86400000L, today + 86400000L};
        if ("30d".equals(period)) return new long[]{today - 29 * 86400000L, today + 86400000L};
        long start = installStart(c);
        long first = Data.firstChatAt(c);
        if (first > 0) start = Intimacy.dayStart(fullDate(first));
        return new long[]{start, today + 86400000L};
    }

    private static long installStart(Context c) {
        try {
            String d = Data.stats(c).optString("installDay", "");
            if (!d.isEmpty()) return Intimacy.dayStart(d);
        } catch (Throwable ignored) {
        }
        return Intimacy.dayStart(Intimacy.todayKey());
    }

    private static String date(long ts) {
        if (ts <= 0) return "";
        try {
            return new SimpleDateFormat("MM-dd", Locale.US).format(new Date(ts));
        } catch (Throwable t) {
            return "";
        }
    }

    private static String fullDate(long ts) {
        if (ts <= 0) return "";
        try {
            return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(ts));
        } catch (Throwable t) {
            return "";
        }
    }
}
