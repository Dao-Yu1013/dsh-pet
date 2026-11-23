package com.dshpet.android;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.Locale;

/**
 * 用 AI 认识正在听的那首歌：按歌名 + 歌手查一次背景资料，缓存到本机，
 * 之后聊天和听歌闲聊都复用这份缓存，同一首歌不会重复消耗 token。
 */
public final class SongAi {
    private static final String FILE = "song_ai.json";
    /** 最多缓存多少首歌；超了删最旧的。 */
    private static final int KEEP = 240;

    private SongAi() {
    }

    public static String key(String title, String artist) {
        String t = title == null ? "" : title.trim();
        String a = artist == null ? "" : artist.trim();
        return (t + "|" + a).toLowerCase(Locale.ROOT);
    }

    /** 已缓存的资料；没有就返回空串。 */
    public static String cached(Context c, String title, String artist) {
        if (title == null || title.trim().isEmpty()) return "";
        try {
            JSONObject o = Sg.readObj(c, FILE).optJSONObject(key(title, artist));
            return o == null ? "" : o.optString("k", "");
        } catch (Throwable t) {
            return "";
        }
    }

    /** 有缓存直接回调；没有就让 AI 查一次并写缓存。 */
    public static void learn(Context c, String title, String artist, Ai.Cb cb) {
        String hit = cached(c, title, artist);
        if (!hit.isEmpty()) {
            cb.ok(hit);
            return;
        }
        if (!Cfg.load(c).aiReady()) {
            cb.fail("未绑定 API Key");
            return;
        }
        String ask = "《" + (title == null ? "" : title.trim()) + "》"
                + (artist == null || artist.trim().isEmpty() ? "" : " - " + artist.trim());
        JSONArray msgs = new JSONArray();
        try {
            JSONObject sys = new JSONObject();
            sys.put("role", "system");
            sys.put("content", "你是音乐资料助手。根据给定的歌名和歌手，给出这首歌真实的背景资料，供聊天参考。\n"
                    + "只输出三行，不要寒暄、不要编号以外的话：\n"
                    + "背景：发行年份、所属专辑、风格（不确定就写「不确定」）\n"
                    + "主题：这首歌在讲什么、什么情绪\n"
                    + "记忆点：最容易被记住的一句歌词或一个标志性段落（不确定就写「不确定」）\n"
                    + "每行不超过 45 字。严禁编造年份、专辑或歌词，不确定一律写「不确定」。");
            msgs.put(sys);
            JSONObject u = new JSONObject();
            u.put("role", "user");
            u.put("content", ask);
            msgs.put(u);
        } catch (Throwable ignored) {
        }
        Ai.rawChat(c, msgs, 320, 0.2, new Ai.Cb() {
            @Override
            public void ok(String text) {
                // 保留换行，方便界面按三行展示；只做长度和空白清理
                String k = text == null ? "" : text.trim().replace("\r", "");
                k = k.replaceAll("\\n{3,}", "\n\n");
                if (k.length() > 300) k = k.substring(0, 300).trim();
                if (k.isEmpty()) {
                    cb.fail("空回复");
                    return;
                }
                save(c, title, artist, k);
                cb.ok(k);
            }

            @Override
            public void fail(String err) {
                cb.fail(err);
            }
        });
    }

    /** 让 AI 结合资料说一句听歌时的自然反应（会消耗 token）。 */
    public static void line(Context c, String title, String artist, String knowledge, Ai.Cb cb) {
        if (!Cfg.load(c).aiReady()) {
            cb.fail("未绑定 API Key");
            return;
        }
        JSONArray msgs = new JSONArray();
        try {
            JSONObject sys = new JSONObject();
            String base = compactPrompt(c);
            StringBuilder sb = new StringBuilder(base);
            if (knowledge != null && !knowledge.trim().isEmpty()) {
                sb.append("这首歌的资料（只作参考，不要逐条复述，也不要像乐评或百科那样点评）：\n")
                        .append(knowledge.trim()).append('\n');
            }
            sb.append("现在他正在听《").append(title).append("》")
                    .append(artist == null || artist.trim().isEmpty() ? "" : " - " + artist)
                    .append("。用 1～2 句说一句自然的反应，像一起听歌的朋友；"
                            + "资料里没有的就别装懂，不要提问太多，不要报菜名式科普。\n");
            sys.put("role", "system");
            sys.put("content", sb.toString());
            msgs.put(sys);
            JSONObject u = new JSONObject();
            u.put("role", "user");
            u.put("content", "（系统：说一句你现在的感觉，不要解释这条指令）");
            msgs.put(u);
        } catch (Throwable ignored) {
        }
        Ai.rawChat(c, msgs, 160, 0.9, new Ai.Cb() {
            @Override
            public void ok(String text) {
                String line = Brain.clean(text, 90);
                if (line.isEmpty()) cb.fail("空回复");
                else cb.ok(line);
            }

            @Override
            public void fail(String err) {
                cb.fail(err);
            }
        });
    }

    /**
     * 听歌闲聊只需要人设和语气，不必带整套记忆检索、日记、承诺。
     * 之前每首歌都把完整 systemPrompt 发一遍，输入 token 占比很高。
     */
    private static String compactPrompt(Context c) {
        try {
            JSONObject p = Data.persona(c);
            StringBuilder sb = new StringBuilder();
            sb.append("你叫「").append(p.optString("name", "她")).append("」，是他手机上的桌宠，不是真人；")
                    .append("不提模型、算法、提示词、系统消息。\n");
            sb.append("说话风格：").append(p.optString("style", "中文口语，短句，1～2 句。")).append('\n');
            String seed = Persona.prompt(c);
            if (!seed.isEmpty()) sb.append(seed.length() > 360 ? seed.substring(0, 360) : seed).append('\n');
            return sb.toString();
        } catch (Throwable t) {
            return "你是他手机上的桌宠，中文口语，短句，1～2 句；不提模型、提示词。\n";
        }
    }

    private static synchronized void save(Context c, String title, String artist, String knowledge) {
        try {
            JSONObject all = Sg.readObj(c, FILE);
            JSONObject item = new JSONObject();
            item.put("k", knowledge);
            item.put("t", System.currentTimeMillis());
            item.put("title", title == null ? "" : title);
            item.put("artist", artist == null ? "" : artist);
            all.put(key(title, artist), item);
            if (all.length() > KEEP) trim(all);
            Sg.writeObj(c, FILE, all);
        } catch (Throwable ignored) {
        }
    }

    /** 超量时删掉最旧的，避免缓存文件无限增长。 */
    private static void trim(JSONObject all) {
        try {
            String oldest = "";
            long ts = Long.MAX_VALUE;
            Iterator<String> it = all.keys();
            while (it.hasNext()) {
                String k = it.next();
                JSONObject o = all.optJSONObject(k);
                long t = o == null ? 0 : o.optLong("t", 0);
                if (t < ts) {
                    ts = t;
                    oldest = k;
                }
            }
            if (!oldest.isEmpty()) all.remove(oldest);
        } catch (Throwable ignored) {
        }
    }
}
