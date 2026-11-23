package com.dshpet.android;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 记忆图谱：分类、标签、权重、来源、置信度、状态、时间线、合并、体检。
 *
 * v2 在不改变 memories.json 数组结构的前提下，为每条记忆补齐：
 * type / origin / confidence / status / sourceQuote / validFrom / validTo /
 * lastConfirmedAt / evidenceCount / history / sensitivity / recallCount 等字段。
 * 旧数据在 normalize() 里惰性升级，不删除、不改写原文。
 */
public final class Mem {
    public static final int SCHEMA = 2;
    public static final int MAX_ACTIVE = 600;
    private static final int MAX_TEXT = 120;
    private static final int REVIEW_LIMIT = 6;
    // 识海运行在手机 WebView 主线程。限制初始规模，避免进入时长时间占满 UI。
    // Larger rolling window lets the local constellation grow with long-term memory.
    // The WebView fallback renders these once on canvas, so keep a firm mobile bound.
    private static final int GRAPH_LIMIT = 8000;
    private static final int GRAPH_LINK_LIMIT = 14400;
    private static final String FILE = "memories.json";
    private static final String AUDIT = "mem_audit.jsonl";

    private Mem() {
    }

    public static String catName(String cat) {
        if ("person".equals(cat)) return "人物";
        if ("project".equals(cat)) return "项目";
        if ("preference".equals(cat)) return "偏好";
        if ("event".equals(cat)) return "事件";
        return "其他";
    }

    public static String typeName(String type) {
        if ("fact".equals(type)) return "事实";
        if ("preference".equals(type)) return "偏好";
        if ("person".equals(type)) return "人物";
        if ("project".equals(type)) return "项目";
        if ("event".equals(type)) return "事件";
        if ("plan".equals(type)) return "计划";
        if ("concern".equals(type)) return "惦记";
        if ("commitment".equals(type)) return "承诺";
        if ("observation".equals(type)) return "印象";
        if ("emotion".equals(type)) return "心情";
        if ("daily".equals(type)) return "日常";
        if ("health".equals(type)) return "健康";
        if ("place".equals(type)) return "地点";
        if ("learning".equals(type)) return "学习";
        if ("interest".equals(type)) return "兴趣";
        if ("goal".equals(type)) return "目标";
        if ("experience".equals(type)) return "经历";
        return "记忆";
    }

    public static String statusName(String status) {
        if ("active".equals(status)) return "有效";
        if ("uncertain".equals(status)) return "不确定";
        if ("dormant".equals(status)) return "搁置";
        if ("superseded".equals(status)) return "已被取代";
        if ("archived".equals(status)) return "已归档";
        return status == null ? "" : status;
    }

    /* ---------------- 读取与迁移 ---------------- */

    public static synchronized JSONArray normalize(Context c) {
        JSONArray a = Sg.readArr(c, FILE);
        boolean changed = false;
        for (int k = 0; k < a.length(); k++) {
            JSONObject o = a.optJSONObject(k);
            if (o == null) continue;
            if (upgrade(o)) changed = true;
        }
        if (changed) Sg.writeArr(c, FILE, a);
        return a;
    }

    /** Small, explicit user preference profile for personalization across ordinary chats. */
    public static synchronized String preferencePrompt(Context c, int limit) {
        JSONArray records = normalize(c);
        ArrayList<JSONObject> preferences = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (int i = 0; i < records.length(); i++) {
            JSONObject o = records.optJSONObject(i);
            if (o == null || !"active".equals(o.optString("status", "active"))) continue;
            if (!"preference".equals(o.optString("type", ""))
                    || !"user".equals(o.optString("subject", "user"))
                    || "secret".equals(o.optString("sensitivity", "normal"))) continue;
            long expiresAt = o.optLong("expiresAt", 0L);
            if (expiresAt > 0 && expiresAt <= now) continue;
            if (o.optDouble("confidence", 0.0) < 0.65) continue;
            String text = o.optString("text", "").trim();
            if (!text.isEmpty()) preferences.add(o);
        }
        preferences.sort((a, b) -> {
            int confidence = Double.compare(b.optDouble("confidence", 0.0), a.optDouble("confidence", 0.0));
            if (confidence != 0) return confidence;
            return Long.compare(b.optLong("lastConfirmedAt", b.optLong("ts", 0L)),
                    a.optLong("lastConfirmedAt", a.optLong("ts", 0L)));
        });
        if (preferences.isEmpty()) return "";
        StringBuilder out = new StringBuilder("用户明确表达过的稳定偏好（只在能帮助当前选择时参考；不要无故提起，最新要求优先）：\n");
        int added = 0;
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (JSONObject o : preferences) {
            if (added >= Math.max(1, limit)) break;
            String text = o.optString("text", "").trim();
            if (!seen.add(text)) continue;
            if (text.length() > 140) text = text.substring(0, 140);
            out.append("- ").append(text).append('\n');
            added++;
        }
        return added == 0 ? "" : out.toString();
    }

    /** 清空全部记忆（含归档）与审计日志；人设、聊天与亲密度保留。 */
    public static synchronized void clearAll(Context c) {
        Sg.write(Sg.file(c, FILE), "[]");
        Sg.write(Sg.file(c, AUDIT), "");
        HerWords.clearAll(c);
        CompanionRules.clearAll(c);
    }

