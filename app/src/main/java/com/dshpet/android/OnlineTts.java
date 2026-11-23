package com.dshpet.android;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 自定义在线语音：调用 OpenAI 兼容的 /audio/speech。
 * 音色由用户在设置里填，接口地址/Key/模型都可换，失败时由 Voice 回退系统 TTS。
 */
public final class OnlineTts {
    public interface Cb {
        void ok();

        void fail(String err);
    }

    private static final Handler H = new Handler(Looper.getMainLooper());
    private static volatile int gen;
    private static volatile Thread worker;
    private static MediaPlayer player;
    private static AudioManager am;
    private static AudioFocusRequest focus;

    private OnlineTts() {
    }

    public static void speak(Context c, String text, double rate, Cb cb) {
        stop();
        if (text == null || text.trim().isEmpty()) {
            if (cb != null) cb.fail("没有要念的内容");
            return;
        }
        final Context app = c.getApplicationContext();
        final String t = text.trim();
        final int my = ++gen;
        Thread th = new Thread(() -> {
            try {
                File f = synth(app, t, rate, my);
                if (my != gen || f == null) return;
                H.post(() -> play(app, f, cb));
            } catch (Throwable e) {
                if (my != gen) return;
                H.post(() -> {
                    if (my == gen && cb != null) cb.fail(e.getMessage() == null ? "在线语音失败" : e.getMessage());
                });
            }
        }, "dsh-online-tts");
        worker = th;
        th.start();
    }

    public static void stop() {
        gen++;
        Thread t = worker;
        if (t != null) t.interrupt();
        worker = null;
        try {
            if (player != null) {
                player.stop();
                player.release();
            }
        } catch (Throwable ignored) {
        }
        player = null;
        try {
            if (focus != null && am != null) am.abandonAudioFocusRequest(focus);
        } catch (Throwable ignored) {
        }
        focus = null;
    }

    private static File synth(Context c, String text, double rate, int my) throws Exception {
        Cfg cfg = Cfg.load(c);
        String base = cfg.str("ttsOnlineUrl", "").trim();
        if (base.isEmpty()) throw new Exception("没填在线语音接口地址");
        String key = cfg.str("ttsOnlineKey", "").trim();
        String model = cfg.str("ttsOnlineModel", "tts-1").trim();
        String voice = cfg.str("ttsOnlineVoice", "alloy").trim();
        if (model.isEmpty()) model = "tts-1";
        if (voice.isEmpty()) voice = "alloy";
        String url = base.endsWith("/audio/speech") ? base
                : (base.endsWith("/") ? base : base + "/") + "audio/speech";

        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("input", text);
        body.put("voice", voice);
        body.put("response_format", "mp3");
        body.put("speed", Math.max(0.25, Math.min(4.0, rate <= 0 ? 1.0 : rate)));

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(45000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "audio/mpeg, application/octet-stream");
            if (!key.isEmpty()) conn.setRequestProperty("Authorization", "Bearer " + key);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            if (code != 200) {
                String err = readText(conn.getErrorStream(), 300);
                throw new Exception("在线语音 " + code + (err.isEmpty() ? "" : "：" + err));
            }
            InputStream in = new BufferedInputStream(conn.getInputStream());
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) {
                if (my != gen) return null;
                buf.write(b, 0, n);
                if (buf.size() > 12000000) throw new Exception("在线语音数据过大");
            }
            in.close();
            if (buf.size() < 800) throw new Exception("在线语音没有返回音频");
            File out = new File(c.getCacheDir(), "online_tts.mp3");
            try (FileOutputStream fo = new FileOutputStream(out)) {
                buf.writeTo(fo);
            }
            return out;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String readText(InputStream in, int max) {
        if (in == null) return "";
        try {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] b = new byte[512];
            int n;
            while ((n = in.read(b)) > 0 && o.size() < max) o.write(b, 0, Math.min(n, max - o.size()));
            in.close();
            return o.toString("UTF-8").replaceAll("\\s+", " ").trim();
        } catch (Throwable t) {
            return "";
        }
    }

    private static void play(Context c, final File f, Cb cb) {
        try {
            stopPlayer();
            // 短暂压低音乐音量（不暂停），避免朗读被音乐盖住
            try {
                am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
                focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build())
                        .setOnAudioFocusChangeListener(x -> {
                        }, H)
                        .build();
                am.requestAudioFocus(focus);
            } catch (Throwable ignored) {
            }
            MediaPlayer mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            mp.setDataSource(f.getAbsolutePath());
            mp.setOnCompletionListener(x -> {
                stopPlayer();
                f.delete();
            });
            mp.prepare();
            mp.start();
            player = mp;
            if (cb != null) cb.ok();
        } catch (Throwable t) {
            stopPlayer();
            if (cb != null) cb.fail("在线语音播放失败");
        }
    }

    private static void stopPlayer() {
        try {
            if (player != null) {
                player.stop();
                player.release();
            }
        } catch (Throwable ignored) {
        }
        player = null;
        try {
            if (focus != null && am != null) am.abandonAudioFocusRequest(focus);
        } catch (Throwable ignored) {
        }
        focus = null;
    }
}
