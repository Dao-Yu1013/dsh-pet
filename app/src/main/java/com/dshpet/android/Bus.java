package com.dshpet.android;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** 轻量事件总线：无障碍/通知/服务 -> UI / 桌宠。 */
public final class Bus {
    public interface Sink {
        void on(String event, JSONObject data);
    }

    private static final List<Sink> sinks = new ArrayList<>();

    public static volatile long lastA11yTs;
    public static volatile String currentPackage = "";
    public static volatile String screenText = "";
    /** 上面这段屏幕文字是从哪个应用读到的；换应用后旧文字必须作废。 */
    public static volatile String screenPkg = "";
    /** 用户刚问过屏幕内容：这一次构建提示词时无视 screenContext 开关，把刚抓到的文字带上。 */
    public static volatile boolean screenForce;
    public static volatile String musicTitle = "";
    public static volatile String musicArtist = "";
    public static volatile boolean musicPlaying;
    public static volatile int unreadCount;
    public static volatile long lastSkipTs;

    private Bus() {
    }

    public static synchronized void add(Sink s) {
        if (s != null && !sinks.contains(s)) sinks.add(s);
    }

    public static synchronized void remove(Sink s) {
        sinks.remove(s);
    }

    public static void emit(String event, JSONObject data) {
        if (data == null) data = new JSONObject();
        List<Sink> copy;
        synchronized (Bus.class) {
            copy = new ArrayList<>(sinks);
        }
        for (Sink s : copy) {
            try {
                s.on(event, data);
            } catch (Throwable ignored) {
            }
        }
    }
}
