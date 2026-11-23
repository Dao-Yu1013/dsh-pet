package com.dshpet.android;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;

import java.util.Calendar;

/**
 * 电量感应：深夜手机快没电了还亮着屏幕，她会急。
 * 不做常驻后台监听——只在桌宠服务活着时动态注册，服务停了就注销。
 * （ACTION_BATTERY_CHANGED 是粘性广播，只能动态注册，注册瞬间就会拿到当前电量。）
 */
public final class Battery extends BroadcastReceiver {
    /** 低于这个电量才唠叨。 */
    private static final int LOW = 15;
    /** 两次唠叨之间的最小间隔。 */
    private static final long GAP_MS = 3 * 3600 * 1000L;

    private static Battery rx;

    private Battery() {
    }

    /** 由桌宠服务在启动时挂上。 */
    public static synchronized void attach(Context c) {
        if (rx != null) return;
        try {
            rx = new Battery();
            IntentFilter f = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                c.registerReceiver(rx, f, Context.RECEIVER_NOT_EXPORTED);
            } else {
                c.registerReceiver(rx, f);
            }
        } catch (Throwable t) {
            rx = null;
        }
    }

    /** 服务停止时注销，别留后台监听。 */
    public static synchronized void detach(Context c) {
        if (rx == null) return;
        try {
            c.unregisterReceiver(rx);
        } catch (Throwable ignored) {
        }
        rx = null;
    }

    @Override
    public void onReceive(Context c, Intent intent) {
        try {
            if (intent == null) return;
            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            if (level < 0 || scale <= 0) return;
            int pct = Math.round(level * 100f / scale);
            boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                    || status == BatteryManager.BATTERY_STATUS_FULL;
            if (charging || pct > LOW) return;
            Cfg cfg = Cfg.load(c);
            if (!cfg.b("batteryCare", true)) return;
            // 只在深夜到凌晨唠叨：白天没电是常事，不值得打断他
            int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
            if (hour >= 3 && hour < 22) return;
            long now = System.currentTimeMillis();
            if (now - cfg.l("batteryWarnAt", 0L) < GAP_MS) return;
            cfg.set("batteryWarnAt", now);
            cfg.save(c);
            // 不占主动次数：熬夜又快没电这种时刻，不该被打扰预算拦住
            PetSvc.sayImportant(c, Care.battery(pct));
        } catch (Throwable ignored) {
        }
    }
}