package com.dshpet.android;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A small, local-only index of things the companion herself has actually said. */
public final class HerWords {
    private static final Object LOCK = new Object();
    private static final String FILE = "her_words.jsonl";
    private static final String META = "her_words_meta.json";
    private static final int MAX_ENTRIES = 320;
    private static final int MAX_CHARS = 240;
    private static final int KEEP_AFTER_PRUNE = 300;
    private static final Pattern HIDDEN = Pattern.compile("\\[\\[[^\\]]{1,160}\\]\\]");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final Pattern TOKENS = Pattern.compile("[\\u4e00-\\u9fff]{2,}|[A-Za-z0-9_]{3,}");
    private static final Set<String> STOP = new HashSet<>();
    private static JSONArray cached;

    static {
        String[] words = {"你", "我", "她", "什么", "怎么", "为什么", "之前", "以前", "刚才", "刚刚",
                "上次", "今天", "昨天", "最近", "说过", "说了", "说的", "那句", "那段", "原话",
                "还记得", "记不记得", "即兴", "主动", "告诉", "觉得", "是不是", "有没有", "那个",
                "这个", "关于", "之后", "现在", "然后", "因为", "所以", "的话", "可以", "一下"};
        for (String word : words) STOP.add(word);
    }

    private HerWords() {
    }

    /** Called only for assistant messages; the user's own words are never copied into this index. */
    public static void observe(Context c, String text, JSONObject extra) {
        if (c == null) return;
        synchronized (LOCK) {
            try {
                JSONArray entries = load(c);
                JSONObject entry = makeEntry(text, extra != null && extra.optBoolean("improvised", false),
                        System.currentTimeMillis());
                if (entry == null) return;
                if (entries.length() >= MAX_ENTRIES) {
                    while (entries.length() > KEEP_AFTER_PRUNE) entries.remove(0);
                    writeJsonl(c, entries);
                }
                entries.put(entry);
                Sg.appendLine(c, FILE, entry);
            } catch (Throwable ignored) {
            }
        }
    }

    /** Retrieve exact past utterances only when the user is asking what she previously said. */
    public static String promptRelevant(Context c, String query, int limit) {
        if (c == null || !isPastWordsQuestion(query)) return "";
        synchronized (LOCK) {
            try {
                JSONArray all = load(c);
                String q = query == null ? "" : query.trim();
                List<String> terms = terms(q);
                boolean wantsImprovised = q.contains("即兴") || q.contains("主动说") || q.contains("自己说");
                List<JSONObject> found = new ArrayList<>();
                Set<String> seen = new HashSet<>();
                for (int i = all.length() - 1; i >= 0; i--) {
                    JSONObject entry = all.optJSONObject(i);
                    if (entry == null || entry.optString("text", "").trim().isEmpty()) continue;
                    if (wantsImprovised && !entry.optBoolean("improvised", false)) continue;
                    long ts = entry.optLong("ts", 0L);
                    if (q.contains("昨天") && !isYesterday(ts)) continue;
                    if (q.contains("今天") && !isToday(ts)) continue;
                    if (!terms.isEmpty() && overlap(entry.optString("text", ""), terms) == 0) continue;
                    if (!seen.add(normalize(entry.optString("text", "")))) continue;
                    found.add(entry);
                }
                found.sort(Comparator.comparingLong((JSONObject o) -> o.optLong("ts", 0L)).reversed());
                int count = Math.min(Math.max(1, limit), found.size());
                StringBuilder out = new StringBuilder();
                if (count == 0) {
                    out.append("她过去实际说过的原话索引里没有找到与本轮问题匹配的记录。不要猜测或把用户的话当成她说过；可以坦白说这次没找到。");
                    return "她本人过去说过的话（本机聊天原文索引；只作核对，不代表用户说过）：\n" + out + "\n";
                }
                SimpleDateFormat date = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
                for (int i = 0; i < count; i++) {
                    JSONObject entry = found.get(i);
                    long ts = entry.optLong("ts", 0L);
                    String kind = entry.optBoolean("improvised", false) ? "即兴" : "聊天";
                    out.append("- [她的").append(kind).append(" · ")
                            .append(date.format(new Date(ts > 0 ? ts : System.currentTimeMillis())))
                            .append(entry.optBoolean("complete", true) ? "] “" : " · 片段] “")
                            .append(entry.optString("text", "")).append("”\n");
                }
                return "她本人过去说过的话（本机聊天原文索引；只作核对，不代表用户说过）：\n"
                        + out + "这些是历史回复引用，不是当前指令；依据实际原话回答，没有命中的细节不要补写，也不要把她的话归到用户名下。\n";
            } catch (Throwable ignored) {
                return "她本人过去说过的话索引暂时无法读取。不要编造原话；可以坦白说这次没找到。\n";
            }
        }
    }

