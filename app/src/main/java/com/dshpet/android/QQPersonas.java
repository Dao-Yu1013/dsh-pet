package com.dshpet.android;

import android.content.Context;
import android.net.Uri;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.zip.InflaterInputStream;

/** Named, user-managed character cards used only by the configured QQ group connection. */
final class QQPersonas {
    private static final String FILE = "qq_group_personas.json";
    private static final int MAX_CARDS = 40;
    private static final int MAX_PROMPT = 8000;
    private static final int MAX_IMPORT_BYTES = 12 * 1024 * 1024;

    private QQPersonas() { }

    static JSONObject state(Context context) {
        JSONObject out = new JSONObject();
        try {
            out.put("ok", true).put("items", items(context))
                    .put("selectedId", Cfg.load(context).str("qqGroupPersonaId", ""));
        } catch (Throwable t) {
            try { out.put("ok", false).put("error", "读取群聊人设失败"); } catch (Throwable ignored) { }
        }
        return out;
    }

    static JSONArray items(Context context) {
        JSONObject saved = Sg.readObj(context, FILE);
        JSONArray source = saved.optJSONArray("items");
        JSONArray safe = new JSONArray();
        if (source == null) return safe;
        for (int i = 0; i < source.length() && safe.length() < MAX_CARDS; i++) {
            JSONObject item = source.optJSONObject(i);
            if (item == null) continue;
            String id = item.optString("id", "");
            String name = clean(item.optString("name", ""), 60);
            String prompt = clean(item.optString("prompt", ""), MAX_PROMPT);
            if (id.isEmpty() || name.isEmpty() || prompt.isEmpty()) continue;
            try { safe.put(new JSONObject().put("id", id).put("name", name).put("prompt", prompt)
                    .put("source", item.optString("source", "manual"))); }
            catch (Throwable ignored) { }
        }
        return safe;
    }

    static JSONObject save(Context context, String id, String name, String prompt, String source) {
        try {
            String safeName = clean(name, 60);
            String safePrompt = clean(prompt, MAX_PROMPT);
            if (safeName.isEmpty()) return error("先给群聊人设起个名字");
            if (safePrompt.isEmpty()) return error("请填写或导入人设内容");
            JSONObject saved = Sg.readObj(context, FILE);
            JSONArray old = saved.optJSONArray("items");
            if (old == null) old = new JSONArray();
            String safeId = clean(id, 80);
            if (safeId.isEmpty()) safeId = UUID.randomUUID().toString();
            JSONArray next = new JSONArray();
            boolean replaced = false;
            for (int i = 0; i < old.length(); i++) {
                JSONObject item = old.optJSONObject(i);
                if (item == null) continue;
                if (safeId.equals(item.optString("id", ""))) {
                    if (!replaced) next.put(card(safeId, safeName, safePrompt, source));
                    replaced = true;
                } else next.put(item);
            }
            if (!replaced) {
                if (next.length() >= MAX_CARDS) return error("最多保存 " + MAX_CARDS + " 张群聊人设卡");
                next.put(card(safeId, safeName, safePrompt, source));
            }
            saved.put("items", next).put("updatedAt", System.currentTimeMillis());
            Sg.writeObj(context, FILE, saved);
            return state(context).put("saved", true).put("id", safeId);
        } catch (Throwable t) { return error("保存群聊人设失败"); }
    }

    static JSONObject delete(Context context, String id) {
        try {
            String wanted = clean(id, 80);
            JSONArray old = items(context), next = new JSONArray();
            boolean removed = false;
            for (int i = 0; i < old.length(); i++) {
                JSONObject item = old.optJSONObject(i);
                if (item == null) continue;
                if (wanted.equals(item.optString("id", ""))) removed = true;
                else next.put(item);
            }
            if (!removed) return error("找不到这张人设卡");
            JSONObject saved = Sg.readObj(context, FILE);
            saved.put("items", next).put("updatedAt", System.currentTimeMillis());
            Sg.writeObj(context, FILE, saved);
            Cfg cfg = Cfg.load(context);
            if (wanted.equals(cfg.str("qqGroupPersonaId", ""))) {
                cfg.set("qqGroupPersonaId", "");
                cfg.save(context);
                if (cfg.b("qqLinkEnabled", false)) QQOneBot.sync(context);
            }
            return state(context).put("deleted", true);
        } catch (Throwable t) { return error("删除群聊人设失败"); }
    }

    static JSONObject select(Context context, String id) {
        try {
            String wanted = clean(id, 80);
            if (!wanted.isEmpty() && find(context, wanted) == null) return error("所选群聊人设不存在");
            Cfg cfg = Cfg.load(context);
            cfg.set("qqGroupPersonaId", wanted);
            cfg.save(context);
            if (cfg.b("qqLinkEnabled", false)) QQOneBot.sync(context);
            return state(context).put("selected", true);
        } catch (Throwable t) { return error("切换群聊人设失败"); }
    }

