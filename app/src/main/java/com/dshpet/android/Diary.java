package com.dshpet.android;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** 她的日记：有真实经历且自己想留下时才写，不设固定字数或每日篇数。 */
public final class Diary {
    private static final String FILE = "diary.jsonl";
    private static final String STATE = "diary_state.json";
    private static final long CONSIDER_GAP_MS = 6 * 60 * 60 * 1000L;
    /** 生成中标记，避免定时器重复触发导致多篇同日日记。 */
    private static volatile boolean busy;
    private static final Set<String> STOP = new HashSet<>(Arrays.asList(
            "今天", "明天", "昨天", "现在", "什么", "怎么", "这个", "那个", "我们", "你们",
            "可以", "没有", "就是", "还是", "因为", "所以", "但是", "如果", "觉得", "时候",
            "一下", "有点", "已经", "不是", "真的", "然后", "一个", "他们"));

    private Diary() {
    }

    /* ---------------- 读取 ---------------- */

    public static JSONArray list(Context c, int limit) {
        JSONArray out = new JSONArray();
        try {
            String s = Sg.read(Sg.file(c, FILE), "");
            String[] lines = s.split("\n");
            int n = 0;
            for (int k = lines.length - 1; k >= 0 && n < limit; k--) {
                if (lines[k].trim().isEmpty()) continue;
                try {
                    out.put(new JSONObject(lines[k]));
                    n++;
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    public static boolean has(Context c, String date) {
        JSONArray a = list(c, 40);
        for (int k = 0; k < a.length(); k++) {
            if (date.equals(a.optJSONObject(k).optString("date", ""))) return true;
        }
        return false;
    }

    /** 最近几天的日记，拼成给 prompt 的一段。 */
    public static String recentPrompt(Context c, int days) {
        JSONArray a = list(c, Math.max(1, days));
        if (a.length() == 0) return "";
        StringBuilder sb = new StringBuilder("你最近写下的日记：\n");
        for (int k = 0; k < a.length(); k++) {
            JSONObject o = a.optJSONObject(k);
            if (o == null) continue;
            sb.append(o.optString("date", "")).append("：").append(o.optString("text", "")).append('\n');
        }
        return sb.toString();
    }

    /**
     * 只在当前话题确实和某篇日记相关时，才带回 1 篇。
     * 日记是她的主观视角，不是用户原话；提示词里必须明确标注。
     */
    public static String promptRelevant(Context c, String query, int limit) {
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) return "";
        JSONArray a = list(c, 60);
        if (a.length() == 0) return "";
        String clean = q.replaceAll("[\\s，。！？、,.!?~～:：;；\"'“”‘’()（）\\[\\]【】]", "");
        if (clean.length() < 2) return "";
        java.util.Set<String> grams = new java.util.LinkedHashSet<>();
        for (int k = 0; k + 2 <= clean.length() && grams.size() < 30; k++) {
            String g = clean.substring(k, k + 2);
            if (!STOP.contains(g)) grams.add(g);
        }
        java.util.List<JSONObject> pick = new java.util.ArrayList<>();
        java.util.List<Integer> hits = new java.util.ArrayList<>();
        for (int k = a.length() - 1; k >= 0; k--) {
            JSONObject o = a.optJSONObject(k);
            if (o == null) continue;
            String text = o.optString("text", "");
            int hit = 0;
            for (String g : grams) if (text.contains(g)) hit++;
            if (hit <= 0) continue;
            pick.add(o);
            hits.add(hit);
        }
        if (pick.isEmpty()) return "";
        for (int i = 0; i < pick.size(); i++) {
            for (int j = i + 1; j < pick.size(); j++) {
                if (hits.get(j) > hits.get(i)) {
                    JSONObject to = pick.get(i);
                    pick.set(i, pick.get(j));
                    pick.set(j, to);
                    Integer th = hits.get(i);
                    hits.set(i, hits.get(j));
                    hits.set(j, th);
                }
            }
        }
        StringBuilder sb = new StringBuilder("你最近写的日记（你的视角，不是用户原话，不要当作用户事实）：\n");
        int n = 0;
        for (JSONObject o : pick) {
            if (n >= Math.max(1, limit)) break;
            sb.append("- ").append(o.optString("date", "")).append("：")
                    .append(o.optString("text", "")).append('\n');
            n++;
        }
        return sb.toString();
    }

    /* ---------------- 生成 ---------------- */

    /** 定时器给她一个写日记的机会；模型可以选择不写，不按日期或聊天数量强制产出。 */
    public static void maybeGenerate(Context c) {
        if (busy) return;
        Cfg cfg = Cfg.load(c);
        if (!cfg.b("diaryEnabled", true) || !cfg.aiReady()) return;
        long now = System.currentTimeMillis();
        JSONObject state = Sg.readObj(c, STATE);
        if (now - state.optLong("lastConsiderAt", 0) < CONSIDER_GAP_MS) return;
        String today = Intimacy.todayKey();
        if (!observed(c, today)) return;
        try {
            state.put("lastConsiderAt", now);
            Sg.writeObj(c, STATE, state);
        } catch (Throwable ignored) { }
        generate(c, today, dayChatCount(c, today) < 4, false);
    }

    /** 用户明确请她写一篇；仍由她自己决定写多长，但这次不选择跳过。 */
    public static void generateNow(Context c) {
        if (busy) return;
        String today = Intimacy.todayKey();
        generate(c, today, dayChatCount(c, today) < 4, true);
    }

    /** 她今天有没有可观察的东西：他在用什么应用、屏幕上的痕迹，或零星几句话。 */
    private static boolean observed(Context c, String date) {
        if (Bus.currentPackage != null && !Bus.currentPackage.isEmpty()) return true;
        if (Bus.screenText != null && !Bus.screenText.isEmpty()) return true;
        return dayChatCount(c, date) > 0;
    }

    private static void generate(Context c, final String date, boolean observer, boolean requested) {
        busy = true;
        JSONArray msgs = new JSONArray();
        try {
            JSONObject s = new JSONObject();
            s.put("role", "system");
            s.put("content", systemPrompt(c, date, observer, requested));
            msgs.put(s);
            JSONObject u = new JSONObject();
            u.put("role", "user");
            u.put("content", observer ? observation(c, date) : transcript(c, date));
            msgs.put(u);
        } catch (Throwable ignored) {
        }
        Ai.rawChat(c, msgs, 0, new Ai.Cb() {
            @Override
            public void ok(String text) {
                busy = false;
                String d = cleanDiary(text);
                if (d.isEmpty() || d.contains("[[不写]]")) return;
                try {
                    JSONObject o = new JSONObject();
                    o.put("date", date);
                    o.put("ts", System.currentTimeMillis());
                    o.put("text", d);
                    o.put("kind", observer ? "observe" : "chat");
                    o.put("stage", Intimacy.stageName(Intimacy.score(c)));
                    Sg.appendLine(c, FILE, o);
                    Bus.emit("diary", o);
                } catch (Throwable ignored) {
                }
            }

            @Override
            public void fail(String err) {
                busy = false;
            }
        });
    }

    private static String systemPrompt(Context c, String date, boolean observer, boolean requested) {
        JSONObject p = Data.persona(c);
        JSONObject u = Data.profile(c);
        String nick = u.optString("nickname", "");
        StringBuilder sb = new StringBuilder();
        sb.append("你是「").append(p.optString("name", "她")).append("」，");
        sb.append("和你在一起的用户").append(nick.isEmpty() ? "" : "叫「" + nick + "」").append("。\n");
        sb.append("请用第一人称记录 ").append(date).append(" 的经历。\n");
        if (!requested) {
            sb.append("要不要写由你自己决定。先判断今天有没有一件你真想记住、以后愿意翻看的事；"
                    + "如果没有，就只输出 [[不写]]，不要为了完成任务硬写。\n");
        }
        if (observer) {
            // 聊得极少的那天：她只能从屏幕的痕迹里猜他在忙什么，像安静的观察者
            sb.append("今天他几乎没跟你说上话，你只能从他在电脑/手机上留下的痕迹猜他在忙什么。\n");
            sb.append("像真人随手记下来的文字，长度由你自己决定，不设字数目标。"
                    + "可以写观察到的事、当时的心情、对某个应用或话题的好奇；也可以只写一两句。\n");
            sb.append("推测要用「好像」「大概」等语气，不要编造具体情节；不要复述隐私内容，"
                    + "也不要提到自己在看他的屏幕或这段写作要求。\n");
            return sb.toString();
        }
        sb.append("像真人随手记下来的文字，不分点、不加标题、不加日期。长度由你自己决定，"
                + "不设字数目标，不要凑字；可以写经历、感受、好奇心或想继续做的事。\n");
        sb.append("只写对话与观察材料里真实出现的事；信息不够就少写或选择不写，不要编造。"
                + "可以有小情绪、小抱怨、小期待，不必一味说好话。不要提自己是 AI、模型或程序。\n");
        return sb.toString();
    }

    /** 去掉外层引号和模型格式包裹，不截断她写下的内容。 */
    private static String cleanDiary(String text) {
        if (text == null) return "";
        String s = text.trim().replace("\r", "");
        if (s.startsWith("```") && s.endsWith("```")) {
            int nl = s.indexOf('\n');
            if (nl >= 0) s = s.substring(nl + 1, s.length() - 3).trim();
        }
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() > 1) s = s.substring(1, s.length() - 1).trim();
        if (s.startsWith("「") && s.endsWith("」") && s.length() > 1) s = s.substring(1, s.length() - 1).trim();
        return s;
    }

    /** 观察者视角的材料：他今天在用的应用、屏幕上的痕迹，加上那几句零星的话。 */
    private static String observation(Context c, String date) {
        StringBuilder sb = new StringBuilder();
        String app = appName(c, Bus.currentPackage);
        if (!app.isEmpty()) sb.append("他一直在用「").append(app).append("」。\n");
        String st = Bus.screenText == null ? "" : Bus.screenText.trim();
        if (!st.isEmpty()) {
            if (st.length() > 300) st = st.substring(0, 300);
            sb.append("屏幕上的文字（只用来判断他在忙什么，不要原样复述，也不要在日记里提隐私内容）：")
                    .append(st.replace("\n", " ")).append('\n');
        }
        String experiences = ExperienceLog.prompt(c);
        if (!experiences.isEmpty()) sb.append("她在共同浏览中实际观察并记下的心得（可自行判断值不值得写进日记）：\n")
                .append(experiences);
        sb.append(transcript(c, date));
        return sb.toString();
    }

    /** 把包名换成看得懂的应用名，拿不到就退回包名。 */
    private static String appName(Context c, String pkg) {
        if (pkg == null || pkg.isEmpty()) return "";
        try {
            android.content.pm.PackageManager pm = c.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Throwable t) {
            return pkg;
        }
    }

    /** 把某一天的对话压成一段文本，控制字数以免烧 token。 */
    private static String transcript(Context c, String date) {
        long start = Intimacy.dayStart(date);
        long end = start + 86400000L;
        JSONArray hist = Data.chatList(c, 400);
        StringBuilder sb = new StringBuilder();
        int chars = 0;
        for (int k = 0; k < hist.length(); k++) {
            JSONObject h = hist.optJSONObject(k);
            if (h == null) continue;
            long ts = h.optLong("ts", 0);
            if (ts < start || ts >= end) continue;
            String role = "user".equals(h.optString("role")) ? "他" : "你";
            String line = role + "：" + h.optString("content", "") + "\n";
            if (chars + line.length() > 2600) break;
            sb.append(line);
            chars += line.length();
        }
        if (sb.length() == 0) sb.append("（今天还没来得及说什么。）");
        return sb.toString();
    }

    private static int dayChatCount(Context c, String date) {
        long start = Intimacy.dayStart(date);
        long end = start + 86400000L;
        JSONArray hist = Data.chatList(c, 600);
        int n = 0;
        for (int k = 0; k < hist.length(); k++) {
            JSONObject h = hist.optJSONObject(k);
            if (h == null) continue;
            long ts = h.optLong("ts", 0);
            if (ts >= start && ts < end) n++;
        }
        return n;
    }

    public static JSONObject summary(Context c) {
        JSONObject o = new JSONObject();
        try {
            JSONArray a = list(c, 60);
            o.put("items", a);
            o.put("total", a.length());
            o.put("today", has(c, Intimacy.todayKey()));
            o.put("yesterday", has(c, Intimacy.yesterdayKey()));
            JSONArray dates = new JSONArray();
            for (int k = a.length() - 1; k >= 0; k--) dates.put(a.optJSONObject(k).optString("date", ""));
            o.put("dates", dates);
        } catch (Throwable ignored) {
        }
        return o;
    }
}
