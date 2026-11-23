package com.dshpet.android;

import android.content.Context;
import android.content.Intent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

import java.util.Locale;

/** Chat flow for choosing a title from the built-in local bookshelf. */
final class ReaderFlow {
    private static final String STATE = "reader_chat_choice.json";
    private static final long STATE_TTL = 15 * 60 * 1000L;

    private ReaderFlow() { }

    static boolean hasPending(Context c) {
        JSONObject p = Sg.readObj(c, STATE);
        return System.currentTimeMillis() - p.optLong("ts", 0) < STATE_TTL
                && ("consent".equals(p.optString("phase")) || p.optJSONArray("books") != null);
    }

    /** Remember only an actual proactive invitation; agreement leads to a shelf menu, never an arbitrary book. */
    static void captureAsk(Context c, String utterance) {
        if (c == null || utterance == null) return;
        String s = utterance.replaceAll("\\s+", "");
        if (!(s.contains("读书") || s.contains("看书") || s.contains("阅读")
                || s.contains("读本") || s.contains("小说"))) return;
        if (!(s.contains("吗") || s.contains("愿意不愿意") || s.contains("愿不愿意"))) return;
        JSONArray shelf = Books.list(c).optJSONArray("visible");
        if (shelf == null || shelf.length() == 0) return;
        JSONObject p = new JSONObject();
        try {
            p.put("ts", System.currentTimeMillis());
            p.put("phase", "consent");
            Sg.writeObj(c, STATE, p);
            ExperienceLog.record(c, "wanted", "内置书架", "她主动提出想一起阅读");
            ExperienceLog.record(c, "asked", "内置书架", "正在等待用户同意后列出书目");
        } catch (Throwable ignored) { }
    }

    static boolean handle(Context c, String text, Ai.Cb cb) {
        if (c == null || text == null || cb == null) return false;
        String s = text.trim();
        JSONObject pending = Sg.readObj(c, STATE);
        JSONArray choices = pending.optJSONArray("books");
        boolean fresh = System.currentTimeMillis() - pending.optLong("ts", 0) < STATE_TTL;
        if (fresh && "consent".equals(pending.optString("phase"))) {
            if (isCancel(s)) {
                clear(c);
                reply(c, s, "好，那先不读。", cb);
                return true;
            }
            if (isYes(s)) {
                ExperienceLog.record(c, "consented", "内置书架", "用户同意一起阅读");
                return showMenu(c, s, cb);
            }
            clear(c);
            return false;
        }
        boolean waiting = choices != null && fresh;

        if (waiting && isCancel(s)) {
            clear(c);
            reply(c, s, "好，那先不读。", cb);
            return true;
        }
        if (isStart(s)) {
            ExperienceLog.record(c, "wanted", "内置书架", "用户明确提出想读书");
            return showMenu(c, s, cb);
        }
        if (!waiting) return false;

        JSONObject selected = select(choices, s);
        if (selected == null) {
            if (!looksLikeChoice(s)) return false;
            reply(c, s, "我没在刚才列出的书里找到这个名字。请说书名、序号，或说‘取消’。", cb);
            return true;
        }
        String id = selected.optString("id", "");
        String title = selected.optString("title", "这本书");
        clear(c);
        boolean launched = openReader(c, id);
            ExperienceLog.record(c, launched ? "started" : "failed", "《" + title + "》",
                    launched ? "用户选书后已请求打开内置阅读页" : "打开内置阅读页失败");
        String answer = launched
                ? "好，我打开《" + title + "》了。我会从你上次看到的位置读起，读到正文后把要点记下来。"
                : "我选好了《" + title + "》，但系统没能切到阅读页。请从「阅读 → 书架」打开它。";
        if (launched) {
            Context app = c.getApplicationContext();
            new Thread(() -> readCurrentChapter(app, id, title), "dsh-ai-book-read").start();
        }
        reply(c, s, answer, cb);
        return true;
    }

