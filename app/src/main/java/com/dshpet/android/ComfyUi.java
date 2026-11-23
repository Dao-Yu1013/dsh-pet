package com.dshpet.android;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;

/** ComfyUI API-format workflow client for image generation on a trusted LAN. */
final class ComfyUi {
    private static final int MAX_WORKFLOW_BYTES = 1024 * 1024;
    private static final int MAX_JSON_BYTES = 4 * 1024 * 1024;
    private static final int MAX_IMAGE_BYTES = 12 * 1024 * 1024;
    private static final long MAX_WAIT_MS = 10L * 60L * 1000L;
    private static final SecureRandom RANDOM = new SecureRandom();

    private ComfyUi() { }

    static boolean isConfigured(Cfg cfg) {
        return cfg != null
                && !cfg.str("comfyUiBaseUrl", "").trim().isEmpty()
                && !cfg.str("comfyUiWorkflow", "").trim().isEmpty();
    }

    static void generate(Context context, String userPrompt, Ai.ImageCb callback) {
        if (callback == null) return;
        Cfg cfg = Cfg.load(context);
        String base = normalizeBase(cfg.str("comfyUiBaseUrl", ""));
        String source = cfg.str("comfyUiWorkflow", "").trim();
        String nodeIds = cfg.str("comfyUiPromptNodeIds", "").trim();
        if (base.isEmpty() || source.isEmpty()) {
            callback.fail("还没有配置 ComfyUI。请在「设置 - 智能核心 - 图像生成」填写电脑地址，并导入 ComfyUI 的 API 工作流。");
            return;
        }
        final String server = base;
        final String workflowJson = source;
        final String promptNodeIds = nodeIds;
        final String prompt = userPrompt == null ? "" : userPrompt.trim();
        new Thread(() -> run(server, workflowJson, promptNodeIds, prompt, callback), "dsh-comfyui-image").start();
    }

    private static void run(String base, String workflowJson, String configuredNodeIds,
                            String userPrompt, Ai.ImageCb callback) {
        try {
            if (workflowJson.getBytes(StandardCharsets.UTF_8).length > MAX_WORKFLOW_BYTES) {
                throw new FriendlyException("工作流超过 1 MB，无法安全提交；请删除无关节点后重新导出 API 工作流。");
            }
            JSONObject workflow;
            try {
                workflow = new JSONObject(workflowJson);
            } catch (Throwable invalid) {
                throw new FriendlyException("ComfyUI 工作流 JSON 读不出来。请重新导出 ComfyUI 的「API 格式」工作流并粘贴保存。");
            }
            if (workflow.length() == 0 || workflow.has("nodes")) {
                throw new FriendlyException("这看起来是 ComfyUI 编辑器工作流，不是 API 工作流。请在 ComfyUI 中导出「API 格式」后再导入。");
            }

            Set<String> promptNodes = configuredNodeIds.isEmpty()
                    ? findPositivePromptNodes(workflow) : parseNodeIds(configuredNodeIds);
            if (promptNodes.isEmpty()) {
                throw new FriendlyException("没找到工作流的正向提示词节点。请填写 ComfyUI API 工作流里正向文本编码节点的 ID。");
            }
            for (String id : promptNodes) {
                JSONObject node = workflow.optJSONObject(id);
                JSONObject inputs = node == null ? null : node.optJSONObject("inputs");
                if (inputs == null) throw new FriendlyException("提示词节点 ID「" + id + "」不在当前工作流里，请检查设置。");
                String field = inputs.has("text") ? "text" : (inputs.has("prompt") ? "prompt" : "");
                if (field.isEmpty()) throw new FriendlyException("提示词节点「" + id + "」没有 text/prompt 输入，请更换节点 ID。");
                String template = inputs.optString(field, "");
                String value = template.contains("{{prompt}}")
                        ? template.replace("{{prompt}}", userPrompt) : userPrompt;
                inputs.put(field, value);
            }
            randomizeSeeds(workflow);

            String clientId = UUID.randomUUID().toString();
            JSONObject request = new JSONObject();
            request.put("prompt", workflow);
            request.put("client_id", clientId);
            JSONObject accepted;
            try {
                accepted = requestJson("POST", base + "/prompt", request);
            } catch (HttpFailure e) {
                if (e.code == 400) {
                    throw new FriendlyException("ComfyUI 拒绝了这个工作流，可能缺少模型或自定义节点。请检查 ComfyUI 页面中的工作流错误。" + detail(e.body));
                }
                throw e;
            }
            String promptId = accepted.optString("prompt_id", "").trim();
            if (promptId.isEmpty()) {
                throw new FriendlyException("ComfyUI 没有接受生成任务。请检查工作流节点连接和模型配置。");
            }

            long deadline = System.currentTimeMillis() + MAX_WAIT_MS;
            while (System.currentTimeMillis() < deadline) {
                JSONObject history;
                try {
                    history = requestJson("GET", base + "/history/" + encode(promptId), null);
                } catch (HttpFailure e) {
                    if (e.code == 404) {
                        sleep(1400L);
                        continue;
                    }
                    throw e;
                }
                JSONObject result = history.optJSONObject(promptId);
                if (result != null) {
                    JSONObject status = result.optJSONObject("status");
                    String statusText = status == null ? "" : status.optString("status_str", "");
                    boolean completed = status != null && status.optBoolean("completed", false);
                    if ("error".equalsIgnoreCase(statusText)) {
                        throw new FriendlyException("ComfyUI 执行工作流失败，请检查缺少的模型、自定义节点或节点参数。" + executionDetail(status));
                    }
                    JSONObject outputs = result.optJSONObject("outputs");
                    ImageRef image = firstImage(outputs);
                    if (image != null) {
                        callback.ok(downloadImage(base, image));
                        return;
                    }
                    if (completed || "success".equalsIgnoreCase(statusText)) {
                        throw new FriendlyException("工作流已运行结束，但没有返回图片。请在 API 工作流末尾添加 SaveImage 或 PreviewImage 节点。");
                    }
                }
                sleep(1400L);
            }
            throw new FriendlyException("ComfyUI 生成超过 10 分钟还没有完成。请检查电脑上的队列、显卡状态和工作流。");
        } catch (Throwable error) {
            callback.fail(friendly(error));
        }
    }

