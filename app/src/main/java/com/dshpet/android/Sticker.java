package com.dshpet.android;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 表情包：图片存 S-G/stickers，名字/适用场景存 S-G/stickers.json。
 * AI 在回复末尾输出 [[表情:名字]] 使用，前端解析成图片，不把标记读出来。
 */
public final class Sticker {
    private static final String META = "stickers.json";
    private static final String DIR = "stickers";
    private static final Object LOCK = new Object();
    private static final Pattern TAG =
            Pattern.compile("\\[\\[\\s*表情\\s*[:：]\\s*([^\\]\\n]{1,16})\\s*\\]\\]");
    /** 她自己想发一张库里没有的表情时，用这个标记点单，客户端去网上找。 */
    private static final Pattern REQUEST =
            Pattern.compile("\\[\\[\\s*找表情\\s*[:：]\\s*([^\\]\\n]{1,20})\\s*\\]\\]");
    private static final LruCache<String, Bitmap> CACHE = new LruCache<>(8);

    private Sticker() {
    }

    /* ---------------- 元数据 ---------------- */

    public static JSONArray list(Context c) {
        JSONArray out = new JSONArray();
        synchronized (LOCK) {
            JSONObject o = Sg.readObj(c, META);
            JSONArray a = o.optJSONArray("items");
            if (a == null) return out;
            for (int i = 0; i < a.length(); i++) {
                JSONObject it = a.optJSONObject(i);
                if (it != null) out.put(it);
            }
        }
        return out;
    }

    private static JSONObject findByName(Context c, String name) {
        if (name == null) return null;
        String n = name.trim();
        JSONArray a = list(c);
        for (int i = 0; i < a.length(); i++) {
            JSONObject it = a.optJSONObject(i);
            if (it != null && n.equals(it.optString("name", ""))) return it;
        }
        return null;
    }

    /** 给聊天提示词用：返回这张表情的场景描述；没识别过就返回空串。 */
    public static String cue(Context c, String name) {
        JSONObject it = findByName(c, name);
        return it == null ? "" : it.optString("desc", "");
    }

    public static JSONObject findById(Context c, String id) {
        if (id == null || id.isEmpty()) return null;
        JSONArray a = list(c);
        for (int i = 0; i < a.length(); i++) {
            JSONObject it = a.optJSONObject(i);
            if (it != null && id.equals(it.optString("id", ""))) return it;
        }
        return null;
    }

    /** 改某个表情包的使用场景说明。 */
    public static void setDesc(Context c, String id, String desc) {
        synchronized (LOCK) {
            JSONArray a = list(c);
            for (int i = 0; i < a.length(); i++) {
                JSONObject it = a.optJSONObject(i);
                if (it == null || !id.equals(it.optString("id", ""))) continue;
                try {
                    it.put("desc", desc == null ? "" : desc.trim());
                    JSONObject root = new JSONObject();
                    root.put("items", a);
                    Sg.writeObj(c, META, root);
                    CACHE.remove(it.optString("name", ""));
                } catch (Throwable ignored) {
                }
                return;
            }
        }
    }

    /** 换掉某个表情包的图片，名字、场景保持不变。 */
    public static boolean replace(Context c, String id, Bitmap bmp) {
        JSONObject it = findById(c, id);
        if (it == null || bmp == null) return false;
        JSONObject r = add(c, it.optString("name", ""), it.optString("desc", ""), bmp);
        return r.optBoolean("ok", false);
    }

    /** 批量导入时取一个不重复的名字，避免同名覆盖掉旧表情。 */
    public static String uniqueName(Context c, String base) {
        String b = base == null ? "" : base.trim();
        if (b.isEmpty()) b = "表情";
        return freeName(c, b);
    }

