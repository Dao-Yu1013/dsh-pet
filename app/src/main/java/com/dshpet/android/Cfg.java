package com.dshpet.android;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

/** 设置，落在 S-G/config.json。 */
public final class Cfg {
    public static final int MIN_SIZE = 80;
    public static final int MAX_SIZE = 360;
    /** 同一进程里可能同时存在多个 Cfg 实例（UI、桌宠服务），写盘时用它串行化。 */
    private static final Object IO = new Object();
    private final JSONObject j;
    /** 只在本次实例里被改过的键；落盘时只覆盖这些键，避免旧快照把别的组件刚存的设置冲掉。 */
    private final java.util.Set<String> dirty = new java.util.HashSet<>();

    private Cfg(JSONObject o) {
        j = o;
    }

    public static Cfg load(Context c) {
        JSONObject o = Sg.readObj(c, "config.json");
        defaults(o);
        return new Cfg(o);
    }

    private static void defaults(JSONObject o) {
        put(o, "apiKey", "");
        put(o, "baseUrl", "https://api.deepseek.com");
        put(o, "model", "deepseek-chat");
        // 视觉模型：只用于看屏幕截图和识别图片；留空时 DeepSeek 默认 deepseek-flash
        put(o, "visionApiKey", "");
        put(o, "visionBaseUrl", "");
        put(o, "visionModel", "");
        // 原生音视频理解 API 与兼容 OpenAI 的视觉接口分开配置。
        put(o, "geminiVideoApiKey", "");
        put(o, "geminiVideoModel", "gemini-3.8-flash");
        // 独立图像生成接口；文本/视觉聊天模型通常不具备生成图片的能力
        put(o, "imageGenProvider", "openai");
        put(o, "imageGenBaseUrl", "");
        put(o, "imageGenApiKey", "");
        put(o, "imageGenModel", "");
        put(o, "comfyUiBaseUrl", "");
        put(o, "comfyUiWorkflow", "");
        put(o, "comfyUiPromptNodeIds", "");
        // 余额接口各家不同，留空按供应商自动判断
        put(o, "balancePath", "");
        put(o, "visionBalancePath", "");
        put(o, "sizeDp", 150);
        put(o, "opacity", 1.0);
        put(o, "locked", false);
        put(o, "running", false);
        put(o, "posX", -1);
        put(o, "posY", -1);
        put(o, "facing", 1);
        put(o, "autoStart", true);
        // 皮肤：默认原版 106 逐帧动画；用户在换装页主动选过才记住立绘皮肤
        put(o, "skinId", "original");
        put(o, "petSound", true);
        // 后台与通知：保活每 15 分钟检查一次服务是否还在
        put(o, "keepAlive", true);
        put(o, "musicAware", true);
        // 深夜 + 慢歌 + 情绪低落时的共情（场景组合判定，不猜歌词）
        put(o, "musicCare", true);
        put(o, "musicHum", true);
        put(o, "lyricBubble", true);
        put(o, "humAction", "a088");
        put(o, "musicHumSec", 55);
        // 歌词换句间隔（秒）：跟着歌词句子走，和哼歌间隔分开
        put(o, "lyricGapSec", 5);
        // 用 AI 认识正在听的歌并聊歌（按歌名+歌手查一次并缓存，会消耗 token）
        put(o, "musicAi", true);
        put(o, "songChatMin", 8);
        put(o, "proactive", true);
        put(o, "freeAction", false);
        put(o, "freeActionApps", new JSONArray());
        put(o, "freeActionIntervalSec", 60);
        put(o, "proactiveMin", 20);
        put(o, "greetEnabled", true);
        put(o, "greetPerDay", 2);
        put(o, "proactiveLevel", 1);
        put(o, "screenContext", true);
        put(o, "screenScanSec", 3);
        put(o, "gameEnabled", true);
        put(o, "games", new JSONArray());
        put(o, "sleepEnabled", false);
        put(o, "sleepStart", "23:30");
        put(o, "sleepEnd", "07:00");
        put(o, "adEnabled", false);
        put(o, "adShowLine", true);
        put(o, "remindersEnabled", true);
        put(o, "msgEnabled", true);
        put(o, "parcelEnabled", true);
        put(o, "travelEnabled", true);
        put(o, "calEnabled", true);
        put(o, "notifyApps", new JSONArray());
        put(o, "notifyAi", true);
        put(o, "realtimeEnabled", true);
        put(o, "realtimeSpeak", true);
        put(o, "aiActionLines", true);
        put(o, "aiSceneActions", true);
        put(o, "aiCoolSec", 40);
        put(o, "actionCoolSec", 10);
        put(o, "speakCoolSec", 120);
        // 应用感知：记录用户在本应用里的高层动作，用于聊天接话和吐槽
        put(o, "watchApp", true);
        put(o, "bubbleSec", 6);
        put(o, "memoryAuto", true);
        put(o, "memoryExtractEvery", 6);
        put(o, "memoryExtractGapMin", 10);
        put(o, "memoryMaxActive", 600);
        put(o, "memoryConfidenceFloor", 0.5);
        put(o, "memorySensitiveFilter", true);
        put(o, "stickerUseMore", false);
        // 关系与记忆
        put(o, "diaryEnabled", true);
        put(o, "traitEvolve", true);
        put(o, "traitSpeed", 1);
        put(o, "recapAuto", true);
        // 主动策略（每天主动次数、每小时上限、闲聊间隔都由亲密度自动推导，不再作为设置项）
        put(o, "polQuiet", false);
        put(o, "polNightStart", "23:00");
        put(o, "polNightEnd", "07:00");
        put(o, "polNightBubbleOnly", true);
        put(o, "polCatChat", true);
        put(o, "polCatReminder", true);
        put(o, "polCatHealth", true);
        put(o, "polCatMusic", true);
        put(o, "polCatAd", true);
        put(o, "polCatFocus", true);
        put(o, "polCatGeo", true);
        put(o, "polCatPlugin", true);
        put(o, "polFocusImportant", true);
        put(o, "polMoodAffect", true);
        // 语音
        put(o, "ttsEnabled", false);
        put(o, "ttsOnlyImportant", true);
        put(o, "ttsRate", 1.0);
        put(o, "ttsPitch", 1.0);
        put(o, "ttsNatural", true);
        put(o, "ttsVoice", "");
        // edge = 微软在线朗读（默认，音质好，需联网）；system = 系统 TTS（离线兜底）；online = 自定义在线语音
        put(o, "ttsEngine", "edge");
        put(o, "edgeVoice", "zh-CN-XiaoxiaoNeural");
        // 系统 TTS 引擎包名：空 = 系统默认，可切到讯飞/Google 等以拿到更多音色
        put(o, "ttsSysEngine", "");
        // 自定义在线语音：OpenAI 兼容的 /audio/speech
        put(o, "ttsOnlineUrl", "");
        put(o, "ttsOnlineKey", "");
        put(o, "ttsOnlineModel", "tts-1");
        put(o, "ttsOnlineVoice", "alloy");
        put(o, "asrAutoSend", true);
        put(o, "weatherEnabled", true);
        // 每 3 小时一次的天气/气象预警巡检（下雨或黄色以上预警时提醒）
        put(o, "weatherAlertCheck", true);
        put(o, "weatherAlertLastAt", 0L);
        // 电量关心：深夜手机快没电时唠叨一次
        put(o, "batteryCare", true);
        put(o, "batteryWarnAt", 0L);
        // 剪贴板
        put(o, "clipMask", true);
        put(o, "clipHistory", 60);
        // 健康动作
        put(o, "wellEnabled", true);
        put(o, "wellSitMin", 50);
        put(o, "wellWaterMin", 60);
        put(o, "wellEyeMin", 40);
        put(o, "wellQuietNight", true);
        // 位置
        put(o, "geoEnabled", false);
        put(o, "geoRemindHome", true);
        put(o, "geoRemindWork", true);
        // 日程/专注
        put(o, "meetRemind", true);
        // 通知摘要
        put(o, "notifSummary", true);
        put(o, "notifMuted", new JSONArray());
        // 音乐增强
        put(o, "musicGesture", true);
        put(o, "whiteNoise", "");
        put(o, "lyricTranslate", true);
        // 插件
        put(o, "pluginOff", new JSONArray());
        // 个人 QQ OneBot V11 forward-WebSocket bridge; disabled and allowlist empty by default.
        put(o, "qqLinkEnabled", false);
        put(o, "qqOnebotUrl", "");
        put(o, "qqOnebotToken", "");
        put(o, "qqAllowedUserId", "");
        put(o, "qqGroupIds", "");
        put(o, "qqGroupMode", "mention");
        put(o, "qqGroupAstrBot", false);
        // 同步
        put(o, "davUrl", "");
        put(o, "davUser", "");
        put(o, "davPass", "");
        put(o, "syncPass", "");
        put(o, "syncAuto", false);
        put(o, "lastSync", 0L);
        // 本地意图 / 开发者
        put(o, "localNlu", true);
        put(o, "devMode", false);
        // 家人阶段：自定义主动次数 / 彩蛋封印状态
        put(o, "familyDayCap", 60);
        put(o, "familySealed", false);
        put(o, "devLocked", false);
        put(o, "adTest", false);
        put(o, "adOffApps", new JSONArray());
        put(o, "firstRun", true);
        put(o, "days", 1);
        put(o, "installDay", Sg.now().substring(0, 10));
    }