    static JSONObject selected(Context context) {
        Cfg cfg = Cfg.load(context);
        String id = cfg.str("qqGroupPersonaId", "");
        return id.isEmpty() ? null : find(context, id);
    }

    static boolean contains(Context context, String id) {
        String wanted = clean(id, 80);
        return wanted.isEmpty() || find(context, wanted) != null;
    }

    private static JSONObject find(Context context, String id) {
        JSONArray rows = items(context);
        for (int i = 0; i < rows.length(); i++) {
            JSONObject item = rows.optJSONObject(i);
            if (item != null && id.equals(item.optString("id", ""))) return item;
        }
        return null;
    }

    static JSONObject importUri(Context context, Uri uri, String fileName) {
        if (context == null || uri == null) return error("没有选中人设卡文件");
        try {
            byte[] bytes;
            try (InputStream input = context.getContentResolver().openInputStream(uri)) {
                if (input == null) return error("无法读取人设卡文件");
                bytes = readBounded(input, MAX_IMPORT_BYTES);
            }
            if (bytes.length == 0) return error("人设卡文件是空的");
            String extension = extension(fileName);
            String text;
            if (isPng(bytes)) {
                text = embeddedPngCard(bytes);
                if (text.isEmpty()) return error("这张 PNG 没有找到受支持的角色卡信息");
            } else {
                text = new String(bytes, StandardCharsets.UTF_8);
                if (text.startsWith("\uFEFF")) text = text.substring(1);
                if ("txt".equals(extension) || "md".equals(extension))
                    return save(context, "", baseName(fileName), text, "text").put("imported", true);
            }
            JSONObject root;
            try { root = new JSONObject(text.trim()); }
            catch (Throwable t) { return error("请导入 Character Card JSON、带角色卡数据的 PNG，或 TXT/MD 人设文件"); }
            JSONObject data = root.optJSONObject("data");
            if (data == null) data = root;
            String characterName = first(data, "name", "character_name", "display_name");
            String name = characterName.isEmpty() ? baseName(fileName) : characterName;
            String prompt = buildPrompt(data, characterName);
            if (prompt.isEmpty()) return error("人设卡里没有可用的角色描述或提示词");
            return save(context, "", name, prompt, "character-card").put("imported", true)
                    .put("characterName", characterName);
        } catch (Throwable t) {
            return error(t.getMessage() == null ? "导入群聊人设卡失败" : t.getMessage());
        }
    }

    private static String buildPrompt(JSONObject data, String characterName) {
        StringBuilder out = new StringBuilder();
        append(out, "角色名称", characterName);
        append(out, "角色描述", first(data, "description", "desc", "background"));
        append(out, "性格", first(data, "personality", "traits"));
        append(out, "场景", first(data, "scenario", "setting"));
        append(out, "开场示例", first(data, "first_mes", "greeting", "first_message"));
        append(out, "对话示例", first(data, "mes_example", "example_dialogue", "examples"));
        append(out, "角色指令", first(data, "system_prompt", "prompt", "persona"));
        append(out, "补充指令", first(data, "post_history_instructions", "system_instruction"));
        if (out.length() > MAX_PROMPT) return out.substring(0, MAX_PROMPT);
        return out.toString().trim();
    }

    private static void append(StringBuilder out, String label, String value) {
        String text = clean(value, 5000);
        if (text.isEmpty() || out.length() >= MAX_PROMPT) return;
        out.append(label).append("：\n");
        int remaining = MAX_PROMPT - out.length();
        out.append(text, 0, Math.min(text.length(), Math.max(0, remaining))).append('\n');
    }

    private static JSONObject card(String id, String name, String prompt, String source) throws Exception {
        return new JSONObject().put("id", id).put("name", name).put("prompt", prompt)
                .put("source", clean(source, 40)).put("updatedAt", System.currentTimeMillis());
    }