    /** Pass the actual local chapter text to the configured model; never infer from the book title alone. */
    private static void readCurrentChapter(Context c, String id, String title) {
        try {
            JSONObject book = Books.get(c, id);
            JSONArray chapters = book.optJSONArray("chapters");
            if (!book.optBoolean("ok", false) || chapters == null || chapters.length() == 0) {
                recordReadFailure(c, title, "书架中没有可提取的正文文本（PDF 页面或受保护内容暂不能作为文字阅读）");
                return;
            }
            JSONObject progress = book.optJSONObject("progress");
            int chapter = Math.max(0, Math.min(chapters.length() - 1, progress == null ? 0 : progress.optInt("chapter", 0)));
            JSONObject current = chapters.optJSONObject(chapter);
            if (current == null) {
                recordReadFailure(c, title, "当前章节没有正文文本，尚未记作读过");
                return;
            }
            String body = current.optString("text", "").trim();
            String chapterTitle = current.optString("title", "第 " + (chapter + 1) + " 章");
            if (body.length() < 80) {
                recordReadFailure(c, title, "《" + chapterTitle + "》正文太短或无法提取，尚未记作读过");
                return;
            }
            if (body.length() > 7000) body = body.substring(0, 7000);
            JSONArray messages = new JSONArray();
            messages.put(new JSONObject().put("role", "system").put("content",
                    "你在陪用户读一本书。下面是书架本地正文，不是书名简介。请实际读完所给片段，提炼一个准确的核心意思和一个具体细节。"
                            + "不能补写片段以外的情节，不能说读完整本书。只返回一句自然中文，不超过100字。"));
            messages.put(new JSONObject().put("role", "user").put("content",
                    "书名：《" + title + "》\n章节：" + chapterTitle + "\n\n正文：\n" + body));
            final int chapterIndex = chapter;
            final String chapterName = chapterTitle;
            Ai.rawChat(c, messages, 180, 0.25, new Ai.Cb() {
                @Override public void ok(String text) {
                    String summary = text == null ? "" : text.trim().replaceAll("^[\"“]|[\"”]$", "");
                    if (summary.isEmpty() || summary.length() > 180 || summary.contains("我无法")
                            || summary.contains("无法阅读") || summary.contains("看不到正文")) {
                        recordReadFailure(c, title, "正文已取到，但阅读总结未成功；尚未保存为读书记忆");
                        return;
                    }
                    String evidence = "依据书架实际正文阅读《" + title + "》·" + chapterName + "：" + summary;
                    ExperienceLog.record(c, "observed", "《" + title + "》·" + chapterName, evidence);
                    InterestProfile.learned(c, "读书：《" + title + "》", evidence);
                    Books.addNote(c, id, chapterIndex, "note", "", "AI伴读：" + summary, 0);
                    remember(c, id, title, chapterIndex, chapterName, summary);
                    Data.chatAppend(c, "assistant", "我刚读了《" + title + "》的「" + chapterName + "」：" + summary);
                }
                @Override public void fail(String error) {
                    recordReadFailure(c, title, "正文已读取，但模型总结失败，尚未保存读书记忆");
                }
            });
        } catch (Throwable ignored) {
            recordReadFailure(c, title, "读取本地正文时发生错误，尚未记作读过");
        }
    }

    private static void recordReadFailure(Context c, String title, String reason) {
        ExperienceLog.record(c, "failed", "《" + title + "》", reason);
    }

    private static void remember(Context c, String id, String title, int chapter, String chapterTitle, String summary) {
        try {
            JSONArray old = Sg.readArr(c, "reader_ai_memory.json");
            JSONArray next = new JSONArray();
            for (int i = Math.max(0, old.length() - 99); i < old.length(); i++) {
                JSONObject item = old.optJSONObject(i);
                if (item != null && !(id.equals(item.optString("bookId")) && chapter == item.optInt("chapter", -1))) next.put(item);
            }
            next.put(new JSONObject().put("bookId", id).put("title", title).put("chapter", chapter)
                    .put("chapterTitle", chapterTitle).put("summary", summary).put("ts", System.currentTimeMillis()));
            Sg.writeArr(c, "reader_ai_memory.json", next);
        } catch (Throwable ignored) { }
    }

    /** Retrieve only memories of books that are relevant to the current question. */
    static String memoryPrompt(Context c, String query) {
        if (c == null || query == null || query.trim().isEmpty()) return "";
        try {
            JSONArray all = Sg.readArr(c, "reader_ai_memory.json");
            String q = query.toLowerCase(Locale.ROOT);
            StringBuilder out = new StringBuilder();
            int count = 0;
            for (int i = all.length() - 1; i >= 0 && count < 3; i--) {
                JSONObject m = all.optJSONObject(i);
                if (m == null) continue;
                String title = m.optString("title", "");
                if (!q.contains(title.toLowerCase(Locale.ROOT)) && !hasAny(q, "读书", "看书", "章节", "书里", "读过什么", "读到哪", "阅读记忆")) continue;
                if (count++ == 0) out.append("你确实根据书架正文读过这些片段（不是整本书）；可据此和用户聊，没读到的章节不要编：\n");
                out.append("- 《").append(title).append("》·").append(m.optString("chapterTitle", ""))
                        .append("：").append(m.optString("summary", "")).append('\n');
            }
            return out.toString();
        } catch (Throwable ignored) { return ""; }
    }

    private static boolean hasAny(String s, String... values) {
        for (String value : values) if (s.contains(value)) return true;
        return false;
    }