    public static File file(Context c, JSONObject item) {
        String f = item == null ? "" : item.optString("file", "");
        if (f.isEmpty()) return null;
        File d = new File(Sg.dir(c), DIR);
        if (!d.exists()) {
            //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        }
        return new File(d, f);
    }

    /* ---------------- 增删改 ---------------- */

    /** 保存一张表情包；同名覆盖旧图。返回 {ok:true,id} 或 {ok:false,error}。 */
    public static JSONObject add(Context c, String name, String desc, Bitmap bmp) {
        JSONObject r = new JSONObject();
        try {
            String n = name == null ? "" : name.trim();
            // 名字要能安全放进 [[表情:名字]]，去掉可能破坏标记的字符
            n = n.replace("[", "").replace("]", "").replace("\n", " ").trim();
            if (n.isEmpty()) n = "表情";
            if (n.length() > 12) n = n.substring(0, 12);
            if (bmp == null || bmp.getWidth() < 32 || bmp.getHeight() < 32) {
                r.put("ok", false);
                r.put("error", "图片太小或为空");
                return r;
            }
            Bitmap small = shrink(bmp, 480);
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            small.compress(Bitmap.CompressFormat.PNG, 100, bo);
            if (bo.size() > 512 * 1024) {
                // PNG 太大（多半是照片表情），改用 JPEG 控制体积
                bo.reset();
                small.compress(Bitmap.CompressFormat.JPEG, 85, bo);
            }
            synchronized (LOCK) {
                JSONArray a = list(c);
                JSONObject item = null;
                for (int i = 0; i < a.length(); i++) {
                    JSONObject it = a.optJSONObject(i);
                    if (it != null && n.equals(it.optString("name", ""))) {
                        item = it;
                        break;
                    }
                }
                boolean isNew = item == null;
                String id;
                if (isNew) {
                    item = new JSONObject();
                    id = "s" + System.currentTimeMillis();
                    item.put("id", id);
                    item.put("name", n);
                    item.put("ts", System.currentTimeMillis());
                    a.put(item);
                } else {
                    id = item.optString("id", "s" + System.currentTimeMillis());
                }
                String file = id + ".png";
                item.put("file", file);
                item.put("desc", desc == null ? "" : desc.trim());
                File f = file(c, item);
                try (FileOutputStream fo = new FileOutputStream(f)) {
                    fo.write(bo.toByteArray());
                }
                JSONObject root = new JSONObject();
                root.put("items", a);
                Sg.writeObj(c, META, root);
                CACHE.remove(n);
                r.put("ok", true);
                r.put("id", id);
                r.put("new", isNew);
            }
        } catch (Throwable t) {
            try {
                r.put("ok", false);
                r.put("error", "保存失败");
            } catch (Throwable ignored) {
            }
        }
        return r;
    }

    public static void delete(Context c, String id) {
        synchronized (LOCK) {
            JSONArray a = list(c);
            JSONArray keep = new JSONArray();
            String file = "";
            String name = "";
            for (int i = 0; i < a.length(); i++) {
                JSONObject it = a.optJSONObject(i);
                if (it == null) continue;
                if (id != null && id.equals(it.optString("id", ""))) {
                    file = it.optString("file", "");
                    name = it.optString("name", "");
                    continue;
                }
                keep.put(it);
            }
            JSONObject root = new JSONObject();
            try {
                root.put("items", keep);
                Sg.writeObj(c, META, root);
            } catch (Throwable ignored) {
            }
            if (!file.isEmpty()) {
                File f = new File(new File(Sg.dir(c), DIR), file);
                //noinspection ResultOfMethodCallIgnored
                if (f.isFile()) f.delete();
            }
            if (!name.isEmpty()) CACHE.remove(name);
        }
    }

    /** 识别结果回调：desc 为空时 err 说明失败原因。 */
    public interface DescCb {
        void done(String desc, String err);
    }

    /** 批量识别进度回调。 */
    public interface BatchCb {
        void progress(int done, int total, String name);

        void done(int ok, int fail);
    }

    /** AI 自动识别适用场景：只在用户没填描述时调用。 */
    public static void autoDescribe(Context c, String name, Bitmap bmp) {
        if (bmp == null || name == null || name.isEmpty()) return;
        JSONObject it = findByName(c, name);
        if (it == null || !it.optString("desc", "").isEmpty()) return;
        final String id = it.optString("id", "");
        askAi(c, name, bmp, (cue, err) -> {
            if (cue == null || cue.isEmpty()) return;
            setDesc(c, id, cue);
        });
    }

    /** 重新识别单张：覆盖原来的场景说明。 */
    public static void describeOne(Context c, String id, DescCb cb) {
        JSONObject it = findById(c, id);
        if (it == null) {
            if (cb != null) cb.done("", "表情不存在");
            return;
        }
        String name = it.optString("name", "");
        Bitmap bmp = bitmap(c, name);
        if (bmp == null) {
            if (cb != null) cb.done("", "图片读取失败");
            return;
        }
        askAi(c, name, bmp, (cue, err) -> {
            if (cue == null || cue.isEmpty()) {
                if (cb != null) cb.done("", err == null || err.isEmpty() ? "识别失败" : err);
                return;
            }
            setDesc(c, id, cue);
            if (cb != null) cb.done(cue, "");
        });
    }

    /** 一键批量识别：逐个重新识别库里所有表情，串行执行，避免一次打十几个请求。 */
    public static void describeAll(Context c, BatchCb cb) {
        new Thread(() -> {
            JSONArray a = list(c);
            List<JSONObject> items = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) {
                JSONObject it = a.optJSONObject(i);
                if (it != null) items.add(it);
            }
            describeItems(c, items, cb);
        }, "dsh-sticker-all").start();
    }

