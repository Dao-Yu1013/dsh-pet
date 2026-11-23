package com.dshpet.android;

import android.content.Context;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** AI-authored long documents: private seven-day storage, one-sentence memory, and real exports. */
public final class LongDocs {
    private static final String INDEX = "long_docs.json";
    private static final long KEEP_MS = 7L * 24 * 60 * 60 * 1000;
    private static final long MAX_BYTES = 2L * 1024 * 1024;
    private static long lastCleanupAt;

    private LongDocs() { }

    public static String chatCardInstructions() {
        return "回复形式规则：日常简短聊天照常自然分成完整句子，不要为了卡片而卡片。最终答复正文超过 1000 个字符，或答复中包含代码/程序片段（不论长短），才按长文卡片格式输出；卡片正文外另给一句话长期记忆摘要。没有达到条件时不要输出任何长文标记。\n";
    }

    public static String instructions() {
        return "用户这次要你写一份可交付的长文档。请认真完成内容，不要只答应、反问无关问题或只给提纲；缺少不影响主体的细节时采用合理假设并标明。"
                + "用户若未指定格式则按普通文本自然回复；若指定 md、txt 或 jar，则按指定格式创作。JAR 格式请输出完整、可读的 Java 源码正文，不要用 Markdown 代码围栏包裹；应用会把它打成包含源码和说明的标准 JAR 源码归档，不要声称它是可直接运行的程序。"
                + "长内容/代码才用以下机器格式包裹，普通短回复不要用标记：\n"
                + "[[长文档开始|格式|文件名]]\n完整正文\n[[长文档结束]]\n[[长期记忆|一句话概括文档主题和核心用途]]\n"
                + "格式只能是 md、txt 或 jar；文件名不带扩展名，简短且适合作为文件名。正文要完整、结构清楚；摘要必须恰好一句话，不能包含换行或标记。不要把隐藏标记放进正文。\n";
    }

