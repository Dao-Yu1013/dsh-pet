package com.dshpet.android;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

import org.json.JSONObject;

import java.util.Random;

/** 音乐增强：白噪音、歌词翻译、切歌。 */
public final class MusicX {
    private static AudioTrack track;
    private static String noiseKind = "";
    private static String lastLogged = "";

    private MusicX() {
    }

    /* ---------------- 白噪音 ---------------- */
    public static synchronized boolean startNoise(String kind) {
        stopNoise();
        if (kind == null || kind.isEmpty()) kind = "brown";
        int rate = 22050;
        int seconds = 4;
        int frames = rate * seconds;
        short[] buf = new short[frames];
        Random r = new Random();
        double lastOut = 0;
        double b0 = 0, b1 = 0, b2 = 0, b3 = 0, b4 = 0, b5 = 0, b6 = 0;
        for (int i = 0; i < frames; i++) {
            double white = r.nextDouble() * 2 - 1;
            double v;
            if ("white".equals(kind)) {
                v = white * 0.28;
            } else if ("pink".equals(kind)) {
                b0 = 0.99886 * b0 + white * 0.0555179;
                b1 = 0.99332 * b1 + white * 0.0750759;
                b2 = 0.96900 * b2 + white * 0.1538520;
                b3 = 0.86650 * b3 + white * 0.3104856;
                b4 = 0.55000 * b4 + white * 0.5329522;
                b5 = -0.7616 * b5 - white * 0.0168980;
                v = (b0 + b1 + b2 + b3 + b4 + b5 + b6 + white * 0.5362) * 0.09;
                b6 = white * 0.115926;
            } else {
                lastOut = (lastOut + 0.02 * white) / 1.02;
                v = lastOut * 2.2;
            }
            int s = (int) Math.max(-32767, Math.min(32767, v * 32767));
            buf[i] = (short) s;
        }
        try {
            int min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(Math.max(min, frames * 2))
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build();
            track.write(buf, 0, frames);
            track.setLoopPoints(0, frames, -1);
            track.setVolume(0.5f);
            track.play();
            noiseKind = kind;
            return true;
        } catch (Throwable t) {
            stopNoise();
            return false;
        }
    }

    public static synchronized void stopNoise() {
        noiseKind = "";
        if (track == null) return;
        try {
            track.stop();
            track.release();
        } catch (Throwable ignored) {
        }
        track = null;
    }

    public static boolean noiseOn() {
        return track != null;
    }

    public static String noiseKind() {
        return noiseKind;
    }

    /* ---------------- 播放记录 ---------------- */
    public static void note(Context c, String title, String artist, boolean playing) {
        if (!playing || title == null || title.trim().isEmpty()) return;
        String key = title + "|" + artist;
        if (key.equals(lastLogged)) return;
        lastLogged = key;
        try {
            JSONObject o = new JSONObject();
            o.put("ts", System.currentTimeMillis());
            o.put("title", title);
            o.put("artist", artist == null ? "" : artist);
            Sg.appendLine(c, "music_log.jsonl", o);
        } catch (Throwable ignored) {
        }
    }

    /* ---------------- 切歌 ---------------- */
    public static boolean next(Context c) {
        return Music.transport(true, c);
    }

    public static boolean prev(Context c) {
        return Music.transport(false, c);
    }

    public static boolean next() {
        return Music.transport(true);
    }

    public static boolean prev() {
        return Music.transport(false);
    }

    /* ---------------- 歌词翻译 ---------------- */
    public static void translateCurrent(Context c, Ai.Cb cb) {
        String line = Music.currentLineText();
        if (line == null || line.trim().isEmpty()) {
            cb.fail("当前没有歌词");
            return;
        }
        translate(c, line, cb);
    }

    public static void translate(Context c, String line, Ai.Cb cb) {
        JSONObject cache = Sg.readObj(c, "lyric_tr.json");
        String hit = cache.optString(line, "");
        if (!hit.isEmpty()) {
            cb.ok(hit);
            return;
        }
        String sys = "把这句歌词翻译成自然中文（若已是中文则翻译成英文）。只输出译文，不要解释，不超过 40 字。";
        org.json.JSONArray msgs = new org.json.JSONArray();
        try {
            JSONObject s = new JSONObject();
            s.put("role", "system");
            s.put("content", sys);
            msgs.put(s);
            JSONObject u = new JSONObject();
            u.put("role", "user");
            u.put("content", line);
            msgs.put(u);
        } catch (Throwable ignored) {
        }
        Ai.rawChat(c, msgs, 120, new Ai.Cb() {
            @Override
            public void ok(String text) {
                String t = Brain.clean(text, 80);
                try {
                    if (cache.length() > 300) {
                        JSONObject nc = new JSONObject();
                        nc.put(line, t);
                        Sg.writeObj(c, "lyric_tr.json", nc);
                    } else {
                        cache.put(line, t);
                        Sg.writeObj(c, "lyric_tr.json", cache);
                    }
                } catch (Throwable ignored) {
                }
                cb.ok(t);
            }

            @Override
            public void fail(String err) {
                cb.fail(err);
            }
        });
    }

    public static JSONObject state(Context c) {
        Cfg cfg = Cfg.load(c);
        JSONObject o = new JSONObject();
        try {
            o.put("noise", noiseOn());
            o.put("noiseKind", noiseKind());
            o.put("gesture", cfg.b("musicGesture", true));
            o.put("whiteNoise", cfg.b("whiteNoise", true));
            o.put("title", Music.title());
            o.put("artist", Music.artist());
            o.put("playing", Music.playing());
            o.put("lyric", Music.currentLineText());
            o.put("line", Music.lineIndex());
            o.put("lines", Music.lines() == null ? 0 : Music.lines().size());
            o.put("app", Music.sourceApp());
            o.put("lyricSource", Music.lyricSource());
            o.put("lyricStatus", lyricStatus(c));
            o.put("songInfo", SongAi.cached(c, Music.title(), Music.artist()));
        } catch (Throwable ignored) {
        }
        return o;
    }

    /** 给界面看的歌词状态，没歌词时能直接看出该去哪里开「通知栏歌词」。 */
    public static String lyricStatus(Context c) {
        if (Music.title().isEmpty()) return "未检测到播放器";
        String src = Music.lyricSource();
        if ("notify".equals(src)) return "通知栏歌词";
        if ("library".equals(src)) return "曲库歌词";
        if (!Music.playing()) return "没有在播放";
        if (!Perm.notifyListener(c)) return "没取到歌词：缺通知使用权，或在音乐 App 里开「通知栏歌词」";
        return "没取到歌词：请在音乐 App 里打开「通知栏歌词」";
    }
}
