package com.dshpet.android;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Native video understanding through Gemini Files API + Interactions API. */
final class GeminiVideo {
    private static final String BASE = "https://generativelanguage.googleapis.com";
    private static final long FILE_WAIT_MS = 20L * 60L * 1000L;
    private static final long INTERACTION_WAIT_MS = 25L * 60L * 1000L;
    private static final int MAX_JSON_BYTES = 8 * 1024 * 1024;

    private GeminiVideo() { }

    static boolean isConfigured(Cfg cfg) {
        return cfg != null && !cfg.str("geminiVideoApiKey", "").trim().isEmpty();
    }

    static void analyze(Context context, File video, String mimeType, String prompt, Ai.Cb callback) {
        if (callback == null) return;
        Cfg cfg = Cfg.load(context);
        String key = cfg.str("geminiVideoApiKey", "").trim();
        String model = cfg.str("geminiVideoModel", "gemini-3.8-flash").trim();
        if (key.isEmpty()) {
            callback.fail("要分析整段视频和音轨，需要先在「设置 → 智能核心 → 完整视频理解」填写 Gemini API Key。应用不会抽几张截图来代替视频。");
            return;
        }
        if (video == null || !video.isFile() || video.length() <= 0) {
            callback.fail("本机暂存的视频已经找不到或为空，请重新选择后发送。");
            return;
        }
        final String apiKey = key;
        final String chosenModel = model.isEmpty() ? "gemini-3.8-flash" : model;
        final String mime = mimeType == null || !mimeType.startsWith("video/") ? "video/mp4" : mimeType;
        final String instructions = prompt == null ? "请完整理解这段视频并用中文回应。" : prompt;
        new Thread(() -> run(video, mime, instructions, apiKey, chosenModel, callback), "dsh-video-understanding").start();
    }

    private static void run(File localVideo, String mimeType, String prompt, String apiKey,
                            String model, Ai.Cb callback) {
        UploadedFile uploaded = null;
        String answer = "";
        try {
            uploaded = upload(localVideo, mimeType, apiKey);
            uploaded = waitUntilReady(uploaded, apiKey);
            JSONObject body = new JSONObject();
            body.put("model", model);
            body.put("background", true);
            body.put("store", false);
            JSONArray input = new JSONArray();
            input.put(new JSONObject().put("type", "video").put("uri", uploaded.uri)
                    .put("mime_type", uploaded.mimeType)
                    // Static mode considers the full clip timeline in one pass; no app-side keyframe extraction.
                    .put("processing", "static"));
            // Gemini recommends placing the text after the video part when sending one video.
            input.put(new JSONObject().put("type", "text").put("text", prompt));
            body.put("input", input);
            JSONObject created = requestJson("POST", BASE + "/v1beta/interactions", apiKey, body);
            String interactionId = created.optString("id", "").trim();
            if (interactionId.isEmpty()) throw new FriendlyException("Gemini 没有接受视频分析任务，请检查视频格式、模型名称和 API Key 权限。");
            answer = interactionText(created);
            if (answer.isEmpty()) answer = waitInteraction(interactionId, apiKey);
            if (answer.trim().isEmpty()) throw new FriendlyException("视频处理完成了，但模型没有返回文字。请换一个可用的视频理解模型后重试。");
        } catch (Throwable failure) {
            callback.fail(friendly(failure));
            return;
        } finally {
            if (uploaded != null) deleteRemote(uploaded.name, apiKey);
        }
        callback.ok(answer.trim());
    }

