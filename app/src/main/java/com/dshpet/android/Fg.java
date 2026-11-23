package com.dshpet.android;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 前台应用与已安装应用列表。 */
public final class Fg {
    private static JSONArray cache;
    private static long cacheAt;

    private Fg() {
    }

    public static String current(Context c) {
        if (!Perm.usage(c)) return Bus.currentPackage;
        try {
            android.app.usage.UsageStatsManager m = (android.app.usage.UsageStatsManager) c.getSystemService(Context.USAGE_STATS_SERVICE);
            long now = System.currentTimeMillis();
            android.app.usage.UsageEvents ev = m.queryEvents(now - 10000, now);
            android.app.usage.UsageEvents.Event e = new android.app.usage.UsageEvents.Event();
            String last = "";
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e);
                if (e.getEventType() == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED
                        || e.getEventType() == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    last = e.getPackageName();
                }
            }
            if (!last.isEmpty()) Bus.currentPackage = last;
            return last;
        } catch (Throwable t) {
            return Bus.currentPackage;
        }
    }

    public static JSONArray apps(Context c) {
        if (cache != null && SystemClock.elapsedRealtime() - cacheAt < 10 * 60 * 1000) return cache;
        JSONArray out = new JSONArray();
        List<JSONObject> list = new ArrayList<>();
        try {
            PackageManager pm = c.getPackageManager();
            List<ApplicationInfo> all = pm.getInstalledApplications(0);
            for (ApplicationInfo ai : all) {
                if (ai.packageName.equals(c.getPackageName())) continue;
                try {
                    if (pm.getLaunchIntentForPackage(ai.packageName) == null) continue;
                } catch (Throwable t) {
                    continue;
                }
                JSONObject o = new JSONObject();
                o.put("pkg", ai.packageName);
                o.put("name", pm.getApplicationLabel(ai).toString());
                o.put("system", (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0);
                o.put("icon", icon(pm.getApplicationIcon(ai)));
                list.add(o);
            }
            list.sort(Comparator.comparing(a -> a.optString("name")));
            for (JSONObject o : list) out.put(o);
        } catch (Throwable ignored) {
        }
        cache = out;
        cacheAt = SystemClock.elapsedRealtime();
        return out;
    }

    private static String icon(Drawable d) {
        try {
            int n = 96;
            Bitmap b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(b);
            d.setBounds(0, 0, n, n);
            d.draw(cv);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.PNG, 90, bo);
            b.recycle();
            return "data:image/png;base64," + Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP);
        } catch (Throwable t) {
            return "";
        }
    }
}
