package com.dshpet.android;

import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.PowerManager;
import android.os.StatFs;
import android.provider.Settings;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 设备状态按需读取：平时不轮询、不常驻，只有用户明确问到才读一次。
 * 只读取不需要额外授权就能拿到的公开状态；读不到就跳过，不猜。
 */
public final class DeviceAsk {
    private DeviceAsk() {
    }

    /** 命中设备查询时返回本地回答，否则返回 null 交给 AI。 */
    public static String answerIfAsked(Context c, String text) {
        if (c == null || text == null) return null;
        String q = text.replaceAll("\\s+", "");
        if (q.isEmpty()) return null;
        List<String> kinds = classify(q);
        if (kinds.isEmpty()) return null;
        if (!isQuery(q)) return null;
        if (kinds.contains("all")) {
            kinds = new ArrayList<>();
            kinds.add("all");
        }
        List<String> lines = new ArrayList<>();
        for (String k : kinds) {
            String line = line(c, k);
            if (line != null && !line.trim().isEmpty()) lines.add(line.trim());
        }
        if (lines.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            if (sb.length() > 0) sb.append(kinds.contains("all") ? "\n" : "；");
            sb.append(line);
        }
        return sb.toString();
    }

    /* ---------------- 意图分类 ---------------- */

    private static List<String> classify(String q) {
        List<String> out = new ArrayList<>();
        if (has(q, "电量", "电池", "充电", "续航", "多少电", "没电", "剩多少电", "还有电",
                "手机烫", "电池烫", "手机热")) out.add("battery");
        if (has(q, "内存", "运存", "ram", "RAM")) out.add("memory");
        if (has(q, "存储", "储存", "空间", "容量", "硬盘")) out.add("storage");
        if (has(q, "网络", "wifi", "WiFi", "WLAN", "流量", "联网", "网速", "断网", "什么网", "有网")) out.add("network");
        if (has(q, "音量", "声音大小", "声音小", "声音大", "太小声", "太大声", "声音多大", "静音", "外放")) out.add("volume");
        if (has(q, "亮度", "屏幕亮", "多亮", "太暗", "太亮", "自动亮度")) out.add("brightness");
        if (has(q, "几点", "现在时间", "当前时间", "日期", "几号", "星期几", "周几")) out.add("time");
        if (has(q, "什么手机", "型号", "安卓版本", "系统版本", "安卓几", "系统几", "分辨率", "屏幕尺寸")) out.add("device");
        if (has(q, "屏幕亮着", "息屏", "屏幕状态", "锁屏", "屏幕没亮", "亮着吗")) out.add("screen");
        if (has(q, "勿扰", "免打扰")) out.add("dnd");
        if (has(q, "飞行模式")) out.add("airplane");
        if (has(q, "省电模式", "省电吗", "节电模式")) out.add("powersave");
        if (has(q, "什么应用", "哪个应用", "前台应用", "正在用什么", "在用哪个", "在哪个应用")) out.add("app");
        if (has(q, "手机状态", "设备状态", "设备信息", "手机信息", "全部状态", "都告诉我", "检查一下手机")) out.add("all");
        return out;
    }

    /** 只有带疑问/查看意图的问句才回答，避免把「电池怎么保养」当成查询。 */
    private static boolean isQuery(String q) {
        if (has(q, "保养", "清理", "优化", "设置", "怎么", "如何", "寿命", "更换", "坏了", "省电", "耗电", "费电")) {
            return has(q, "多少", "还剩", "还有", "剩下", "现在", "当前", "状态", "吗", "呢");
        }
        // 单独说「电量」「内存」「网络」这种主题词也算在问，可以直接给出当前值
        if (q.length() <= 4 && has(q, "电量", "电池", "内存", "运存", "存储", "空间",
                "网络", "音量", "亮度", "时间", "日期", "型号")) return true;
        return has(q, "多少", "几", "吗", "呢", "现在", "当前", "还剩", "还有", "剩下",
                "什么", "多大", "多久", "状态", "查", "看看", "看一下", "告诉我", "信息");
    }