    private static UploadedFile upload(File source, String mimeType, String apiKey) throws Exception {
        JSONObject metadata = new JSONObject();
        metadata.put("file", new JSONObject().put("display_name", source.getName()));
        byte[] json = metadata.toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection start = open("POST", BASE + "/upload/v1beta/files", apiKey, 30000);
        String uploadUrl;
        try {
            start.setDoOutput(true);
            start.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            start.setRequestProperty("X-Goog-Upload-Protocol", "resumable");
            start.setRequestProperty("X-Goog-Upload-Command", "start");
            start.setRequestProperty("X-Goog-Upload-Header-Content-Length", Long.toString(source.length()));
            start.setRequestProperty("X-Goog-Upload-Header-Content-Type", mimeType);
            start.setFixedLengthStreamingMode(json.length);
            try (OutputStream out = start.getOutputStream()) { out.write(json); }
            int status = start.getResponseCode();
            if (status < 200 || status >= 300) throw httpFailure(status, readText(start.getErrorStream()));
            uploadUrl = start.getHeaderField("X-Goog-Upload-URL");
            if (uploadUrl == null || uploadUrl.trim().isEmpty()) {
                throw new FriendlyException("Gemini 没有创建视频上传通道，请稍后重试。");
            }
        } finally {
            start.disconnect();
        }

        HttpURLConnection send = open("POST", uploadUrl, "", 0);
        try {
            send.setDoOutput(true);
            send.setRequestProperty("Content-Type", mimeType);
            send.setRequestProperty("X-Goog-Upload-Offset", "0");
            send.setRequestProperty("X-Goog-Upload-Command", "upload, finalize");
            send.setFixedLengthStreamingMode(source.length());
            try (InputStream in = new FileInputStream(source); OutputStream out = send.getOutputStream()) {
                byte[] buffer = new byte[1024 * 1024];
                int count;
                while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
                out.flush();
            }
            int status = send.getResponseCode();
            String response = readText(status >= 400 ? send.getErrorStream() : send.getInputStream());
            if (status < 200 || status >= 300) throw httpFailure(status, response);
            JSONObject root = new JSONObject(response);
            JSONObject file = root.optJSONObject("file");
            if (file == null) file = root;
            String name = file.optString("name", "").trim();
            String uri = file.optString("uri", "").trim();
            String remoteMime = file.optString("mimeType", mimeType).trim();
            if (name.isEmpty() || uri.isEmpty()) throw new FriendlyException("Gemini 上传完成后没有返回可分析的视频文件信息。");
            return new UploadedFile(name, uri, remoteMime.isEmpty() ? mimeType : remoteMime,
                    file.optString("state", ""));
        } catch (org.json.JSONException invalid) {
            throw new FriendlyException("Gemini 返回的上传结果无法识别，请稍后重试。");
        } finally {
            send.disconnect();
        }
    }

    private static UploadedFile waitUntilReady(UploadedFile uploaded, String apiKey) throws Exception {
        long deadline = System.currentTimeMillis() + FILE_WAIT_MS;
        UploadedFile current = uploaded;
        while (System.currentTimeMillis() < deadline) {
            String state = current.state.toUpperCase(Locale.ROOT);
            if ("ACTIVE".equals(state)) return current;
            if ("FAILED".equals(state)) throw new FriendlyException("Gemini 无法处理这个视频编码。请将视频转成 MP4（H.264/AAC）后重试。");
            sleep(2500L);
            JSONObject status = requestJson("GET", BASE + "/v1beta/" + current.name, apiKey, null);
            current = new UploadedFile(status.optString("name", current.name),
                    status.optString("uri", current.uri), status.optString("mimeType", current.mimeType),
                    status.optString("state", current.state));
            if (current.uri.isEmpty()) throw new FriendlyException("Gemini 没有准备好视频文件，请检查视频格式后重试。");
        }
        throw new FriendlyException("视频上传成功，但云端解码超过 20 分钟。可稍后重试或转成更常见的 MP4 格式。");
    }

    private static String waitInteraction(String id, String apiKey) throws Exception {
        long deadline = System.currentTimeMillis() + INTERACTION_WAIT_MS;
        while (System.currentTimeMillis() < deadline) {
            sleep(2500L);
            JSONObject response = requestJson("GET", BASE + "/v1beta/interactions/" + id, apiKey, null);
            String status = response.optString("status", "").toLowerCase(Locale.ROOT);
            if ("completed".equals(status)) return interactionText(response);
            if ("failed".equals(status) || "cancelled".equals(status)) {
                throw new FriendlyException("Gemini 视频理解任务没有完成。请检查模型是否支持视频、视频编码和账号可用额度。" + detail(response));
            }
        }
        throw new FriendlyException("视频很长或服务繁忙，Gemini 分析超过 25 分钟。稍后重试，或使用较短的视频。");
    }

    private static String interactionText(JSONObject interaction) {
        String direct = interaction.optString("output_text", "").trim();
        if (!direct.isEmpty()) return direct;
        JSONArray steps = interaction.optJSONArray("steps");
        if (steps == null) return "";
        StringBuilder output = new StringBuilder();
        for (int i = 0; i < steps.length(); i++) {
            JSONObject step = steps.optJSONObject(i);
            if (step == null || !"model_output".equals(step.optString("type", ""))) continue;
            JSONArray content = step.optJSONArray("content");
            if (content == null) continue;
            for (int j = 0; j < content.length(); j++) {
                JSONObject item = content.optJSONObject(j);
                if (item == null || !"text".equals(item.optString("type", ""))) continue;
                String text = item.optString("text", "").trim();
                if (!text.isEmpty()) {
                    if (output.length() > 0) output.append('\n');
                    output.append(text);
                }
            }
        }
        return output.toString().trim();
    }