    private static void put(JSONObject o, String k, Object v) {
        try {
            if (!o.has(k)) o.put(k, v);
        } catch (Throwable ignored) {
        }
    }

    public synchronized JSONObject json() {
        try {
            return new JSONObject(j.toString());
        } catch (Throwable t) {
            return new JSONObject();
        }
    }

    /**
     * 只把本次真正改过的键合并到当前磁盘上的 config.json，再整体写回。
     * 桌宠服务是长生命周期实例，直接整份写回会用它启动时的旧快照覆盖 UI 刚保存的设置
     * （例如「接收应用」白名单），所以这里改为增量合并。
     */
    public synchronized void save(Context c) {
        synchronized (IO) {
            JSONObject disk = Sg.readObj(c, "config.json");
            if (disk.length() == 0) {
                // 首次落盘：把默认值一起写下
                disk = j;
            } else {
                for (String k : dirty) {
                    try {
                        disk.put(k, j.opt(k));
                    } catch (Throwable ignored) {
                    }
                }
            }
            dirty.clear();
            Sg.writeObj(c, "config.json", disk);
        }
    }

    public synchronized String str(String k, String d) {
        return j.optString(k, d);
    }

    public synchronized int i(String k, int d) {
        return j.optInt(k, d);
    }

    public synchronized double d(String k, double d) {
        return j.optDouble(k, d);
    }