    /** Parse only a fully closed document block; partial model output is never saved as complete. */
    public static JSONObject saveFromResponse(Context c, String response) {
        JSONObject out = new JSONObject();
        if (response == null) return out;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "(?s)\\[\\[(?:长|長)文档开始[|｜:]([a-zA-Z0-9]+)[|｜:]([^\\]\\r\\n]{1,100})]](.*?)\\[\\[(?:长|長)文档结束]]").matcher(response);
        boolean marked = m.find();
        String format = marked ? m.group(1).toLowerCase(java.util.Locale.ROOT) : "md";
        if (!("md".equals(format) || "txt".equals(format) || "jar".equals(format))) return out;
        String title = safeName(marked ? m.group(2) : "长文创作");
        String body = (marked ? m.group(3) : response).trim();
        if (body.isEmpty() || body.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) return out;
        boolean code = looksLikeCode(body) || "jar".equals(format);
        if (body.length() <= 1000 && !code) {
            try {
                out.put("short", true);
                out.put("text", body);
            } catch (Throwable ignored) { }
            return out;
        }
        java.util.regex.Matcher sm = java.util.regex.Pattern.compile("(?s)\\[\\[长期记忆[|｜:]([^\\]]{1,240})]]").matcher(marked ? response.substring(m.end()) : "");
        String summary = sm.find() ? firstSentence(sm.group(1)) : firstSentence(body);
        if (summary.isEmpty()) summary = "已完成《" + title + "》，内容涵盖" + title + "的主要说明。";
        String id = UUID.randomUUID().toString().replace("-", "");
        File dir = new File(Sg.dir(c), "long-docs");
        if (!dir.exists() && !dir.mkdirs()) return out;
        String contentExt = "jar".equals(format) ? (looksLikeJava(body) ? "java" : "txt") : format;
        File contentFile = new File(dir, id + "." + contentExt);
        File exportFile = "jar".equals(format) ? new File(dir, id + ".jar") : contentFile;
        try {
            writeText(contentFile, body);
            if ("jar".equals(format)) createJar(exportFile, title, body, contentExt);
            long now = System.currentTimeMillis();
            JSONObject item = new JSONObject();
            item.put("id", id);
            item.put("title", title);
            item.put("format", format);
            item.put("path", exportFile.getName());
            item.put("contentPath", contentFile.getName());
            item.put("createdAt", now);
            item.put("expiresAt", now + KEEP_MS);
            item.put("summary", summary);
            synchronized (LongDocs.class) {
                JSONArray all = Sg.readArr(c, INDEX);
                all.put(item);
                Sg.writeArr(c, INDEX, all);
            }
            // Only the requested one-sentence summary enters long-term memory; full content expires.
            Data.addMemory(c, firstSentence("文档《" + title + "》：" + summary), "generated_document");
            out.put("ok", true);
            out.put("id", id);
            out.put("title", title);
            out.put("format", format);
            out.put("summary", summary);
        } catch (Throwable t) {
            contentFile.delete();
            if (!exportFile.equals(contentFile)) exportFile.delete();
            return new JSONObject();
        }
        return out;
    }

    public static boolean isPartial(String response) {
        return response != null && (response.contains("[[长文档开始") || response.contains("[[長文档开始"))
                && !response.contains("[[长文档结束]]") && !response.contains("[[長文档结束]]");
    }

    public static JSONObject get(Context c, String id) {
        JSONObject item = find(c, id);
        if (item == null || item.optBoolean("expired", false) || System.currentTimeMillis() >= item.optLong("expiresAt", 0))
            return error("文档已过期，长期记忆里只保留了一句话摘要");
        File src = resolve(c, item.optString("contentPath", ""));
        if (src == null || !src.isFile()) return error("文档文件已清理，长期记忆里只保留了一句话摘要");
        try {
            byte[] bytes = readBytes(src, MAX_BYTES);
            JSONObject out = new JSONObject();
            out.put("ok", true);
            out.put("id", id);
            out.put("title", item.optString("title", "长文档"));
            out.put("format", item.optString("format", "md"));
            out.put("summary", item.optString("summary", ""));
            out.put("text", new String(bytes, StandardCharsets.UTF_8));
            out.put("expiresAt", item.optLong("expiresAt", 0));
            return out;
        } catch (Throwable t) { return error("读取文档失败"); }
    }

    public static JSONObject info(Context c, String id) {
        JSONObject item = find(c, id);
        if (item == null) return error("找不到这份文档");
        JSONObject out = new JSONObject();
        try {
            out.put("ok", true);
            out.put("id", id);
            out.put("title", item.optString("title", "长文档"));
            out.put("format", item.optString("format", "md"));
            out.put("summary", item.optString("summary", ""));
            out.put("expired", item.optBoolean("expired", false) || System.currentTimeMillis() >= item.optLong("expiresAt", 0));
            out.put("expiresAt", item.optLong("expiresAt", 0));
        } catch (Throwable ignored) { }
        return out;
    }

    public static boolean export(Context c, String id, String format, Uri destination) {
        JSONObject item = find(c, id);
        if (item == null || item.optBoolean("expired", false) || System.currentTimeMillis() >= item.optLong("expiresAt", 0)) return false;
        File src = resolve(c, item.optString("contentPath", ""));
        if (src == null || !src.isFile()) return false;
        String ext = normalizeFormat(format);
        if (ext.isEmpty()) return false;
        try (OutputStream out = c.getContentResolver().openOutputStream(destination)) {
            if (out == null) return false;
            byte[] bytes = readBytes(src, MAX_BYTES);
            String title = item.optString("title", "long-document");
            if ("jar".equals(ext)) {
                createJar(out, title, new String(bytes, StandardCharsets.UTF_8),
                        src.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".java") ? "java" : "txt");
            } else {
                String body = new String(bytes, StandardCharsets.UTF_8);
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
            out.flush();
            return true;
        } catch (Throwable ignored) { return false; }
    }

    public static void enrich(Context c, JSONArray messages) {
        if (messages == null) return;
        for (int i = 0; i < messages.length(); i++) {
            JSONObject m = messages.optJSONObject(i);
            if (m == null || !"long_text_document".equals(m.optString("cardType", ""))) continue;
            JSONObject info = info(c, m.optString("docId", ""));
            if (info.optBoolean("ok", false)) {
                try {
                    m.put("docName", info.optString("title", "长文档"));
                    m.put("docFormat", info.optString("format", "md"));
                    m.put("docSummary", info.optString("summary", ""));
                    m.put("docExpired", info.optBoolean("expired", false));
                } catch (Throwable ignored) { }
            } else try { m.put("docExpired", true); } catch (Throwable ignored) { }
        }
    }

    public static void cleanup(Context c) {
        synchronized (LongDocs.class) {
            long now = System.currentTimeMillis();
            if (now - lastCleanupAt < 60L * 60 * 1000) return;
            lastCleanupAt = now;
            JSONArray old = Sg.readArr(c, INDEX), kept = new JSONArray();
            File dir = new File(Sg.dir(c), "long-docs");
            for (int i = 0; i < old.length(); i++) {
                JSONObject item = old.optJSONObject(i);
                if (item == null) continue;
                if (now >= item.optLong("expiresAt", now)) {
                    deleteChild(dir, item.optString("path", ""));
                    deleteChild(dir, item.optString("contentPath", ""));
                    item.remove("path");
                    item.remove("contentPath");
                    try { item.put("expired", true); } catch (Throwable ignored) { }
                }
                kept.put(item);
            }
            Sg.writeArr(c, INDEX, kept);
        }
    }

    public static String cardMarker(String id) { return "[[LONGDOC_CARD:" + id + "]]"; }
    public static boolean isCardMarker(String value) {
        return value != null && value.trim().matches("(?i)\\[\\[LONGDOC_CARD:[a-f0-9]{32}]]");
    }

    private static JSONObject find(Context c, String id) {
        if (id == null || !id.matches("[a-fA-F0-9]{32}")) return null;
        JSONArray all = Sg.readArr(c, INDEX);
        for (int i = 0; i < all.length(); i++) {
            JSONObject item = all.optJSONObject(i);
            if (item != null && id.equals(item.optString("id", ""))) return item;
        }
        return null;
    }

    private static File resolve(Context c, String name) {
        if (name == null || !name.matches("[a-fA-F0-9]{32}\\.[a-zA-Z0-9]{1,8}")) return null;
        return new File(new File(Sg.dir(c), "long-docs"), name);
    }

    private static void deleteChild(File dir, String name) {
        File f = resolvePath(dir, name);
        if (f != null) f.delete();
    }

    private static File resolvePath(File dir, String name) {
        return name != null && name.matches("[a-fA-F0-9]{32}\\.[a-zA-Z0-9]{1,8}") ? new File(dir, name) : null;
    }

    private static String normalizeFormat(String format) {
        String f = format == null ? "" : format.toLowerCase(java.util.Locale.ROOT).replace(".", "").trim();
        return "md".equals(f) || "markdown".equals(f) ? "md" : "txt".equals(f) ? "txt" : "jar".equals(f) ? "jar" : "";
    }

    private static String safeName(String s) {
        String x = s == null ? "文档" : s.trim().replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
        if (x.isEmpty()) x = "文档";
        return x.length() > 60 ? x.substring(0, 60) : x;
    }

    private static boolean looksLikeJava(String body) {
        return body.contains("class ") && (body.contains("public ") || body.contains("static "));
    }

    private static boolean looksLikeCode(String body) {
        String s = body == null ? "" : body;
        return s.contains("```") || s.matches("(?s).*\\b(class|interface|function|def|import|package)\\b.*")
                || s.contains("=>") || s.contains("</") || s.contains("#!/")
                || s.contains("System.out.") || s.contains("console.log(") || s.contains("printf(")
                || s.matches("(?s).*\\b(?:public|private|static|void)\\s+\\w+\\s*\\([^)]*\\)\\s*\\{.*");
    }

    private static void writeText(File file, String text) throws Exception {
        try (FileOutputStream out = new FileOutputStream(file)) { out.write(text.getBytes(StandardCharsets.UTF_8)); out.getFD().sync(); }
    }

    private static byte[] readBytes(File file, long max) throws Exception {
        if (file.length() > max) throw new IllegalArgumentException("document too large");
        try (FileInputStream in = new FileInputStream(file); java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) > 0) { if (out.size() + n > max) throw new IllegalArgumentException("document too large"); out.write(buf, 0, n); }
            return out.toByteArray();
        }
    }

    private static void createJar(File file, String title, String body, String bodyExt) throws Exception {
        try (OutputStream out = new FileOutputStream(file)) { createJar(out, title, body, bodyExt); }
    }

    private static void createJar(OutputStream output, String title, String body, String bodyExt) throws Exception {
        try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
            zip.write("Manifest-Version: 1.0\r\nCreated-By: DSH Pet\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            String safe = safeName(title);
            String entry = "java".equals(bodyExt) ? "src/" + safe + ".java" : "docs/" + safe + ".txt";
            zip.putNextEntry(new ZipEntry(entry));
            zip.write(body.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("README.txt"));
            String note = "这是一个源码/文档 JAR 归档。Java 源码包需要在对应 Java 环境中编译后才能运行；它不是 Android 安装包。\n";
            zip.write(note.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }

    private static String firstSentence(String text) {
        String s = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        if (s.isEmpty()) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(.{1,150}?[。！？!?])(?:\\s|$)").matcher(s);
        String one = m.find() ? m.group(1) : s.substring(0, Math.min(110, s.length()));
        return one.length() > 160 ? one.substring(0, 159) + "…" : one;
    }

    private static JSONObject error(String message) {
        JSONObject o = new JSONObject();
        try { o.put("ok", false); o.put("error", message); } catch (Throwable ignored) { }
        return o;
    }
}
