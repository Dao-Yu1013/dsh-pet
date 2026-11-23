package com.dshpet.android;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 系统语音识别 + 系统 TTS，可只在重要提醒时朗读。 */
public final class Voice {
    public interface Cb {
        void text(String text, boolean isFinal);

        void error(String msg);
    }

    private static TextToSpeech tts;
    private static boolean ready;
    private static boolean initFailed;
    private static long initAt;
    private static volatile String voiceError = "";
    private static final Handler H = new Handler(Looper.getMainLooper());
    private static final Pattern PAREN = Pattern.compile("[（(]([^（）()]{1,14})[）)]");
    private static SpeechRecognizer sr;
    private static Cb asrCb;
    private static boolean listening;
    private static String asrPartial = "";

    private Voice() {
    }

    /* ---------------- TTS ---------------- */
    public static synchronized void init(final Context c) {
        if (tts != null || initFailed) return;
        final Context app = c.getApplicationContext();
        initAt = System.currentTimeMillis();
        final String want = Cfg.load(c).str("ttsSysEngine", "").trim();
        TextToSpeech.OnInitListener l = status -> {
            if (status != TextToSpeech.SUCCESS) {
                // 引擎装不上/没数据：记下来，别让调用方每 800ms 无限重试
                initFailed = true;
                ready = false;
                voiceError = "系统语音引擎不可用，请在系统设置里安装或切换文字转语音引擎";
                H.post(() -> Bus.emit("tts", state(app)));
                return;
            }
            ready = true;
            apply(app);
            H.post(() -> Bus.emit("tts", state(app)));
        };
        tts = want.isEmpty() ? new TextToSpeech(app, l) : new TextToSpeech(app, l, want);
    }

    /** 切换系统 TTS 引擎：重建实例后重新列音色。 */
    public static synchronized void setSystemEngine(Context c, String pkg) {
        try {
            if (tts != null) tts.shutdown();
        } catch (Throwable ignored) {
        }
        tts = null;
        ready = false;
        initFailed = false;
        initAt = 0;
        voiceError = "";
        init(c);
    }

