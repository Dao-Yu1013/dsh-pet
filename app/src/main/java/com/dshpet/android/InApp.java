package com.dshpet.android;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 她对这个应用里发生的事的观察：翻过哪些功能页、改了什么设置、看了哪本书、
 * 发了什么消息、记了哪笔账。全部只存本机 S-G/monitor.json，随备份迁移。
 */
public final class InApp {
    private static final String FILE = "monitor.json";
    private static final Object LOCK = new Object();
    private static final int MAX = 240;
    private static volatile long lastRemarkAt;
    private static final Map<String, Long> LAST = new HashMap<>();

    private InApp() {
    }

    /** 记录一条她注意到的应用内动作；连续重复动作 2 分钟内只留一条。 */
    public static void track(Context c, String cat, String action, String detail) {
        if (c == null) return;
        try {
            if (!Cfg.load(c).b("watchApp", true)) return;
            String sig = (cat == null ? "" : cat) + "|" + (action == null ? "" : action)
                    + "|" + (detail == null ? "" : detail);
            long now = System.currentTimeMillis();
            Long lastAt = LAST.get(sig);
            if (lastAt != null && now - lastAt < 120000L) return;
            if (LAST.size() > 80) LAST.clear();
            LAST.put(sig, now);
            JSONObject o = new JSONObject();
            o.put("ts", now);
            o.put("cat", cat == null || cat.isEmpty() ? "其他" : cat);
            o.put("action", clean(action, 40));
            o.put("detail", clean(detail, 60));
            synchronized (LOCK) {
                JSONArray a = list(c);
                JSONObject last = a.length() == 0 ? null : a.optJSONObject(a.length() - 1);
                if (last != null
                        && o.optString("cat").equals(last.optString("cat"))
                        && o.optString("action").equals(last.optString("action"))
                        && o.optString("detail").equals(last.optString("detail"))
                        && o.optLong("ts") - last.optLong("ts") < 120000L) {
                    return;
                }
                a.put(o);
                JSONArray cut = new JSONArray();
                for (int i = Math.max(0, a.length() - MAX); i < a.length(); i++) cut.put(a.opt(i));
                Sg.writeArr(c, FILE, cut);
            }
        } catch (Throwable ignored) {
        }
    }

    public static JSONArray list(Context c) {
        JSONArray a = Sg.readArr(c, FILE);
        return a == null ? new JSONArray() : a;
    }

    public static void clear(Context c) {
        synchronized (LOCK) {
            try {
                Sg.writeArr(c, FILE, new JSONArray());
            } catch (Throwable ignored) {
            }
        }
    }

    /* ---------------- 进提示词 ---------------- */

