package com.dshpet.android;

import android.content.Context;
import android.net.Uri;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** 人设卡：支持 JSON、纯文本、SillyTavern PNG（tEXt chara）。 */
public final class Card {
    private Card() {
    }

    public static JSONObject parse(Context c, Uri uri) {
        try {
            byte[] b = read(c, uri);
            if (b == null || b.length == 0) return null;
            if (isPng(b)) {
                String text = pngText(b);
                if (text != null && !text.trim().isEmpty()) {
                    JSONObject p = fromJson(new JSONObject(text));
                    if (p != null) return p;
                }
            }
            String s = new String(b, StandardCharsets.UTF_8).trim();
            if (s.startsWith("{")) {
                JSONObject p = fromJson(new JSONObject(s));
                if (p != null) return p;
            }
            return parseText(s);
        } catch (Throwable t) {
            return null;
        }
    }

    private static final String[][] SECTIONS = {
            {"personality", "性格", "个性", "特征", "traits", "temperament"},
            {"description", "background", "背景", "设定", "身份", "appearance", "外貌", "profile", "角色背景", "story"},
            {"style", "说话风格", "语言风格", "语气", "speech", "voice", "tone", "communication", "语言"},
            {"greeting", "开场白", "问候", "first message", "greeting message", "初次"},
            {"boundaries", "边界", "禁忌", "规则", "rules", "limits", "do not", "限制"}
    };

    /** 纯文本人设：按中英文小标题自动归类，无法归类时整段作为背景。 */
    public static JSONObject parseText(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        try {
            if (s.startsWith("{")) {
                JSONObject j = fromJson(new JSONObject(s));
                if (j != null) return j;
            }
            JSONObject p = new JSONObject();
            p.put("name", guessName(s));
            // 去人设化：性格由涌现决定，不再手写 description/personality
            String style = section(s, 2);
            if (style.isEmpty()) style = looksEnglish(s) ? "英文口语，短句，保持角色语气。" : "中文口语，短句，1～2 句。";
            p.put("style", style);
            String greeting = section(s, 3);
            p.put("greeting", greeting.isEmpty() ? "初次见面，请多关照。" : greeting);
            p.put("boundaries", section(s, 4));
            boolean en = looksEnglish(s);
            p.put("language", en ? "en" : "zh");
            p.put("source", "paste");
            return p;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String category(String s, boolean en) {
        String low = s.toLowerCase();
        if (s.contains("鲸鱼") || s.contains("大肥鱼")) return "鲸鱼娘";
        if (s.contains("女仆")) return "女仆角色";
        if (s.contains("助手")) return "工作助手";
        if (s.contains("傲娇") || low.contains("tsundere")) return "傲娇角色";
        if (en) return "英文角色卡";
        return "通用角色卡";
    }

    private static String guessName(String s) {
        String[] lines = s.split("\\n");
        for (int i = 0; i < lines.length && i < 12; i++) {
            String line = lines[i].trim();
            String low = line.toLowerCase();
            String[] keys = {"name:", "name：", "char_name:", "角色名:", "角色名：", "名字:", "名字：", "姓名:", "姓名："};
            for (String k : keys) {
                if (low.startsWith(k)) {
                    String v = line.substring(k.length()).trim();
                    if (!v.isEmpty() && v.length() <= 30) return v;
                }
            }
            if (low.startsWith("you are ")) {
                String v = line.substring(8).trim();
                if (v.length() <= 30) return v;
            }
        }
        String first = lines.length == 0 ? "" : lines[0].trim();
        if (!first.isEmpty() && first.length() <= 16
                && !first.contains("。") && !first.contains("，") && !first.contains(".")
                && !first.contains("！") && !first.contains("!") && !first.contains("？") && !first.contains("?")) {
            return first;
        }
        return "自定义角色";
    }

    private static String section(String s, int idx) {
        String target = SECTIONS[idx][0];
        StringBuilder sb = new StringBuilder();
        boolean on = false;
        for (String line : s.split("\\n")) {
            String l = line.trim();
            String hit = headingKey(l);
            if (hit != null) {
                on = hit.equals(target);
                if (on) {
                    String rest = afterHeading(l, hit);
                    if (!rest.isEmpty()) sb.append(rest).append('\n');
                }
                continue;
            }
            if (on && !l.isEmpty()) sb.append(l).append('\n');
        }
        return sb.toString().trim();
    }

    private static String headingKey(String line) {
        if (line.isEmpty() || line.length() > 60) return null;
        String low = line.toLowerCase();
        for (String[] group : SECTIONS) {
            for (String k : group) {
                if (low.equals(k) || low.startsWith(k + ":") || low.startsWith(k + "：")) return group[0];
            }
        }
        return null;
    }

    private static String afterHeading(String line, String key) {
        String low = line.toLowerCase();
        for (int k = 0; k < SECTIONS.length; k++) {
            if (!SECTIONS[k][0].equals(key)) continue;
            for (String name : SECTIONS[k]) {
                if (low.startsWith(name + ":") || low.startsWith(name + "：")) {
                    return line.substring(name.length() + 1).trim();
                }
            }
        }
        return "";
    }

    private static String guessPersonality(String s) {
        String low = s.toLowerCase();
        StringBuilder sb = new StringBuilder();
        if (low.contains("tsundere") || s.contains("傲娇")) sb.append("傲娇、嘴硬心软");
        if (low.contains("gentle") || s.contains("温柔")) sb.append(sb.length() > 0 ? "、" : "").append("温柔");
        if (low.contains("clingy") || s.contains("粘人")) sb.append(sb.length() > 0 ? "、" : "").append("黏人");
        if (low.contains("lazy") || s.contains("懒")) sb.append(sb.length() > 0 ? "、" : "").append("慵懒");
        if (sb.length() == 0) sb.append("按文本中的设定自然演绎");
        return sb.toString();
    }

    private static boolean looksEnglish(String s) {
        int latin = 0, cjk = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) latin++;
            else if (c >= 0x4E00 && c <= 0x9FFF) cjk++;
        }
        return latin > cjk * 2;
    }

