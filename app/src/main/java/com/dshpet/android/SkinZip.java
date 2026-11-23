package com.dshpet.android;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * 皮肤包：一个 zip 里放多张白底/透明立绘即可，文件名就是皮肤名。
 * 导入时逐张走 SkinStore.importCustom（自动抠图 + 压缩），导出时把自定义皮肤打回 zip 分享。
 */
public final class SkinZip {
    private static final int MAX_ENTRIES = 60;
    private static final long MAX_ENTRY_BYTES = 24L * 1024 * 1024;
    private static final int MAX_ANIM_FRAMES = 64;
    private static final long MAX_ANIM_BYTES = 36L * 1024 * 1024;
    private static final long MAX_CONFIG_BYTES = 128L * 1024;
    /** Live2D 模型包：结构复杂、贴图大，限制放宽一些，但仍然要防止解压炸弹。 */
    private static final int MAX_L2D_ENTRIES = 600;
    private static final long MAX_L2D_ENTRY_BYTES = 40L * 1024 * 1024;
    private static final long MAX_L2D_TOTAL_BYTES = 180L * 1024 * 1024;

    private SkinZip() {
    }

    /** 导入皮肤包，返回成功套数。 */
    public static int importZip(Context c, Uri uri) {
        int ok = 0;
        InputStream raw = null;
        ZipInputStream zin = null;
        try {
            raw = c.getContentResolver().openInputStream(uri);
            if (raw == null) return 0;
            zin = new ZipInputStream(new BufferedInputStream(raw));
            ZipEntry e;
            int count = 0;
            while ((e = zin.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String leaf = leafOf(e.getName());
                if (!isImage(leaf)) continue;
                if (++count > MAX_ENTRIES) break;
                byte[] data = readEntry(zin, MAX_ENTRY_BYTES);
                if (data == null) continue;
                Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length);
                if (b == null) continue;
                String name = stripExt(leaf);
                JSONObject r = SkinStore.importCustom(c, name, b);
                if (!b.isRecycled()) b.recycle();
                if (r.optBoolean("ok", false)) ok++;
            }
        } catch (Throwable ignored) {
        } finally {
            try {
                if (zin != null) zin.close();
            } catch (Throwable ignored) {
            }
            try {
                if (raw != null) raw.close();
            } catch (Throwable ignored) {
            }
        }
        return ok;
    }

