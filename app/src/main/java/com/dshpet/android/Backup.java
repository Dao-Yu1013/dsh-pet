package com.dshpet.android;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/** 备份：可选密码加密、导入预览、合并策略、迁移日志。 */
public final class Backup {
    private static final byte[] MAGIC = "SGBK1".getBytes(StandardCharsets.US_ASCII);

    private Backup() {
    }

    public static void exportTo(Context c, OutputStream out, String password) throws Exception {
        File zip = File.createTempFile("sg-export", ".zip", c.getCacheDir());
        try {
            try (FileOutputStream fo = new FileOutputStream(zip)) {
                Sg.zipDir(Sg.dir(c), fo);
            }
            if (password == null || password.isEmpty()) {
                copy(new FileInputStream(zip), out);
                return;
            }
            byte[] salt = new byte[16];
            byte[] iv = new byte[12];
            SecureRandom r = new SecureRandom();
            r.nextBytes(salt);
            r.nextBytes(iv);
            out.write(MAGIC);
            out.write(salt.length);
            out.write(salt);
            out.write(iv.length);
            out.write(iv);
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, password, salt, iv);
            try (CipherOutputStream cos = new CipherOutputStream(out, cipher);
                 FileInputStream in = new FileInputStream(zip)) {
                copy(in, cos);
            }
        } finally {
            //noinspection ResultOfMethodCallIgnored
            zip.delete();
        }
    }

    public static File toZipFile(Context c, File src, String password) throws Exception {
        if (!isEncrypted(src)) return src;
        if (password == null || password.isEmpty()) throw new Exception("这是加密备份，需要密码");
        File out = File.createTempFile("sg-import", ".zip", c.getCacheDir());
        try (InputStream in = new FileInputStream(src); OutputStream fo = new FileOutputStream(out)) {
            int n = in.read(MAGIC);
            if (n != MAGIC.length) throw new Exception("备份格式不正确");
            int sl = in.read();
            byte[] salt = new byte[sl];
            readFully(in, salt);
            int il = in.read();
            byte[] iv = new byte[il];
            readFully(in, iv);
            Cipher cipher = cipher(Cipher.DECRYPT_MODE, password, salt, iv);
            try (CipherInputStream cis = new CipherInputStream(in, cipher)) {
                copy(cis, fo);
            }
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            out.delete();
            throw new Exception(e.getMessage() == null ? "密码错误或文件损坏" : "密码错误或文件损坏");
        }
        return out;
    }

    public static boolean isEncrypted(File f) {
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[MAGIC.length];
            if (in.read(b) != MAGIC.length) return false;
            for (int i = 0; i < MAGIC.length; i++) if (b[i] != MAGIC[i]) return false;
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static JSONObject preview(Context c, File src, String password) {
        JSONObject o = new JSONObject();
        File zip = null;
        try {
            boolean enc = isEncrypted(src);
            zip = toZipFile(c, src, password);
            o.put("encrypted", enc);
            o.put("size", src.length());
            try (ZipFile z = new ZipFile(zip)) {
                int entries = 0;
                long total = 0;
                JSONArray files = new JSONArray();
                List<String> names = new ArrayList<>();
                Enumeration<? extends ZipEntry> en = z.entries();
                while (en.hasMoreElements()) {
                    ZipEntry e = en.nextElement();
                    if (e.isDirectory()) continue;
                    entries++;
                    total += e.getSize();
                    names.add(e.getName());
                    if (files.length() < 60) files.put(e.getName());
                }
                o.put("entries", entries);
                o.put("bytes", total);
                o.put("files", files);

                JSONObject persona = obj(z, "persona.json");
                if (persona != null) o.put("persona", persona.optString("name", ""));
                JSONArray mem = arr(z, "memories.json");
                o.put("memories", mem.length());
                o.put("chats", countLines(z, "chat.jsonl"));
                JSONArray rem = arr(z, "reminders.json");
                o.put("reminders", rem.length());
                JSONArray tasks = arr(z, "tasks.json");
                o.put("tasks", tasks.length());
                JSONArray clips = arr(z, "clips.json");
                o.put("clips", clips.length());
                o.put("hasConfig", names.contains("config.json"));
                o.put("hasPlugins", hasPrefix(names, "plugins/"));
                o.put("hasClips", hasPrefix(names, "clips/"));
                o.put("version", "2.1.0");
            }
        } catch (Throwable t) {
            try {
                o.put("error", t.getMessage() == null ? "无法读取备份" : t.getMessage());
            } catch (Throwable ignored) {
            }
        } finally {
            if (zip != null && !zip.equals(src)) //noinspection ResultOfMethodCallIgnored
                zip.delete();
        }
        return o;
    }

    /** mode: replace / merge。 */
    public static JSONObject apply(Context c, File src, String password, String mode) {
        JSONObject r = new JSONObject();
        File zip = null;
        // 恢复期间必须停掉桌宠：它内存里持有 Cfg 快照，运行中覆盖数据会被旧快照回写
        boolean wasRunning = PetSvc.running;
        try {
            zip = toZipFile(c, src, password);
            pausePet(c, wasRunning);
            if ("merge".equals(mode)) {
                File dir = File.createTempFile("sg-merge", "", c.getCacheDir());
                //noinspection ResultOfMethodCallIgnored
                dir.delete();
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
                try {
                    try (InputStream in = new FileInputStream(zip)) {
                        Sg.unzipTo(in, dir);
                    }
                    mergeDir(c, dir);
                } finally {
                    // 解压超限等异常也要清掉临时目录，避免缓存里堆积半套文件
                    deleteTree(dir);
                }
            } else {
                replaceDir(c, zip);
            }
            r.put("ok", true);
            r.put("mode", mode == null ? "replace" : mode);
            r.put("encrypted", isEncrypted(src));
            log(c, r, src.length());
        } catch (Throwable t) {
            try {
                r.put("ok", false);
                r.put("error", t.getMessage() == null ? "导入失败" : t.getMessage());
            } catch (Throwable ignored) {
            }
        } finally {
            if (zip != null && !zip.equals(src)) //noinspection ResultOfMethodCallIgnored
                zip.delete();
            resumePet(c, wasRunning);
        }
        return r;
    }

    /** 等桌宠真正停下来再动数据；停不下来就中止，绝不在服务运行中覆盖。 */
    private static void pausePet(Context c, boolean wasRunning) throws Exception {
        if (!wasRunning) return;
        PetSvc.send(c, PetSvc.A_STOP, null, null);
        long deadline = System.currentTimeMillis() + 6000;
        while (PetSvc.running && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (PetSvc.running) throw new Exception("桌宠服务未能停止，请稍后重试");
        // running 先置 false，onDestroy 稍后才跑完；留一点时间让播放器/窗口彻底释放
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 恢复完成后按原状态拉起桌宠；running 以恢复后的 config 为准，只补“之前在跑”这一点。 */
    private static void resumePet(Context c, boolean wasRunning) {
        if (!wasRunning) return;
        try {
            Cfg cfg = Cfg.load(c);
            cfg.set("running", true);
            cfg.save(c);
            PetSvc.start(c);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 事务式替换：先解压到 S-G.restore.tmp → 校验关键 JSON → 旧目录改名 S-G.bak
     * → 新目录改名 S-G。任一步失败都回滚，正式目录不会出现半套数据。
     */
    private static void replaceDir(Context c, File zip) throws Exception {
        File root = c.getFilesDir();
        File target = new File(root, Sg.DIR);
        File stage = new File(root, Sg.DIR + ".restore.tmp");
        File bak = new File(root, Sg.DIR + ".bak");
        deleteTree(stage);
        if (!stage.mkdirs()) throw new Exception("无法创建临时解压目录");
        try (InputStream in = new FileInputStream(zip)) {
            Sg.unzipTo(in, stage);
        } catch (Throwable t) {
            deleteTree(stage);
            throw new Exception(t.getMessage() == null ? "备份解压失败" : t.getMessage());
        }
        try {
            validate(stage);
        } catch (Throwable t) {
            deleteTree(stage);
            throw t instanceof Exception ? (Exception) t : new Exception("备份校验失败");
        }
        synchronized (Sg.WRITE_LOCK) {
            deleteTree(bak);
            if (target.exists() && !target.renameTo(bak)) {
                deleteTree(stage);
                throw new Exception("无法暂存当前数据，已取消恢复");
            }
            if (!stage.renameTo(target)) {
                if (!target.exists() && bak.exists()) //noinspection ResultOfMethodCallIgnored
                    bak.renameTo(target);
                deleteTree(stage);
                throw new Exception("替换数据失败，已回滚到原数据");
            }
        }
        // 保留一代 S-G.bak，下次恢复成功时会自动清掉
    }

    /** 备份里带的 JSON 必须能解析；缺失的文件视为旧版本备份，直接跳过。 */
    private static void validate(File dir) throws Exception {
        checkObj(dir, "config.json");
        checkObj(dir, "persona.json");
        checkObj(dir, "action_lines.json");
        checkArr(dir, "memories.json");
        checkArr(dir, "reminders.json");
        checkArr(dir, "tasks.json");
        checkArr(dir, "clips.json");
        checkArr(dir, "books.json");
    }

    private static void checkObj(File dir, String name) throws Exception {
        File f = new File(dir, name);
        if (!f.isFile() || f.length() == 0) return;
        String s = Sg.read(f, "");
        if (s.trim().isEmpty()) return;
        try {
            new JSONObject(s);
        } catch (Throwable t) {
            throw new Exception("备份中的 " + name + " 已损坏");
        }
    }

    private static void checkArr(File dir, String name) throws Exception {
        File f = new File(dir, name);
        if (!f.isFile() || f.length() == 0) return;
        String s = Sg.read(f, "");
        if (s.trim().isEmpty()) return;
        try {
            new JSONArray(s);
        } catch (Throwable t) {
            throw new Exception("备份中的 " + name + " 已损坏");
        }
    }

    private static void mergeDir(Context c, File dir) {
        // 聊天
        JSONArray chat = readJsonl(new File(dir, "chat.jsonl"));
        if (chat.length() > 0) {
            Set<String> seen = new HashSet<>();
            JSONArray cur = readJsonl(Sg.file(c, "chat.jsonl"));
            for (int k = 0; k < cur.length(); k++) {
                JSONObject o = cur.optJSONObject(k);
                if (o != null) seen.add(o.optString("role") + "|" + o.optString("content") + "|" + o.optLong("ts"));
            }
            StringBuilder add = new StringBuilder();
            for (int k = 0; k < chat.length(); k++) {
                JSONObject o = chat.optJSONObject(k);
                if (o == null) continue;
                String key = o.optString("role") + "|" + o.optString("content") + "|" + o.optLong("ts");
                if (seen.add(key)) add.append(o).append('\n');
            }
            if (add.length() > 0) {
                String all = Sg.read(Sg.file(c, "chat.jsonl"), "");
                if (!all.isEmpty() && !all.endsWith("\n")) all += "\n";
                Sg.write(Sg.file(c, "chat.jsonl"), all + add);
            }
        }
        // 记忆
        JSONArray mem = arrFile(dir, "memories.json");
        if (mem.length() > 0) Mem.importArray(c, mem);
        mergeArrayFile(c, dir, "companion_rules.json", "id");
        // 提醒
        mergeArrayFile(c, dir, "reminders.json", "id");
        mergeArrayFile(c, dir, "tasks.json", "id");
        mergeArrayFile(c, dir, "clips.json", "id");
        // 人设：当前没有才导入
        File p = Sg.file(c, "persona.json");
        File ip = new File(dir, "persona.json");
        if (!p.isFile() && ip.isFile()) copyFile(ip, p);
        // 动作台词：补齐缺失项
        JSONObject lines = Sg.readObj(c, "action_lines.json");
        JSONObject inLines = objFile(dir, "action_lines.json");
        if (inLines != null) {
            java.util.Iterator<String> it = inLines.keys();
            while (it.hasNext()) {
                String k = it.next();
                if (!lines.has(k)) {
                    try {
                        lines.put(k, inLines.opt(k));
                    } catch (Throwable ignored) {
                    }
                }
            }
            Sg.writeObj(c, "action_lines.json", lines);
        }
        // 插件目录
        File pl = new File(dir, "plugins");
        if (pl.isDirectory()) copyTree(pl, new File(Sg.dir(c), "plugins"));
        // 剪贴板附件
        File cl = new File(dir, "clips");
        if (cl.isDirectory()) copyTree(cl, new File(Sg.dir(c), "clips"));
    }

    private static void mergeArrayFile(Context c, File dir, String name, String idKey) {
        JSONArray in = arrFile(dir, name);
        if (in.length() == 0) return;
        JSONArray cur = Sg.readArr(c, name);
        Set<String> ids = new HashSet<>();
        for (int k = 0; k < cur.length(); k++) {
            JSONObject o = cur.optJSONObject(k);
            if (o != null) ids.add(o.optString(idKey));
        }
        for (int k = 0; k < in.length(); k++) {
            JSONObject o = in.optJSONObject(k);
            if (o == null) continue;
            String id = o.optString(idKey);
            if (id.isEmpty() || ids.add(id)) cur.put(o);
        }
        Sg.writeArr(c, name, cur);
    }

    private static JSONArray readJsonl(File f) {
        JSONArray a = new JSONArray();
        try {
            String s = Sg.read(f, "");
            for (String line : s.split("\n")) {
                if (line.trim().isEmpty()) continue;
                try {
                    a.put(new JSONObject(line));
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return a;
    }

    private static JSONObject obj(ZipFile z, String name) {
        String s = readEntry(z, name, 512 * 1024);
        if (s.isEmpty()) return null;
        try {
            return new JSONObject(s);
        } catch (Throwable t) {
            return null;
        }
    }

    private static JSONArray arr(ZipFile z, String name) {
        String s = readEntry(z, name, 2048 * 1024);
        if (s.isEmpty()) return new JSONArray();
        try {
            return new JSONArray(s);
        } catch (Throwable t) {
            return new JSONArray();
        }
    }

    private static JSONObject objFile(File dir, String name) {
        try {
            String s = Sg.read(new File(dir, name), "");
            return s.trim().isEmpty() ? null : new JSONObject(s);
        } catch (Throwable t) {
            return null;
        }
    }

    private static JSONArray arrFile(File dir, String name) {
        try {
            String s = Sg.read(new File(dir, name), "");
            return s.trim().isEmpty() ? new JSONArray() : new JSONArray(s);
        } catch (Throwable t) {
            return new JSONArray();
        }
    }

    private static String readEntry(ZipFile z, String name, int max) {
        try {
            ZipEntry e = z.getEntry(name);
            if (e == null) return "";
            try (InputStream in = z.getInputStream(e)) {
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) > 0 && bo.size() < max) bo.write(b, 0, n);
                return new String(bo.toByteArray(), StandardCharsets.UTF_8);
            }
        } catch (Throwable t) {
            return "";
        }
    }

    private static int countLines(ZipFile z, String name) {
        String s = readEntry(z, name, 4096 * 1024);
        if (s.isEmpty()) return 0;
        int n = 0;
        for (String line : s.split("\n")) if (!line.trim().isEmpty()) n++;
        return n;
    }

    private static boolean hasPrefix(List<String> names, String prefix) {
        for (String s : names) if (s.startsWith(prefix)) return true;
        return false;
    }

    private static void log(Context c, JSONObject r, long size) {
        try {
            JSONArray a = Sg.readArr(c, "migrations.json");
            JSONObject o = new JSONObject();
            o.put("ts", System.currentTimeMillis());
            o.put("time", Sg.now());
            o.put("mode", r.optString("mode", "replace"));
            o.put("size", size);
            o.put("encrypted", r.optBoolean("encrypted", false));
            o.put("version", "2.1.0");
            a.put(o);
            while (a.length() > 50) {
                JSONArray b = new JSONArray();
                for (int k = 1; k < a.length(); k++) b.put(a.opt(k));
                a = b;
            }
            Sg.writeArr(c, "migrations.json", a);
        } catch (Throwable ignored) {
        }
    }

    public static JSONArray migrations(Context c) {
        return Sg.readArr(c, "migrations.json");
    }

    private static Cipher cipher(int mode, String password, byte[] salt, byte[] iv) throws Exception {
        SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        SecretKey key = f.generateSecret(new PBEKeySpec(password.toCharArray(), salt, 12000, 256));
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(mode, new SecretKeySpec(key.getEncoded(), "AES"), new GCMParameterSpec(128, iv));
        return c;
    }

    private static void readFully(InputStream in, byte[] b) throws Exception {
        int off = 0;
        while (off < b.length) {
            int n = in.read(b, off, b.length - off);
            if (n < 0) throw new Exception("备份不完整");
            off += n;
        }
    }

    private static void copy(InputStream in, OutputStream out) throws Exception {
        byte[] b = new byte[16384];
        int n;
        while ((n = in.read(b)) > 0) out.write(b, 0, n);
    }

    private static void copyFile(File src, File dst) {
        try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst)) {
            copy(in, out);
        } catch (Throwable ignored) {
        }
    }

    private static void copyTree(File src, File dst) {
        if (!dst.exists()) //noinspection ResultOfMethodCallIgnored
            dst.mkdirs();
        File[] fs = src.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            File o = new File(dst, f.getName());
            if (f.isDirectory()) copyTree(f, o);
            else if (!o.exists()) copyFile(f, o);
        }
    }

    private static void deleteTree(File f) {
        File[] fs = f.listFiles();
        if (fs != null) for (File x : fs) {
            if (x.isDirectory()) deleteTree(x);
            else //noinspection ResultOfMethodCallIgnored
                x.delete();
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
