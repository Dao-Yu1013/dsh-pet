package com.dshpet.android;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** 本地书架：TXT / EPUB / PDF / MOBI / AZW3，内容全部落在 S-G/books。 */
public final class Books {
    private static final Object LOCK = new Object();
    private static final int MAX_BYTES = 80 * 1024 * 1024;
    private static final int MAX_CHAPTERS = 6000;
    private static final Pattern TXT_HEAD = Pattern.compile(
            "^\\s*(第[0-9一二三四五六七八九十百千万零两]{1,10}[章节回卷集部篇][^\\n]{0,36}|Chapter\\s+\\d+[^\\n]{0,40}|CHAPTER\\s+\\d+[^\\n]{0,40})\\s*$",
            Pattern.CASE_INSENSITIVE);

    private Books() {
    }

    private static File dir(Context c) {
        File d = new File(Sg.dir(c), "books");
        if (!d.exists()) //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        return d;
    }

    private static File metaFile(Context c) {
        return Sg.file(c, "books.json");
    }

    private static File contentFile(Context c, String id) {
        return new File(dir(c), id + ".json");
    }

    private static JSONObject readContent(Context c, String id) {
        JSONObject o = Sg.readObj(c, "books/" + id + ".json");
        if (o.length() == 0) {
            // 兼容 2.3.0 早期导入的根目录内容文件
            JSONObject old = Sg.readObj(c, "book_content_" + id + ".json");
            if (old.length() > 0) {
                try {
                    Sg.writeObj(c, "books/" + id + ".json", old);
                    //noinspection ResultOfMethodCallIgnored
                    Sg.file(c, "book_content_" + id + ".json").delete();
                } catch (Throwable ignored) {
                }
                return old;
            }
        }
        return o;
    }

    private static File pdfFile(Context c, String id) {
        return new File(dir(c), id + ".pdf");
    }

    /* ---------------- 书架 ---------------- */

