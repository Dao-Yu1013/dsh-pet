package com.dshpet.android;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** 所有持久化都落在应用私有 S-G 文件夹；相册、文件管理器和清理工具都扫描不到。 */
public final class Sg {
    public static final String DIR = "S-G";
    /** 所有落盘写操作共用同一把锁：恢复备份替换目录时，通知/记忆等后台写入不会插进来。 */
    public static final Object WRITE_LOCK = new Object();
    /** 解压上限：防止压缩炸弹或损坏备份把存储写满。 */
    public static final long MAX_UNZIP_TOTAL_BYTES = 512L * 1024 * 1024;
    public static final long MAX_UNZIP_ENTRY_BYTES = 256L * 1024 * 1024;
    public static final int MAX_UNZIP_ENTRIES = 5000;

    private Sg() {
    }

    /** 仅用于判断“所有文件访问”，供扫描 Download/Documents 里的书籍使用。 */
    public static boolean canUsePublic(Context c) {
        if (Build.VERSION.SDK_INT >= 30) {
            return Environment.isExternalStorageManager();
        }
        if (Build.VERSION.SDK_INT >= 29) {
            return false;
        }
        return c.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    /** 应用私有目录：其他应用、相册、媒体扫描和清理工具都无法读取或删除。 */
    public static File dir(Context c) {
        File d = new File(c.getFilesDir(), DIR);
        if (!d.exists()) {
            //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        }
        return d;
    }

    public static String path(Context c) {
        return dir(c).getAbsolutePath();
    }

    public static File file(Context c, String name) {
        return new File(dir(c), name);
    }

    public static String now() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
    }

    public static String read(File f, String def) {
        if (f == null) return def;
        File src = f;
        if (!src.isFile()) {
            // 上次写入在「旧文件改名 → 新文件改名」之间崩溃时，目标文件会短暂缺失；
            // 优先用已经 fsync 过的 .tmp（最新完整内容），其次用上一代 .old。
            File tmp = new File(f.getAbsolutePath() + ".tmp");
            File old = new File(f.getAbsolutePath() + ".old");
            if (tmp.isFile()) src = tmp;
            else if (old.isFile()) src = old;
            else return def;
        }
        try (BufferedReader r = new BufferedReader(new FileReader(src))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            return sb.toString();
        } catch (Throwable t) {
            return def;
        }
    }

    /**
     * 原子写：先写 .tmp 并 fsync，再把旧文件改名成 .old，最后把 .tmp 改名成目标。
     * 整个过程不移除目标文件，任何一步崩溃都能由 {@link #read(File, String)} 从
     * .tmp / .old 找回完整内容。
     */
    public static void write(File f, String text) {
        synchronized (WRITE_LOCK) {
            File tmp = new File(f.getAbsolutePath() + ".tmp");
            File old = new File(f.getAbsolutePath() + ".old");
            try {
                File p = f.getParentFile();
                if (p != null && !p.exists()) //noinspection ResultOfMethodCallIgnored
                    p.mkdirs();
                try (FileOutputStream fo = new FileOutputStream(tmp);
                     OutputStreamWriter w = new OutputStreamWriter(fo, StandardCharsets.UTF_8)) {
                    w.write(text);
                    w.flush();
                    fo.getFD().sync();
                }
                if (old.exists()) //noinspection ResultOfMethodCallIgnored
                    old.delete();
                if (f.exists() && !f.renameTo(old)) {
                    // 个别文件系统不允许改名覆盖；此时退回“先删目标”，但 .tmp 已完整落盘
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
                if (!tmp.renameTo(f)) {
                    // 交换失败：把上一代挪回去，避免目标文件凭空消失
                    if (!f.exists() && old.exists()) //noinspection ResultOfMethodCallIgnored
                        old.renameTo(f);
                    return;
                }
                // 新文件已就位，上一代只是崩溃兜底，成功后立即删掉，避免双份占空间
                if (old.exists()) //noinspection ResultOfMethodCallIgnored
                    old.delete();
            } catch (Throwable ignored) {
            }
        }
    }

    public static JSONObject readObj(Context c, String name) {
        try {
            String s = read(file(c, name), "");
            if (s.trim().isEmpty()) return new JSONObject();
            return new JSONObject(s);
        } catch (Throwable t) {
            return new JSONObject();
        }
    }

    public static void writeObj(Context c, String name, JSONObject o) {
        write(file(c, name), o.toString());
    }

    public static JSONArray readArr(Context c, String name) {
        try {
            String s = read(file(c, name), "");
            if (s.trim().isEmpty()) return new JSONArray();
            return new JSONArray(s);
        } catch (Throwable t) {
            return new JSONArray();
        }
    }

    public static void writeArr(Context c, String name, JSONArray a) {
        write(file(c, name), a.toString());
    }

    public static void appendLine(Context c, String name, JSONObject o) {
        synchronized (WRITE_LOCK) {
            try (FileWriter w = new FileWriter(file(c, name), true)) {
                w.write(o.toString());
                w.write('\n');
            } catch (Throwable ignored) {
            }
        }
    }

    public static String readTail(Context c, String name, int maxBytes) {
        File f = file(c, name);
        if (!f.isFile()) return "";
        long len = f.length();
        long start = Math.max(0, len - maxBytes);
        try (FileInputStream in = new FileInputStream(f)) {
            //noinspection ResultOfMethodCallIgnored
            in.skip(start);
            byte[] buf = new byte[(int) (len - start)];
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n < 0) break;
                off += n;
            }
            String s = new String(buf, 0, off, StandardCharsets.UTF_8);
            // 丢掉可能被截断的第一行
            int nl = s.indexOf('\n');
            if (start > 0 && nl >= 0) s = s.substring(nl + 1);
            return s;
        } catch (Throwable t) {
            return "";
        }
    }

