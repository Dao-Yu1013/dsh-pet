package com.dshpet.android;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 前端加固：ui/*（index.html、app.js、app.css、memory-graph.js、图形库）和 pet-config.json 在打包时被加密成
 * assets/sec.bin，运行时用 native 提供的密钥解到私有目录 files/ui/ 再交给 WebView。
 *
 * 为什么要落盘：WebView 用 file:// 加载时，同目录下的 webp 资源要能被相对路径引用，
 * baseUrl 必须指向真实目录；解密结果放在应用私有目录，别的应用读不到。
 *
 * -Fast 打包（不加密）时不生成 sec.bin，这里自动回退读明文 assets，方便排障对比。
 */
public final class WebUI {
    private static final String BIN = "sec.bin";
    private static final String MAGIC = "DSH1";
    /** 解密结果的根目录：files/ui/ */
    private static File root;

    private WebUI() {
    }

    /** 解密后的资源根目录（里面有 ui/ 和 pet-config.json）。 */
    public static synchronized File dir(Context c) {
        if (root == null) root = new File(c.getFilesDir(), "ui");
        return root;
    }

    /**
     * 准备前端资源：需要时解密并落盘。返回可用的资源根目录。
     * 解密失败（例如 -Fast 包没有 sec.bin）返回 null，调用方回退到 assets。
     */
    public static synchronized File prepare(Context c) {
        File dir = dir(c);
        // 用安装/更新时间做戳，而不是 versionCode：
        // 同一版本号重复覆盖安装时，versionCode 不变，旧前端会被一直复用，用户会以为没更新。
        long version = updateTime(c);
        File stamp = new File(dir, ".v");
        if (dir.isDirectory() && readLong(stamp) == version
                && new File(dir, "ui/index.html").isFile()
                && new File(dir, "ui/app.js").isFile()
                && new File(dir, "ui/app.css").isFile()
                && new File(dir, "ui/memory-graph.js").isFile()
                && new File(dir, "ui/vendor/3d-force-graph.min.js").isFile()
                && new File(dir, "ui/vendor/oss-starfield-opening.js").isFile()
                && new File(dir, "pet-config.json").isFile()) return dir;
        try {
            Map<String, byte[]> files = unseal(c);
            if (files.isEmpty()) return null;
            // 版本变了就整体重写，避免残留旧文件
            deleteTree(dir);
            if (!dir.mkdirs() && !dir.isDirectory()) return null;
            for (Map.Entry<String, byte[]> e : files.entrySet()) {
                File out = new File(dir, e.getKey());
                File parent = out.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) continue;
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    fos.write(e.getValue());
                }
            }
            try (FileOutputStream fos = new FileOutputStream(stamp)) {
                fos.write(String.valueOf(version).getBytes(StandardCharsets.UTF_8));
            }
            return dir;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 读一个前端资源；解密目录优先，取不到时退回 assets（-Fast 包走这里）。 */
    public static byte[] read(Context c, String rel) {
        File dir = dir(c);
        File f = new File(dir, rel);
        if (f.isFile()) {
            try (InputStream in = new java.io.FileInputStream(f)) {
                return drain(in);
            } catch (Throwable ignored) {
            }
        }
        try (InputStream in = c.getAssets().open(rel)) {
            return drain(in);
        } catch (Throwable t) {
            return new byte[0];
        }
    }

    public static String readText(Context c, String rel) {
        return new String(read(c, rel), StandardCharsets.UTF_8);
    }

    /** 是否处于加固模式（有 sec.bin）。 */
    public static boolean sealed(Context c) {
        try (InputStream in = c.getAssets().open(BIN)) {
            return in.read() >= 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /* ---------------- 内部 ---------------- */

    /** 解析并解密 sec.bin：magic + count + [名字, 长度, IV+密文]… */
    private static Map<String, byte[]> unseal(Context c) throws Exception {
        Map<String, byte[]> out = new HashMap<>();
        byte[] all;
        try (InputStream in = c.getAssets().open(BIN)) {
            all = drain(in);
        }
        if (all.length < 8 || !MAGIC.equals(new String(all, 0, 4, StandardCharsets.US_ASCII))) return out;
        byte[] key = Secure.key();
        SecretKeySpec spec = new SecretKeySpec(key, "AES");
        int p = 4;
        int count = readInt(all, p);
        p += 4;
        if (count <= 0 || count > 64) return out;
        for (int i = 0; i < count; i++) {
            int nameLen = readShort(all, p);
            p += 2;
            if (nameLen <= 0 || p + nameLen > all.length) return out;
            String name = new String(all, p, nameLen, StandardCharsets.UTF_8);
            p += nameLen;
            int len = readInt(all, p);
            p += 4;
            if (len < 16 || p + len > all.length) return out;
            byte[] iv = new byte[16];
            System.arraycopy(all, p, iv, 0, 16);
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, spec, new IvParameterSpec(iv));
            out.put(name, cipher.doFinal(all, p + 16, len - 16));
            p += len;
        }
        return out;
    }

    private static int readInt(File f) {
        try (InputStream in = new java.io.FileInputStream(f)) {
            byte[] b = new byte[12];
            int n = in.read(b);
            return n <= 0 ? -1 : Integer.parseInt(new String(b, 0, n, StandardCharsets.UTF_8).trim());
        } catch (Throwable t) {
            return -1;
        }
    }

    private static long readLong(File f) {
        try (InputStream in = new java.io.FileInputStream(f)) {
            byte[] b = new byte[32];
            int n = in.read(b);
            return n <= 0 ? -1L : Long.parseLong(new String(b, 0, n, StandardCharsets.UTF_8).trim());
        } catch (Throwable t) {
            return -1L;
        }
    }

    private static int readInt(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16) | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    private static int readShort(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    private static int versionCode(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return pi.versionCode;
        } catch (Throwable t) {
            return -1;
        }
    }

    private static long updateTime(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return pi.lastUpdateTime;
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private static byte[] drain(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }
}
