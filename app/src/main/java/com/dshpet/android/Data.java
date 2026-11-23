package com.dshpet.android;

import android.content.Context;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** 人设、用户资料、聊天、记忆、动作台词、广告台词、提醒、统计。 */
public final class Data {
    private static final String DEFAULT_GREETING = "在呢，今天怎么样？";

    private Data() {
    }

    /* ---------------- 人设 ---------------- */
    /**
     * 身份骨架（去人设化）：只保留名字、说话风格、开场白、边界。
     * 性格由 Traits 涌现，背景由日记+记忆自然形成，不再手写 description/personality。
     */
    public static JSONObject defaultPersona() {
        JSONObject o = new JSONObject();
        try {
            o.put("name", "蓝色大肥鱼");
            o.put("roleId", "default");
            o.put("style", "中文口语，简洁自然，像熟人发消息；少用语气词，不总结不说教。");
            o.put("greeting", DEFAULT_GREETING);
            o.put("boundaries", "不编造现实中的事实，不知道就直说；涉及健康、金钱、法律时提醒谨慎；不说教。");
        } catch (Throwable ignored) {
        }
        return o;
    }

    /**
     * persona 文件的读改写锁。
     * PersonaAuto、性格重置、手动改名都会「读出来改一个字段再写回去」，
     * 各自只锁自己的方法不够：A 读到旧快照后 B 先写，A 再把旧快照写回，B 的改动就丢了。
     * 所有这类操作统一套这把锁。
     */
    private static final Object PERSONA_LOCK = new Object();

    public static Object personaLock() {
        return PERSONA_LOCK;
    }

    public static JSONObject persona(Context c) {
        synchronized (PERSONA_LOCK) {
            JSONObject o = Sg.readObj(c, "persona.json");
            if (!o.has("name")) o = defaultPersona();
            // 旧默认开场白过于客服化，只替换完全一致的旧默认，不动用户自定义
            if ("在呢在呢～找我干嘛呀？".equals(o.optString("greeting", ""))) {
                try {
                    o.put("greeting", DEFAULT_GREETING);
                    Sg.writeObj(c, "persona.json", o);
                } catch (Throwable ignored) {
                }
            }
            return o;
        }
    }

    /** 旧版默认人设自动升级：如果还是老版「蓝毛小女仆」或「蓝色大肥鱼」的完整人设，升级为去人设化的身份骨架。 */
    public static void upgradePersona(Context c) {
        try {
            // 已填写 v2 人格种子的用户不再按旧默认名重置，否则会抹掉新选的风格
            if (Contract.load(c).optInt("seedVersion", 0) >= 2) return;
            JSONObject o = Sg.readObj(c, "persona.json");
            String n = o.optString("name", "");
            String d = o.optString("description", "");
            boolean oldDefault = "蓝毛小女仆".equals(n) || n.isEmpty()
                    || ("蓝色大肥鱼".equals(n) && (d.isEmpty() || d.startsWith("蓝色大肥鱼")));
            if (oldDefault) {
                savePersona(c, defaultPersona());
            }
        } catch (Throwable ignored) {
        }
    }

    public static void savePersona(Context c, JSONObject o) {
        synchronized (PERSONA_LOCK) {
            Sg.writeObj(c, "persona.json", o);
        }
    }

    /* ---------------- 用户资料 ---------------- */
    public static JSONObject profile(Context c) {
        return Sg.readObj(c, "profile.json");
    }

    public static void saveProfile(Context c, JSONObject o) {
        Sg.writeObj(c, "profile.json", o);
    }

    /* ---------------- 聊天 ---------------- */
    public static void chatAppend(Context c, String role, String text) {
        chatAppend(c, role, text, "", "");
    }

    public static void chatAppend(Context c, String role, String text, String link, String linkText) {
        chatAppend(c, role, text, link, linkText, null);
    }

