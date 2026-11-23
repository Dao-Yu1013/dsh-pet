package com.dshpet.android;

import android.content.Context;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 广告跳过规则：兼容常见 JSON / 李跳跳风格的 text、desc、id 规则。 */
public final class AdRules {
    public static final class Rule {
        String name = "规则";
        String pkg = "";
        String activity = "";
        boolean builtin;
        final List<String> keywords = new ArrayList<>();
        final List<String> ids = new ArrayList<>();
        final List<String> descs = new ArrayList<>();

        boolean empty() {
            return keywords.isEmpty() && ids.isEmpty() && descs.isEmpty();
        }
    }

    private static final List<Rule> rules = new ArrayList<>();
    private static final Map<String, Long> lastTry = new HashMap<>();
    // A click is only a request. Count it as a skip after the matched control
    // disappears and the accessibility tree reflects a changed screen.
    private static final Map<String, PendingSkip> pendingSkips = new HashMap<>();
    private static final Map<String, String> lastSkippedTarget = new HashMap<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile long loadedAt;

    private static final class PendingSkip {
        final String target;
        final String label;
        final int screen;
        final long clickedAt;

        PendingSkip(String target, String label, int screen, long clickedAt) {
            this.target = target;
            this.label = label;
            this.screen = screen;
            this.clickedAt = clickedAt;
        }
    }

    private AdRules() {
    }

    public static synchronized void load(Context c) {
        rules.clear();
        lastTry.clear();
        pendingSkips.clear();
        lastSkippedTarget.clear();
        String raw = Sg.read(Sg.file(c, "ad_rules.json"), "");
        if (!raw.trim().isEmpty()) {
            try {
                Object root = new org.json.JSONTokener(raw).nextValue();
                if (root instanceof JSONArray) collectArray((JSONArray) root, "", "", rules);
                else if (root instanceof JSONObject) collectObject((JSONObject) root, "", "", rules);
            } catch (Throwable ignored) {
            }
        }
        loadedAt = System.currentTimeMillis();
    }

    public static long loadedAt() {
        return loadedAt;
    }

    /** Ad skipping is a built-in accessibility rule engine, not a generated code plugin. */
    public static boolean isAdRuleCreationRequest(String text) {
        if (text == null) return false;
        String s = text.replaceAll("\\s+", "");
        boolean ad = s.contains("跳过广告") || s.contains("开屏广告") || s.contains("广告跳过");
        boolean create = s.contains("写") || s.contains("做") || s.contains("生成") || s.contains("创建") || s.contains("帮我");
        return ad && create && (s.contains("规则") || s.contains("插件") || s.contains("跳过") || s.contains("广告"));
    }

    public static String enableBuiltInFromChat(Context c) {
        try {
            Cfg cfg = Cfg.load(c);
            cfg.set("adEnabled", true);
            cfg.save(c);
            A11y.reload();
            return "这项功能不是插件页里的代码插件，而是“功能中心 → 广告跳过”里的无障碍规则。我已启用内置的明确跳过按钮识别；"
                    + (A11y.ready() ? "无障碍服务已连接，可以识别屏幕上的广告跳过控件。" : "还需要到“设置 → 权限体检”开启无障碍服务，系统授权前我不会实际点击。")
                    + "识别不到按钮时，可在广告跳过页导入对应应用的规则。";
        } catch (Throwable ignored) {
            return "广告跳过走“功能中心 → 广告跳过”的规则引擎，不属于插件页。刚才没能启用它，请在该页面检查开关和无障碍权限。";
        }
    }

    private static void collectArray(JSONArray a, String pkg, String act, List<Rule> out) {
        for (int k = 0; k < a.length() && out.size() < 1500; k++) {
            Object o = a.opt(k);
            if (o instanceof JSONObject) collectObject((JSONObject) o, pkg, act, out);
            else if (o instanceof JSONArray) collectArray((JSONArray) o, pkg, act, out);
            else {
                String s = String.valueOf(o).trim();
                if (s.length() >= 2 && s.length() <= 24) {
                    Rule r = new Rule();
                    r.pkg = pkg;
                    r.activity = act;
                    r.keywords.add(s);
                    out.add(r);
                }
            }
        }
    }

