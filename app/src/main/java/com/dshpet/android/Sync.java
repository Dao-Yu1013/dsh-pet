package com.dshpet.android;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** WebDAV 同步：手动上传/下载，复用加密备份容器。 */
public final class Sync {
    public interface Cb {
        void ok(String msg);

        void fail(String msg);
    }

    private Sync() {
    }

    public static JSONObject state(Context c) {
        Cfg cfg = Cfg.load(c);
        JSONObject o = new JSONObject();
        try {
            o.put("url", cfg.str("davUrl", ""));
            o.put("user", cfg.str("davUser", ""));
            o.put("hasPass", !cfg.str("davPass", "").isEmpty());
            o.put("hasEnc", !cfg.str("syncPass", "").isEmpty());
            o.put("lastSync", cfg.str("lastSync", ""));
            o.put("auto", cfg.b("syncAuto", false));
        } catch (Throwable ignored) {
        }
        return o;
    }

    public static void save(Context c, JSONObject o) {
        if (o == null) return;
        Cfg cfg = Cfg.load(c);
        String[] keys = {"davUrl", "davUser", "davPass", "syncPass", "syncAuto"};
        for (String k : keys) if (o.has(k)) cfg.set(k, o.opt(k));
        cfg.save(c);
    }

    public static void test(Context c, Cb cb) {
        Cfg cfg = Cfg.load(c);
        String url = cfg.str("davUrl", "").trim();
        if (url.isEmpty()) {
            cb.fail("先填 WebDAV 文件地址");
            return;
        }
        try {
            HttpURLConnection conn = open(url, cfg);
            conn.setRequestMethod("HEAD");
            int code = conn.getResponseCode();
            conn.disconnect();
            if (code == 401 || code == 403) cb.fail("认证失败（" + code + "）");
            else if (code == 404) cb.ok("服务器可达，文件还不存在");
            else if (code >= 200 && code < 400) cb.ok("连接正常（" + code + "）");
            else cb.fail("服务器返回 " + code);
        } catch (Throwable t) {
            cb.fail(t.getMessage() == null ? "连接失败" : t.getMessage());
        }
    }

    public static void upload(Context c, Cb cb) {
        Cfg cfg = Cfg.load(c);
        String url = cfg.str("davUrl", "").trim();
        if (url.isEmpty()) {
            cb.fail("先填 WebDAV 文件地址");
            return;
        }
        File tmp = null;
        try {
            tmp = File.createTempFile("sg-sync", ".bak", c.getCacheDir());
            try (OutputStream out = new FileOutputStream(tmp)) {
                Backup.exportTo(c, out, cfg.str("syncPass", ""));
            }
            HttpURLConnection conn = open(url, cfg);
            conn.setRequestMethod("PUT");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/octet-stream");
            conn.setFixedLengthStreamingMode(tmp.length());
            try (OutputStream out = conn.getOutputStream(); InputStream in = new FileInputStream(tmp)) {
                byte[] b = new byte[16384];
                int n;
                while ((n = in.read(b)) > 0) out.write(b, 0, n);
            }
            int code = conn.getResponseCode();
            conn.disconnect();
            if (code >= 200 && code < 300) {
                cfg.set("lastSync", Sg.now());
                cfg.save(c);
                cb.ok("已上传（" + Math.round(tmp.length() / 1024.0) + " KB）");
            } else {
                cb.fail("上传失败，HTTP " + code);
            }
        } catch (Throwable t) {
            cb.fail(t.getMessage() == null ? "上传失败" : t.getMessage());
        } finally {
            if (tmp != null) //noinspection ResultOfMethodCallIgnored
                tmp.delete();
        }
    }

    public static void download(Context c, Cb cb) {
        Cfg cfg = Cfg.load(c);
        String url = cfg.str("davUrl", "").trim();
        if (url.isEmpty()) {
            cb.fail("先填 WebDAV 文件地址");
            return;
        }
        File tmp = null;
        try {
            tmp = File.createTempFile("sg-sync", ".bak", c.getCacheDir());
            HttpURLConnection conn = open(url, cfg);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                conn.disconnect();
                cb.fail("下载失败，HTTP " + code);
                return;
            }
            try (InputStream in = conn.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
                byte[] b = new byte[16384];
                int n;
                while ((n = in.read(b)) > 0) out.write(b, 0, n);
            }
            conn.disconnect();
            JSONObject r = Backup.apply(c, tmp, cfg.str("syncPass", ""), "replace");
            if (!r.optBoolean("ok", false)) {
                cb.fail(r.optString("error", "导入失败"));
                return;
            }
            // 恢复后必须重新读配置：旧的 cfg 是恢复前快照，直接 save 会覆盖刚恢复的 config.json
            Cfg latest = Cfg.load(c);
            latest.set("lastSync", Sg.now());
            latest.save(c);
            A11y.reload();
            cb.ok("已从云端恢复");
        } catch (Throwable t) {
            cb.fail(t.getMessage() == null ? "下载失败" : t.getMessage());
        } finally {
            if (tmp != null) //noinspection ResultOfMethodCallIgnored
                tmp.delete();
        }
    }

    private static HttpURLConnection open(String url, Cfg cfg) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(60000);
        conn.setRequestProperty("User-Agent", "DSH-Pet/2.1");
        String user = cfg.str("davUser", "");
        String pass = cfg.str("davPass", "");
        if (!user.isEmpty()) {
            String auth = user + ":" + pass;
            String enc = Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));
            conn.setRequestProperty("Authorization", "Basic " + enc);
        }
        return conn;
    }
}