    /** extra 用于标记即兴台词等附加信息，不覆盖聊天主字段。 */
    public static void chatAppend(Context c, String role, String text, String link, String linkText,
                                  JSONObject extra) {
        if (TextUtils.isEmpty(text)) return;
        // 所有聊天都从这里落地，关系/性格/她自己的记忆也只在这里结算一次，避免漏算或重复计数
        if ("user".equals(role)) {
            noteFirstChat(c);
            Intimacy.onUser(c, text);
            Interact.onUser(c);
            Traits.observe(c, text);
            SelfMem.observeUser(c, text);
            // 应用感知：她记得你在这个应用里打过什么字（只留很短一段，可关闭、可清空）
            InApp.track(c, "输入", "发消息", text);
            // 通知桌宠服务：用户开始聊天，作废在途的主动生成并把下一次检查往后推
            try {
                Bus.emit("userTalk", new JSONObject());
            } catch (Throwable ignored) {
            }
        } else if ("assistant".equals(role)) {
            SelfMem.observeHer(c, text);
            Traits.onHerReply(c, text);
        }
        try {
            JSONObject o = new JSONObject();
            o.put("role", role);
            o.put("content", text);
            if (link != null && !link.isEmpty()) {
                o.put("link", link);
                o.put("linkText", linkText == null ? "" : linkText);
            }
            if (extra != null) {
                java.util.Iterator<String> it = extra.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    if ("role".equals(k) || "content".equals(k) || "ts".equals(k)
                            || "link".equals(k) || "linkText".equals(k)) continue;
                    o.put(k, extra.opt(k));
                }
            }
            o.put("ts", System.currentTimeMillis());
            Sg.appendLine(c, "chat.jsonl", o);
            bump(c, "chats", 1);
        } catch (Throwable ignored) {
        }
        if ("assistant".equals(role)) HerWords.observe(c, text, extra);
    }

    public static JSONArray chatList(Context c, int limit) {
        String s = Sg.readTail(c, "chat.jsonl", 512 * 1024);
        ArrayList<JSONObject> list = new ArrayList<>();
        for (String line : s.split("\n")) {
            if (line.trim().isEmpty()) continue;
            try {
                list.add(new JSONObject(line));
            } catch (Throwable ignored) {
            }
        }
        JSONArray a = new JSONArray();
        int start = Math.max(0, list.size() - limit);
        for (int k = start; k < list.size(); k++) a.put(list.get(k));
        ChatFiles.enrich(c, a);
        ChatVideos.enrich(c, a);
        LongDocs.enrich(c, a);
        return a;
    }

    /** Read one chronological page from the end of chat.jsonl. Cursor is an exclusive byte offset. */
    public static JSONObject chatPage(Context c, long cursor, int limit) {
        JSONObject out = new JSONObject();
        JSONArray messages = new JSONArray();
        int pageSize = Math.max(1, Math.min(120, limit));
        long nextCursor = 0;
        boolean hasMore = false;
        File file = Sg.file(c, "chat.jsonl");
        synchronized (Sg.WRITE_LOCK) {
            try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                long pos = cursor < 0 ? raf.length() : Math.min(cursor, raf.length());
                nextCursor = pos;
                ByteArrayOutputStream reversed = new ByteArrayOutputStream();
                ArrayList<JSONObject> newestFirst = new ArrayList<>();
                while (pos > 0) {
                    raf.seek(--pos);
                    int b = raf.read();
                    if (b == '\n') {
                        JSONObject item = parseReversedChatLine(reversed);
                        if (item != null) newestFirst.add(item);
                        if (newestFirst.size() > pageSize) {
                            hasMore = true;
                            nextCursor = pos;
                            break;
                        }
                    } else {
                        reversed.write(b);
                    }
                }
                if (!hasMore && reversed.size() > 0) {
                    JSONObject item = parseReversedChatLine(reversed);
                    if (item != null) newestFirst.add(item);
                }
                for (int i = Math.min(pageSize, newestFirst.size()) - 1; i >= 0; i--) {
                    messages.put(newestFirst.get(i));
                }
            } catch (Throwable ignored) {
            }
        }
        try {
            ChatFiles.enrich(c, messages);
            ChatVideos.enrich(c, messages);
            LongDocs.enrich(c, messages);
            out.put("messages", messages);
            out.put("cursor", nextCursor);
            out.put("hasMore", hasMore);
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static JSONObject parseReversedChatLine(ByteArrayOutputStream reversed) {
        if (reversed.size() == 0) return null;
        byte[] bytes = reversed.toByteArray();
        for (int i = 0, j = bytes.length - 1; i < j; i++, j--) {
            byte b = bytes[i]; bytes[i] = bytes[j]; bytes[j] = b;
        }
        reversed.reset();
        try {
            String line = new String(bytes, StandardCharsets.UTF_8).trim();
            return line.isEmpty() ? null : new JSONObject(line);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static int chatCount(Context c) {
        String s = Sg.read(Sg.file(c, "chat.jsonl"), "");
        if (s.isEmpty()) return 0;
        int n = 0;
        for (String line : s.split("\n")) if (!line.trim().isEmpty()) n++;
        return n;
    }

    /* ---------------- 聊天图片：保留一个月 ---------------- */
    /** 聊天里发的图片保留时长。 */
    public static final long CHAT_IMG_KEEP_MS = 30L * 86400000L;

    /** 把聊天里发的图片存进 S-G/chatimg/，返回相对路径（写进聊天记录）；失败返回空串。 */
    public static String saveChatImage(Context c, android.graphics.Bitmap bmp) {
        if (bmp == null || bmp.isRecycled()) return "";
        try {
            File dir = new File(Sg.dir(c), "chatimg");
            if (!dir.exists()) dir.mkdirs();
            String rel = "chatimg/ci_" + System.currentTimeMillis() + "_"
                    + (int) (Math.random() * 1000) + ".jpg";
            File f = new File(Sg.dir(c), rel);
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
                bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, out);
            }
            return f.isFile() && f.length() > 0 ? rel : "";
        } catch (Throwable t) {
            return "";
        }
    }

    /** 删掉超过一个月的聊天图片；聊天记录里的文字留着，前端按加载失败隐藏图片。 */
    public static void cleanChatImages(Context c) {
        try {
            File[] fs = new File(Sg.dir(c), "chatimg").listFiles();
            if (fs == null) return;
            long cut = System.currentTimeMillis() - CHAT_IMG_KEEP_MS;
            for (File f : fs) {
                if (f.isFile() && f.lastModified() < cut) f.delete();
            }
        } catch (Throwable ignored) {
        }
    }

    /* ---------------- 共同经历：首次聊天与区间统计 ---------------- */

    private static volatile boolean firstChatChecked;

    /** 第一条用户消息时记下首次聊天时间；旧数据从聊天文件第一行回填。 */
    private static synchronized void noteFirstChat(Context c) {
        if (firstChatChecked) return;
        try {
            JSONObject o = stats(c);
            if (o.optLong("firstChatAt", 0) > 0) {
                firstChatChecked = true;
                return;
            }
            long t = scanFirstUserChat(c);
            if (t <= 0) t = System.currentTimeMillis();
            o.put("firstChatAt", t);
            Sg.writeObj(c, "stats.json", o);
            firstChatChecked = true;
        } catch (Throwable ignored) {
        }
    }

    /** 首次聊天时间；没有聊天记录时返回 0。只在缺失时扫描一次全文件。 */
    public static long firstChatAt(Context c) {
        try {
            JSONObject o = stats(c);
            long t = o.optLong("firstChatAt", 0);
            if (t > 0) return t;
            t = scanFirstUserChat(c);
            if (t > 0) {
                o.put("firstChatAt", t);
                Sg.writeObj(c, "stats.json", o);
            }
            return t;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static long scanFirstUserChat(Context c) {
        try {
            String s = Sg.read(Sg.file(c, "chat.jsonl"), "");
            for (String line : s.split("\n")) {
                if (line.trim().isEmpty()) continue;
                JSONObject o = new JSONObject(line);
                if ("user".equals(o.optString("role", ""))) return o.optLong("ts", 0);
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    /**
     * 按时间段统计聊天：消息数、有聊天的天数、区间内首尾消息。
     * 只在打开/生成回顾时调用，不在每条消息里调用。
     */
    public static JSONObject chatDayStats(Context c, long from, long to) {
        JSONObject out = new JSONObject();
        java.util.Set<String> days = new java.util.HashSet<>();
        int messages = 0;
        int userMessages = 0;
        long first = 0;
        long last = 0;
        try {
            String s = Sg.read(Sg.file(c, "chat.jsonl"), "");
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
            for (String line : s.split("\n")) {
                if (line.trim().isEmpty()) continue;
                JSONObject o = new JSONObject(line);
                long ts = o.optLong("ts", 0);
                if (ts < from || (to > 0 && ts >= to)) continue;
                messages++;
                if ("user".equals(o.optString("role", ""))) userMessages++;
                if (first <= 0 || ts < first) first = ts;
                if (ts > last) last = ts;
                days.add(f.format(new java.util.Date(ts)));
            }
            out.put("messages", messages);
            out.put("userMessages", userMessages);
            out.put("days", days.size());
            out.put("firstTs", first);
            out.put("lastTs", last);
        } catch (Throwable ignored) {
        }
        return out;
    }

    public static void chatClear(Context c) {
        Sg.write(Sg.file(c, "chat.jsonl"), "");
    }

    /* ---------------- 记忆 ---------------- */
    public static JSONArray memories(Context c) {
        return Mem.normalize(c);
    }

    public static void addMemory(Context c, String text, String source) {
        Mem.add(c, text, null, null, source == null ? "manual" : source, 1.0);
    }

    public static void deleteMemory(Context c, String id) {
        Mem.delete(c, id);
    }

    public static String memoryBrief(Context c, int limit) {
        return Mem.brief(c, limit);
    }

    /* ---------------- 动作台词 ---------------- */
    public static JSONObject actionLines(Context c) {
        return Sg.readObj(c, "action_lines.json");
    }

    public static String actionLine(Context c, String slug) {
        return actionLines(c).optJSONObject(slug) == null ? "" : actionLines(c).optJSONObject(slug).optString("line", "");
    }

    public static boolean actionAI(Context c, String slug) {
        JSONObject o = actionLines(c).optJSONObject(slug);
        return o != null && o.optBoolean("ai", false);
    }

    public static void saveActionLine(Context c, String slug, String line, boolean ai) {
        JSONObject all = actionLines(c);
        try {
            JSONObject o = new JSONObject();
            o.put("line", line == null ? "" : line.trim());
            o.put("ai", ai);
            all.put(slug, o);
            Sg.writeObj(c, "action_lines.json", all);
        } catch (Throwable ignored) {
        }
    }

    /* ---------------- 广告台词 ---------------- */
    public static JSONArray adLines(Context c) {
        JSONArray a = Sg.readArr(c, "ad_lines.json");
        if (a.length() == 0) {
            a = new JSONArray();
            a.put("广告？没门～");
            a.put("手起刀落，广告退散。");
            a.put("又帮你省下十几秒，分我一半？");
            a.put("这种小事，交给我就好。");
            a.put("跳过成功，主人夸我一句嘛。");
            Sg.writeArr(c, "ad_lines.json", a);
        }
        return a;
    }

    public static void saveAdLines(Context c, JSONArray a) {
        Sg.writeArr(c, "ad_lines.json", a);
    }

    public static String randomAdLine(Context c) {
        JSONArray a = adLines(c);
        java.util.ArrayList<String> pool = new java.util.ArrayList<>();
        for (int k = 0; k < a.length(); k++) {
            String s = a.optString(k, "").trim();
            if (!s.isEmpty()) pool.add(s);
        }
        if (pool.isEmpty()) return "广告已经帮你跳过啦。";
        return pool.get((int) (Math.random() * pool.size()));
    }

    /* ---------------- 统计 ---------------- */
    public static synchronized JSONObject stats(Context c) {
        JSONObject o = Sg.readObj(c, "stats.json");
        boolean dirty = false;
        try {
            String day = o.optString("installDay", "");
            if (day.isEmpty()) {
                day = Cfg.load(c).str("installDay", Sg.now().substring(0, 10));
                o.put("installDay", day);
                dirty = true;
            }
            if (!o.has("interactions")) o.put("interactions", 0);
            if (!o.has("skips")) o.put("skips", 0);
            if (!o.has("chats")) o.put("chats", 0);
            if (!o.has("actions")) o.put("actions", 0);
            if (!o.has("affection")) o.put("affection", 0);
            if (!o.has("feed")) o.put("feed", 0);
            if (!o.has("lastFeed")) o.put("lastFeed", 0L);
            if (!o.has("lastActive")) o.put("lastActive", 0);
            if (dirty) Sg.writeObj(c, "stats.json", o);
        } catch (Throwable ignored) {
        }
        return o;
    }

    public static synchronized void bump(Context c, String key, int n) {
        JSONObject o = stats(c);
        try {
            o.put(key, o.optInt(key) + n);
            o.put("lastActive", System.currentTimeMillis());
            Sg.writeObj(c, "stats.json", o);
        } catch (Throwable ignored) {
        }
    }

    public static int affection(Context c) {
        return stats(c).optInt("affection", 0);
    }

    public static synchronized void addAffection(Context c, int n) {
        JSONObject o = stats(c);
        try {
            o.put("affection", Math.max(0, o.optInt("affection", 0) + n));
            o.put("lastActive", System.currentTimeMillis());
            Sg.writeObj(c, "stats.json", o);
        } catch (Throwable ignored) {
        }
    }

    public static synchronized void feed(Context c) {
        JSONObject o = stats(c);
        try {
            o.put("feed", o.optInt("feed", 0) + 1);
            o.put("lastFeed", System.currentTimeMillis());
            o.put("affection", Math.max(0, o.optInt("affection", 0) + 2));
            Sg.writeObj(c, "stats.json", o);
        } catch (Throwable ignored) {
        }
    }

    /** 0 不饿，100 很饿。 */
    public static int hunger(Context c) {
        long last = stats(c).optLong("lastFeed", 0);
        if (last <= 0) return 35;
        double hours = (System.currentTimeMillis() - last) / 3600000.0;
        return (int) Math.min(100, Math.max(0, hours * 9));
    }

    public static String moodText(Context c) {
        int hunger = hunger(c);
        int af = affection(c);
        if (hunger >= 78) return "有点饿";
        if (af >= 120) return "离不开你";
        if (af >= 60) return "很黏人";
        if (af >= 20) return "亲近";
        if (af >= 5) return "好奇";
        return "还在观察你";
    }

    public static JSONObject mood(Context c) {
        JSONObject o = new JSONObject();
        try {
            o.put("affection", affection(c));
            o.put("hunger", hunger(c));
            o.put("mood", moodText(c));
            o.put("feed", stats(c).optInt("feed", 0));
        } catch (Throwable ignored) {
        }
        return o;
    }

    public static synchronized int days(Context c) {
        String day = stats(c).optString("installDay", Sg.now().substring(0, 10));
        String cfgDay = Cfg.load(c).str("installDay", "");
        if (!cfgDay.isEmpty() && cfgDay.compareTo(day) < 0) {
            day = cfgDay;
            JSONObject o = stats(c);
            try {
                o.put("installDay", day);
                Sg.writeObj(c, "stats.json", o);
            } catch (Throwable ignored) {
            }
        }
        try {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
            long a = f.parse(day).getTime();
            long b = f.parse(Sg.now().substring(0, 10)).getTime();
            return (int) (Math.max(0, b - a) / 86400000L) + 1;
        } catch (Throwable t) {
            return 1;
        }
    }

    /* ---------------- 提醒 ---------------- */
    public static JSONArray reminders(Context c) {
        return Sg.readArr(c, "reminders.json");
    }

    public static void addReminder(Context c, String type, String title, String detail, String key) {
        addReminder(c, type, title, detail, key, null);
    }

    public static void addParcel(Context c, String title, String detail, String key,
                                 String carrier, String item, String code, String stage) {
        try {
            JSONObject extra = new JSONObject();
            extra.put("carrier", carrier == null ? "" : carrier);
            extra.put("item", item == null ? "" : item);
            extra.put("code", code == null ? "" : code);
            extra.put("stage", stage == null ? "" : stage);
            addReminder(c, "parcel", title, detail, key, extra);
        } catch (Throwable ignored) {
            addReminder(c, "parcel", title, detail, key);
        }
    }

    public static synchronized void addReminder(Context c, String type, String title, String detail,
                                                String key, JSONObject extra) {
        JSONArray a = reminders(c);
        long now = System.currentTimeMillis();
        if (key != null && !key.isEmpty()) {
            for (int k = 0; k < a.length(); k++) {
                JSONObject o = a.optJSONObject(k);
                if (o != null && key.equals(o.optString("key"))) {
                    try {
                        o.put("ts", now);
                        o.put("title", title);
                        o.put("detail", detail);
                        o.put("fired", false);
                        merge(o, extra);
                        Sg.writeArr(c, "reminders.json", a);
                    } catch (Throwable ignored) {
                    }
                    return;
                }
            }
        }
        try {
            JSONObject o = new JSONObject();
            o.put("id", "r" + now + "_" + (int) (Math.random() * 999));
            o.put("type", type);
            o.put("title", title);
            o.put("detail", detail == null ? "" : detail);
            o.put("key", key == null ? "" : key);
            o.put("ts", now);
            o.put("fired", false);
            merge(o, extra);
            a.put(o);
            if (a.length() > 120) {
                JSONArray b = new JSONArray();
                for (int k = a.length() - 120; k < a.length(); k++) b.put(a.get(k));
                a = b;
            }
            Sg.writeArr(c, "reminders.json", a);
        } catch (Throwable ignored) {
        }
    }

    /** 到点提醒已真正送达后标记，避免每分钟重复提醒。 */
    public static synchronized boolean markReminderFired(Context c, String id) {
        if (id == null || id.isEmpty()) return false;
        JSONArray a = reminders(c);
        boolean changed = false;
        for (int k = 0; k < a.length(); k++) {
            JSONObject o = a.optJSONObject(k);
            if (o == null || !id.equals(o.optString("id", ""))) continue;
            try {
                o.put("fired", true);
                o.put("firedAt", System.currentTimeMillis());
                changed = true;
            } catch (Throwable ignored) {
            }
        }
        if (changed) Sg.writeArr(c, "reminders.json", a);
        return changed;
    }

    private static void merge(JSONObject o, JSONObject extra) {
        if (o == null || extra == null) return;
        java.util.Iterator<String> it = extra.keys();
        while (it.hasNext()) {
            String k = it.next();
            try {
                o.put(k, extra.opt(k));
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 快递签收/取件成功：按取件码、物品名、快递公司匹配并自动完成对应提醒。
     * 返回被完成的提醒，便于桌宠播报“哪个快递完成了”。
     */
    public static synchronized JSONArray completeParcels(Context c, String carrier, String item, String code) {
        JSONArray out = new JSONArray();
        JSONArray a = reminders(c);
        if (a.length() == 0) return out;
        int best = -1;
        int bestScore = 0;
        int oldest = -1;
        for (int k = 0; k < a.length(); k++) {
            JSONObject o = a.optJSONObject(k);
            if (o == null || !"parcel".equals(o.optString("type"))) continue;
            if (oldest < 0) oldest = k;
            String blob = o.optString("title", "") + " " + o.optString("detail", "") + " "
                    + o.optString("carrier", "") + " " + o.optString("item", "") + " " + o.optString("code", "");
            int score = 0;
            if (code != null && !code.isEmpty() && blob.contains(code)) score += 8;
            if (item != null && !item.isEmpty() && blob.contains(item)) score += 5;
            if (carrier != null && !carrier.isEmpty() && blob.contains(carrier)) score += 3;
            if (score > bestScore) {
                bestScore = score;
                best = k;
            }
        }
        boolean coded = (code != null && !code.isEmpty()) || (item != null && !item.isEmpty());
        boolean anyClue = coded || (carrier != null && !carrier.isEmpty());
        if (best < 0 || (bestScore == 0 && anyClue)) {
            // 有线索但没匹配上，不能误删其他快递；完全无线索时才退化为完成最早的一条
            if (anyClue) return out;
            best = oldest;
        }
        if (best < 0) return out;
        JSONObject hit = a.optJSONObject(best);
        if (hit != null) out.put(hit);
        JSONArray keep = new JSONArray();
        for (int k = 0; k < a.length(); k++) {
            if (k == best) continue;
            JSONObject o = a.optJSONObject(k);
            if (o != null) keep.put(o);
        }
        Sg.writeArr(c, "reminders.json", keep);
        return out;
    }

    public static void removeReminder(Context c, String id) {
        JSONArray a = reminders(c);
        JSONArray b = new JSONArray();
        for (int k = 0; k < a.length(); k++) {
            JSONObject o = a.optJSONObject(k);
            if (o != null && !id.equals(o.optString("id"))) b.put(o);
        }
        Sg.writeArr(c, "reminders.json", b);
    }

    public static void removeReminderByKey(Context c, String key) {
        JSONArray a = reminders(c);
        JSONArray b = new JSONArray();
        for (int k = 0; k < a.length(); k++) {
            JSONObject o = a.optJSONObject(k);
            if (o != null && !key.equals(o.optString("key"))) b.put(o);
        }
        Sg.writeArr(c, "reminders.json", b);
    }

    public static int pendingCount(Context c) {
        return reminders(c).length();
    }

    public static String remindersBrief(Context c, int limit) {
        return remindersBrief(c, limit, false);
    }

    public static String remindersBrief(Context c, int limit, boolean actionableOnly) {
        JSONArray a = reminders(c);
        StringBuilder sb = new StringBuilder();
        int start = Math.max(0, a.length() - limit);
        for (int k = start; k < a.length(); k++) {
            JSONObject o = a.optJSONObject(k);
            if (o == null) continue;
            if (actionableOnly && isShippingReminder(o)) continue;
            String tag = "";
            if ("parcel".equals(o.optString("type"))) {
                tag = isShippingReminder(o) ? "【运输中】" : "【待取件】";
            }
            sb.append("- ").append(tag).append(o.optString("title"))
                    .append("：").append(o.optString("detail")).append('\n');
        }
        return sb.toString();
    }

    /** 发货、运输、派送中的快递不是待取件，不能让桌宠催取。 */
    public static boolean isShippingReminder(JSONObject o) {
        if (o == null || !"parcel".equals(o.optString("type"))) return false;
        String stage = o.optString("stage", "");
        if ("shipped".equals(stage)) return true;
        if ("pickup".equals(stage)) return false;
        String blob = o.optString("title", "") + " " + o.optString("detail", "");
        if (containsAny(blob, new String[]{"发货", "已发出", "运输", "已揽收"})) return true;
        return containsAny(blob, new String[]{"派送", "派件"})
                && !containsAny(blob, new String[]{"已到", "待取", "请取", "可取", "到了", "驿站", "快递柜"});
    }

    private static boolean containsAny(String s, String[] arr) {
        if (s == null || s.isEmpty()) return false;
        for (String k : arr) if (s.contains(k)) return true;
        return false;
    }

    public static String dropOldReminders(Context c, long ageDays) {
        JSONArray a = reminders(c);
        JSONArray b = new JSONArray();
        long min = System.currentTimeMillis() - ageDays * 86400000L;
        for (int k = 0; k < a.length(); k++) {
            JSONObject o = a.optJSONObject(k);
            if (o != null && o.optLong("ts") >= min) b.put(o);
        }
        Sg.writeArr(c, "reminders.json", b);
        return "";
    }

    /**
     * 新版只保留「日历日程」和「我手动加的提醒」，
     * 清掉旧版由快递/外卖/消息通知生成的待办。
     */
    public static int keepOnlyOwn(Context c) {
        JSONArray a = reminders(c);
        JSONArray keep = new JSONArray();
        int removed = 0;
        for (int k = 0; k < a.length(); k++) {
            JSONObject o = a.optJSONObject(k);
            if (o == null) continue;
            String key = o.optString("key", "");
            String type = o.optString("type", "");
            boolean mine = key.startsWith("cal:") || key.startsWith("manual:") || key.startsWith("nlu:")
                    || "plan".equals(type) || "manual".equals(type);
            if (mine) keep.put(o);
            else removed++;
        }
        if (removed > 0) Sg.writeArr(c, "reminders.json", keep);
        return removed;
    }
}
