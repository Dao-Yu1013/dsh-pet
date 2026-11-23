package com.dshpet.android;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;

/**
 * 定时插件的系统级兜底。
 * 进程被系统杀掉后，Alive 最快也要 15 分钟才拉一次；这里按插件真正的下次时间挂一个闹钟，
 * 到点直接把服务叫起来跑一轮，不依赖服务还活着。
 * 不申请精确闹钟权限：能精确就精确，不能就用 setAndAllowWhileIdle（可能推迟几分钟）。
 */
public final class TickAlarm extends BroadcastReceiver {
    private static final String ACTION = "com.dshpet.android.TICK";
    private static final int REQ = 72;
    private static volatile long lastAt;
    private static volatile long lastSetAt;

    public static void schedule(Context c) {
        try {
            if (c == null) return;
            if (!Cfg.load(c).b("running", false)) {
                cancel(c);
                return;
            }
            long nextAt = nextDue(c);
            if (nextAt <= 0) {
                cancel(c);
                return;
            }
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            PendingIntent pi = pending(c);
            if (am == null || pi == null) return;
            // 至少留一分钟，避免到点后连着唤醒、而这一分钟里没人更新 nextAt
            long at = Math.max(nextAt, System.currentTimeMillis() + 60000L);
            long now = System.currentTimeMillis();
            // 每分钟都会被叫一次，同一目标就没必要反复取消重挂
            if (at == lastAt && now - lastSetAt < 90000L) return;
            am.cancel(pi);
            if (Build.VERSION.SDK_INT >= 31 && am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            } else if (Build.VERSION.SDK_INT >= 23) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            } else {
                am.set(AlarmManager.RTC_WAKEUP, at, pi);
            }
            lastAt = at;
            lastSetAt = now;
        } catch (Throwable ignored) {
        }
    }

    public static void cancel(Context c) {
        try {
            lastAt = 0;
            lastSetAt = 0;
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            PendingIntent pi = pending(c);
            if (am != null && pi != null) am.cancel(pi);
        } catch (Throwable ignored) {
        }
    }

    /** 所有启用中的定时插件里最早的下次时间；没有就返回 0。 */
    private static long nextDue(Context c) {
        long best = 0;
        try {
            JSONArray ps = Plugins.list(c);
            for (int i = 0; i < ps.length(); i++) {
                JSONObject p = ps.optJSONObject(i);
                if (p == null || !p.optBoolean("enabled", true)
                        || !p.optBoolean("hasCode", false) || !p.optBoolean("background", false)) continue;
                File d = new File(Plugins.dir(c), p.optString("id", ""));
                if (!d.isDirectory()) continue;
                long nextAt = 0;
                try {
                    String s = Sg.read(new File(d, "tick.json"), "");
                    if (!s.trim().isEmpty()) nextAt = new JSONObject(s).optLong("nextAt", 0);
                } catch (Throwable ignored) {
                }
                if (nextAt <= 0) nextAt = System.currentTimeMillis();
                if (best <= 0 || nextAt < best) best = nextAt;
            }
        } catch (Throwable ignored) {
        }
        return best;
    }

    private static PendingIntent pending(Context c) {
        try {
            Intent i = new Intent(c, TickAlarm.class).setAction(ACTION);
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
        if (c == null || intent == null) return;
        try {
            if (!Cfg.load(c).b("running", false)) return;
            // 服务已经被杀：先拉起来；拉不起来也要直接跑这一轮，插件本身不依赖服务
            if (!PetSvc.running) {
                try {
                    PetSvc.start(c);
                } catch (Throwable ignored) {
                }
            }
            Plugins.tickBackground(c);
        } catch (Throwable ignored) {
        }
        schedule(c);
    }
}