    private static byte[] read(Context c, Uri uri) {
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isPng(byte[] b) {
        return b.length > 8 && (b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
    }

    private static String pngText(byte[] b) {
        try {
            int p = 8;
            while (p + 8 <= b.length) {
                int len = ((b[p] & 0xff) << 24) | ((b[p + 1] & 0xff) << 16) | ((b[p + 2] & 0xff) << 8) | (b[p + 3] & 0xff);
                String type = new String(b, p + 4, 4, StandardCharsets.US_ASCII);
                int data = p + 8;
                if (len < 0 || data + len > b.length) break;
                if ("tEXt".equals(type)) {
                    int z = data;
                    while (z < data + len && b[z] != 0) z++;
                    String key = new String(b, data, z - data, StandardCharsets.ISO_8859_1);
                    String val = new String(b, z + 1, data + len - z - 1, StandardCharsets.ISO_8859_1);
                    if ("chara".equalsIgnoreCase(key)) return new String(Base64.getDecoder().decode(val.trim()), StandardCharsets.UTF_8);
                } else if ("iTXt".equals(type)) {
                    int z = data;
                    while (z < data + len && b[z] != 0) z++;
                    String key = new String(b, data, z - data, StandardCharsets.ISO_8859_1);
                    if ("chara".equalsIgnoreCase(key)) {
                        int q = z + 3;
                        while (q < data + len && b[q] != 0) q++;
                        q++;
                        while (q < data + len && b[q] != 0) q++;
                        q++;
                        String val = new String(b, q, data + len - q, StandardCharsets.UTF_8);
                        return new String(Base64.getDecoder().decode(val.trim()), StandardCharsets.UTF_8);
                    }
                }
                p = data + len + 4;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static JSONObject fromJson(JSONObject o) {
        try {
            JSONObject d = o.optJSONObject("data");
            if (d == null) d = o;
            JSONObject p = new JSONObject();
            String name = d.optString("name", o.optString("name", "自定义角色"));
            p.put("name", name);
            // 去人设化：不再写 description/personality，性格由涌现决定
            String sys = d.optString("system_prompt", "");
            p.put("style", sys.isEmpty() ? "中文口语，短句，1～2 句，符合角色。" : sys);
            p.put("greeting", d.optString("first_mes", "初次见面，请多关照。"));
            p.put("boundaries", d.optString("post_history_instructions", ""));
            p.put("source", o.optString("spec", "json"));
            return p;
        } catch (Throwable t) {
            return null;
        }
    }
}