    /** 近两小时她注意到的高层动作摘要；没什么就不注入，避免白耗 token。 */
    public static String prompt(Context c) {
        try {
            JSONArray a = list(c);
            long cutoff = System.currentTimeMillis() - 2 * 3600000L;
            Map<String, Integer> count = new HashMap<>();
            long lastBookTs = 0, lastSetTs = 0;
            String lastBook = "", lastSet = "";
            int total = 0;
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null || o.optLong("ts", 0) < cutoff) continue;
                total++;
                String cat = o.optString("cat", "其他");
                count.put(cat, count.getOrDefault(cat, 0) + 1);
                if ("阅读".equals(cat) && o.optLong("ts", 0) >= lastBookTs) {
                    lastBookTs = o.optLong("ts", 0);
                    lastBook = o.optString("detail", "");
                }
                if ("设置".equals(cat) && o.optLong("ts", 0) >= lastSetTs) {
                    lastSetTs = o.optLong("ts", 0);
                    lastSet = o.optString("detail", "");
                }
            }
            if (total == 0) return "";
            StringBuilder sb = new StringBuilder();
            sb.append("你注意到他这两小时在应用里：");
            int shown = 0;
            for (Map.Entry<String, Integer> e : count.entrySet()) {
                sb.append(e.getKey()).append(' ').append(e.getValue()).append(" 次，");
                if (++shown >= 4) break;
            }
            if (!lastBook.isEmpty()) sb.append("最近在看「").append(lastBook).append("」；");
            if (!lastSet.isEmpty()) sb.append("刚改过「").append(lastSet).append("」；");
            sb.append("聊到相关的事时自然接一句，不要生硬复述，也不要把这些记录当成监视报告念出来。\n");
            return sb.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** 一句吐槽：取最近 12 小时里最值得说的一件事，用她自己的语气。 */
    public static String remark(Context c) {
        try {
            JSONArray a = list(c);
            long cutoff = System.currentTimeMillis() - 12 * 3600000L;
            JSONObject lastNav = null, lastSet = null, lastBook = null, lastLed = null, lastType = null;
            for (int i = a.length() - 1; i >= 0; i--) {
                JSONObject o = a.optJSONObject(i);
                if (o == null || o.optLong("ts", 0) < cutoff) continue;
                String cat = o.optString("cat", "");
                if (lastNav == null && "功能".equals(cat)) lastNav = o;
                else if (lastSet == null && "设置".equals(cat)) lastSet = o;
                else if (lastBook == null && "阅读".equals(cat)) lastBook = o;
                else if (lastLed == null && "记账".equals(cat)) lastLed = o;
                else if (lastType == null && "输入".equals(cat)) lastType = o;
            }
            if (lastBook != null) {
                return "又翻开「" + lastBook.optString("detail", "那本书") + "」了。看到哪了，别跟我说忘了。";
            }
            if (lastLed != null) {
                return "刚记了一笔「" + lastLed.optString("action", "账") + "」，花钱手不抖，我替你心疼一下。";
            }
            if (lastSet != null) {
                return "你把「" + lastSet.optString("detail", "某个设置") + "」调了调？我可记着了。";
            }
            if (lastNav != null) {
                return "看你翻「" + lastNav.optString("detail", "功能中心") + "」翻了半天，要不要我直接告诉你在哪？";
            }
            if (lastType != null) {
                return "你刚才发的那句「" + lastType.optString("detail", "") + "」，我看到了，别装没事。";
            }
            String[] base = {
                    "你今天在应用里折腾的每一步，我都悄悄记下来了。",
                    "别以为我没看见你在翻设置。",
            };
            return base[(int) (Math.random() * base.length)];
        } catch (Throwable ignored) {
            return "";
        }
    }

    /* ---------------- 页面数据 ---------------- */

    public static JSONObject summary(Context c) {
        JSONObject out = new JSONObject();
        try {
            JSONArray a = list(c);
            JSONArray items = new JSONArray();
            Map<String, Integer> count = new HashMap<>();
            long cutoff = System.currentTimeMillis() - 24 * 3600000L;
            for (int i = a.length() - 1; i >= 0 && items.length() < 40; i--) {
                JSONObject o = a.optJSONObject(i);
                if (o == null || o.optLong("ts", 0) < cutoff) continue;
                items.put(o);
            }
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null || o.optLong("ts", 0) < cutoff) continue;
                String cat = o.optString("cat", "其他");
                count.put(cat, count.getOrDefault(cat, 0) + 1);
            }
            JSONArray picks = new JSONArray();
            for (Map.Entry<String, Integer> e : count.entrySet()) {
                JSONObject p = new JSONObject();
                p.put("cat", e.getKey());
                p.put("count", e.getValue());
                picks.put(p);
            }
            out.put("items", items);
            out.put("picks", picks);
            out.put("remark", remark(c));
            out.put("count", a.length());
            out.put("enabled", Cfg.load(c).b("watchApp", true));
            out.put("appCount", Sg.readObj(c, "app_look.json").optInt("appCount", 0));
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** 首装时把「这个应用长什么样」记一笔；之后升级只更新版本号，不重复打扰。 */
    public static void firstLook(Context c) {
        try {
            JSONObject o = Sg.readObj(c, "app_look.json");
            boolean first = o.optLong("firstAt", 0) <= 0;
            if (!first && Ver.NAME.equals(o.optString("version", ""))) return;
            if (first) o.put("firstAt", System.currentTimeMillis());
            o.put("version", Ver.NAME);
            o.put("at", System.currentTimeMillis());
            // 记住这台手机装了哪些能点开的应用，之后提到应用名时她不会完全陌生
            try {
                PackageManager pm = c.getPackageManager();
                Intent main = new Intent(Intent.ACTION_MAIN);
                main.addCategory(Intent.CATEGORY_LAUNCHER);
                List<ResolveInfo> list = pm.queryIntentActivities(main, 0);
                JSONArray names = new JSONArray();
                for (int i = 0; i < list.size() && names.length() < 60; i++) {
                    ResolveInfo ri = list.get(i);
                    if (ri == null || ri.activityInfo == null) continue;
                    String label = ri.loadLabel(pm).toString().trim();
                    if (!label.isEmpty()) names.put(label);
                }
                o.put("appCount", list.size());
                o.put("apps", names);
            } catch (Throwable ignored) {
            }
            Sg.writeObj(c, "app_look.json", o);
        } catch (Throwable ignored) {
        }
    }