    public synchronized long l(String k, long d) {
        return j.optLong(k, d);
    }

    public synchronized boolean b(String k, boolean d) {
        return j.optBoolean(k, d);
    }

    public synchronized JSONArray arr(String k) {
        JSONArray a = j.optJSONArray(k);
        return a == null ? new JSONArray() : a;
    }

    public synchronized void set(String k, Object v) {
        try {
            j.put(k, v);
            dirty.add(k);
        } catch (Throwable ignored) {
        }
    }

    public synchronized void merge(JSONObject o) {
        if (o == null) return;
        java.util.Iterator<String> it = o.keys();
        while (it.hasNext()) {
            String k = it.next();
            try {
                j.put(k, o.opt(k));
                dirty.add(k);
            } catch (Throwable ignored) {
            }
        }
    }

    public String apiKey() {
        return str("apiKey", "").trim();
    }

    public boolean aiReady() {
        return !apiKey().isEmpty();
    }

    public String baseUrl() {
        String u = str("baseUrl", "https://api.deepseek.com").trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }

    /** 视觉模型：单独填了 Key 才启用，地址和模型名空着就沿用主模型。 */
    public String visionKey() {
        String k = str("visionApiKey", "").trim();
        return k.isEmpty() ? apiKey() : k;
    }

    public String visionBase() {
        String u = str("visionBaseUrl", "").trim();
        if (u.isEmpty()) return baseUrl();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }

    public String visionModelName() {
        String m = str("visionModel", "").trim();
        if (!m.isEmpty()) return m;
        // 留空：DeepSeek 用带视觉的 flash，别家沿用主模型
        String b = visionBase().toLowerCase(java.util.Locale.US);
        return b.contains("deepseek") ? "deepseek-flash" : str("model", "deepseek-chat");
    }

    public boolean visionReady() {
        return !visionKey().isEmpty();
    }

    public int sizeDp() {
        int v = i("sizeDp", 150);
        return Math.max(MIN_SIZE, Math.min(MAX_SIZE, v));
    }

    public float opacity() {
        return (float) Math.max(0.5, Math.min(1.0, d("opacity", 1.0)));
    }

    public boolean isGame(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        JSONArray a = arr("games");
        for (int k = 0; k < a.length(); k++) {
            if (pkg.equals(a.optString(k))) return true;
        }
        return false;
    }
}
