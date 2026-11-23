package com.dshpet.android;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Random;
import java.util.TimeZone;
import java.util.UUID;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * 微软 Edge「大声朗读」在线语音。
 * 免 Key、中文自然、按句合成；失败时由 Voice 回退到系统 TTS。
 * 只用 JDK 自带的 TLS + 手写的最小 WebSocket，不引入第三方库。
 */
public final class EdgeTts {
    private static final String HOST = "speech.platform.bing.com";
    private static final String PATH = "/consumer/speech/synthesize/readaloud/edge/v1";
    private static final String ORIGIN = "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold";

    public static final String DEFAULT_VOICE = "zh-CN-XiaoxiaoNeural";

    /**
     * Edge 免费接口实际提供的中文音色（2026-10 实测共 14 个：8 女 6 男），
     * 顺序上女声在前（默认人设），男声放在后面供用户自选。
     */
    public static final String[][] VOICES = {
            {"zh-CN-XiaoxiaoNeural", "晓晓 · 女声 自然温柔"},
            {"zh-CN-XiaoyiNeural", "晓伊 · 女声 年轻元气"},
            {"zh-CN-liaoning-XiaobeiNeural", "晓北 · 女声 东北口音"},
            {"zh-CN-shaanxi-XiaoniNeural", "晓妮 · 女声 陕西口音"},
            {"zh-TW-HsiaoChenNeural", "曉臻 · 女声 台湾腔"},
            {"zh-TW-HsiaoYuNeural", "曉雨 · 女声 台湾腔轻柔"},
            {"zh-HK-HiuGaaiNeural", "曉佳 · 女声 粤语"},
            {"zh-HK-HiuMaanNeural", "曉曼 · 女声 粤语温柔"},
            {"zh-CN-YunxiNeural", "云希 · 男声 温暖"},
            {"zh-CN-YunyangNeural", "云扬 · 男声 播音"},
            {"zh-CN-YunjianNeural", "云健 · 男声 沉稳"},
            {"zh-CN-YunxiaNeural", "云夏 · 男声 少年"},
            {"zh-TW-YunJheNeural", "雲哲 · 男声 台湾腔"},
            {"zh-HK-WanLungNeural", "雲龍 · 男声 粤语"},
    };

    public interface Cb {
        void ok();

        void fail(String err);
    }

    private static final Handler H = new Handler(Looper.getMainLooper());
    private static final Random RND = new Random();
    private static volatile Socket sock;
    private static volatile Thread worker;
    private static volatile int gen;
    private static MediaPlayer player;
    private static AudioManager am;
    private static AudioFocusRequest focus;

    private EdgeTts() {
    }

    public static boolean knownVoice(String v) {
        if (v == null) return false;
        for (String[] row : VOICES) if (row[0].equals(v)) return true;
        return false;
    }

    public static void speak(Context c, String text, double rate, double pitch, String voice, Cb cb) {
        stop();
        if (text == null || text.trim().isEmpty()) {
            if (cb != null) cb.fail("没有要念的内容");
            return;
        }
        final Context app = c.getApplicationContext();
        final String t = text.trim();
        final String v = knownVoice(voice) ? voice : DEFAULT_VOICE;
        final int my = ++gen;
        Thread th = new Thread(() -> {
            try {
                File f = synth(app, t, rate, pitch, v, my);
                if (my != gen || f == null) return;
                H.post(() -> play(app, f, cb));
            } catch (Throwable e) {
                if (my != gen) return;
                H.post(() -> {
                    if (my == gen && cb != null) cb.fail(e.getMessage() == null ? "在线语音失败" : e.getMessage());
                });
            }
        }, "dsh-edge-tts");
        worker = th;
        th.start();
    }

    public static void stop() {
        gen++;
        try {
            Socket s = sock;
            if (s != null) s.close();
        } catch (Throwable ignored) {
        }
        sock = null;
        Thread t = worker;
        worker = null;
        if (t != null) t.interrupt();
        H.post(EdgeTts::stopPlayer);
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
        // 朗读结束就还回音频焦点，别一直压着用户的音乐
        try {
            if (focus != null && am != null) am.abandonAudioFocusRequest(focus);
        } catch (Throwable ignored) {
        }
        focus = null;
    }

