package com.dshpet.android;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 通知含义分析：把「应用 / 标题 / 正文」交给模型判断这是什么通知，给一句要点，
 * 结果按通知 key 缓存，同一条只花一次 token。批量小、上限低、有冷却，避免刷屏时反复调用。
 */
public final class NotifAi {
    private static final String FILE = "notif_ai.json";
    private static final int BATCH = 8;
    private static final int MAX_TOKENS = 240;
    private static final long MIN_INTERVAL = 45000L;
    private static final int CACHE_MAX = 240;
    private static volatile long lastAt;
    private static volatile boolean busy;

    private NotifAi() {
    }

    /** 已分析好的标签：{通知key: {t: 类型, s: 要点, ts}}。 */
    public static JSONObject labels(Context c) {
        JSONObject cache = Sg.readObj(c, FILE);
        JSONArray all = NotifyStore.list(c);
        boolean changed = false;
        for (int k = 0; k < all.length(); k++) {
            JSONObject notice = all.optJSONObject(k);
            if (notice == null) continue;
            String key = notice.optString("key", "");
            if (key.isEmpty()) continue;
            if (!"广告营销".equals(notice.optString("kind", ""))
                    && !Notify.isMarketingText(notice.optString("title", ""), notice.optString("text", ""))) continue;
            if (cache.has(key)) {
                cache.remove(key);
                changed = true;
            }
        }
        if (changed) Sg.writeObj(c, FILE, cache);
        return cache;
    }

    /**
     * 挑最多 8 条未读且还没分析过的通知丢给模型，立刻返回现有缓存；
     * 分析完成后再通过 Bus 的 "notifAi" 事件把新缓存推给界面。
     * 没绑定 Key、开关关闭、正在分析、刚调用过、没有新条目时都不产生请求。
     */
    public static JSONObject analyze(Context c) {
        JSONObject cache = labels(c);
        Cfg cfg = Cfg.load(c);
        if (!cfg.b("notifAi", true) || !cfg.aiReady() || busy) return cache;
        long now = System.currentTimeMillis();
        if (now - lastAt < MIN_INTERVAL) return cache;

        JSONArray all = NotifyStore.list(c);
        List<JSONObject> todo = new ArrayList<>();
        boolean removedFalseParcelLabel = false;
        for (int k = 0; k < all.length() && todo.size() < BATCH; k++) {
            JSONObject o = all.optJSONObject(k);
            if (o == null || o.optBoolean("read", false)) continue;
            String key = o.optString("key", "");
            if (key.isEmpty()) continue;
            boolean ad = "广告营销".equals(o.optString("kind", ""))
                    || Notify.isMarketingText(o.optString("title", ""), o.optString("text", ""));
            if (ad) {
                if (cache.has(key)) {
                    cache.remove(key);
                    removedFalseParcelLabel = true;
                }
                continue;
            }
            if (cache.has(key)) continue;
            todo.add(o);
        }
        if (removedFalseParcelLabel) {
            Sg.writeObj(c, FILE, cache);
            Bus.emit("notifAi", cache);
        }
        if (todo.isEmpty()) return cache;

        JSONArray msgs = new JSONArray();
        try {
            JSONObject sys = new JSONObject();
            sys.put("role", "system");
            sys.put("content", "你是通知分拣助手。根据每条通知的应用、标题、正文判断它是什么通知。"
                    + "类型只能从这些里选一个：广告营销、账单扣款、快递物流、外卖餐饮、出行交通、"
                    + "工作事务、家人朋友、验证码、系统更新、其他。再给一句不超过16字的中文要点。"
                    + "不要只因为出现“派送/快递/包裹”就判断为真实物流；“快递员手滑发重”“期待你拆箱”“早餐专享好价，速来”或“你买的东西在路上啦”等玩笑/促销文案应归为广告营销。"
                    + "只有出现明确物流状态、运单号或取件码等证据时，才优先判断为快递物流。"
                    + "只输出 JSON 数组，形如 [{\"i\":0,\"t\":\"快递物流\",\"s\":\"顺丰包裹已到驿站\"}]，不要解释。");
            msgs.put(sys);
            JSONObject user = new JSONObject();
            user.put("role", "user");
            StringBuilder sb = new StringBuilder();
            for (int k = 0; k < todo.size(); k++) {
                JSONObject o = todo.get(k);
                sb.append(k).append(". 应用=").append(clip(o.optString("app", o.optString("pkg", "")), 20))
                        .append(" | 标题=").append(clip(o.optString("title", ""), 40))
                        .append(" | 正文=").append(clip(o.optString("text", ""), 80)).append('\n');
            }
            user.put("content", sb.toString().trim());
            msgs.put(user);
        } catch (Throwable ignored) {
            return cache;
        }

        busy = true;
        lastAt = now;
        Ai.rawChat(c, msgs, MAX_TOKENS, 0.2, new Ai.Cb() {
            @Override
            public void ok(String text) {
                busy = false;
                try {
                    merge(c, todo, pick(text));
                } catch (Throwable ignored) {
                }
            }

            @Override
            public void fail(String err) {
                busy = false;
            }
        });
        return cache;
    }

    /** 把模型结果按序号写回缓存；写之前重读一次，避免覆盖期间新增的其它结果。 */
    private static void merge(Context c, List<JSONObject> todo, JSONArray arr) {
        if (arr == null) return;
        JSONObject cache = labels(c);
        long now = System.currentTimeMillis();
        for (int k = 0; k < arr.length(); k++) {
            JSONObject v = arr.optJSONObject(k);
            if (v == null) continue;
            int i = v.optInt("i", -1);
            if (i < 0 || i >= todo.size()) continue;
            String t = clip(v.optString("t", ""), 8);
            String s = clip(v.optString("s", ""), 24);
            if (t.isEmpty() && s.isEmpty()) continue;
            try {
                JSONObject rec = new JSONObject();
                rec.put("t", t);
                rec.put("s", s);
                rec.put("ts", now);
                cache.put(todo.get(i).optString("key", ""), rec);
            } catch (Throwable ignored) {
            }
        }
        if (cache.length() > CACHE_MAX) cache = prune(cache, now);
        Sg.writeObj(c, FILE, cache);
        Bus.emit("notifAi", cache);
    }

    /** 从回复里抠出 JSON 数组；模型偶尔会包 ```json 或加一句解释，这里只取方括号之间。 */
    private static JSONArray pick(String text) {
        if (text == null) return null;
        int s = text.indexOf('[');
        int e = text.lastIndexOf(']');
        if (s < 0 || e <= s) return null;
        try {
            return new JSONArray(text.substring(s, e + 1));
        } catch (Throwable t) {
            return null;
        }
    }

    /** 缓存过多时按时间保留最近的一批，避免文件无限长。 */
    private static JSONObject prune(JSONObject cache, long now) {
        JSONObject out = new JSONObject();
        try {
            List<String> keys = new ArrayList<>();
            Iterator<String> it = cache.keys();
            while (it.hasNext()) keys.add(it.next());
            keys.sort((a, b) -> Long.compare(cache.optJSONObject(b).optLong("ts", 0),
                    cache.optJSONObject(a).optLong("ts", 0)));
            for (String k : keys) {
                if (out.length() >= CACHE_MAX) break;
                if (now - cache.optJSONObject(k).optLong("ts", 0) > 7 * 86400000L) continue;
                out.put(k, cache.optJSONObject(k));
            }
        } catch (Throwable ignored) {
            return cache;
        }
        return out;
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        s = s.replace('\n', ' ').trim();
        return s.length() > max ? s.substring(0, max) : s;
    }
}