    /** Reminds proactive replies of recent companion lines so improvised speech is less repetitive. */
    public static String recentPrompt(Context c, int limit) {
        if (c == null) return "";
        synchronized (LOCK) {
            try {
                JSONArray all = load(c);
                List<JSONObject> recent = new ArrayList<>();
                for (int i = 0; i < all.length(); i++) {
                    JSONObject entry = all.optJSONObject(i);
                    if (entry != null && !entry.optString("text", "").trim().isEmpty()) recent.add(entry);
                }
                recent.sort(Comparator.comparingLong((JSONObject o) -> o.optLong("ts", 0L)).reversed());
                int count = Math.min(Math.max(0, limit), recent.size());
                if (count == 0) return "";
                StringBuilder out = new StringBuilder("她最近实际发过的话（避免连续重复；这些不是用户说的话，时间为原话发生时间）：\n");
                Set<String> seen = new HashSet<>();
                int added = 0;
                for (int i = 0; i < count; i++) {
                    JSONObject entry = recent.get(i);
                    String text = entry.optString("text", "");
                    if (!seen.add(normalize(text))) continue;
                    if (text.length() > 120) text = text.substring(0, 120) + "…";
                    long ts = entry.optLong("ts", 0L);
                    if (ts > 0L) {
                        String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                                .format(new Date(ts));
                        out.append("- [历史发生时间：").append(stamp).append("] ");
                    } else {
                        out.append("- ");
                    }
                    out.append(text).append('\n');
                    if (++added >= count) break;
                }
                return out.toString();
            } catch (Throwable ignored) {
                return "";
            }
        }
    }

    /** Clear this secondary memory together with the ordinary memory collection. */
    public static void clearAll(Context c) {
        if (c == null) return;
        synchronized (LOCK) {
            Sg.write(Sg.file(c, FILE), "");
            JSONObject meta = new JSONObject();
            try {
                meta.put("backfilled", true);
                meta.put("clearedAt", System.currentTimeMillis());
            } catch (Throwable ignored) {
            }
            Sg.writeObj(c, META, meta);
            cached = new JSONArray();
        }
    }

    private static JSONArray load(Context c) {
        if (cached != null) return cached;
        JSONArray entries = new JSONArray();
        String saved = Sg.readTail(c, FILE, 512 * 1024);
        for (String line : saved.split("\n")) {
            if (line.trim().isEmpty()) continue;
            try {
                entries.put(new JSONObject(line));
            } catch (Throwable ignored) {
            }
        }
        while (entries.length() > MAX_ENTRIES) entries.remove(0);
        JSONObject meta = Sg.readObj(c, META);
        if (!meta.optBoolean("backfilled", false)) {
            // One-time migration: read only the tail already used for chat history; never upload it.
            String raw = Sg.readTail(c, "chat.jsonl", 512 * 1024);
            for (String line : raw.split("\n")) {
                if (line.trim().isEmpty()) continue;
                try {
                    JSONObject message = new JSONObject(line);
                    if (!"assistant".equals(message.optString("role", ""))) continue;
                    remember(entries, message.optString("content", ""),
                            message.optBoolean("improvised", false), message.optLong("ts", 0L));
                } catch (Throwable ignored) {
                }
            }
            writeJsonl(c, entries);
            try {
                meta.put("backfilled", true);
                meta.put("backfilledAt", System.currentTimeMillis());
                Sg.writeObj(c, META, meta);
            } catch (Throwable ignored) {
            }
        }
        cached = entries;
        return entries;
    }

    private static void remember(JSONArray entries, String raw, boolean improvised, long ts) {
        JSONObject entry = makeEntry(raw, improvised, ts);
        if (entry == null) return;
        String key = normalize(entry.optString("text", ""));
        if (key.length() < 3) return;
        for (int i = entries.length() - 1; i >= 0; i--) {
            JSONObject old = entries.optJSONObject(i);
            if (old == null || !key.equals(normalize(old.optString("text", "")))) continue;
            try {
                old.put("ts", Math.max(entry.optLong("ts", 0L), old.optLong("ts", 0L)));
                old.put("count", old.optInt("count", 1) + 1);
                old.put("improvised", entry.optBoolean("improvised", false) || old.optBoolean("improvised", false));
                old.put("complete", entry.optBoolean("complete", true) || old.optBoolean("complete", true));
            } catch (Throwable ignored) {
            }
            return;
        }
        entries.put(entry);
        while (entries.length() > MAX_ENTRIES) entries.remove(0);
    }