    public static JSONObject list(Context c) {
        JSONObject out = new JSONObject();
        try {
            JSONArray books = meta(c);
            JSONArray vis = new JSONArray();
            for (int k = 0; k < books.length(); k++) {
                JSONObject b = books.optJSONObject(k);
                if (b != null && !b.optBoolean("hidden", false)) vis.put(b);
            }
            out.put("books", books);
            out.put("visible", vis);
            out.put("all", books);
            out.put("stats", stats(c));
            out.put("config", readerCfg(c));
        } catch (Throwable t) {
            try {
                out.put("books", new JSONArray());
                out.put("stats", new JSONObject());
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    public static JSONObject get(Context c, String id) {
        JSONObject out = new JSONObject();
        try {
            JSONObject meta = find(meta(c), id);
            if (meta == null) return out;
            JSONObject content = readContent(c, id);
            JSONArray chapters = content.optJSONArray("chapters");
            if (chapters == null) chapters = new JSONArray();
            meta.put("chapters", chapters);
            meta.put("notes", notes(c, id));
            out = meta;
            out.put("ok", true);
        } catch (Throwable t) {
            try {
                out.put("ok", false);
                out.put("error", "打开失败");
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    public static synchronized JSONArray meta(Context c) {
        return Sg.readArr(c, "books.json");
    }

    private static void saveMeta(Context c, JSONArray a) {
        Sg.writeArr(c, "books.json", a);
    }

    private static JSONObject find(JSONArray a, String id) {
        for (int k = 0; k < a.length(); k++) {
            JSONObject o = a.optJSONObject(k);
            if (o != null && id.equals(o.optString("id"))) return o;
        }
        return null;
    }

    private static String newId() {
        return "b" + System.currentTimeMillis() + "_" + (int) (Math.random() * 999);
    }

    private static String day() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    /* ---------------- 导入 ---------------- */

    public static JSONObject importUri(Context c, Uri uri) {
        try {
            String name = displayName(c, uri);
            byte[] bytes = readUri(c, uri);
            if (bytes == null || bytes.length == 0) return err("读取文件失败");
            return importBytes(c, bytes, name, name, "file");
        } catch (Throwable t) {
            return err("导入失败：" + t.getClass().getSimpleName());
        }
    }

    public static JSONObject importFile(Context c, File f) {
        try {
            if (f == null || !f.isFile() || f.length() <= 0 || f.length() > MAX_BYTES) return err("文件不可读");
            byte[] bytes = readFile(f);
            return importBytes(c, bytes, f.getName(), f.getName(), "scan");
        } catch (Throwable t) {
            return err("导入失败");
        }
    }

    public static JSONObject importUrl(Context c, String raw) {
        try {
            String url = raw == null ? "" : raw.trim();
            if (!url.startsWith("http://") && !url.startsWith("https://")) return err("请输入 http/https 链接");
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) DSH-Pet/2.2.5");
            conn.setRequestProperty("Accept", "*/*");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) return err("下载失败 HTTP " + code);
            String type = conn.getContentType() == null ? "" : conn.getContentType().toLowerCase(Locale.ROOT);
            String disposition = conn.getHeaderField("Content-Disposition");
            String name = fileNameFromDisposition(disposition, url);
            byte[] bytes = readStream(conn.getInputStream(), MAX_BYTES);
            conn.disconnect();
            if (bytes == null || bytes.length == 0) return err("网页没有内容");
            String lower = name.toLowerCase(Locale.ROOT);
            boolean looksHtml = type.contains("text/html") && !lower.endsWith(".txt") && !lower.endsWith(".epub");
            if (looksHtml) return importHtml(c, bytes, name, url);
            return importBytes(c, bytes, name, name, "url");
        } catch (Throwable t) {
            return err("下载失败：" + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage()));
        }
    }

    private static JSONObject importHtml(Context c, byte[] bytes, String name, String url) {
        try {
            String html = decode(bytes);
            String title = tag(html, "title");
            if (title.isEmpty()) title = name.isEmpty() ? "网页文章" : name;
            String body = html;
            Matcher m = Pattern.compile("<(article|main)[^>]*>([\\s\\S]*?)</\\1>", Pattern.CASE_INSENSITIVE).matcher(html);
            if (m.find()) body = m.group(2);
            String text = stripHtml(body);
            if (text.length() < 80) text = stripHtml(html);
            if (text.length() < 20) return err("没有识别到可阅读的正文");
            JSONArray chapters = new JSONArray();
            JSONObject ch = new JSONObject();
            ch.put("title", title);
            ch.put("text", text);
            chapters.put(ch);
            return createBook(c, title, host(url), "WEB", chapters, null, text.length(), "url", url);
        } catch (Throwable t) {
            return err("网页解析失败");
        }
    }

    private static JSONObject importBytes(Context c, byte[] b, String fileName, String display, String source) {
        try {
            String name = display == null ? "" : display;
            String low = name.toLowerCase(Locale.ROOT);
            if (isZip(b)) return importEpub(c, b, fileName, source);
            if (isPdf(b) || low.endsWith(".pdf")) return importPdf(c, b, fileName, source);
            if (isPalm(b) || low.endsWith(".mobi") || low.endsWith(".azw3") || low.endsWith(".azw")) {
                return importMobi(c, b, name, low.endsWith(".azw3") ? "AZW3" : "MOBI", source);
            }
            String text = decode(b);
            if (text == null || text.trim().isEmpty()) return err("无法识别文本编码");
            String title = baseName(fileName);
            if (title.isEmpty()) title = "未命名书籍";
            JSONArray chapters = splitText(text);
            if (chapters.length() == 0) return err("这本书没有正文");
            return createBook(c, title, "", "TXT", chapters, null, text.length(), source, "");
        } catch (Throwable t) {
            return err("导入失败：" + t.getClass().getSimpleName());
        }
    }

    private static JSONObject importEpub(Context c, byte[] b, String fileName, String source) {
        File tmp = new File(dir(c), "tmp_" + System.currentTimeMillis() + ".epub");
        try {
            writeFile(tmp, b);
            ZipFile zip = new ZipFile(tmp);
            String opfPath = findOpf(zip);
            if (opfPath == null) {
                zip.close();
                return err("不是有效的 EPUB（缺少 container.xml）");
            }
            String opf = readZip(zip, opfPath);
            String base = opfPath.contains("/") ? opfPath.substring(0, opfPath.lastIndexOf('/') + 1) : "";
            JSONArray items = parseManifest(opf);
            String title = tag(opf, "dc:title");
            if (title.isEmpty()) title = tag(opf, "title");
            if (title.isEmpty()) title = baseName(fileName);
            String author = tag(opf, "dc:creator");
            List<String> spine = parseSpine(opf);
            Map<String, JSONObject> byId = new HashMap<>();
            for (int k = 0; k < items.length(); k++) {
                JSONObject it = items.optJSONObject(k);
                if (it != null) byId.put(it.optString("id"), it);
            }
            JSONArray chapters = new JSONArray();
            Map<String, String> toc = parseToc(zip, base, items);
            int chapterIndex = 0;
            for (String idref : spine) {
                JSONObject it = byId.get(idref);
                if (it == null) continue;
                String href = resolve(base, it.optString("href"));
                String html = readZip(zip, href);
                if (html == null) continue;
                String heading = heading(html);
                String text = stripHtml(html);
                if (text.trim().isEmpty()) continue;
                JSONObject ch = new JSONObject();
                String t = toc.get(href);
                if (t == null || t.trim().isEmpty()) t = heading;
                if (t == null || t.trim().isEmpty()) t = "第 " + (chapterIndex + 1) + " 章";
                ch.put("title", cleanTitle(t));
                ch.put("text", text);
                chapters.put(ch);
                chapterIndex++;
                if (chapters.length() > MAX_CHAPTERS) break;
            }
            String cover = null;
            String coverId = metaContent(opf, "cover");
            JSONObject coverItem = coverId == null ? null : byId.get(coverId);
            if (coverItem == null) {
                for (int k = 0; k < items.length(); k++) {
                    JSONObject it = items.optJSONObject(k);
                    if (it == null) continue;
                    String mt = it.optString("media-type");
                    if (mt.startsWith("image/") && (it.optString("id").contains("cover") || it.optString("href").contains("cover"))) {
                        coverItem = it;
                        break;
                    }
                }
            }
            if (coverItem != null) {
                byte[] img = readZipBytes(zip, resolve(base, coverItem.optString("href")));
                cover = coverData(img);
            }
            zip.close();
            if (chapters.length() == 0) return err("EPUB 没有解析到正文");
            return createBook(c, title, author, "EPUB", chapters, cover, textWords(chapters), source, "");
        } catch (Throwable t) {
            return err("EPUB 解析失败");
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    private static JSONObject importPdf(Context c, byte[] b, String fileName, String source) {
        File tmp = new File(dir(c), "tmp_" + System.currentTimeMillis() + ".pdf");
        try {
            writeFile(tmp, b);
            ParcelFileDescriptor pfd = ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY);
            PdfRenderer r = new PdfRenderer(pfd);
            int pages = r.getPageCount();
            JSONArray chapters = new JSONArray();
            for (int k = 0; k < pages; k++) {
                JSONObject ch = new JSONObject();
                ch.put("title", "第 " + (k + 1) + " 页");
                ch.put("page", k);
                chapters.put(ch);
            }
            String cover = null;
            try {
                cover = pdfCover(r);
            } catch (Throwable ignored) {
            }
            r.close();
            pfd.close();
            if (pages == 0) return err("PDF 没有页面");
            JSONObject out = createBook(c, baseName(fileName), "", "PDF", chapters, cover, 0, source, "");
            if (out.optBoolean("ok")) {
                String id = out.optString("book");
                File dst = pdfFile(c, id);
                writeFile(dst, b);
            }
            return out;
        } catch (Throwable t) {
            return err("PDF 解析失败");
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    private static JSONObject importMobi(Context c, byte[] b, String name, String format, String source) {
        try {
            String text = mobiText(b);
            if (text == null || text.trim().length() < 20) return err("MOBI/AZW3 暂不支持该压缩格式");
            JSONArray chapters = splitText(text);
            if (chapters.length() == 0) return err("没有解析到正文");
            String title = baseName(name);
            if (title.isEmpty()) title = pdbName(b);
            return createBook(c, title, "", format, chapters, null, text.length(), source, "");
        } catch (Throwable t) {
            return err("MOBI/AZW3 解析失败");
        }
    }

    private static JSONObject createBook(Context c, String title, String author, String format,
                                         JSONArray chapters, String cover, int words, String source, String url) {
        try {
            String id = newId();
            JSONObject content = new JSONObject();
            content.put("id", id);
            content.put("chapters", chapters);
            Sg.writeObj(c, "books/" + id + ".json", content);

            JSONObject m = new JSONObject();
            m.put("id", id);
            m.put("title", title == null || title.trim().isEmpty() ? "未命名书籍" : title.trim());
            m.put("author", author == null ? "" : author.trim());
            m.put("format", format);
            m.put("source", source == null ? "file" : source);
            m.put("url", url == null ? "" : url);
            m.put("cover", cover == null ? "" : cover);
            m.put("chapters", chapters.length());
            m.put("words", Math.max(0, words));
            m.put("addedAt", System.currentTimeMillis());
            m.put("lastReadAt", 0L);
            m.put("group", "");
            m.put("tags", new JSONArray());
            m.put("hidden", false);
            m.put("finished", false);
            m.put("readingSeconds", 0);
            JSONObject p = new JSONObject();
            p.put("chapter", 0);
            p.put("percent", 0);
            m.put("progress", p);
            synchronized (LOCK) {
                JSONArray a = meta(c);
                a.put(m);
                saveMeta(c, a);
            }
            JSONObject out = new JSONObject();
            out.put("ok", true);
            out.put("book", id);
            out.put("meta", m);
            Bus.emit("books", new JSONObject().put("count", meta(c).length()));
            return out;
        } catch (Throwable t) {
            return err("保存失败");
        }
    }

    public static JSONObject setCover(Context c, String id, Uri uri) {
        try {
            byte[] bytes = readUri(c, uri);
            String cover = coverData(bytes);
            if (cover.isEmpty()) return err("图片无法识别");
            return update(c, id, new JSONObject().put("cover", cover));
        } catch (Throwable t) {
            return err("封面保存失败");
        }
    }

    public static JSONObject update(Context c, String id, JSONObject patch) {
        try {
            synchronized (LOCK) {
                JSONArray a = meta(c);
                JSONObject m = find(a, id);
                if (m == null) return err("书不存在");
                if (patch.has("title")) m.put("title", patch.optString("title"));
                if (patch.has("author")) m.put("author", patch.optString("author"));
                if (patch.has("group")) m.put("group", patch.optString("group"));
                if (patch.has("tags")) m.put("tags", patch.optJSONArray("tags"));
                if (patch.has("hidden")) m.put("hidden", patch.optBoolean("hidden"));
                if (patch.has("finished")) m.put("finished", patch.optBoolean("finished"));
                if (patch.has("cover")) m.put("cover", patch.optString("cover"));
                saveMeta(c, a);
            }
            return ok();
        } catch (Throwable t) {
            return err("保存失败");
        }
    }

    public static JSONObject delete(Context c, String id) {
        try {
            synchronized (LOCK) {
                JSONArray a = meta(c);
                JSONArray b = new JSONArray();
                for (int k = 0; k < a.length(); k++) {
                    JSONObject o = a.optJSONObject(k);
                    if (o != null && !id.equals(o.optString("id"))) b.put(o);
                }
                saveMeta(c, b);
            }
            //noinspection ResultOfMethodCallIgnored
            contentFile(c, id).delete();
            //noinspection ResultOfMethodCallIgnored
            Sg.file(c, "book_content_" + id + ".json").delete();
            //noinspection ResultOfMethodCallIgnored
            pdfFile(c, id).delete();
            removeNotes(c, id);
            return ok();
        } catch (Throwable t) {
            return err("删除失败");
        }
    }

    /* ---------------- 进度、统计、笔记 ---------------- */

    public static JSONObject progress(Context c, String id, int chapter, double percent, double scroll, int seconds) {
        try {
            synchronized (LOCK) {
                JSONArray a = meta(c);
                JSONObject m = find(a, id);
                if (m == null) return err("书不存在");
                JSONObject p = m.optJSONObject("progress");
                if (p == null) p = new JSONObject();
                p.put("chapter", Math.max(0, chapter));
                p.put("percent", Math.max(0, Math.min(1, percent)));
                p.put("scroll", Math.max(0, Math.min(1, scroll)));
                p.put("ts", System.currentTimeMillis());
                m.put("progress", p);
                m.put("lastReadAt", System.currentTimeMillis());
                m.put("readingSeconds", m.optInt("readingSeconds", 0) + Math.max(0, Math.min(3600, seconds)));
                if (percent >= 0.995) m.put("finished", true);
                saveMeta(c, a);
                readingStats(c, Math.max(0, Math.min(3600, seconds)));
                // 应用感知：她记得你在看哪本书、看到第几章
                InApp.track(c, "阅读", "看书", m.optString("title", "")
                        + " · 第 " + (Math.max(0, chapter) + 1) + " 章");
            }
            return ok();
        } catch (Throwable t) {
            return err("进度保存失败");
        }
    }

    private static void readingStats(Context c, int seconds) {
        try {
            JSONObject s = Sg.readObj(c, "reading.json");
            String today = day();
            JSONObject days = s.optJSONObject("days");
            if (days == null) days = new JSONObject();
            days.put(today, days.optInt(today, 0) + seconds);
            String last = s.optString("lastDay", "");
            int streak = s.optInt("streak", 0);
            if (!today.equals(last)) {
                String yesterday = new SimpleDateFormat("yyyy-MM-dd", Locale.US)
                        .format(new Date(System.currentTimeMillis() - 86400000L));
                streak = yesterday.equals(last) ? streak + 1 : 1;
                s.put("lastDay", today);
            }
            s.put("days", days);
            s.put("streak", streak);
            s.put("totalSeconds", s.optInt("totalSeconds", 0) + seconds);
            Sg.writeObj(c, "reading.json", s);
        } catch (Throwable ignored) {
        }
    }

    public static JSONObject stats(Context c) {
        JSONObject out = new JSONObject();
        try {
            JSONArray a = meta(c);
            int total = 0, finished = 0, words = 0, readSec = 0;
            for (int k = 0; k < a.length(); k++) {
                JSONObject m = a.optJSONObject(k);
                if (m == null || m.optBoolean("hidden", false)) continue;
                total++;
                if (m.optBoolean("finished", false)) finished++;
                words += m.optInt("words", 0);
                readSec += m.optInt("readingSeconds", 0);
            }
            JSONObject r = Sg.readObj(c, "reading.json");
            out.put("total", total);
            out.put("finished", finished);
            out.put("words", words);
            out.put("seconds", readSec);
            out.put("streak", r.optInt("streak", 0));
            out.put("totalSeconds", r.optInt("totalSeconds", readSec));
            out.put("days", r.optJSONObject("days") == null ? new JSONObject() : r.optJSONObject("days"));
        } catch (Throwable ignored) {
        }
        return out;
    }

    public static JSONArray notes(Context c, String bookId) {
        JSONArray out = new JSONArray();
        try {
            JSONArray a = Sg.readArr(c, "book_notes.json");
            for (int k = 0; k < a.length(); k++) {
                JSONObject o = a.optJSONObject(k);
                if (o != null && (bookId == null || bookId.isEmpty() || bookId.equals(o.optString("bookId")))) out.put(o);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    public static JSONObject addNote(Context c, String bookId, int chapter, String type, String quote, String note, int offset) {
        try {
            JSONArray a = Sg.readArr(c, "book_notes.json");
            JSONObject o = new JSONObject();
            o.put("id", "n" + System.currentTimeMillis() + "_" + (int) (Math.random() * 999));
            o.put("bookId", bookId);
            o.put("chapter", chapter);
            o.put("type", type);
            o.put("quote", quote == null ? "" : quote);
            o.put("note", note == null ? "" : note);
            o.put("offset", offset);
            o.put("ts", System.currentTimeMillis());
            a.put(o);
            Sg.writeArr(c, "book_notes.json", a);
            return ok().put("note", o);
        } catch (Throwable t) {
            return err("保存笔记失败");
        }
    }

    public static JSONObject deleteNote(Context c, String noteId) {
        try {
            JSONArray a = Sg.readArr(c, "book_notes.json");
            JSONArray b = new JSONArray();
            for (int k = 0; k < a.length(); k++) {
                JSONObject o = a.optJSONObject(k);
                if (o != null && !noteId.equals(o.optString("id"))) b.put(o);
            }
            Sg.writeArr(c, "book_notes.json", b);
            return ok();
        } catch (Throwable t) {
            return err("删除失败");
        }
    }

    private static void removeNotes(Context c, String bookId) {
        try {
            JSONArray a = Sg.readArr(c, "book_notes.json");
            JSONArray b = new JSONArray();
            for (int k = 0; k < a.length(); k++) {
                JSONObject o = a.optJSONObject(k);
                if (o != null && !bookId.equals(o.optString("bookId"))) b.put(o);
            }
            Sg.writeArr(c, "book_notes.json", b);
        } catch (Throwable ignored) {
        }
    }

    /* ---------------- 阅读设置 ---------------- */

    public static JSONObject readerCfg(Context c) {
        JSONObject o = Sg.readObj(c, "reader.json");
        try {
            if (!o.has("fontSize")) o.put("fontSize", 18);
            if (!o.has("lineHeight")) o.put("lineHeight", 1.85);
            if (!o.has("paraSpace")) o.put("paraSpace", 12);
            if (!o.has("margin")) o.put("margin", 22);
            if (!o.has("align")) o.put("align", "justify");
            if (!o.has("font")) o.put("font", "serif");
            if (!o.has("theme")) o.put("theme", "night");
            if (!o.has("paged")) o.put("paged", false);
            if (!o.has("anim")) o.put("anim", "slide");
            if (!o.has("brightness")) o.put("brightness", 1.0);
            if (!o.has("traditional")) o.put("traditional", false);
            if (!o.has("autoSec")) o.put("autoSec", 25);
            if (!o.has("ttsRate")) o.put("ttsRate", 1.0);
        } catch (Throwable ignored) {
        }
        return o;
    }

    public static JSONObject saveReaderCfg(Context c, JSONObject o) {
        try {
            JSONObject cur = readerCfg(c);
            if (o != null) {
                Iterator<String> it = o.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    cur.put(k, o.opt(k));
                }
            }
            Sg.writeObj(c, "reader.json", cur);
            return ok().put("config", cur);
        } catch (Throwable t) {
            return err("设置保存失败");
        }
    }

    /* ---------------- PDF 页 ---------------- */

    public static JSONObject page(Context c, String id, int index) {
        JSONObject out = new JSONObject();
        try {
            File f = pdfFile(c, id);
            if (!f.isFile()) return err("PDF 文件不存在");
            ParcelFileDescriptor pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
            PdfRenderer r = new PdfRenderer(pfd);
            int total = r.getPageCount();
            int n = Math.max(0, Math.min(total - 1, index));
            PdfRenderer.Page p = r.openPage(n);
            int width = 1080;
            int height = Math.max(1, (int) (width * (double) p.getHeight() / Math.max(1, p.getWidth())));
            Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bmp);
            canvas.drawColor(Color.WHITE);
            p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            p.close();
            r.close();
            pfd.close();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.JPEG, 88, bo);
            bmp.recycle();
            out.put("ok", true);
            out.put("img", "data:image/jpeg;base64," + Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP));
            out.put("page", n);
            out.put("pages", total);
            return out;
        } catch (Throwable t) {
            return err("PDF 渲染失败");
        }
    }

    private static String pdfCover(PdfRenderer r) {
        PdfRenderer.Page p = r.openPage(0);
        int width = 300;
        int height = Math.max(1, (int) (width * (double) p.getHeight() / Math.max(1, p.getWidth())));
        Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        canvas.drawColor(Color.WHITE);
        p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
        p.close();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.JPEG, 82, bo);
        bmp.recycle();
        return "data:image/jpeg;base64," + Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP);
    }

    /* ---------------- 导出 / 恢复 ---------------- */

    public static void exportBook(Context c, String id, OutputStream out) throws Exception {
        JSONObject meta = find(meta(c), id);
        if (meta == null) throw new Exception("书不存在");
        JSONObject content = readContent(c, id);
        meta.put("chaptersData", content.optJSONArray("chapters"));
        ZipOutputStream z = new ZipOutputStream(out);
        z.putNextEntry(new ZipEntry("book.json"));
        z.write(meta.toString().getBytes(StandardCharsets.UTF_8));
        z.closeEntry();
        if ("PDF".equals(meta.optString("format"))) {
            File f = pdfFile(c, id);
            if (f.isFile()) {
                z.putNextEntry(new ZipEntry("source.pdf"));
                try (InputStream in = new FileInputStream(f)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) z.write(buf, 0, n);
                }
                z.closeEntry();
            }
        }
        z.finish();
    }

    public static void exportNotes(Context c, String bookId, OutputStream out) throws Exception {
        JSONObject m = find(meta(c), bookId);
        JSONArray notes = notes(c, bookId);
        StringBuilder sb = new StringBuilder();
        sb.append("《").append(m == null ? "书籍" : m.optString("title", "")).append("》笔记\n\n");
        for (int k = 0; k < notes.length(); k++) {
            JSONObject n = notes.optJSONObject(k);
            if (n == null) continue;
            String type = "bookmark".equals(n.optString("type")) ? "书签"
                    : "highlight".equals(n.optString("type")) ? "划线" : "笔记";
            sb.append("[").append(type).append("] 第 ").append(n.optInt("chapter", 0) + 1).append(" 章\n");
            if (!n.optString("quote").isEmpty()) sb.append("摘录：").append(n.optString("quote")).append('\n');
            if (!n.optString("note").isEmpty()) sb.append("想法：").append(n.optString("note")).append('\n');
            sb.append("时间：").append(n.optLong("ts", 0)).append("\n\n");
        }
        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    public static void exportAll(Context c, OutputStream out) throws Exception {
        JSONObject all = new JSONObject();
        JSONArray metas = meta(c);
        JSONArray items = new JSONArray();
        for (int k = 0; k < metas.length(); k++) {
            JSONObject m = metas.optJSONObject(k);
            if (m == null) continue;
            JSONObject x = new JSONObject(m.toString());
            JSONObject content = readContent(c, m.optString("id"));
            x.put("chaptersData", content.optJSONArray("chapters"));
            items.put(x);
        }
        all.put("books", items);
        all.put("notes", Sg.readArr(c, "book_notes.json"));
        all.put("reading", Sg.readObj(c, "reading.json"));
        ZipOutputStream z = new ZipOutputStream(out);
        z.putNextEntry(new ZipEntry("bookshelf.json"));
        z.write(all.toString().getBytes(StandardCharsets.UTF_8));
        z.closeEntry();
        for (int k = 0; k < metas.length(); k++) {
            JSONObject m = metas.optJSONObject(k);
            if (m == null || !"PDF".equals(m.optString("format"))) continue;
            File f = pdfFile(c, m.optString("id"));
            if (!f.isFile()) continue;
            z.putNextEntry(new ZipEntry("pdf_" + m.optString("id") + ".pdf"));
            try (InputStream in = new FileInputStream(f)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) z.write(buf, 0, n);
            }
            z.closeEntry();
        }
        z.finish();
    }

    public static JSONObject restore(Context c, InputStream in) {
        int count = 0;
        String pendingPdf = "";
        try {
            ZipInputStream z = new ZipInputStream(in);
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = z.read(buf)) > 0) bo.write(buf, 0, n);
                String name = e.getName();
                if (name.startsWith("pdf_") && name.endsWith(".pdf")) {
                    String id = name.substring(4, name.length() - 4);
                    writeFile(pdfFile(c, id), bo.toByteArray());
                    continue;
                }
                if ("source.pdf".equals(name) && !pendingPdf.isEmpty()) {
                    writeFile(pdfFile(c, pendingPdf), bo.toByteArray());
                    continue;
                }
                String text = new String(bo.toByteArray(), StandardCharsets.UTF_8);
                if ("book.json".equals(name)) {
                    JSONObject b = new JSONObject(text);
                    pendingPdf = b.optString("id", "");
                    importArchived(c, b);
                    count++;
                } else if ("bookshelf.json".equals(name)) {
                    JSONObject all = new JSONObject(text);
                    JSONArray books = all.optJSONArray("books");
                    if (books != null) for (int k = 0; k < books.length(); k++) {
                        JSONObject b = books.optJSONObject(k);
                        if (b != null) {
                            importArchived(c, b);
                            count++;
                        }
                    }
                    JSONArray notes = all.optJSONArray("notes");
                    if (notes != null) {
                        JSONArray cur = Sg.readArr(c, "book_notes.json");
                        for (int k = 0; k < notes.length(); k++) cur.put(notes.opt(k));
                        Sg.writeArr(c, "book_notes.json", cur);
                    }
                }
            }
            return ok().put("count", count);
        } catch (Throwable t) {
            return err("恢复失败");
        }
    }

    private static void importArchived(Context c, JSONObject b) throws Exception {
        String id = b.optString("id", newId());
        b.put("id", id);
        JSONObject content = new JSONObject();
        content.put("id", id);
        content.put("chapters", b.optJSONArray("chaptersData") == null ? new JSONArray() : b.optJSONArray("chaptersData"));
        Sg.writeObj(c, "books/" + id + ".json", content);
        b.remove("chaptersData");
        synchronized (LOCK) {
            JSONArray a = meta(c);
            JSONObject old = find(a, id);
            if (old != null) {
                for (Iterator<String> it = b.keys(); it.hasNext(); ) {
                    String k = it.next();
                    old.put(k, b.opt(k));
                }
            } else {
                a.put(b);
            }
            saveMeta(c, a);
        }
    }

    public static JSONObject scan(Context c) {
        JSONArray found = new JSONArray();
        int imported = 0, failed = 0;
        try {
            List<File> dirs = new ArrayList<>();
            dirs.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS));
            dirs.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS));
            dirs.add(new File(Environment.getExternalStorageDirectory(), "Books"));
            List<File> files = new ArrayList<>();
            for (File d : dirs) collect(d, files, 0);
            for (File f : files) {
                if (files.size() > 400) break;
                JSONObject r = importFile(c, f);
                if (r.optBoolean("ok")) {
                    imported++;
                    found.put(r.optJSONObject("meta"));
                } else failed++;
            }
            return ok().put("imported", imported).put("failed", failed).put("books", found);
        } catch (Throwable t) {
            return err("扫描失败");
        }
    }

    private static void collect(File d, List<File> out, int depth) {
        if (d == null || !d.isDirectory() || depth > 3) return;
        File[] fs = d.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) {
                if (!"S-G".equals(f.getName())) collect(f, out, depth + 1);
            } else if (isBookName(f.getName()) && f.length() > 0 && f.length() <= MAX_BYTES) {
                out.add(f);
            }
        }
    }

    private static boolean isBookName(String n) {
        String s = n.toLowerCase(Locale.ROOT);
        return s.endsWith(".txt") || s.endsWith(".epub") || s.endsWith(".pdf")
                || s.endsWith(".mobi") || s.endsWith(".azw3") || s.endsWith(".azw");
    }

    /* ---------------- 解析辅助 ---------------- */

    private static JSONArray splitText(String text) throws Exception {
        String s = text.replace("\r\n", "\n").replace('\r', '\n').replace("\u0000", "");
        JSONArray chapters = new JSONArray();
        StringBuilder cur = new StringBuilder();
        String title = "";
        String[] lines = s.split("\n", -1);
        for (String line : lines) {
            String t = line.trim();
            if (TXT_HEAD.matcher(t).matches() && cur.length() > 40) {
                JSONObject ch = new JSONObject();
                ch.put("title", title.isEmpty() ? "第 " + (chapters.length() + 1) + " 章" : title);
                ch.put("text", cur.toString().trim());
                chapters.put(ch);
                cur.setLength(0);
                title = t;
                if (chapters.length() > MAX_CHAPTERS) break;
            } else {
                if (title.isEmpty() && TXT_HEAD.matcher(t).matches()) title = t;
                cur.append(line).append('\n');
            }
        }
        if (cur.toString().trim().length() > 0) {
            JSONObject ch = new JSONObject();
            ch.put("title", title.isEmpty() ? "正文" : title);
            ch.put("text", cur.toString().trim());
            chapters.put(ch);
        }
        if (chapters.length() <= 1 && s.length() > 8000) {
            chapters = chunk(s);
        }
        return chapters;
    }

    private static JSONArray chunk(String s) throws Exception {
        JSONArray out = new JSONArray();
        int pos = 0, idx = 1;
        while (pos < s.length()) {
            int end = Math.min(s.length(), pos + 5000);
            if (end < s.length()) {
                int nl = s.lastIndexOf('\n', end);
                if (nl > pos + 2000) end = nl;
            }
            JSONObject ch = new JSONObject();
            ch.put("title", "第 " + idx + " 节");
            ch.put("text", s.substring(pos, end).trim());
            out.put(ch);
            pos = end;
            idx++;
            if (out.length() > MAX_CHAPTERS) break;
        }
        return out;
    }

    private static String decode(byte[] b) {
        if (b == null || b.length == 0) return "";
        try {
            if (b.length > 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF)
                return new String(b, 3, b.length - 3, StandardCharsets.UTF_8);
            if (b.length > 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xFE)
                return new String(b, 2, b.length - 2, StandardCharsets.UTF_16LE);
            if (b.length > 2 && (b[0] & 0xFF) == 0xFE && (b[1] & 0xFF) == 0xFF)
                return new String(b, 2, b.length - 2, StandardCharsets.UTF_16BE);
        } catch (Throwable ignored) {
        }
        String[] names = {"UTF-8", "GB18030", "GBK", "Big5", "UTF-16LE"};
        for (String n : names) {
            try {
                CharsetDecoder d = Charset.forName(n).newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT);
                return d.decode(ByteBuffer.wrap(b)).toString();
            } catch (CharacterCodingException ignored) {
            } catch (Throwable ignored) {
            }
        }
        return new String(b, StandardCharsets.UTF_8);
    }

    private static boolean isZip(byte[] b) {
        return b != null && b.length > 4 && (b[0] & 0xFF) == 0x50 && (b[1] & 0xFF) == 0x4B;
    }

    private static boolean isPdf(byte[] b) {
        return b != null && b.length > 4 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F';
    }

    private static boolean isPalm(byte[] b) {
        if (b == null || b.length < 80) return false;
        try {
            String type = new String(b, 60, 8, StandardCharsets.US_ASCII);
            return "BOOKMOBI".equals(type) || "TEXtREAd".equals(type) || new String(b, 0, 32, StandardCharsets.US_ASCII).contains("MOBI");
        } catch (Throwable t) {
            return false;
        }
    }

    private static String findOpf(ZipFile zip) {
        String container = readZip(zip, "META-INF/container.xml");
        if (container == null) return null;
        Matcher m = Pattern.compile("full-path\\s*=\\s*\"([^\"]+)\"").matcher(container);
        return m.find() ? m.group(1) : null;
    }

    private static JSONArray parseManifest(String opf) {
        JSONArray out = new JSONArray();
        Matcher m = Pattern.compile("<item\\b[^>]*>", Pattern.CASE_INSENSITIVE).matcher(opf);
        while (m.find()) {
            try {
                JSONObject o = new JSONObject();
                o.put("id", attr(m.group(), "id"));
                o.put("href", attr(m.group(), "href"));
                o.put("media-type", attr(m.group(), "media-type"));
                out.put(o);
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    private static List<String> parseSpine(String opf) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("<itemref\\b[^>]*>", Pattern.CASE_INSENSITIVE).matcher(opf);
        while (m.find()) {
            String id = attr(m.group(), "idref");
            if (!id.isEmpty()) out.add(id);
        }
        return out;
    }

    private static Map<String, String> parseToc(ZipFile zip, String base, JSONArray items) {
        Map<String, String> out = new HashMap<>();
        String nav = null;
        for (int k = 0; k < items.length(); k++) {
            JSONObject it = items.optJSONObject(k);
            if (it == null) continue;
            String href = it.optString("href");
            String id = it.optString("id");
            if (href.endsWith("nav.xhtml") || href.endsWith("toc.xhtml") || href.endsWith(".ncx") || id.equals("nav") || id.equals("ncx")) {
                nav = resolve(base, href);
                break;
            }
        }
        if (nav == null) return out;
        String text = readZip(zip, nav);
        if (text == null) return out;
        Matcher a = Pattern.compile("<a\\b[^>]*href\\s*=\\s*\"([^\"]+)\"[^>]*>([\\s\\S]*?)</a>", Pattern.CASE_INSENSITIVE).matcher(text);
        while (a.find()) out.put(resolve(base, a.group(1).split("#")[0]), stripHtml(a.group(2)));
        Matcher n = Pattern.compile("<navPoint\\b[\\s\\S]*?<text>([\\s\\S]*?)</text>[\\s\\S]*?<content\\b[^>]*src\\s*=\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE).matcher(text);
        while (n.find()) out.put(resolve(base, n.group(2).split("#")[0]), stripHtml(n.group(1)));
        return out;
    }

    private static String heading(String html) {
        String h = tag(html, "h1");
        if (h.isEmpty()) h = tag(html, "h2");
        if (h.isEmpty()) h = tag(html, "h3");
        if (h.isEmpty()) h = tag(html, "title");
        return cleanTitle(h);
    }

    private static String stripHtml(String html) {
        if (html == null) return "";
        String s = html;
        s = s.replaceAll("(?is)<script[^>]*>.*?</script>", " ");
        s = s.replaceAll("(?is)<style[^>]*>.*?</style>", " ");
        s = s.replaceAll("(?is)<!--.*?-->", " ");
        s = s.replaceAll("(?i)<br\\s*/?>", "\n");
        s = s.replaceAll("(?i)</(p|div|h[1-6]|li|tr|section|article|blockquote)>", "\n");
        s = s.replaceAll("(?is)<[^>]+>", " ");
        s = entity(s);
        s = s.replace('\u00A0', ' ');
        s = s.replaceAll("[ \\t\\x0B\\f\\r]+", " ");
        s = s.replaceAll(" *\\n *", "\n");
        s = s.replaceAll("\\n{3,}", "\n\n");
        return s.trim();
    }

    private static String entity(String s) {
        String x = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&apos;", "'").replace("&mdash;", "—").replace("&ndash;", "–")
                .replace("&hellip;", "…").replace("&ldquo;", "“").replace("&rdquo;", "”");
        try {
            Matcher m = Pattern.compile("&#(x?[0-9a-fA-F]+);").matcher(x);
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                String v = m.group(1);
                int code = v.startsWith("x") || v.startsWith("X")
                        ? Integer.parseInt(v.substring(1), 16) : Integer.parseInt(v);
                m.appendReplacement(sb, new String(Character.toChars(code)));
            }
            m.appendTail(sb);
            return sb.toString();
        } catch (Throwable t) {
            return x;
        }
    }

    private static String tag(String html, String name) {
        if (html == null) return "";
        Matcher m = Pattern.compile("<" + Pattern.quote(name) + "[^>]*>([\\s\\S]*?)</" + Pattern.quote(name) + ">", Pattern.CASE_INSENSITIVE).matcher(html);
        return m.find() ? entity(m.group(1).replaceAll("<[^>]+>", " ")).trim() : "";
    }

    private static String cleanTitle(String s) {
        String t = s == null ? "" : s.replaceAll("\\s+", " ").trim();
        if (t.length() > 80) t = t.substring(0, 80);
        return t;
    }

    private static String attr(String tag, String key) {
        Matcher m = Pattern.compile(Pattern.quote(key) + "\\s*=\\s*\"([^\"]*)\"", Pattern.CASE_INSENSITIVE).matcher(tag);
        return m.find() ? m.group(1) : "";
    }

    private static String metaContent(String opf, String name) {
        Matcher m = Pattern.compile("<meta\\b[^>]*name\\s*=\\s*\"" + Pattern.quote(name) + "\"[^>]*content\\s*=\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE).matcher(opf);
        if (m.find()) return m.group(1);
        Matcher r = Pattern.compile("<meta\\b[^>]*content\\s*=\\s*\"([^\"]+)\"[^>]*name\\s*=\\s*\"" + Pattern.quote(name) + "\"", Pattern.CASE_INSENSITIVE).matcher(opf);
        return r.find() ? r.group(1) : null;
    }

    private static String resolve(String base, String href) {
        if (href == null) return "";
        String h = href;
        if (h.startsWith("/")) return h.substring(1);
        String out = base + h;
        while (out.contains("/../")) out = out.replaceFirst("/[^/]+/\\.\\./", "/");
        return out.replace("/./", "/");
    }

    private static String readZip(ZipFile zip, String name) {
        try {
            ZipEntry e = zip.getEntry(name);
            if (e == null) return null;
            try (InputStream in = zip.getInputStream(e)) {
                return new String(readStream(in, MAX_BYTES), StandardCharsets.UTF_8);
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private static byte[] readZipBytes(ZipFile zip, String name) {
        try {
            ZipEntry e = zip.getEntry(name);
            if (e == null) return null;
            try (InputStream in = zip.getInputStream(e)) {
                return readStream(in, MAX_BYTES);
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private static String coverData(byte[] img) {
        if (img == null || img.length == 0) return "";
        try {
            Bitmap bmp = BitmapFactory.decodeByteArray(img, 0, img.length);
            if (bmp == null) return "";
            int w = Math.min(300, bmp.getWidth());
            int h = Math.max(1, (int) (w * (double) bmp.getHeight() / Math.max(1, bmp.getWidth())));
            Bitmap scaled = Bitmap.createScaledBitmap(bmp, w, h, true);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            scaled.compress(Bitmap.CompressFormat.JPEG, 82, bo);
            if (scaled != bmp) scaled.recycle();
            bmp.recycle();
            return "data:image/jpeg;base64," + Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP);
        } catch (Throwable t) {
            return "";
        }
    }

    private static int textWords(JSONArray chapters) {
        int n = 0;
        for (int k = 0; k < chapters.length(); k++) {
            JSONObject c = chapters.optJSONObject(k);
            if (c != null) n += c.optString("text").length();
        }
        return n;
    }

    private static String mobiText(byte[] b) {
        try {
            int nrec = ((b[76] & 0xFF) << 8) | (b[77] & 0xFF);
            if (nrec <= 1) return null;
            int[] off = new int[nrec];
            for (int i = 0; i < nrec; i++) {
                int p = 78 + i * 8;
                off[i] = ((b[p] & 0xFF) << 24) | ((b[p + 1] & 0xFF) << 16)
                        | ((b[p + 2] & 0xFF) << 8) | (b[p + 3] & 0xFF);
                if (off[i] < 0 || off[i] >= b.length) return null;
            }
            int rec0 = off[0];
            int compression = ((b[rec0] & 0xFF) << 8) | (b[rec0 + 1] & 0xFF);
            int textLength = ((b[rec0 + 4] & 0xFF) << 24) | ((b[rec0 + 5] & 0xFF) << 16)
                    | ((b[rec0 + 6] & 0xFF) << 8) | (b[rec0 + 7] & 0xFF);
            int records = ((b[rec0 + 8] & 0xFF) << 8) | (b[rec0 + 9] & 0xFF);
            int encoding = 1252;
            if (rec0 + 16 + 12 < b.length) {
                encoding = ((b[rec0 + 16 + 12] & 0xFF) << 8) | (b[rec0 + 16 + 13] & 0xFF);
            }
            if (compression == 17480) return null;
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            for (int i = 1; i <= records && i < nrec; i++) {
                int start = off[i];
                int end = i + 1 < nrec ? off[i + 1] : b.length;
                if (start < 0 || end <= start || end > b.length) continue;
                byte[] rec = new byte[end - start];
                System.arraycopy(b, start, rec, 0, rec.length);
                if (compression == 2) bo.write(palmDecompress(rec));
                else bo.write(rec);
            }
            byte[] raw = bo.toByteArray();
            if (textLength > 0 && textLength < raw.length) {
                byte[] cut = new byte[textLength];
                System.arraycopy(raw, 0, cut, 0, textLength);
                raw = cut;
            }
            String s = encoding == 65001 ? new String(raw, StandardCharsets.UTF_8) : new String(raw, Charset.forName("windows-1252"));
            return stripHtml(s);
        } catch (Throwable t) {
            return null;
        }
    }

    private static byte[] palmDecompress(byte[] in) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int i = 0;
        while (i < in.length) {
            int c = in[i++] & 0xFF;
            if (c == 0) {
                out.write(0);
            } else if (c >= 1 && c <= 8) {
                for (int k = 0; k < c && i < in.length; k++) out.write(in[i++]);
            } else if (c >= 0x09 && c <= 0x7F) {
                out.write(c);
            } else if (c >= 0xC0) {
                out.write(' ');
                out.write(c ^ 0x80);
            } else if (i < in.length) {
                int d = in[i++] & 0xFF;
                int pair = (c << 8) | d;
                int dist = (pair >> 3) & 0x7FF;
                int len = (pair & 7) + 3;
                byte[] buf = out.toByteArray();
                int pos = buf.length - dist;
                for (int k = 0; k < len; k++) {
                    if (pos + k >= 0 && pos + k < buf.length) out.write(buf[pos + k]);
                    else out.write(0);
                }
            }
        }
        return out.toByteArray();
    }

    private static String pdbName(byte[] b) {
        try {
            String s = new String(b, 0, 32, StandardCharsets.US_ASCII);
            int z = s.indexOf('\u0000');
            if (z >= 0) s = s.substring(0, z);
            return s.trim();
        } catch (Throwable t) {
            return "";
        }
    }

    private static String baseName(String name) {
        if (name == null) return "";
        String s = name.trim();
        int slash = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
        if (slash >= 0) s = s.substring(slash + 1);
        int dot = s.lastIndexOf('.');
        if (dot > 0) s = s.substring(0, dot);
        return s.trim();
    }

    private static String fileNameFromDisposition(String disposition, String url) {
        try {
            if (disposition != null) {
                Matcher m = Pattern.compile("filename\\*?=\"?([^\";]+)\"?", Pattern.CASE_INSENSITIVE).matcher(disposition);
                if (m.find()) return java.net.URLDecoder.decode(m.group(1).replace("UTF-8''", ""), "UTF-8");
            }
            String path = new URL(url).getPath();
            String n = path.substring(path.lastIndexOf('/') + 1);
            return n.isEmpty() ? "网页书籍" : n;
        } catch (Throwable t) {
            return "网页书籍";
        }
    }

    private static String host(String url) {
        try {
            String h = new URL(url).getHost();
            return h == null ? "" : h;
        } catch (Throwable t) {
            return "";
        }
    }

    private static String displayName(Context c, Uri uri) {
        try (android.database.Cursor cur = c.getContentResolver().query(uri, null, null, null, null)) {
            if (cur != null && cur.moveToFirst()) {
                int idx = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    String n = cur.getString(idx);
                    if (n != null && !n.trim().isEmpty()) return n;
                }
            }
        } catch (Throwable ignored) {
        }
        String s = uri.getLastPathSegment();
        return s == null ? "未命名" : s;
    }

    private static byte[] readUri(Context c, Uri uri) {
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            return readStream(in, MAX_BYTES);
        } catch (Throwable t) {
            return null;
        }
    }

    private static byte[] readFile(File f) {
        try (InputStream in = new FileInputStream(f)) {
            return readStream(in, MAX_BYTES);
        } catch (Throwable t) {
            return null;
        }
    }

    private static byte[] readStream(InputStream in, int max) throws Exception {
        if (in == null) return null;
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        int total = 0;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > max) throw new Exception("文件过大");
            bo.write(buf, 0, n);
        }
        return bo.toByteArray();
    }

    private static void writeFile(File f, byte[] b) throws Exception {
        File p = f.getParentFile();
        if (p != null && !p.exists()) //noinspection ResultOfMethodCallIgnored
            p.mkdirs();
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(b);
        }
    }

    private static JSONObject ok() {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", true);
        } catch (Throwable ignored) {
        }
        return o;
    }

    private static JSONObject err(String msg) {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", false);
            o.put("error", msg);
        } catch (Throwable ignored) {
        }
        return o;
    }
}