    private static String line(Context c, String kind) {
        switch (kind) {
            case "battery":
                return batteryLine(c);
            case "memory":
                return memoryLine(c);
            case "storage":
                return storageLine(c);
            case "network":
                return networkLine(c);
            case "volume":
                return volumeLine(c);
            case "brightness":
                return brightnessLine(c);
            case "time":
                return timeLine();
            case "device":
                return deviceLine(c);
            case "screen":
                return screenLine(c);
            case "dnd":
                return dndLine(c);
            case "airplane":
                return airplaneLine(c);
            case "powersave":
                return powerSaveLine(c);
            case "app":
                return appLine(c);
            case "all":
                return allLine(c);
            default:
                return null;
        }
    }

    /* ---------------- 电量与充电 ---------------- */

    public static JSONObject battery(Context c) {
        JSONObject o = new JSONObject();
        try {
            BatteryManager bm = (BatteryManager) c.getSystemService(Context.BATTERY_SERVICE);
            if (bm != null) {
                put(o, "level", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY));
                put(o, "status", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS));
                put(o, "chargeCounter", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER));
                put(o, "current", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW));
                put(o, "currentAvg", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE));
                put(o, "energy", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER));
                put(o, "timeToFull", bm.computeChargeTimeRemaining());
            }
        } catch (Throwable ignored) {
        }
        try {
            Intent it = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (it != null) {
                put(o, "plugged", it.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0));
                put(o, "temp", it.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0));
                put(o, "voltage", it.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0));
                put(o, "health", it.getIntExtra(BatteryManager.EXTRA_HEALTH, 0));
                put(o, "present", it.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true));
                put(o, "tech", it.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY));
            }
        } catch (Throwable ignored) {
        }
        try {
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            put(o, "powerSave", pm != null && pm.isPowerSaveMode());
        } catch (Throwable ignored) {
        }
        return o;
    }

    private static String batteryLine(Context c) {
        JSONObject o = battery(c);
        int level = o.optInt("level", -1);
        if (level < 0 || level > 100) return "我读不到电量，这台手机可能没开放这个接口。";
        StringBuilder sb = new StringBuilder();
        sb.append("现在电量 ").append(level).append('%');
        int status = o.optInt("status", -1);
        int plugged = o.optInt("plugged", 0);
        boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;
        boolean full = status == BatteryManager.BATTERY_STATUS_FULL;
        if (plugged != 0) {
            sb.append("，").append(full ? "已经充满" : charging ? "正在" : "插着")
                    .append(pluggedName(plugged)).append('电');
        } else {
            sb.append("，没在充电");
        }
        int temp = o.optInt("temp", 0);
        if (temp > 0) {
            sb.append("，电池 ").append(fmt1(temp / 10f)).append("°C");
            if (temp >= 450) sb.append("，有点烫");
        }
        int volt = o.optInt("voltage", 0);
        if (volt > 0) sb.append('，').append(fmt1(volt / 1000f)).append('V');
        String health = healthAbnormal(o.optInt("health", 0));
        if (!health.isEmpty()) sb.append("，").append(health);
        int counter = o.optInt("chargeCounter", Integer.MIN_VALUE);
        if (counter != Integer.MIN_VALUE && counter > 0) sb.append("，剩余 ").append(counter / 1000).append("mAh");
        int current = o.optInt("current", Integer.MIN_VALUE);
        if (current != Integer.MIN_VALUE && current != 0) {
            sb.append("，电流 ").append(current / 1000).append("mA");
        }
        if (o.optBoolean("powerSave", false)) sb.append("，省电模式开着");
        long toFull = o.optLong("timeToFull", -1);
        if (charging && !full && toFull > 0) {
            sb.append("，约 ").append(Math.max(1, Math.round(toFull / 60000f))).append(" 分钟充满");
        }
        sb.append('。');
        return sb.toString();
    }

    private static String pluggedName(int p) {
        switch (p) {
            case BatteryManager.BATTERY_PLUGGED_AC:
                return "充电器";
            case BatteryManager.BATTERY_PLUGGED_USB:
                return "USB";
            case BatteryManager.BATTERY_PLUGGED_WIRELESS:
                return "无线";
            case BatteryManager.BATTERY_PLUGGED_DOCK:
                return "底座";
            default:
                return "";
        }
    }

    private static String healthAbnormal(int h) {
        switch (h) {
            case BatteryManager.BATTERY_HEALTH_OVERHEAT:
                return "电池过热";
            case BatteryManager.BATTERY_HEALTH_DEAD:
                return "电池状态异常";
            case BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE:
                return "电池电压偏高";
            case BatteryManager.BATTERY_HEALTH_COLD:
                return "电池温度偏低";
            case BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE:
                return "电池状态未知";
            default:
                return "";
        }
    }

    /* ---------------- 内存与存储 ---------------- */

    private static String memoryLine(Context c) {
        try {
            ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return null;
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            int pct = mi.totalMem <= 0 ? 0 : (int) Math.round(mi.availMem * 100.0 / mi.totalMem);
            return "内存 " + gb(mi.totalMem) + "，还剩 " + gb(mi.availMem) + "（" + pct + "% 可用）";
        } catch (Throwable t) {
            return null;
        }
    }

    private static String storageLine(Context c) {
        try {
            StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
            long total = fs.getTotalBytes();
            long free = fs.getAvailableBytes();
            if (total <= 0) return null;
            int pct = (int) Math.round(free * 100.0 / total);
            return "存储 " + gb(total) + "，还剩 " + gb(free) + "（" + pct + "% 可用）";
        } catch (Throwable t) {
            return null;
        }
    }

    /* ---------------- 网络 ---------------- */

    private static String networkLine(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network n = cm == null ? null : cm.getActiveNetwork();
            NetworkCapabilities cap = n == null || cm == null ? null : cm.getNetworkCapabilities(n);
            if (cap == null) return "现在没联网。";
            String type = "网络";
            if (cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) type = "WiFi";
            else if (cap.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) type = "移动数据";
            else if (cap.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) type = "有线网络";
            else if (cap.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) type = "VPN";
            boolean ok = cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
            return "现在是 " + type + (ok ? "，能正常上网。" : "，但可能连不上外网。");
        } catch (Throwable t) {
            return null;
        }
    }

    /* ---------------- 音量与亮度 ---------------- */

    private static String volumeLine(Context c) {
        try {
            AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return null;
            int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int pct = max <= 0 ? 0 : Math.round(cur * 100f / max);
            boolean mute = am.isStreamMute(AudioManager.STREAM_MUSIC);
            return "媒体音量 " + pct + "%" + (mute ? "，已静音。" : "。");
        } catch (Throwable t) {
            return null;
        }
    }

    private static String brightnessLine(Context c) {
        try {
            int v = Settings.System.getInt(c.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, -1);
            if (v < 0) return null;
            int mode = Settings.System.getInt(c.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS_MODE, 0);
            int pct = Math.round(v * 100f / 255);
            return "屏幕亮度约 " + Math.max(0, Math.min(100, pct)) + "%"
                    + (mode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC ? "，自动亮度开着。" : "。");
        } catch (Throwable t) {
            return null;
        }
    }

    /* ---------------- 时间 / 设备 / 屏幕状态 ---------------- */

    private static String timeLine() {
        try {
            String hm = new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date());
            String day = new SimpleDateFormat("M月d日 EEEE", Locale.CHINA).format(new Date());
            return "现在 " + hm + "，" + day + "。";
        } catch (Throwable t) {
            return null;
        }
    }

    private static String deviceLine(Context c) {
        try {
            int[] ss = Screen.size(c);
            return Build.MANUFACTURER + " " + Build.MODEL + "，Android " + Build.VERSION.RELEASE
                    + "，屏幕 " + ss[0] + "×" + ss[1] + "。";
        } catch (Throwable t) {
            return null;
        }
    }

    private static String screenLine(Context c) {
        try {
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            boolean on = pm != null && pm.isInteractive();
            KeyguardManager km = (KeyguardManager) c.getSystemService(Context.KEYGUARD_SERVICE);
            boolean lock = km != null && km.isKeyguardLocked();
            return "屏幕" + (on ? "亮着" : "已经熄了") + (lock ? "，还在锁屏。" : "。");
        } catch (Throwable t) {
            return null;
        }
    }

    private static String dndLine(Context c) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return null;
            int f = nm.getCurrentInterruptionFilter();
            return f == NotificationManager.INTERRUPTION_FILTER_ALL ? "勿扰模式关着。" : "勿扰模式开着。";
        } catch (Throwable t) {
            return null;
        }
    }

    private static String airplaneLine(Context c) {
        try {
            int v = Settings.Global.getInt(c.getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, 0);
            return v == 1 ? "飞行模式开着。" : "飞行模式关着。";
        } catch (Throwable t) {
            return null;
        }
    }

    private static String powerSaveLine(Context c) {
        try {
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            if (pm == null) return null;
            return pm.isPowerSaveMode() ? "省电模式开着。" : "省电模式关着。";
        } catch (Throwable t) {
            return null;
        }
    }

    private static String appLine(Context c) {
        String pkg = Bus.currentPackage == null ? "" : Bus.currentPackage;
        if (pkg.isEmpty() || pkg.equals(c.getPackageName())) return "我还不知道你现在在用什么应用。";
        try {
            PackageManager pm = c.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            String label = pm.getApplicationLabel(ai).toString();
            return "你现在在用「" + label + "」。";
        } catch (Throwable t) {
            return null;
        }
    }

    /* ---------------- 全部状态 ---------------- */

    private static String allLine(Context c) {
        List<String> parts = new ArrayList<>();
        JSONObject b = battery(c);
        int level = b.optInt("level", -1);
        if (level >= 0 && level <= 100) {
            int status = b.optInt("status", -1);
            boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                    || status == BatteryManager.BATTERY_STATUS_FULL;
            StringBuilder s = new StringBuilder("电量 " + level + "%");
            if (charging) s.append(" 充电中");
            else if (b.optInt("plugged", 0) != 0) s.append(" 已插电");
            else s.append(" 未充电");
            int temp = b.optInt("temp", 0);
            if (temp > 0) s.append("，").append(fmt1(temp / 10f)).append("°C");
            parts.add(s.toString());
        }
        try {
            ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            if (am != null) am.getMemoryInfo(mi);
            StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
            if (am == null || mi.totalMem <= 0) {
                parts.add("存储剩 " + gb(fs.getAvailableBytes()) + "/" + gb(fs.getTotalBytes()));
            } else {
                parts.add("内存剩 " + gb(mi.availMem) + "/" + gb(mi.totalMem)
                        + "，存储剩 " + gb(fs.getAvailableBytes()) + "/" + gb(fs.getTotalBytes()));
            }
        } catch (Throwable ignored) {
        }
        String net = networkLine(c);
        String vol = volumeLine(c);
        String bri = brightnessLine(c);
        StringBuilder third = new StringBuilder();
        if (net != null) third.append(net);
        if (vol != null) third.append(third.length() > 0 ? "，" : "").append(vol);
        if (bri != null) third.append(third.length() > 0 ? "，" : "").append(bri);
        if (third.length() > 0) parts.add(third.toString());
        String time = timeLine();
        String dev = deviceLine(c);
        if (time != null) parts.add(time + (dev == null ? "" : "\n" + dev));
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(p);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /* ---------------- 工具 ---------------- */

    private static void put(JSONObject o, String key, Object v) {
        try {
            o.put(key, v == null ? "" : v);
        } catch (Throwable ignored) {
        }
    }

    private static String gb(long bytes) {
        try {
            return fmt1(bytes / 1073741824.0) + "G";
        } catch (Throwable t) {
            return "?";
        }
    }

    private static String fmt1(double v) {
        return String.format(Locale.CHINA, "%.1f", v);
    }

    private static boolean has(String s, String... keys) {
        for (String k : keys) {
            if (s.contains(k)) return true;
        }
        return false;
    }
}