    private static Set<String> findPositivePromptNodes(JSONObject workflow) {
        Set<String> positiveRoots = new HashSet<>();
        Set<String> negativeRoots = new HashSet<>();
        // Walk graph links from sampler positive/negative inputs to their text encoders.
        Iterator<String> ids = workflow.keys();
        while (ids.hasNext()) {
            String id = ids.next();
            JSONObject node = workflow.optJSONObject(id);
            JSONObject inputs = node == null ? null : node.optJSONObject("inputs");
            if (inputs == null) continue;
            Iterator<String> names = inputs.keys();
            while (names.hasNext()) {
                String originalName = names.next();
                String name = originalName.toLowerCase(java.util.Locale.ROOT);
                Object value = inputs.opt(originalName);
                if ("positive".equals(name)) addLinkRoot(value, positiveRoots, workflow);
                else if ("negative".equals(name)) addLinkRoot(value, negativeRoots, workflow);
            }
        }
        Set<String> positive = new HashSet<>();
        Set<String> negative = new HashSet<>();
        for (String root : positiveRoots) collectTextEncoders(workflow, root, positive, new HashSet<>());
        for (String root : negativeRoots) collectTextEncoders(workflow, root, negative, new HashSet<>());
        // A negative ConditioningZeroOut node may be wired from the same positive
        // encoder. Keep encoders reachable from a sampler's positive input.
        if (!positive.isEmpty()) return positive;

        // Workflows without explicit sampler positive/negative inputs are common in newer graph layouts.
        Set<String> all = new HashSet<>();
        ids = workflow.keys();
        while (ids.hasNext()) {
            String id = ids.next();
            if (isTextEncoder(workflow.optJSONObject(id))) all.add(id);
        }
        all.removeAll(negative);
        if (all.size() == 1) return all;
        return new HashSet<>();
    }

    private static void addLinkRoot(Object value, Set<String> roots, JSONObject workflow) {
        if (!(value instanceof JSONArray)) return;
        JSONArray link = (JSONArray) value;
        String id = link.optString(0, "");
        if (!id.isEmpty() && workflow.has(id)) roots.add(id);
    }

