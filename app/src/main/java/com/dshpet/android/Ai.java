package com.dshpet.android;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** DeepSeek / OpenAI 兼容接口。Key 只在本地 S-G 中。 */
public final class Ai {
    public interface ImageCb {
        void ok(byte[] imageBytes);
        void fail(String err);
    }
    public interface Cb {
        void ok(String text);

        void fail(String err);

        /** Partial assistant text from providers that support OpenAI-compatible SSE. */
        default void progress(String delta) { }

        /** Exact usage reported by the provider, when available. */
        default void usage(int totalTokens) { }
    }

    private static final ConcurrentMap<String, HttpURLConnection> ACTIVE_STREAMS = new ConcurrentHashMap<>();
    private static final Set<String> CANCELLED_STREAMS = ConcurrentHashMap.newKeySet();

    private Ai() {
    }

    /** OpenAI-compatible image generation. Uses a separately configured image provider, never a chat model. */
    public static void generateImage(Context c, String prompt, ImageCb cb) {
        Cfg cfg = Cfg.load(c);
        if ("comfyui".equalsIgnoreCase(cfg.str("imageGenProvider", "openai").trim())) {
            ComfyUi.generate(c, prompt, cb);
            return;
        }
        String key = cfg.str("imageGenApiKey", "").trim();
        String base = cfg.str("imageGenBaseUrl", "").trim();
        String model = cfg.str("imageGenModel", "").trim();
        if (key.isEmpty() || base.isEmpty() || model.isEmpty()) {
            cb.fail("还没有配置图像生成服务。请在「设置 - 智能核心 - 图像生成」填写服务地址、Key 和模型；聊天模型不能直接画图。");
            return;
        }
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        final String endpoint = base.endsWith("/images/generations") ? base : base + "/images/generations";
        final String apiKey = key;
        final String imageModel = model;
        new Thread(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("model", imageModel);
                body.put("prompt", prompt);
                body.put("n", 1);
                String response = request("POST", endpoint, apiKey, body.toString(), 180000);
                JSONArray data = new JSONObject(response).optJSONArray("data");
                if (data == null || data.length() == 0) throw new Exception("图像服务没有返回图片");
                JSONObject item = data.optJSONObject(0);
                if (item == null) throw new Exception("图像服务返回格式异常");
                String b64 = item.optString("b64_json", "");
                byte[] bytes;
                if (!b64.isEmpty()) {
                    bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT);
                } else {
                    String imageUrl = item.optString("url", "");
                    if (imageUrl.isEmpty()) throw new Exception("图像服务没有返回图片数据");
                    bytes = fetchGeneratedImage(imageUrl);
                }
                if (bytes.length < 128 || bytes.length > 12 * 1024 * 1024) throw new Exception("图片数据为空或超出 12 MB 限制");
                cb.ok(bytes);
            } catch (Throwable t) {
                cb.fail(friendly(t));
            }
        }, "dsh-image-gen").start();
    }

    private static byte[] fetchGeneratedImage(String imageUrl) throws Exception {
        URL url = new URL(imageUrl);
        if (!"https".equalsIgnoreCase(url.getProtocol())) throw new Exception("图像服务返回了不安全的图片地址");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(60000);
        conn.setRequestProperty("Accept", "image/*");
        try (InputStream in = conn.getInputStream(); java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int n;
            while ((n = in.read(buffer)) != -1) {
                if (out.size() + n > 12 * 1024 * 1024) throw new Exception("生成图片超过 12 MB 限制");
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        } finally {
            conn.disconnect();
        }
    }

    public static void chat(Context c, JSONArray messages, Cb cb) {
        chat(c, messages, 45000, cb);
    }

    /** Longer server timeout for authored documents; output length remains provider/model controlled. */
    public static void chatLong(Context c, JSONArray messages, Cb cb) {
        chat(c, messages, 180000, cb);
    }

    /** Stream ordinary chat text as it arrives; the caller still receives the complete final answer. */
    public static void chatStream(Context c, JSONArray messages, String requestId, Cb cb) {
        if (cb == null) return;
        new Thread(() -> {
            try {
                if (isStreamCancelled(requestId)) {
                    cb.fail("已停止生成");
                    return;
                }
                Cfg cfg = Cfg.load(c);
                if (!cfg.aiReady()) {
                    cb.fail("还没有绑定 API Key，去「设置 · 智能核心」里填一个吧。");
                    return;
                }
                String url = cfg.baseUrl() + "/chat/completions";
                String model = cfg.str("model", "deepseek-chat");
                JSONObject body = new JSONObject();
                body.put("model", model);
                body.put("messages", messages);
                body.put("temperature", 0.9);
                body.put("frequency_penalty", 0.3);
                body.put("presence_penalty", 0.2);
                body.put("stream", true);
                try {
                    String text = requestStream(url, cfg.apiKey(), body.toString(), 180000, requestId, cb);
                    if (isStreamCancelled(requestId)) cb.fail("已停止生成");
                    else if (text.trim().isEmpty()) cb.fail("回复为空");
                    else cb.ok(text);
                } catch (StreamHttpException e) {
                    if (isStreamCancelled(requestId)) cb.fail("已停止生成");
                    else if (e.unsupported) {
                        // Some OpenAI-compatible providers accept chat completions but not SSE.
                        String response = request("POST", url, cfg.apiKey(), nonStreamBody(body), 60000);
                        if (isStreamCancelled(requestId)) cb.fail("已停止生成");
                        else {
                            String text = parseContent(response);
                            reportUsage(response, cb);
                            if (text.isEmpty()) cb.fail("回复为空"); else cb.ok(text);
                        }
                    } else cb.fail(friendly(e));
                }
            } catch (Throwable t) {
                cb.fail(isStreamCancelled(requestId) ? "已停止生成" : friendly(t));
            } finally {
                if (requestId != null && !requestId.isEmpty()) {
                    ACTIVE_STREAMS.remove(requestId);
                    CANCELLED_STREAMS.remove(requestId);
                }
            }
        }, "dsh-ai-stream").start();
    }

    /** Stop a stream associated with a chat request. It is safe to call before the HTTP request opens. */
    public static void cancelStream(String requestId) {
        if (requestId == null || requestId.isEmpty()) return;
        CANCELLED_STREAMS.add(requestId);
        HttpURLConnection connection = ACTIVE_STREAMS.get(requestId);
        if (connection != null) connection.disconnect();
    }

    public static boolean isStreamCancelled(String requestId) {
        return requestId != null && !requestId.isEmpty() && CANCELLED_STREAMS.contains(requestId);
    }

    private static String nonStreamBody(JSONObject streamBody) throws Exception {
        JSONObject fallback = new JSONObject(streamBody.toString());
        fallback.put("stream", false);
        return fallback.toString();
    }

    private static final class StreamHttpException extends Exception {
        final boolean unsupported;
        StreamHttpException(int code, String body) {
            super("HTTP " + code + (body == null || body.isEmpty() ? "" : " " + body));
            unsupported = code == 400 || code == 404 || code == 405 || code == 415 || code == 422;
        }
    }

    private static String requestStream(String url, String key, String body, int timeout, String requestId, Cb cb)
            throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        if (requestId != null && !requestId.isEmpty()) ACTIVE_STREAMS.put(requestId, conn);
        StringBuilder answer = new StringBuilder();
        StringBuilder eventData = new StringBuilder();
        StringBuilder plain = new StringBuilder();
        try {
            if (isStreamCancelled(requestId)) throw new java.io.InterruptedIOException("已停止生成");
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(timeout);
            conn.setRequestProperty("Authorization", "Bearer " + key);
            conn.setRequestProperty("Accept", "text/event-stream, application/json");
            conn.setRequestProperty("Cache-Control", "no-cache");
            conn.setRequestProperty("User-Agent", "DSH-Pet/2.6.0");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream out = conn.getOutputStream()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
            int code = conn.getResponseCode();
            InputStream source = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
            if (code < 200 || code >= 300) {
                String error = readLimited(source, 512);
                throw new StreamHttpException(code, error);
            }
            if (source == null) throw new Exception("接口没有返回内容");
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(source, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (isStreamCancelled(requestId)) throw new java.io.InterruptedIOException("已停止生成");
                    if (line.isEmpty()) {
                        appendStreamEvent(eventData, answer, cb);
                        continue;
                    }
                    if (line.startsWith("data:")) {
                        if (eventData.length() > 0) eventData.append('\n');
                        String value = line.substring(5);
                        if (value.startsWith(" ")) value = value.substring(1);
                        eventData.append(value);
                    } else if (!line.startsWith(":")) {
                        plain.append(line).append('\n');
                    }
                }
            }
            appendStreamEvent(eventData, answer, cb);
            if (answer.length() == 0 && plain.length() > 0) {
                String parsed = parseContent(plain.toString());
                if (!parsed.isEmpty()) {
                    answer.append(parsed);
                    cb.progress(parsed);
                }
            }
            return answer.toString();
        } finally {
            if (requestId != null && !requestId.isEmpty()) ACTIVE_STREAMS.remove(requestId, conn);
            conn.disconnect();
        }
    }

    private static void appendStreamEvent(StringBuilder eventData, StringBuilder answer, Cb cb) throws Exception {
        if (eventData.length() == 0) return;
        String data = eventData.toString().trim();
        eventData.setLength(0);
        if (data.isEmpty() || "[DONE]".equals(data)) return;
        JSONObject frame;
        try { frame = new JSONObject(data); }
        catch (Throwable ignored) { return; }
        JSONArray choices = frame.optJSONArray("choices");
        if (choices == null || choices.length() == 0) return;
        JSONObject choice = choices.optJSONObject(0);
        if (choice == null) return;
        JSONObject delta = choice.optJSONObject("delta");
        if (delta == null) return;
        Object raw = delta.opt("content");
        String chunk = "";
        if (raw instanceof String) chunk = (String) raw;
        else if (raw instanceof JSONArray) {
            JSONArray parts = (JSONArray) raw;
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < parts.length(); i++) {
                JSONObject part = parts.optJSONObject(i);
                if (part != null) text.append(part.optString("text", ""));
            }
            chunk = text.toString();
        }
        if (!chunk.isEmpty()) {
            answer.append(chunk);
            cb.progress(chunk);
        }
    }

    private static String readLimited(InputStream in, int max) throws Exception {
        if (in == null) return "";
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null && out.length() < max) out.append(line);
        }
        if (out.length() > max) out.setLength(max);
        return out.toString();
    }

    private static void chat(Context c, JSONArray messages, int timeoutMs, Cb cb) {
        Cfg cfg = Cfg.load(c);
        if (!cfg.aiReady()) {
            cb.fail("还没有绑定 API Key，去「设置 · 智能核心」里填一个吧。");
            return;
        }
        final String key = cfg.apiKey();
        final String url = cfg.baseUrl() + "/chat/completions";
        final String model = cfg.str("model", "deepseek-chat");
        new Thread(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("model", model);
                body.put("messages", messages);
                body.put("temperature", 0.9);
                body.put("frequency_penalty", 0.3);
                body.put("presence_penalty", 0.2);
                // 不设 max_tokens：长度交给提示词控制，避免话说到一半被截断
                body.put("stream", false);
                String resp = request("POST", url, key, body.toString(), timeoutMs);
                if (timeoutMs > 45000 && outputTruncated(resp))
                    throw new Exception("回复达到模型单次输出上限，未保存不完整文档；可以让我接着上一段继续写。");
                reportUsage(resp, cb);
                String text = parseContent(resp);
                if (text.isEmpty()) throw new Exception("回复为空");
                cb.ok(text);
            } catch (Throwable t) {
                cb.fail(friendly(t));
            }
        }, "dsh-ai").start();
    }

    public static void rawChat(Context c, JSONArray messages, int maxTokens, Cb cb) {
        rawChat(c, messages, maxTokens, 0.8, cb);
    }

    /** temperature 可控版本：要 JSON 之类确定性输出时传低值。 */
    public static void rawChat(Context c, JSONArray messages, int maxTokens, double temperature, Cb cb) {
        Cfg cfg = Cfg.load(c);
        if (!cfg.aiReady()) {
            cb.fail("未绑定 API Key");
            return;
        }
        rawChat(cfg.baseUrl() + "/chat/completions", cfg.apiKey(),
                cfg.str("model", "deepseek-chat"), messages, maxTokens, temperature, cb);
    }

    /** 看图专用：配置了独立视觉模型就走它；没配时 DeepSeek 默认 deepseek-flash，别家沿用主模型。 */
    public static void rawChatVision(Context c, JSONArray messages, int maxTokens, Cb cb) {
        rawChatVision(c, messages, maxTokens, 0.8, cb);
    }

    public static void rawChatVision(Context c, JSONArray messages, int maxTokens, double temperature, Cb cb) {
        Cfg cfg = Cfg.load(c);
        if (!cfg.visionReady()) {
            cb.fail("未绑定 API Key");
            return;
        }
        rawChat(cfg.visionBase() + "/chat/completions", cfg.visionKey(),
                cfg.visionModelName(), messages, maxTokens, temperature, cb);
    }

    private static void rawChat(String url, String key, String model, JSONArray messages,
                                int maxTokens, double temperature, Cb cb) {
        new Thread(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("model", model);
                body.put("messages", messages);
                body.put("temperature", temperature);
                // maxTokens <= 0 表示不限长度（普通聊天用）；内部 JSON 类任务仍传具体值
                if (maxTokens > 0) body.put("max_tokens", maxTokens);
                body.put("stream", false);
                String resp = request("POST", url, key, body.toString(), 60000);
                reportUsage(resp, cb);
                cb.ok(parseContent(resp));
            } catch (Throwable t) {
                cb.fail(friendly(t));
            }
        }, "dsh-ai").start();
    }

    public static void balance(Context c, Cb cb) {
        balance(c, false, cb);
    }

    /** 视觉模型如果是另一家供应商，用它的地址和 Key 单独查余额。 */
    public static void balanceVision(Context c, Cb cb) {
        balance(c, true, cb);
    }

    private static void balance(Context c, final boolean vision, final Cb cb) {
        final Cfg cfg = Cfg.load(c);
        final String key = vision ? cfg.visionKey() : cfg.apiKey();
        final String url = balanceUrl(cfg, vision);
        if (key.isEmpty()) {
            cb.fail("未绑定 API Key");
            return;
        }
        new Thread(() -> {
            try {
                String resp = request("GET", url, key, null, 15000);
                cb.ok(parseBalance(new JSONObject(resp)));
            } catch (Throwable t) {
                String m = t.getMessage() == null ? "" : t.getMessage();
                if (m.contains("404")) {
                    cb.fail("这家没有公开的余额接口，可以去控制台看；知道路径的话填在「余额接口路径」里");
                } else {
                    cb.fail(friendly(t));
                }
            }
        }, "dsh-balance").start();
    }

    /** 文本模型和视觉模型的余额查询地址是否相同：相同就只查一次、只显示一条余额。 */
    public static boolean sameBalanceTarget(Context c) {
        try {
            Cfg cfg = Cfg.load(c);
            return balanceUrl(cfg, false).equals(balanceUrl(cfg, true));
        } catch (Throwable t) {
            return false;
        }
    }

    private static String balanceUrl(Cfg cfg, boolean vision) {
        String base = vision ? cfg.visionBase() : cfg.baseUrl();
        String path = cfg.str(vision ? "visionBalancePath" : "balancePath", "").trim();
        if (path.isEmpty()) path = defaultBalancePath(base);
        return path.startsWith("http") ? path : base + (path.startsWith("/") ? path : "/" + path);
    }

    /** 常见供应商的余额路径；不认识的先按 DeepSeek 格式试一次。 */
    private static String defaultBalancePath(String base) {
        String h = base.toLowerCase(java.util.Locale.US);
        if (h.contains("moonshot")) return "/users/me/balance";
        if (h.contains("siliconflow")) return "/user/info";
        if (h.contains("openrouter")) return "/credits";
        return "/user/balance";
    }

    /** 余额各家字段不一样，按 DeepSeek / Moonshot / 硅基流动 / OpenRouter 依次认，最后兜底找数字。 */
    private static String parseBalance(JSONObject o) {
        JSONArray a = o.optJSONArray("balance_infos");
        if (a != null && a.length() > 0) {
            JSONObject b = a.optJSONObject(0);
            if (b != null) {
                String v = b.optString("total_balance", "").trim();
                String cur = b.optString("currency", "CNY");
                if (!v.isEmpty()) return "余额 " + v + ("CNY".equalsIgnoreCase(cur) ? " 元" : " " + cur);
            }
        }
        JSONObject d = o.optJSONObject("data");
        JSONObject t = d != null ? d : o;
        String v = num(t, "totalBalance", "balance", "available_balance", "availableBalance", "remaining", "total_remaining");
        if (!v.isEmpty()) {
            String cur = t.optString("currency", "");
            return "余额 " + v + (cur.isEmpty() ? "" : " " + cur);
        }
        double credits = t.optDouble("total_credits", -1);
        if (credits >= 0) {
            double left = credits - t.optDouble("total_usage", 0);
            return "余额 " + trimNum(left) + " USD";
        }
        String deep = deepFind(o, 0);
        if (!deep.isEmpty()) return "余额 " + deep;
        return "余额信息不可用";
    }

    private static String num(JSONObject o, String... keys) {
        for (String k : keys) {
            if (!o.has(k)) continue;
            String s = o.optString(k, "").trim();
            if (!s.isEmpty() && !"null".equals(s) && !"NaN".equals(s)) return s;
        }
        return "";
    }

    /** 兜底：在 JSON 里找第一个名字像余额/额度/剩余的数值。 */
    private static String deepFind(Object node, int depth) {
        if (node == null || depth > 4) return "";
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                String lk = k.toLowerCase(java.util.Locale.US);
                if (!lk.contains("balance") && !lk.contains("credit") && !lk.contains("remain")) continue;
                Object val = o.opt(k);
                if (val instanceof Number) return trimNum(((Number) val).doubleValue());
                if (val instanceof String) {
                    String s = ((String) val).trim();
                    if (s.matches("-?\\d+(\\.\\d+)?")) return s;
                }
            }
            it = o.keys();
            while (it.hasNext()) {
                String r = deepFind(o.opt(it.next()), depth + 1);
                if (!r.isEmpty()) return r;
            }
        } else if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            for (int i = 0; i < arr.length(); i++) {
                String r = deepFind(arr.opt(i), depth + 1);
                if (!r.isEmpty()) return r;
            }
        }
        return "";
    }

    private static String trimNum(double v) {
        if (Math.abs(v - Math.rint(v)) < 0.000001) return String.valueOf((long) v);
        return String.format(java.util.Locale.US, "%.2f", v);
    }

    private static String parseContent(String resp) throws Exception {
        JSONObject o = new JSONObject(resp);
        JSONArray choices = o.optJSONArray("choices");
        if (choices == null || choices.length() == 0) {
            String err = o.optString("error", "");
            if (err.isEmpty()) err = o.optString("message", "");
            throw new Exception(err.isEmpty() ? "接口没有返回内容" : err);
        }
        JSONObject msg = choices.getJSONObject(0).optJSONObject("message");
        if (msg == null) throw new Exception("接口返回格式异常");
        String content = msg.optString("content", "");
        if (content == null) content = "";
        return content.trim();
    }

    private static boolean outputTruncated(String resp) {
        try {
            JSONArray choices = new JSONObject(resp).optJSONArray("choices");
            return choices != null && choices.length() > 0
                    && "length".equalsIgnoreCase(choices.optJSONObject(0).optString("finish_reason", ""));
        } catch (Throwable ignored) { return false; }
    }

    private static void reportUsage(String resp, Cb cb) {
        if (cb == null) return;
        try {
            JSONObject usage = new JSONObject(resp).optJSONObject("usage");
            if (usage == null) return;
            int total = usage.optInt("total_tokens", 0);
            if (total <= 0) total = usage.optInt("prompt_tokens", 0) + usage.optInt("completion_tokens", 0);
            if (total > 0) cb.usage(total);
        } catch (Throwable ignored) {
        }
    }

    private static String request(String method, String url, String key, String body, int timeout) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(timeout);
        conn.setRequestProperty("Authorization", "Bearer " + key);
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("User-Agent", "DSH-Pet/2.0");
        if (body != null) {
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream out = conn.getOutputStream()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        int code = conn.getResponseCode();
        InputStream in = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
        StringBuilder sb = new StringBuilder();
        if (in != null) {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
        }
        conn.disconnect();
        if (code < 200 || code >= 300) {
            String msg = sb.toString();
            if (msg.length() > 160) msg = msg.substring(0, 160);
            throw new Exception("HTTP " + code + (msg.isEmpty() ? "" : " " + msg));
        }
        return sb.toString();
    }

    private static String friendly(Throwable t) {
        String message = t == null ? "" : t.getMessage();
        if ((message == null || message.trim().isEmpty()) && t != null) message = t.getClass().getSimpleName();
        return userFacingError(message);
    }

    /** Converts provider/transport diagnostics into a short explanation with a practical next step. */
    public static String userFacingError(String raw) {
        String message = raw == null ? "" : raw.trim();
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("401") || lower.contains("invalid_api_key") || lower.contains("incorrect api key")
                || lower.contains("api key is invalid") || lower.contains("unauthorized"))
            return "我暂时没法通过聊天服务的身份校验。主人可以到「设置 → 智能核心」检查 API Key 是否填对或已过期。";
        if (lower.contains("402") || lower.contains("insufficient_quota") || lower.contains("quota exceeded")
                || lower.contains("insufficient balance") || lower.contains("billing") || lower.contains("余额不足"))
            return "聊天服务的可用额度可能不足了。主人可以检查服务商账户余额或套餐额度，补充后再试。";
        if (lower.contains("403") || lower.contains("permission denied") || lower.contains("forbidden")
                || lower.contains("无权限"))
            return "聊天服务拒绝了这次请求，可能是模型权限或 API Key 权限不足。请检查「设置 → 智能核心」里的模型和服务商权限。";
        if (lower.contains("429") || lower.contains("rate_limit") || lower.contains("rate limit")
                || lower.contains("too many requests"))
            return "聊天服务现在有点忙，或请求次数暂时达到上限。等一会儿再试，也可以检查服务商的调用额度。";
        if (lower.contains("503") || lower.contains("service_unavailable") || lower.contains("service is too busy")
                || lower.contains("overloaded") || lower.contains("temporarily unavailable"))
            return "聊天服务暂时繁忙或正在维护，这条消息没有得到回复。稍后重试，或到「设置 → 智能核心」切换可用的模型服务。";
        if (lower.contains("500") || lower.contains("502") || lower.contains("504")
                || lower.contains("bad gateway") || lower.contains("gateway timeout"))
            return "聊天服务暂时出了故障，这条消息没有处理成功。请稍后重试；如果一直如此，检查服务商状态或切换模型。";
        if (lower.contains("unable to resolve host") || lower.contains("unknownhost") || lower.contains("connection refused")
                || lower.contains("network is unreachable") || lower.contains("failed to connect") || lower.contains("连接失败"))
            return "我现在连不上聊天服务。请检查手机网络、服务地址和网络限制，再重新发送。";
        if (lower.contains("timeout") || lower.contains("timed out") || lower.contains("超时"))
            return "聊天服务响应超时了，这条消息没有处理完。请确认网络正常后稍等片刻再试。";
        if (lower.contains("回复为空") || lower.contains("empty response") || lower.contains("没有可显示的文字"))
            return "聊天服务这次没有返回可显示的内容。主人可以检查模型配置后再试一次。";
        if (message.contains("已停止生成") || message.contains("请先") || message.contains("还没有绑定 API Key"))
            return message.length() > 180 ? message.substring(0, 180) : message;
        boolean looksDiagnostic = message.startsWith("{") || message.startsWith("[")
                || lower.startsWith("http ") || lower.contains("request_id") || lower.contains("error\"")
                || lower.contains("exception") || lower.contains(" at com.") || lower.contains("stacktrace");
        if (!message.isEmpty() && !looksDiagnostic && message.length() <= 150 && !message.matches(".*\\b[45][0-9]{2}\\b.*"))
            return message;
        return "我这次没能顺利处理这条消息，可能是聊天服务暂不可用或配置需要检查。请稍后重试；若持续发生，检查「设置 → 智能核心」中的网络、API Key 和模型。";
    }
}
