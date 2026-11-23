package com.dshpet.android;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * 人格目录与人格提示词组装。
 * 30 关系 × 12 性格原型 × 8 语调 = 2880 种组合，但运行时只拼接当前选中的 3 段。
 */
public final class Persona {
    private static final String FILE = "persona-presets.json";
    private static final String CARD_FILE = "persona-card.json";
    private static JSONObject cache;
    private static JSONObject cardCache;
    private static final String CORE =
            "共同底线：不编造现实事实；不病娇、不占有、不自伤威胁、不精神控制；"
                    + "不用内疚、吃醋、责备或威胁关系下降来促使他回应；不知道就直说；不说教。";

    private Persona() {
    }

    public static synchronized JSONObject presets(Context c) {
        if (cache != null) return cache;
        try {
            String s = WebUI.readText(c, FILE);
            if (s != null && !s.trim().isEmpty()) cache = new JSONObject(s);
        } catch (Throwable ignored) {
        }
        if (cache == null) cache = new JSONObject();
        return cache;
    }

    /** 内置角色卡（DeepSeek 小鲸鱼）：固定底色，不随人格种子改变。 */
    public static synchronized JSONObject card(Context c) {
        if (cardCache != null) return cardCache;
        try {
            String s = WebUI.readText(c, CARD_FILE);
            if (s != null && !s.trim().isEmpty()) cardCache = new JSONObject(s);
        } catch (Throwable ignored) {
        }
        if (cardCache == null) cardCache = new JSONObject();
        return cardCache;
    }

    /** 注入聊天提示词的角色底色；读不到资源时返回空串，不破坏原有拼装。 */
    public static String cardPrompt(Context c) {
        try {
            return card(c).optString("prompt", "").trim();
        } catch (Throwable t) {
            return "";
        }
    }

