package com.dshpet.android;

import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.media.audiofx.Visualizer;

import org.json.JSONArray;

import java.util.List;

/**
 * 读取当前音乐的真实频谱（Android Visualizer），供首页音浪卡片使用。
 * 需要录音权限；拿不到权限或初始化失败时，上层会自动降级为跟随播放进度的动效。
 */
public final class Wave {
    private static final int BANDS = 7;

    private static Visualizer vis;
    private static int session = -1;
    private static volatile byte[] fft;
    private static volatile long fftAt;
    private static volatile float peak = 1f;

    private Wave() {
    }

    public static boolean hasPermission(Context c) {
        try {
            return c.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 找到当前音乐应用的音频会话 id（getAudioSessionId 是系统接口，用反射取）。 */
    public static int sessionId(Context c, String pkg) {
        try {
            AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return -1;
            List<AudioPlaybackConfiguration> list = am.getActivePlaybackConfigurations();
            int fallback = -1;
            for (AudioPlaybackConfiguration cfg : list) {
                if (cfg == null) continue;
                AudioAttributes at = cfg.getAudioAttributes();
                if (at == null || at.getUsage() != AudioAttributes.USAGE_MEDIA) continue;
                int sid = reflectInt(cfg, "getAudioSessionId");
                if (sid <= 0) continue;
                int uid = reflectInt(cfg, "getClientUid");
                if (uid > 0 && pkg != null && !pkg.isEmpty()) {
                    String[] pkgs = c.getPackageManager().getPackagesForUid(uid);
                    if (pkgs != null) {
                        for (String p : pkgs) {
                            if (p.equals(pkg)) return sid;
                        }
                    }
                }
                if (fallback < 0) fallback = sid;
            }
            return fallback;
        } catch (Throwable t) {
            return -1;
        }
    }

    private static int reflectInt(Object o, String name) {
        try {
            java.lang.reflect.Method m = o.getClass().getMethod(name);
            Object v = m.invoke(o);
            return v instanceof Integer ? (Integer) v : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    public static synchronized void ensure(Context c, int sessionId) {
        if (sessionId <= 0) return;
        if (session == sessionId && vis != null) return;
        if (!hasPermission(c)) return;
        stop();
        try {
            Visualizer v = new Visualizer(sessionId);
            int size = Visualizer.getCaptureSizeRange()[1];
            if (v.setCaptureSize(size) != Visualizer.SUCCESS) {
                v.release();
                return;
            }
            v.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                @Override
                public void onWaveFormDataCapture(Visualizer visualizer, byte[] waveform, int rate) {
                }

                @Override
                public void onFftDataCapture(Visualizer visualizer, byte[] data, int rate) {
                    if (data == null || data.length < 8) return;
                    fft = data;
                    fftAt = System.currentTimeMillis();
                }
            }, Visualizer.getMaxCaptureRate() / 2, false, true);
            if (v.setEnabled(true) != Visualizer.SUCCESS) {
                v.release();
                return;
            }
            vis = v;
            session = sessionId;
        } catch (Throwable t) {
            stop();
        }
    }

    public static synchronized void stop() {
        if (vis != null) {
            try {
                vis.setEnabled(false);
                vis.release();
            } catch (Throwable ignored) {
            }
        }
        vis = null;
        session = -1;
        fft = null;
    }

    /** 频谱是否新鲜可用。 */
    public static boolean fresh() {
        byte[] f = fft;
        return vis != null && f != null && System.currentTimeMillis() - fftAt < 1500L;
    }

    /** 归一化后的各频段能量（0-100），拿不到数据时返回空数组。 */
    public static JSONArray levels() {
        JSONArray out = new JSONArray();
        byte[] f = fft;
        if (f == null || f.length < 8) return out;
        int bins = f.length / 2;
        float[] mag = new float[BANDS];
        int[] count = new int[BANDS];
        float max = 1f;
        for (int i = 1; i < bins; i++) {
            int re = f[i * 2];
            int im = f[i * 2 + 1];
            float m = (float) Math.sqrt((double) re * re + (double) im * im);
            // 低频到高频按对数分布分到各段
            int band = (int) (Math.log(i) / Math.log(bins) * BANDS);
            if (band < 0) band = 0;
            if (band >= BANDS) band = BANDS - 1;
            mag[band] += m;
            count[band]++;
            if (m > max) max = m;
        }
        if (max > peak) peak = max;
        else peak = Math.max(6f, peak * 0.92f);
        for (int b = 0; b < BANDS; b++) {
            float v = count[b] == 0 ? 0 : mag[b] / count[b];
            int lv = Math.round(Math.min(100f, v / peak * 130f));
            try {
                out.put(lv);
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    public static int bands() {
        return BANDS;
    }
}
