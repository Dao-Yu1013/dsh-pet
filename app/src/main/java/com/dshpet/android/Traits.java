package com.dshpet.android;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * 长期性格演化：10 维「稳定基线」+ 按天聚合的证据。
 *
 * 关键原则：
 * - 单条消息只增加当天证据，不直接改基线；
 * - 每天结算一次，只有跨天重复出现的行为才缓慢进入基线；
 * - 有日/周/月上限和人格种子包络，不会无限放大；
 * - State 是临时情绪，不写入这里。
 */
public final class Traits {
    private static final String FILE = "traits.json";
    private static final int VERSION = 2;
    private static final String[] DIM = {"lively", "clingy", "sharp", "humor", "gentle", "night", "curious", "reserved", "protective", "boundary"};
    private static final String[] LABEL = {"活泼", "黏人", "毒舌", "幽默", "温柔", "夜猫子", "好奇", "含蓄", "保护欲", "边界感"};
    /** 至少观察这么多天才开始显示「已成形」。 */
    private static final int MIN_DAYS = 7;
    private static final long REFLECT_DAYS = 7;

    private static final String[] FEEL = {"累", "烦", "难过", "开心", "压力", "焦虑", "委屈", "想哭", "崩溃", "心疼"};
    private static final String[] HUMOR = {"哈哈", "233", "笑死", "hhh", "梗", "乐死", "离谱"};
    private static final String[] HURT = {"闭嘴", "滚", "烦死了", "别烦我", "讨厌你", "走开", "没用"};
    private static final String[] PUSH_AWAY = {"别管我", "不用你管", "关你什么事", "要你管", "别烦", "少管我"};
    private static final String[] FORBID = {"我是", "人格", "性格类型", "我最近变", "内疚", "吃醋", "关系下降", "你都不理我"};

    private Traits() {
    }

    public static synchronized JSONObject load(Context c) {
        JSONObject o = Sg.readObj(c, FILE);
        boolean changed = false;
        if (o.optInt("version", 0) < VERSION) {
            migrate(c, o);
            changed = true;
        }
        try {
            if (!o.has("dims")) {
                o.put("dims", defaultDims());
                changed = true;
            }
            JSONObject d = o.optJSONObject("dims");
            if (d == null) {
                d = defaultDims();
                o.put("dims", d);
                changed = true;
            }
            for (String k : DIM) {
                if (!d.has(k)) {
                    d.put(k, 50.0);
                    changed = true;
                }
            }
            if (!o.has("samples")) {
                o.put("samples", 0);
                changed = true;
            }
            if (!o.has("days")) {
                o.put("days", 0);
                changed = true;
            }
            if (!o.has("roleId")) {
                o.put("roleId", "default");
                changed = true;
            }
            if (!o.has("origin")) {
                o.put("origin", copy(d));
                changed = true;
            }
            if (!o.has("evidence")) {
                o.put("evidence", new JSONObject());
                changed = true;
            }
            if (!o.has("evidenceDays")) {
                o.put("evidenceDays", new JSONObject());
                changed = true;
            }
            if (!o.has("address")) {
                o.put("address", "");
                changed = true;
            }
            if (!o.has("note")) {
                o.put("note", "");
                changed = true;
            }
            if (!o.has("topics")) {
                o.put("topics", new JSONArray());
                changed = true;
            }
            if (!o.has("emojiRate")) {
                o.put("emojiRate", 0.3);
                changed = true;
            }
            if (!o.has("lastReflect")) {
                o.put("lastReflect", 0L);
                changed = true;
            }
            if (settleIfNeeded(c, o)) changed = true;
        } catch (Throwable ignored) {
        }
        if (changed) Sg.writeObj(c, FILE, o);
        return o;
    }

