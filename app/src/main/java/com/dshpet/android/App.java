package com.dshpet.android;

import android.app.Application;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;

public final class App extends Application {
    private static volatile App instance;

    public static App get() {
        return instance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                Sg.write(new File(Sg.dir(this), "diag.txt"), Sg.now() + " " + t.getName() + "\n" + sw + "\n");
            } catch (Throwable ignored) {
            }
            if (prev != null) prev.uncaughtException(t, e);
        });
        // 首次启动记一次「她第一次见到这个应用」的环境快照，之后只在升级时更新版本
        try {
            InApp.firstLook(this);
        } catch (Throwable ignored) {
        }
        // 进程被系统拉起时，把保活看门狗和定时插件的闹钟重新挂上
        try {
            Cfg cfg = Cfg.load(this);
            if (cfg.b("running", false) && cfg.b("keepAlive", true)) Alive.schedule(this);
            if (cfg.b("running", false)) TickAlarm.schedule(this);
        } catch (Throwable ignored) {
        }
    }
}
