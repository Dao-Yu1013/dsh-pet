package com.dshpet.android;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;

/**
 * 后台保活：每 15 分钟检查一次桌宠服务是否还活着，不在就尝试拉起来。
 * 不申请精确闹钟权限，用不精确重复闹钟，省电且不需要用户额外授权。
 * 系统强行停止应用后不会再被拉起，这是 Android 的限制。
 */
public final class Alive extends BroadcastReceiver {
    private static final String ACTION = "com.dshpet.android.KEEPALIVE";
    private static final long INTERVAL = 15 * 60 * 1000L;
    private static final int REQ = 71;
    private static volatile long lastScheduleAt;

    public static void schedule(Context c) {
        try {
            long now = System.currentTimeMillis();
            if (now - lastScheduleAt < 10 * 60000L) return;
            lastScheduleAt = now;
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            PendingIntent pi = pending(c);
            if (pi == null) return;
            am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + INTERVAL, INTERVAL, pi);
        } catch (Throwable ignored) {
        }
    }

    public static void cancel(Context c) {
        try {
            lastScheduleAt = 0;
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            PendingIntent pi = pending(c);
            if (am != null && pi != null) am.cancel(pi);
        } catch (Throwable ignored) {
        }
    }

    private static PendingIntent pending(Context c) {
        try {
            Intent i = new Intent(c, Alive.class).setAction(ACTION);
            int flags = Build.VERSION.SDK_INT >= 23
                    ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                    : PendingIntent.FLAG_UPDATE_CURRENT;
            return PendingIntent.getBroadcast(c, REQ, i, flags);
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public void onReceive(Context c, Intent intent) {
        try {
            Cfg cfg = Cfg.load(c);
            if (!cfg.b("running", false) || !cfg.b("keepAlive", true)
                    || !Settings.canDrawOverlays(c)) {
                cancel(c);
                return;
            }
            if (!PetSvc.running) PetSvc.start(c);
        } catch (Throwable ignored) {
        }
    }
}
