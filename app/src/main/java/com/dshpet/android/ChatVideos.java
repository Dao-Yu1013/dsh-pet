package com.dshpet.android;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.util.UUID;

/** Local staging for user-selected videos. The original file is uploaded only when the user sends it. */
public final class ChatVideos {
    private static final String INDEX = "chat_videos.json";
    private static final long KEEP_MS = 7L * 24 * 60 * 60 * 1000;

    private ChatVideos() { }

    public static JSONObject importUri(Context context, Uri uri) {
        if (uri == null) return error("没有收到视频文件");
        String name = displayName(context, uri);
        String mime = context.getContentResolver().getType(uri);
        if (mime != null) mime = mime.trim().toLowerCase(java.util.Locale.ROOT);
        if (mime == null || !mime.startsWith("video/")) {
            mime = mimeFromName(name);
        }
        if (mime == null || !mime.startsWith("video/")) return error("选中的文件不是可识别的视频格式");

        String id = UUID.randomUUID().toString().replace("-", "");
        String ext = extension(name);
        File dir = new File(Sg.dir(context), "chat-videos");
        if (!dir.exists() && !dir.mkdirs()) return error("无法创建视频暂存目录，请检查手机存储空间");
        File raw = new File(dir, id + (ext.isEmpty() ? ".video" : "." + ext));
        long bytes = 0;
        try (InputStream input = context.getContentResolver().openInputStream(uri);
             java.io.FileOutputStream output = new java.io.FileOutputStream(raw)) {
            if (input == null) return error("无法读取这个视频，请重新选择文件");
            byte[] buffer = new byte[1024 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
                bytes += count;
            }
            output.getFD().sync();
        } catch (Throwable failure) {
            raw.delete();
            return error("视频暂存失败，可能是手机空间不足或文件不可读取：" + safeMessage(failure));
        }

        long now = System.currentTimeMillis();
        synchronized (ChatVideos.class) {
            JSONArray all = Sg.readArr(context, INDEX);
            JSONObject item = new JSONObject();
            try {
                item.put("id", id);
                item.put("name", name);
                item.put("path", raw.getName());
                item.put("mimeType", mime);
                item.put("bytes", bytes);
                item.put("createdAt", now);
                item.put("expiresAt", now + KEEP_MS);
                all.put(item);
                Sg.writeArr(context, INDEX, all);
            } catch (Throwable failure) {
                raw.delete();
                return error("无法保存视频附件记录");
            }
        }
        JSONObject out = new JSONObject();
        try {
            out.put("ok", true).put("id", id).put("name", name)
                    .put("mimeType", mime).put("bytes", bytes).put("expiresAt", now + KEEP_MS);
        } catch (Throwable ignored) { }
        return out;
    }

    public static File file(Context context, String id) {
        JSONObject item = find(context, id);
        if (item == null || item.optBoolean("expired", false)) return null;
        String path = item.optString("path", "");
        if (path.isEmpty()) return null;
        File file = new File(new File(Sg.dir(context), "chat-videos"), path);
        return file.isFile() ? file : null;
    }

    public static String mimeType(Context context, String id) {
        JSONObject item = find(context, id);
        return item == null ? "video/mp4" : item.optString("mimeType", "video/mp4");
    }

    public static String name(Context context, String id) {
        JSONObject item = find(context, id);
        return item == null ? "视频" : item.optString("name", "视频");
    }

    public static void delete(Context context, String id) {
        if (id == null || id.isEmpty()) return;
        synchronized (ChatVideos.class) {
            JSONArray old = Sg.readArr(context, INDEX);
            JSONArray kept = new JSONArray();
            File dir = new File(Sg.dir(context), "chat-videos");
            for (int i = 0; i < old.length(); i++) {
                JSONObject item = old.optJSONObject(i);
                if (item == null) continue;
                if (id.equals(item.optString("id", ""))) {
                    String path = item.optString("path", "");
                    if (!path.isEmpty()) new File(dir, path).delete();
                } else kept.put(item);
            }
            Sg.writeArr(context, INDEX, kept);
        }
    }

    public static void cleanup(Context context) {
        synchronized (ChatVideos.class) {
            long now = System.currentTimeMillis();
            JSONArray old = Sg.readArr(context, INDEX);
            JSONArray kept = new JSONArray();
            File dir = new File(Sg.dir(context), "chat-videos");
            for (int i = 0; i < old.length(); i++) {
                JSONObject item = old.optJSONObject(i);
                if (item == null) continue;
                long created = item.optLong("createdAt", now);
                if (now - created >= KEEP_MS) {
                    String path = item.optString("path", "");
                    if (!path.isEmpty()) new File(dir, path).delete();
                    item.remove("path");
                    try { item.put("expired", true); } catch (Throwable ignored) { }
                }
                kept.put(item);
            }
            Sg.writeArr(context, INDEX, kept);
        }
    }

    public static void enrich(Context context, JSONArray messages) {
        if (messages == null) return;
        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.optJSONObject(i);
            if (message == null) continue;
            String id = message.optString("videoId", "");
            if (id.isEmpty()) continue;
            JSONObject item = find(context, id);
            boolean expired = item == null || item.optBoolean("expired", false);
            try { message.put("videoExpired", expired); } catch (Throwable ignored) { }
        }
    }

    private static JSONObject find(Context context, String id) {
        if (id == null || id.isEmpty()) return null;
        JSONArray all = Sg.readArr(context, INDEX);
        for (int i = 0; i < all.length(); i++) {
            JSONObject item = all.optJSONObject(i);
            if (item != null && id.equals(item.optString("id", ""))) return item;
        }
        return null;
    }

    private static String displayName(Context context, Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (column >= 0) return cursor.getString(column);
            }
        } catch (Throwable ignored) { }
        String path = uri.getLastPathSegment();
        return path == null || path.isEmpty() ? "视频" : path;
    }

    private static String extension(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "";
        String ext = name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
        return ext.matches("[a-z0-9]{1,8}") ? ext : "";
    }

    private static String mimeFromName(String name) {
        String ext = extension(name);
        if ("mp4".equals(ext) || "m4v".equals(ext)) return "video/mp4";
        if ("mov".equals(ext)) return "video/quicktime";
        if ("webm".equals(ext)) return "video/webm";
        if ("mkv".equals(ext)) return "video/x-matroska";
        if ("avi".equals(ext)) return "video/x-msvideo";
        if ("3gp".equals(ext)) return "video/3gpp";
        if ("mpeg".equals(ext) || "mpg".equals(ext)) return "video/mpeg";
        return "";
    }

    private static JSONObject error(String message) {
        JSONObject out = new JSONObject();
        try { out.put("ok", false).put("error", message); } catch (Throwable ignored) { }
        return out;
    }

    private static String safeMessage(Throwable failure) {
        String message = failure == null ? "" : failure.getMessage();
        return message == null || message.trim().isEmpty() ? "请检查文件访问权限" : message.trim();
    }
}
