package com.dshpet.android;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.regex.Pattern;

/** 剪贴板历史、分享给桌宠、敏感信息脱敏。 */
public final class Clip {
    private static final int MAX = 100;
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern IDCARD = Pattern.compile("(?<!\\d)\\d{17}[\\dXx](?!\\d)");
    private static final Pattern CARD = Pattern.compile("(?<!\\d)\\d{16,19}(?!\\d)");
    private static final Pattern KEY = Pattern.compile("sk-[A-Za-z0-9_-]{8,}");

    private Clip() {
    }

    public static String mask(String text) {
        if (text == null) return "";
        String s = text;
        try {
            s = replace(PHONE, s, 3, 7);
            s = replace(IDCARD, s, 6, 14);
            s = cardMask(s);
            s = KEY.matcher(s).replaceAll("sk-****");
        } catch (Throwable ignored) {
        }
        return s;
    }

    /** 保留前 keep 位与后 tailFrom 之后的内容。 */
    private static String replace(Pattern p, String src, int keep, int tailFrom) {
        java.util.regex.Matcher m = p.matcher(src);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String g = m.group();
            String rep = g.substring(0, Math.min(keep, g.length())) + "****"
                    + g.substring(Math.min(tailFrom, g.length()));
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String cardMask(String src) {
        java.util.regex.Matcher m = CARD.matcher(src);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String g = m.group();
            String rep = g.substring(0, 4) + " **** **** " + g.substring(g.length() - 4);
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    public static JSONObject add(Context c, String text, String kind, String path, String source) {
        JSONObject out = new JSONObject();
        try {
            text = text == null ? "" : text.trim();
            if (text.isEmpty() && (path == null || path.isEmpty())) return out;
            boolean mask = Cfg.load(c).b("clipMask", true);
            JSONArray a = list(c);
            JSONObject o = new JSONObject();
            o.put("id", "c" + System.currentTimeMillis() + "_" + (int) (Math.random() * 999));
            o.put("kind", kind == null ? "text" : kind);
            o.put("text", mask ? mask(text) : text);
            o.put("path", path == null ? "" : path);
            o.put("source", source == null ? "manual" : source);
            o.put("ts", System.currentTimeMillis());
            o.put("masked", mask && !mask(text).equals(text));
            JSONArray b = new JSONArray();
            b.put(o);
            for (int k = 0; k < a.length() && b.length() < MAX; k++) {
                JSONObject old = a.optJSONObject(k);
                if (old == null) continue;
                if (!mask && text.equals(old.optString("text"))) continue;
                b.put(old);
            }
            Sg.writeArr(c, "clips.json", b);
            out = o;
            Bus.emit("clip", o);
        } catch (Throwable ignored) {
        }
        return out;
    }

    public static JSONObject capture(Context c) {
        try {
            ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) return new JSONObject();
            ClipData clip = cm.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return new JSONObject();
            CharSequence t = clip.getItemAt(0).coerceToText(c);
            if (t == null || t.toString().trim().isEmpty()) return new JSONObject();
            return add(c, t.toString(), "text", "", "clipboard");
        } catch (Throwable t) {
            return new JSONObject();
        }
    }

    public static JSONObject fromIntent(Context c, Intent i) {
        JSONObject out = new JSONObject();
        if (i == null) return out;
        try {
            String action = i.getAction();
            if (Intent.ACTION_SEND.equals(action)) {
                String type = i.getType() == null ? "" : i.getType();
                CharSequence text = i.getCharSequenceExtra(Intent.EXTRA_TEXT);
                String title = i.getStringExtra(Intent.EXTRA_SUBJECT);
                if (type.startsWith("image/") && i.getParcelableExtra(Intent.EXTRA_STREAM) instanceof Uri) {
                    Uri uri = i.getParcelableExtra(Intent.EXTRA_STREAM);
                    String path = copyImage(c, uri);
                    out = add(c, title == null ? "分享的图片" : title, "image", path, "share");
                } else if (text != null) {
                    String all = text.toString();
                    if (title != null && !title.isEmpty()) all = title + "\n" + all;
                    out = add(c, all, "text", "", "share");
                }
            } else if (Intent.ACTION_PROCESS_TEXT.equals(action)) {
                CharSequence text = i.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT);
                if (text != null) out = add(c, text.toString(), "text", "", "process");
            }
            if (out.length() > 0) Bus.emit("share", out);
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static String copyImage(Context c, Uri uri) {
        try {
            File dir = new File(Sg.dir(c), "clips");
            if (!dir.exists()) //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
            File f = new File(dir, "clip_" + System.currentTimeMillis() + ".img");
            try (InputStream in = c.getContentResolver().openInputStream(uri);
                 FileOutputStream out = new FileOutputStream(f)) {
                byte[] b = new byte[8192];
                int n;
                while (in != null && (n = in.read(b)) > 0) out.write(b, 0, n);
            }
            return f.getAbsolutePath();
        } catch (Throwable t) {
            return "";
        }
    }

    public static JSONArray list(Context c) {
        return Sg.readArr(c, "clips.json");
    }

    public static void delete(Context c, String id) {
        JSONArray a = list(c);
        JSONArray b = new JSONArray();
        for (int k = 0; k < a.length(); k++) {
            JSONObject o = a.optJSONObject(k);
            if (o != null && !id.equals(o.optString("id"))) b.put(o);
        }
        Sg.writeArr(c, "clips.json", b);
    }

    public static void clear(Context c) {
        Sg.writeArr(c, "clips.json", new JSONArray());
    }

    public static void saveNote(Context c, String text) {
        try {
            File dir = new File(Sg.dir(c), "clips");
            if (!dir.exists()) //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
            File f = new File(dir, "note_" + System.currentTimeMillis() + ".txt");
            Sg.write(f, text == null ? "" : text);
            add(c, text, "note", f.getAbsolutePath(), "save");
        } catch (Throwable ignored) {
        }
    }

    /** action: summary / translate / explain。 */
    public static void act(Context c, String text, String action, Ai.Cb cb) {
        if (text == null || text.trim().isEmpty()) {
            cb.fail("没有内容");
            return;
        }
        String sys;
        if ("translate".equals(action)) {
            sys = "你是翻译。若原文主要是中文，译成自然英文；否则译成自然中文。只输出译文，保留原格式，不要解释。";
        } else if ("explain".equals(action)) {
            sys = "你是耐心的解释者。用 3～5 句中文解释这段内容的含义、关键点和可能需要注意的地方。";
        } else {
            sys = "你是信息整理助手。用中文给出 3 条以内的要点总结，每行一条，不要客套。";
        }
        JSONArray msgs = new JSONArray();
        try {
            JSONObject s = new JSONObject();
            s.put("role", "system");
            s.put("content", sys);
            msgs.put(s);
            JSONObject u = new JSONObject();
            u.put("role", "user");
            u.put("content", text.length() > 6000 ? text.substring(0, 6000) : text);
            msgs.put(u);
        } catch (Throwable ignored) {
        }
        Ai.rawChat(c, msgs, 500, new Ai.Cb() {
            @Override
            public void ok(String t) {
                cb.ok(Brain.clean(t, 1200));
            }

            @Override
            public void fail(String e) {
                cb.fail(e);
            }
        });
    }
}
