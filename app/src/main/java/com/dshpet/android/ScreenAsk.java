package com.dshpet.android;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 判断用户这句话是不是在问屏幕内容。
 * 命中后聊天链路会即时读取一次当前应用窗口的文字，再交给 AI 回答。
 */
public final class ScreenAsk {
    private ScreenAsk() {
    }

    /** 明确指向屏幕/当前页面/这段内容的问法。 */
    private static final String[] STRONG = {
            "屏幕", "页面", "界面", "这上面", "这段文字", "这句话", "这一页", "这页",
            "这篇文章", "这道题", "这张图", "截图", "你现在看到", "你能看到", "看得见",
            "看到我在", "看得出我在", "知道我在", "猜我在", "我在干嘛", "我在做什么",
            "我在看什么", "看得出", "你在看什么", "看看我的手机", "看看我手机", "识别一下",
            "屏幕上", "手机屏幕", "这一屏", "现在在干嘛", "现在干嘛", "现在在干什么",
            "现在在做什么", "在玩什么", "能看见", "看得到", "看得清",
            "说说看到了什么", "看到了什么", "刚才看到什么", "你看到什么了"
    };
    /** 指代词，需要配合疑问词才算。 */
    private static final String[] WEAK = {
            "这个", "这段", "这句", "这篇", "这道", "这里", "上面"
    };
    private static final String[] ASK = {
            "什么", "意思", "写的", "显示", "内容", "翻译", "总结", "识别",
            "看看", "看一下", "念一下", "读一下", "怎么读", "怎么做", "讲什么"
    };
    /** 问亮度/息屏的句子交给设备状态，不要误抓屏。 */
    private static final String[] NOT_SCREEN = {
            "亮度", "多亮", "太亮", "太暗", "好亮", "好暗", "有点暗", "偏暗",
            "亮着", "熄屏", "息屏"
    };

    public static boolean wants(String text) {
        if (text == null) return false;
        String s = text.replaceAll("\\s+", "");
        if (s.isEmpty()) return false;
        for (String k : NOT_SCREEN) {
            if (s.contains(k)) return false;
        }
        for (String k : STRONG) {
            if (s.contains(k)) return true;
        }
        boolean weak = false;
        for (String k : WEAK) {
            if (s.contains(k)) {
                weak = true;
                break;
            }
        }
        if (!weak) return false;
        for (String k : ASK) {
            if (s.contains(k)) return true;
        }
        return false;
    }

    /** 是否明确在问屏幕 / 当前页面；宽泛的「这个是什么」不算。 */
    public static boolean explicit(String text) {
        if (text == null) return false;
        String s = text.replaceAll("\\s+", "");
        if (s.isEmpty()) return false;
        for (String k : NOT_SCREEN) {
            if (s.contains(k)) return false;
        }
        for (String k : STRONG) {
            if (s.contains(k)) return true;
        }
        return false;
    }

    /** Recent explicit screen discussion is the only context that needs an AI screen/chat decision. */
    public static boolean hasRecentScreenContext(Context c) {
        if (c == null) return false;
        try {
            JSONArray history = Data.chatList(c, 12);
            int recentUserTurns = 0;
            long now = System.currentTimeMillis();
            for (int i = history.length() - 1; i >= 0; i--) {
                JSONObject item = history.optJSONObject(i);
                if (item == null || !"user".equals(item.optString("role", ""))) continue;
                if (++recentUserTurns > 3) break;
                long ts = item.optLong("ts", 0);
                if (ts > 0 && now - ts > 10 * 60 * 1000L) break;
                if (explicit(item.optString("content", ""))) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }
}