    public static void zipDir(File dir, OutputStream out) throws Exception {
        ZipOutputStream z = new ZipOutputStream(out);
        zipInto(z, dir, "");
        z.finish();
    }

    private static void zipInto(ZipOutputStream z, File dir, String prefix) throws Exception {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.getName().endsWith(".tmp") || f.getName().endsWith(".old")) continue;
            if (f.isDirectory()) {
                zipInto(z, f, prefix + f.getName() + "/");
            } else {
                z.putNextEntry(new ZipEntry(prefix + f.getName()));
                try (InputStream in = new FileInputStream(f)) {
                    byte[] b = new byte[8192];
                    int n;
                    while ((n = in.read(b)) > 0) z.write(b, 0, n);
                }
                z.closeEntry();
            }
        }
    }

    /** 删除 S-G 下的全部用户数据，保留目录本身。调用前应确保桌宠服务已停止。 */
    public static void deleteAll(Context c) {
        synchronized (WRITE_LOCK) {
            File d = dir(c);
            File[] fs = d.listFiles();
            if (fs != null) for (File f : fs) deleteRec(f);
        }
    }

    private static void deleteRec(File f) {
        if (f == null) return;
        if (f.isDirectory()) {
            File[] fs = f.listFiles();
            if (fs != null) for (File x : fs) deleteRec(x);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    /**
     * 解压到指定目录。带条目数、单条大小和总大小上限，超过就抛异常中止。
     * 调用方应在临时目录里解压，失败时整体删除，不要把半套文件留在正式目录。
     */
    public static void unzipTo(InputStream in, File dir) throws Exception {
        if (!dir.exists() && !dir.mkdirs()) throw new Exception("无法创建解压目录");
        ZipInputStream z = new ZipInputStream(in);
        ZipEntry e;
        int entries = 0;
        long total = 0;
        while ((e = z.getNextEntry()) != null) {
            if (e.isDirectory()) continue;
            if (++entries > MAX_UNZIP_ENTRIES) throw new Exception("备份条目过多（超过 " + MAX_UNZIP_ENTRIES + " 个）");
            String name = e.getName();
            if (name == null || name.isEmpty() || name.contains("..")
                    || name.startsWith("/") || name.startsWith("\\")) continue;
            long declared = e.getSize();
            if (declared > MAX_UNZIP_ENTRY_BYTES) throw new Exception("备份中存在超大文件");
            File f = new File(dir, name);
            File p = f.getParentFile();
            if (p != null && !p.exists() && !p.mkdirs()) throw new Exception("无法创建解压子目录");
            long entry = 0;
            try (FileOutputStream out = new FileOutputStream(f)) {
                byte[] b = new byte[8192];
                int n;
                while ((n = z.read(b)) > 0) {
                    entry += n;
                    total += n;
                    if (entry > MAX_UNZIP_ENTRY_BYTES || total > MAX_UNZIP_TOTAL_BYTES) {
                        throw new Exception("备份解压后过大，已中止");
                    }
                    out.write(b, 0, n);
                }
            }
        }
    }
}