    private static boolean showMenu(Context c, String user, Ai.Cb cb) {
        JSONArray books = Books.list(c).optJSONArray("visible");
        if (books == null || books.length() == 0) {
            clear(c);
            reply(c, user, "我看了内置书架，现在还没有可读的书。先到「阅读 → 书架」导入一本，我再帮你打开。", cb);
            return true;
        }
        JSONArray list = new JSONArray();
        StringBuilder menu = new StringBuilder("书架里有这些书，你想读哪一本？\n");
        int shown = Math.min(books.length(), 12);
        for (int i = 0; i < shown; i++) {
            JSONObject b = books.optJSONObject(i);
            if (b == null) continue;
            try { list.put(new JSONObject().put("id", b.optString("id", "")).put("title", b.optString("title", "未命名"))); }
            catch (Throwable ignored) { }
            menu.append(i + 1).append(". 《").append(b.optString("title", "未命名")).append("》");
            String author = b.optString("author", "").trim();
            if (!author.isEmpty()) menu.append(" — ").append(author);
            menu.append('\n');
        }
        if (books.length() > shown) menu.append("还有 ").append(books.length() - shown).append(" 本；你也可以直接说书名。\n");
        menu.append("告诉我序号或书名，我就打开它。需要取消就说‘先不读了’。");
        JSONObject state = new JSONObject();
        try {
            state.put("ts", System.currentTimeMillis());
            state.put("phase", "choice");
            state.put("books", list);
            Sg.writeObj(c, STATE, state);
        } catch (Throwable ignored) { }
        ExperienceLog.record(c, "asked", "内置书架", "已列出可读书目，等待用户选择");
        reply(c, user, menu.toString().trim(), cb);
        return true;
    }

    private static boolean isYes(String s) {
        String n = s.trim().replaceAll("[，。！？!?、]+$", "");
        return "好".equals(n) || "行".equals(n) || "可以".equals(n) || "好啊".equals(n)
                || "好呀".equals(n) || "愿意".equals(n) || "一起读".equals(n)
                || n.startsWith("可以，") || n.startsWith("好啊，") || n.startsWith("好呀，");
    }

    private static JSONObject select(JSONArray books, String text) {
        String normalized = text.toLowerCase(Locale.ROOT).replaceAll("[《》“”\"'，。！？!?、\\s]", "");
        int n = ordinal(normalized);
        if (n > 0 && n <= books.length()) return books.optJSONObject(n - 1);
        for (int i = 0; i < books.length(); i++) {
            JSONObject b = books.optJSONObject(i);
            if (b == null) continue;
            String title = b.optString("title", "").toLowerCase(Locale.ROOT);
            if (!title.isEmpty() && normalized.contains(title.replaceAll("[\\s]", ""))) return b;
        }
        return null;
    }

    private static int ordinal(String s) {
        String digits = s.replaceFirst("^(我想读|我要读|我选|选|读|看|打开)?", "")
                .replaceFirst("^(第)?", "").replaceFirst("(本|个|部)?$", "");
        try { return Integer.parseInt(digits); } catch (Throwable ignored) { }
        String[] cn = {"一", "二", "三", "四", "五", "六", "七", "八", "九", "十"};
        for (int i = 0; i < cn.length; i++) if (digits.equals(cn[i])) return i + 1;
        return 0;
    }

    private static boolean isStart(String s) {
        String n = s.replaceAll("\\s+", "");
        return n.contains("去读书") || n.contains("去看书") || n.contains("陪我读")
                || n.contains("陪我看书") || n.contains("打开阅读") || n.contains("读一本书")
                || n.contains("看一本书") || n.contains("去看小说") || n.contains("去读小说")
                || n.contains("开始读书") || n.contains("开始阅读") || n.contains("想读书")
                || n.contains("想看书") || n.equals("读小说") || n.equals("看小说")
                || n.equals("读书") || n.equals("看书");
    }

    private static boolean looksLikeChoice(String s) {
        String n = s.trim();
        return n.matches("第?[0-9一二三四五六七八九十]+(本|个|部)?")
                || n.startsWith("选") || n.startsWith("读") || n.startsWith("打开")
                || n.startsWith("看") || n.contains("《");
    }

    private static boolean isCancel(String s) {
        return s.contains("取消") || s.contains("先不读") || s.contains("不读了") || s.contains("算了");
    }

    private static void clear(Context c) {
        try { Sg.writeObj(c, STATE, new JSONObject()); } catch (Throwable ignored) { }
    }

    private static boolean openReader(Context c, String id) {
        if (id == null || id.trim().isEmpty()) return false;
        try {
            Intent i = new Intent(c, MainAct.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra("page", "readerShelf")
                    .putExtra("readerBookId", id);
            c.startActivity(i);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void reply(Context c, String user, String answer, Ai.Cb cb) {
        Data.chatAppend(c, "user", user);
        Data.chatAppend(c, "assistant", answer);
        cb.ok(answer);
    }
}
