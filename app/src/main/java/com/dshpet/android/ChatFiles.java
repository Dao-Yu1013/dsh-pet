package com.dshpet.android;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Xml;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.BufferedInputStream;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** One week temporary chat attachments and compact summaries retained in S-G. */
public final class ChatFiles {
    private static final String INDEX = "chat_files.json";
    private static final long KEEP_MS = 7L * 24 * 60 * 60 * 1000;
    /** Model-context guard, not an attachment-size limit. */
    private static final int MAX_TEXT_CHARS = 100_000;
    private static long lastCleanupAt;

    private ChatFiles() { }

    public static JSONObject importUri(Context c, Uri uri) {
        JSONObject out = new JSONObject();
        if (uri == null) return error("没有收到文件");
        String name = displayName(c, uri);
        String ext = extension(name);
        String id = UUID.randomUUID().toString().replace("-", "");
        File dir = new File(Sg.dir(c), "chat-files");
        if (!dir.exists() && !dir.mkdirs()) return error("无法创建 S-G 临时文件目录");
        File raw = new File(dir, id + (ext.isEmpty() ? ".bin" : "." + ext));
        try (InputStream in = c.getContentResolver().openInputStream(uri);
             java.io.FileOutputStream dst = new java.io.FileOutputStream(raw)) {
            if (in == null) return error("无法读取这个文件");
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) >= 0) {
                dst.write(buffer, 0, n);
            }
            dst.getFD().sync();
        } catch (Throwable t) {
            raw.delete();
            return error("文件读取失败：" + safeMessage(t));
        }

        final String extracted;
        try {
            extracted = extract(raw, ext);
        } catch (Throwable t) {
            raw.delete();
            return error("暂时无法解析这个文件，请使用 TXT、MD、CSV、DOCX、XLSX 或 PPTX 格式");
        }
        String text = extracted == null ? "" : extracted.trim();
        if (text.isEmpty()) {
            raw.delete();
            return error("文件里没有可提取的文字内容");
        }
        if (text.length() >= MAX_TEXT_CHARS) text = text.substring(0, MAX_TEXT_CHARS) + "\n（文档正文会完整保留；本轮模型上下文先读取前 10 万字）";
        long now = System.currentTimeMillis();
        String summary = firstSentence(text);
        if (summary.isEmpty()) summary = "已读取《" + name + "》，内容可在本轮聊天中继续讨论。";
        synchronized (ChatFiles.class) {
            JSONArray all = Sg.readArr(c, INDEX);
            JSONObject item = new JSONObject();
            try {
                item.put("id", id);
                item.put("name", name);
                item.put("path", raw.getName());
                item.put("createdAt", now);
                item.put("expiresAt", now + KEEP_MS);
                item.put("summary", summary);
                all.put(item);
                Sg.writeArr(c, INDEX, all);
            } catch (Throwable t) {
                raw.delete();
                return error("无法保存文件记录");
            }
        }
        try {
            out.put("ok", true);
            out.put("id", id);
            out.put("name", name);
            out.put("summary", summary);
            out.put("expiresAt", now + KEEP_MS);
        } catch (Throwable ignored) { }
        return out;
    }

    public static void cleanup(Context c) {
        synchronized (ChatFiles.class) {
            long now = System.currentTimeMillis();
            if (now - lastCleanupAt < 60L * 60 * 1000) return;
            lastCleanupAt = now;
            JSONArray old = Sg.readArr(c, INDEX);
            JSONArray kept = new JSONArray();
            File dir = new File(Sg.dir(c), "chat-files");
            for (int i = 0; i < old.length(); i++) {
                JSONObject item = old.optJSONObject(i);
                if (item == null) continue;
                long created = item.optLong("createdAt", now);
                String path = item.optString("path", "");
                if (now - created >= KEEP_MS && !path.isEmpty()) {
                    new File(dir, path).delete();
                    item.remove("path");
                    try { item.put("expired", true); } catch (Throwable ignored) { }
                }
                // The retained record contains only its id, display name, date and one-sentence summary.
                String summary = firstSentence(item.optString("summary", ""));
                try { item.put("summary", summary); } catch (Throwable ignored) { }
                kept.put(item);
            }
            Sg.writeArr(c, INDEX, kept);
        }
    }

    public static void saveSummary(Context c, String id, String text) {
        if (id == null || id.isEmpty()) return;
        String summary = firstSentence(text);
        if (summary.isEmpty()) return;
        synchronized (ChatFiles.class) {
            JSONArray all = Sg.readArr(c, INDEX);
            for (int i = 0; i < all.length(); i++) {
                JSONObject item = all.optJSONObject(i);
                if (item != null && id.equals(item.optString("id", ""))) {
                    try { item.put("summary", summary); } catch (Throwable ignored) { }
                    break;
                }
            }
            Sg.writeArr(c, INDEX, all);
        }
    }

    public static String summary(Context c, String id) {
        if (id == null || id.isEmpty()) return "";
        JSONArray all = Sg.readArr(c, INDEX);
        for (int i = 0; i < all.length(); i++) {
            JSONObject item = all.optJSONObject(i);
            if (item != null && id.equals(item.optString("id", ""))) return item.optString("summary", "");
        }
        return "";
    }

    public static void enrich(Context c, JSONArray messages) {
        if (messages == null) return;
        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.optJSONObject(i);
            if (message == null) continue;
            String id = message.optString("fileId", "");
            if (id.isEmpty()) continue;
            String summary = summary(c, id);
            if (!summary.isEmpty()) try { message.put("fileSummary", summary); } catch (Throwable ignored) { }
            JSONObject item = find(c, id);
            if (item != null) try { message.put("fileExpired", item.optBoolean("expired", false)); } catch (Throwable ignored) { }
        }
    }

    public static String removeSummaryMarker(String response) {
        if (response == null) return "";
        return response.replaceAll("(?s)\\[\\[附件总结[:：].*?]]", "").replaceAll("[ \\t]{2,}", " ").trim();
    }

    public static String summaryMarker(String response) {
        if (response == null) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?s)\\[\\[附件总结[:：](.*?)]]").matcher(response);
        return m.find() ? m.group(1).trim() : "";
    }

    private static JSONObject find(Context c, String id) {
        JSONArray all = Sg.readArr(c, INDEX);
        for (int i = 0; i < all.length(); i++) {
            JSONObject item = all.optJSONObject(i);
            if (item != null && id.equals(item.optString("id", ""))) return item;
        }
        return null;
    }

    /** Read the staged document on the native side so large file text never crosses the WebView bridge. */
    public static String readText(Context c, String id) throws Exception {
        JSONObject item = find(c, id);
        if (item == null || item.optBoolean("expired", false)) throw new IllegalArgumentException("原文件已过期，请重新上传");
        String path = item.optString("path", "");
        if (path.isEmpty()) throw new IllegalArgumentException("找不到暂存的原文件，请重新上传");
        File raw = new File(new File(Sg.dir(c), "chat-files"), path);
        if (!raw.isFile()) throw new IllegalArgumentException("暂存的原文件已不存在，请重新上传");
        String text = extract(raw, extension(item.optString("name", "")));
        text = text == null ? "" : text.trim();
        if (text.length() >= MAX_TEXT_CHARS) text = text.substring(0, MAX_TEXT_CHARS)
                + "\n（文档正文会完整保留；本轮模型上下文先读取前 10 万字）";
        if (text.isEmpty()) throw new IllegalArgumentException("文件中没有可读取的文字内容");
        return text;
    }

    private static String extract(File file, String ext) throws Exception {
        if ("txt".equals(ext) || "md".equals(ext) || "markdown".equals(ext) || "csv".equals(ext)
                || "tsv".equals(ext) || "json".equals(ext) || "xml".equals(ext) || "html".equals(ext)
                || "htm".equals(ext) || "log".equals(ext) || "rtf".equals(ext)) {
            String text = readTextFile(file);
            if ("rtf".equals(ext)) text = text.replaceAll("(?s)\\\\'[0-9a-fA-F]{2}", " ")
                    .replaceAll("\\\\[a-zA-Z]+-?\\d* ?", "").replaceAll("[{}]", "");
            if ("html".equals(ext) || "htm".equals(ext) || "xml".equals(ext)) text = text.replaceAll("(?s)<[^>]+>", " ");
            return text.replaceAll("[\\t ]+", " ").replaceAll(" *\\n *", "\n").trim();
        }
        if ("docx".equals(ext)) return extractDocx(file);
        if ("pptx".equals(ext)) return extractPptx(file);
        if ("xlsx".equals(ext)) return extractXlsx(file);
        if ("odt".equals(ext) || "odp".equals(ext) || "ods".equals(ext)) return extractOpenDocument(file);
        throw new IllegalArgumentException("unsupported format");
    }

    private static String extractDocx(File file) throws Exception {
        ArrayList<String> names = officeEntryNames(file, name -> name.equals("word/document.xml")
                || name.startsWith("word/header") && name.endsWith(".xml")
                || name.startsWith("word/footer") && name.endsWith(".xml"));
        names.sort(String::compareTo);
        StringBuilder out = new StringBuilder();
        try (ZipFile zip = new ZipFile(file)) {
            for (String name : names) {
                try (InputStream part = zip.getInputStream(zip.getEntry(name))) {
                    appendLimited(out, xmlText(part));
                }
                appendLimited(out, "\n");
                if (out.length() >= MAX_TEXT_CHARS) break;
            }
        }
        return out.toString();
    }

    private static String extractPptx(File file) throws Exception {
        ArrayList<String> names = officeEntryNames(file, name -> name.matches("ppt/slides/slide[0-9]+\\.xml"));
        names.sort(Comparator.comparingInt(ChatFiles::slideNumber));
        StringBuilder out = new StringBuilder();
        try (ZipFile zip = new ZipFile(file)) {
            for (String name : names) {
                try (InputStream part = zip.getInputStream(zip.getEntry(name))) {
                    appendLimited(out, xmlText(part));
                }
                appendLimited(out, "\n");
                if (out.length() >= MAX_TEXT_CHARS) break;
            }
        }
        return out.toString();
    }

    private static String extractXlsx(File file) throws Exception {
        ArrayList<String> shared = new ArrayList<>();
        ArrayList<String> names = officeEntryNames(file, name -> name.equals("xl/sharedStrings.xml")
                || name.matches("xl/worksheets/sheet[0-9]+\\.xml"));
        names.sort(Comparator.comparingInt(ChatFiles::sheetNumber));
        StringBuilder out = new StringBuilder();
        try (ZipFile zip = new ZipFile(file)) {
            ZipEntry sharedEntry = zip.getEntry("xl/sharedStrings.xml");
            if (sharedEntry != null) {
                try (InputStream part = zip.getInputStream(sharedEntry)) { shared = sharedStrings(part); }
            }
            for (String name : names) {
                if (!name.startsWith("xl/worksheets/sheet")) continue;
                try (InputStream part = zip.getInputStream(zip.getEntry(name))) {
                    appendLimited(out, sheetText(part, shared));
                }
                appendLimited(out, "\n");
                if (out.length() >= MAX_TEXT_CHARS) break;
            }
        }
        return out.toString();
    }

    private static String extractOpenDocument(File file) throws Exception {
        try (ZipFile zip = new ZipFile(file)) {
            ZipEntry content = zip.getEntry("content.xml");
            if (content == null) return "";
            try (InputStream part = zip.getInputStream(content)) { return xmlText(part); }
        }
    }

    private interface EntryFilter { boolean accept(String name); }

    private static ArrayList<String> officeEntryNames(File file, EntryFilter filter) throws Exception {
        ArrayList<String> names = new ArrayList<>();
        try (ZipFile zip = new ZipFile(file)) {
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.isDirectory() && filter.accept(entry.getName())) names.add(entry.getName());
            }
        }
        return names;
    }

    private static ArrayList<String> sharedStrings(InputStream input) throws Exception {
        ArrayList<String> values = new ArrayList<>();
        XmlPullParser p = parser(new InputStreamReader(input, StandardCharsets.UTF_8));
        StringBuilder current = null;
        int totalChars = 0;
        int event;
        while ((event = p.next()) != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && "si".equals(p.getName())) current = new StringBuilder();
            else if ((event == XmlPullParser.TEXT || event == XmlPullParser.CDSECT) && current != null) {
                int available = MAX_TEXT_CHARS - totalChars - current.length();
                if (available > 0) current.append(p.getText(), 0, Math.min(available, p.getText().length()));
                if (current.length() >= MAX_TEXT_CHARS - totalChars) {
                    values.add(current.toString());
                    return values;
                }
            }
            else if (event == XmlPullParser.END_TAG && "si".equals(p.getName()) && current != null) {
                values.add(current.toString());
                totalChars += current.length();
                current = null;
                if (totalChars >= MAX_TEXT_CHARS || values.size() >= MAX_TEXT_CHARS) break;
            }
        }
        return values;
    }

    private static String sheetText(InputStream input, ArrayList<String> shared) throws Exception {
        XmlPullParser p = parser(new InputStreamReader(input, StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder();
        String type = "";
        StringBuilder value = new StringBuilder();
        boolean inCell = false, inValue = false;
        int event;
        while ((event = p.next()) != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && "c".equals(p.getName())) {
                inCell = true; type = p.getAttributeValue(null, "t"); value.setLength(0);
            } else if (event == XmlPullParser.START_TAG && ("v".equals(p.getName()) || "t".equals(p.getName())) && inCell) inValue = true;
            else if ((event == XmlPullParser.TEXT || event == XmlPullParser.CDSECT) && inValue) {
                int available = MAX_TEXT_CHARS - value.length();
                if (available > 0) value.append(p.getText(), 0, Math.min(available, p.getText().length()));
                if (value.length() >= MAX_TEXT_CHARS) {
                    appendLimited(out, value.toString());
                    break;
                }
            }
            else if (event == XmlPullParser.END_TAG && ("v".equals(p.getName()) || "t".equals(p.getName()))) inValue = false;
            else if (event == XmlPullParser.END_TAG && "c".equals(p.getName()) && inCell) {
                String cell = value.toString();
                if ("s".equals(type)) {
                    try { int index = Integer.parseInt(cell.trim()); if (index >= 0 && index < shared.size()) cell = shared.get(index); }
                    catch (Throwable ignored) { }
                }
                appendLimited(out, cell);
                appendLimited(out, "\t");
                inCell = false;
            } else if (event == XmlPullParser.END_TAG && "row".equals(p.getName())) appendLimited(out, "\n");
            if (out.length() > MAX_TEXT_CHARS) break;
        }
        return out.toString();
    }

    private static String xmlText(InputStream input) throws Exception {
        XmlPullParser p = parser(new InputStreamReader(input, StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder();
        int event;
        while ((event = p.next()) != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.TEXT || event == XmlPullParser.CDSECT) appendLimited(out, p.getText());
            else if (event == XmlPullParser.END_TAG && ("p".equals(p.getName()) || "tr".equals(p.getName()) || "row".equals(p.getName()))) appendLimited(out, "\n");
            if (out.length() >= MAX_TEXT_CHARS) break;
        }
        return out.toString();
    }

    private static XmlPullParser parser(Reader reader) throws Exception {
        XmlPullParser parser = Xml.newPullParser();
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
        parser.setInput(reader);
        return parser;
    }

    private static void appendLimited(StringBuilder out, String value) {
        if (value == null) return;
        int remaining = MAX_TEXT_CHARS - out.length();
        if (remaining > 0) out.append(value, 0, Math.min(remaining, value.length()));
    }

    private static String readTextFile(File file) throws Exception {
        StringBuilder text = new StringBuilder();
        boolean truncated = false;
        try (BufferedInputStream input = new BufferedInputStream(new FileInputStream(file))) {
            input.mark(4);
            int b1 = input.read(), b2 = input.read(), b3 = input.read();
            input.reset();
            Charset charset = StandardCharsets.UTF_8;
            int skip = 0;
            if (b1 == 0xFF && b2 == 0xFE) { charset = Charset.forName("UTF-16LE"); skip = 2; }
            else if (b1 == 0xFE && b2 == 0xFF) { charset = Charset.forName("UTF-16BE"); skip = 2; }
            else if (b1 == 0xEF && b2 == 0xBB && b3 == 0xBF) skip = 3;
            while (skip-- > 0) input.read();
            try (Reader reader = new InputStreamReader(input, charset)) {
                char[] chars = new char[8192];
                int count;
                while ((count = reader.read(chars)) != -1) {
                    int available = MAX_TEXT_CHARS - text.length();
                    if (available > 0) text.append(chars, 0, Math.min(available, count));
                    if (count > available) truncated = true;
                }
            }
        }
        if (truncated) text.append("\n（文档已完整上传；单轮分析受模型上下文限制，当前先读取前 10 万字）");
        return text.toString();
    }

    private static String displayName(Context c, Uri uri) {
        try (Cursor cursor = c.getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (column >= 0) return cursor.getString(column);
            }
        } catch (Throwable ignored) { }
        String path = uri.getLastPathSegment();
        return path == null || path.isEmpty() ? "文档" : path;
    }

    private static String extension(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static int slideNumber(String name) { return numericSuffix(name, "slide"); }
    private static int sheetNumber(String name) { return numericSuffix(name, "sheet"); }
    private static int numericSuffix(String name, String prefix) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(".*" + prefix + "([0-9]+)\\.xml").matcher(name);
        try { return m.matches() ? Integer.parseInt(m.group(1)) : Integer.MAX_VALUE; } catch (Throwable ignored) { return Integer.MAX_VALUE; }
    }

    private static String firstSentence(String text) {
        String clean = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        if (clean.isEmpty()) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(.{1,180}?[。！？!?])(?:\\s|$)").matcher(clean);
        String sentence = m.find() ? m.group(1) : clean.substring(0, Math.min(120, clean.length()));
        if (sentence.length() > 180) sentence = sentence.substring(0, 179) + "…";
        return sentence;
    }

    private static JSONObject error(String text) {
        JSONObject out = new JSONObject();
        try { out.put("ok", false); out.put("error", text); } catch (Throwable ignored) { }
        return out;
    }

    private static String safeMessage(Throwable t) {
        String value = t == null ? "" : t.getMessage();
        return value == null || value.isEmpty() ? "请检查文件权限和格式" : value;
    }
}
