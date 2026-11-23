package com.dshpet.android;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

public final class Boot extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String a = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(a) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) return;
        Cfg cfg = Cfg.load(c);
        if (cfg.b("running", false) && cfg.b("autoStart", true) && Settings.canDrawOverlays(c)) {
            PetSvc.start(c);
            if (cfg.b("keepAlive", true)) Alive.schedule(c);
            // 重启后闹钟全没了，定时插件要重新挂上
            TickAlarm.schedule(c);
        }
    }
}