    private static void collectObject(JSONObject o, String inheritedPkg, String inheritedAct, List<Rule> out) {
        if (o == null || out.size() >= 1500) return;
        String pkg = first(o, "package", "packageName", "pkg", "pkgName", "appId", "app");
        if (pkg == null) pkg = inheritedPkg;
        String act = first(o, "activity", "activityName", "className", "activityId");
        if (act == null) act = inheritedAct;

        Rule r = new Rule();
        r.pkg = pkg == null ? "" : pkg;
        r.activity = act == null ? "" : act;
        String name = first(o, "name", "title", "descName", "ruleName");
        if (name != null && name.length() <= 40) r.name = name;

        addKeywords(r.keywords, o, "keywords", "texts", "text", "clickText", "skipText", "words", "labels", "buttonText");
        addKeywords(r.ids, o, "ids", "id", "resourceId", "viewId", "clickId", "viewIds");
        addKeywords(r.descs, o, "descs", "desc", "description", "clickDesc", "contentDescription");
        if (!r.empty()) out.add(r);

        // 常见嵌套：rules / matches / actions / apps / steps
        String[] containers = {"rules", "matches", "actions", "apps", "steps", "items", "list", "data"};
        for (String key : containers) {
            Object sub = o.opt(key);
            if (sub instanceof JSONArray) collectArray((JSONArray) sub, r.pkg, r.activity, out);
            else if (sub instanceof JSONObject) collectObject((JSONObject) sub, r.pkg, r.activity, out);
        }
    }

    private static String first(JSONObject o, String... keys) {
        for (String k : keys) {
            String v = o.optString(k, "");
            if (!v.isEmpty() && !"null".equals(v)) return v;
        }
        return null;
    }

    private static void addKeywords(List<String> out, JSONObject o, String... keys) {
        for (String k : keys) {
            Object v = o.opt(k);
            if (v == null) continue;
            if (v instanceof JSONArray) {
                JSONArray a = (JSONArray) v;
                for (int i = 0; i < a.length(); i++) {
                    String s = a.optString(i, "").trim();
                    if (!s.isEmpty() && s.length() <= 40) out.add(s);
                }
            } else if (v instanceof String) {
                String s = ((String) v).trim();
                if (!s.isEmpty() && s.length() <= 40) out.add(s);
            }
        }
    }

    public static JSONObject importText(Context c, String raw) {
        Sg.write(Sg.file(c, "ad_rules.json"), raw == null ? "" : raw);
        load(c);
        JSONObject o = summary(c);
        A11y.reload();
        return o;
    }

    public static synchronized JSONObject summary(Context c) {
        if (loadedAt == 0) load(c);
        JSONObject o = new JSONObject();
        try {
            List<Rule> effective = effectiveRules();
            o.put("count", effective.size());
            JSONArray names = new JSONArray();
            for (int k = 0; k < effective.size() && k < 8; k++) {
                Rule r = effective.get(k);
                names.put(r.name + (r.pkg.isEmpty() ? "" : " · " + r.pkg));
            }
            o.put("sample", names);
            JSONObject st = Sg.readObj(c, "adstats.json");
            int total = st.optInt("total", 0);
            int ok = st.optInt("ok", 0);
            o.put("total", total);
            o.put("ok", ok);
            o.put("fail", Math.max(0, total - ok));
            o.put("hitRate", total == 0 ? 0 : Math.round(ok * 1000.0 / total) / 10.0);
            o.put("byPkg", st.optJSONObject("byPkg") == null ? new JSONObject() : st.optJSONObject("byPkg"));
            o.put("offApps", Cfg.load(c).arr("adOffApps"));
            o.put("testMode", Cfg.load(c).b("adTest", false));
            o.put("recent", recentLog(c, 30));
        } catch (Throwable ignored) {
        }
        return o;
    }

    /** Return imported rules plus the real built-in rule shown in the UI. */
    private static synchronized List<Rule> effectiveRules() {
        List<Rule> out = new ArrayList<>(rules);
        out.add(builtinRule());
        return out;
    }

    private static Rule builtinRule() {
        Rule builtin = new Rule();
        builtin.name = "内置明确广告跳过按钮";
        builtin.pkg = "*";
        builtin.builtin = true;
        return builtin;
    }

