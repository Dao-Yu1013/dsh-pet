package com.dshpet.android;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Prevents an explicit timed browsing request from falling through to a pretend AI claim. */
final class BrowsePluginGate {
    private static final Pattern DURATION = Pattern.compile("(\\d{1,3}|半|一|两|二|三|四|五|六|七|八|九|十)\\s*(分钟|分|小时|钟头)");

    private BrowsePluginGate() { }

    /** Returns a user-facing clarification when the requested browsing capability is unavailable. */
    static String missingPluginReply(Context c, String text) {
        if (!isBrowsingRequest(text)) return null;
        if (hasNativeBrowser(c, category(text))) return null;
        int minutes = durationMinutes(text);
        if (minutes <= 0) return "可以安排，不过需要先说一下时长，比如‘去抖音刷10分钟’。";
        return "我还没找到已安装、能实际浏览并核对画面内容的插件。要我帮你写一个吗？";
    }

    private static boolean isBrowsingRequest(String text) {
        if (text == null) return false;
        String s = text.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        boolean target = s.contains("抖音") || s.contains("douyin") || s.contains("哔站")
                || s.contains("b站") || s.contains("bilibili") || s.contains("小说");
        boolean action = s.contains("刷") || s.contains("看") || s.contains("读") || s.contains("逛");
        return target && action && (s.contains("去") || s.contains("自己") || s.contains("帮我")
                || s.contains("让她") || s.contains("让你") || s.contains("希望") || s.contains("自动"));
    }

    private static String category(String text) {
        String s = text == null ? "" : text.toLowerCase(Locale.ROOT);
        return s.contains("小说") ? "reader" : "video";
    }

    private static int durationMinutes(String text) {
        Matcher m = DURATION.matcher(text == null ? "" : text);
        if (!m.find()) return 0;
        String n = m.group(1);
        boolean hours = m.group(2).contains("小时") || m.group(2).contains("钟头");
        if ("半".equals(n)) return hours ? 30 : 1;
        int value;
        if ("一".equals(n)) value = 1;
        else if ("两".equals(n) || "二".equals(n)) value = 2;
        else if ("三".equals(n)) value = 3;
        else if ("四".equals(n)) value = 4;
        else if ("五".equals(n)) value = 5;
        else if ("六".equals(n)) value = 6;
        else if ("七".equals(n)) value = 7;
        else if ("八".equals(n)) value = 8;
        else if ("九".equals(n)) value = 9;
        else if ("十".equals(n)) value = 10;
        else {
            try { value = Integer.parseInt(n); } catch (Throwable ignored) { return 0; }
        }
        return hours ? value * 60 : value;
    }

    private static boolean hasNativeBrowser(Context c, String category) {
        try {
            JSONArray plugins = Plugins.list(c);
            for (int i = 0; i < plugins.length(); i++) {
                JSONObject p = plugins.optJSONObject(i);
                if (p == null || !p.optBoolean("native") || !p.optBoolean("enabled")
                        || !"running".equals(p.optString("runtimeState"))) continue;
                String id = p.optString("id", "").toLowerCase(Locale.ROOT);
                String name = p.optString("name", "").toLowerCase(Locale.ROOT);
                String desc = p.optString("description", "").toLowerCase(Locale.ROOT);
                if (id.contains("browse")) return true;
                if ("reader".equals(category)
                        && (id.contains("reader") || id.contains("novel") || id.contains("book"))) return true;
                if ("video".equals(category)
                        && (id.contains("douyin") || id.contains("bilibili") || id.contains("video"))) return true;
                JSONArray caps = p.optJSONArray("capabilities");
                for (int k = 0; caps != null && k < caps.length(); k++) {
                    String cap = caps.optString(k, "").toLowerCase(Locale.ROOT);
                    if ("guided_browsing".equals(cap)
                            || ("reader".equals(category) && "novel_browsing".equals(cap))
                            || ("video".equals(category) && "media_browsing".equals(cap))) return true;
                }
                boolean targetName = "reader".equals(category)
                        ? name.contains("小说") || name.contains("阅读") || name.contains("看书")
                        : name.contains("抖音") || name.contains("哔站") || name.contains("b站")
                                || name.contains("视频浏览");
                if (targetName && (desc.contains("滑动") || desc.contains("阅读")
                        || desc.contains("刷视频") || desc.contains("浏览"))) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }
}
