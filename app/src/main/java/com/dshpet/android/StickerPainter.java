package com.dshpet.android;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.util.Locale;

/**
 * 绘图任务协调器：把用户完整描述交给独立图像生成服务，并只发送真实返回且成功保存的图。
 */
public final class StickerPainter {
    private static final String JOB_FILE = "sticker-draw-job.json";
    private static final Object LOCK = new Object();
    private static volatile String activeJobId = "";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private StickerPainter() {
    }

    public static void start(Context context, String query) {
        startJob(context, query);
    }

    /** 用户明确要画图时走独立图像生成服务。 */
    public static void startDirect(Context context, String query) {
        startJob(context, query);
    }

    public static boolean wantsDrawRequest(String text) {
        if (text == null) return false;
        String s = text.replaceAll("\\s+", "");
        if (s.isEmpty() || asksDrawingStatus(s)) return false;
        return s.contains("给我画") || s.contains("帮我画") || s.contains("画幅画")
                || s.contains("画一幅") || s.contains("画张") || s.contains("画一张")
                || s.contains("画条") || s.contains("画一条") || s.contains("画个") || s.contains("画一个")
                || s.contains("画个表情") || s.contains("画一个表情") || s.contains("画出来给我")
                || s.contains("画张图") || s.contains("画个图") || asksRedraw(s);
    }

    private static void startJob(Context context, String query) {
        final Context c = context.getApplicationContext();
        final String q = resolveDrawingQuery(context, query);
        Cfg cfg = Cfg.load(c);
        boolean comfy = "comfyui".equalsIgnoreCase(cfg.str("imageGenProvider", "openai").trim());
        boolean ready = comfy ? ComfyUi.isConfigured(cfg)
                : !cfg.str("imageGenApiKey", "").trim().isEmpty()
                && !cfg.str("imageGenBaseUrl", "").trim().isEmpty()
                && !cfg.str("imageGenModel", "").trim().isEmpty();
        if (!ready) {
            String setup = comfy
                    ? "请到「设置 - 智能核心 - 图像生成」填写 ComfyUI 电脑地址并导入 API 工作流。"
                    : "请到「设置 - 智能核心 - 图像生成」配置服务地址、Key 和模型。";
            sendUpdate(c, "我还没有配置可用的图像生成服务，所以不能把模板图说成我画的。" + setup, "");
            return;
        }
        final String id = Long.toString(System.currentTimeMillis() % 0xFFFFFF, 36).toLowerCase(Locale.US);
        synchronized (LOCK) {
            if (!activeJobId.isEmpty()) {
                JSONObject previous = Sg.readObj(c, JOB_FILE);
                if (activeJobId.equals(previous.optString("id", ""))
                        && "drawing".equals(previous.optString("state", ""))) return;
            }
            activeJobId = id;
        }

        String start = "我开始按你的描述生成图片啦。服务返回真实图像之前，我不会发模板图冒充草稿；你随时可以问进度。";
        update(c, id, q, "图像生成服务正在处理请求", "drawing", "", "");
        emitProgress(start);
        Data.chatAppend(c, "assistant", start);
        scheduleProgress(c, id, 30000L);
        String prompt = "Create one image that follows the user's requested subject and scene. "
                + "Do not replace the requested subject with a mascot, sticker template, title card, or unrelated character. "
                + "Do not add words, captions, labels, watermarks, or UI unless the user explicitly asks for text. "
                + "Make a complete, polished image suitable for sharing in a chat. Preserve specific names, objects, actions, colors, and composition in the request.\n\nUser request: " + q;
        Ai.generateImage(c, prompt, new Ai.ImageCb() {
            @Override public void ok(byte[] bytes) {
                try {
                    Bitmap image = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                    if (image == null) throw new Exception("图像服务返回的数据无法解码");
                    String name = Sticker.uniqueName(c, subjectName(q));
                    JSONObject saved = Sticker.add(c, name, "AI生成：" + q, image);
                    image.recycle();
                    if (!saved.optBoolean("ok", false)) throw new Exception("真实图片生成成功，但保存失败：" + saved.optString("error", "未知错误"));
                    update(c, id, q, "图像已生成并保存", "done", "", name);
                    sendUpdate(c, "图出来啦，这张是按你刚才的描述生成的。", name);
                } catch (Throwable t) {
                    update(c, id, q, "图像保存失败", "failed", "", "");
                    sendUpdate(c, "图像生成完成了，但保存或读取失败；我没有发旧图代替。原因：" + safeError(t), "");
                }
            }
            @Override public void fail(String error) {
                update(c, id, q, "图像生成失败", "failed", "", "");
                sendUpdate(c, "这次没有生成出图片，所以我没有东西可以发。" + (error == null || error.isEmpty() ? "" : "原因：" + error), "");
            }
        });
    }

    private static void scheduleProgress(Context c, String id, long delay) {
        MAIN.postDelayed(() -> {
            JSONObject current = Sg.readObj(c, JOB_FILE);
            if (!id.equals(current.optString("id", "")) || !"drawing".equals(current.optString("state", ""))) return;
            String msg = "绘图服务还在处理，目前还没返回可看的图；一有真实结果我会发给你。";
            Data.chatAppend(c, "assistant", msg);
            PetSvc.send(c, PetSvc.A_SAY, "text", msg);
            emitProgress(msg);
            scheduleProgress(c, id, 90000L);
        }, delay);
    }

    private static String subjectName(String query) {
        String s = query == null ? "新图片" : query.replaceAll("[\\[\\]\\n\\r]", " ").trim();
        s = s.replaceFirst("^(请|帮我|给我|能不能|可以)?(画一个|画一只|画一条|画一张|画个|画只|画条|画张|画幅|画)\\s*", "");
        if (s.isEmpty()) s = "新图片";
        return s.length() > 10 ? s.substring(0, 10) : s;
    }