    /** 旧记录升级到 v2；返回是否发生了字段补齐。 */
    private static boolean upgrade(JSONObject o) {
        try {
            long now = System.currentTimeMillis();
            long ts = o.optLong("ts", now);
            String text = o.optString("text", "");
            boolean legacy = !o.has("schema") || o.optInt("schema", 1) < SCHEMA;
            boolean changed = legacy;
            if (legacy) {
                String source = o.optString("source", "manual");
                boolean pinned = o.optBoolean("pinned", false);
                boolean heavy = o.optBoolean("heavy", false);
                String type = inferType(o.optString("category", ""), text);
                o.put("type", o.optString("type", type));
                o.put("subject", o.optString("subject", "user"));
                o.put("sourceQuote", o.optString("sourceQuote", ""));
                o.put("sourceRole", o.optString("sourceRole",
                        ("manual".equals(source) || "import".equals(source)) ? "manual" : "user"));
                o.put("origin", o.optString("origin", originFor(source, pinned, heavy)));
                o.put("confidence", o.optDouble("confidence", confidenceFor(source, pinned, heavy)));
                o.put("status", o.optString("status", o.optBoolean("archived", false) ? "archived" : "active"));
                o.put("validFrom", o.optLong("validFrom", ts));
                o.put("validTo", o.optLong("validTo", 0L));
                o.put("observedAt", o.optLong("observedAt", ts));
                o.put("lastConfirmedAt", o.optLong("lastConfirmedAt", ts));
                o.put("evidenceCount", o.optInt("evidenceCount", 1));
                o.put("contradicts", o.optJSONArray("contradicts") == null ? new JSONArray() : o.optJSONArray("contradicts"));
                o.put("supersededBy", o.optString("supersededBy", ""));
                o.put("revision", o.optInt("revision", 1));
                o.put("history", o.optJSONArray("history") == null ? new JSONArray() : o.optJSONArray("history"));
                o.put("sensitivity", o.optString("sensitivity", looksSecret(text) ? "secret" : "normal"));
                o.put("recallCount", o.optInt("recallCount", 0));
                o.put("lastRecalledAt", o.optLong("lastRecalledAt", 0L));
                o.put("createdAt", o.optLong("createdAt", ts));
                o.put("updatedAt", o.optLong("updatedAt", ts));
                o.put("schema", SCHEMA);
            }
            changed |= putIfAbsent(o, "type", inferType(o.optString("category", ""), text));
            changed |= putIfAbsent(o, "subject", "user");
            changed |= putIfAbsent(o, "sourceQuote", "");
            changed |= putIfAbsent(o, "sourceRole", "user");
            changed |= putIfAbsent(o, "origin", "legacy_manual");
            changed |= putIfAbsent(o, "confidence", 0.7);
            if (!o.has("status")) {
                o.put("status", o.optBoolean("archived", false) ? "archived" : "active");
                changed = true;
            }
            changed |= putIfAbsent(o, "validFrom", ts);
            changed |= putIfAbsent(o, "validTo", 0L);
            changed |= putIfAbsent(o, "observedAt", ts);
            changed |= putIfAbsent(o, "lastConfirmedAt", ts);
            changed |= putIfAbsent(o, "evidenceCount", 1);
            changed |= putIfAbsent(o, "contradicts", new JSONArray());
            changed |= putIfAbsent(o, "supersededBy", "");
            changed |= putIfAbsent(o, "revision", 1);
            changed |= putIfAbsent(o, "history", new JSONArray());
            changed |= putIfAbsent(o, "sensitivity", looksSecret(text) ? "secret" : "normal");
            changed |= putIfAbsent(o, "recallCount", 0);
            changed |= putIfAbsent(o, "lastRecalledAt", 0L);
            changed |= putIfAbsent(o, "createdAt", ts);
            changed |= putIfAbsent(o, "updatedAt", ts);
            changed |= putIfAbsent(o, "expiresAt", 0L);
            changed |= putIfAbsent(o, "schema", SCHEMA);
            return changed;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean putIfAbsent(JSONObject o, String key, Object value) {
        if (o.has(key)) return false;
        try {
            o.put(key, value);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String originFor(String source, boolean pinned, boolean heavy) {
        if (heavy || "explicit".equals(source)) return "user_explicit";
        if ("auto".equals(source)) return "legacy_auto";
        if ("merge".equals(source)) return "legacy_merge";
        if ("import".equals(source)) return "legacy_manual";
        return "legacy_manual";
    }

    private static double confidenceFor(String source, boolean pinned, boolean heavy) {
        if (heavy || "explicit".equals(source)) return 0.9;
        if ("auto".equals(source)) return 0.55;
        if ("merge".equals(source)) return 0.65;
        if ("import".equals(source)) return 0.7;
        return 0.8;
    }

    private static String sourceForOrigin(String origin) {
        if ("user_explicit".equals(origin)) return "explicit";
        if ("legacy_auto".equals(origin) || "assistant_inference".equals(origin)
                || "diary_observation".equals(origin)) return "auto";
        if ("legacy_merge".equals(origin)) return "merge";
        if ("import".equals(origin)) return "import";
        if ("manual".equals(origin) || "legacy_manual".equals(origin)) return "manual";
        return "manual";
    }

    /* ---------------- 列表与查询 ---------------- */

    public static JSONArray list(Context c, String query, String category, boolean includeArchived) {
        return listFiltered(c, query, null, null, category, includeArchived);
    }

    public static JSONArray listFiltered(Context c, String query, String type, String status,
                                         String category, boolean includeArchived) {
        JSONArray all = normalize(c);
        JSONArray out = new JSONArray();
        String q = query == null ? "" : query.trim().toLowerCase();
        for (int k = 0; k < all.length(); k++) {
            JSONObject o = all.optJSONObject(k);
            if (o == null) continue;
            String st = o.optString("status", "active");
            if (!includeArchived && "archived".equals(st)) continue;
            if (!includeArchived && "superseded".equals(st)) continue;
            if (status != null && !status.isEmpty() && !"all".equals(status) && !status.equals(st)) continue;
            if (type != null && !type.isEmpty() && !"all".equals(type)
                    && !type.equals(o.optString("type", "fact"))) continue;
            if (category != null && !category.isEmpty() && !"all".equals(category)
                    && !category.equals(o.optString("category", "other"))) continue;
            if (!q.isEmpty()) {
                String text = o.optString("text", "").toLowerCase();
                String tags = o.optString("tags", "");
                String quote = o.optString("sourceQuote", "").toLowerCase();
                if (!text.contains(q) && !tags.toLowerCase().contains(q) && !quote.contains(q)) continue;
            }
            out.put(o);
        }
        return out;
    }

    /** 识海图数据：只读现有 memories.json，不改记忆结构或写入推测关系。 */
    public static synchronized JSONObject graphData(Context c) {
        JSONObject result = new JSONObject();
        try {
            JSONArray all = normalize(c);
            ArrayList<JSONObject> candidates = new ArrayList<>();
            long now = System.currentTimeMillis();
            for (int i = 0; i < all.length(); i++) {
                JSONObject o = all.optJSONObject(i);
                if (o == null || !"active".equals(o.optString("status", "active"))) continue;
                if ("secret".equals(o.optString("sensitivity", "normal"))) continue;
                long expiresAt = o.optLong("expiresAt", 0L);
                if (expiresAt > 0L && expiresAt <= now) continue;
                if (o.optString("id", "").isEmpty() || o.optString("text", "").trim().isEmpty()) continue;
                candidates.add(o);
            }
            candidates.sort((x, y) -> {
                int score = Double.compare(graphImportance(y, now), graphImportance(x, now));
                if (score != 0) return score;
                return Long.compare(y.optLong("lastConfirmedAt", y.optLong("ts", 0L)),
                        x.optLong("lastConfirmedAt", x.optLong("ts", 0L)));
            });

            int total = candidates.size();
            JSONArray nodes = new JSONArray();
            Map<String, List<Integer>> tagOwners = new LinkedHashMap<>();
            Map<String, String> tagLabels = new HashMap<>();
            int count = Math.min(GRAPH_LIMIT, candidates.size());
            for (int i = 0; i < count; i++) {
                JSONObject source = candidates.get(i);
                String text = source.optString("text", "").trim();
                double importance = graphImportance(source, now);
                int value = Math.max(2, Math.min(9, 2 + (int) Math.round(importance * 6.0)));
                JSONArray tags = graphTags(source);
                JSONObject node = new JSONObject();
                node.put("id", source.optString("id", ""));
                node.put("kind", "memory");
                node.put("label", shortGraphText(text, 22));
                node.put("text", text);
                node.put("type", source.optString("type", "fact"));
                node.put("typeLabel", typeName(source.optString("type", "fact")));
                node.put("val", value);
                node.put("importance", Math.round(importance * 100.0) / 100.0);
                node.put("tags", tags);
                node.put("origin", source.optString("origin", "manual"));
                node.put("sourceQuote", source.optString("sourceQuote", ""));
                node.put("evidenceCount", source.optInt("evidenceCount", 1));
                node.put("createdAt", source.optLong("createdAt", source.optLong("ts", 0L)));
                node.put("pinned", source.optBoolean("pinned", false));
                node.put("heavy", source.optBoolean("heavy", false));
                node.put("confidence", source.optDouble("confidence", 0.7));
                node.put("recallCount", source.optInt("recallCount", 0));
                node.put("lastRecalledAt", source.optLong("lastRecalledAt", 0L));
                node.put("updatedAt", source.optLong("updatedAt", source.optLong("ts", 0L)));
                nodes.put(node);
                for (int j = 0; j < tags.length(); j++) {
                    String label = tags.optString(j, "").trim();
                    String key = label.toLowerCase(Locale.ROOT);
                    if (key.isEmpty()) continue;
                    List<Integer> owners = tagOwners.get(key);
                    if (owners == null) {
                        owners = new ArrayList<>();
                        tagOwners.put(key, owners);
                        tagLabels.put(key, label);
                    }
                    owners.add(i);
                }
            }

            // Add only short, locally recorded observations that were actually verified.
            // These are separate from long-term memory and never write back to memories.json.
            JSONArray experiences = ExperienceLog.graphNodes(c, 10);
            int experienceCount = 0;
            for (int i = 0; i < experiences.length(); i++) {
                JSONObject node = experiences.optJSONObject(i);
                if (node == null) continue;
                JSONArray tags = graphTags(node);
                node.put("tags", tags);
                int ownerIndex = nodes.length();
                nodes.put(node);
                experienceCount++;
                for (int j = 0; j < tags.length(); j++) {
                    String label = tags.optString(j, "").trim();
                    String key = label.toLowerCase(Locale.ROOT);
                    if (key.isEmpty()) continue;
                    List<Integer> owners = tagOwners.get(key);
                    if (owners == null) {
                        owners = new ArrayList<>();
                        tagOwners.put(key, owners);
                        tagLabels.put(key, label);
                    }
                    owners.add(ownerIndex);
                }
            }

            // 只依据明确标签或可复现的词面关键词连边，不推断隐含语义关系。
            LinkedHashMap<String, JSONArray> linksByPair = new LinkedHashMap<>();
            for (Map.Entry<String, List<Integer>> entry : tagOwners.entrySet()) {
                List<Integer> owners = entry.getValue();
                if (owners.size() < 2) continue;
                String label = tagLabels.get(entry.getKey());
                // 小主题做完整关系网；常见主题用相邻链路连接，避免 O(n²) 连线和整组断开。
                int pairCount = owners.size() <= 10 ? owners.size() * (owners.size() - 1) / 2 : owners.size() - 1;
                for (int p = 0; p < pairCount; p++) {
                    int a, b;
                    if (owners.size() <= 10) {
                        a = 0;
                        int remaining = p;
                        while (a < owners.size() - 1 && remaining >= owners.size() - a - 1) {
                            remaining -= owners.size() - a - 1;
                            a++;
                        }
                        b = a + 1 + remaining;
                    } else {
                        a = p;
                        b = p + 1;
                    }
                    int left = owners.get(a), right = owners.get(b);
                    String pair = left + ":" + right;
                    JSONArray shared = linksByPair.get(pair);
                    if (shared == null) {
                        if (linksByPair.size() >= GRAPH_LINK_LIMIT) continue;
                        shared = new JSONArray();
                        linksByPair.put(pair, shared);
                    }
                    boolean duplicate = false;
                    for (int t = 0; t < shared.length(); t++) {
                        if (label.equals(shared.optString(t, ""))) { duplicate = true; break; }
                    }
                    if (!duplicate) shared.put(label);
                }
            }
            JSONArray links = new JSONArray();
            for (Map.Entry<String, JSONArray> entry : linksByPair.entrySet()) {
                if (links.length() >= GRAPH_LINK_LIMIT) break;
                String[] pair = entry.getKey().split(":", 2);
                JSONArray shared = entry.getValue();
                StringBuilder relation = new StringBuilder();
                for (int i = 0; i < shared.length(); i++) {
                    if (i > 0) relation.append("、");
                    relation.append(shared.optString(i, ""));
                }
                JSONObject link = new JSONObject();
                link.put("source", nodes.getJSONObject(Integer.parseInt(pair[0])).optString("id", ""));
                link.put("target", nodes.getJSONObject(Integer.parseInt(pair[1])).optString("id", ""));
                link.put("relation", relation.toString());
                link.put("sharedCount", shared.length());
                link.put("relationKind", "shared_tags_or_keywords");
                links.put(link);
            }
            result.put("nodes", nodes);
            result.put("links", links);
            result.put("total", total);
            result.put("shown", count);
            result.put("experiences", experienceCount);
            result.put("shownNodes", nodes.length());
            result.put("sharedLinks", links.length());
            result.put("truncated", total > count);
        } catch (Throwable ignored) {
            try {
                result.put("nodes", new JSONArray());
                result.put("links", new JSONArray());
                result.put("total", 0);
                result.put("shown", 0);
                result.put("experiences", 0);
                result.put("shownNodes", 0);
            } catch (Throwable ignoredAgain) {
            }
        }
        return result;
    }

    private static double graphImportance(JSONObject o, long now) {
        double weight = Math.max(0.0, Math.min(3.0, o.optDouble("weight", 1.0))) / 3.0;
        double confidence = Math.max(0.0, Math.min(1.0, o.optDouble("confidence", 0.7)));
        double recalls = Math.min(1.0, Math.max(0, o.optInt("recallCount", 0)) / 8.0);
        long confirmed = o.optLong("lastConfirmedAt", o.optLong("ts", 0L));
        double days = confirmed <= 0L ? 180.0 : Math.max(0.0, (now - confirmed) / 86400000.0);
        double recent = Math.exp(-days / 180.0);
        return 0.34 * weight + 0.27 * confidence + 0.16 * recalls + 0.13 * recent
                + (o.optBoolean("pinned", false) ? 0.07 : 0.0)
                + (o.optBoolean("heavy", false) ? 0.12 : 0.0);
    }

    private static JSONArray graphTags(JSONObject record) {
        JSONArray result = new JSONArray();
        Set<String> seen = new HashSet<>();
        JSONArray source = record.optJSONArray("tags");
        if (source != null) {
            for (int i = 0; i < source.length() && result.length() < 8; i++) {
                addGraphTag(result, seen, source.optString(i, ""));
            }
        } else {
            String raw = record.optString("tags", "");
            for (String tag : raw.split("[,，;；]")) {
                if (result.length() >= 8) break;
                addGraphTag(result, seen, tag);
            }
        }
        String text = record.optString("text", "");
        JSONArray topics = graphTopics(text);
        for (int i = 0; i < topics.length() && result.length() < 12; i++)
            addGraphTag(result, seen, topics.optString(i, ""));
        JSONArray inferred = autoTags(text);
        for (int i = 0; i < inferred.length() && result.length() < 12; i++)
            addGraphTag(result, seen, inferred.optString(i, ""));
        return result;
    }

    /** 识海专用的可解释关键词补充；只按原文匹配，不写回记忆，也不代表语义推理。 */
    private static JSONArray graphTopics(String text) {
        JSONArray out = new JSONArray();
        if (text == null || text.isEmpty()) return out;
        String[][] terms = {
                {"西安", "西安"}, {"天气", "天气"}, {"下雨", "下雨"}, {"带伞", "带伞"},
                {"QQ", "QQ"}, {"微信", "微信"}, {"AstrBot", "AstrBot"}, {"桌宠", "桌宠"},
                {"识海", "识海"}, {"手机", "手机"}, {"电脑", "电脑"}, {"Android", "Android"},
                {"工作", "工作"}, {"上班", "工作"}, {"项目", "项目"}, {"开发", "开发"},
                {"学习", "学习"}, {"考试", "考试"}, {"学校", "学校"}, {"课程", "课程"},
                {"旅行", "旅行"}, {"旅游", "旅行"}, {"出门", "出行"}, {"回家", "回家"},
                {"家人", "家人"}, {"朋友", "朋友"}, {"生日", "生日"}, {"约定", "约定"},
                {"音乐", "音乐"}, {"歌曲", "音乐"}, {"听歌", "音乐"}, {"游戏", "游戏"},
                {"电影", "影视"}, {"动漫", "动漫"}, {"小说", "阅读"}, {"阅读", "阅读"},
                {"咖啡", "咖啡"}, {"奶茶", "奶茶"}, {"草莓", "草莓"}, {"火锅", "火锅"},
                {"吃饭", "饮食"}, {"做饭", "饮食"}, {"睡觉", "睡眠"}, {"失眠", "睡眠"},
                {"运动", "运动"}, {"健身", "运动"}, {"感冒", "健康"}, {"医院", "健康"},
                {"焦虑", "情绪"}, {"开心", "情绪"}, {"难过", "情绪"}, {"压力", "情绪"},
                {"宠物", "宠物"}, {"猫", "猫"}, {"狗", "狗"}, {"表情包", "表情包"}
        };
        Set<String> seen = new HashSet<>();
        String lower = text.toLowerCase(Locale.ROOT);
        for (String[] term : terms) {
            if (lower.contains(term[0].toLowerCase(Locale.ROOT))
                    && seen.add(term[1].toLowerCase(Locale.ROOT))) out.put(term[1]);
        }
        return out;
    }

    private static void addGraphTag(JSONArray out, Set<String> seen, String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty() || value.length() > 32) return;
        if (seen.add(value.toLowerCase(Locale.ROOT))) out.put(value);
    }

    private static String shortGraphText(String text, int max) {
        if (text == null || text.length() <= max) return text == null ? "" : text;
        int end = max;
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(0, end) + "…";
    }

    public static JSONObject get(Context c, String id) {
        JSONArray a = normalize(c);
        return byId(a, id);
    }

    public static JSONArray history(Context c, String id) {
        JSONObject o = get(c, id);
        JSONArray h = o == null ? null : o.optJSONArray("history");
        return h == null ? new JSONArray() : h;
    }

    private static JSONObject byId(JSONArray a, String id) {
        if (id == null || id.isEmpty()) return null;
        for (int k = 0; k < a.length(); k++) {
            JSONObject o = a.optJSONObject(k);
            if (o != null && id.equals(o.optString("id", ""))) return o;
        }
        return null;
    }

    /* ---------------- 新增与候选 ---------------- */

    /** 兼容旧入口：手动/自动/合并/导入都走这里。 */
    public static synchronized void add(Context c, String text, String category, JSONArray tags,
                                        String source, double weight) {
        JSONObject cand = new JSONObject();
        try {
            cand.put("text", text);
            if (category != null) cand.put("category", category);
            if (tags != null) cand.put("tags", tags);
            cand.put("source", source == null ? "manual" : source);
            cand.put("weight", weight <= 0 ? 1.0 : weight);
            String origin = "auto".equals(source) ? "legacy_auto"
                    : "explicit".equals(source) ? "user_explicit"
                    : "merge".equals(source) ? "legacy_merge"
                    : "import".equals(source) ? "legacy_manual" : "manual";
            cand.put("origin", origin);
            cand.put("confidence", "auto".equals(source) ? 0.55
                    : "explicit".equals(source) ? 0.9
                    : "merge".equals(source) ? 0.65
                    : "import".equals(source) ? 0.7 : 1.0);
            cand.put("pinned", "explicit".equals(source));
        } catch (Throwable ignored) {
        }
        addCandidate(c, cand);
    }

    /** 写入一条候选记忆；同类型高相似会自动并成一条，不覆盖旧值。 */
    public static synchronized String addCandidate(Context c, JSONObject cand) {
        if (cand == null) return "";
        JSONArray a = normalize(c);
        JSONObject o = newRecord(cand, "");
        if (o == null) return "";
        JSONObject dup = findSimilar(a, o.optString("text", ""), o.optString("type", "fact"));
        if (dup != null) {
            mergeEvidence(dup, o);
            trimCapacity(a);
            Sg.writeArr(c, FILE, a);
            audit(c, "update", dup.optString("id", ""), "duplicate");
            Bus.emit("memory", new JSONObject());
            return dup.optString("id", "");
        }
        a.put(o);
        trimCapacity(a);
        Sg.writeArr(c, FILE, a);
        audit(c, "add", o.optString("id", ""), o.optString("origin", ""));
        Bus.emit("memory", new JSONObject());
        return o.optString("id", "");
    }

    private static JSONObject newRecord(JSONObject cand, String forcedId) {
        try {
            String text = cleanText(cand.optString("text", ""));
            if (text.length() < 2) return null;
            long now = System.currentTimeMillis();
            String type = cand.optString("type", inferType(cand.optString("category", ""), text));
            type = normalizeType(type);
            String origin = cand.optString("origin", "user_implicit");
            double confidence = cand.optDouble("confidence", defaultConfidence(origin));
            confidence = Math.max(0, Math.min(1, confidence));
            String status = cand.optString("status", confidence >= 0.5 ? "active" : "uncertain");
            String id = cand.optString("id", forcedId);
            if (id == null || id.isEmpty()) id = "m" + now + "_" + (int) (Math.random() * 999);
            JSONObject o = new JSONObject();
            o.put("schema", SCHEMA);
            o.put("id", id);
            o.put("type", type);
            o.put("subject", cand.optString("subject", "user"));
            o.put("text", text);
            o.put("sourceQuote", trunc(cand.optString("sourceQuote", ""), 200));
            o.put("sourceRole", cand.optString("sourceRole", "user"));
            o.put("origin", origin);
            o.put("confidence", confidence);
            o.put("status", status);
            long validFrom = cand.optLong("validFrom", now);
            o.put("validFrom", validFrom);
            o.put("validTo", cand.optLong("validTo", 0L));
            o.put("expiresAt", cand.optLong("expiresAt", 0L));
            o.put("observedAt", cand.optLong("observedAt", now));
            o.put("lastConfirmedAt", cand.optLong("lastConfirmedAt", now));
            o.put("evidenceCount", cand.optInt("evidenceCount", 1));
            o.put("contradicts", cand.optJSONArray("contradicts") == null ? new JSONArray() : cand.optJSONArray("contradicts"));
            o.put("supersededBy", cand.optString("supersededBy", ""));
            o.put("revision", cand.optInt("revision", 1));
            o.put("history", cand.optJSONArray("history") == null ? new JSONArray() : cand.optJSONArray("history"));
            o.put("category", cand.optString("category", categoryForType(type)));
            o.put("tags", cand.optJSONArray("tags") == null ? autoTags(text) : cand.optJSONArray("tags"));
            o.put("source", cand.optString("source", sourceForOrigin(origin)));
            o.put("weight", cand.optDouble("weight", 1.0));
            o.put("pinned", cand.optBoolean("pinned", false));
            o.put("heavy", cand.optBoolean("heavy", false));
            o.put("sensitivity", cand.optString("sensitivity", looksSecret(text) ? "secret" : "normal"));
            o.put("recallCount", cand.optInt("recallCount", 0));
            o.put("lastRecalledAt", cand.optLong("lastRecalledAt", 0L));
            o.put("lastUsed", cand.optLong("lastUsed", now));
            o.put("ts", cand.optLong("ts", now));
            o.put("createdAt", cand.optLong("createdAt", now));
            o.put("updatedAt", cand.optLong("updatedAt", now));
            return o;
        } catch (Throwable t) {
            return null;
        }
    }

    private static double defaultConfidence(String origin) {
        if ("user_explicit".equals(origin)) return 0.95;
        if ("manual".equals(origin)) return 1.0;
        if ("user_implicit".equals(origin)) return 0.7;
        if ("assistant_inference".equals(origin) || "diary_observation".equals(origin)) return 0.4;
        if ("legacy_auto".equals(origin)) return 0.55;
        if ("legacy_merge".equals(origin)) return 0.65;
        return 0.7;
    }

    private static void mergeEvidence(JSONObject old, JSONObject fresh) {
        try {
            long now = System.currentTimeMillis();
            old.put("evidenceCount", old.optInt("evidenceCount", 1) + 1);
            old.put("lastConfirmedAt", now);
            old.put("updatedAt", now);
            old.put("lastUsed", now);
            old.put("weight", Math.max(old.optDouble("weight", 1.0), fresh.optDouble("weight", 1.0)));
            old.put("confidence", Math.max(old.optDouble("confidence", 0.5), fresh.optDouble("confidence", 0.5)));
            if (old.optString("sourceQuote", "").isEmpty() && !fresh.optString("sourceQuote", "").isEmpty()) {
                old.put("sourceQuote", fresh.optString("sourceQuote", ""));
            }
            if ("uncertain".equals(old.optString("status", "")) && fresh.optDouble("confidence", 0) >= 0.7) {
                old.put("status", "active");
            }
            old.put("tags", unionTags(old.optJSONArray("tags"), fresh.optJSONArray("tags")));
        } catch (Throwable ignored) {
        }
    }

    /* ---------------- 提取操作 ---------------- */

    /**
     * 批量应用提炼结果。op 支持 add / update / supersede / ignore。
     * 返回各类操作计数。所有修改一次写盘。
     */
    public static synchronized JSONObject applyOps(Context c, JSONArray ops) {
        JSONObject stats = new JSONObject();
        int added = 0, updated = 0, superseded = 0, ignored = 0;
        JSONArray a = normalize(c);
        if (ops != null) {
            for (int k = 0; k < ops.length(); k++) {
                JSONObject op = ops.optJSONObject(k);
                if (op == null) continue;
                try {
                    String kind = op.optString("op", "add");
                    if ("ignore".equals(kind)) { ignored++; continue; }
                    if ("supersede".equals(kind)) {
                        JSONObject old = byId(a, op.optString("oldId", ""));
                        if (old == null) {
                            String oldText = op.optString("oldText", "");
                            old = findByText(a, oldText);
                            if (old == null) old = findSimilar(a, oldText, null);
                        }
                        JSONObject fresh = newRecord(op, "");
                        if (fresh == null) { ignored++; continue; }
                        if (old != null && !"archived".equals(old.optString("status", ""))) {
                            pushHistory(old, "被新信息取代");
                            old.put("status", "superseded");
                            old.put("validTo", System.currentTimeMillis());
                            old.put("supersededBy", fresh.optString("id", ""));
                            old.put("updatedAt", System.currentTimeMillis());
                            superseded++;
                        }
                        a.put(fresh);
                        added++;
                        continue;
                    }
                    if ("update".equals(kind)) {
                        JSONObject target = byId(a, op.optString("id", ""));
                        if (target == null) target = findSimilar(a, op.optString("text", ""), op.optString("type", "fact"));
                        if (target != null) {
                            patchRecord(target, op, true);
                            updated++;
                        } else {
                            JSONObject fresh = newRecord(op, "");
                            if (fresh != null) { a.put(fresh); added++; }
                        }
                        continue;
                    }
                    // add
                    JSONObject fresh = newRecord(op, "");
                    if (fresh == null) { ignored++; continue; }
                    JSONObject dup = findSimilar(a, fresh.optString("text", ""), fresh.optString("type", "fact"));
                    if (dup != null) {
                        mergeEvidence(dup, fresh);
                        updated++;
                    } else {
                        a.put(fresh);
                        added++;
                    }
                } catch (Throwable ignoredEx) {
                    ignored++;
                }
            }
        }
        trimCapacity(a);
        Sg.writeArr(c, FILE, a);
        try {
            stats.put("added", added);
            stats.put("updated", updated);
            stats.put("superseded", superseded);
            stats.put("ignored", ignored);
        } catch (Throwable ignoredStat) {
        }
        audit(c, "extract", "", stats.toString());
        Bus.emit("memory", new JSONObject());
        return stats;
    }

    public static synchronized boolean update(Context c, String id, JSONObject patch, String reason) {
        if (patch == null) return false;
        if (patch.has("text")) {
            String nt = cleanText(patch.optString("text", ""));
            if (nt.length() < 2) return false;
        }
        JSONArray a = normalize(c);
        JSONObject o = byId(a, id);
        if (o == null) return false;
        patchRecord(o, patch, true);
        Sg.writeArr(c, FILE, a);
        audit(c, "edit", id, reason == null ? "user" : reason);
        Bus.emit("memory", new JSONObject());
        return true;
    }

    private static void patchRecord(JSONObject o, JSONObject patch, boolean keepHistory) {
        try {
            if (patch.has("text") && !patch.optString("text", "").equals(o.optString("text", ""))) {
                if (keepHistory) pushHistory(o, "修改前");
                o.put("text", cleanText(patch.optString("text", "")));
            }
            if (patch.has("type")) o.put("type", patch.optString("type", o.optString("type", "fact")));
            if (patch.has("category")) o.put("category", patch.optString("category", o.optString("category", "other")));
            if (patch.has("tags")) o.put("tags", patch.optJSONArray("tags"));
            if (patch.has("sourceQuote")) o.put("sourceQuote", trunc(patch.optString("sourceQuote", ""), 200));
            if (patch.has("confidence")) {
                double v = patch.optDouble("confidence", o.optDouble("confidence", 0.7));
                o.put("confidence", Math.max(0, Math.min(1, v)));
            }
            if (patch.has("status")) o.put("status", patch.optString("status", "active"));
            if (patch.has("pinned")) o.put("pinned", patch.optBoolean("pinned", false));
            if (patch.has("sensitivity")) o.put("sensitivity", patch.optString("sensitivity", "normal"));
            o.put("revision", o.optInt("revision", 1) + 1);
            o.put("updatedAt", System.currentTimeMillis());
            o.put("lastConfirmedAt", System.currentTimeMillis());
        } catch (Throwable ignored) {
        }
    }

    private static void pushHistory(JSONObject o, String reason) {
        try {
            JSONArray h = o.optJSONArray("history");
            if (h == null) h = new JSONArray();
            JSONObject e = new JSONObject();
            e.put("text", o.optString("text", ""));
            e.put("status", o.optString("status", "active"));
            e.put("validFrom", o.optLong("validFrom", 0));
            e.put("validTo", o.optLong("validTo", 0));
            e.put("reason", reason == null ? "" : reason);
            e.put("ts", System.currentTimeMillis());
            h.put(e);
            while (h.length() > 12) h.remove(0);
            o.put("history", h);
        } catch (Throwable ignored) {
        }
    }

    /* ---------------- 合并 ---------------- */

    public static synchronized JSONObject merge(Context c, JSONArray ids) {
        return mergeSmart(c, ids);
    }

    public static synchronized JSONObject mergeSmart(Context c, JSONArray ids) {
        JSONObject out = new JSONObject();
        if (ids == null || ids.length() < 2) return out;
        JSONArray a = normalize(c);
        List<JSONObject> pick = new ArrayList<>();
        String type = null;
        for (int k = 0; k < a.length(); k++) {
            JSONObject o = a.optJSONObject(k);
            if (o == null) continue;
            for (int j = 0; j < ids.length(); j++) {
                if (!ids.optString(j).equals(o.optString("id", ""))) continue;
                if (type == null) type = o.optString("type", "fact");
                if (!type.equals(o.optString("type", "fact"))) return out; // 不同类不合并
                pick.add(o);
                break;
            }
        }
        if (pick.size() < 2) return out;
        try {
            StringBuilder text = new StringBuilder();
            Set<String> tags = new HashSet<>();
            Set<String> seen = new LinkedHashSet<>();
            JSONArray history = new JSONArray();
            long validFrom = Long.MAX_VALUE;
            long lastConfirmed = 0;
            double weight = 0, confidence = 0;
            boolean pinned = false, heavy = false, secret = false;
            int evidence = 0;
            String category = pick.get(0).optString("category", "other");
            for (JSONObject o : pick) {
                String t = o.optString("text", "");
                if (seen.add(t)) {
                    if (text.length() > 0) text.append("；");
                    text.append(t);
                }
                JSONArray tg = o.optJSONArray("tags");
                if (tg != null) for (int j = 0; j < tg.length(); j++) tags.add(tg.optString(j));
                JSONObject h = new JSONObject();
                h.put("text", t);
                h.put("status", "merged");
                h.put("ts", o.optLong("ts", 0L));
                history.put(h);
                validFrom = Math.min(validFrom, o.optLong("validFrom", o.optLong("ts", 0L)));
                lastConfirmed = Math.max(lastConfirmed, o.optLong("lastConfirmedAt", 0L));
                weight = Math.max(weight, o.optDouble("weight", 1.0));
                confidence = Math.max(confidence, o.optDouble("confidence", 0.5));
                pinned |= o.optBoolean("pinned", false);
                heavy |= o.optBoolean("heavy", false);
                secret |= "secret".equals(o.optString("sensitivity", "normal"));
                evidence += o.optInt("evidenceCount", 1);
            }
            JSONObject merged = new JSONObject();
            merged.put("schema", SCHEMA);
            merged.put("id", "m" + System.currentTimeMillis());
            merged.put("type", type);
            merged.put("subject", pick.get(0).optString("subject", "user"));
            merged.put("text", text.toString());
            merged.put("sourceQuote", pick.get(0).optString("sourceQuote", ""));
            merged.put("sourceRole", "manual");
            merged.put("origin", "legacy_merge");
            merged.put("confidence", confidence);
            merged.put("status", "active");
            merged.put("validFrom", validFrom == Long.MAX_VALUE ? System.currentTimeMillis() : validFrom);
            merged.put("validTo", 0L);
            merged.put("expiresAt", 0L);
            merged.put("observedAt", System.currentTimeMillis());
            merged.put("lastConfirmedAt", lastConfirmed == 0 ? System.currentTimeMillis() : lastConfirmed);
            merged.put("evidenceCount", evidence);
            merged.put("contradicts", new JSONArray());
            merged.put("supersededBy", "");
            merged.put("revision", 1);
            merged.put("history", history);
            merged.put("category", category);
            JSONArray tg = new JSONArray();
            for (String t : tags) tg.put(t);
            merged.put("tags", tg);
            merged.put("source", "merge");
            merged.put("weight", Math.min(3.0, weight + 0.3));
            merged.put("pinned", pinned);
            merged.put("heavy", heavy);
            merged.put("sensitivity", secret ? "secret" : "normal");
            merged.put("recallCount", 0);
            merged.put("lastRecalledAt", 0L);
            merged.put("lastUsed", System.currentTimeMillis());
            merged.put("ts", System.currentTimeMillis());
            merged.put("createdAt", System.currentTimeMillis());
            merged.put("updatedAt", System.currentTimeMillis());
            Set<String> drop = new HashSet<>();
            for (JSONObject o : pick) drop.add(o.optString("id", ""));
            JSONArray b = new JSONArray();
            for (int k = 0; k < a.length(); k++) {
                JSONObject o = a.optJSONObject(k);
                if (o == null || !drop.contains(o.optString("id", ""))) b.put(o);
            }
            b.put(merged);
            Sg.writeArr(c, FILE, b);
            out = merged;
            audit(c, "merge", merged.optString("id", ""), "ids=" + ids.length());
            Bus.emit("memory", new JSONObject());
        } catch (Throwable ignored) {
        }
        return out;
    }

    /* ---------------- 归档、删除、固定 ---------------- */

    public static synchronized void archive(Context c, String id, boolean archived) {
        archive(c, id, archived, "user");
    }

    public static synchronized void archive(Context c, String id, boolean archived, String reason) {
        JSONArray a = normalize(c);
        JSONObject o = byId(a, id);
        if (o == null) return;
        try {
            o.put("archived", archived);
            o.put("status", archived ? "archived" : "active");
            o.put("archiveReason", archived ? (reason == null ? "user" : reason) : "");
            o.put("archivedAt", archived ? System.currentTimeMillis() : 0L);
            o.put("updatedAt", System.currentTimeMillis());
            Sg.writeArr(c, FILE, a);
            audit(c, archived ? "archive" : "unarchive", id, reason);
            Bus.emit("memory", new JSONObject());
        } catch (Throwable ignored) {
        }
    }

    public static synchronized boolean delete(Context c, String id) {
        JSONArray a = normalize(c);
        JSONArray b = new JSONArray();
        boolean found = false;
        for (int k = 0; k < a.length(); k++) {
            JSONObject o = a.optJSONObject(k);
            if (o == null) continue;
            if (id.equals(o.optString("id", ""))) {
                found = true;
                continue;
            }
            try {
                if (id.equals(o.optString("supersededBy", ""))) o.put("supersededBy", "");
            } catch (Throwable ignored) {
            }
            b.put(o);
        }
        if (!found) return false;
        Sg.writeArr(c, FILE, b);
        audit(c, "delete", id, "user");
        Bus.emit("memory", new JSONObject());
        return true;
    }

    public static synchronized void pin(Context c, String text) {
        if (text == null || text.isEmpty()) return;
        JSONArray a = normalize(c);
        JSONObject o = findByText(a, text);
        if (o == null) o = findSimilar(a, text, null);
        if (o == null) return;
        pinById(c, o.optString("id", ""), true);
    }

    public static synchronized void pinById(Context c, String id, boolean pinned) {
        JSONArray a = normalize(c);
        JSONObject o = byId(a, id);
        if (o == null) return;
        try {
            o.put("pinned", pinned);
            if (pinned) {
                o.put("weight", Math.max(2.0, o.optDouble("weight", 1.0)));
                o.put("lastConfirmedAt", System.currentTimeMillis());
            }
            o.put("updatedAt", System.currentTimeMillis());
            Sg.writeArr(c, FILE, a);
            audit(c, pinned ? "pin" : "unpin", id, "user");
            Bus.emit("memory", new JSONObject());
        } catch (Throwable ignored) {
        }
    }

    /** 情绪很重的记忆：权重拉满，永不参与遗忘体检。 */
    public static synchronized void markHeavy(Context c, String text) {
        if (text == null || text.isEmpty()) return;
        JSONArray a = normalize(c);
        JSONObject o = findByText(a, text);
        if (o == null) o = findSimilar(a, text, null);
        if (o == null) return;
        try {
            o.put("pinned", true);
            o.put("heavy", true);
            o.put("type", "event");
            o.put("confidence", Math.max(0.9, o.optDouble("confidence", 0.7)));
            o.put("weight", Math.max(2.6, o.optDouble("weight", 1.0)));
            o.put("lastConfirmedAt", System.currentTimeMillis());
            o.put("updatedAt", System.currentTimeMillis());
            Sg.writeArr(c, FILE, a);
            audit(c, "heavy", o.optString("id", ""), "emotional");
            Bus.emit("memory", new JSONObject());
        } catch (Throwable ignored) {
        }
    }

    /** 检索实际用到的记忆：更新使用次数与时间。 */
    public static synchronized void recordUse(Context c, JSONArray ids) {
        if (ids == null || ids.length() == 0) return;
        JSONArray a = normalize(c);
        JSONArray recalled = new JSONArray();
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (int k = 0; k < a.length(); k++) {
            JSONObject o = a.optJSONObject(k);
            if (o == null) continue;
            for (int j = 0; j < ids.length(); j++) {
                if (!ids.optString(j).equals(o.optString("id", ""))) continue;
                try {
                    o.put("recallCount", o.optInt("recallCount", 0) + 1);
                    o.put("lastRecalledAt", now);
                    o.put("lastUsed", now);
                    recalled.put(o.optString("id", ""));
                    changed = true;
                } catch (Throwable ignored) {
                }
                break;
            }
        }
        if (changed) {
            Sg.writeArr(c, FILE, a);
            try {
                JSONObject event = new JSONObject();
                event.put("ids", recalled);
                event.put("ts", now);
                Bus.emit("memoryRecall", event);
            } catch (Throwable ignored) {
            }
        }
    }

    /* ---------------- 容量、体检、摘要 ---------------- */

    private static void trimCapacity(JSONArray a) {
        List<JSONObject> live = new ArrayList<>();
        for (int k = 0; k < a.length(); k++) {
            JSONObject o = a.optJSONObject(k);
            if (o == null) continue;
            if ("active".equals(o.optString("status", "active"))) live.add(o);
        }
        if (live.size() <= MAX_ACTIVE) return;
        live.sort((x, y) -> {
            double sx = x.optDouble("weight", 1.0) * 2 + x.optDouble("confidence", 0.5);
            double sy = y.optDouble("weight", 1.0) * 2 + y.optDouble("confidence", 0.5);
            int c = Double.compare(sx, sy);
            if (c != 0) return c;
            return Long.compare(x.optLong("lastConfirmedAt", 0), y.optLong("lastConfirmedAt", 0));
        });
        int over = live.size() - MAX_ACTIVE;
        for (int k = 0; k < over; k++) {
            JSONObject o = live.get(k);
            try {
                o.put("status", "archived");
                o.put("archived", true);
                o.put("archiveReason", "capacity");
                o.put("archivedAt", System.currentTimeMillis());
            } catch (Throwable ignored) {
            }
        }
    }

    public static String brief(Context c, int limit) {
        JSONArray a = normalize(c);
        List<JSONObject> live = new ArrayList<>();
        for (int k = 0; k < a.length(); k++) {
            JSONObject o = a.optJSONObject(k);
            if (o != null && "active".equals(o.optString("status", "active"))) live.add(o);
        }
        live.sort((x, y) -> {
            double sx = x.optDouble("weight", 1.0) + x.optDouble("confidence", 0.5);
            double sy = y.optDouble("weight", 1.0) + y.optDouble("confidence", 0.5);
            int cw = Double.compare(sy, sx);
            if (cw != 0) return cw;
            return Long.compare(y.optLong("lastConfirmedAt", 0), x.optLong("lastConfirmedAt", 0));
        });
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (JSONObject o : live) {
            if (n >= limit) break;
            sb.append("- [").append(typeName(o.optString("type", "fact"))).append("] ")
                    .append(o.optString("text")).append('\n');
            n++;
        }
        return sb.toString();
    }

    public static JSONObject summary(Context c) {
        JSONObject out = new JSONObject();
        try {
            JSONArray a = normalize(c);
            JSONObject cats = new JSONObject();
            JSONObject statuses = new JSONObject();
            int live = 0, archived = 0;
            for (int k = 0; k < a.length(); k++) {
                JSONObject o = a.optJSONObject(k);
                if (o == null) continue;
                String st = o.optString("status", "active");
                statuses.put(st, statuses.optInt(st, 0) + 1);
                if ("archived".equals(st)) {
                    archived++;
                    continue;
                }
                if (!"active".equals(st)) continue;
                live++;
                String cat = o.optString("category", "other");
                cats.put(cat, cats.optInt(cat, 0) + 1);
            }
            out.put("schema", SCHEMA);
            out.put("total", live);
            out.put("archived", archived);
            out.put("statuses", statuses);
            out.put("cats", cats);
            out.put("items", a);
            out.put("reviewDue", reviewDue(c));
        } catch (Throwable ignored) {
        }
        return out;
    }

    public static boolean reviewDue(Context c) {
        try {
            JSONObject r = Sg.readObj(c, "mem_review.json");
            long last = r.optLong("lastAt", 0);
            return System.currentTimeMillis() - last > 7 * 86400000L && review(c).length() > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 低权重、低置信度且很久没被确认的记忆，供每周体检确认。 */
    public static JSONArray review(Context c) {
        JSONArray out = new JSONArray();
        try {
            JSONArray a = normalize(c);
            long old = System.currentTimeMillis() - 45 * 86400000L;
            for (int k = 0; k < a.length() && out.length() < REVIEW_LIMIT; k++) {
                JSONObject o = a.optJSONObject(k);
                if (o == null || !"active".equals(o.optString("status", "active"))) continue;
                if (o.optBoolean("pinned", false) || o.optBoolean("heavy", false)) continue;
                boolean lowWeight = o.optDouble("weight", 1.0) <= 1.2;
                boolean lowConfidence = o.optDouble("confidence", 1.0) < 0.6;
                if ((lowWeight || lowConfidence) && o.optLong("lastConfirmedAt", 0) < old) out.put(o);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    public static void markReviewed(Context c) {
        try {
            JSONObject o = new JSONObject();
            o.put("lastAt", System.currentTimeMillis());
            Sg.writeObj(c, "mem_review.json", o);
        } catch (Throwable ignored) {
        }
    }

    /* ---------------- 导入 ---------------- */

    public static synchronized void importArray(Context c, JSONArray in) {
        if (in == null) return;
        JSONArray a = normalize(c);
        Set<String> ids = new HashSet<>();
        for (int k = 0; k < a.length(); k++) {
            JSONObject o = a.optJSONObject(k);
            if (o != null) ids.add(o.optString("id", ""));
        }
        int added = 0;
        for (int k = 0; k < in.length(); k++) {
            JSONObject o = in.optJSONObject(k);
            if (o == null) continue;
            try {
                JSONObject copy = new JSONObject(o.toString());
                upgrade(copy);
                String id = copy.optString("id", "");
                if (id.isEmpty()) {
                    id = "m" + System.currentTimeMillis() + "_" + (int) (Math.random() * 999);
                    copy.put("id", id);
                }
                if (!ids.add(id)) continue;
                a.put(copy);
                added++;
            } catch (Throwable ignored) {
            }
        }
        if (added > 0) {
            trimCapacity(a);
            Sg.writeArr(c, FILE, a);
            audit(c, "import", "", "count=" + added);
            Bus.emit("memory", new JSONObject());
        }
    }

    /* ---------------- 工具 ---------------- */

    private static JSONObject findByText(JSONArray a, String text) {
        String t = cleanText(text);
        for (int k = 0; k < a.length(); k++) {
            JSONObject o = a.optJSONObject(k);
            if (o != null && t.equals(cleanText(o.optString("text", "")))) return o;
        }
        return null;
    }

    private static JSONObject findSimilar(JSONArray a, String text, String type) {
        if (text == null || text.trim().isEmpty()) return null;
        String t = cleanText(text);
        JSONObject best = null;
        double bestScore = 0;
        for (int k = 0; k < a.length(); k++) {
            JSONObject o = a.optJSONObject(k);
            if (o == null) continue;
            String st = o.optString("status", "active");
            if ("archived".equals(st) || "superseded".equals(st)) continue;
            String other = cleanText(o.optString("text", ""));
            if (t.equals(other)) return o;
            if (type != null && !type.isEmpty() && !type.equals(o.optString("type", "fact"))) continue;
            double sim = similarity(t, other);
            if (sim >= 0.75 && sim > bestScore) {
                bestScore = sim;
                best = o;
            }
        }
        return best;
    }

    private static double similarity(String a, String b) {
        Set<String> ga = grams(a), gb = grams(b);
        if (ga.isEmpty() || gb.isEmpty()) return 0;
        int inter = 0;
        for (String g : ga) if (gb.contains(g)) inter++;
        int union = ga.size() + gb.size() - inter;
        return union <= 0 ? 0 : inter / (double) union;
    }

    private static Set<String> grams(String text) {
        Set<String> set = new LinkedHashSet<>();
        String clean = normalizeText(text);
        for (int k = 0; k + 2 <= clean.length() && set.size() < 80; k++) {
            set.add(clean.substring(k, k + 2));
        }
        return set;
    }

    private static String normalizeText(String text) {
        if (text == null) return "";
        return text.toLowerCase().replaceAll("[\\s，。！？、,.!?~～:：;；\"'“”‘’()（）\\[\\]【】]", "");
    }

    private static String cleanText(String text) {
        if (text == null) return "";
        String s = text.replace("\r", "").replace("\n", " ").trim();
        s = s.replaceAll("\\s+", " ");
        return trunc(s, MAX_TEXT);
    }

    private static String trunc(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static boolean looksSecret(String text) {
        if (text == null) return false;
        String t = text.toLowerCase();
        return t.contains("验证码") || t.contains("密码") || t.contains("身份证")
                || t.contains("银行卡") || t.contains("卡号") || t.contains("cvv")
                || t.contains("银行密码");
    }

    private static String inferType(String category, String text) {
        if ("person".equals(category)) return "person";
        if ("project".equals(category)) return "project";
        if ("preference".equals(category)) return "preference";
        if ("event".equals(category)) return "event";
        if (text == null) return "fact";
        if (containsAny(text, new String[]{"朋友", "家人", "妈妈", "爸爸", "同事", "老板", "女朋友", "男朋友", "孩子"}))
            return "person";
        if (containsAny(text, new String[]{"身体", "健康", "过敏", "检查", "吃药", "用药", "发烧", "感冒", "头疼", "疼痛"}))
            return "health";
        if (containsAny(text, new String[]{"开心", "难过", "生气", "委屈", "孤独", "安心", "心情", "情绪", "感动", "焦虑", "害怕", "想念"}))
            return "emotion";
        if (containsAny(text, new String[]{"每天", "早上", "晚上", "作息", "通勤", "日常", "平时", "周末习惯"}))
            return "daily";
        if (containsAny(text, new String[]{"住在", "住址", "家在", "来自", "城市", "小区", "街道", "地点", "附近"}))
            return "place";
        if (containsAny(text, new String[]{"学习", "课程", "考试", "练习", "学会", "读书", "知识点", "培训"}))
            return "learning";
        if (containsAny(text, new String[]{"目标", "希望做到", "想成为", "想要实现", "长期计划", "梦想"}))
            return "goal";
        if (containsAny(text, new String[]{"感兴趣", "爱好", "喜欢听", "喜欢看", "喜欢玩", "收藏", "追剧", "乐器"}))
            return "interest";
        if (containsAny(text, new String[]{"项目", "工作", "公司", "客户", "代码", "需求", "上线", "会议"}))
            return "project";
        if (containsAny(text, new String[]{"喜欢", "讨厌", "爱吃", "不吃", "偏好", "习惯", "害怕"}))
            return "preference";
        if (containsAny(text, new String[]{"生日", "纪念", "计划", "准备", "去了", "发生"})) return "event";
        if (containsAny(text, new String[]{"明天", "后天", "下周", "下个月", "打算", "准备去"})) return "plan";
        if (containsAny(text, new String[]{"累", "压力", "失眠", "睡不着", "担心", "牵挂", "惦记"}))
            return "concern";
        if (containsAny(text, new String[]{"承诺", "答应", "保证", "约好", "说好了"})) return "commitment";
        if (containsAny(text, new String[]{"印象", "看起来", "表现得", "观察到", "发现他", "发现她"})) return "observation";
        return "fact";
    }

    private static String categoryForType(String type) {
        if ("person".equals(type)) return "person";
        if ("project".equals(type)) return "project";
        if ("preference".equals(type)) return "preference";
        if ("event".equals(type) || "plan".equals(type)) return "event";
        return "other";
    }

    private static String normalizeType(String type) {
        if ("fact".equals(type) || "preference".equals(type) || "person".equals(type)
                || "project".equals(type) || "event".equals(type) || "plan".equals(type)
                || "concern".equals(type) || "commitment".equals(type) || "observation".equals(type)
                || "emotion".equals(type) || "daily".equals(type) || "health".equals(type)
                || "place".equals(type) || "learning".equals(type) || "interest".equals(type)
                || "goal".equals(type) || "experience".equals(type)) {
            return type;
        }
        return "fact";
    }

    private static JSONArray unionTags(JSONArray a, JSONArray b) {
        Set<String> set = new LinkedHashSet<>();
        if (a != null) for (int k = 0; k < a.length(); k++) set.add(a.optString(k));
        if (b != null) for (int k = 0; k < b.length(); k++) set.add(b.optString(k));
        JSONArray out = new JSONArray();
        for (String s : set) out.put(s);
        return out;
    }

    private static void audit(Context c, String op, String id, String why) {
        try {
            JSONObject e = new JSONObject();
            e.put("ts", System.currentTimeMillis());
            e.put("op", op);
            e.put("id", id == null ? "" : id);
            e.put("why", why == null ? "" : why);
            Sg.appendLine(c, AUDIT, e);
        } catch (Throwable ignored) {
        }
    }

    public static String autoCategory(String text) {
        String type = inferType("", text);
        return categoryForType(type);
    }

    public static JSONArray autoTags(String text) {
        JSONArray a = new JSONArray();
        if (text == null) return a;
        String[][] map = {
                {"咖啡", "咖啡"}, {"音乐", "音乐"}, {"游戏", "游戏"}, {"工作", "工作"},
                {"学习", "学习"}, {"旅行", "旅行"}, {"健康", "健康"}, {"运动", "运动"},
                {"吃", "饮食"}, {"睡", "睡眠"}, {"家人", "家人"}, {"朋友", "朋友"}
        };
        for (String[] m : map) {
            if (text.contains(m[0])) a.put(m[1]);
        }
        return a;
    }

    /** 供提炼层复用：判断是否含验证码/密码/证件等敏感信息。 */
    public static boolean isSensitive(String text) {
        return looksSecret(text);
    }

    private static boolean containsAny(String s, String[] arr) {
        for (String k : arr) if (s.contains(k)) return true;
        return false;
    }
}
