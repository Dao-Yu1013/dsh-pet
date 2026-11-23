package com.dshpet.android;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Uses the configured model to distinguish ordinary conversation from native actions. */
public final class IntentRouter {
    public static final String CHAT = "chat", WEATHER = "weather", TRAVEL = "travel", WRITE = "write",
            SCREEN = "screen", LOCAL = "local", PLUGIN = "plugin", DRAW = "draw",
            NEARBY_FOOD = "nearby_food", NEARBY_PLACE = "nearby_place", NEARBY_SHOP = "nearby_shop";
    private static final long MODEL_WAIT_MS = 12000L;
    private IntentRouter() { }

    public interface RouteCb { void done(String intent); }

    /** Run the conversational intent decision away from the floating chat's UI thread. */
    public static void routeAsync(Context c, String text, RouteCb cb) {
        if (cb == null) return;
        Context app = c == null ? null : c.getApplicationContext();
        new Thread(() -> {
            String result = CHAT;
            try { result = route(app == null ? c : app, text); }
            catch (Throwable ignored) { }
            cb.done(result);
        }, "dsh-intent-route").start();
    }

    public static String route(Context c, String text) {
        if (text == null || text.trim().isEmpty()) return CHAT;
        // Preserve offline/local commands when no model has been configured.
        if (!Cfg.load(c).aiReady()) return LOCAL;

        JSONArray context = recentContext(c);
        Candidate candidate = classify(c, text, context);
        if (candidate == null || CHAT.equals(candidate.intent)) return CHAT;
        if (!allowed(candidate.intent)) return CHAT;
        // A special action must be grounded in a literal excerpt from this message or a
        // clearly referenced recent turn; this is evidence validation, not keyword routing.
        if (!hasGrounding(candidate, text, context)) return CHAT;
        // Verify every non-chat route in a separate small model call. If the request is
        // ambiguous, mismatched, or the verifier is unavailable, leave it as conversation.
        return confirm(c, text, context, candidate) ? candidate.intent : CHAT;
    }

    private static Candidate classify(Context c, String text, JSONArray context) {
        JSONArray messages = new JSONArray();
        try {
            JSONObject sys = new JSONObject();
            sys.put("role", "system");
            sys.put("content", "你是 DSH 桌宠的意图规划器。只判断这条消息是否需要调用桌宠专用处理流程，不回答用户。"
                    + "只输出 JSON：{\"intent\":\"chat|weather|travel|nearby_food|nearby_place|nearby_shop|screen|local|plugin|draw|write\","
                    + "\"confidence\":0.0,\"basis\":\"current|followup|unclear\",\"evidence\":\"原文片段\",\"goal\":\"当前目的\"}。"
                    + "先用最后一条消息概括用户此刻要什么，再决定是否有专用动作。当前消息是主线；旧对话只用于补全明确省略、代词或‘继续’。"
                    + "不能因为旧对话聊过天气，就把新的吃饭、购物、闲聊判成天气；不能因为刚看过屏幕，就把一般‘看看’都判成读屏。"
                    + "天气表示用户此刻要天气数据；travel 表示正在规划跨地点/多日行程；当用户要按当前位置查具体餐馆/咖啡馆时选 nearby_food，查附近景点/公园时选 nearby_place，查附近商店/超市时选 nearby_shop。"
                    + "附近请求必须明确需要现实地点结果；单纯聊口味、问一般建议或闲聊仍选 chat。附近商家库存、评分、价格不能从地图数据推断。"
                    + "screen 仅当用户要读取当前屏幕或明确追问最近一次屏幕观察；local 仅当要改本机设置/提醒/设备状态；plugin 仅当要创建或修改本应用可运行插件；"
                    + "draw 仅当要生成贴纸/图画；write 仅用于用户明确要求创作长文/代码文档等可保存作品。"
                    + "evidence 必须是当前消息或明确被指代的最近对话中的连续原文；没有可引用依据时选 chat、basis=unclear。"
                    + "如果一句话包含多个目的，选择用户最主要的目的；不要让次要旧话题覆盖当前目的。confidence 低于 0.72 或不确定时选 chat。"
                    + "不要根据关键词命中做判断，要按整句语义、上下文和用户目的判断。只输出 JSON，不输出解释。");
            messages.put(sys);
            appendContext(messages, context);
            messages.put(new JSONObject().put("role", "user")
                    .put("content", "当前这一轮用户刚刚说：\n" + limit(text, 1200)
                            + "\n以当前消息为准。需要专用动作时，请给出支持该判断的原文片段；否则选 chat。"));
        } catch (Throwable ignored) { return null; }
        String raw = ask(c, messages, 180);
        if (raw.isEmpty()) return null;
        try {
            JSONObject o = jsonObject(raw);
            Candidate out = new Candidate();
            out.intent = o.optString("intent", CHAT).trim().toLowerCase(java.util.Locale.ROOT);
            out.confidence = o.optDouble("confidence", 0.0);
            out.basis = o.optString("basis", "unclear").trim();
            out.evidence = o.optString("evidence", "").trim();
            out.goal = o.optString("goal", "").trim();
            if (out.confidence < 0.72 || !("current".equals(out.basis) || "followup".equals(out.basis))) {
                out.intent = CHAT;
            }
            return out;
        } catch (Throwable ignored) { return null; }
    }