    private static void apply(Context c) {
        if (tts == null) return;
        try {
            Cfg cfg = Cfg.load(c);
            int r = tts.setLanguage(Locale.CHINA);
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts.setLanguage(Locale.getDefault());
            }
            voiceError = "";
            String sel = cfg.str("ttsVoice", "");
            if (!sel.isEmpty() && !applyVoice(tts, sel)) {
                voiceError = "这个音色当前不可用，已用系统默认音色";
            }
            // 切换语言/音色后引擎可能重置语速音调，最后再应用一次
            tts.setSpeechRate((float) Math.max(0.5, Math.min(2.0, cfg.d("ttsRate", 1.0))));
            tts.setPitch((float) Math.max(0.5, Math.min(2.0, cfg.d("ttsPitch", 1.0))));
        } catch (Throwable ignored) {
        }
    }

    /** 按“名称|语言”精确匹配，避免同名音色选错或选中后不生效。 */
    private static boolean applyVoice(TextToSpeech engine, String sel) {
        if (engine == null || sel == null || sel.isEmpty()) return false;
        String name = sel;
        String loc = "";
        int p = sel.lastIndexOf('|');
        if (p > 0) {
            name = sel.substring(0, p);
            loc = sel.substring(p + 1);
        }
        try {
            Set<android.speech.tts.Voice> vs = engine.getVoices();
            if (vs == null) return false;
            for (android.speech.tts.Voice v : vs) {
                if (v.getName() == null || !v.getName().equals(name)) continue;
                String vl = v.getLocale() == null ? "" : v.getLocale().toString();
                if (!loc.isEmpty() && !vl.equals(loc)) continue;
                try {
                    if (v.getLocale() != null) {
                        int lr = engine.setLanguage(v.getLocale());
                        if (lr == TextToSpeech.LANG_MISSING_DATA || lr == TextToSpeech.LANG_NOT_SUPPORTED) {
                            engine.setLanguage(Locale.CHINA);
                        }
                    }
                    if (engine.setVoice(v) == TextToSpeech.SUCCESS) return true;
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    public static void speak(Context c, String text, boolean important) {
        Cfg cfg = Cfg.load(c);
        if (!cfg.b("ttsEnabled", false)) return;
        if (!important && cfg.b("ttsOnlyImportant", true)) return;
        if (text == null || text.trim().isEmpty()) return;
        speakByEngine(c, clean(text.trim(), true), cfg);
    }

    /** 阅读器朗读：不受“仅重要提醒朗读”开关限制，可单独指定语速。 */
    public static void speakForce(Context c, String text, float rate) {
        if (text == null || text.trim().isEmpty()) return;
        Cfg cfg = Cfg.load(c);
        speakByEngine(c, clean(text.trim(), false), cfg, (float) Math.max(0.5, Math.min(2.5, rate)));
    }

    /** 聊天语音消息：沿用陪伴朗读的引擎、音色、语速和音调，但由用户点击时不受自动朗读开关限制。 */
    public static void speakChat(Context c, String text) {
        if (text == null || text.trim().isEmpty()) return;
        Cfg cfg = Cfg.load(c);
        String plain = Sticker.strip(text.trim());
        speakByEngine(c, clean(plain, true), cfg);
    }

    /** 只有用户明确要求本轮用语音回复时才附加语音消息。 */
    public static boolean wantsVoiceReply(String userText) {
        if (userText == null) return false;
        String s = userText.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        if (s.contains("能不能") || s.contains("可不可以") || s.contains("会不会")
                || s.endsWith("吗") || s.endsWith("吗？") || s.endsWith("吗?")) return false;
        String[] asks = {"用语音说", "用语音回答", "用语音回复", "语音回复我", "语音回答我",
                "发语音给我", "给我发语音", "发条语音", "发个语音", "发一段语音", "语音给我",
                "用语音念", "念给我听", "读给我听"};
        for (String ask : asks) if (s.contains(ask)) return true;
        return false;
    }

    /** 试听：不受“回复朗读”开关限制，用于选择音色后立即确认效果。 */
    public static void test(Context c, String text) {
        if (text == null || text.trim().isEmpty()) return;
        speakByEngine(c, clean(text.trim(), true), Cfg.load(c));
    }

    private static void speakByEngine(Context c, String text, Cfg cfg) {
        speakByEngine(c, text, cfg, -1);
    }

    /**
     * 选引擎：默认走 Edge 在线语音，失败自动退回系统 TTS。
     * rate 小于 0 表示用配置里的语速。
     */
    private static void speakByEngine(Context c, final String text, final Cfg cfg, float rate) {
        if (text == null || text.isEmpty()) return;
        final Context app = c.getApplicationContext();
        final double r = rate > 0 ? rate : cfg.d("ttsRate", 1.0);
        final double p = cfg.d("ttsPitch", 1.0);
        String engine = cfg.str("ttsEngine", "edge");
        if ("edge".equals(engine)) {
            EdgeTts.speak(app, text, r, p, cfg.str("edgeVoice", EdgeTts.DEFAULT_VOICE), new EdgeTts.Cb() {
                @Override
                public void ok() {
                    voiceError = "";
                }

                @Override
                public void fail(String err) {
                    // 没网 / 接口变更 / 被拦：退回系统朗读，不打断聊天
                    voiceError = "在线语音不可用，已改用系统朗读";
                    speakSystem(app, text, (float) r, (float) p);
                }
            });
            return;
        }
        if ("online".equals(engine)) {
            OnlineTts.speak(app, text, r, new OnlineTts.Cb() {
                @Override
                public void ok() {
                    voiceError = "";
                }

                @Override
                public void fail(String err) {
                    voiceError = "自定义在线语音不可用，已改用系统朗读";
                    speakSystem(app, text, (float) r, (float) p);
                }
            });
            return;
        }
        speakSystem(app, text, (float) r, (float) p);
    }

    private static void speakSystem(final Context app, final String text, final float rate, final float pitch) {
        init(app);
        H.post(() -> {
            if (initFailed || !ready || tts == null) {
                // 只等 6 秒；超时就明确报不可用，不再无限 postDelayed
                if (initFailed) {
                    if (voiceError.isEmpty()) {
                        voiceError = "系统语音引擎不可用，请在系统设置里安装或切换文字转语音引擎";
                    }
                    return;
                }
                if (System.currentTimeMillis() - initAt < 6000) {
                    H.postDelayed(() -> speakSystem(app, text, rate, pitch), 500);
                } else if (voiceError.isEmpty()) {
                    voiceError = "系统语音引擎没有响应，请在系统设置里检查文字转语音";
                }
                return;
            }
            try {
                apply(app);
                tts.setSpeechRate(rate);
                tts.setPitch(pitch);
                speakText(tts, text, Cfg.load(app).b("ttsNatural", true));
            } catch (Throwable ignored) {
            }
        });
    }

    public static void stopSpeak() {
        EdgeTts.stop();
        OnlineTts.stop();
        try {
            if (tts != null) tts.stop();
        } catch (Throwable ignored) {
        }
    }

    /** 按标点和长度切句，句间插入短暂静音，减少机械念稿感。 */
    private static void speakText(TextToSpeech engine, String text, boolean natural) {
        if (engine == null || text == null || text.isEmpty()) return;
        List<String> parts;
        if (natural) parts = split(text);
        else {
            parts = new ArrayList<>();
            parts.add(text);
        }
        boolean first = true;
        String prev = "";
        int n = 0;
        for (String raw : parts) {
            final String part = raw.trim();
            if (part.isEmpty()) continue;
            if (!first) {
                try {
                    engine.playSilentUtterance(pauseOf(prev), TextToSpeech.QUEUE_ADD, "dsh_p" + (n++));
                } catch (Throwable ignored) {
                }
            }
            try {
                engine.speak(part, first ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD,
                        null, "dsh_" + (n++));
            } catch (Throwable ignored) {
            }
            first = false;
            prev = part;
        }
    }

    private static List<String> split(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            sb.append(ch);
            boolean end = "。！？!?；;\n".indexOf(ch) >= 0 || ch == '…';
            boolean comma = "，,、".indexOf(ch) >= 0;
            if (end || (comma && sb.length() >= 22) || sb.length() >= 48) {
                out.add(sb.toString());
                sb.setLength(0);
            }
        }
        if (sb.length() > 0) out.add(sb.toString());
        return out;
    }

    private static int pauseOf(String s) {
        if (s == null || s.isEmpty()) return 80;
        char c = s.charAt(s.length() - 1);
        if (c == '。' || c == '！' || c == '？' || c == '!' || c == '?') return 260;
        if (c == '；' || c == ';') return 200;
        if (c == '…') return 330;
        if (c == '，' || c == ',' || c == '、') return 110;
        return 120;
    }

    /** 清掉表情、Markdown 和动作括号，避免 TTS 把符号念出来。 */
    private static String clean(String s, boolean stripActions) {
        if (s == null) return "";
        s = s.replaceAll("```[\\s\\S]*?```", " ");
        s = s.replaceAll("\\*\\*|__|~~|`", "");
        s = s.replaceAll("https?://\\S+", " ");
        if (stripActions) {
            Matcher m = PAREN.matcher(s);
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                String inner = m.group(1);
                if (inner.matches(".*\\d.*")) m.appendReplacement(sb, Matcher.quoteReplacement(m.group()));
                else m.appendReplacement(sb, "，");
            }
            m.appendTail(sb);
            s = sb.toString();
        }
        s = s.replaceAll("[\\uD800-\\uDBFF][\\uDC00-\\uDFFF]", "");
        s = s.replaceAll("[\\u2600-\\u27BF\\uFE0F\\u2B00-\\u2BFF\\u0600-\\u06FF]", " ");
        s = s.replaceAll("[\\u02B0-\\u02FF\\u1D00-\\u1DFF]", "");
        s = s.replace('～', '，').replace('~', '，');
        s = s.replaceAll("\\s+", " ").trim();
        return s;
    }

    public static void shutdown() {
        EdgeTts.stop();
        OnlineTts.stop();
        try {
            if (tts != null) tts.shutdown();
        } catch (Throwable ignored) {
        }
        tts = null;
        ready = false;
        initFailed = false;
        initAt = 0;
        stopAsr();
    }

    public static JSONArray voices(Context c) {
        JSONArray out = new JSONArray();
        try {
            // Listing is a UI/settings read. Do not initialize TextToSpeech here:
            // some vendor engines block during bind/init and can freeze callers.
            if (!ready || tts == null) return out;
            Set<android.speech.tts.Voice> vs = tts.getVoices();
            if (vs == null) return out;
            ArrayList<JSONObject> female = new ArrayList<>();
            ArrayList<JSONObject> all = new ArrayList<>();
            for (android.speech.tts.Voice v : vs) {
                Locale l = v.getLocale();
                String name = v.getName() == null ? "" : v.getName();
                JSONObject o = new JSONObject();
                o.put("id", name + "|" + (l == null ? "" : l.toString()));
                o.put("name", name);
                o.put("locale", l == null ? "" : l.toString());
                o.put("quality", v.getQuality());
                o.put("network", v.isNetworkConnectionRequired());
                o.put("lang", l == null ? "" : l.getLanguage());
                all.add(o);
                if (!isMaleVoice(name)) female.add(o);
            }
            // 过滤男声；如果引擎把音色都标成中性，则退回完整列表避免空下拉
            ArrayList<JSONObject> list = female.isEmpty() ? all : female;
            for (JSONObject o : list) out.put(o);
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static boolean isMaleVoice(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (n.contains("female") || n.contains("女") || n.contains("girl")) return false;
        return n.contains("男") || n.contains("male");
    }

    /** 手机上所有可用的系统 TTS 引擎，供设置里切换（换引擎通常能多出不少中文音色）。 */
    public static JSONArray engines(Context c) {
        JSONArray out = new JSONArray();
        try {
            PackageManager pm = c.getPackageManager();
            Intent it = new Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE);
            List<ResolveInfo> list = pm.queryIntentServices(it, 0);
            if (list == null) return out;
            String def = "";
            try {
                def = android.provider.Settings.Secure.getString(
                        c.getContentResolver(), "tts_default_synth");
            } catch (Throwable ignored) {
            }
            if (def == null) def = "";
            java.util.TreeMap<String, String> map = new java.util.TreeMap<>();
            for (ResolveInfo ri : list) {
                if (ri == null || ri.serviceInfo == null) continue;
                String pkg = ri.serviceInfo.packageName;
                String label = pkg;
                try {
                    if (ri.serviceInfo.applicationInfo != null) {
                        label = String.valueOf(ri.serviceInfo.applicationInfo.loadLabel(pm));
                    }
                } catch (Throwable ignored) {
                }
                map.put(pkg, label);
            }
            for (java.util.Map.Entry<String, String> e : map.entrySet()) {
                JSONObject o = new JSONObject();
                o.put("pkg", e.getKey());
                o.put("name", e.getValue());
                o.put("default", e.getKey().equals(def));
                out.put(o);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    public static JSONObject state(Context c) {
        Cfg cfg = Cfg.load(c);
        JSONObject o = new JSONObject();
        try {
            o.put("enabled", cfg.b("ttsEnabled", false));
            o.put("onlyImportant", cfg.b("ttsOnlyImportant", true));
            o.put("rate", cfg.d("ttsRate", 1.0));
            o.put("pitch", cfg.d("ttsPitch", 1.0));
            o.put("natural", cfg.b("ttsNatural", true));
            o.put("voice", cfg.str("ttsVoice", ""));
            o.put("engine", cfg.str("ttsEngine", "edge"));
            o.put("edgeVoice", cfg.str("edgeVoice", EdgeTts.DEFAULT_VOICE));
            o.put("sysEngine", cfg.str("ttsSysEngine", ""));
            o.put("sysEngines", engines(c));
            o.put("onlineUrl", cfg.str("ttsOnlineUrl", ""));
            o.put("onlineKey", cfg.str("ttsOnlineKey", "").isEmpty() ? "" : "已填写");
            o.put("onlineModel", cfg.str("ttsOnlineModel", "tts-1"));
            o.put("onlineVoice", cfg.str("ttsOnlineVoice", "alloy"));
            JSONArray ev = new JSONArray();
            for (String[] row : EdgeTts.VOICES) {
                JSONObject v = new JSONObject();
                v.put("id", row[0]);
                v.put("name", row[1]);
                ev.put(v);
            }
            o.put("edgeVoices", ev);
            o.put("voiceError", voiceError);
            o.put("ready", ready);
            o.put("asr", SpeechRecognizer.isRecognitionAvailable(c));
            o.put("voices", voices(c));
        } catch (Throwable ignored) {
        }
        return o;
    }

    public static void save(Context c, JSONObject o) {
        if (o == null) return;
        Cfg cfg = Cfg.load(c);
        String oldEngine = cfg.str("ttsSysEngine", "");
        String[] keys = {"ttsEnabled", "ttsOnlyImportant", "ttsRate", "ttsPitch", "ttsNatural", "ttsVoice",
                "ttsEngine", "edgeVoice", "asrAutoSend",
                "ttsSysEngine", "ttsOnlineUrl", "ttsOnlineKey", "ttsOnlineModel", "ttsOnlineVoice"};
        for (String k : keys) if (o.has(k)) cfg.set(k, o.opt(k));
        cfg.save(c);
        // 换了系统引擎要重建实例，否则音色列表还是旧引擎的
        if (o.has("ttsSysEngine") && !oldEngine.equals(o.optString("ttsSysEngine", ""))) {
            setSystemEngine(c, o.optString("ttsSysEngine", ""));
        }
        apply(c);
    }

    /* ---------------- ASR ---------------- */
    public static boolean canListen(Context c) {
        return SpeechRecognizer.isRecognitionAvailable(c)
                && c.checkSelfPermission("android.permission.RECORD_AUDIO") == 0;
    }

    public static void start(final Context c, final Cb cb) {
        if (c.checkSelfPermission("android.permission.RECORD_AUDIO") != 0) {
            cb.error("需要麦克风权限");
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(c)) {
            cb.error("系统没有可用的语音识别服务");
            return;
        }
        H.post(() -> {
            try {
                // 每次重新创建，避免上一次识别错误/取消后识别器卡在 busy 状态
                if (sr != null) {
                    try {
                        sr.cancel();
                    } catch (Throwable ignored) {
                    }
                    try {
                        sr.destroy();
                    } catch (Throwable ignored) {
                    }
                    sr = null;
                }
                sr = SpeechRecognizer.createSpeechRecognizer(c.getApplicationContext());
                asrCb = cb;
                asrPartial = "";
                listening = true;
                sr.setRecognitionListener(new RecognitionListener() {
                    @Override
                    public void onReadyForSpeech(Bundle params) {
                    }

                    @Override
                    public void onBeginningOfSpeech() {
                    }

                    @Override
                    public void onRmsChanged(float rmsdB) {
                    }

                    @Override
                    public void onBufferReceived(byte[] buffer) {
                    }

                    @Override
                    public void onEndOfSpeech() {
                    }

                    @Override
                    public void onError(int error) {
                        listening = false;
                        Cb callback = asrCb;
                        asrCb = null;
                        String partial = asrPartial;
                        asrPartial = "";
                        if (callback != null) {
                            // Some Android engines report NO_MATCH/TIMEOUT after
                            // already returning useful partial words. Keep them.
                            if (!partial.isEmpty()) callback.text(partial, true);
                            else callback.error(asrError(error));
                        }
                    }

                    @Override
                    public void onResults(Bundle results) {
                        listening = false;
                        String t = first(results);
                        if (t.isEmpty()) t = asrPartial;
                        asrPartial = "";
                        Cb callback = asrCb;
                        asrCb = null;
                        if (callback != null) {
                            if (t.isEmpty()) callback.error("语音服务没有识别到内容，请检查手机语音识别服务后再试");
                            else callback.text(t, true);
                        }
                    }

                    @Override
                    public void onPartialResults(Bundle partialResults) {
                        String t = first(partialResults);
                        if (!t.isEmpty() && asrCb != null) {
                            asrPartial = t;
                            asrCb.text(t, false);
                        }
                    }

                    @Override
                    public void onEvent(int eventType, Bundle params) {
                    }
                });
                Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN");
                i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
                i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
                i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, c.getPackageName());
                sr.startListening(i);
            } catch (Throwable t) {
                listening = false;
                asrCb = null;
                asrPartial = "";
                cb.error("语音识别启动失败");
            }
        });
    }

    public static void stopAsr() {
        H.post(() -> {
            try {
                if (sr != null) {
                    sr.stopListening();
                    sr.cancel();
                    sr.destroy();
                }
            } catch (Throwable ignored) {
            }
            sr = null;
            asrCb = null;
            asrPartial = "";
            listening = false;
        });
    }

    /** 松开按住说话按钮：让识别器收尾并回传最终转写，不立即 cancel 掉结果。 */
    public static void finishAsr() {
        H.post(() -> {
            try {
                if (sr != null && listening) sr.stopListening();
            } catch (Throwable ignored) {
            }
        });
    }

    public static boolean listening() {
        return listening;
    }

    private static String first(Bundle b) {
        if (b == null) return "";
        ArrayList<String> list = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (list == null || list.isEmpty()) return "";
        String s = list.get(0);
        return s == null ? "" : s.trim();
    }

    private static String asrError(int code) {
        switch (code) {
            case SpeechRecognizer.ERROR_AUDIO:
                return "录音出错";
            case SpeechRecognizer.ERROR_CLIENT:
                return "识别被中断";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                return "缺少麦克风权限";
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                return "语音服务网络异常";
            case SpeechRecognizer.ERROR_NO_MATCH:
                return "这次没有识别到清楚的语音，请靠近麦克风再试一次";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                return "识别器忙，稍后再试";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                return "没有检测到语音，请确认麦克风没有被其他应用占用";
            default:
                return "语音识别失败（" + code + "）";
        }
    }
}