    private static void collectTextEncoders(JSONObject workflow, String id, Set<String> found, Set<String> visited) {
        if (id == null || id.isEmpty() || !visited.add(id)) return;
        JSONObject node = workflow.optJSONObject(id);
        if (node == null) return;
        if (isTextEncoder(node)) {
            found.add(id);
            return;
        }
        JSONObject inputs = node.optJSONObject("inputs");
        if (inputs == null) return;
        Iterator<String> keys = inputs.keys();
        while (keys.hasNext()) {
            Object value = inputs.opt(keys.next());
            if (value instanceof JSONArray) {
                String linkedId = ((JSONArray) value).optString(0, "");
                if (workflow.has(linkedId)) collectTextEncoders(workflow, linkedId, found, visited);
            }
        }
    }

    private static boolean isTextEncoder(JSONObject node) {
        if (node == null) return false;
        String type = node.optString("class_type", "").toLowerCase(java.util.Locale.ROOT);
        JSONObject inputs = node.optJSONObject("inputs");
        return inputs != null && (type.contains("textencode") || type.contains("cliptextencode"))
                && (inputs.has("text") || inputs.has("prompt"));
    }

    private static Set<String> parseNodeIds(String value) {
        Set<String> ids = new HashSet<>();
        for (String id : value.split("[,，;；\\s]+")) {
            String trimmed = id.trim();
            if (!trimmed.isEmpty()) ids.add(trimmed);
        }
        return ids;
    }

    private static void randomizeSeeds(JSONObject workflow) throws Exception {
        long seed = RANDOM.nextLong() & Long.MAX_VALUE;
        Iterator<String> ids = workflow.keys();
        while (ids.hasNext()) {
            JSONObject node = workflow.optJSONObject(ids.next());
            JSONObject inputs = node == null ? null : node.optJSONObject("inputs");
            if (inputs == null) continue;
            Iterator<String> names = inputs.keys();
            while (names.hasNext()) {
                String name = names.next();
                String lower = name.toLowerCase(java.util.Locale.ROOT);
                if ("seed".equals(lower) || "noise_seed".equals(lower)) inputs.put(name, seed++);
            }
        }
    }

    private static ImageRef firstImage(JSONObject outputs) {
        if (outputs == null) return null;
        Iterator<String> ids = outputs.keys();
        while (ids.hasNext()) {
            JSONObject output = outputs.optJSONObject(ids.next());
            JSONArray images = output == null ? null : output.optJSONArray("images");
            if (images == null || images.length() == 0) continue;
            JSONObject image = images.optJSONObject(0);
            if (image == null) continue;
            String filename = image.optString("filename", "");
            if (filename.isEmpty()) continue;
            return new ImageRef(filename, image.optString("subfolder", ""), image.optString("type", "output"));
        }
        return null;
    }