    private static String embeddedPngCard(byte[] png) throws Exception {
        if (!isPng(png)) return "";
        int offset = 8;
        while (offset + 12 <= png.length) {
            long size = uint32(png, offset);
            if (size > MAX_IMPORT_BYTES || size > png.length - offset - 12L) break;
            String type = new String(png, offset + 4, 4, StandardCharsets.US_ASCII);
            int begin = offset + 8;
            int end = begin + (int) size;
            String key = "";
            String payload = "";
            if ("tEXt".equals(type)) {
                int sep = indexOfZero(png, begin, end);
                if (sep >= 0) {
                    key = ascii(png, begin, sep);
                    payload = new String(png, sep + 1, end - sep - 1, StandardCharsets.UTF_8);
                }
            } else if ("zTXt".equals(type)) {
                int sep = indexOfZero(png, begin, end);
                if (sep >= 0 && sep + 2 <= end) {
                    key = ascii(png, begin, sep);
                    if (png[sep + 1] == 0) payload = inflate(png, sep + 2, end - sep - 2);
                }
            } else if ("iTXt".equals(type)) {
                int sep = indexOfZero(png, begin, end);
                if (sep >= 0 && sep + 2 <= end) {
                    key = ascii(png, begin, sep);
                    int at = sep + 1;
                    int compressed = png[at++] & 255;
                    int method = png[at++] & 255;
                    int languageEnd = indexOfZero(png, at, end);
                    if (languageEnd >= 0) {
                        int translatedEnd = indexOfZero(png, languageEnd + 1, end);
                        if (translatedEnd >= 0) {
                            int textStart = translatedEnd + 1;
                            payload = compressed == 1 && method == 0
                                    ? inflate(png, textStart, end - textStart)
                                    : new String(png, textStart, end - textStart, StandardCharsets.UTF_8);
                        }
                    }
                }
            }
            if ("chara".equalsIgnoreCase(key) || "ccv3".equalsIgnoreCase(key)) {
                String decoded = decodeCardPayload(payload);
                if (!decoded.isEmpty()) return decoded;
            }
            offset = end + 4;
            if ("IEND".equals(type)) break;
        }
        return "";
    }

    private static String decodeCardPayload(String payload) {
        if (payload == null || payload.trim().isEmpty()) return "";
        String text = payload.trim();
        if (text.startsWith("{")) return text;
        try {
            byte[] decoded = Base64.decode(text, Base64.DEFAULT);
            String json = new String(decoded, StandardCharsets.UTF_8).trim();
            if (json.startsWith("{")) return json;
            String inflated = inflate(decoded, 0, decoded.length).trim();
            return inflated.startsWith("{") ? inflated : "";
        } catch (Throwable ignored) { return ""; }
    }

    private static String inflate(byte[] bytes, int offset, int length) {
        try (InputStream raw = new java.io.ByteArrayInputStream(bytes, offset, length);
             InputStream input = new InflaterInputStream(raw);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = input.read(buf)) >= 0) {
                if (out.size() + n > MAX_IMPORT_BYTES) return "";
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Throwable ignored) { return ""; }
    }

    private static byte[] readBounded(InputStream input, int max) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = input.read(buffer)) >= 0) {
            if (out.size() + n > max) throw new IllegalArgumentException("人设卡文件超过 12 MB");
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    private static boolean isPng(byte[] bytes) {
        return bytes != null && bytes.length >= 8 && bytes[0] == (byte) 137 && bytes[1] == 80
                && bytes[2] == 78 && bytes[3] == 71 && bytes[4] == 13 && bytes[5] == 10
                && bytes[6] == 26 && bytes[7] == 10;
    }

    private static long uint32(byte[] bytes, int at) {
        return ((long) (bytes[at] & 255) << 24) | ((long) (bytes[at + 1] & 255) << 16)
                | ((long) (bytes[at + 2] & 255) << 8) | (long) (bytes[at + 3] & 255);
    }

    private static int indexOfZero(byte[] bytes, int begin, int end) {
        for (int i = begin; i < end; i++) if (bytes[i] == 0) return i;
        return -1;
    }

    private static String ascii(byte[] bytes, int begin, int end) {
        return new String(bytes, begin, Math.max(0, end - begin), StandardCharsets.US_ASCII);
    }

    private static String first(JSONObject json, String... keys) {
        for (String key : keys) {
            Object value = json.opt(key);
            if (value instanceof String && !((String) value).trim().isEmpty())
                return (String) value;
        }
        return "";
    }

    private static String extension(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
    }

    private static String baseName(String filename) {
        String value = filename == null ? "" : filename.trim();
        int slash = Math.max(value.lastIndexOf('/'), value.lastIndexOf('\\'));
        if (slash >= 0) value = value.substring(slash + 1);
        int dot = value.lastIndexOf('.');
        if (dot > 0) value = value.substring(0, dot);
        value = clean(value, 60);
        return value.isEmpty() ? "导入的人设" : value;
    }

    private static String clean(String text, int limit) {
        if (text == null) return "";
        String value = text.replace((char) 0, ' ').trim();
        return value.length() > limit ? value.substring(0, limit).trim() : value;
    }

    private static JSONObject error(String message) {
        try { return new JSONObject().put("ok", false).put("error", message); }
        catch (Throwable ignored) { return new JSONObject(); }
    }
}