    /**
     * v2 迁移：
     * - 已经选过人格种子的旧用户：按确认的策略回到种子，旧数据先归档；
     * - 还没完成人格升级的用户：保留当前 10 维作为临时 origin，等升级表单保存时再由 seed() 重置。
     */
    private static void migrate(Context c, JSONObject o) {
        try {
            JSONObject dims = o.optJSONObject("dims");
            if (dims == null) dims = defaultDims();
            boolean adopted = Contract.load(c).optInt("seedVersion", 0) >= 2;
            JSONObject persona = Data.persona(c);
            if (adopted) {
                archive(c, "traits", o);
                if (persona.length() > 0) archive(c, "persona", persona);
                JSONObject seed = Persona.initialTraits(c);
                if (seed == null) seed = defaultDims();
                o.put("dims", seed);
                o.put("origin", copy(seed));
                o.put("samples", 0);
                o.put("days", 0);
                o.put("evidence", new JSONObject());
                o.put("evidenceDays", new JSONObject());
                o.put("note", "");
                o.put("originAt", System.currentTimeMillis());
                o.put("originEmojiRate", 0.3);
                // 重置 persona 到新种子，避免旧风格和新基线打架
                String aid = Contract.load(c).optString("archetypeId", "natural");
                String tid = Contract.load(c).optString("toneId", "soft");
                String rid = Contract.load(c).optString("relationId", "friend");
                synchronized (Data.personaLock()) {
                    JSONObject np = Data.persona(c);
                    np.put("style", Persona.styleFor(c, tid, aid));
                    np.put("greeting", Persona.greetingFor(aid));
                    np.put("boundaries", Persona.boundariesFor(c, rid, aid));
                    o.put("originStyle", np.optString("style", ""));
                    o.put("originGreeting", np.optString("greeting", ""));
                    o.put("originBoundaries", np.optString("boundaries", ""));
                    np.put("autoDate", today().replace("-", ""));
                    np.put("autoAt", System.currentTimeMillis());
                    Data.savePersona(c, np);
                }
                o.put("lastChangeWhy", "旧版人格已归档，基线回到人格种子");
            } else {
                o.put("origin", copy(dims));
                o.put("originStyle", persona.optString("style", ""));
                o.put("originGreeting", persona.optString("greeting", ""));
                o.put("originBoundaries", persona.optString("boundaries", ""));
                o.put("originEmojiRate", o.optDouble("emojiRate", 0.3));
                o.put("originAt", System.currentTimeMillis());
                o.put("lastChangeWhy", "等待人格升级表单确认");
            }
            o.put("version", VERSION);
            o.put("roleId", o.optString("roleId", "default"));
            o.put("weekStart", today());
            o.put("weekBase", copy(o.optJSONObject("dims")));
            o.put("monthStart", today());
            o.put("monthBase", copy(o.optJSONObject("dims")));
        } catch (Throwable ignored) {
        }
    }

    /** 用新人格种子重置 10 维起点；旧数据归档，不删除。 */
    public static synchronized void seed(Context c, JSONObject dims) {
        if (dims == null) return;
        try {
            JSONObject old = Sg.readObj(c, FILE);
            if (old.length() > 0) archive(c, "traits", old);
            // persona 的旧版本由 Persona.applySeed 在改动前归档，这里不重复归档，避免同名覆盖
            JSONObject p = Data.persona(c);
            JSONObject o = new JSONObject();
            o.put("version", VERSION);
            o.put("roleId", "default");
            o.put("dims", copy(dims));
            o.put("origin", copy(dims));
            o.put("samples", 0);
            o.put("days", 0);
            o.put("evidence", new JSONObject());
            o.put("evidenceDays", new JSONObject());
            o.put("note", "");
            o.put("topics", old.optJSONArray("topics") == null ? new JSONArray() : old.optJSONArray("topics"));
            o.put("address", old.optString("address", ""));
            o.put("emojiRate", 0.3);
            o.put("originEmojiRate", 0.3);
            o.put("lastReflect", 0L);
            o.put("originAt", System.currentTimeMillis());
            o.put("originStyle", p.optString("style", ""));
            o.put("originGreeting", p.optString("greeting", ""));
            o.put("originBoundaries", p.optString("boundaries", ""));
            o.put("weekStart", today());
            o.put("weekBase", copy(dims));
            o.put("monthStart", today());
            o.put("monthBase", copy(dims));
            o.put("lastChangeWhy", "人格种子初始化");
            Sg.writeObj(c, FILE, o);
        } catch (Throwable ignored) {
        }
    }

