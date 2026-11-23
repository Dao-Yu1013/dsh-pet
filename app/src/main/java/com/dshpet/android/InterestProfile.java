package com.dshpet.android;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

/** Local, evidence-based interests assembled from launcher apps, optional usage stats, and the shelf. */
public final class InterestProfile {
    private static final String FILE = "interest_profile.json";
    private InterestProfile() { }

    public static String prompt(Context c) {
        if (c == null || !Cfg.load(c).b("watchApp", true)) return "";
        JSONObject state = Sg.readObj(c, FILE);
        StringBuilder out = new StringBuilder();
        JSONArray installed = Fg.apps(c);
        if (installed.length() > 0) {
            JSONObject usage = Usage.state(c);
            JSONObject today = usage.optJSONObject("today");
            JSONArray day = today == null ? new JSONArray() : today.optJSONArray("apps");
            if (day == null) day = new JSONArray();
            JSONArray installedProfile = new JSONArray();
            for (int i = 0; i < installed.length() && installedProfile.length() < 80; i++) {
                JSONObject source = installed.optJSONObject(i);
                if (source == null) continue;
                try {
                    installedProfile.put(new JSONObject().put("pkg", source.optString("pkg"))
                            .put("name", source.optString("name")));
                } catch (Throwable ignored) { }
            }
            JSONArray usageProfile = new JSONArray();
            for (int i = 0; i < day.length() && usageProfile.length() < 20; i++) {
                JSONObject source = day.optJSONObject(i);
                if (source == null) continue;
                try {
                    usageProfile.put(new JSONObject().put("pkg", source.optString("pkg"))
                            .put("name", source.optString("name")).put("minutes", source.optInt("min"))
                            .put("date", today == null ? "" : today.optString("date")));
                } catch (Throwable ignored) { }
            }
            JSONArray shelf = Books.list(c).optJSONArray("visible");
            JSONArray reading = new JSONArray();
            if (shelf != null) {
                for (int i = 0; i < shelf.length(); i++) {
                    JSONObject book = shelf.optJSONObject(i);
                    if (book == null) continue;
                    try {
                        reading.put(new JSONObject().put("id", book.optString("id"))
                                .put("title", book.optString("title", "未命名"))
                                .put("lastReadAt", book.optLong("lastReadAt", 0))
                                .put("progress", book.optJSONObject("progress") == null ? new JSONObject()
                                        : book.optJSONObject("progress")));
                    } catch (Throwable ignored) { }
                }
            }
            try {
                state.put("updatedAt", System.currentTimeMillis());
                state.put("installedApps", installedProfile);
                state.put("dailyAppUse", usageProfile);
                state.put("readingHistory", reading);
                Sg.writeObj(c, FILE, state);
            } catch (Throwable ignored) { }
            out.append("本机兴趣线索（只作好奇话题，不代表她已经使用或看过）：\n");
            int n = 0;
            for (int i = 0; i < day.length() && n < 3; i++) {
                JSONObject app = day.optJSONObject(i);
                if (app == null) continue;
                String name = app.optString("name", "").trim();
                if (name.isEmpty()) continue;
                out.append("- 今天常用应用：").append(name).append("（约 ")
                        .append(app.optInt("min", 0)).append(" 分钟）\n");
                n++;
            }
            if (shelf != null && shelf.length() > 0) {
                JSONObject book = null;
                long newest = -1;
                for (int i = 0; i < shelf.length(); i++) {
                    JSONObject candidate = shelf.optJSONObject(i);
                    if (candidate != null && candidate.optLong("lastReadAt", 0) >= newest) {
                        book = candidate;
                        newest = candidate.optLong("lastReadAt", 0);
                    }
                }
                if (book != null) out.append("- 内置书架有《").append(book.optString("title", "未命名"))
                        .append("》；只知道书名和阅读进度，不代表已经读过正文。若自然合适，可以问他要不要一起读；得到同意后必须先列出书架让他选。\n");
            }
            JSONArray known = state.optJSONArray("learned");
            if (known != null) {
                int shown = 0;
                for (int i = known.length() - 1; i >= 0 && shown < 3; i--) {
                    JSONObject item = known.optJSONObject(i);
                    if (item == null) continue;
                    out.append("- 以前截屏并成功分析过「").append(item.optString("topic", "应用"))
                            .append("」的可见内容：").append(item.optString("evidence", "")).append("\n");
                    shown++;
                }
            }
            if (Cfg.load(c).b("freeAction", false)) {
                if (CompanionSession.phoneIdle(c)) {
                    out.append("自由行动已开启；手机亮屏、解锁且连续十分钟无触控时，可以在用户允许范围内的安全应用里限时探索，且不要求普通主动开口开关开启，但聊天类主动行为不能关闭。勾选应用时限制到勾选项，未勾选时允许所有通过安全筛选的应用。开始前先直接显示目的地提示；总静音、专注、夜间、用户聊天/听语音时不启动。每一步都要依据刚读取的页面，只能滑动、返回或点可见普通导航文字；不能发消息、发布、点赞、评论、支付、删除、登录或改设置。截图和页面文字会发给已配置的 AI 服务；只记录有实际观察依据的简短心得，不保存整屏内容。\n");
                } else {
                    out.append("自由行动已开启但手机不空闲；不要发起自动行动，也不要打断用户正在看的内容。\n");
                }
            } else if (A11y.ready()) {
                out.append("若自然合适，可以用自己的性格问他愿不愿意一起打开/看看；没有实际读取证据时，不可声称使用过、看过或学会了。\n");
            } else {
                out.append("可以对名字感到好奇，但无障碍服务未连接时不要承诺现在能打开、浏览或读取内容。\n");
            }
        }
        return out.toString();
    }

    /** Mark a topic as learned only from an observed screen result. */
    public static void learned(Context c, String topic, String evidence) {
        if (c == null || topic == null || topic.trim().isEmpty() || evidence == null || evidence.trim().isEmpty()) return;
        try {
            JSONObject p = Sg.readObj(c, FILE);
            JSONArray learned = p.optJSONArray("learned");
            if (learned == null) learned = new JSONArray();
            JSONObject item = new JSONObject();
            item.put("topic", topic.trim());
            item.put("evidence", evidence.trim());
            item.put("ts", System.currentTimeMillis());
            JSONArray next = new JSONArray();
            for (int i = Math.max(0, learned.length() - 29); i < learned.length(); i++) {
                JSONObject old = learned.optJSONObject(i);
                if (old != null && !topic.trim().equals(old.optString("topic"))) next.put(old);
            }
            next.put(item);
            p.put("learned", next);
            Sg.writeObj(c, FILE, p);
        } catch (Throwable ignored) { }
    }
}