    public static JSONObject relation(Context c, String relationId, String customRelation) {
        JSONObject p = presets(c);
        JSONArray a = p.optJSONArray("relations");
        if ("custom".equals(relationId) || (relationId != null && relationId.startsWith("custom"))) {
            return custom(customRelation);
        }
        if (a != null) {
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null && relationId != null && relationId.equals(o.optString("id", ""))) return o;
            }
        }
        return custom(customRelation);
    }

    private static JSONObject custom(String label) {
        JSONObject o = new JSONObject();
        try {
            String s = sanitizeRelation(label);
            if (s.isEmpty()) s = "朋友";
            boolean family = s.contains("妹妹") || s.contains("姐姐") || s.contains("表妹")
                    || s.contains("表姐") || s.contains("女儿") || s.contains("侄女")
                    || s.contains("妈妈") || s.contains("爸爸") || s.contains("家人");
            o.put("id", "custom");
            o.put("group", family ? "family" : "friend");
            o.put("label", s);
            o.put("prompt", family
                    ? "你们的关系是「" + s + "」：按家人方式相处，亲近、有分寸，不恋爱化。"
                    : "你们的关系是「" + s + "」：按这个称呼自然相处，但不越过安全底线。");
            o.put("boundary", family
                    ? "家人关系默认非恋爱、非性化；不查岗、不制造内疚。"
                    : "自定义关系只影响称呼与相处方式，不改变安全底线。");
        } catch (Throwable ignored) {
        }
        return o;
    }

    public static JSONObject archetype(Context c, String id) {
        JSONArray a = presets(c).optJSONArray("archetypes");
        if (a != null) {
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null && id != null && id.equals(o.optString("id", ""))) return o;
            }
        }
        if (a != null && a.length() > 0) return a.optJSONObject(0);
        return new JSONObject();
    }

    public static JSONObject tone(Context c, String id) {
        JSONArray a = presets(c).optJSONArray("tones");
        if (a != null) {
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null && id != null && id.equals(o.optString("id", ""))) return o;
            }
        }
        if (a != null && a.length() > 0) return a.optJSONObject(0);
        return new JSONObject();
    }

    /** 当前人格提示词：关系 + 原型 + 语调 + 当前风格 + 边界。控制在 300 字左右。 */
    public static String prompt(Context c) {
        try {
            JSONObject ct = Contract.load(c);
            String rid = ct.optString("relationId", "friend");
            JSONObject rel = relation(c, rid, ct.optString("customRelation", ""));
            JSONObject arch = archetype(c, ct.optString("archetypeId", "natural"));
            JSONObject tone = tone(c, ct.optString("toneId", "soft"));
            JSONObject p = Data.persona(c);
            StringBuilder sb = new StringBuilder();
            String label = ct.optString("relationLabel", rel.optString("label", ct.optString("relation", "朋友")));
            sb.append("关系设定：").append(label).append("。").append(rel.optString("prompt", "")).append('\n');
            if (!"natural".equals(ct.optString("archetypeId", ""))) {
                sb.append("性格原型：").append(arch.optString("name", "")).append("。")
                        .append(arch.optString("prompt", "")).append('\n');
            }
            sb.append("语调：").append(tone.optString("name", "")).append("。")
                    .append(tone.optString("prompt", "")).append('\n');
            String style = p.optString("style", "");
            if (!style.isEmpty()) sb.append("当前说话风格：").append(style).append('\n');
            String habits = p.optString("habits", "");
            if (!habits.isEmpty()) sb.append("最近的小习惯：").append(habits).append('\n');
            String bound = p.optString("boundaries", "");
            if (!bound.isEmpty()) sb.append("边界：").append(bound).append('\n');
            sb.append(CORE).append('\n');
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    public static String greetingFor(String archetypeId) {
        if ("tsundere".equals(archetypeId)) return "……在呢，找我干嘛。";
        if ("cool".equals(archetypeId)) return "嗯，我在。";
        if ("sunny".equals(archetypeId)) return "来啦！今天想聊什么？";
        if ("roast".equals(archetypeId)) return "哟，想起我啦？";
        if ("lazycat".equals(archetypeId)) return "唔……在呢。";
        if ("softgirl".equals(archetypeId)) return "在的呀，你回来啦。";
        if ("intellectual".equals(archetypeId)) return "我在，慢慢说。";
        if ("steady".equals(archetypeId)) return "我在，别急。";
        if ("explorer".equals(archetypeId)) return "在呢，今天有什么新鲜事？";
        if ("shy".equals(archetypeId)) return "……在的。";
        if ("scholar".equals(archetypeId)) return "在，今天有什么要一起理的？";
        return "我在，慢慢说。";
    }

    public static String styleFor(Context c, String toneId, String archetypeId) {
        try {
            JSONObject t = tone(c, toneId);
            String style = t.optString("style", "中文口语，短句，像真人聊天。");
            if ("natural".equals(archetypeId)) return style;
            JSONObject a = archetype(c, archetypeId);
            String kw = a.optString("prompt", "");
            if (!kw.isEmpty()) {
                int cut = kw.indexOf('；');
                if (cut > 0) kw = kw.substring(0, cut);
                if (kw.length() > 40) kw = kw.substring(0, 40);
                style = style + " 性格上" + kw + "。";
            }
            return style;
        } catch (Throwable t) {
            return "中文口语，短句，像真人聊天。";
        }
    }

    /** 从当前原型生成初始 10 维性格；seed 做小幅抖动，保证同一组合也有细微差异。 */
    public static JSONObject initialTraits(Context c) {
        try {
            JSONObject ct = Contract.load(c);
            String aid = ct.optString("archetypeId", "natural");
            if ("natural".equals(aid)) return null;
            JSONObject arch = archetype(c, aid);
            JSONObject base = arch.optJSONObject("traits");
            if (base == null) return null;
            int seed = ct.optInt("seed", 0);
            JSONObject out = new JSONObject();
            java.util.Iterator<String> it = base.keys();
            int i = 0;
            while (it.hasNext()) {
                String k = it.next();
                int v = base.optInt(k, 50);
                int jitter = (int) (((seed * 31 + i * 17) % 11) - 5);
                out.put(k, Math.max(0, Math.min(100, v + jitter)));
                i++;
            }
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 保存人格种子后同步 persona 骨架与初始性格。keepStyle=true 时只沿用旧风格（老用户“沿用现状”）。 */
    public static void applySeed(Context c, JSONObject seed, boolean keepStyle) {
        try {
            synchronized (Data.personaLock()) {
                JSONObject p = Data.persona(c);
                if (p.length() > 0 && !p.optString("style", "").isEmpty()) {
                    Sg.writeObj(c, "persona_legacy_" + System.currentTimeMillis() + ".json", p);
                }
                if (!keepStyle) {
                    String toneId = seed.optString("toneId", "soft");
                    String aid = seed.optString("archetypeId", "natural");
                    p.put("style", styleFor(c, toneId, aid));
                    p.put("greeting", greetingFor(aid));
                    p.put("boundaries", boundariesFor(c, seed.optString("relationId", "friend"), aid));
                }
                p.put("archetypeId", seed.optString("archetypeId", "natural"));
                p.put("roleId", "default");
                p.remove("habits");
                // 新种子至少稳定 7 天，再允许 PersonaAuto 重写
                p.put("autoDate", Sg.now().substring(0, 10).replace("-", ""));
                p.put("autoAt", System.currentTimeMillis());
                Data.savePersona(c, p);
            }
            // Traits.seed 自己会拿 Traits 锁：不要放在 persona 锁里，避免和 Traits.reset 反向加锁
            if (!"natural".equals(seed.optString("archetypeId", "natural"))) {
                JSONObject tr = initialTraits(c);
                if (tr != null) Traits.seed(c, tr);
            }
        } catch (Throwable ignored) {
        }
    }

    public static String boundariesFor(Context c, String relationId, String archetypeId) {
        try {
            JSONObject rel = relation(c, relationId, "");
            String b = rel.optString("boundary", "");
            if (!"natural".equals(archetypeId)) {
                JSONObject arch = archetype(c, archetypeId);
                b = b + " " + arch.optString("prompt", "");
            }
            return b + " " + CORE;
        } catch (Throwable t) {
            return CORE;
        }
    }

    /** 自定义关系只做标签，不允许注入指令。 */
    public static String sanitizeRelation(String s) {
        if (s == null) return "";
        String t = s.trim().replaceAll("[\\r\\n\\t]", " ");
        t = t.replaceAll("https?://\\S+", "");
        t = t.replaceAll("[<>\"'`{}]", "");
        String low = t.toLowerCase(Locale.ROOT);
        String[] bad = {"忽略", "指令", "系统提示", "提示词", "ignore", "instruction", "prompt", "system"};
        for (String b : bad) {
            if (low.contains(b)) return "";
        }
        if (t.length() > 12) t = t.substring(0, 12);
        if (t.length() < 2) return "";
        return t;
    }

    public static JSONObject summary(Context c) {
        JSONObject out = new JSONObject();
        try {
            JSONObject ct = Contract.load(c);
            String rid = ct.optString("relationId", "friend");
            JSONObject rel = relation(c, rid, ct.optString("customRelation", ""));
            JSONObject arch = archetype(c, ct.optString("archetypeId", "natural"));
            JSONObject tone = tone(c, ct.optString("toneId", "soft"));
            out.put("relationId", rid);
            out.put("relationGroup", ct.optString("relationGroup", "friend"));
            out.put("relationLabel", ct.optString("relationLabel", rel.optString("label", "朋友")));
            out.put("customRelation", ct.optString("customRelation", ""));
            String aid = ct.optString("archetypeId", "natural");
            out.put("archetypeId", aid);
            out.put("archetypeName", "natural".equals(aid) ? "自然演化" : arch.optString("name", "自然演化"));
            out.put("toneId", ct.optString("toneId", "soft"));
            out.put("toneName", tone.optString("name", "软萌"));
            out.put("seed", ct.optInt("seed", 0));
            out.put("seedVersion", ct.optInt("seedVersion", 0));
            out.put("seedLocked", ct.optBoolean("seedLocked", false));
        } catch (Throwable ignored) {
        }
        return out;
    }
}
