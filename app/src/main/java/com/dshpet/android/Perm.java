package com.dshpet.android;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

public final class Perm {
    private Perm() {
    }

    public static boolean overlay(Context c) {
        return Settings.canDrawOverlays(c);
    }

    public static boolean notif(Context c) {
        return Build.VERSION.SDK_INT < 33 || c.checkSelfPermission("android.permission.POST_NOTIFICATIONS") == 0;
    }

    public static boolean a11y(Context c) {
        String s = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (s == null) return false;
        return s.contains(c.getPackageName() + "/" + A11y.class.getName()) || s.contains(c.getPackageName() + "/.A11y");
    }

    public static boolean notifyListener(Context c) {
        String s = Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners");
        return s != null && s.contains(c.getPackageName());
    }

    public static boolean usage(Context c) {
        try {
            AppOpsManager m = (AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
            int mode = m.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), c.getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean calendar(Context c) {
        return c.checkSelfPermission("android.permission.READ_CALENDAR") == 0;
    }

    public static boolean allFiles(Context c) {
        return Sg.canUsePublic(c);
    }

    public static boolean battery(Context c) {
        android.os.PowerManager p = (android.os.PowerManager) c.getSystemService(Context.POWER_SERVICE);
        return p != null && p.isIgnoringBatteryOptimizations(c.getPackageName());
    }

    public static boolean record(Context c) {
        return c.checkSelfPermission("android.permission.RECORD_AUDIO") == 0;
    }

    public static boolean location(Context c) {
        return Geo.granted(c);
    }

    public static boolean backgroundLocation(Context c) {
        return Geo.background(c);
    }

    public static boolean autostart(Context c) {
        try {
            AppOpsManager m = (AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
            int mode = m.checkOpNoThrow("android:run_in_background", android.os.Process.myUid(), c.getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Throwable t) {
            return true;
        }
    }

    /** 权限健康检查：逐项状态、影响的功能、修复入口。 */
    public static JSONArray health(Context c) {
        JSONArray a = new JSONArray();
        item(a, "overlay", "悬浮窗", overlay(c), "required", "让她能出现在其他应用上面", "系统设置 › 显示在其他应用上层");
        item(a, "notif", "通知权限", notif(c), "recommended", "常驻通知和提醒；关掉后可能被系统清理", "系统设置 › 通知");
        item(a, "a11y", "无障碍服务", a11y(c), "optional", "广告跳过、理解屏幕文字；开启后屏幕文字会发给 AI", "系统设置 › 无障碍");
        item(a, "listener", "通知读取", notifyListener(c), "optional", "通知摘要；通知内容会发给 AI 做总结", "系统设置 › 通知读取权限");
        item(a, "usage", "使用情况访问", usage(c), "optional", "识别正在用的应用、游戏静默", "系统设置 › 使用情况访问");
        item(a, "battery", "电池不优化", battery(c), "recommended", "让她在后台活着、按时提醒", "系统设置 › 电池优化");
        item(a, "calendar", "日历读取", calendar(c), "optional", "日程与会前提醒", "系统设置 › 权限 › 日历");
        item(a, "files", "所有文件访问", allFiles(c), "optional", "从 Download/Documents 扫描书籍", "系统设置 › 所有文件访问");
        item(a, "location", "定位权限", location(c), "optional", "到家/到公司提醒", "系统设置 › 权限 › 位置");
        item(a, "backgroundLocation", "后台定位", backgroundLocation(c), "optional", "锁屏时也能判断位置", "系统设置 › 权限 › 位置 › 始终允许");
        item(a, "record", "麦克风", record(c), "optional", "语音输入", "系统设置 › 权限 › 麦克风");
        return a;
    }

    private static void item(JSONArray a, String id, String title, boolean granted, String level, String affects, String fix) {
        try {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("title", title);
            o.put("granted", granted);
            o.put("level", level);
            o.put("affects", affects);
            o.put("fix", fix);
            a.put(o);
        } catch (Throwable ignored) {
        }
    }

    public static JSONObject status(Context c) {
        JSONObject o = new JSONObject();
        try {
            o.put("overlay", overlay(c));
            o.put("notif", notif(c));
            o.put("a11y", a11y(c));
            o.put("listener", notifyListener(c));
            o.put("usage", usage(c));
            o.put("calendar", calendar(c));
            o.put("files", allFiles(c));
            o.put("battery", battery(c));
            o.put("location", location(c));
            o.put("backgroundLocation", backgroundLocation(c));
            o.put("record", record(c));
            o.put("autostart", autostart(c));
            o.put("health", health(c));
            o.put("path", Sg.path(c));
            o.put("version", Ver.NAME);
        } catch (Throwable ignored) {
        }
        return o;
    }

    public static boolean open(Context c, String kind) {
        try {
            Intent i = null;
            switch (kind) {
                case "overlay":
                    i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + c.getPackageName()));
                    break;
                case "a11y":
                    i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                    break;
                case "listener":
                    i = new Intent("android.settings.NOTIFICATION_LISTENER_SETTINGS");
                    break;
                case "usage":
                    i = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
                    break;
                case "files":
                    if (Build.VERSION.SDK_INT >= 30) {
                        i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + c.getPackageName()));
                    } else {
                        i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + c.getPackageName()));
                    }
                    break;
                case "battery":
                    i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + c.getPackageName()));
                    break;
                case "batteryOff":
                    // 关闭省电豁免：系统没有“直接取消”的入口，打开电池优化列表让用户改回“优化”
                    i = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                    break;
                case "notif":
                    i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, c.getPackageName());
                    break;
                case "location":
                case "backgroundLocation":
                case "record":
                    i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + c.getPackageName()));
                    break;
                case "autostart":
                    i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + c.getPackageName()));
                    break;
                case "app":
                    i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + c.getPackageName()));
                    break;
            }
            if (i == null) return false;
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
            return true;
        } catch (Throwable t) {
            // 部分 ROM 不认专用设置页，退回应用详情页，避免点了没反应
            try {
                Intent fb = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + c.getPackageName()));
                fb.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(fb);
                return true;
            } catch (Throwable t2) {
                return false;
            }
        }
    }

    public static String allFilesPath() {
        try {
            return new java.io.File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "S-G").getAbsolutePath();
        } catch (Throwable t) {
            return "";
        }
    }
}
