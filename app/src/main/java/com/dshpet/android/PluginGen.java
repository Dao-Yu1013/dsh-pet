package com.dshpet.android;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 让 AI 在聊天里现写一个代码插件。
 * 流程：用户说「帮我写个 xx 插件」→ 生成草稿（不安装）→ 用户回「装上」才落地。
 * 插件代码在独立的 WebView 沙箱里执行，只能调用 PluginCode 明确开放的原子能力。
 */
public final class PluginGen {
    private static final String DRAFT = "plugin_draft.json";
    private static final String REQUEST = "plugin_request_pending.json";
    private static final String LAST_PLUGIN = "plugin_last_interaction.json";
    private static final int MAX_CODE = 49152;
    private static final ExecutorService NATIVE_COMPILE = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "dsh-ai-plugin-compile");
        t.setDaemon(true);
        return t;
    });

    private PluginGen() {
    }

    /* ---------------- 意图判断 ---------------- */

    public static boolean wants(String t) {
        if (t == null) return false;
        String s = t.trim();
        if (s.isEmpty()) return false;
        if (has(s, "怎么", "在哪", "怎么用", "导入", "删除", "删掉", "关闭", "关掉", "哪些")) return false;
        if (!has(s, "装", "写", "做", "弄", "加", "帮", "开发")) return false;
        // 「你会写代码吗」「现在有写代码的能力了吗」是在问能力，不是在派活
        if (asking(s)) return false;
        // 「插件/代码/脚本/小程序/小工具」是明确的插件对象
        if (has(s, "插件", "代码", "脚本", "小程序", "小工具")) return true;
        // 「功能」太泛，只认「加/写/做一个功能」，并排除评价和咨询
        if (s.contains("功能") && !has(s, "好用", "挺", "不错", "推荐", "怎么样", "是什么")) {
            return has(s, "加", "写", "做", "弄", "开发");
        }
        return false;
    }

    /** 没有「帮我/给我/写个」这类明确的委托时，带疑问词的句子按「问能力」处理。 */
    private static boolean asking(String s) {
        if (!has(s, "你", "咱")) return false;
        if (has(s, "帮我", "帮忙", "给我", "替我", "写个", "做个", "弄个", "加个",
                "写一个", "做一个", "弄一个", "写段", "做段")) return false;
        return has(s, "会不会", "能不能", "可不可以", "有没有", "还会", "能力", "吗", "么", "？", "?");
    }

    public static boolean confirm(String t) {
        if (t == null) return false;
        String s = t.trim().replaceAll("[\\s。！!，,]", "");
        if (s.length() > 8) return false;
        if (s.contains("不")) return false;
        if (has(s, "装上", "安装", "装吧", "可以", "好的", "好呀", "确定")) return true;
        return s.equals("行") || s.equals("好") || s.equals("嗯") || s.equals("装");
    }

    /** 「写完直接装上」：生成完就落地，不再追一句确认。 */
    private static boolean installNow(String t) {
        if (t == null) return false;
        return has(t, "直接装", "就装上", "写完就装", "不用问", "自动装", "马上装", "直接安装");
    }

    public static boolean cancel(String t) {
        if (t == null) return false;
        String s = t.trim();
        return has(s, "不装", "算了", "取消", "先不", "别装", "不要了");
    }

    public static boolean intercept(Context c, String text) {
        return wants(text)
                || pendingRequest(c) != null
                || (draft(c) != null && (confirm(text) || cancel(text)))
                || inspect(c, text) != null
                || ((wantsStop(text) || wantsResume(text)) && hasBackground(c));
    }

    /** 有没有后台定时插件：没有的话「停一下」交给普通聊天，别答非所问。 */
    private static boolean hasBackground(Context c) {
        try {
            JSONArray ps = Plugins.list(c);
            for (int i = 0; i < ps.length(); i++) {
                JSONObject p = ps.optJSONObject(i);
                if (p != null && p.optBoolean("background") && p.optBoolean("hasCode")) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 「别刷了 / 停一下」：停掉后台定时插件。音乐、朗读、提醒这类不算。 */
    private static boolean wantsStop(String s) {
        if (s == null) return false;
        if (has(s, "音乐", "语音", "朗读", "闹钟", "闹铃", "提醒")) return false;
        return has(s, "别刷", "不刷", "不要刷", "别看了", "不用看了", "停一下", "停掉", "暂停",
                "关掉插件", "关闭插件", "别忙了", "歇会", "歇一会儿", "先停", "别搞了", "别忙活");
    }

    /** 「继续刷 / 接着看」：把暂停的后台插件放出来。 */
    private static boolean wantsResume(String s) {
        if (s == null) return false;
        return has(s, "继续刷", "接着刷", "接着看", "继续看", "继续就好", "接着来");
    }

    /** 暂停所有后台定时插件两个小时；返回停了几个。 */
    private static int pauseBackground(Context c) {
        int n = 0;
        long until = System.currentTimeMillis() + 2 * 3600000L;
        try {
            JSONArray ps = Plugins.list(c);
            for (int i = 0; i < ps.length(); i++) {
                JSONObject p = ps.optJSONObject(i);
                if (p == null || !p.optBoolean("background") || !p.optBoolean("hasCode")) continue;
                File d = new File(Plugins.dir(c), p.optString("id", ""));
                if (!d.isDirectory()) continue;
                JSONObject t = new JSONObject();
                t.put("nextAt", until);
                t.put("fails", 0);
                t.put("pausedAt", System.currentTimeMillis());
                Sg.write(new File(d, "tick.json"), t.toString());
                n++;
            }
        } catch (Throwable ignored) {
        }
        TickAlarm.schedule(c);
        return n;
    }

    /** 让暂停的后台插件马上可以再跑；返回放了几个。 */
    private static int resumeBackground(Context c) {
        int n = 0;
        try {
            JSONArray ps = Plugins.list(c);
            for (int i = 0; i < ps.length(); i++) {
                JSONObject p = ps.optJSONObject(i);
                if (p == null || !p.optBoolean("background") || !p.optBoolean("hasCode")) continue;
                if (!p.optBoolean("enabled")) continue;
                File d = new File(Plugins.dir(c), p.optString("id", ""));
                if (!d.isDirectory()) continue;
                File tf = new File(d, "tick.json");
                JSONObject t = new JSONObject(Sg.read(tf, "{}"));
                t.put("nextAt", 0);
                Sg.write(tf, t.toString());
                n++;
            }
        } catch (Throwable ignored) {
        }
        TickAlarm.schedule(c);
        return n;
    }

    /**
     * 已装插件相关的两件事：解释它怎么用、按新要求改它。
     * 返回 explain / edit，跟插件无关返回 null。
     */
    private static String inspect(Context c, String text) {
        if (text == null) return null;
        JSONObject p = find(c, text);
        if (p == null) return null;
        rememberPlugin(c, p);
        if (wantsEdit(text)) return "edit";
        if (wantsExplain(text)) return "explain";
        return null;
    }

    /** 找出句子里点名的已装插件；没点名但只装了一个代码插件时，默认就是它。 */
    private static JSONObject find(Context c, String text) {
        String s = text == null ? "" : text.trim();
        if (s.isEmpty()) return null;
        JSONArray ps = Plugins.list(c);
        JSONObject best = null;
        int bestLen = 0;
        for (int i = 0; i < ps.length(); i++) {
            JSONObject m = ps.optJSONObject(i);
            if (m == null || !m.optBoolean("hasCode")) continue;
            String name = m.optString("name", "").trim();
            if (name.length() >= 2 && s.contains(name) && name.length() > bestLen) {
                best = m;
                bestLen = name.length();
            }
        }
        if (best != null) return best;
        if (isCorrection(s)) {
            JSONObject last = Sg.readObj(c, LAST_PLUGIN);
            String lastId = last.optString("id", "");
            if (!lastId.isEmpty() && System.currentTimeMillis() - last.optLong("at", 0L) < 24L * 60 * 60 * 1000) {
                for (int i = 0; i < ps.length(); i++) {
                    JSONObject m = ps.optJSONObject(i);
                    if (m != null && m.optBoolean("hasCode") && lastId.equals(m.optString("id", ""))) return m;
                }
            }
            JSONObject newest = null;
            long newestAt = 0L;
            for (int i = 0; i < ps.length(); i++) {
                JSONObject m = ps.optJSONObject(i);
                if (m == null || !m.optBoolean("hasCode") || !m.optBoolean("ai")) continue;
                File manifest = new File(new File(Plugins.dir(c), m.optString("id", "")), "manifest.json");
                long modified = manifest.lastModified();
                if (modified > newestAt) { newest = m; newestAt = modified; }
            }
            if (newest != null && System.currentTimeMillis() - newestAt < 24L * 60 * 60 * 1000) return newest;
        }
        // 「你看一下装上的那个插件」这种指代：只有一个装了代码的插件时不会有歧义
        if (!has(s, "插件", "脚本", "代码") && !isCorrection(s)) return null;
        JSONObject only = null;
        int count = 0;
        for (int i = 0; i < ps.length(); i++) {
            JSONObject m = ps.optJSONObject(i);
            if (m != null && m.optBoolean("hasCode")) {
                only = m;
                count++;
            }
        }
        return count == 1 ? only : null;
    }

    private static boolean wantsExplain(String s) {
        if (!has(s, "看一", "看看", "看一下", "解释", "介绍", "说明", "怎么用", "干嘛", "干什么",
                "讲讲", "讲一下", "有什么用",
                "跑不了", "跑不起来", "没反应", "没动静", "报错", "出错", "失败", "不能用",
                "怎么回事", "为什么", "怎么没")) return false;
        return has(s, "插件", "代码", "脚本", "它", "这个");
    }

    private static boolean wantsEdit(String s) {
        if (has(s, "删掉", "删除", "卸载", "关掉", "关闭", "禁用", "停用")) return false;
        if (isCorrection(s)) return true;
        if (!has(s, "改", "修改", "调整", "优化", "加上", "增加", "补充", "换个", "换成")) return false;
        return has(s, "插件", "代码", "脚本", "它", "这个");
    }

    private static boolean isCorrection(String s) {
        return has(s, "我的意思是", "我说的是", "我想要的是", "不是这样", "不是这个意思", "应该是")
                && has(s, "这个", "它", "视频", "抖音", "插件", "脚本");
    }

    private static void rememberPlugin(Context c, JSONObject plugin) {
        if (c == null || plugin == null) return;
        try {
            String id = plugin.optString("id", "");
            if (!id.isEmpty()) Sg.writeObj(c, LAST_PLUGIN,
                    new JSONObject().put("id", id).put("at", System.currentTimeMillis()));
        } catch (Throwable ignored) { }
    }

    /* ---------------- 草稿 ---------------- */

    public static JSONObject draft(Context c) {
        JSONObject o = Sg.readObj(c, DRAFT);
        if (o.optString("code", "").trim().isEmpty() && o.optString("source", "").trim().isEmpty()) return null;
        // 草稿三天不确认就自动作废，避免一直挂着
        if (System.currentTimeMillis() - o.optLong("at", 0) > 3 * 86400000L) {
            clear(c);
            return null;
        }
        return o;
    }

    private static JSONObject pendingRequest(Context c) {
        JSONObject o = Sg.readObj(c, REQUEST);
        long at = o.optLong("at", 0L);
        if (at <= 0L || System.currentTimeMillis() - at > 15L * 60 * 1000) {
            clearRequest(c);
            return null;
        }
        return o;
    }

    private static void clearRequest(Context c) {
        try {
            File f = Sg.file(c, REQUEST);
            if (f.isFile()) f.delete();
        } catch (Throwable ignored) { }
    }

    private static boolean hasConcreteGoal(String text) {
        // A chat-only plugin has a concrete trigger/action even when it never
        // opens or operates another app (for example, reply to a keyword).
        boolean chatTrigger = has(text, "收到", "出现", "检测到", "匹配到", "用户说", "关键词", "消息")
                && has(text, "回复", "回应", "回答", "发消息", "说一句", "回复说");
        if (chatTrigger) return true;
        return has(text, "打开", "刷", "滑动", "划动", "看完", "播完", "结束后", "截屏", "截图",
                "识别", "记录", "学习", "提醒", "通知", "播放", "阅读", "读书", "搜索", "查找",
                "记账", "定时", "下载", "点击", "点按", "下一条", "下一个", "自动操作");
    }

    public static void clear(Context c) {
        try {
            JSONObject draft = Sg.readObj(c, DRAFT);
            String path = draft.optString("dexPath", "");
            File owned = new File(new File(Sg.dir(c), "plugin-drafts"), "").getCanonicalFile();
            File dex = path.isEmpty() ? null : new File(path).getCanonicalFile();
            if (dex != null && dex.getPath().startsWith(owned.getPath() + File.separator) && dex.isFile()) {
                //noinspection ResultOfMethodCallIgnored
                dex.delete();
            }
            File f = Sg.file(c, DRAFT);
            //noinspection ResultOfMethodCallIgnored
            if (f.isFile()) f.delete();
        } catch (Throwable ignored) {
        }
    }

    /** 安装草稿为正式插件，返回 manifest；失败返回带 error 的对象。 */
    public static JSONObject installDraft(Context c) {
        JSONObject out = new JSONObject();
        JSONObject d = draft(c);
        if (d == null) {
            try {
                out.put("error", "没有待安装的插件");
            } catch (Throwable ignored) {
            }
            return out;
        }
        try {
            String name = d.optString("name", "AI 插件").trim();
            if (name.length() > 20) name = name.substring(0, 20);
            // 改已装插件时覆盖原目录，id 不变，已装的位置和启用状态都保留
            String target = d.optString("target", "").trim();
            File dir = target.isEmpty() ? null : new File(Plugins.dir(c), target);
            boolean update = dir != null && dir.isDirectory();
            // 同名的新插件不另起一份，直接覆盖原来那个，避免出现两个「刷视频」
            if (!update && !name.isEmpty()) {
                JSONArray ps = Plugins.list(c);
                for (int i = 0; i < ps.length(); i++) {
                    JSONObject old = ps.optJSONObject(i);
                    if (old == null || !old.optBoolean("hasCode")) continue;
                    if (!name.equals(old.optString("name", "").trim())) continue;
                    File od = new File(Plugins.dir(c), old.optString("id", ""));
                    if (od.isDirectory()) {
                        target = old.optString("id", "");
                        dir = od;
                        update = true;
                        break;
                    }
                }
            }
            String id = update ? target : "ai_" + System.currentTimeMillis();
            if (!update) dir = new File(Plugins.dir(c), id);
            JSONObject m = new JSONObject();
            m.put("id", id);
            m.put("name", name);
            m.put("description", d.optString("description", "她在聊天里现写的插件"));
            m.put("version", update ? version(dir) : "1.0.0");
            m.put("author", "她");
            m.put("ai", true);
            JSONArray hs = d.optJSONArray("hosts");
            if (hs != null && hs.length() > 0) m.put("hosts", hs);
            m.put("background", d.optBoolean("background", false));
            m.put("tickMin", Math.max(2, Math.min(240, d.optInt("tickMin", 10))));
            if (d.optBoolean("native", false)) {
                String main = d.optString("main", "").trim();
                File dex = new File(d.optString("dexPath", ""));
                String source = d.optString("source", "");
                if (main.isEmpty() || source.isEmpty() || !dex.isFile() || !hasDexMagic(dex)) {
                    throw new Exception("原生插件草稿缺少已校验的 Java 源码或 DEX");
                }
                m.put("main", main);
                m.put("file", "plugin.dex");
                m.put("nativeSource", "source.java");
                m.put("permissionsNote", "原生 DEX 与桌宠同进程运行；由用户确认安装");
            } else {
                m.put("code", d.optString("code", ""));
            }
            if (update) m.put("updatedAt", System.currentTimeMillis());
            if (!dir.isDirectory() && !dir.mkdirs()) throw new Exception("无法创建插件安装目录");
            if (d.optBoolean("native", false)) {
                Sg.write(new File(dir, "source.java"), d.optString("source", ""));
                File installedDex = new File(dir, "plugin.dex");
                copy(new File(d.optString("dexPath", "")), installedDex);
                if (!installedDex.isFile() || installedDex.length() < 112 || !hasDexMagic(installedDex)) {
                    installedDex.delete();
                    throw new Exception("插件 DEX 复制后校验失败，未启用插件");
                }
                if (!installedDex.setReadOnly() || installedDex.canWrite()) {
                    installedDex.delete();
                    throw new Exception("无法将已安装的插件 DEX 设为只读，未启用插件");
                }
            }
            Sg.write(new File(dir, "manifest.json"), m.toString());
            PluginAudit.write(dir, d.optJSONObject("audit"));
            Plugins.toggle(c, id, true);
            Sg.writeObj(c, LAST_PLUGIN, new JSONObject().put("id", id).put("at", System.currentTimeMillis()));
            clear(c);
            out = m;
            Bus.emit("plugins", new JSONObject());
        } catch (Throwable t) {
            try {
                out.put("error", "安装失败：" + (t.getMessage() == null ? "" : t.getMessage()));
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    /** 原有的清单版本号，改插件时沿用（没有就 1.0.0）。 */
    private static String version(File dir) {
        try {
            JSONObject old = new JSONObject(Sg.read(new File(dir, "manifest.json"), "{}"));
            String v = old.optString("version", "");
            return v.isEmpty() ? "1.0.0" : v;
        } catch (Throwable t) {
            return "1.0.0";
        }
    }

    /* ---------------- 生成 ---------------- */

    public static void talk(Context c, String text, Ai.Cb cb) {
        JSONObject pendingRequest = pendingRequest(c);
        if (pendingRequest != null) {
            if (cancel(text)) {
                clearRequest(c);
                cb.ok("好，那先不写。你之后告诉我想让插件做什么就行。");
                return;
            }
            if (!hasConcreteGoal(text)) {
                cb.ok("我还差一点具体要求：你希望插件在哪个应用里做什么？比如“打开抖音，当前视频播完后再划下一条”。我先等你说清楚，不会擅自生成。");
                return;
            }
            text = pendingRequest.optString("initial", "写个插件") + "\n具体要求：" + text.trim();
            clearRequest(c);
        } else if (wants(text) && !hasConcreteGoal(text)) {
            try {
                Sg.writeObj(c, REQUEST, new JSONObject()
                        .put("initial", text.trim())
                        .put("at", System.currentTimeMillis()));
            } catch (Throwable ignored) { }
            cb.ok("可以呀，你想让插件具体做什么？告诉我它要在哪个应用里、按什么条件做什么事；我先等你说明，不会直接乱写。比如：“打开抖音，等当前视频播完再划到下一个。”");
            return;
        }
        JSONObject d = draft(c);
        if (d != null && confirm(text)) {
            JSONObject m = installDraft(c);
            if (m.has("error")) {
                cb.fail(m.optString("error"));
            } else if (m.has("updatedAt")) {
                cb.ok("改好了，「" + m.optString("name", "插件") + "」已经换成新的一版。插件已安装，但我还没有在手机上实际运行验证。");
            } else {
                cb.ok("装好了，「" + m.optString("name", "插件") + "」已经启用。注意：这只表示插件已安装，还没有实际运行或验证效果。");
            }
            return;
        }
        if (d != null && cancel(text)) {
            clear(c);
            cb.ok("好，那先不装了。");
            return;
        }
        // 「别刷了」：先停下后台插件，不打断聊天
        if (wantsStop(text)) {
            int n = pauseBackground(c);
            if (n > 0) cb.ok("行，我先不刷了，歇一会儿。想让我接着看就说「继续刷」。");
            else cb.fail("");
            return;
        }
        if (wantsResume(text)) {
            int n = resumeBackground(c);
            if (n > 0) cb.ok("好，那我接着来，等下把看到的有意思的告诉你。");
            else cb.fail("");
            return;
        }
        JSONObject target = find(c, text);
        // 「你看一下装上的 xxx 插件，解释一下怎么用」：读已装插件回答，不生成新东西
        if (target != null && !wantsEdit(text) && wantsExplain(text)) {
            explain(c, target, cb);
            return;
        }
        boolean edit = target != null && wantsEdit(text);
        if (!edit && !wants(text)) {
            cb.fail("");
            return;
        }
        Cfg cfg = Cfg.load(c);
        if (!cfg.aiReady()) {
            cb.fail("还没绑定 API Key，我写不了插件。也可以从本机导入现成的插件包。");
            return;
        }
        JSONArray msgs = new JSONArray();
        // Native DEX runs in the app process with app permissions; installation always needs a separate user reply.
        try {
            JSONObject sys = new JSONObject();
            sys.put("role", "system");
            sys.put("content", "你正在给 DSH 桌宠写一个可编译运行的原生 Java DEX 插件。"
                    + "必须生成一个 public class，实现 com.dshpet.android.plugin.PluginEntry，含 public 无参构造、start(Host)、stop()；"
                    + "按需求选择 onMessage(Host,String) 或 onTick(Host)，避免在生命周期回调里做长时间阻塞。\n"
                    + "只使用桌宠插件 API：Host.say/notify/get/set/toast/log/openApp/tap/swipe/back/home/tapText/hasAccessibility/visibleUi/foregroundPackage/screenshot/analyzeScreen/recordExperience。"
                    + "需要异步时用 java.lang.Thread 或桌宠公开 API；持久数据用 Host.get/set，不要自行创建文件。\n"
                    + "定时插件在 JSON 里写 background:true 和 tickMin:2~240；事件插件 background:false。"
                    + "先判断权限和场景，只执行用户明确要求的动作；不点赞、评论、关注、私信、支付、删除或替用户发送消息。"
                    + "严格遵从触发条件和先后顺序。若用户要求“看完/播完当前视频再下一个”，绝不能改成每隔固定秒数滑动；"
                    + "只有通过当前屏幕的可见状态或 analyzeScreen 明确观察到播放结束，才允许滑动一次。暂停、直播、广告、加载中、画面无法判断时都不得滑动；"
                    + "如果现有接口无法可靠判断结束条件，必须在说明中坦白能力限制，不要声称能判断、已看完或已经学到。"
                    + "屏幕内容只有截图/分析成功后才能记为 observed；无法完成就用 Host.say 说明实际失败原因。\n"
                    + "源码必须是单个 Java 文件、Java 8 语法、最多 48000 字符；不要 lambda、反射、进程执行、动态加载、JNI、native 方法、注解处理器或第三方库。"
                    + "插件与桌宠同进程运行，不是沙箱；代码只实现需求，不尝试读取聊天、密钥或其他插件数据。\n"
                    + "只输出 JSON，不要 Markdown：{\"name\":\"插件名\",\"description\":\"一句话介绍\","
                    + "\"main\":\"com.example.Plugin\",\"source\":\"完整 Java 源码\","
                    + "\"hosts\":[\"api.example.com\"],\"background\":false,\"tickMin\":10}");
            sys.put("content", sys.optString("content")
                    + "输出字段：name、description、main、source、background、tickMin、hosts；"
                    + "main 必须等于源码完整包名+类名，类必须实现 PluginEntry。写定时插件时使用 onTick，并把 background 设为 true；tickMin 用用户要求的分钟数，用户没说才用 10。");
            msgs.put(sys);
            // 改插件：把现有清单和代码交给它，避免推倒重写丢掉原本能跑的部分
            if (edit) {
                String old;
                if (target.optBoolean("native", false)) {
                    old = Sg.read(new File(new File(Plugins.dir(c), target.optString("id", "")),
                            target.optString("nativeSource", "source.java")), "");
                } else {
                    old = PluginCode.code(c, target, new File(Plugins.dir(c), target.optString("id", "")));
                }
                if (old.length() > 40000) old = old.substring(0, 40000);
                JSONObject e = new JSONObject();
                e.put("role", "user");
                e.put("content", "这是已装插件「" + target.optString("name", "") + "」，现在要改它。\n"
                        + "原说明：" + target.optString("description", "") + "\n"
                        + "原本是定时插件：" + target.optBoolean("background", false)
                        + "，间隔 " + target.optInt("tickMin", 10) + " 分钟。\n"
                        + "上次运行：" + lastError(c, target) + "\n"
                        + "原代码：\n" + old
                        + "\n要求：在原代码基础上改，保留原本能跑的入口和已有功能，只改下面这次要求的部分；"
                        + "如果上次有报错，先把那个错修掉；输出完整的新代码，不要只给片段；"
                        + "清单字段也按本次需要重新给出。原生插件必须继续生成完整 Java PluginEntry 源码。");
                msgs.put(e);
            }
            // 只按用户这一句需求写，不带任何历史聊天
            JSONObject u = new JSONObject();
            u.put("role", "user");
            u.put("content", "需求：" + text + "\n只按这条需求写，需求里没提到的功能不要加进来。若需求存在歧义或关键触发条件缺失，应停止生成并说明需要澄清，不可自行猜测。");
            msgs.put(u);
        } catch (Throwable ignored) {
        }
        final String requestForPlugin = text.trim();
        // 0 = 不设 max_tokens 上限，让插件代码按需要写完整，不再因为截断丢后半段
        Ai.rawChat(c, msgs, 0, 0.25, new Ai.Cb() {
            @Override
            public void ok(String reply) {
                try {
                    JSONObject root = parseReply(reply);
                    String source = root.optString("source", "");
                    String main = root.optString("main", "").trim();
                    if (source.length() > MAX_CODE || source.trim().isEmpty() || main.isEmpty()) {
                        cb.fail("她没有生成完整的 Java 插件源码，换个说法再试试。");
                        return;
                    }
                    compileAndSave(c, main, source, root, requestForPlugin, edit, target, cb, 0);
                } catch (Throwable t) {
                    cb.fail("她写出来的东西不太对，换个说法再试试。");
                }
            }

            @Override
            public void fail(String err) {
                cb.fail(err == null || err.isEmpty() ? "写插件失败了，稍后再试。" : err);
            }
        });
    }

    /** Try to repair source against real on-device compiler diagnostics; never install on this path. */
    private static void compileAndSave(Context c, String main, String source, JSONObject manifest,
                                       String request, boolean edit, JSONObject target, Ai.Cb cb, int attempt) {
        NATIVE_COMPILE.execute(() -> {
            PluginDexCompiler.Result result = PluginDexCompiler.compile(c, main, source);
            if (!result.ok()) {
                String error = result.error == null ? "未知编译错误" : result.error;
                if (isCompilerToolchainFailure(error)) {
                    cb.fail("手机内置的 Java/DEX 编译工具链缺少标准 Java 类或运行组件。这个问题会影响各种插件，继续改某一个插件源码也修不好；插件未保存或安装。需要先修复共用编译环境。\n" + shortError(error));
                    return;
                }
                if (attempt < 2 && Cfg.load(c).aiReady()) {
                    repairGeneratedPlugin(c, main, source, manifest, request, edit, target, cb, attempt + 1, error);
                } else {
                    error = shortError(error);
                    cb.fail("我按手机编译器的报错自动修了两次，还是没通过，所以没有保存或安装这个插件。最后的错误是：\n" + error);
                }
                return;
            }
            try {
                JSONObject audit = PluginAudit.reviewGeneratedNative(source, manifest);
                JSONObject d = new JSONObject();
                String name = manifest.optString("name", "AI 插件").trim();
                if (name.isEmpty()) name = "AI 插件";
                if (name.length() > 20) name = name.substring(0, 20);
                d.put("name", name);
                d.put("description", manifest.optString("description", ""));
                d.put("source", source);
                d.put("main", main);
                d.put("dexPath", result.dex.getAbsolutePath());
                d.put("native", true);
                JSONArray hs = manifest.optJSONArray("hosts");
                if (hs != null && hs.length() > 0) d.put("hosts", hs);
                d.put("background", manifest.optBoolean("background", false));
                d.put("tickMin", Math.max(2, Math.min(240, manifest.optInt("tickMin", 10))));
                d.put("audit", audit);
                if (edit && target != null) d.put("target", target.optString("id", ""));
                d.put("at", System.currentTimeMillis());
                clear(c);
                Sg.writeObj(c, DRAFT, d);
                String desc = d.optString("description", "");
                JSONArray auditIssues = audit.optJSONArray("issues");
                String auditText = audit.optString("summary", "本机结构检查完成，但未实际运行插件。");
                if (auditIssues != null && auditIssues.length() > 0) {
                    StringBuilder warnings = new StringBuilder("风险提示：");
                    for (int i = 0; i < Math.min(3, auditIssues.length()); i++) {
                        if (i > 0) warnings.append("；");
                        warnings.append(auditIssues.optString(i));
                    }
                    auditText += warnings.toString();
                }
                cb.ok("我写好并编译了「" + name + "」"
                        + (desc.isEmpty() ? "" : "：" + desc)
                        + "。" + auditText + " 看过源码并确认后，回复「装上」启用，回复「算了」放弃。");
            } catch (Throwable t) {
                cb.fail("DEX 已生成，但保存插件草稿失败：" + (t.getMessage() == null ? "未知错误" : t.getMessage()));
            }
        });
    }

    private static boolean isCompilerToolchainFailure(String error) {
        if (error == null) return false;
        String e = error.toLowerCase(java.util.Locale.ROOT);
        return e.contains("noclassdeffounderror")
                || e.contains("writable dex file") || e.contains("内置 java/dex 编译器资源不完整")
                || e.contains("java.lang.object cannot be resolved")
                || e.contains("java.lang.string cannot be resolved")
                || e.contains("java.lang.sourceversion") || e.contains("sourceversion cannot be resolved")
                || e.contains("the type java.lang.object cannot be resolved")
                || e.contains("the type java.lang.string cannot be resolved");
    }

    private static String shortError(String error) {
        if (error == null) return "未知编译错误";
        return error.length() > 1400 ? error.substring(0, 1400) : error;
    }

    private static void repairGeneratedPlugin(Context c, String main, String source, JSONObject manifest,
                                              String request, boolean edit, JSONObject target, Ai.Cb cb,
                                              int attempt, String compilerError) {
        JSONArray msgs = new JSONArray();
        try {
            msgs.put(new JSONObject().put("role", "system").put("content",
                    "你是 Java/Android 插件编译修复器。根据手机上的真实编译错误修复完整源码。"
                            + "只修编译和入口问题，保持用户要求的行为，不添加无关功能，不伪造手机能力。"
                            + "只使用桌宠插件公开 API；Java 8；不使用第三方库、反射、进程、动态加载或文件系统。"
                            + "输出完整 JSON，不要 Markdown：{\"name\":\"...\",\"description\":\"...\",\"main\":\"完整类名\",\"source\":\"完整 Java 源码\",\"hosts\":[],\"background\":false,\"tickMin\":10}"));
            msgs.put(new JSONObject().put("role", "user").put("content",
                    "用户原始需求：\n" + request + "\n\n当前入口类：" + main
                            + "\n\n手机编译器错误（优先修复根因）：\n" + compilerError
                            + "\n\n当前完整源码：\n" + source
                            + "\n\n当前清单：\n" + manifest.toString()
                            + "\n请给出完整、可编译的新源码和清单。"));
        } catch (Throwable t) {
            cb.fail("无法准备编译修复请求");
            return;
        }
        Ai.rawChat(c, msgs, 0, 0.2, new Ai.Cb() {
            @Override public void ok(String reply) {
                try {
                    JSONObject fixed = parseReply(reply);
                    String fixedSource = fixed.optString("source", "");
                    String fixedMain = fixed.optString("main", "").trim();
                    if (fixedSource.isEmpty() || fixedSource.length() > MAX_CODE || fixedMain.isEmpty()) {
                        cb.fail("自动修复没有返回完整源码，插件未保存或安装。");
                        return;
                    }
                    compileAndSave(c, fixedMain, fixedSource, fixed, request, edit, target, cb, attempt);
                } catch (Throwable t) {
                    cb.fail("自动修复结果格式不正确，插件未保存或安装。");
                }
            }
            @Override public void fail(String err) {
                cb.fail("自动修复服务未完成，插件未保存或安装。" + (err == null || err.isEmpty() ? "" : "原因：" + err));
            }
        });
    }

    /** 「解释一下装上的 xxx 插件怎么用」：读清单和代码，用她自己的口吻讲给用户听。 */
    private static void explain(Context c, JSONObject p, Ai.Cb cb) {
        if (!Cfg.load(c).aiReady()) {
            cb.fail("还没绑定 API Key，我读不了插件代码。");
            return;
        }
        File pluginDir = new File(Plugins.dir(c), p.optString("id", ""));
        String code = p.optBoolean("native", false)
                ? Sg.read(new File(pluginDir, p.optString("nativeSource", "source.java")), "")
                : PluginCode.code(c, p, pluginDir);
        if (code.length() > 40000) code = code.substring(0, 40000);
        JSONArray hosts = p.optJSONArray("hosts");
        String hostStr = hosts == null || hosts.length() == 0 ? "没有" : hosts.toString();
        JSONArray msgs = new JSONArray();
        try {
            JSONObject sys = new JSONObject();
            sys.put("role", "system");
            sys.put("content", "用户问一个已经装好的插件是干什么的、怎么用，或者为什么跑不起来。用她自己的口吻（第一人称）讲清楚："
                    + "它什么时候会动（聊天里提到才跑，还是每隔多久在后台自己跑）、实际会做什么、"
                    + "用到手机上的哪些能力（打开应用、点按滑动、截屏、调模型、联网）、有什么限制。"
                    + "如果给了上次报错，就用普通话说明是哪一步不行、为什么不行（比如没开辅助功能、白名单没写这个域名、"
                    + "调用了不存在的函数、等太久超时），并说清楚要怎么办；没报错就别硬说有问题。"
                    + "不要念代码，不要提「以下是我写的代码」，不用 Markdown 和列表符号；"
                    + "口语、简短，250 字以内；看不出来的地方就说不确定，别编。");
            msgs.put(sys);
            JSONObject u = new JSONObject();
            u.put("role", "user");
            u.put("content", "插件名：" + p.optString("name", "插件")
                    + "\n说明：" + p.optString("description", "")
                    + "\n是否启用：" + (p.optBoolean("enabled") ? "是" : "否")
                    + "\n定时后台：" + (p.optBoolean("background")
                    ? "是，每 " + p.optInt("tickMin", 10) + " 分钟" : "否，只在聊天里匹配时跑")
                    + "\n联网域名：" + hostStr
                    + "\n上次运行：" + lastError(c, p)
                    + "\n代码：\n" + code);
            msgs.put(u);
        } catch (Throwable ignored) {
        }
        Ai.rawChat(c, msgs, 0, 0.4, cb);
    }

    /** 上次运行失败的原因（插件自己目录里的 last_error.json），超过 30 天不再翻出来。 */
    private static String lastError(Context c, JSONObject p) {
        try {
            File f = new File(new File(Plugins.dir(c), p.optString("id", "")), "last_error.json");
            String s = Sg.read(f, "");
            if (s.trim().isEmpty()) return "没记录到报错";
            JSONObject o = new JSONObject(s);
            long at = o.optLong("at", 0);
            String reason = o.optString("reason", "").trim();
            if (at <= 0 || reason.isEmpty()) return "没记录到报错";
            long days = (System.currentTimeMillis() - at) / 86400000L;
            if (days > 30) return "没记录到报错";
            return reason + (days <= 0 ? "（今天）" : "（" + days + " 天前）");
        } catch (Throwable t) {
            return "没记录到报错";
        }
    }

    private static boolean hasDexMagic(File file) {
        try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
            if (in.read() != 'd' || in.read() != 'e' || in.read() != 'x' || in.read() != '\n') return false;
            int a = in.read(), b = in.read(), c = in.read();
            int version = (a - '0') * 100 + (b - '0') * 10 + c - '0';
            return a >= '0' && a <= '9' && b >= '0' && b <= '9' && c >= '0' && c <= '9'
                    && version >= 35 && version <= 41 && in.read() == 0;
        } catch (Throwable ignored) { return false; }
    }

    private static void copy(File from, File to) throws Exception {
        File parent = to.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new Exception("无法创建插件目录");
        try (java.io.InputStream in = new java.io.FileInputStream(from);
             java.io.FileOutputStream out = new java.io.FileOutputStream(to)) {
            byte[] b = new byte[32768];
            for (int n; (n = in.read(b)) >= 0;) if (n > 0) out.write(b, 0, n);
            out.getFD().sync();
        }
        to.setReadOnly();
    }

    /**
     * 模型常带 ``` 围栏或前后废话，先把 JSON 抠出来；
     * 真被截断（少个 } 或半个字符串）时按字段名单独取，尽量不整条丢掉。
     */
    private static JSONObject parseReply(String reply) {
        String s = reply == null ? "" : reply.trim();
        s = s.replace("```json", "").replace("```JSON", "").replace("```", "").trim();
        int a = s.indexOf('{');
        int b = s.lastIndexOf('}');
        String body = a < 0 ? "" : (b > a ? s.substring(a, b + 1) : s.substring(a));
        if (!body.isEmpty()) {
            try {
                JSONObject o = new JSONObject(body);
                if (!o.optString("source", "").isEmpty() || !o.optString("code", "").isEmpty()) return o;
            } catch (Throwable ignored) {
            }
        }
        String src = body.isEmpty() ? s : body;
        JSONObject o = new JSONObject();
        try {
            o.put("source", field(src, "source"));
            o.put("main", field(src, "main"));
            o.put("name", field(src, "name"));
            o.put("description", field(src, "description"));
            o.put("background", src.matches("(?s).*\\\"background\\\"\\s*:\\s*true.*"));
            String tick = field(src, "tickMin");
            if (!tick.isEmpty()) o.put("tickMin", Integer.parseInt(tick));
        } catch (Throwable ignored) {
        }
        return o;
    }

    /** 从残缺 JSON 里取字符串字段，并还原 \\n \\" 等转义。 */
    private static String field(String s, String key) {
        int i = s.indexOf("\"" + key + "\"");
        if (i < 0) return "";
        i = s.indexOf(':', i + key.length() + 2);
        if (i < 0) return "";
        i++;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        if (i >= s.length() || s.charAt(i) != '"') return "";
        StringBuilder sb = new StringBuilder();
        i++;
        while (i < s.length()) {
            char ch = s.charAt(i++);
            if (ch == '"') break;
            if (ch == '\\' && i < s.length()) {
                char e = s.charAt(i++);
                switch (e) {
                    case 'n':
                        sb.append('\n');
                        break;
                    case 't':
                        sb.append('\t');
                        break;
                    case 'r':
                        break;
                    case 'u':
                        if (i + 4 <= s.length()) {
                            try {
                                sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                                i += 4;
                            } catch (Throwable ignored) {
                            }
                        }
                        break;
                    default:
                        sb.append(e);
                }
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    private static boolean has(String s, String... keys) {
        for (String k : keys) {
            if (s.contains(k)) return true;
        }
        return false;
    }
}