    private static String safeError(Throwable t) {
        String m = t == null || t.getMessage() == null ? "未知错误" : t.getMessage();
        return m.length() > 180 ? m.substring(0, 180) : m;
    }

    private static void sendUpdate(Context c, String text, String imageName) {
        Data.chatAppend(c, "assistant", text);
        try {
            PetSvc.send(c, PetSvc.A_SAY, "text", text);
            JSONObject ev = new JSONObject().put("text", text);
            if (imageName != null && !imageName.isEmpty()) ev.put("name", imageName);
            Bus.emit("drawMessage", ev);
            if (imageName != null && !imageName.isEmpty()) {
                Data.chatAppend(c, "assistant", "[[表情:" + imageName + "]]");
                emitSticker(imageName);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 用户问进度时返回真实当前状态和已保存成图；无可用任务则返回 null。 */
    public static JSONObject statusFor(Context context, String text) {
        if (!asksDrawingStatus(text)) return null;
        JSONObject job = Sg.readObj(context, JOB_FILE);
        long at = job.optLong("createdAt", 0);
        if (at <= 0 || System.currentTimeMillis() - at > 24L * 60 * 60 * 1000) return null;
        String state = job.optString("state", "");
        if (state.isEmpty()) return null;
        if ("drawing".equals(state) && activeJobId.isEmpty()) {
            update(context, job.optString("id", ""), job.optString("query", ""),
                    "上次绘图任务中断", "failed", "", "");
            try { return new JSONObject().put("text", "上次生成图片时任务中断了，目前没有生成结果；我不会说成已经画好。你可以重新让我画一次。"); }
            catch (Throwable ignored) { return null; }
        }
        if ("failed".equals(state)) {
            try { return new JSONObject().put("text", "这次画稿没有保存成功，所以目前没有图可以看；我不会拿之前的图片冒充。可以再让我试一次。") ; }
            catch (Throwable ignored) { return null; }
        }
        String name = "done".equals(state)
                ? job.optString("finalName", "") : job.optString("previewName", "");
        String phase = job.optString("phase", "还在画");
        String reply;
        if ("done".equals(state)) reply = "已经画好了，我把最后的版本再发给你看看。";
        else if ("draft".equals(state)) reply = "主题草稿已经画好并发给你了，但细节整理没成功；目前没有更新版。";
        else if (name.isEmpty()) reply = "我还在" + phase + "，现在还没有存下能展示的画面；一旦有真实草稿或完成版，我会发给你。";
        else reply = "主题草稿已经保存，我现在在" + phase + "。这是当前实际画到的样子。";
        try {
            return new JSONObject().put("text", reply).put("name", name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean asksDrawingStatus(String text) {
        if (text == null) return false;
        return text.contains("画到哪") || text.contains("画好了吗") || text.contains("画完了吗")
                || text.contains("发出来看看") || text.contains("给我看看草稿")
                || text.contains("看看画的") || text.contains("绘图进度")
                || text.contains("画得怎么样") || text.contains("画好了吗") || text.contains("怎么样了")
                || text.contains("还没画好") || text.contains("还在画吗") || text.contains("图呢")
                || text.contains("好了没") || text.contains("等多久") || text.contains("给我看看");
    }

    private static void update(Context c, String id, String query, String phase, String state,
                               String previewName, String finalName) {
        try {
            Sg.writeObj(c, JOB_FILE, new JSONObject()
                    .put("id", id).put("query", query).put("phase", phase).put("state", state)
                    .put("previewName", previewName).put("finalName", finalName)
                    .put("createdAt", System.currentTimeMillis()));
        } catch (Throwable ignored) {
        }
    }

    private static void emitSticker(String name) {
        try {
            JSONObject ev = new JSONObject().put("name", name);
            Bus.emit("stickers", new JSONObject());
            Bus.emit("chatSticker", ev);
        } catch (Throwable ignored) {
        }
    }

    private static void emitProgress(String message) {
        try {
            Bus.emit("drawProgress", new JSONObject().put("message", message));
        } catch (Throwable ignored) {
        }
    }

    private static String clean(String s) {
        if (s == null) return "可爱";
        s = s.replaceAll("[\\r\\n\\[\\]{}]", " ").replaceAll("\\s+", " ").trim();
        if (s.isEmpty()) return "可爱";
        // Keep the complete request: shortening it to a tiny keyword caused rich/varied requests
        // to collapse into the same generic mascot prompt.
        return s.length() > 1600 ? s.substring(0, 1600) : s;
    }

    /** Follow-up redraws reuse the original subject instead of drawing the user's feedback as a caption. */
    private static String resolveDrawingQuery(Context context, String query) {
        String candidate = clean(query);
        if (!asksRedraw(candidate)) return candidate;
        try {
            JSONObject previous = Sg.readObj(context, JOB_FILE);
            long created = previous.optLong("createdAt", 0L);
            String subject = previous.optString("query", "").trim();
            if (created > 0L && System.currentTimeMillis() - created < 24L * 60 * 60 * 1000
                    && !subject.isEmpty()) return subject;
        } catch (Throwable ignored) {
        }
        return candidate.replaceAll("^(不好看|難看|难看|不喜欢|不喜歡|重画|重畫|重新画|重新畫)?[，,。！!、]*", "");
    }

    private static boolean asksRedraw(String text) {
        return containsAny(text, "再画一个", "再畫一個", "再画一个", "再画一张", "再畫一張",
                "重新画", "重新畫", "重画", "重畫", "再画", "再畫");
    }

    private static boolean containsAny(String s, String... words) {
        for (String word : words) if (s.contains(word)) return true;
        return false;
    }
}
