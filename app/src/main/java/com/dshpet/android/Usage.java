package com.dshpet.android;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 每天用了哪些应用、用了多久、都在干什么。
 * 只用系统 UsageStats 本地统计，不上传、不耗 token；没有权限时页面只显示开启入口。
 * 历史每天一条存 S-G/usage_daily.json，随备份迁移。
 */
public final class Usage {
    private static final String FILE = "usage_daily.json";
    private static volatile long lastSnapAt;
    private static volatile String lastSnapDay = "";

    private Usage() {
    }

    public static boolean granted(Context c) {
        if (Perm.usage(c)) return true;
        // 部分 ROM 的 AppOps 返回 DEFAULT，实际已授权；用最近 7 天有没有数据兜底判断
        try {
            long now = System.currentTimeMillis();
            List<UsageStats> l = usm(c).queryUsageStats(UsageStatsManager.INTERVAL_DAILY,
                    now - 7 * 86400000L, now);
            for (UsageStats s : l) {
                if (s != null && s.getTotalTimeInForeground() > 0) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 页面数据：今天的明细 + 最近 7 天历史；顺手把今天写进历史。 */
    public static JSONObject state(Context c) {
        JSONObject out = new JSONObject();
        try {
            boolean g = granted(c);
            out.put("granted", g);
            if (!g) return out;
            JSONObject today = dayStats(c, System.currentTimeMillis());
            out.put("today", today);
            saveDay(c, today);
            out.put("history", history(c, 7));
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** 每小时最多写一次当天快照，保证历史接近完整，又不频繁查系统。 */
    public static void snapshot(Context c) {
        try {
            long now = System.currentTimeMillis();
            String day = dayKey(now);
            if (day.equals(lastSnapDay) && now - lastSnapAt < 3600000L) return;
            lastSnapAt = now;
            if (!granted(c)) return;
            lastSnapDay = day;
            saveDay(c, dayStats(c, now));
        } catch (Throwable ignored) {
        }
    }

    /* ---------------- 统计 ---------------- */

    private static JSONObject dayStats(Context c, long now) {
        JSONObject out = new JSONObject();
        long start = startOfDay(now);
        Map<String, long[]> agg = new HashMap<>(); // pkg -> [前台毫秒, 打开次数]
        int unlocks = 0;
        try {
            UsageStatsManager usm = usm(c);
            List<UsageStats> list = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, now);
            if (list != null) {
                for (UsageStats s : list) {
                    if (s == null) continue;
                    String pkg = s.getPackageName();
                    if (pkg == null || pkg.isEmpty()) continue;
                    // 跨天区间可能带回昨天的桶，只保留今天用过的
                    if (s.getLastTimeUsed() > 0 && s.getLastTimeUsed() < start) continue;
                    long[] v = agg.get(pkg);
                    if (v == null) {
                        v = new long[]{0, 0};
                        agg.put(pkg, v);
                    }
                    v[0] += Math.max(0, s.getTotalTimeInForeground());
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            UsageEvents ev = usm(c).queryEvents(start, now);
            if (ev != null) {
                UsageEvents.Event e = new UsageEvents.Event();
                while (ev.hasNextEvent()) {
                    ev.getNextEvent(e);
                    int t = e.getEventType();
                    if (t == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                        String pkg = e.getPackageName();
                        if (pkg == null) continue;
                        long[] v = agg.get(pkg);
                        if (v == null) {
                            v = new long[]{0, 0};
                            agg.put(pkg, v);
                        }
                        v[1]++;
                    } else if (t == UsageEvents.Event.KEYGUARD_HIDDEN) {
                        unlocks++;
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        PackageManager pm = c.getPackageManager();
        Map<String, String> labelCache = new HashMap<>();
        Map<String, String> catCache = new HashMap<>();
        long totalMs = 0;
        List<JSONObject> apps = new ArrayList<>();
        for (Map.Entry<String, long[]> en : agg.entrySet()) {
            String pkg = en.getKey();
            long ms = en.getValue()[0];
            if (ms <= 0) continue;
            String name = label(pm, pkg, labelCache);
            if (name.isEmpty()) continue;
            // 只统计能在桌面点开的应用，过滤系统桌面、设置等服务
            if (pm.getLaunchIntentForPackage(pkg) == null) continue;
            totalMs += ms;
            try {
                JSONObject a = new JSONObject();
                a.put("pkg", pkg);
                a.put("name", name);
                a.put("min", Math.max(1, ms / 60000));
                a.put("launches", en.getValue()[1]);
                a.put("cat", category(c, pkg, catCache));
                apps.add(a);
            } catch (Throwable ignored) {
            }
        }
        apps.sort(new Comparator<JSONObject>() {
            @Override
            public int compare(JSONObject x, JSONObject y) {
                return y.optInt("min", 0) - x.optInt("min", 0);
            }
        });
        JSONArray top = new JSONArray();
        for (int i = 0; i < apps.size() && i < 12; i++) top.put(apps.get(i));
        try {
            out.put("date", dayKey(now));
            out.put("totalMin", Math.round(totalMs / 60000f));
            out.put("unlocks", unlocks);
            out.put("apps", top);
            out.put("top", top.optJSONObject(0) == null ? "" : top.optJSONObject(0).optString("name", ""));
            out.put("topMin", top.optJSONObject(0) == null ? 0 : top.optJSONObject(0).optInt("min", 0));
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static String label(PackageManager pm, String pkg, Map<String, String> cache) {
        String s = cache.get(pkg);
        if ("".equals(s)) return "";
        if (s != null) return s;
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            String n = pm.getApplicationLabel(ai).toString();
            cache.put(pkg, n);
            return n;
        } catch (Throwable t) {
            cache.put(pkg, "");
            return "";
        }
    }

    /** 应用分类：先按常见包名兜底，再读系统分类，都没有就归到「其他」。 */
    private static String category(Context c, String pkg, Map<String, String> cache) {
        String hit = cache.get(pkg);
        if (hit != null) return hit;
        String[] keys = {"tencent.mm", "tencent.mobileqq", "telegram", "whatsapp", "messenger",
                "weibo", "discord", "snapchat", "instagram", "line", "kakaotalk"};
        if (has(pkg, keys)) return set(cache, pkg, "社交");
        String[] video = {"bili", "douyin", "kuaishou", "youku", "qiyi", "qqlive", "mgtv",
                "hunantv", "netflix", "youtube", "iqiyi"};
        if (has(pkg, video)) return set(cache, pkg, "视频");
        String[] music = {"cloudmusic", "qqmusic", "kugou", "luna.music", "spotify",
                "com.google.android.apps.youtube.music", "apple.music", "melon"};
        if (has(pkg, music)) return set(cache, pkg, "音乐");
        String[] read = {"qidian", "weread", "duokan", "ireader", "kindle", "medium", "zhihu"};
        if (has(pkg, read)) return set(cache, pkg, "阅读");
        String[] game = {"tmgp", "mihoyo", "hypergryph", "lilith", "supercell", "roblox", "mojang"};
        if (has(pkg, game)) return set(cache, pkg, "游戏");
        String[] shop = {"taobao", "jd.com", "pinduoduo", "xianyu", "suning", "amazon"};
        if (has(pkg, shop)) return set(cache, pkg, "购物");
        String[] life = {"meituan", "eleme", "dianping", "didi", "waimai", "ctrip", "12306"};
        if (has(pkg, life)) return set(cache, pkg, "生活");
        String[] work = {"wps", "notion", "feishu", "lark", "dingtalk", "tencent.docs", "slack"};
        if (has(pkg, work)) return set(cache, pkg, "效率");
        String[] browse = {"chrome", "firefox", "microsoft.edge", "quark", "ucmobile", "toutiao"};
        if (has(pkg, browse)) return set(cache, pkg, "浏览");
        if (pkg.equals(c.getPackageName())) return set(cache, pkg, "陪伴");
        try {
            ApplicationInfo ai = c.getPackageManager().getApplicationInfo(pkg, 0);
            switch (ai.category) {
                case ApplicationInfo.CATEGORY_GAME:
                    return set(cache, pkg, "游戏");
                case ApplicationInfo.CATEGORY_AUDIO:
                    return set(cache, pkg, "音乐");
                case ApplicationInfo.CATEGORY_VIDEO:
                    return set(cache, pkg, "视频");
                case ApplicationInfo.CATEGORY_SOCIAL:
                    return set(cache, pkg, "社交");
                case ApplicationInfo.CATEGORY_NEWS:
                    return set(cache, pkg, "阅读");
                case ApplicationInfo.CATEGORY_MAPS:
                    return set(cache, pkg, "出行");
                case ApplicationInfo.CATEGORY_PRODUCTIVITY:
                    return set(cache, pkg, "效率");
                case ApplicationInfo.CATEGORY_IMAGE:
                    return set(cache, pkg, "图片");
                default:
                    break;
            }
        } catch (Throwable ignored) {
        }
        return set(cache, pkg, "其他");
    }

    private static boolean has(String pkg, String[] keys) {
        for (String k : keys) {
            if (pkg.contains(k)) return true;
        }
        return false;
    }

    private static String set(Map<String, String> cache, String pkg, String cat) {
        cache.put(pkg, cat);
        return cat;
    }

    /* ---------------- 历史 ---------------- */

    private static void saveDay(Context c, JSONObject day) {
        if (day == null || day.optString("date", "").isEmpty()) return;
        try {
            JSONArray a = Sg.readArr(c, FILE);
            JSONArray out = new JSONArray();
            String date = day.optString("date", "");
            out.put(day);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null || date.equals(o.optString("date", ""))) continue;
                out.put(o);
            }
            // 只保留最近 14 天，避免文件无限增长
            JSONArray cut = new JSONArray();
            for (int i = 0; i < out.length() && i < 14; i++) cut.put(out.get(i));
            Sg.writeArr(c, FILE, cut);
        } catch (Throwable ignored) {
        }
    }

    private static JSONArray history(Context c, int days) {
        JSONArray out = new JSONArray();
        try {
            JSONArray a = Sg.readArr(c, FILE);
            ArrayList<JSONObject> list = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null) list.add(o);
            }
            Collections.sort(list, new Comparator<JSONObject>() {
                @Override
                public int compare(JSONObject x, JSONObject y) {
                    return x.optString("date", "").compareTo(y.optString("date", ""));
                }
            });
            for (int i = Math.max(0, list.size() - days); i < list.size(); i++) out.put(list.get(i));
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static UsageStatsManager usm(Context c) {
        return (UsageStatsManager) c.getSystemService(Context.USAGE_STATS_SERVICE);
    }

    private static long startOfDay(long now) {
        Calendar cc = Calendar.getInstance();
        cc.setTimeInMillis(now);
        cc.set(Calendar.HOUR_OF_DAY, 0);
        cc.set(Calendar.MINUTE, 0);
        cc.set(Calendar.SECOND, 0);
        cc.set(Calendar.MILLISECOND, 0);
        return cc.getTimeInMillis();
    }

    private static String dayKey(long now) {
        return new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new java.util.Date(now));
    }
}