    /** 用户点「重置到人格种子」：回到 origin，连风格/开场白/边界一起回。 */
    public static synchronized boolean reset(Context c) {
        try {
            JSONObject o = load(c);
            JSONObject origin = o.optJSONObject("origin");
            JSONObject dims = o.optJSONObject("dims");
            if (origin == null) origin = dims == null ? defaultDims() : copy(dims);
            archive(c, "traits", o);
            JSONObject p;
            synchronized (Data.personaLock()) {
                p = Data.persona(c);
                archive(c, "persona", p);
                o.put("dims", copy(origin));
                o.put("samples", 0);
                o.put("days", 0);
                o.put("evidence", new JSONObject());
                o.put("evidenceDays", new JSONObject());
                o.put("note", "");
                o.put("emojiRate", o.optDouble("originEmojiRate", 0.3));
                o.put("lastReflect", 0L);
                o.put("weekStart", today());
                o.put("weekBase", copy(origin));
                o.put("monthStart", today());
                o.put("monthBase", copy(origin));
                o.put("lastChangeWhy", "用户重置到人格种子");
                Sg.writeObj(c, FILE, o);
                p.put("style", o.optString("originStyle", p.optString("style", "")));
                p.put("greeting", o.optString("originGreeting", p.optString("greeting", "")));
                p.put("boundaries", o.optString("originBoundaries", p.optString("boundaries", "")));
                Data.savePersona(c, p);
            }
            State.toCalm(c);
            PersonaAuto.markReset(c);
            Bus.emit("traits", summary(c));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 每条用户消息只记录当天证据，不直接改基线。 */
    public static synchronized void observe(Context c, String text) {
        if (text == null) return;
        String t = text.trim();
        if (t.isEmpty()) return;
        if (!Cfg.load(c).b("traitEvolve", true)) return;
        JSONObject o = load(c);
        JSONObject ev = o.optJSONObject("evidence");
        if (ev == null) ev = new JSONObject();
        try {
            int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
            if (hour >= 23 || hour < 3) add(ev, "night");
            if (t.indexOf('！') >= 0 || t.indexOf('!') >= 0) add(ev, "lively");
            if (t.length() > 40) add(ev, "lively");
            if (t.length() > 80) add(ev, "curious");
            double moodScore = State.summary(c).optDouble("moodScore", 0);
            boolean lowMood = moodScore < -30;
            if (containsAny(t, HUMOR)) {
                if (lowMood) add(ev, "protective");
                else add(ev, "humor");
            }
            if (containsAny(t, FEEL)) {
                add(ev, "gentle");
                add(ev, "protective");
            }
            if (t.indexOf('？') >= 0 || t.indexOf('?') >= 0) add(ev, "curious");
            if (t.length() <= 3) add(ev, "reserved");
            if (containsAny(t, HURT)) add(ev, "sharp");
            if (containsAny(t, PUSH_AWAY)) add(ev, "boundary");
            long last = o.optLong("lastObserve", 0);
            long now = System.currentTimeMillis();
            if (last > 0 && now - last < 60000L) add(ev, "clingy");
            o.put("lastObserve", now);
            o.put("evidence", ev);
            o.put("samples", o.optInt("samples", 0) + 1);
            learnAddress(o, t);
            o.put("updated", now);
            Sg.writeObj(c, FILE, o);
        } catch (Throwable ignored) {
        }
    }

    private static void add(JSONObject ev, String k) {
        try {
            if (ev.optInt(k, 0) < 3) ev.put(k, ev.optInt(k, 0) + 1);
        } catch (Throwable ignored) {
        }
    }

    /** 跨天结算：把前一天证据缓慢折算进基线，带上限与包络。 */
    private static boolean settleIfNeeded(Context c, JSONObject o) {
        try {
            String day = today();
            String evDay = o.optString("evidenceDay", "");
            if (evDay.isEmpty()) {
                o.put("evidenceDay", day);
                return true;
            }
            if (evDay.equals(day)) return false;

            JSONObject ev = o.optJSONObject("evidence");
            JSONObject d = o.optJSONObject("dims");
            JSONObject origin = o.optJSONObject("origin");
            JSONObject evDays = o.optJSONObject("evidenceDays");
            if (d == null) d = defaultDims();
            if (origin == null) origin = copy(d);
            if (evDays == null) evDays = new JSONObject();

            boolean any = ev != null && ev.length() > 0;
            if (!any) {
                o.put("evidenceDay", day);
                o.put("evidence", new JSONObject());
                return true;
            }

            int days = o.optInt("days", 0) + 1;
            boolean seeded = !"natural".equals(Contract.load(c).optString("archetypeId", "natural"));
            double env = seeded ? 18 : 30;
            int speed = Cfg.load(c).i("traitSpeed", 1);
            double factor = speed <= 0 ? 0.6 : speed >= 2 ? 1.5 : 1.0;

            rotateWeek(o, day, d);
            rotateMonth(o, day, d);
            JSONObject weekBase = o.optJSONObject("weekBase");
            JSONObject monthBase = o.optJSONObject("monthBase");
            if (weekBase == null) weekBase = copy(d);
            if (monthBase == null) monthBase = copy(d);

            for (String k : DIM) {
                int count = ev == null ? 0 : Math.min(3, ev.optInt(k, 0));
                if (count <= 0) continue;
                int dc = evDays.optInt(k, 0) + 1;
                evDays.put(k, dc);
                if (dc < 3 || days < MIN_DAYS) continue;
                double base = origin.optDouble(k, 50);
                double v = d.optDouble(k, 50);
                double delta = Math.min(1.2, 0.15 * count * factor);
                v = v + delta + (base - v) * 0.02;
                v = Math.max(weekBase.optDouble(k, v) - 3, Math.min(weekBase.optDouble(k, v) + 3, v));
                v = Math.max(monthBase.optDouble(k, v) - 6, Math.min(monthBase.optDouble(k, v) + 6, v));
                // 包络最后兜底，不能被周/月 cap 重新拉回极端
                v = Math.max(base - env, Math.min(base + env, v));
                d.put(k, Math.max(0, Math.min(100, v)));
            }
            o.put("dims", d);
            o.put("evidenceDays", evDays);
            o.put("days", days);
            o.put("evidence", new JSONObject());
            o.put("evidenceDay", day);
            o.put("lastSettleDay", evDay);
            o.put("lastChangeWhy", "按天结算：跨天重复出现的互动");
            appendHistory(o, day, d);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void rotateWeek(JSONObject o, String day, JSONObject d) {
        try {
            String s = o.optString("weekStart", "");
            if (s.isEmpty() || dayNum(day) - dayNum(s) >= 7) {
                o.put("weekStart", day);
                o.put("weekBase", copy(d));
            }
        } catch (Throwable ignored) {
        }
    }

    private static void rotateMonth(JSONObject o, String day, JSONObject d) {
        try {
            String s = o.optString("monthStart", "");
            if (s.isEmpty() || dayNum(day) - dayNum(s) >= 30) {
                o.put("monthStart", day);
                o.put("monthBase", copy(d));
            }
        } catch (Throwable ignored) {
        }
    }

    private static void appendHistory(JSONObject o, String day, JSONObject d) {
        try {
            JSONArray h = o.optJSONArray("history");
            if (h == null) h = new JSONArray();
            JSONObject snap = new JSONObject();
            snap.put("day", day);
            snap.put("dims", copy(d));
            h.put(snap);
            JSONArray keep = new JSONArray();
            for (int i = Math.max(0, h.length() - 31); i < h.length(); i++) keep.put(h.opt(i));
            o.put("history", keep);
        } catch (Throwable ignored) {
        }
    }

    /** 从用户的话里学称呼：以后叫我 XX。 */
    private static void learnAddress(JSONObject o, String t) {
        try {
            int at = t.indexOf("叫我");
            if (at < 0) return;
            String rest = t.substring(at + 2).trim();
            if (rest.isEmpty()) return;
            int end = rest.length();
            for (int k = 0; k < rest.length(); k++) {
                char ch = rest.charAt(k);
                if (ch == '，' || ch == '。' || ch == '！' || ch == '？' || ch == ',' || ch == ' ' || ch == '\n') {
                    end = k;
                    break;
                }
            }
            String name = rest.substring(0, Math.min(end, 8)).trim();
            if (name.length() >= 1 && name.length() <= 8) o.put("address", name);
        } catch (Throwable ignored) {
        }
    }

    /** 每 7 天用一次 API，从最近日记与对话里提炼性格现状和专属话题。 */
    public static synchronized void maybeReflect(Context c) {
        JSONObject o = load(c);
        if (o.optInt("days", 0) < MIN_DAYS) return;
        if (!Cfg.load(c).aiReady()) return;
        long last = o.optLong("lastReflect", 0);
        if (last > 0 && System.currentTimeMillis() - last < REFLECT_DAYS * 86400000L) return;
        String diary = Diary.recentPrompt(c, 3);
        if (diary.isEmpty()) return;
        JSONObject persona = Data.persona(c);
        JSONArray msgs = new JSONArray();
        try {
            JSONObject s = new JSONObject();
            s.put("role", "system");
            s.put("content", "你在分析一个桌面伙伴角色的长期性格演化，角色名字是「"
                    + persona.optString("name", "她") + "」。只根据下面给出的真实对话与日记，总结：\n"
                    + "1) 她现在的表达习惯（2～3 句，必须能对应到具体互动，不要写“她变了”）；\n"
                    + "2) 她和用户之间的专属话题或梗（最多 3 个，短词）。\n"
                    + "不要输出数字、性格类型、评分或心理诊断。只输出 JSON：{\"note\":\"...\",\"topics\":[\"...\"]}。");
            msgs.put(s);
            JSONObject u = new JSONObject();
            u.put("role", "user");
            u.put("content", diary + "\n" + recentChat(c));
            msgs.put(u);
        } catch (Throwable ignored) {
        }
        Ai.rawChat(c, msgs, 260, new Ai.Cb() {
            @Override
            public void ok(String text) {
                try {
                    int a = text.indexOf('{');
                    int b = text.lastIndexOf('}');
                    if (a < 0 || b <= a) return;
                    JSONObject r = new JSONObject(text.substring(a, b + 1));
                    String note = r.optString("note", "").trim();
                    if (note.length() > 120) note = note.substring(0, 120);
                    if (containsAny(note, FORBID)) return;
                    JSONObject o2 = load(c);
                    o2.put("note", note);
                    JSONArray topics = r.optJSONArray("topics");
                    JSONArray keep = new JSONArray();
                    if (topics != null) {
                        for (int i = 0; i < topics.length() && keep.length() < 3; i++) {
                            String t = topics.optString(i, "").trim();
                            if (!t.isEmpty() && t.length() <= 12) keep.put(t);
                        }
                    }
                    o2.put("topics", keep);
                    o2.put("lastReflect", System.currentTimeMillis());
                    Sg.writeObj(c, FILE, o2);
                    Bus.emit("traits", summary(c));
                } catch (Throwable ignored) {
                }
            }

            @Override
            public void fail(String err) {
            }
        });
    }

    private static String recentChat(Context c) {
        JSONArray hist = Data.chatList(c, 30);
        StringBuilder sb = new StringBuilder("最近的对话：\n");
        for (int k = 0; k < hist.length(); k++) {
            JSONObject h = hist.optJSONObject(k);
            if (h == null) continue;
            sb.append("user".equals(h.optString("role")) ? "他" : "她").append("：")
                    .append(h.optString("content", "")).append('\n');
        }
        return sb.toString();
    }

    public static void onHerReply(Context c, String reply) {
        if (reply == null || reply.isEmpty()) return;
        if (!Cfg.load(c).b("traitEvolve", true)) return;
        JSONObject o = load(c);
        try {
            boolean has = reply.matches("(?s).*[\\uD83C-\\uDBFF\\uDC00-\\uDFFF].*")
                    || reply.indexOf('♪') >= 0 || reply.indexOf('~') >= 0;
            double rate = o.optDouble("emojiRate", 0.3);
            rate = rate * 0.94 + (has ? 0.06 : 0);
            o.put("emojiRate", Math.max(0, Math.min(1, rate)));
            Sg.writeObj(c, FILE, o);
        } catch (Throwable ignored) {
        }
    }

    /** 写进系统提示的表达习惯：只给行为描述，不给数字和标签。 */
    public static String prompt(Context c) {
        JSONObject o = load(c);
        if (o.optInt("days", 0) < 3) return "";
        StringBuilder sb = new StringBuilder("和你相处下来，你形成了这些表达习惯：\n");
        String[] lines = descriptors(o);
        if (lines.length == 0) {
            sb.append("- 你还在慢慢熟悉他，保持自然就好。\n");
        } else {
            for (String s : lines) sb.append("- ").append(s).append('\n');
        }
        sb.append("这些是习惯，不要主动总结自己的性格，也不要说“我最近变活泼/变黏人了”。\n");
        String note = o.optString("note", "");
        if (!note.isEmpty()) sb.append("你最近的小观察：").append(note).append('\n');
        JSONArray topics = o.optJSONArray("topics");
        if (topics != null && topics.length() > 0) {
            sb.append("你们之间的专属话题：");
            for (int k = 0; k < topics.length(); k++) {
                if (k > 0) sb.append('、');
                sb.append(topics.optString(k));
            }
            sb.append('\n');
        }
        String address = o.optString("address", "");
        if (!address.isEmpty()) sb.append("他让你这样叫他：").append(address).append('\n');
        double rate = o.optDouble("emojiRate", 0.3);
        sb.append("你平时").append(rate > 0.5 ? "很爱" : rate > 0.2 ? "偶尔会" : "很少")
                .append("用表情和语气词。\n");
        return sb.toString();
    }

    private static String[] descriptors(JSONObject o) {
        JSONObject d = o.optJSONObject("dims");
        if (d == null) return new String[0];
        java.util.List<String> out = new java.util.ArrayList<>();
        if (v(d, "lively") >= 60) out.add("说话有精神，情绪外放一点");
        if (v(d, "reserved") >= 60) out.add("话不多，常把话收短");
        if (v(d, "humor") >= 60) out.add("容易接梗，偶尔逗他");
        if (v(d, "sharp") >= 60) out.add("嘴上偶尔损一句，但不伤人");
        if (v(d, "gentle") >= 60) out.add("先接情绪，再讲建议");
        if (v(d, "protective") >= 60) out.add("他累的时候会先护着他");
        if (v(d, "curious") >= 60) out.add("对他提过的小事会多问一句");
        if (v(d, "clingy") >= 60) out.add("会主动靠近，但不查岗");
        if (v(d, "boundary") >= 60) out.add("需要空间时会退一步");
        if (v(d, "night") >= 60) out.add("夜里更清醒，容易陪他晚一点");
        if (out.size() > 4) out = out.subList(0, 4);
        return out.toArray(new String[0]);
    }

    private static double v(JSONObject d, String k) {
        return d == null ? 50 : d.optDouble(k, 50);
    }

    public static JSONObject summary(Context c) {
        JSONObject out = new JSONObject();
        try {
            JSONObject o = load(c);
            JSONObject d = o.optJSONObject("dims");
            JSONArray dims = new JSONArray();
            for (int k = 0; k < DIM.length; k++) {
                JSONObject it = new JSONObject();
                it.put("key", DIM[k]);
                it.put("label", LABEL[k]);
                it.put("value", Math.round(d == null ? 50 : d.optDouble(DIM[k], 50)));
                dims.put(it);
            }
            JSONArray desc = new JSONArray();
            for (String s : descriptors(o)) desc.put(s);
            JSONArray trend = new JSONArray();
            JSONObject week = o.optJSONObject("weekBase");
            if (week != null && d != null) {
                for (int k = 0; k < DIM.length; k++) {
                    double delta = d.optDouble(DIM[k], 50) - week.optDouble(DIM[k], 50);
                    if (Math.abs(delta) < 1) continue;
                    JSONObject t = new JSONObject();
                    t.put("key", DIM[k]);
                    t.put("label", LABEL[k]);
                    t.put("delta", Math.round(delta));
                    trend.put(t);
                }
            }
            out.put("dims", dims);
            out.put("descriptors", desc);
            out.put("trend", trend);
            out.put("samples", o.optInt("samples", 0));
            out.put("days", o.optInt("days", 0));
            out.put("address", o.optString("address", ""));
            out.put("note", o.optString("note", ""));
            out.put("topics", o.optJSONArray("topics"));
            out.put("emojiRate", Math.round(o.optDouble("emojiRate", 0.3) * 100));
            out.put("evolved", o.optInt("days", 0) >= MIN_DAYS);
            out.put("lastReflect", o.optLong("lastReflect", 0));
            out.put("roleId", o.optString("roleId", "default"));
            out.put("speed", Cfg.load(c).i("traitSpeed", 1));
            out.put("lastChangeWhy", o.optString("lastChangeWhy", ""));
            out.put("lastSettleDay", o.optString("lastSettleDay", ""));
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static JSONObject defaultDims() {
        JSONObject d = new JSONObject();
        for (String k : DIM) {
            try {
                d.put(k, 50.0);
            } catch (Throwable ignored) {
            }
        }
        return d;
    }

    private static JSONObject copy(JSONObject o) {
        JSONObject out = new JSONObject();
        if (o == null) return out;
        java.util.Iterator<String> it = o.keys();
        while (it.hasNext()) {
            String k = it.next();
            try {
                out.put(k, o.opt(k));
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    private static String today() {
        return Sg.now().substring(0, 10);
    }

    private static long dayNum(String d) {
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            Date date = f.parse(d);
            return date == null ? 0 : date.getTime() / 86400000L;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 旧数据只归档，不删除；备份 ZIP 会一起带走。 */
    private static void archive(Context c, String prefix, JSONObject o) {
        try {
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
            Sg.writeObj(c, prefix + "_legacy_" + stamp + ".json", o);
        } catch (Throwable ignored) {
        }
    }

    private static boolean containsAny(String s, String[] arr) {
        if (s == null) return false;
        for (String k : arr) if (s.contains(k)) return true;
        return false;
    }
}