    /** 吐槽的冷却：45 分钟最多一次，且最近半小时确实有动作。 */
    public static String maybeRemark(Context c) {
        try {
            if (!Cfg.load(c).b("watchApp", true)) return "";
            long now = System.currentTimeMillis();
            if (now - lastRemarkAt < 45 * 60000L) return "";
            JSONArray a = list(c);
            JSONObject last = a.length() == 0 ? null : a.optJSONObject(a.length() - 1);
            boolean fresh = last != null && now - last.optLong("ts", 0) <= 30 * 60000L;
            if (!fresh) return "";
            String line = remark(c);
            if (line.isEmpty()) return "";
            lastRemarkAt = now;
            return line;
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String clean(String s, int max) {
        if (s == null) return "";
        String t = s.replace("\r", "").replace("\n", " ").trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    /** 把设置键翻译成她能说出口的中文名。 */
    public static String cfgLabel(String key) {
        if (key == null) return "";
        switch (key) {
            case "speakCoolSec": return "主动说话冷却";
            case "screenScanSec": return "屏幕识别频率";
            case "proactive": return "主动说话开关";
            case "proactiveMin": return "主动说话间隔";
            case "greetEnabled": return "主动问候";
            case "notifyAi": return "通知话术 AI";
            case "realtimeEnabled": return "实时提醒";
            case "realtimeSpeak": return "提醒朗读";
            case "watchApp": return "应用感知";
            case "ledgerAuto": return "自动记账";
            case "msgEnabled": return "消息提醒";
            case "parcelEnabled": return "快递提醒";
            case "travelEnabled": return "出行提醒";
            case "calEnabled": return "日程提醒";
            case "a11y": return "无障碍服务";
            case "ttsEnabled": return "语音朗读";
            case "ttsRate": return "语速";
            case "ttsPitch": return "音调";
            case "bubbleSec": return "气泡停留时间";
            case "opacity": return "桌宠不透明度";
            case "sizeDp": return "桌宠大小";
            case "aiActionLines": return "动作 AI 台词";
            case "memoryAuto": return "自动记忆";
            case "traitEvolve": return "性格演化";
            case "lock": return "桌宠锁定";
            case "petSound": return "点击音效";
            case "musicAware": return "音乐感知";
            case "lyricBubble": return "头顶歌词气泡";
            case "lyricGapSec": return "歌词换句间隔";
            case "musicAi": return "AI 认识歌曲";
            case "skinId": return "角色皮肤";
            case "keepAlive": return "后台保活";
            default: return key;
        }
    }
}