    /** 只识别本次导入的表情，避免批量上传时重跑整个表情库。 */
    public static void describeIds(Context c, List<String> ids, BatchCb cb) {
        new Thread(() -> {
            JSONArray a = list(c);
            java.util.HashSet<String> wanted = new java.util.HashSet<>();
            if (ids != null) wanted.addAll(ids);
            List<JSONObject> items = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) {
                JSONObject it = a.optJSONObject(i);
                if (it != null && wanted.contains(it.optString("id", ""))) items.add(it);
            }
            describeItems(c, items, cb);
        }, "dsh-sticker-selected").start();
    }

    private static void describeItems(Context c, List<JSONObject> items, BatchCb cb) {
        stepAll(c, items, 0, new int[]{0}, cb);
    }

    private static void stepAll(Context c, List<JSONObject> items, int i, int[] ok, BatchCb cb) {
        if (i >= items.size()) {
            if (cb != null) cb.done(ok[0], items.size() - ok[0]);
            return;
        }
        JSONObject it = items.get(i);
        String name = it.optString("name", "");
        Bitmap bmp = bitmap(c, name);
        if (bmp == null) {
            if (cb != null) cb.progress(i + 1, items.size(), name);
            stepAll(c, items, i + 1, ok, cb);
            return;
        }
        final String id = it.optString("id", "");
        askAi(c, name, bmp, (cue, err) -> {
            if (cue != null && !cue.isEmpty()) {
                setDesc(c, id, cue);
                ok[0]++;
            }
            if (cb != null) cb.progress(i + 1, items.size(), name);
            stepAll(c, items, i + 1, ok, cb);
        });
    }

    /** 看图让模型写一句场景说明。 */
    private static void askAi(Context c, String name, Bitmap bmp, DescCb cb) {
        Cfg cfg = Cfg.load(c);
        if (!cfg.visionReady()) {
            if (cb != null) cb.done("", "还没有绑定 API Key");
            return;
        }
        String url = Brain.imageData(bmp);
        if (url.isEmpty()) {
            if (cb != null) cb.done("", "图片转码失败");
            return;
        }
        JSONArray msgs = new JSONArray();
        try {
            JSONObject sys = new JSONObject();
            sys.put("role", "system");
            sys.put("content", "你在帮桌宠整理表情包库。看这张表情包，用 30～60 个字的中文说清三件事："
                    + "1) 画面里有什么、在做什么（人物/动物/物件、表情、动作、配的字）；"
                    + "2) 图里如果有文字，原样写出来；"
                    + "3) 表达什么情绪、适合在什么场合发。"
                    + "要具体，不要只写「可爱」「开心」这种空泛词，不要引号、不要分点，只输出这一句。");
            msgs.put(sys);
            JSONArray parts = new JSONArray();
            JSONObject t = new JSONObject();
            t.put("type", "text");
            t.put("text", "表情包名字：" + name);
            parts.put(t);
            JSONObject pic = new JSONObject();
            pic.put("type", "image_url");
            JSONObject u = new JSONObject();
            u.put("url", url);
            pic.put("image_url", u);
            parts.put(pic);
            JSONObject m = new JSONObject();
            m.put("role", "user");
            m.put("content", parts);
            msgs.put(m);
        } catch (Throwable ignored) {
        }
        // 0 = 不设 max_tokens 上限，识别结果不因为截断丢掉后半段
        Ai.rawChatVision(c, msgs, 0, new Ai.Cb() {
            @Override
            public void ok(String text) {
                String cue = text == null ? "" : text.trim().replace("\n", " ");
                if (cue.length() > 160) cue = cue.substring(0, 160);
                if (cue.length() < 2) {
                    if (cb != null) cb.done("", "识别结果为空");
                    return;
                }
                if (cb != null) cb.done(cue, "");
            }

            @Override
            public void fail(String err) {
                if (cb != null) cb.done("", err == null || err.isEmpty() ? "识别失败" : err);
            }
        });
    }

    /* ---------------- 聊天展示 ---------------- */

    /**
     * 提示词：只在普通聊天里注入，主动说话不注入，
     * 避免她平时自言自语时也蹦出标记。
     */
    public static String prompt(Context c) {
        JSONArray a = list(c);
        StringBuilder sb = new StringBuilder();
        boolean useMore = Cfg.load(c).b("stickerUseMore", false);
        if (useMore) sb.append("用户明确表示喜欢你多用表情包。闲聊、开心、惊讶、无奈、撒娇等适合图片表达的场景，请更主动地从已确认的本地表情包里挑选；每条最多一张，不要每句话都发，严肃求助或没有贴合图片时仍用文字。\n");
        else sb.append("表情包是可选表达：仅在比文字更合适时使用，不要为了显得活泼而硬发。\n");
        int usable = 0;
        for (int i = 0; i < a.length() && i < 24; i++) {
            JSONObject it = a.optJSONObject(i);
            if (it == null) continue;
            String d = it.optString("desc", "");
            // 联网图片先留在库里供用户检查，只有用户补写场景说明后才加入自动挑选。
            if (isUnreviewedSticker(d) || bitmap(c, it.optString("name", "")) == null) continue;
            usable++;
            sb.append("- ").append(it.optString("name", ""));
            if (!d.isEmpty()) sb.append("：").append(d);
            sb.append('\n');
        }
        if (usable == 0) sb.append("- 现在没有经过确认、可自动使用的表情；不要把未审核的联网图片发给用户。\n");
        sb.append("用法：想发表情时，直接在回复最后写 [[表情:名字]]，名字必须来自上面列表，"
                + "普通聊天最多一个，不要解释这个标记，不要在正文里写名字。用户明确要求多张联网表情时，"
                + "可以在回复末尾输出 2～3 个不同的 [[找表情:关键词]] 标记，每个关键词要不同；不要说一次只能发一张。"
                + "优先从已确认的本地表情里挑，按使用场景匹配；遵守用户对使用频率的偏好，不要为了凑消息硬发。"
                + "只有用户明确让你联网找新表情时，才在回复最后写 [[找表情:关键词]]"
                + "（关键词 2～6 个字，例如 猫猫、无语、得意），系统会优先搜索二次元 Q 版蓝发女仆贴纸风格；"
                + "不要搜索或发送真人照片。没有合适结果就用文字回复，不要勉强发图。"
                + "绝对不要只用文字声称“已经发了/刚发了”表情；只有在回复里放入有效标记才算请求发送，应用会核对图片文件后展示。"
                + "用户发来表情时就像看到一张图那样自然接住，不要问「你发了什么」。\n");
        return sb.toString();
    }

    /** 主动聊天只允许使用已确认的本地图片，避免主动联网下载或发出未审核内容。 */
    public static String proactivePrompt(Context c) {
        JSONArray a = list(c);
        StringBuilder sb = new StringBuilder();
        sb.append("你主动开口时也可以偶尔用一张贴合语气的本地表情，但不要每次都发；只可从下面列出的、文件可读取且已确认的表情中选择。"
                + "选中时把 [[表情:名字]] 放在回复最后，名字必须逐字匹配列表；不使用联网搜索标记，不要只用文字声称已经发图。严肃提醒或没有合适图片时只说文字。\n");
        int usable = 0;
        for (int i = 0; i < a.length() && usable < 16; i++) {
            JSONObject it = a.optJSONObject(i);
            if (it == null) continue;
            String name = it.optString("name", "");
            String desc = it.optString("desc", "");
            if (name.isEmpty() || isUnreviewedSticker(desc) || bitmap(c, name) == null) continue;
            sb.append("- ").append(name);
            if (!desc.isEmpty()) sb.append("：").append(desc);
            sb.append('\n');
            usable++;
        }
        if (usable == 0) sb.append("目前没有可用于主动消息的已确认表情，不要输出任何表情标记。\n");
        return sb.toString();
    }

    /** 返回回复中第一张确实存在且可解码的本地表情名。 */
    public static String usableNameIn(String text, Context c) {
        if (text == null) return "";
        Matcher m = TAG.matcher(text);
        while (m.find()) {
            String name = m.group(1).trim();
            if (!name.isEmpty() && bitmap(c, name) != null) return name;
        }
        return "";
    }

    /** 删除指向不存在/损坏文件的表情标记，避免 UI 隐藏标记后留下空白气泡。 */
    public static String removeUnusableTags(String text, Context c) {
        if (text == null || text.isEmpty()) return text == null ? "" : text;
        Matcher m = TAG.matcher(text);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String name = m.group(1).trim();
            String replacement = !name.isEmpty() && bitmap(c, name) != null ? m.group() : "";
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString().replaceAll("[ \\t]{2,}", " ").replaceAll("\\s+([。！？!?])", "$1").trim();
    }

    /** 语义上明确要发一个表情的请求；询问库里有哪些不算发送请求。 */
    public static boolean explicitSendRequest(String text) {
        if (text == null) return false;
        String s = text.replaceAll("\\s+", "");
        if (!(s.contains("表情") || s.contains("贴纸") || s.contains("贴图"))) return false;
        if (s.contains("有哪些") || s.contains("还有什么") || s.contains("其他的") || s.contains("都有什么")) return false;
        return s.contains("表情呢") || s.contains("发表情") || s.contains("发个") || s.contains("发一个")
                || s.contains("发张") || s.contains("发一张") || s.contains("给我发") || s.contains("发给我")
                || s.contains("发我") || s.contains("来一个") || s.contains("来个") || s.contains("给我来")
                || s.contains("贴一个") || s.contains("贴个") || s.contains("要一个") || s.contains("要个")
                || s.contains("送我") || s.contains("甩一个");
    }

    /** 最近一轮明确要表情、但助手没有真正发出时，承接“快点”等短追问。 */
    public static boolean hasPendingSendRequest(Context c, JSONArray history) {
        return !requestContext(c, "", history).isEmpty();
    }

    /** 找到当前追问承接的那条表情请求，保留“本地/网上”等来源要求。 */
    public static String requestContext(Context c, String current, JSONArray history) {
        boolean currentIsRequest = explicitSendRequest(current);
        if (currentIsRequest && !isStickerContinuation(current)) return current;
        if (history == null) return "";
        StringBuilder assistantContext = new StringBuilder();
        String pending = currentIsRequest ? current : "";
        int followUps = currentIsRequest ? 1 : 0;
        int start = Math.max(0, history.length() - 24);
        for (int i = history.length() - 1; i >= start; i--) {
            JSONObject row = history.optJSONObject(i);
            if (row == null) continue;
            String role = row.optString("role", "");
            String content = row.optString("content", "");
            if ("assistant".equals(role)) {
                if (!usableNameIn(content, c).isEmpty()) return "";
                assistantContext.insert(0, content + " ");
            } else if ("user".equals(role)) {
                if (isCancelled(content)) return "";
                if (explicitSendRequest(content)) {
                    if (pending.isEmpty()) pending = content;
                    if (!isStickerContinuation(content)) return content;
                    if (++followUps > 6) return pending;
                    continue;
                }
                if (!isStickerFollowUp(content, assistantContext.toString())) return "";
                if (++followUps > 6) return pending;
            }
        }
        return pending;
    }

    /** 很短的“表情呢/快点/没看到”应延续未完成请求，继承此前的来源选择。 */
    private static boolean isStickerContinuation(String text) {
        if (text == null) return false;
        String s = text.replaceAll("\\s+", "");
        return s.matches("(?s).*(表情呢|贴纸呢|贴图呢|快点|快发|发呀|发啊|再发一次|重发|没看到|没看见|看不到|没显示|没有显示|没出来|这里没有|这边没有|没有喔|没有啊|在哪|怎么没|咋没).*" )
                && s.length() <= 24;
    }

    private static boolean isCancelled(String text) {
        if (text == null) return false;
        String s = text.replaceAll("\\s+", "");
        return s.contains("算了") || s.contains("不用了") || s.contains("别发了")
                || s.contains("不要发") || s.contains("取消") || s.contains("不发了");
    }

    private static boolean isStickerFollowUp(String text, String assistantContext) {
        if (text == null) return false;
        String s = text.replaceAll("\\s+", "");
        String context = assistantContext == null ? "" : assistantContext.replaceAll("\\s+", "");
        if (s.isEmpty() || isCancelled(s)) return false;
        if (s.contains("表情") || s.contains("贴纸") || s.contains("贴图")) return true;
        if (s.matches("(?s).*(天气|吃什么|好吃|出行|位置|附近|插件|文件|小说|书架|代码).*")) return false;
        if (s.matches("(?s).*(快点|快发|发呀|发啊|再发|重发|没看到|没看见|看不到|没显示|没有显示|没出来|这里没有|这边没有|没有喔|没有啊|在哪|怎么没|咋没|网上的|本地的|只能看见|现在呢).*")) return true;
        return s.length() <= 8 && context.matches("(?s).*(表情|贴纸|贴图|发给你|发了一张|再发一次|刚发).*" );
    }

    /** 用户在明确要发贴纸的同时指定来源时，用于把来源落实成真实发送动作。 */
    public static boolean explicitLocalRequest(String text) {
        if (text == null) return false;
        String s = text.replaceAll("\\s+", "");
        return s.contains("本地") || s.contains("库里") || s.contains("表情库")
                || s.contains("贴纸库") || s.contains("我上传的");
    }

    public static boolean explicitOnlineRequest(String text) {
        if (text == null) return false;
        String s = text.replaceAll("\\s+", "");
        return s.contains("网上") || s.contains("联网") || s.contains("在线找")
                || s.contains("从网上") || s.contains("网络上") || s.contains("上网找");
    }

    /** 从用户原话提取搜索主题，去掉“网上找一张发给我”等操作词。 */
    public static String searchQuery(String text) {
        String s = text == null ? "" : text;
        String[] sourceWords = {"网上", "联网", "在线", "网络上", "上网", "本地", "库里", "表情库", "贴纸库"};
        for (String word : sourceWords) s = s.replace(word, " ");
        String[] actionWords = {"试试", "一起", "给我", "发给我", "发一张", "发一个", "找一张", "找一个", "一张", "一个", "帮我", "麻烦"};
        for (String word : actionWords) s = s.replace(word, " ");
        return forSearch(s);
    }

    /** 选一张实际可读、已经审核过且尽量贴近本轮语境的本地表情。 */
    public static String chooseLocal(Context c, String hint) {
        JSONArray a = list(c);
        String previous = Cfg.load(c).str("lastAutoSticker", "");
        String best = "";
        int bestScore = -1;
        for (int i = 0; i < a.length(); i++) {
            JSONObject item = a.optJSONObject(i);
            if (item == null) continue;
            String name = item.optString("name", "").trim();
            String desc = item.optString("desc", "").trim();
            if (name.isEmpty() || isUnreviewedSticker(desc) || bitmap(c, name) == null) continue;
            String haystack = (name + " " + desc).toLowerCase(Locale.ROOT);
            int score = 0;
            if (hint != null) {
                Matcher words = Pattern.compile("[\\p{IsHan}]{2,}|[A-Za-z0-9_]{3,}").matcher(hint);
                while (words.find()) {
                    String word = words.group().toLowerCase(Locale.ROOT);
                    if (haystack.contains(word)) score += word.length() + 2;
                    if (word.matches("[\\p{IsHan}]+")) {
                        for (int j = 0; j + 1 < word.length(); j++) {
                            if (haystack.contains(word.substring(j, j + 2))) score++;
                        }
                    }
                }
            }
            if (!name.equals(previous)) score++;
            if (score > bestScore) { bestScore = score; best = name; }
        }
        if (!best.isEmpty()) {
            Cfg cfg = Cfg.load(c);
            cfg.set("lastAutoSticker", best);
            cfg.save(c);
        }
        return best;
    }

    /** 移除会让用户误以为发送成功的句子；调用方随后附上真实资源或诚实说明。 */
    public static String removeFalseSendClaims(String text) {
        if (text == null || text.trim().isEmpty()) return "";
        StringBuilder kept = new StringBuilder();
        List<String> markers = new ArrayList<>();
        for (String part : text.split("(?<=[。！？!?])|\\n+")) {
            String s = part.trim();
            if (s.isEmpty()) continue;
            Matcher marker = Pattern.compile("\\[\\[\\s*(?:表情|找表情)\\s*[:：]\\s*[^\\]\\n]{1,20}\\s*\\]\\]").matcher(s);
            while (marker.find()) markers.add(marker.group());
            String visible = marker.replaceAll("");
            String flat = visible.replaceAll("\\s+", "");
            boolean claim = flat.matches("(?s).*(我发了|我刚发|刚发了|刚刚发|已经发给你|给你发了|刚才发了|发了呀没看见|已经发出|已经发了|已经发完|发出来了|给你发一张|给你发一个|我给你发).*" );
            if (claim) continue;
            if (!visible.trim().isEmpty()) {
                if (kept.length() > 0) kept.append('\n');
                kept.append(visible.trim());
            }
        }
        for (String marker : markers) {
            if (kept.length() > 0) kept.append(' ');
            kept.append(marker);
        }
        return kept.toString().trim();
    }

    /** 显式的「多用/少用表情包」偏好会保存到配置并持续遵从。 */
    public static void learnUsePreference(Context c, String userText) {
        String text = userText == null ? "" : userText.toLowerCase(java.util.Locale.ROOT);
        if (text.isEmpty() || !(text.contains("表情包") || text.contains("贴纸"))) return;
        boolean wantsLess = text.matches("(?s).*(别|不要|不想|少|少点|少发|不用).{0,12}(用|发|发表情|表情包|贴纸).*")
                || text.matches("(?s).*(表情包|贴纸).{0,10}(别发|少发|少用|不用).*");
        boolean wantsMore = text.matches("(?s).*(多用|多发|多发表情|多一点|多一些|更常|频繁).{0,18}(表情包|贴纸).*")
                || text.matches("(?s).*(表情包|贴纸).{0,16}(多用|多发|多一点|多一些|更频繁).*");
        if (!wantsLess && !wantsMore) return;
        Cfg cfg = Cfg.load(c);
        boolean value = wantsMore && !wantsLess;
        if (cfg.b("stickerUseMore", false) == value) return;
        cfg.set("stickerUseMore", value);
        cfg.save(c);
    }

    private static boolean isUnreviewedSticker(String desc) {
        if (desc == null) return false;
        String d = desc.trim();
        return d.startsWith("联网找的：") || d.startsWith("联网找的:")
                || d.startsWith("绘图草稿：") || d.startsWith("绘图草稿:");
    }

    /** 取回复里的第一个表情名；没有返回空串。 */
    public static String pickName(String text) {
        if (text == null) return "";
        Matcher m = TAG.matcher(text);
        if (!m.find()) return "";
        return m.group(1).trim();
    }

    /** 去掉表情标记并收拾多余空格，用于显示与朗读。 */
    public static String strip(String text) {
        if (text == null) return "";
        String s = TAG.matcher(text).replaceAll("").trim();
        s = s.replaceAll("[ \\t]{2,}", " ").replaceAll("\\s+。", "。").trim();
        return s;
    }

    /** 按名字加载表情图；找不到返回 null。 */
    public static Bitmap bitmap(Context c, String name) {
        if (name == null || name.isEmpty()) return null;
        JSONObject it = findByName(c, name);
        File f = file(c, it);
        if (f == null || !f.isFile() || f.length() <= 0) {
            CACHE.remove(name);
            return null;
        }
        Bitmap cached = CACHE.get(name);
        if (cached != null && !cached.isRecycled()) return cached;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            int w = o.outWidth, h = o.outHeight;
            if (w <= 0 || h <= 0) return null;
            int sample = 1;
            while (w / (sample * 2) >= 360 && h / (sample * 2) >= 360) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            Bitmap bmp = BitmapFactory.decodeFile(f.getAbsolutePath(), o2);
            if (bmp != null) CACHE.put(name, bmp);
            return bmp;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 她在回复里点的「找表情」关键词；没有返回空串。 */
    public static String findRequest(String text) {
        if (text == null) return "";
        Matcher m = REQUEST.matcher(text);
        return m.find() ? m.group(1).trim() : "";
    }

    /** 找出模型为一次明确的多图请求生成的关键词，最多执行三次联网搜索。 */
    public static List<String> findRequests(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        Matcher m = REQUEST.matcher(text);
        while (m.find() && out.size() < 3) {
            String query = m.group(1).trim();
            if (!query.isEmpty()) out.add(query);
        }
        return out;
    }

    /** 把点单标记换成真正的表情标记；没找到图就把标记删掉。 */
    public static String resolveRequest(String text, String name) {
        if (text == null) return "";
        String tag = (name == null || name.isEmpty()) ? "" : "[[表情:" + name + "]]";
        return REQUEST.matcher(text).replaceFirst(Matcher.quoteReplacement(tag));
    }

    /** 去掉点单标记，只留正文：文字先发出去，图片晚一步单独补。 */
    public static String dropRequest(String text) {
        if (text == null) return "";
        return REQUEST.matcher(text).replaceAll("").trim();
    }

    /* ---------------- 联网找表情包 ---------------- */

    public interface FetchCb {
        void done(String name, String err);
    }

    /** 优先搜角色贴纸画风，过滤明显的图库照片和横幅图后存入表情库。 */
    public static void fetchOnline(Context c, String query, FetchCb cb) {
        if (cb == null) return;
        final String q = forSearch(query);
        final String search = q + " 二次元 Q版 蓝发女仆 动漫表情包 贴纸 简洁背景";
        // 只回调一次：并发下载时可能多张同时成功，不能让调用方收到两次
        final java.util.concurrent.atomic.AtomicBoolean replied =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        final Object resultLock = new Object();
        final boolean[] completed = {false};
        final FetchCb once = (name, err) -> {
            if (replied.compareAndSet(false, true)) cb.done(name, err);
        };
        new Thread(() -> {
            String err = "网上没找到合适的表情包";
            try {
                String html = new String(http("https://cn.bing.com/images/search?q="
                        + URLEncoder.encode(search, "UTF-8") + "&form=HDRSC2&first=1&qft="
                        + URLEncoder.encode("+filterui:photo-clipart", "UTF-8")), StandardCharsets.UTF_8);
                Matcher m = Pattern.compile("murl&quot;:&quot;(https?://.+?)&quot;").matcher(html);
                // 取前三张同时下载，谁先通过画幅/尺寸/图库域名筛选就用谁。
                final java.util.List<String> urls = new java.util.ArrayList<>();
                while (m.find() && urls.size() < 3) {
                    String url = m.group(1).replace("&amp;", "&").replace("&quot;", "\"");
                    // Android 9+ 默认禁止明文 HTTP，能上 HTTPS 的图床就直接换掉
                    if (url.startsWith("http://")) url = "https://" + url.substring(7);
                    if (isStockPhotoUrl(url) || urls.contains(url)) continue;
                    urls.add(url);
                }
                if (urls.isEmpty()) {
                    synchronized (resultLock) {
                        completed[0] = true;
                        once.done("", err);
                    }
                    return;
                }
                final java.util.concurrent.atomic.AtomicBoolean taken =
                        new java.util.concurrent.atomic.AtomicBoolean(false);
                final java.util.concurrent.CountDownLatch latch =
                        new java.util.concurrent.CountDownLatch(urls.size());
                for (String url : urls) {
                    new Thread(() -> {
                        try {
                            if (taken.get()) return;
                            Bitmap bmp = image(url);
                            if (!isStickerShape(bmp)) return;
                            if (!taken.compareAndSet(false, true)) return;
                            synchronized (resultLock) {
                                if (completed[0]) return;
                                String name = freeName(c, shortName(q));
                                JSONObject r = add(c, name, "联网找的：" + q, bmp);
                                if (r.optBoolean("ok", false)) {
                                    completed[0] = true;
                                    once.done(name, "");
                                } else taken.set(false);
                            }
                        } catch (Throwable ignored) {
                        } finally {
                            latch.countDown();
                        }
                    }, "dsh-sticker-img").start();
                }
                boolean allDone = latch.await(25, java.util.concurrent.TimeUnit.SECONDS);
                synchronized (resultLock) {
                    if (!completed[0]) {
                        completed[0] = true;
                        once.done("", allDone ? err : "搜索或下载超时，暂时没有可发送的图片");
                    }
                }
                return;
            } catch (Throwable t) {
                err = "联网失败，检查网络后再试一次";
            }
            synchronized (resultLock) {
                if (!completed[0]) {
                    completed[0] = true;
                    once.done("", err);
                }
            }
        }, "dsh-sticker").start();
    }

    /** 把用户那句话里的动词去掉，留下能当搜索词的部分。 */
    private static String forSearch(String text) {
        String s = text == null ? "" : text;
        s = s.replaceAll("[，。！？、,.!?~～]", " ");
        String[] junk = {"帮我", "给我", "麻烦", "可以", "能不能", "一个", "一张", "表情包",
                "表情", "贴图", "斗图", "联网", "网上", "找", "搜", "下载", "发", "来", "换",
                "弄", "整", "个", "张", "吧", "吗", "呢", "呀", "啊"};
        for (String j : junk) s = s.replace(j, " ");
        s = s.replaceAll("\\s+", " ").trim();
        if (s.isEmpty()) s = "可爱";
        if (s.length() > 20) s = s.substring(0, 20);
        return s;
    }

    private static boolean isStockPhotoUrl(String url) {
        try {
            String host = new URL(url).getHost().toLowerCase(Locale.US);
            String[] stockHosts = {"pexels.com", "unsplash.com", "shutterstock.com", "gettyimages.com",
                    "istockphoto.com", "stock.adobe.com", "depositphotos.com", "123rf.com",
                    "dreamstime.com", "alamy.com"};
            for (String blocked : stockHosts) {
                if (host.equals(blocked) || host.endsWith("." + blocked)) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean isStickerShape(Bitmap bmp) {
        if (bmp == null) return false;
        int w = bmp.getWidth(), h = bmp.getHeight();
        if (w < 240 || h < 240) return false;
        float ratio = w / (float) h;
        return ratio >= 0.65f && ratio <= 1.35f;
    }

    private static String shortName(String q) {
        String s = q.replaceAll("\\s+", "");
        if (s.isEmpty()) return "网图";
        return s.length() > 10 ? s.substring(0, 10) : s;
    }

    /** 同名已存在就加数字，避免每次联网都把上一张覆盖掉。 */
    private static String freeName(Context c, String base) {
        if (findByName(c, base) == null) return base;
        for (int i = 2; i < 100; i++) {
            String n = base.length() <= 9 ? base + i : base.substring(0, 9) + i;
            if (findByName(c, n) == null) return n;
        }
        return base;
    }

    private static byte[] http(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(7000);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
        conn.setRequestProperty("Referer", "https://cn.bing.com/");
        int code = conn.getResponseCode();
        if (code != 200) throw new Exception("HTTP " + code);
        InputStream in = conn.getInputStream();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n, total = 0;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > 4 * 1024 * 1024) break;
            bo.write(buf, 0, n);
        }
        in.close();
        return bo.toByteArray();
    }

    private static Bitmap image(String url) {
        try {
            byte[] b = http(url);
            if (b == null || b.length < 2048) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(b, 0, b.length, o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;
            int sample = 1;
            while (o.outWidth / (sample * 2) >= 480 && o.outHeight / (sample * 2) >= 480) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(b, 0, b.length, o2);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Bitmap shrink(Bitmap src, int max) {
        try {
            int w = src.getWidth(), h = src.getHeight();
            if (w <= max && h <= max) return src;
            float s = Math.min(max / (float) w, max / (float) h);
            return Bitmap.createScaledBitmap(src, Math.max(1, (int) (w * s)),
                    Math.max(1, (int) (h * s)), true);
        } catch (Throwable t) {
            return src;
        }
    }
}