    private static void play(Context c, File f, Cb cb) {
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
            mp.setOnCompletionListener(x -> stopPlayer());
            mp.prepare();
            mp.start();
            player = mp;
            if (cb != null) cb.ok();
        } catch (Throwable t) {
            stopPlayer();
            if (cb != null) cb.fail("在线语音播放失败");
        }
    }

    /* ---------------- 合成 ---------------- */

    private static File synth(Context c, String text, double rate, double pitch, String voice, int my)
            throws Exception {
        File out = new File(c.getCacheDir(), "edge_tts.mp3");
        SSLSocket ssl = null;
        try {
            Socket raw = new Socket();
            raw.connect(new InetSocketAddress(HOST, 443), 9000);
            ssl = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                    .createSocket(raw, HOST, 443, true);
            ssl.setSoTimeout(12000);
            ssl.startHandshake();
            sock = ssl;
            InputStream in = new BufferedInputStream(ssl.getInputStream());
            OutputStream os = ssl.getOutputStream();

            String key = base64(randomBytes(16));
            String req = "GET " + PATH + "?TrustedClientToken=" + Drm.CLIENT_TOKEN
                    + "&ConnectionId=" + UUID.randomUUID().toString().replace("-", "")
                    + "&Sec-MS-GEC=" + Drm.gec()
                    + "&Sec-MS-GEC-Version=" + Drm.GEC_VERSION
                    + " HTTP/1.1\r\n"
                    + "Host: " + HOST + "\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: " + key + "\r\n"
                    + "Sec-WebSocket-Version: 13\r\n"
                    + "Origin: " + ORIGIN + "\r\n"
                    + "User-Agent: " + Drm.UA + "\r\n"
                    + "Cookie: muid=" + Drm.muid() + ";\r\n"
                    + "Pragma: no-cache\r\n"
                    + "Cache-Control: no-cache\r\n"
                    + "Accept-Language: zh-CN,zh;q=0.9,en;q=0.8\r\n\r\n";
            os.write(req.getBytes(StandardCharsets.UTF_8));
            os.flush();

            String status = readLine(in);
            if (status == null || !status.contains("101")) {
                throw new Exception("在线语音鉴权失败" + (status == null ? "" : "：" + status.trim()));
            }
            while (true) {
                String h = readLine(in);
                if (h == null || h.isEmpty()) break;
            }
            if (my != gen) return null;

            String ts = stamp();
            String config = "X-Timestamp:" + ts + "\r\n"
                    + "Content-Type:application/json; charset=utf-8\r\n"
                    + "Path:speech.config\r\n\r\n"
                    + "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{"
                    + "\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"},"
                    + "\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}";
            sendText(os, config);
            String rid = UUID.randomUUID().toString().replace("-", "");
            String ssml = "X-RequestId:" + rid + "\r\n"
                    + "Content-Type:application/ssml+xml\r\n"
                    + "X-Timestamp:" + ts + "\r\n"
                    + "Path:ssml\r\n\r\n"
                    + ssml(text, rate, pitch, voice);
            sendText(os, ssml);

            ByteArrayOutputStream audio = new ByteArrayOutputStream();
            long deadline = System.currentTimeMillis() + 15000L;
            boolean ended = false;
            while (System.currentTimeMillis() < deadline) {
                if (my != gen) return null;
                Frame fr = readFrame(in);
                if (fr == null) break;
                if (fr.op == 0x8) break;
                if (fr.op == 0x9) { // ping
                    sendFrame(os, 0xA, fr.data);
                    continue;
                }
                if (fr.op == 0x2) { // 二进制：前两字节是头长度
                    if (fr.data.length < 2) continue;
                    int hl = ((fr.data[0] & 0xFF) << 8) | (fr.data[1] & 0xFF);
                    if (hl + 2 > fr.data.length) continue;
                    String head = new String(fr.data, 2, hl, StandardCharsets.UTF_8);
                    if (head.contains("Path:audio")) {
                        audio.write(fr.data, 2 + hl, fr.data.length - 2 - hl);
                    }
                    continue;
                }
                if (fr.op == 0x1) {
                    String s = new String(fr.data, StandardCharsets.UTF_8);
                    if (s.contains("Path:turn.end")) {
                        ended = true;
                        break;
                    }
                }
            }
            if (audio.size() < 1200) {
                throw new Exception(ended ? "在线语音没有返回音频" : "在线语音超时");
            }
            // 期间又发起了新的朗读：别把旧音频写进同一个文件
            if (my != gen) return null;
            try (FileOutputStream fo = new FileOutputStream(out)) {
                audio.writeTo(fo);
            }
            return out;
        } finally {
            try {
                if (ssl != null) ssl.close();
            } catch (Throwable ignored) {
            }
            sock = null;
        }
    }

    private static String ssml(String text, double rate, double pitch, String voice) {
        int r = (int) Math.round((Math.max(0.5, Math.min(2.0, rate)) - 1) * 100);
        int p = (int) Math.round((Math.max(0.5, Math.min(2.0, pitch)) - 1) * 100);
        String rs = (r >= 0 ? "+" : "") + r + "%";
        String ps = (p >= 0 ? "+" : "") + p + "%";
        return "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='zh-CN'>"
                + "<voice name='" + voice + "'><prosody pitch='" + ps + "' rate='" + rs
                + "' volume='+0%'>" + xml(text) + "</prosody></voice></speak>";
    }

    private static String xml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    private static String stamp() {
        SimpleDateFormat f = new SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'",
                Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date());
    }

    private static String base64(byte[] b) {
        return android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP);
    }

    private static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        RND.nextBytes(b);
        return b;
    }

    /* ---------------- 最小 WebSocket ---------------- */

    private static final class Frame {
        int op;
        byte[] data;
    }

    private static void sendText(OutputStream os, String s) throws Exception {
        sendFrame(os, 0x1, s.getBytes(StandardCharsets.UTF_8));
    }

    /** 客户端帧必须加掩码。 */
    private static synchronized void sendFrame(OutputStream os, int op, byte[] data) throws Exception {
        int len = data.length;
        ByteArrayOutputStream fr = new ByteArrayOutputStream(len + 14);
        fr.write(0x80 | op);
        if (len <= 125) {
            fr.write(0x80 | len);
        } else if (len <= 65535) {
            fr.write(0x80 | 126);
            fr.write((len >> 8) & 0xFF);
            fr.write(len & 0xFF);
        } else {
            fr.write(0x80 | 127);
            for (int i = 7; i >= 0; i--) fr.write((int) (((long) len) >> (8 * i)) & 0xFF);
        }
        byte[] mask = randomBytes(4);
        fr.write(mask);
        for (int i = 0; i < len; i++) fr.write(data[i] ^ mask[i & 3]);
        os.write(fr.toByteArray());
        os.flush();
    }

    private static Frame readFrame(InputStream in) throws Exception {
        int b0 = in.read();
        if (b0 < 0) return null;
        int b1 = readByte(in);
        Frame f = new Frame();
        f.op = b0 & 0x0F;
        boolean masked = (b1 & 0x80) != 0;
        long len = b1 & 0x7F;
        if (len == 126) {
            len = (readByte(in) << 8) | readByte(in);
        } else if (len == 127) {
            len = 0;
            for (int i = 0; i < 8; i++) len = (len << 8) | readByte(in);
        }
        byte[] mask = new byte[4];
        if (masked) readFully(in, mask);
        if (len < 0 || len > 8000000L) throw new Exception("在线语音数据异常");
        f.data = new byte[(int) len];
        readFully(in, f.data);
        if (masked) for (int i = 0; i < f.data.length; i++) f.data[i] ^= mask[i & 3];
        return f;
    }

    private static int readByte(InputStream in) throws Exception {
        int v = in.read();
        if (v < 0) throw new Exception("在线语音连接中断");
        return v;
    }

    private static void readFully(InputStream in, byte[] b) throws Exception {
        int off = 0;
        while (off < b.length) {
            int n = in.read(b, off, b.length - off);
            if (n < 0) throw new Exception("在线语音连接中断");
            off += n;
        }
    }

    private static String readLine(InputStream in) throws Exception {
        ByteArrayOutputStream sb = new ByteArrayOutputStream();
        while (true) {
            int c = in.read();
            if (c < 0) return sb.size() == 0 ? null : sb.toString("UTF-8");
            if (c == '\n') {
                String s = sb.toString("UTF-8");
                return s.endsWith("\r") ? s.substring(0, s.length() - 1) : s;
            }
            sb.write(c);
        }
    }
}
