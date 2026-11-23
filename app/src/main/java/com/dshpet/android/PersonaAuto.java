package com.dshpet.android;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 人设自动演化：每天 0:00 后第一次心跳，根据最近的聊天、记忆与性格，
 * 重写她的说话风格 / 开场白 / 边界。名字永远不动，只由用户手动修改。
 */
public final class PersonaAuto {
    private static final String[] FORBID = {"我是", "人格", "性格类型", "我最近变", "内疚", "吃醋", "关系下降", "你都不理我"};

    private PersonaAuto() {
    }

    public static synchronized void dailyMaybe(Context c) {
        try {
            if (!Cfg.load(c).aiReady()) return;
            if (!Cfg.load(c).b("traitEvolve", true)) return;
            String today = new SimpleDateFormat("yyyyMMdd", Locale.CHINA).format(new Date());
            long lastAt;
            String herName;
            synchronized (Data.personaLock()) {
                JSONObject p = Data.persona(c);
                if (today.equals(p.optString("autoDate", ""))) return;

                // 每天只检查一次；真正重写至少间隔 7 天
                p.put("autoDate", today);
                Data.savePersona(c, p);
                lastAt = p.optLong("autoAt", 0);
                herName = p.optString("name", "她");
            }
            if (lastAt > 0 && System.currentTimeMillis() - lastAt < 7 * 86400000L) return;

            JSONObject tr = Traits.summary(c);
            if (tr.optInt("days", 0) < 3) return;         // 观察天数太少，不下结论
            JSONArray hist = Data.chatList(c, 40);
            if (hist.length() < 8) return;                // 最近没怎么聊，跳过

            StringBuilder talk = new StringBuilder();
            for (int i = 0; i < hist.length(); i++) {
                JSONObject h = hist.optJSONObject(i);
                if (h == null) continue;
                talk.append("user".equals(h.optString("role")) ? "他" : "她")
                        .append("：").append(h.optString("content", "")).append('\n');
            }
            StringBuilder mem = new StringBuilder();
            JSONArray ms = Data.memories(c);
            int take = Math.min(ms.length(), 12);
            for (int i = ms.length() - take; i < ms.length(); i++) {
                JSONObject m = ms.optJSONObject(i);
                if (m != null) mem.append("- ").append(m.optString("text", "")).append('\n');
            }

            JSONArray msgs = new JSONArray();
            JSONObject sys = new JSONObject();
            sys.put("role", "system");
            String card = Persona.cardPrompt(c);
            sys.put("content", "你在维护一个桌面伙伴角色的身份骨架。角色名字是「" + herName
                    + "」。根据最近相处，重写她现在的：说话风格 style（1～2 句，具体可执行）、"
                    + "初次问候 greeting（1 句）、边界 boundaries（1～2 句，必须保留安全底线："
                    + "不编造事实、健康/金钱/法律问题提醒谨慎、不说教；"
                    + "不许用内疚、吃醋、责备或威胁关系下降促使他回应）、"
                    + "最近的小习惯 habits（1 句，20～40 字，写她这段时间的口头禅、吃饭作息或接梗习惯，要具体）。"
                    + "不要改变已经选定的关系、性格原型和语调，只在这个范围内演化表达。"
                    + (card.isEmpty() ? "" : "\n她的固定底色（不可改写，只据此调整表达）：\n"
                    + (card.length() > 800 ? card.substring(0, 800) : card) + "\n")
                    + "当前人格种子：\n" + Persona.prompt(c)
                    + "\n只输出 JSON：{\"style\":\"...\",\"greeting\":\"...\",\"boundaries\":\"...\",\"habits\":\"...\"}，"
                    + "不要输出别的文字，不要写名字，不要提 AI、模型或提示词。");
            msgs.put(sys);
            JSONObject u = new JSONObject();
            u.put("role", "user");
            u.put("content", "最近的对话：\n" + talk + "\n长期记忆：\n" + mem
                    + "\n性格现状：" + tr.optString("note", "暂无")
                    + "\n请输出更新后的身份骨架 JSON。");
            msgs.put(u);

            Ai.rawChat(c, msgs, 320, new Ai.Cb() {
                @Override
                public void ok(String text) {
                    try {
                        int a = text.indexOf('{'), b = text.lastIndexOf('}');
                        if (a < 0 || b <= a) return;
                        JSONObject r = new JSONObject(text.substring(a, b + 1));
                        String style = r.optString("style", "").trim();
                        String greeting = r.optString("greeting", "").trim();
                        String boundaries = r.optString("boundaries", "").trim();
                        String habits = r.optString("habits", "").trim();
                        if (style.isEmpty() && greeting.isEmpty() && boundaries.isEmpty()) return;
                        if (style.length() < 20 || style.length() > 120) return;
                        if (greeting.length() < 2 || greeting.length() > 40) return;
                        if (boundaries.length() > 160) return;
                        // 习惯是可选项：不合格就沿用旧的，不因此否决整次更新
                        if (habits.length() < 6 || habits.length() > 60 || containsAny(habits, FORBID)) habits = "";
                        String all = style + " " + greeting + " " + boundaries;
                        if (containsAny(all, FORBID)) return;
                        synchronized (Data.personaLock()) {
                            JSONObject np = Data.persona(c);
                            String oldStyle = np.optString("style", "");
                            if (!oldStyle.isEmpty()) {
                                double ratio = style.length() / (double) oldStyle.length();
                                if (ratio > 2.2 || ratio < 0.4) return;
                            }
                            np.put("style", style);
                            if (!greeting.isEmpty()) np.put("greeting", greeting);
                            if (!boundaries.isEmpty()) np.put("boundaries", boundaries);
                            if (!habits.isEmpty()) np.put("habits", habits);
                            JSONArray hist = np.optJSONArray("styleHistory");
                            if (hist == null) hist = new JSONArray();
                            JSONObject snap = new JSONObject();
                            snap.put("at", System.currentTimeMillis());
                            snap.put("style", style);
                            snap.put("greeting", np.optString("greeting", ""));
                            snap.put("boundaries", np.optString("boundaries", ""));
                            snap.put("habits", np.optString("habits", ""));
                            hist.put(snap);
                            JSONArray keep = new JSONArray();
                            for (int i = Math.max(0, hist.length() - 5); i < hist.length(); i++) keep.put(hist.opt(i));
                            np.put("styleHistory", keep);
                            np.put("autoAt", System.currentTimeMillis());
                            Data.savePersona(c, np);
                        }
                        Bus.emit("persona", Data.persona(c));
                    } catch (Throwable ignored) {
                    }
                }

                @Override
                public void fail(String err) {
                }
            });
        } catch (Throwable ignored) {
        }
    }

    /** 用户重置性格/重新播种后调用：把自动重写推迟 7 天，避免立刻被覆盖。 */
    public static synchronized void markReset(Context c) {
        try {
            synchronized (Data.personaLock()) {
                JSONObject p = Data.persona(c);
                p.put("autoDate", new SimpleDateFormat("yyyyMMdd", Locale.CHINA).format(new Date()));
                p.put("autoAt", System.currentTimeMillis());
                Data.savePersona(c, p);
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean containsAny(String s, String[] arr) {
        if (s == null) return false;
        for (String k : arr) if (s.contains(k)) return true;
        return false;
    }
}