    private static void deleteRemote(String name, String apiKey) {
        if (name == null || name.isEmpty()) return;
        HttpURLConnection connection = null;
        try {
            connection = open("DELETE", BASE + "/v1beta/" + name, apiKey, 15000);
            connection.getResponseCode();
        } catch (Throwable ignored) {
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static JSONObject requestJson(String method, String url, String apiKey, JSONObject body) throws Exception {
        HttpURLConnection connection = open(method, url, apiKey, 45000);
        try {
            if (body != null) {
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream out = connection.getOutputStream()) { out.write(bytes); }
            }
            int status = connection.getResponseCode();
            String response = readText(status >= 400 ? connection.getErrorStream() : connection.getInputStream());
            if (status < 200 || status >= 300) throw httpFailure(status, response);
            try { return new JSONObject(response); }
            catch (Throwable invalid) { throw new FriendlyException("Gemini 返回了无法识别的结果，请检查视频服务接口状态。"); }
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection open(String method, String url, String apiKey, int readTimeout) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(30000);
        connection.setReadTimeout(readTimeout);
        connection.setUseCaches(false);
        connection.setRequestProperty("Accept", "application/json");
        if (apiKey != null && !apiKey.isEmpty()) connection.setRequestProperty("x-goog-api-key", apiKey);
        return connection;
    }

    private static String readText(InputStream input) throws Exception {
        if (input == null) return "";
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) {
                if (out.size() + count > MAX_JSON_BYTES) throw new FriendlyException("Gemini 返回的数据异常过大。");
                out.write(buffer, 0, count);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static HttpFailure httpFailure(int code, String body) {
        return new HttpFailure(code, body);
    }

    private static String detail(JSONObject response) {
        JSONObject error = response == null ? null : response.optJSONObject("error");
        if (error != null) {
            String message = error.optString("message", "").replaceAll("\\s+", " ").trim();
            if (!message.isEmpty()) return "详情：" + (message.length() > 160 ? message.substring(0, 160) + "…" : message);
        }
        return "";
    }

    private static String detail(String responseBody) {
        if (responseBody == null || responseBody.trim().isEmpty()) return "";
        try { return detail(new JSONObject(responseBody)); }
        catch (Throwable ignored) { return ""; }
    }

    private static String friendly(Throwable failure) {
        if (failure instanceof FriendlyException) return failure.getMessage();
        if (failure instanceof HttpFailure) {
            HttpFailure http = (HttpFailure) failure;
            if (http.code == 400 || http.code == 415 || http.code == 422)
                return "Gemini 不接受这个视频或当前模型不支持该输入格式。请使用常见 MP4 视频，并检查模型名称。" + detail(http.body);
            if (http.code == 401 || http.code == 403)
                return "Gemini API Key 无效或没有视频理解权限。请检查「设置 → 智能核心 → 完整视频理解」里的 Key。";
            if (http.code == 404) return "找不到 Gemini 视频接口或模型。请检查模型名称和视频服务配置。";
            if (http.code == 429) return "Gemini 视频理解请求过多或额度暂时不足。请稍后再试，或检查账号额度。";
            if (http.code >= 500) return "Gemini 视频服务暂时繁忙，请稍后重试。";
            return "Gemini 视频服务没有完成请求。" + detail(http.body);
        }
        String message = failure == null || failure.getMessage() == null ? "" : failure.getMessage().trim();
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("unable to resolve") || lower.contains("failed to connect")
                || lower.contains("connection refused") || lower.contains("timed out")
                || lower.contains("network is unreachable") || lower.contains("connection reset"))
            return "手机连不上 Gemini 视频服务。请检查网络是否能访问 Google AI 服务，以及 API Key 和模型设置。";
        if (message.isEmpty()) return "视频分析没有完成，请检查网络、视频格式和 Gemini 配置后重试。";
        return "视频分析失败：" + (message.length() > 180 ? message.substring(0, 180) + "…" : message);
    }

    private static void sleep(long duration) throws FriendlyException {
        try { Thread.sleep(duration); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new FriendlyException("视频分析已停止。");
        }
    }

    private static final class UploadedFile {
        final String name;
        final String uri;
        final String mimeType;
        final String state;
        UploadedFile(String name, String uri, String mimeType, String state) {
            this.name = name;
            this.uri = uri;
            this.mimeType = mimeType;
            this.state = state == null ? "" : state;
        }
    }

    private static final class FriendlyException extends Exception {
        FriendlyException(String message) { super(message); }
    }

    private static final class HttpFailure extends Exception {
        final int code;
        final String body;
        HttpFailure(int code, String body) {
            super("HTTP " + code);
            this.code = code;
            this.body = body == null ? "" : body;
        }
    }
}
