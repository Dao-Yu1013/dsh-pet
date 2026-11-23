package com.dshpet.android;

import android.content.Context;

import org.json.JSONObject;

/**
 * 初始契约：首次打开应用时填写的内容，永久不可更改。
 * 只作为 prompt 里的"原点背景"存在，不强制她的行为——实际长成什么样由相处决定。
 */
public final class Contract {
    private static final String FILE = "initial_contract.json";

    public static final String[] TONES = {"软萌", "直爽", "冷淡", "俏皮"};
    public static final String[] RELATIONS = {"朋友", "恋人", "损友", "家人", "导师"};

    private Contract() {
    }

    public static synchronized JSONObject load(Context c) {
        JSONObject o = Sg.readObj(c, FILE);
        try {
            if (!o.has("userName")) o.put("userName", "");
            if (!o.has("herName")) o.put("herName", "");
            if (!o.has("tone")) o.put("tone", "软萌");
            if (!o.has("relation")) o.put("relation", "朋友");
            if (!o.has("createdAt")) o.put("createdAt", "");
            if (!o.has("roleId")) o.put("roleId", "default");
        } catch (Throwable ignored) {
        }
        return o;
    }

    /** 是否已经正式填写（跳过不算）。填过之后界面永久隐藏。 */
    public static boolean isSet(Context c) {
        JSONObject o = Sg.readObj(c, FILE);
        String created = o.optString("createdAt", "");
        return !created.isEmpty() && !pending(c);
    }

    /**
     * 是否是「跳过但还没正式填写」。
     * 兼容旧版本：旧文件没有 pending 字段，但 createdAt 有值，说明当时点过跳过，
     * 允许补填一次；补填保存后 pending=false，之后永久不可更改。
     */
    public static boolean pending(Context c) {
        JSONObject o = Sg.readObj(c, FILE);
        String created = o.optString("createdAt", "");
        if (created.isEmpty()) return false;
        if (!o.has("pending")) return true;
        return o.optBoolean("pending", false);
    }

    /** 保存初始契约（一次性，之后不再允许修改）。 */
    public static synchronized void save(Context c, String userName, String herName, String tone, String relation) {
        saveSeed(c, userName, herName, tone, relation, null);
    }

    /**
     * 保存 v2 人格种子。旧版覆盖安装时允许再选一次，保存后永久锁定；
     * createdAt 保留旧值，认识天数不会重置。
     */
    public static synchronized void saveSeed(Context c, String userName, String herName, String tone,
                                             String relation, JSONObject seed) {
        JSONObject old = load(c);
        JSONObject o = new JSONObject();
        try {
            o.put("userName", userName == null ? "" : userName.trim());
            o.put("herName", (herName == null || herName.trim().isEmpty()) ? "蓝色大肥鱼" : herName.trim());
            o.put("tone", (tone == null || tone.trim().isEmpty()) ? "软萌" : tone.trim());
            o.put("relation", (relation == null || relation.trim().isEmpty()) ? "朋友" : relation.trim());
            String created = old.optString("createdAt", "");
            o.put("createdAt", created.isEmpty() ? Sg.now().substring(0, 10) : created);
            o.put("roleId", seed == null ? "default" : seed.optString("roleId", "default"));
            o.put("pending", false);
            if (seed != null) {
                String[] keys = {"relationGroup", "relationId", "relationLabel", "customRelation",
                        "archetypeId", "toneId", "seed"};
                for (String k : keys) if (seed.has(k)) o.put(k, seed.opt(k));
                o.put("seedVersion", 2);
                o.put("seedLocked", true);
            } else {
                o.put("seedVersion", 1);
                o.put("seedLocked", false);
            }
            Sg.writeObj(c, FILE, o);
        } catch (Throwable ignored) {
        }
    }

    /** 是否还允许做这一次人格种子填写/重选。 */
    public static boolean canEditSeed(Context c) {
        return !isSet(c) || needsSeed(c);
    }

    /** 旧版用户：只要已经有过契约、且还没写过 v2 种子，就需要一次人格升级。 */
    public static boolean needsSeed(Context c) {
        JSONObject o = load(c);
        if (o.optString("createdAt", "").isEmpty()) return false;
        int v = o.optInt("seedVersion", 0);
        return v >= 0 && v < 2;
    }

    public static String relationId(String relation) {
        if (relation == null) return "friend";
        switch (relation) {
            case "恋人": return "lover";
            case "损友": return "roast_friend";
            case "家人": return "family_general";
            case "导师": return "mentor";
            default: return "friend";
        }
    }

    public static String relationGroup(String relation) {
        if ("恋人".equals(relation)) return "lover";
        if ("家人".equals(relation)) return "family";
        if ("导师".equals(relation)) return "mentor";
        return "friend";
    }

    public static String toneId(String tone) {
        if ("直爽".equals(tone)) return "direct";
        if ("冷淡".equals(tone)) return "cool";
        if ("俏皮".equals(tone)) return "playful";
        return "soft";
    }

    /** 点「跳过」：先记下默认骨架，但标记 pending，首页会保留「继续填写」入口。 */
    public static synchronized void skip(Context c) {
        JSONObject o = load(c);
        try {
            o.put("pending", true);
            // -1 = 新用户主动跳过，不触发「旧版人格升级」自动弹窗；之后仍可从首页补填
            o.put("seedVersion", -1);
            if (o.optString("createdAt", "").isEmpty()) o.put("createdAt", Sg.now().substring(0, 10));
            Sg.writeObj(c, FILE, o);
        } catch (Throwable ignored) {
        }
    }

    /** 写进系统提示的原点背景。 */
    public static String prompt(Context c) {
        if (!isSet(c)) return "";
        JSONObject o = load(c);
        StringBuilder sb = new StringBuilder();
        String relation = o.optString("relation", "朋友");
        String herName = o.optString("herName", "蓝色大肥鱼");
        sb.append("你们刚认识的时候，他希望你们的关系是「").append(relation).append("」。\n");
        sb.append("他给你起的名字是「").append(herName).append("」。\n");
        String userName = o.optString("userName", "");
        if (!userName.isEmpty()) sb.append("他希望你叫他「").append(userName).append("」。\n");
        sb.append("这只是他最初的期许，你们实际处成什么样，由你们的相处决定，不要被这个限定死。\n");
        return sb.toString();
    }

    public static JSONObject summary(Context c) {
        JSONObject out = new JSONObject();
        try {
            JSONObject o = load(c);
            out.put("isSet", isSet(c));
            out.put("pending", pending(c));
            out.put("userName", o.optString("userName", ""));
            out.put("herName", o.optString("herName", ""));
            out.put("tone", o.optString("tone", "软萌"));
            out.put("relation", o.optString("relation", "朋友"));
            String rel = o.optString("relation", "朋友");
            String relId = o.optString("relationId", relationId(rel));
            out.put("relationId", relId);
            out.put("relationGroup", o.optString("relationGroup", relationGroup(rel)));
            out.put("relationLabel", o.optString("relationLabel", rel));
            out.put("customRelation", o.optString("customRelation", ""));
            out.put("archetypeId", o.optString("archetypeId", "natural"));
            out.put("toneId", o.optString("toneId", toneId(o.optString("tone", "软萌"))));
            out.put("seed", o.optInt("seed", 0));
            out.put("seedVersion", o.optInt("seedVersion", 0));
            out.put("seedLocked", o.optBoolean("seedLocked", false));
            out.put("needsSeed", needsSeed(c));
            out.put("roleId", o.optString("roleId", "default"));
            out.put("createdAt", o.optString("createdAt", ""));
        } catch (Throwable ignored) {
        }
        return out;
    }
}