    /**
     * 导入序列帧皮肤：zip 里所有图片都当成同一个角色的帧，按文件名自然排序（2.png 排在 10.png 前面）。
     * 可选 config.json：{"name":"小猫","fps":12}。
     * 所有帧会被缩放并居中到同一张画布，白底帧自动抠成透明底，这样播放时不会抖。
     *
     * @param fallbackName 没有 config.json 时用它当皮肤名（一般是 zip 文件名）
     */
    public static JSONObject importAnim(Context c, Uri uri, String fallbackName) {
        JSONObject out = new JSONObject();
        List<String> names = new ArrayList<>();
        JSONObject cfg = null;
        int maxW = 0, maxH = 0;
        try {
            // 第一遍：收集帧文件名、最大画布、可选配置
            InputStream raw = null;
            ZipInputStream zin = null;
            try {
                raw = c.getContentResolver().openInputStream(uri);
                if (raw == null) return fail(out, "读不到文件");
                zin = new ZipInputStream(new BufferedInputStream(raw));
                ZipEntry e;
                while ((e = zin.getNextEntry()) != null) {
                    if (skipEntry(e)) continue;
                    String leaf = leafOf(e.getName());
                    if ("config.json".equalsIgnoreCase(leaf)) {
                        byte[] data = readEntry(zin, MAX_CONFIG_BYTES);
                        if (data != null) {
                            try {
                                cfg = new JSONObject(new String(data, java.nio.charset.StandardCharsets.UTF_8));
                            } catch (Throwable ignored) {
                            }
                        }
                        continue;
                    }
                    if (!isImage(leaf)) continue;
                    if (names.size() >= MAX_ANIM_FRAMES) {
                        return fail(out, "帧太多了，最多 " + MAX_ANIM_FRAMES + " 帧");
                    }
                    byte[] data = readEntry(zin, MAX_ENTRY_BYTES);
                    if (data == null) continue;
                    BitmapFactory.Options bounds = new BitmapFactory.Options();
                    bounds.inJustDecodeBounds = true;
                    BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
                    if (bounds.outWidth <= 0) continue;
                    names.add(e.getName());
                    if (bounds.outWidth > maxW) maxW = bounds.outWidth;
                    if (bounds.outHeight > maxH) maxH = bounds.outHeight;
                }
            } finally {
                try {
                    if (zin != null) zin.close();
                } catch (Throwable ignored) {
                }
                try {
                    if (raw != null) raw.close();
                } catch (Throwable ignored) {
                }
            }
            if (names.isEmpty()) return fail(out, "包里没有图片，序列帧皮肤需要多张 png/jpg");
            Collections.sort(names, (x, y) -> sortKey(x).compareTo(sortKey(y)));

            // 统一画布：按最大帧的比例缩到 360 宽以内，帧越多越往下压，控制内存
            float ratio = maxH / (float) maxW;
            int tw = Math.min(360, maxW);
            int th = Math.max(1, Math.round(tw * ratio));
            long bytes = (long) tw * th * 4L * names.size();
            while (bytes > MAX_ANIM_BYTES && tw > 96) {
                tw = Math.max(96, (int) (tw * 0.85f));
                th = Math.max(1, Math.round(tw * ratio));
                bytes = (long) tw * th * 4L * names.size();
            }
            int sample = 1;
            while (maxW / (sample * 2) >= tw && maxH / (sample * 2) >= th) sample *= 2;

            String dirName = "a" + System.currentTimeMillis();
            File dir = new File(SkinStore.customDir(c), dirName);
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            if (!dir.isDirectory()) return fail(out, "无法创建皮肤目录");

            Map<String, Integer> order = new HashMap<>();
            for (int i = 0; i < names.size(); i++) order.put(names.get(i), i);

            // 第二遍：逐帧解码 → 抠白底 → 居中到统一画布 → 存 PNG（一次只在内存里留一两帧）
            Bitmap first = null;
            int saved = 0;
            InputStream raw2 = null;
            ZipInputStream zin2 = null;
            try {
                raw2 = c.getContentResolver().openInputStream(uri);
                if (raw2 == null) {
                    deleteTree(dir);
                    return fail(out, "读不到文件");
                }
                zin2 = new ZipInputStream(new BufferedInputStream(raw2));
                ZipEntry e;
                while ((e = zin2.getNextEntry()) != null) {
                    if (skipEntry(e)) continue;
                    if (!isImage(leafOf(e.getName()))) continue;
                    Integer idx = order.get(e.getName());
                    if (idx == null) continue;
                    byte[] data = readEntry(zin2, MAX_ENTRY_BYTES);
                    if (data == null) continue;
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inSampleSize = sample;
                    o.inPreferredConfig = Bitmap.Config.ARGB_8888;
                    Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length, o);
                    if (b == null) continue;
                    Bitmap clear = SkinStore.stripWhiteKeepCanvas(b);
                    if (clear != b) b.recycle();
                    Bitmap frame = fitCanvas(clear, tw, th);
                    if (frame != clear) clear.recycle();
                    try (FileOutputStream fo = new FileOutputStream(new File(dir, SkinStore.frameName(idx)))) {
                        frame.compress(Bitmap.CompressFormat.PNG, 100, fo);
                    }
                    if (first == null) first = frame;
                    else frame.recycle();
                    saved++;
                }
            } finally {
                try {
                    if (zin2 != null) zin2.close();
                } catch (Throwable ignored) {
                }
                try {
                    if (raw2 != null) raw2.close();
                } catch (Throwable ignored) {
                }
            }
            if (saved == 0 || first == null) {
                deleteTree(dir);
                return fail(out, "图片都读不出来，换一组 png/jpg 再试");
            }
            // 统一裁掉透明留白，让角色在桌面上别缩成一小只
            first.recycle();
            int[] box = trimFramesUniform(dir, names.size());
            if (box != null) {
                tw = box[0];
                th = box[1];
            }
            Bitmap thumbSrc = null;
            for (int i = 0; i < names.size(); i++) {
                File f = new File(dir, SkinStore.frameName(i));
                if (!f.isFile()) continue;
                thumbSrc = BitmapFactory.decodeFile(f.getAbsolutePath());
                break;
            }

            String name = cfg == null ? "" : cfg.optString("name", "");
            if (name == null || name.trim().isEmpty()) name = fallbackName;
            int fps = cfg == null ? 12 : cfg.optInt("fps", 12);
            JSONObject r = SkinStore.addAnimSkin(c, name, dirName, names.size(), tw, th, fps, thumbSrc);
            if (thumbSrc != null) thumbSrc.recycle();
            if (!r.optBoolean("ok", false)) {
                deleteTree(dir);
                return fail(out, r.optString("error", "保存皮肤失败"));
            }
            out.put("ok", true);
            out.put("count", saved);
            out.put("name", r.optString("name", name));
            out.put("fps", Math.max(1, Math.min(30, fps)));
            return out;
        } catch (Throwable t) {
            return fail(out, "皮肤包读取失败，确认是 zip 文件");
        }
    }

    /**
     * 导入 Live2D 模型包：zip 里必须有一个 *.model3.json，同目录下的 moc3、贴图、动作都按相对路径读取。
     * 只导入找到的第一个模型；model3.json 可以放在子目录里，导入时会把它那一层当作模型根。
     * 导入后立刻离屏渲染一帧当缩略图，同时取回画布比例。
     *
     * @param fallbackName 模型名缺失时用它（一般是 zip 文件名）
     */
    public static JSONObject importLive2d(Context c, Uri uri, String fallbackName) {
        JSONObject out = new JSONObject();
        if (!Live2DRenderer.supported()) {
            return fail(out, "这台设备是 32 位 ARM，Live2D 需要 64 位安装包");
        }
        String model3Entry = null;
        try {
            // 第一遍：只找 model3.json，顺便数一下条目
            InputStream raw = null;
            ZipInputStream zin = null;
            try {
                raw = c.getContentResolver().openInputStream(uri);
                if (raw == null) return fail(out, "读不到文件");
                zin = new ZipInputStream(new BufferedInputStream(raw));
                ZipEntry e;
                int count = 0;
                while ((e = zin.getNextEntry()) != null) {
                    if (skipEntry(e)) continue;
                    if (++count > MAX_L2D_ENTRIES) return fail(out, "文件太多了，最多 " + MAX_L2D_ENTRIES + " 个");
                    String leaf = leafOf(e.getName());
                    if (leaf.toLowerCase(Locale.ROOT).endsWith(".model3.json")) {
                        model3Entry = e.getName().replace('\\', '/');
                        break;
                    }
                }
            } finally {
                closeQuietly(zin, raw);
            }
            if (model3Entry == null) {
                return fail(out, "包里没有 .model3.json，不是 Live2D 模型包（需要 moc3 + 贴图 + model3.json）");
            }

            int slash = model3Entry.lastIndexOf('/');
            String prefix = slash >= 0 ? model3Entry.substring(0, slash + 1) : "";
            String model3Rel = slash >= 0 ? model3Entry.substring(slash + 1) : model3Entry;

            String dirName = "l" + System.currentTimeMillis();
            File dir = new File(SkinStore.live2dDir(c), dirName);
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            if (!dir.isDirectory()) return fail(out, "无法创建模型目录");

            // 第二遍：只解出模型根目录下的文件，其他目录（预览图、文档）一律丢弃
            long total = 0;
            int saved = 0;
            InputStream raw2 = null;
            ZipInputStream zin2 = null;
            try {
                raw2 = c.getContentResolver().openInputStream(uri);
                if (raw2 == null) {
                    deleteTree(dir);
                    return fail(out, "读不到文件");
                }
                zin2 = new ZipInputStream(new BufferedInputStream(raw2));
                ZipEntry e;
                while ((e = zin2.getNextEntry()) != null) {
                    if (skipEntry(e)) continue;
                    String full = e.getName().replace('\\', '/');
                    if (!full.startsWith(prefix)) continue;
                    String rel = full.substring(prefix.length());
                    if (!safeRel(rel)) continue;
                    byte[] data = readEntry(zin2, MAX_L2D_ENTRY_BYTES);
                    if (data == null) {
                        deleteTree(dir);
                        return fail(out, "包里有文件超过 40MB，没法导入");
                    }
                    total += data.length;
                    if (total > MAX_L2D_TOTAL_BYTES) {
                        deleteTree(dir);
                        return fail(out, "模型太大了，解压后不能超过 180MB");
                    }
                    File dst = new File(dir, rel);
                    File parent = dst.getParentFile();
                    if (parent != null) {
                        //noinspection ResultOfMethodCallIgnored
                        parent.mkdirs();
                    }
                    try (FileOutputStream fo = new FileOutputStream(dst)) {
                        fo.write(data);
                    }
                    saved++;
                }
            } finally {
                closeQuietly(zin2, raw2);
            }
            if (saved == 0) {
                deleteTree(dir);
                return fail(out, "模型文件解不出来，确认 zip 没加密");
            }

            // 校验 moc3 是否存在，避免把一个空壳登记成皮肤
            String moc = mocFileOf(new File(dir, model3Rel));
            if (moc == null || !new File(dir, moc).isFile()) {
                deleteTree(dir);
                return fail(out, "模型里缺少 moc3 文件，包可能不完整");
            }

            float[] ratio = new float[]{1.5f};
            Bitmap thumb = Live2DRenderer.snapshot(c, dir, model3Rel, ratio);
            if (thumb == null) {
                deleteTree(dir);
                return fail(out, "模型渲染失败，可能贴图缺失或 moc3 版本太旧");
            }
            String name = stem(model3Rel);
            if (name.isEmpty()) name = fallbackName;
            JSONObject r = SkinStore.addLive2dSkin(c, name, dirName, model3Rel, ratio[0], thumb);
            thumb.recycle();
            if (!r.optBoolean("ok", false)) {
                deleteTree(dir);
                return fail(out, r.optString("error", "保存皮肤失败"));
            }
            out.put("ok", true);
            out.put("name", r.optString("name", name));
            out.put("files", saved);
            return out;
        } catch (Throwable t) {
            return fail(out, "模型包读取失败，确认是 zip 文件");
        }
    }

    /** 读 model3.json 里的 FileReferences.Moc；读不出来返回 null。 */
    private static String mocFileOf(File model3) {
        byte[] data = Live2DRenderer.readFile(model3);
        if (data == null) return null;
        try {
            JSONObject json = new JSONObject(new String(data, java.nio.charset.StandardCharsets.UTF_8));
            JSONObject ref = json.optJSONObject("FileReferences");
            if (ref == null) return null;
            String moc = ref.optString("Moc", "");
            return moc.isEmpty() ? null : moc;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 只允许相对路径：不能有 ..、盘符、开头的 /。 */
    private static boolean safeRel(String rel) {
        if (rel == null || rel.isEmpty()) return false;
        if (rel.startsWith("/") || rel.contains(":")) return false;
        for (String seg : rel.split("/")) {
            if (seg.isEmpty() || ".".equals(seg) || "..".equals(seg)) return false;
        }
        return true;
    }

    private static String stem(String leaf) {
        String s = leafOf(leaf);
        int dot = s.toLowerCase(Locale.ROOT).lastIndexOf(".model3.json");
        if (dot > 0) s = s.substring(0, dot);
        else {
            dot = s.lastIndexOf('.');
            if (dot > 0) s = s.substring(0, dot);
        }
        return s.trim();
    }

    private static void closeQuietly(ZipInputStream zin, InputStream raw) {
        try {
            if (zin != null) zin.close();
        } catch (Throwable ignored) {
        }
        try {
            if (raw != null) raw.close();
        } catch (Throwable ignored) {
        }
    }

    /** 导出自定义皮肤到 zip，返回写入套数；0 表示没有可导出的皮肤。 */
    public static int exportTo(Context c, Uri uri) {
        List<JSONObject> list = SkinStore.customList(c);
        if (list.isEmpty()) return 0;
        File dir = SkinStore.customDir(c);
        ZipOutputStream zout = null;
        OutputStream raw = null;
        int n = 0;
        try {
            raw = c.getContentResolver().openOutputStream(uri);
            if (raw == null) return 0;
            zout = new ZipOutputStream(new BufferedOutputStream(raw));
            Set<String> used = new HashSet<>();
            for (JSONObject o : list) {
                File f = new File(dir, o.optString("file", ""));
                if (!f.isFile()) continue;
                String base = safe(o.optString("name", "皮肤"));
                String entry = base + ".png";
                int k = 2;
                while (!used.add(entry.toLowerCase(Locale.ROOT))) {
                    entry = base + "_" + (k++) + ".png";
                }
                zout.putNextEntry(new ZipEntry(entry));
                copy(new FileInputStream(f), zout);
                zout.closeEntry();
                n++;
            }
            zout.flush();
            zout.finish();
        } catch (Throwable ignored) {
        } finally {
            try {
                if (zout != null) zout.close();
            } catch (Throwable ignored) {
            }
            try {
                if (raw != null) raw.close();
            } catch (Throwable ignored) {
            }
        }
        return n;
    }

    /* ---------------- 内部 ---------------- */

    private static JSONObject fail(JSONObject out, String msg) {
        try {
            out.put("ok", false);
            out.put("error", msg);
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static boolean skipEntry(ZipEntry e) {
        String n = e.getName();
        if (n == null || n.isEmpty()) return true;
        if (e.isDirectory()) return true;
        String low = n.toLowerCase(Locale.ROOT);
        return low.startsWith("__macosx") || low.contains("/__macosx/") || low.endsWith(".ds_store");
    }

    /** 自然排序键：把数字补零，让 2.png 排在 10.png 前面。 */
    private static String sortKey(String name) {
        String s = name == null ? "" : name.toLowerCase(Locale.ROOT);
        Matcher m = Pattern.compile("\\d+").matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String d = m.group();
            StringBuilder pad = new StringBuilder();
            for (int i = d.length(); i < 6; i++) pad.append('0');
            m.appendReplacement(sb, Matcher.quoteReplacement(pad + d));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * 所有帧统一裁掉透明边：先取所有帧的并集包围盒，再按同一个矩形裁，
     * 这样既去掉四周留白，又不会破坏帧之间相对位置（不会抖）。
     * 返回新的画布 {w,h}；没有可裁的就返回 null。
     */
    private static int[] trimFramesUniform(File dir, int count) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = -1, maxY = -1;
        int w = 0, h = 0;
        for (int i = 0; i < count; i++) {
            File f = new File(dir, SkinStore.frameName(i));
            if (!f.isFile()) continue;
            Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath());
            if (b == null) continue;
            w = b.getWidth();
            h = b.getHeight();
            if (w <= 0 || h <= 0) {
                b.recycle();
                continue;
            }
            int[] px = new int[w * h];
            b.getPixels(px, 0, w, 0, 0, w, h);
            b.recycle();
            for (int y = 0; y < h; y++) {
                int row = y * w;
                for (int x = 0; x < w; x++) {
                    if ((px[row + x] >>> 24) > 16) {
                        if (x < minX) minX = x;
                        if (x > maxX) maxX = x;
                        if (y < minY) minY = y;
                        if (y > maxY) maxY = y;
                    }
                }
            }
        }
        if (maxX < minX || maxY < minY || w <= 0 || h <= 0) return null;
        int pad = 2;
        int x0 = Math.max(0, minX - pad);
        int y0 = Math.max(0, minY - pad);
        int x1 = Math.min(w - 1, maxX + pad);
        int y1 = Math.min(h - 1, maxY + pad);
        int cw = x1 - x0 + 1, ch = y1 - y0 + 1;
        if (cw >= w && ch >= h) return null;
        for (int i = 0; i < count; i++) {
            File f = new File(dir, SkinStore.frameName(i));
            if (!f.isFile()) continue;
            Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath());
            if (b == null) continue;
            int bx = Math.min(x0, Math.max(0, b.getWidth() - 1));
            int by = Math.min(y0, Math.max(0, b.getHeight() - 1));
            int bw = Math.min(cw, b.getWidth() - bx);
            int bh = Math.min(ch, b.getHeight() - by);
            if (bw <= 0 || bh <= 0) {
                b.recycle();
                continue;
            }
            Bitmap crop = Bitmap.createBitmap(b, bx, by, bw, bh);
            b.recycle();
            try (FileOutputStream fo = new FileOutputStream(f)) {
                crop.compress(Bitmap.CompressFormat.PNG, 100, fo);
            } catch (Throwable ignored) {
            }
            crop.recycle();
        }
        return new int[]{cw, ch};
    }

    /** 把一帧等比缩放并居中到统一画布，四周留透明。 */
    private static Bitmap fitCanvas(Bitmap src, int tw, int th) {
        Bitmap out = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        float s = Math.min(tw / (float) src.getWidth(), th / (float) src.getHeight());
        float dw = src.getWidth() * s, dh = src.getHeight() * s;
        float left = (tw - dw) / 2f, top = (th - dh) / 2f;
        cv.drawBitmap(src, null, new RectF(left, top, left + dw, top + dh), p);
        return out;
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) deleteTree(k);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private static String leafOf(String path) {
        if (path == null) return "";
        int i = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return i >= 0 ? path.substring(i + 1) : path;
    }

    private static boolean isImage(String leaf) {
        String s = leaf.toLowerCase(Locale.ROOT);
        return s.endsWith(".png") || s.endsWith(".jpg") || s.endsWith(".jpeg")
                || s.endsWith(".webp") || s.endsWith(".bmp");
    }

    private static String stripExt(String leaf) {
        int dot = leaf.lastIndexOf('.');
        String s = dot > 0 ? leaf.substring(0, dot) : leaf;
        s = s.replace("[", "").replace("]", "").trim();
        if (s.isEmpty()) s = "导入皮肤";
        return s;
    }

    private static String safe(String name) {
        String s = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|\\s]+", "_").trim();
        return s.isEmpty() ? "皮肤" : s;
    }

    private static byte[] readEntry(InputStream in, long max) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            long total = 0;
            while ((r = in.read(buf)) != -1) {
                total += r;
                if (total > max) return null;
                bos.write(buf, 0, r);
            }
            return bos.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }

    private static void copy(InputStream in, OutputStream out) throws Exception {
        try (InputStream src = in) {
            byte[] buf = new byte[8192];
            int r;
            while ((r = src.read(buf)) != -1) out.write(buf, 0, r);
        }
    }
}