    public static JSONArray recentLog(Context c, int limit) {
        JSONArray out = new JSONArray();
        try {
            String s = Sg.read(Sg.file(c, "adlog.jsonl"), "");
            String[] lines = s.split("\n");
            int n = 0;
            for (int k = lines.length - 1; k >= 0 && n < limit; k--) {
                if (lines[k].trim().isEmpty()) continue;
                try {
                    out.put(new JSONObject(lines[k]));
                    n++;
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    public static void setAppEnabled(Context c, String pkg, boolean on) {
        Cfg cfg = Cfg.load(c);
        JSONArray a = cfg.arr("adOffApps");
        JSONArray b = new JSONArray();
        for (int k = 0; k < a.length(); k++) {
            String p = a.optString(k);
            if (!p.equals(pkg)) b.put(p);
        }
        if (!on) b.put(pkg);
        cfg.set("adOffApps", b);
        cfg.save(c);
    }

    private static boolean appEnabled(Context c, String pkg) {
        JSONArray a = Cfg.load(c).arr("adOffApps");
        for (int k = 0; k < a.length(); k++) if (pkg.equals(a.optString(k))) return false;
        return true;
    }

    private static void record(Context c, String pkg, boolean ok, long ms, String label, String reason) {
        synchronized (AdRules.class) {
            try {
                JSONObject st = Sg.readObj(c, "adstats.json");
                st.put("total", st.optInt("total", 0) + 1);
                if (ok) st.put("ok", st.optInt("ok", 0) + 1);
                JSONObject by = st.optJSONObject("byPkg");
                if (by == null) by = new JSONObject();
                JSONObject p = by.optJSONObject(pkg);
                if (p == null) p = new JSONObject();
                p.put("total", p.optInt("total", 0) + 1);
                if (ok) p.put("ok", p.optInt("ok", 0) + 1);
                p.put("last", System.currentTimeMillis());
                by.put(pkg, p);
                st.put("byPkg", by);
                Sg.writeObj(c, "adstats.json", st);

                JSONObject log = new JSONObject();
                log.put("ts", System.currentTimeMillis());
                log.put("pkg", pkg);
                log.put("ok", ok);
                log.put("ms", ms);
                log.put("label", label == null ? "" : label);
                log.put("why", reason == null ? "" : reason);
                Sg.appendLine(c, "adlog.jsonl", log);
                java.io.File f = Sg.file(c, "adlog.jsonl");
                if (f.length() > 200 * 1024) {
                    String[] lines = Sg.read(f, "").split("\n");
                    StringBuilder sb = new StringBuilder();
                    int start = Math.max(0, lines.length - 400);
                    for (int k = start; k < lines.length; k++) {
                        if (lines[k].trim().isEmpty()) continue;
                        sb.append(lines[k]).append('\n');
                    }
                    Sg.write(f, sb.toString());
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /** 测试模式：只匹配不点击，返回命中规则与节点路径。 */
    public static JSONObject test(A11y svc, AccessibilityNodeInfo root, String pkg) {
        JSONObject out = new JSONObject();
        if (root == null || TextUtils.isEmpty(pkg)) return out;
        if (loadedAt == 0) load(svc);
        try {
            List<Rule> matched = matchingRules(pkg);
            if (matched.isEmpty()) {
                out.put("matched", false);
                out.put("pkg", pkg);
                out.put("nodes", 0);
                out.put("path", "");
                return out;
            }
            List<String> path = new ArrayList<>();
            long t0 = System.currentTimeMillis();
            AccessibilityNodeInfo found = find(root, pkg, matched, 0, new int[]{0}, path, hasAdContext(root, 0, new int[]{0}));
            out.put("matched", found != null);
            out.put("ms", System.currentTimeMillis() - t0);
            out.put("pkg", pkg);
            out.put("nodes", path.size());
            StringBuilder sb = new StringBuilder();
            for (int k = path.size() - 1; k >= 0; k--) {
                if (sb.length() > 0) sb.append(" > ");
                sb.append(path.get(k));
            }
            out.put("path", sb.length() > 240 ? sb.substring(0, 240) : sb.toString());
            if (found != null) {
                out.put("text", str(found.getText()));
                out.put("desc", str(found.getContentDescription()));
                out.put("id", found.getViewIdResourceName() == null ? "" : found.getViewIdResourceName());
                AccessibilityNodeInfo clickable = found;
                for (int i = 0; i < 4 && clickable != null; i++) {
                    if (clickable.isClickable()) break;
                    clickable = clickable.getParent();
                }
                out.put("clickable", clickable != null);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static boolean pkgMatches(String pattern, String pkg) {
        if (pattern == null || pattern.isEmpty() || "*".equals(pattern)) return true;
        if (pattern.endsWith("*")) return pkg.startsWith(pattern.substring(0, pattern.length() - 1));
        if (pattern.contains(",")) {
            for (String p : pattern.split(",")) if (p.trim().equals(pkg)) return true;
            return false;
        }
        return pattern.equals(pkg);
    }

    private static boolean isSkipText(String s) {
        if (s == null) return false;
        String t = s.trim();
        if (t.isEmpty() || t.length() > 12) return false;
        String low = t.toLowerCase(Locale.ROOT);
        if (low.equals("skip") || t.equals("跳过")) return true;
        return low.matches("skip\\s*\\d+\\s*s?") || t.matches("跳过\\s*\\d+\\s*(秒|s)?");
    }

    /** @return true 表示已发出点击动作；真实跳过需等按钮消失并观察到界面变化。 */
    public static boolean handle(A11y svc, AccessibilityNodeInfo root, String pkg) {
        if (root == null || TextUtils.isEmpty(pkg) || pkg.equals(svc.getPackageName())) return false;
        long now = System.currentTimeMillis();
        verifyPending(svc, root, pkg, now);
        synchronized (AdRules.class) {
            Long last = lastTry.get(pkg);
            if (last != null && now - last < 1100) return false;
            lastTry.put(pkg, now);
        }
        if (now - Bus.lastSkipTs < 1200) return false;
        if (loadedAt == 0) load(svc);
        if (!appEnabled(svc, pkg)) return false;
        Cfg cfg = Cfg.load(svc);
        if (cfg.b("adTest", false)) return false;
        List<Rule> matched = matchingRules(pkg);
        if (matched.isEmpty()) return false;
        int screen = screenSignature(root);
        long t0 = System.currentTimeMillis();
        List<String> path = new ArrayList<>();
        boolean adContext = hasAdContext(root, 0, new int[]{0});
        AccessibilityNodeInfo found = find(root, pkg, matched, 0, new int[]{0}, path, adContext);
        if (found == null) {
            synchronized (AdRules.class) {
                if (!pendingSkips.containsKey(pkg)) lastSkippedTarget.remove(pkg);
            }
            return false;
        }
        String targetKey = skipTargetKey(found);
        synchronized (AdRules.class) {
            String doneTarget = lastSkippedTarget.get(pkg);
            if (targetKey.equals(doneTarget) || pendingSkips.containsKey(pkg)) return false;
            if (doneTarget != null) lastSkippedTarget.remove(pkg);
        }
        AccessibilityNodeInfo clickable = found;
        for (int i = 0; i < 4 && clickable != null; i++) {
            if (clickable.isClickable() && eligible(clickable)) break;
            clickable = clickable.getParent();
        }
        boolean ok = false;
        String label = str(found.getText());
        try {
            if (clickable != null) {
                ok = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            }
        } catch (Throwable ignored) {
        }
        if (!ok) {
            record(svc, pkg, false, System.currentTimeMillis() - t0, label,
                    clickable == null ? "没有可见且可点击的父节点" : "点击被系统拒绝");
        }
        if (ok) {
            PendingSkip pending = new PendingSkip(targetKey, label, screen, now);
            synchronized (AdRules.class) {
                lastSkippedTarget.put(pkg, targetKey);
                pendingSkips.put(pkg, pending);
            }
            schedulePendingCheck(svc, pkg, pending);
        }
        return ok;
    }

    private static boolean eligible(AccessibilityNodeInfo node) {
        if (node == null) return false;
        try {
            return node.isEnabled() && visibleBounds(node);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean visibleBounds(AccessibilityNodeInfo node) {
        if (!visibleToUser(node)) return false;
        try {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            return bounds.width() > 0 && bounds.height() > 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean visibleToUser(AccessibilityNodeInfo node) {
        if (node == null) return false;
        try {
            return node.isVisibleToUser();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void verifyPending(A11y svc, AccessibilityNodeInfo root, String pkg, long now) {
        PendingSkip pending;
        synchronized (AdRules.class) {
            pending = pendingSkips.get(pkg);
        }
        if (pending == null) return;
        try {
            CharSequence rootPkg = root.getPackageName();
            if (rootPkg != null && !pkg.contentEquals(rootPkg)) {
                synchronized (AdRules.class) {
                    if (pendingSkips.get(pkg) == pending) {
                        pendingSkips.remove(pkg);
                        lastSkippedTarget.remove(pkg);
                    }
                }
                record(svc, pkg, false, now - pending.clickedAt, pending.label, "离开应用，无法确认跳过结果");
                return;
            }
        } catch (Throwable ignored) {
        }
        boolean stillVisible = containsTarget(root, pending.target, 0, new int[]{0});
        int current = screenSignature(root);
        if (!stillVisible && current != pending.screen) {
            synchronized (AdRules.class) {
                if (pendingSkips.get(pkg) != pending) return;
                pendingSkips.remove(pkg);
                lastSkippedTarget.remove(pkg);
            }
            Bus.lastSkipTs = now;
            record(svc, pkg, true, now - pending.clickedAt, pending.label, "跳过按钮消失且界面已变化");
            Data.bump(svc, "skips", 1);
            JSONObject ev = new JSONObject();
            try {
                ev.put("pkg", pkg);
                ev.put("label", pending.label);
                ev.put("skips", Data.stats(svc).optInt("skips", 0));
            } catch (Throwable ignored) {
            }
            Bus.emit("ad_skipped", ev);
        } else if (now - pending.clickedAt >= 2600L) {
            synchronized (AdRules.class) {
                if (pendingSkips.get(pkg) != pending) return;
                pendingSkips.remove(pkg);
                if (!stillVisible) lastSkippedTarget.remove(pkg);
            }
            record(svc, pkg, false, now - pending.clickedAt, pending.label, "点击后没有观察到广告跳过");
        }
    }

    private static void schedulePendingCheck(A11y svc, String pkg, PendingSkip pending) {
        MAIN.postDelayed(() -> {
            synchronized (AdRules.class) {
                if (pendingSkips.get(pkg) != pending) return;
            }
            AccessibilityNodeInfo latest = null;
            try {
                latest = svc.getRootInActiveWindow();
            } catch (Throwable ignored) {
            }
            if (latest != null) verifyPending(svc, latest, pkg, System.currentTimeMillis());
            synchronized (AdRules.class) {
                if (pendingSkips.get(pkg) != pending) return;
            }
            if (System.currentTimeMillis() - pending.clickedAt < 2700L) {
                schedulePendingCheck(svc, pkg, pending);
            } else {
                long endedAt = System.currentTimeMillis();
                if (latest != null) {
                    verifyPending(svc, latest, pkg, endedAt);
                } else {
                    synchronized (AdRules.class) {
                        if (pendingSkips.get(pkg) != pending) return;
                        pendingSkips.remove(pkg);
                    }
                    record(svc, pkg, false, endedAt - pending.clickedAt, pending.label, "无法读取界面，未确认跳过");
                }
            }
        }, 500L);
    }

    private static boolean containsTarget(AccessibilityNodeInfo node, String target, int depth, int[] count) {
        if (node == null || depth > 22 || count[0]++ >= 700) return false;
        try {
            if (eligible(node) && target.equals(skipTargetKey(node))) return true;
            for (int i = 0; i < node.getChildCount(); i++) {
                if (containsTarget(node.getChild(i), target, depth + 1, count)) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** Hash visible accessibility content while ignoring countdown digits and punctuation. */
    private static int screenSignature(AccessibilityNodeInfo root) {
        StringBuilder content = new StringBuilder(1024);
        appendScreenSignature(root, content, 0, new int[]{0});
        return content.toString().hashCode();
    }

    private static void appendScreenSignature(AccessibilityNodeInfo node, StringBuilder out, int depth, int[] count) {
        if (node == null || depth > 22 || count[0]++ >= 700) return;
        try {
            String text = stableSignatureText(str(node.getText()));
            String desc = stableSignatureText(str(node.getContentDescription()));
            String id = node.getViewIdResourceName();
            boolean clickable = node.isClickable();
            if (visibleToUser(node) && (!text.isEmpty() || !desc.isEmpty() || (id != null && !id.isEmpty()) || clickable)) {
                out.append(text).append('|').append(desc).append('|')
                        .append(id == null ? "" : id.toLowerCase(Locale.ROOT)).append('|');
                if (clickable) out.append("clickable");
                out.append(';');
            }
            int n = node.getChildCount();
            for (int i = 0; i < n; i++) appendScreenSignature(node.getChild(i), out, depth + 1, count);
        } catch (Throwable ignored) {
        }
    }

    private static String stableSignatureText(String value) {
        if (value == null || value.isEmpty()) return "";
        return value.toLowerCase(Locale.ROOT).replaceAll("\\d+", "")
                .replaceAll("[\\p{Punct}\\p{P}\\s]+", "").trim();
    }

    private static String skipTargetKey(AccessibilityNodeInfo node) {
        try {
            String id = node.getViewIdResourceName();
            CharSequence cls = node.getClassName();
            return (id == null ? "" : id.toLowerCase(Locale.ROOT)) + "|"
                    + stableSignatureText(str(node.getText())) + "|"
                    + stableSignatureText(str(node.getContentDescription())) + "|"
                    + (cls == null ? "" : cls.toString());
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static AccessibilityNodeInfo find(AccessibilityNodeInfo node, String pkg, List<Rule> rules, int depth, int[] count) {
        return find(node, pkg, rules, depth, count, null, false);
    }

    private static AccessibilityNodeInfo find(AccessibilityNodeInfo node, String pkg, List<Rule> rules, int depth, int[] count,
                                              List<String> path, boolean adContext) {
        if (node == null || depth > 22 || count[0] > 700) return null;
        count[0]++;
        String text = str(node.getText());
        String desc = str(node.getContentDescription());
        String id = node.getViewIdResourceName() == null ? "" : node.getViewIdResourceName();
        if (path != null) {
            String n = text.isEmpty() ? desc : text;
            path.add(n.isEmpty() ? (id.isEmpty() ? node.getClassName() == null ? "node" : str(node.getClassName()) : id) : n);
            if (path.size() > 40) path.remove(0);
        }

        String lowId = id.toLowerCase(Locale.ROOT);
        if (visibleBounds(node)) {
            for (Rule r : rules) {
                if (r.builtin) {
                    if (isExplicitAdSkip(text) || isExplicitAdSkip(desc)) return node;
                    if (adContext && (isSkipText(text) || isSkipText(desc))) return node;
                    if (adContext && isAdSkipId(lowId)) return node;
                    continue;
                }
                for (String k : r.keywords) {
                    if (!k.isEmpty() && (text.contains(k) || desc.contains(k))) return node;
                }
                for (String k : r.descs) {
                    if (!k.isEmpty() && desc.contains(k)) return node;
                }
                for (String k : r.ids) {
                    if (!k.isEmpty() && id.contains(k)) return node;
                }
            }
        }

        int n = node.getChildCount();
        for (int i = 0; i < n; i++) {
            AccessibilityNodeInfo child = null;
            try {
                child = node.getChild(i);
            } catch (Throwable ignored) {
            }
            if (child == null) continue;
            AccessibilityNodeInfo r = find(child, pkg, rules, depth + 1, count, path, adContext);
            if (r != null) return r;
        }
        return null;
    }

    /** Built-in rules deliberately target only semantic ad-skip controls, never arbitrary ad content. */
    private static List<Rule> matchingRules(String pkg) {
        List<Rule> out = new ArrayList<>();
        synchronized (AdRules.class) {
            for (Rule r : rules) if (pkgMatches(r.pkg, pkg)) out.add(r);
        }
        out.add(builtinRule());
        return out;
    }

    private static boolean isExplicitAdSkip(String value) {
        if (value == null) return false;
        String s = value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        return s.equals("跳过广告") || s.equals("跳过此广告") || s.equals("关闭广告")
                || s.equals("关闭此广告") || s.equals("skip ad") || s.equals("skip ads")
                || s.equals("skip advertisement") || s.equals("skip this ad");
    }

    private static boolean isAdSkipId(String lowId) {
        return lowId != null && (lowId.contains("ad_skip") || lowId.contains("adskip")
                || lowId.contains("skip_ad") || lowId.contains("skipad")
                || lowId.contains("close_ad") || lowId.contains("closead"));
    }

    private static boolean hasAdContext(AccessibilityNodeInfo node, int depth, int[] count) {
        if (node == null || depth > 22 || count[0]++ > 700) return false;
        try {
            String text = str(node.getText()).toLowerCase(Locale.ROOT);
            String desc = str(node.getContentDescription()).toLowerCase(Locale.ROOT);
            String id = node.getViewIdResourceName() == null ? "" : node.getViewIdResourceName().toLowerCase(Locale.ROOT);
            if (visibleToUser(node) && (text.contains("广告") || desc.contains("广告") || id.contains("advert")
                    || id.contains("sponsor") || text.contains("advertisement") || desc.contains("advertisement"))) return true;
            for (int i = 0; i < node.getChildCount(); i++) if (hasAdContext(node.getChild(i), depth + 1, count)) return true;
        } catch (Throwable ignored) { }
        return false;
    }

    private static String str(CharSequence s) {
        return s == null ? "" : s.toString();
    }
}