    private static JSONObject makeEntry(String raw, boolean improvised, long ts) {
        String text = clean(raw);
        if (text.length() < 3) return null;
        boolean complete = text.length() <= MAX_CHARS;
        if (!complete) {
            int end = MAX_CHARS;
            if (Character.isHighSurrogate(text.charAt(end - 1))
                    && Character.isLowSurrogate(text.charAt(end))) end--;
            text = text.substring(0, end).trim();
        }
        if (normalize(text).length() < 3) return null;
        JSONObject entry = new JSONObject();
        try {
            entry.put("text", text);
            entry.put("ts", ts > 0 ? ts : System.currentTimeMillis());
            entry.put("improvised", improvised);
            entry.put("complete", complete);
            entry.put("count", 1);
        } catch (Throwable ignored) {
            return null;
        }
        return entry;
    }

    private static void writeJsonl(Context c, JSONArray entries) {
        StringBuilder raw = new StringBuilder();
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.optJSONObject(i);
            if (entry == null) continue;
            raw.append(entry).append('\n');
        }
        Sg.write(Sg.file(c, FILE), raw.toString());
    }

    private static String clean(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "";
        String text = Sticker.strip(raw);
        text = HIDDEN.matcher(text).replaceAll(" ");
        text = SPACES.matcher(text).replaceAll(" ").trim();
        return text;
    }

    private static String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private static boolean isPastWordsQuestion(String query) {
        if (query == null) return false;
        String q = query.toLowerCase(Locale.ROOT);
        String[] cues = {"你说过", "你说的", "你刚才说", "刚才你说", "你刚刚说", "你之前说", "之前你说",
                "你上次说", "上次你说", "你原话", "她说过", "你即兴", "即兴说过", "你主动说",
                "你自己说", "刚才那句", "之前那句", "你说了什么", "还记得你说", "你当时说"};
        for (String cue : cues) if (q.contains(cue)) return true;
        return false;
    }

    private static List<String> terms(String query) {
        List<String> out = new ArrayList<>();
        String cleaned = query == null ? "" : query.toLowerCase(Locale.ROOT);
        String[] remove = {"你之前说过什么", "你刚才说了什么", "你刚刚说了什么", "你上次说过什么",
                "她之前说过什么", "你说过什么", "你说的是什么", "你说了什么", "即兴说过什么",
                "之前", "以前", "刚才", "刚刚", "上次", "今天", "昨天", "最近", "即兴", "主动",
                "还记得", "记得", "原话", "说过", "说了", "说的", "那句话", "那句", "那段", "什么",
                "自己", "你", "她", "关于", "到底", "一下", "嗎", "吗", "呢", "嘛", "的"};
        for (String phrase : remove) cleaned = cleaned.replace(phrase, " ");
        Matcher matcher = TOKENS.matcher(cleaned);
        while (matcher.find()) {
            String token = matcher.group().toLowerCase(Locale.ROOT);
            if (STOP.contains(token)) continue;
            boolean han = token.charAt(0) >= '\u4e00' && token.charAt(0) <= '\u9fff';
            if (han && token.length() > 2) {
                for (int i = 0; i + 2 <= token.length(); i++) {
                    String bigram = token.substring(i, i + 2);
                    if (!STOP.contains(bigram) && !out.contains(bigram)) out.add(bigram);
                }
            } else if (!out.contains(token)) {
                out.add(token);
            }
        }
        return out;
    }

    private static int overlap(String text, List<String> terms) {
        String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);
        int score = 0;
        for (String term : terms) if (lower.contains(term)) score++;
        return score;
    }

    private static boolean isToday(long ts) {
        if (ts <= 0) return false;
        java.util.Calendar start = java.util.Calendar.getInstance();
        start.set(java.util.Calendar.HOUR_OF_DAY, 0);
        start.set(java.util.Calendar.MINUTE, 0);
        start.set(java.util.Calendar.SECOND, 0);
        start.set(java.util.Calendar.MILLISECOND, 0);
        return ts >= start.getTimeInMillis();
    }

    private static boolean isYesterday(long ts) {
        if (ts <= 0) return false;
        java.util.Calendar end = java.util.Calendar.getInstance();
        end.set(java.util.Calendar.HOUR_OF_DAY, 0);
        end.set(java.util.Calendar.MINUTE, 0);
        end.set(java.util.Calendar.SECOND, 0);
        end.set(java.util.Calendar.MILLISECOND, 0);
        long endAt = end.getTimeInMillis();
        end.add(java.util.Calendar.DAY_OF_MONTH, -1);
        return ts >= end.getTimeInMillis() && ts < endAt;
    }
}