    private static byte[] downloadImage(String base, ImageRef image) throws Exception {
        String query = "filename=" + encode(image.filename)
                + "&subfolder=" + encode(image.subfolder)
                + "&type=" + encode(image.type);
        HttpURLConnection connection = open("GET", base + "/view?" + query);
        try {
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) throw new HttpFailure(code, readText(connection.getErrorStream(), 4096));
            byte[] bytes = readBytes(connection.getInputStream(), MAX_IMAGE_BYTES);
            if (bytes.length < 128) throw new FriendlyException("ComfyUI 返回的图片数据为空或损坏。");
            return bytes;
        } finally {
            connection.disconnect();
        }
    }

    private static JSONObject requestJson(String method, String address, JSONObject body) throws Exception {
        HttpURLConnection connection = open(method, address);
        try {
            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                if (bytes.length > MAX_JSON_BYTES) throw new FriendlyException("ComfyUI 请求数据超过大小限制。");
                try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
            }
            int code = connection.getResponseCode();
            InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String response = new String(readBytes(stream, MAX_JSON_BYTES), StandardCharsets.UTF_8);
            if (code < 200 || code >= 300) throw new HttpFailure(code, response);
            try { return new JSONObject(response); }
            catch (Throwable invalid) { throw new FriendlyException("ComfyUI 返回了无法识别的结果，请检查服务版本和地址。"); }
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection open(String method, String address) throws Exception {
        URL url = new URL(address);
        String protocol = url.getProtocol();
        if (!"http".equalsIgnoreCase(protocol) && !"https".equalsIgnoreCase(protocol)) {
            throw new FriendlyException("ComfyUI 地址只能使用 http 或 https。");
        }
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(20000);
        connection.setUseCaches(false);
        connection.setRequestProperty("Accept", "application/json, image/*");
        return connection;
    }

    private static byte[] readBytes(InputStream input, int limit) throws Exception {
        if (input == null) return new byte[0];
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) {
                if (out.size() + count > limit) throw new FriendlyException("ComfyUI 返回的数据超过大小限制。");
                out.write(buffer, 0, count);
            }
            return out.toByteArray();
        }
    }

    private static String readText(InputStream stream, int limit) {
        try { return new String(readBytes(stream, limit), StandardCharsets.UTF_8); }
        catch (Throwable ignored) { return ""; }
    }

    private static String executionDetail(JSONObject status) {
        JSONArray messages = status == null ? null : status.optJSONArray("messages");
        if (messages == null) return "";
        for (int i = messages.length() - 1; i >= 0; i--) {
            JSONArray item = messages.optJSONArray(i);
            if (item == null || item.length() < 2) continue;
            String type = item.optString(0, "");
            if (!"execution_error".equals(type)) continue;
            JSONObject data = item.optJSONObject(1);
            if (data == null) continue;
            String message = data.optString("exception_message", "").trim();
            if (!message.isEmpty()) return "详情：" + shortText(message);
        }
        return "";
    }

    private static String detail(String response) {
        if (response == null || response.trim().isEmpty()) return "";
        try {
            JSONObject json = new JSONObject(response);
            JSONObject error = json.optJSONObject("error");
            if (error != null) {
                String message = error.optString("message", "").trim();
                if (!message.isEmpty()) return " 详情：" + shortText(message);
            }
            String message = json.optString("message", "").trim();
            if (!message.isEmpty()) return " 详情：" + shortText(message);
        } catch (Throwable ignored) { }
        return "";
    }

    private static String shortText(String text) {
        String clean = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        return clean.length() > 220 ? clean.substring(0, 220) + "…" : clean;
    }

    private static String friendly(Throwable error) {
        if (error instanceof FriendlyException) return error.getMessage();
        if (error instanceof HttpFailure) {
            HttpFailure failure = (HttpFailure) error;
            if (failure.code == 404) return "找不到 ComfyUI 接口。请确认电脑地址和端口（默认 8188）填写正确。";
            if (failure.code == 401 || failure.code == 403) return "ComfyUI 拒绝了连接。请检查服务端访问设置和网络权限。";
            return "ComfyUI 服务暂时没有完成请求。请检查服务状态和工作流。" + detail(failure.body);
        }
        String message = error == null || error.getMessage() == null ? "" : error.getMessage().trim();
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("unable to resolve host") || lower.contains("failed to connect")
                || lower.contains("connection refused") || lower.contains("timed out")
                || lower.contains("cleartext http traffic") || lower.contains("no route to host")
                || lower.contains("network is unreachable") || lower.contains("connection reset")) {
            return "连不上 ComfyUI。请确认电脑和手机在同一局域网、ComfyUI 已开启局域网访问，并检查电脑 IP、端口和防火墙。";
        }
        if (message.isEmpty()) return "ComfyUI 绘图没有完成。请检查电脑上的服务和工作流后重试。";
        return "ComfyUI 绘图失败：" + shortText(message);
    }

    private static String normalizeBase(String address) {
        String base = address == null ? "" : address.trim();
        if (base.isEmpty()) return "";
        if (!base.contains("://")) base = "http://" + base;
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base;
    }

    private static String encode(String value) throws Exception {
        return URLEncoder.encode(value == null ? "" : value, "UTF-8");
    }

    private static void sleep(long millis) throws FriendlyException {
        try { Thread.sleep(millis); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new FriendlyException("ComfyUI 绘图已停止。");
        }
    }

    private static final class ImageRef {
        final String filename;
        final String subfolder;
        final String type;
        ImageRef(String filename, String subfolder, String type) {
            this.filename = filename;
            this.subfolder = subfolder;
            this.type = type;
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
