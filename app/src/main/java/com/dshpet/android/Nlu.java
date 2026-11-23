package com.dshpet.android;

import android.content.Context;
import android.content.Intent;

import org.json.JSONObject;

import java.util.Calendar;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 本地意图：提醒、待办、专注、静音、心情。零 token。 */
public final class Nlu {
    private static final Pattern MIN = Pattern.compile("(\\d{1,3})\\s*分钟后");
    private static final Pattern HOUR = Pattern.compile("(\\d{1,2})\\s*小时后");
    private static final Pattern CLOCK = Pattern.compile("(\\d{1,2})[:：](\\d{2})");

    private Nlu() {
    }

    public static JSONObject handle(Context c, String text) {
        JSONObject out = new JSONObject();
        if (text == null) return out;
        String t = text.trim().replaceFirst("^(?:现在)?(?:没雨|没有雨|不下雨|没有下雨)[，,。\\s]*(?:但|不过)?[，,。\\s]*", "");
        if (t.isEmpty()) return out;
        try {
            // 设备状态：只有用户问到才读一次，平时不轮询
            String device = DeviceAsk.answerIfAsked(c, t);
            if (device != null) {
                out.put("handled", true);
                out.put("reply", device);
                return out;
            }
            if (t.matches("^(静音|别说话|安静一下).*")) {
                Policy.setQuiet(c, true);
                out.put("handled", true);
                out.put("reply", "好，我先安静，需要我时再叫我。");
                return out;
            }
            if (t.matches("^(取消静音|可以说话了|解除静音).*")) {
                Policy.setQuiet(c, false);
                out.put("handled", true);
                out.put("reply", "又能陪你说话了。");
                return out;
            }
            if (t.matches("^(藏起来|躲一下|收起).*")) {
                PetSvc.send(c, PetSvc.A_HIDE, null, null);
                out.put("handled", true);
                out.put("reply", "我先躲一下。");
                return out;
            }
            if (t.matches("^(出来|叫出来|出现).*")) {
                PetSvc.send(c, PetSvc.A_SHOW, null, null);
                out.put("handled", true);
                out.put("reply", "我回来啦。");
                return out;
            }
            if (t.matches("^(喂食|吃饭|投喂).*")) {
                PetSvc.send(c, PetSvc.A_FEED, null, null);
                out.put("handled", true);
                out.put("reply", "谢谢，我开动啦。");
                return out;
            }
            // 附近美食、商品、出行等开放式请求交给 Brain 和模型结合聊天记忆来理解；
            // 不再在这里用固定话术截断成一条“打开地图”链接。
            if (t.matches("^(停止专注|结束专注|停止番茄钟).*")) {
                PetSvc.send(c, PetSvc.A_POMO_STOP, null, null);
                out.put("handled", true);
                out.put("reply", "专注结束，休息一下。");
                return out;
            }
            Matcher pm = Pattern.compile("^(开始)?(专注|番茄钟)\\s*(\\d{1,3})?").matcher(t);
            if (pm.find() && (t.contains("专注") || t.startsWith("番茄钟"))) {
                int min = 25;
                try {
                    if (pm.group(3) != null) min = Integer.parseInt(pm.group(3));
                } catch (Throwable ignored) {
                }
                Intent i = new Intent(c, PetSvc.class).setAction(PetSvc.A_POMO_START)
                        .putExtra("work", Math.max(1, Math.min(180, min))).putExtra("rest", 5);
                try {
                    c.startService(i);
                } catch (Throwable ignored) {
                }
                out.put("handled", true);
                out.put("reply", "开始专注 " + min + " 分钟，我陪着你。");
                return out;
            }
            String mood = null;
            if (t.matches("^(我)?(有点|好|很)?(累|疲惫).*")) mood = "tired";
            else if (t.matches("^(我)?(有点|好|很)?(烦|烦死|生气).*")) mood = "annoyed";
            else if (t.matches("^(我)?(有点|好|很)?(开心|高兴).*")) mood = "happy";
            else if (t.matches("^(我)?(很|在)?(专注|认真).*")) mood = "focused";
            if (mood != null && t.length() <= 14) {
                MoodLog.log(c, mood, t);
                out.put("handled", true);
                out.put("reply", replyMood(mood));
                return out;
            }
            if (t.startsWith("提醒我") || t.startsWith("记一下") || t.startsWith("添加待办") || t.startsWith("待办")) {
                String body = t.replaceFirst("^(提醒我|记一下|添加待办|待办)", "").trim();
                if (body.isEmpty()) return out;
                long due = parseTime(body);
                if (t.startsWith("提醒我")) {
                    JSONObject extra = null;
                    if (due > 0) {
                        extra = new JSONObject();
                        extra.put("at", due);
                    }
                    Data.addReminder(c, "manual", body, due > 0 ? "到点提醒" : "",
                            "nlu:" + System.currentTimeMillis(), extra);
                } else {
                    Taskbook.add(c, body, "", due, 1, "nlu");
                }
                out.put("handled", true);
                out.put("reply", (due > 0 ? "好，到点我提醒你。"
                        : (t.startsWith("提醒我") ? "我把“" + body + "”放进提醒清单了；这条还没有具体响铃时间。想设到点提醒的话，告诉我几点。" : "记下了。")));
                return out;
            }
        } catch (Throwable ignored) {
        }
        // 没配置模型时才用本地帮助兜底；配置了模型后由 Brain 携带完整知识库回答，避免短话术截断上下文。
        try {
            if (!Cfg.load(c).aiReady()) {
                String guide = AppGuide.answer(c, t);
                if (!guide.isEmpty()) {
                    out.put("handled", true);
                    out.put("reply", guide);
                    return out;
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static long parseTime(String s) {
        Calendar cal = Calendar.getInstance();
        try {
            Matcher m = MIN.matcher(s);
            if (m.find()) {
                cal.add(Calendar.MINUTE, Integer.parseInt(m.group(1)));
                return cal.getTimeInMillis();
            }
            m = HOUR.matcher(s);
            if (m.find()) {
                cal.add(Calendar.HOUR_OF_DAY, Integer.parseInt(m.group(1)));
                return cal.getTimeInMillis();
            }
            if (s.contains("明天")) {
                cal.add(Calendar.DAY_OF_MONTH, 1);
                m = CLOCK.matcher(s);
                if (m.find()) {
                    cal.set(Calendar.HOUR_OF_DAY, Integer.parseInt(m.group(1)));
                    cal.set(Calendar.MINUTE, Integer.parseInt(m.group(2)));
                } else {
                    cal.set(Calendar.HOUR_OF_DAY, 9);
                    cal.set(Calendar.MINUTE, 0);
                }
                return cal.getTimeInMillis();
            }
            if (s.contains("今晚")) {
                m = CLOCK.matcher(s);
                if (m.find()) {
                    cal.set(Calendar.HOUR_OF_DAY, Integer.parseInt(m.group(1)));
                    cal.set(Calendar.MINUTE, Integer.parseInt(m.group(2)));
                } else {
                    cal.set(Calendar.HOUR_OF_DAY, 20);
                    cal.set(Calendar.MINUTE, 0);
                }
                return cal.getTimeInMillis();
            }
            m = CLOCK.matcher(s);
            if (m.find()) {
                cal.set(Calendar.HOUR_OF_DAY, Integer.parseInt(m.group(1)));
                cal.set(Calendar.MINUTE, Integer.parseInt(m.group(2)));
                if (cal.getTimeInMillis() < System.currentTimeMillis()) cal.add(Calendar.DAY_OF_MONTH, 1);
                return cal.getTimeInMillis();
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private static String replyMood(String mood) {
        if ("tired".equals(mood)) return "累了就靠一会儿，我安静陪你。";
        if ("annoyed".equals(mood)) return "烦就先不硬撑，我少说两句。";
        if ("happy".equals(mood)) return "你开心，我也跟着高兴。";
        return "好，我陪你专心。";
    }
}