    private static boolean confirm(Context c, String text, JSONArray context, Candidate candidate) {
        JSONArray messages = new JSONArray();
        try {
            JSONObject sys = new JSONObject();
            sys.put("role", "system");
            sys.put("content", "你是独立的请求核验器。判断候选专用动作是否确实符合用户当前意图。"
                    + "用户可能换了话题；当前消息优先，历史仅用于解决明确指代。"
                    + "如果普通聊天回答就能满足当前消息，或候选动作只是由旧话题/宽泛词语引起，必须否决。"
                    + "只有证据片段准确支持当前动作时才确认。仅输出 JSON：{\"confirmed\":true|false}。");
            messages.put(sys);
            appendContext(messages, context);
            messages.put(new JSONObject().put("role", "user")
                    .put("content", "当前用户消息：" + limit(text, 1200)
                            + "\n候选动作：" + candidate.intent
                            + "\n规划器概括的目的：" + limit(candidate.goal, 100)
                            + "\n支持证据原文：" + limit(candidate.evidence, 120)
                            + "\n这条当前请求真的需要该专用动作吗？"));
        } catch (Throwable ignored) { return false; }
        String raw = ask(c, messages, 48);
        if (raw.isEmpty()) return false;
        try { return jsonObject(raw).optBoolean("confirmed", false); }
        catch (Throwable ignored) { return false; }
    }

    private static boolean hasGrounding(Candidate candidate, String current, JSONArray context) {
        if (candidate.evidence.isEmpty()) return false;
        String needle = normalize(candidate.evidence);
        if (needle.length() < 2) return false;
        if ("current".equals(candidate.basis)) return normalize(current).contains(needle);
        if ("followup".equals(candidate.basis)) {
            String history = "";
            for (int i = 0; i < context.length(); i++) {
                JSONObject row = context.optJSONObject(i);
                if (row != null) history += normalize(row.optString("content", ""));
            }
            return history.contains(needle);
        }
        return false;
    }

    /** Keep the two latest user turns and at most two assistant bubbles after each. */
    private static JSONArray recentContext(Context c) {
        JSONArray source = Data.chatList(c, 40);
        int latestUser = -1, previousUser = -1;
        for (int i = source.length() - 1; i >= 0; i--) {
            JSONObject row = source.optJSONObject(i);
            if (row == null || !"user".equals(row.optString("role", ""))) continue;
            if (latestUser < 0) latestUser = i;
            else { previousUser = i; break; }
        }
        JSONArray out = new JSONArray();
        if (previousUser >= 0) appendTurn(out, source, previousUser, latestUser);
        if (latestUser >= 0) appendTurn(out, source, latestUser, source.length());
        return out;
    }

    private static void appendTurn(JSONArray out, JSONArray source, int userIndex, int endIndex) {
        JSONObject user = source.optJSONObject(userIndex);
        if (user != null) out.put(compact(user));
        ArrayList<JSONObject> assistant = new ArrayList<>();
        for (int i = userIndex + 1; i < endIndex; i++) {
            JSONObject row = source.optJSONObject(i);
            if (row != null && "assistant".equals(row.optString("role", ""))) assistant.add(compact(row));
        }
        int start = Math.max(0, assistant.size() - 2);
        for (int i = start; i < assistant.size(); i++) out.put(assistant.get(i));
    }

    private static JSONObject compact(JSONObject row) {
        try {
            return new JSONObject().put("role", row.optString("role", ""))
                    .put("content", limit(row.optString("content", ""), 320));
        } catch (Throwable ignored) { return new JSONObject(); }
    }

    private static void appendContext(JSONArray messages, JSONArray context) {
        if (context == null) return;
        for (int i = 0; i < context.length(); i++) {
            JSONObject row = context.optJSONObject(i);
            if (row == null) continue;
            String role = row.optString("role", "");
            if (!"user".equals(role) && !"assistant".equals(role)) continue;
            try { messages.put(new JSONObject().put("role", role).put("content", row.optString("content", ""))); }
            catch (Throwable ignored) { }
        }
    }

    private static String ask(Context c, JSONArray messages, int maxTokens) {
        final String[] answer = {""};
        CountDownLatch latch = new CountDownLatch(1);
        Ai.rawChat(c, messages, maxTokens, 0.0, new Ai.Cb() {
            @Override public void ok(String value) { answer[0] = value == null ? "" : value; latch.countDown(); }
            @Override public void fail(String error) { latch.countDown(); }
        });
        try { if (!latch.await(MODEL_WAIT_MS, TimeUnit.MILLISECONDS)) return ""; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return ""; }
        return answer[0].trim();
    }

    private static JSONObject jsonObject(String raw) throws Exception {
        String value = raw == null ? "" : raw.trim();
        int start = value.indexOf('{'), end = value.lastIndexOf('}');
        if (start >= 0 && end > start) value = value.substring(start, end + 1);
        return new JSONObject(value);
    }

    private static boolean allowed(String intent) {
        return WEATHER.equals(intent) || TRAVEL.equals(intent) || NEARBY_FOOD.equals(intent)
                || NEARBY_PLACE.equals(intent) || NEARBY_SHOP.equals(intent)
                || SCREEN.equals(intent) || WRITE.equals(intent)
                || LOCAL.equals(intent) || PLUGIN.equals(intent) || DRAW.equals(intent);
    }

    private static String normalize(String text) {
        return text == null ? "" : text.replaceAll("[\\s，。！？!?；;：:、‘’“”\"'（）()【】\\[\\]{}]", "");
    }

    private static String limit(String text, int max) {
        if (text == null) return "";
        String s = text.trim();
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static final class Candidate {
        String intent = CHAT;
        String basis = "unclear";
        String evidence = "";
        String goal = "";
        double confidence;
    }
}
