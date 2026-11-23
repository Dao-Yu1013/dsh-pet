package com.dshpet.android;

import android.content.Context;
import android.content.res.AssetManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** 插件：只支持 JSON 配置，不执行任意代码。 */
public final class Plugins {
    private Plugins() {
    }

    public static File dir(Context c) {
        File d = new File(Sg.dir(c), "plugins");
        if (!d.exists()) //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        return d;
    }

    public static JSONArray list(Context c) {
        JSONArray out = new JSONArray();
        File[] fs = dir(c).listFiles();
        if (fs == null) return out;
        for (File f : fs) {
            if (!f.isDirectory()) continue;
            JSONObject m = manifest(f);
            if (m == null) continue;
            try {
                m.put("id", f.getName());
                m.put("enabled", enabled(c, f.getName()));
                m.put("builtin", new File(f, ".preset").isFile());
                m.put("dir", f.getAbsolutePath());
                m.put("quickCount", m.optJSONArray("quick") == null ? 0 : m.optJSONArray("quick").length());
                m.put("actionCount", m.optJSONArray("actions") == null ? 0 : m.optJSONArray("actions").length());
                m.put("hasCode", PluginCode.hasCode(m));
                m.put("native", DexPlugins.isNative(m));
                if (DexPlugins.isNative(m)) {
                    m.put("runtimeState", DexPlugins.runtimeState(f.getName()));
                    String runtimeError = DexPlugins.runtimeError(f.getName());
                    if (runtimeError != null && !runtimeError.isEmpty()) {
                        m.put("runtimeError", runtimeError);
                    }
                }
                m.put("codeSize", PluginCode.hasCode(m) ? PluginCode.code(c, m, f).length() : 0);
                JSONObject audit = PluginAudit.read(f);
                if (audit.length() > 0) {
                    m.put("audit", audit);
                    m.put("auditState", audit.optString("state", "pending"));
                    m.put("auditSummary", audit.optString("summary", "正在检查插件…"));
                    m.put("repairCandidate", audit.optString("repairCandidate", ""));
                }
                out.put(m);
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    private static JSONObject manifest(File d) {
        try {
            String s = Sg.read(new File(d, "manifest.json"), "");
            if (s.trim().isEmpty()) s = Sg.read(new File(d, "plugin.json"), "");
            if (s.trim().isEmpty()) return null;
            JSONObject o = new JSONObject(s);
            if (o.optJSONObject("plugin") != null) o = o.getJSONObject("plugin");
            return o;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 内置预设：首次启动或升级后自动装进 S-G/plugins，不覆盖用户已装/已删的同名插件。 */
    public static synchronized void ensurePresets(Context c) {
        final int ver = 2;
        Cfg cfg = Cfg.load(c);
        if (cfg.i("presetPluginsV", 0) >= ver) return;
        AssetManager am = c.getAssets();
        try {
            String[] ids = am.list("plugins");
            if (ids != null) {
                for (String id : ids) {
                    File dst = new File(dir(c), id);
                    if (dst.exists()) continue;
                    String raw = readAsset(am, "plugins/" + id + "/manifest.json");
                    if (raw.trim().isEmpty()) continue;
                    if (!dst.mkdirs()) continue;
                    Sg.write(new File(dst, "manifest.json"), raw);
                    Sg.write(new File(dst, ".preset"), id);
                }
            }
        } catch (Throwable ignored) {
        }
        cfg.set("presetPluginsV", ver);
        cfg.save(c);
        Bus.emit("plugins", new JSONObject());
    }

    private static String readAsset(AssetManager am, String name) {
        try (InputStream in = am.open(name)) {
            byte[] b = readAll(in, 512 * 1024);
            return new String(b, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return "";
        }
    }

    private static byte[] readAll(InputStream in, int limit) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        int total = 0;
        while ((n = in.read(b)) > 0) {
            total += n;
            if (total > limit) throw new Exception("插件包过大");
            out.write(b, 0, n);
        }
        return out.toByteArray();
    }

    public static boolean enabled(Context c, String id) {
        JSONArray off = Cfg.load(c).arr("pluginOff");
        for (int k = 0; k < off.length(); k++) if (id.equals(off.optString(k))) return false;
        return true;
    }

    public static void toggle(Context c, String id, boolean on) {
        Cfg cfg = Cfg.load(c);
        JSONArray a = cfg.arr("pluginOff");
        JSONArray b = new JSONArray();
        boolean had = false;
        for (int k = 0; k < a.length(); k++) {
            String p = a.optString(k);
            if (p.equals(id)) had = true;
            if (!p.equals(id)) b.put(p);
        }
        if (!on && !had) b.put(id);
        cfg.set("pluginOff", b);
        cfg.save(c);
        // 原生插件没有沙箱，开关直接对应 start/stop
        DexPlugins.sync(c);
        // 定时插件的开关会影响闹钟，立刻重挂
        TickAlarm.schedule(c);
    }

    /** 安装 zip 或单个 JSON 插件包，返回 manifest。 */
    public static synchronized JSONObject install(Context c, InputStream in) {
        JSONObject out = new JSONObject();
        File tmp = null;
        File stagedPlugin = null;
        boolean installedComplete = false;
        try {
            byte[] data = readAll(in, 16 * 1024 * 1024);
            if (data.length < 4) throw new Exception("插件文件为空");
            tmp = File.createTempFile("plugin", "", c.getCacheDir());
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            //noinspection ResultOfMethodCallIgnored
            tmp.mkdirs();
            boolean zip = data[0] == 'P' && data[1] == 'K';
            if (zip) {
                Sg.unzipTo(new ByteArrayInputStream(data), tmp);
            } else {
                String text = new String(data, StandardCharsets.UTF_8).trim();
                JSONObject one = new JSONObject(text);
                if (one.optJSONObject("plugin") != null) one = one.getJSONObject("plugin");
                if (!one.has("id") && !one.has("name")) throw new Exception("不是有效的插件 JSON");
                Sg.write(new File(tmp, "manifest.json"), one.toString());
            }
            File root = findRoot(tmp);
            if (root == null) throw new Exception("插件包缺少 manifest.json / plugin.json");
            JSONObject m = manifest(root);
            if (m == null) throw new Exception("插件清单格式不正确");
            String id = m.optString("id", "").trim();
            if (id.isEmpty()) id = "p" + System.currentTimeMillis();
            id = id.replaceAll("[^A-Za-z0-9_-]", "");
            if (id.length() > 32) id = id.substring(0, 32);
            File dst = new File(dir(c), id);
            if (dst.exists()) {
                String base = id.length() > 24 ? id.substring(0, 24) : id;
                int suffix = 2;
                do { id = base + "-" + suffix++; dst = new File(dir(c), id); }
                while (dst.exists() && suffix < 1000);
                if (dst.exists()) throw new Exception("找不到可用的插件副本名称；原插件已保留");
            }
            stagedPlugin = dst;
            copyTree(root, dst);
            // Keep a broken but readable package so the report can explain what failed.
            JSONObject installed = manifest(dst);
            if (installed == null) { deleteTree(dst); throw new Exception("插件清单格式不正确"); }
            installed.put("id", id);
            Sg.write(new File(dst, "manifest.json"), installed.toString());
            JSONObject audit = PluginAudit.inspect(dst, installed);
            PluginAudit.write(dst, audit);
            // An imported plugin is staged disabled. Reviewing its code must never execute it.
            toggle(c, id, false);
            installedComplete = true;
            out = manifest(dst);
            if (out != null) {
                out.put("id", id);
                out.put("enabled", false);
                out.put("auditState", audit.optString("state", "review"));
            }
            reviewImported(c, id);
            Bus.emit("plugins", new JSONObject());
        } catch (Throwable t) {
            if (!installedComplete && stagedPlugin != null && stagedPlugin.exists()) deleteTree(stagedPlugin);
            try {
                out.put("error", t.getMessage() == null ? "安装失败" : t.getMessage());
            } catch (Throwable ignored) {
            }
        } finally {
            if (tmp != null) deleteTree(tmp);
        }
        return out;
    }

    /** Run non-invasive checks first, then ask the configured AI for a compatibility report. */
    private static void reviewImported(Context c, String id) {
        File d = new File(dir(c), id);
        JSONObject manifest = manifest(d);
        if (manifest == null) return;
        JSONObject report = PluginAudit.read(d);
        try { report.put("aiState", Cfg.load(c).aiReady() ? "running" : "unavailable"); }
        catch (Throwable ignored) { }
        PluginAudit.write(d, report);
        boolean nativePlugin = DexPlugins.isNative(manifest);
        String source = nativePlugin
                ? readContainedSource(d, manifest.optString("nativeSource", "source.java"))
                : PluginCode.code(c, manifest, d);
        if (!nativePlugin && PluginCode.hasCode(manifest)
                && !"blocked".equals(report.optString("state", ""))) {
            final String code = source;
            PluginCode.dryRun(c, id + "-audit", code,
                    manifest.optBoolean("background", false), new PluginCode.Cb() {
                @Override public void done(String line, String action, String type) {
                    JSONObject current = PluginAudit.read(d);
                    try {
                        current.put("dryRun", true);
                        current.put("dryRunStatus", "passed");
                        current.put("dryRunSummary", "JS 入口已在屏蔽操作、截图、记忆写入和网络的沙箱中试跑；输出未执行。");
                        if (!line.isEmpty()) current.put("dryRunOutput", line.substring(0, Math.min(180, line.length())));
                        if (!action.isEmpty()) current.put("dryRunAction", action);
                    } catch (Throwable ignored) { }
                    PluginAudit.write(d, current);
                    requestAiReview(c, id, manifest, code, false);
                }
                @Override public void fail(String reason) {
                    JSONObject current = PluginAudit.read(d);
                    try {
                        current.put("dryRun", true);
                        current.put("dryRunStatus", "failed");
                        current.put("dryRunSummary", "沙箱试跑失败：" + (reason == null || reason.isEmpty() ? "脚本未能完成入口调用" : reason));
                    } catch (Throwable ignored) { }
                    PluginAudit.write(d, current);
                    requestAiReview(c, id, manifest, code, false);
                }
            });
        } else {
            requestAiReview(c, id, manifest, source, nativePlugin);
        }
    }

    private static void requestAiReview(Context c, String id, JSONObject manifest, String source, boolean nativePlugin) {
        File d = new File(dir(c), id);
        if (!Cfg.load(c).aiReady()) {
            JSONObject report = PluginAudit.read(d);
            try {
                report.put("aiState", "unavailable");
                report.put("aiSummary", "未配置聊天模型；已完成本机静态检查" + (PluginCode.hasCode(manifest) && !nativePlugin ? "和隔离试跑" : "") + "。");
            } catch (Throwable ignored) { }
            PluginAudit.write(d, report);
            Bus.emit("plugins", new JSONObject());
            return;
        }
        PluginAudit.askAi(c, manifest, source, nativePlugin, new Ai.Cb() {
            @Override public void ok(String text) {
                JSONObject review = PluginAudit.parseAi(text);
                JSONObject report = PluginAudit.read(d);
                if (review == null) {
                    try { report.put("aiState", "failed"); report.put("aiSummary", "模型没有返回可读取的审查报告；本机静态结果仍然保留。"); }
                    catch (Throwable ignored) { }
                    PluginAudit.write(d, report);
                    Bus.emit("plugins", new JSONObject());
                    return;
                }
                String summary = review.optString("summary", "").trim();
                JSONArray issues = review.optJSONArray("issues");
                String repaired = review.optString("repairCode", "");
                JSONObject repairedManifest = review.optJSONObject("repairManifest");
                try {
                    report.put("aiState", "complete");
                    report.put("aiSummary", summary.isEmpty() ? "审查已完成。" : summary);
                    if (issues != null) report.put("aiIssues", issues);
                } catch (Throwable ignored) { }
                boolean sourceCanBeTranslated = !source.trim().isEmpty();
                boolean hasRepairableSource = PluginCode.hasCode(manifest)
                        || (nativePlugin && !source.trim().isEmpty());
                if (sourceCanBeTranslated && hasRepairableSource && repaired.length() > 0
                        && source.length() <= 18000
                        && repaired.length() <= 65536
                        && PluginAudit.hasEntry(repaired, manifest.optBoolean("background", false))) {
                    try { report.put("repairStatus", "testing"); }
                    catch (Throwable ignored) { }
                    PluginAudit.write(d, report);
                    PluginCode.dryRun(c, id + "-repair-check", repaired,
                            manifest.optBoolean("background", false), new PluginCode.Cb() {
                        @Override public void done(String line, String action, String type) {
                            String candidate = createRepairCopy(c, id, manifest, repaired);
                            JSONObject latest = PluginAudit.read(d);
                            try {
                                if (candidate.isEmpty()) {
                                    latest.put("repairStatus", "failed");
                                    latest.put("repairSummary", "修复脚本通过隔离试跑，但未能保存独立副本。");
                                } else {
                                    latest.put("repairStatus", "ready");
                                    latest.put("repairCandidate", candidate);
                                    latest.put("repairSummary", "AI 修复副本已保存并通过隔离试跑；副本默认关闭，原插件未改动。试跑没有验证真实网络或手机操作。");
                                }
                            } catch (Throwable ignored) { }
                            PluginAudit.write(d, latest);
                            Bus.emit("plugins", new JSONObject());
                        }
                        @Override public void fail(String reason) {
                            JSONObject latest = PluginAudit.read(d);
                            try {
                                latest.put("repairStatus", "failed");
                                latest.put("repairSummary", "AI 提供了修复脚本，但隔离试跑未通过：" + (reason == null ? "" : reason));
                            } catch (Throwable ignored) { }
                            PluginAudit.write(d, latest);
                            Bus.emit("plugins", new JSONObject());
                        }
                    });
                } else if (!nativePlugin && !PluginCode.hasCode(manifest)
                        && repairedManifest != null && repairedManifest.length() > 0) {
                    String candidate = createConfigRepairCopy(c, id, manifest, repairedManifest);
                    try {
                        if (candidate.isEmpty()) {
                            report.put("repairStatus", "failed");
                            report.put("repairSummary", "AI 给出了配置副本，但结构检查没有通过；原插件保持不变。");
                        } else {
                            report.put("repairStatus", "ready");
                            report.put("repairCandidate", candidate);
                            report.put("repairSummary", "AI 配置副本已通过本机规则/入口检查；副本默认关闭，原插件未改动。此检查不等于对每种触发场景都完成设备验证。");
                        }
                    } catch (Throwable ignored) { }
                    PluginAudit.write(d, report);
                    Bus.emit("plugins", new JSONObject());
                } else {
                    if (!nativePlugin && hasRepairableSource && source.length() > 18000) {
                        try {
                            report.put("repairStatus", "manual");
                            report.put("repairSummary", "源码超过模型的完整审查范围；已给出局部兼容性建议，但为避免误改，没有自动生成副本。");
                        } catch (Throwable ignored) { }
                    } else if (nativePlugin) {
                        try {
                            report.put("repairStatus", "manual");
                            report.put("repairSummary", source.trim().isEmpty()
                                    ? "包内没有可用源码，DEX 无法可靠还原；只完成清单与文件检查，未执行原插件。"
                                    : "没有得到可通过隔离试跑的 JS 兼容副本；原生 DEX 未执行，原插件也未修改。");
                        }
                        catch (Throwable ignored) { }
                    }
                    PluginAudit.write(d, report);
                    Bus.emit("plugins", new JSONObject());
                }
            }
            @Override public void fail(String error) {
                JSONObject report = PluginAudit.read(d);
                try { report.put("aiState", "failed"); report.put("aiSummary", "AI 审查未完成：" + (error == null ? "请求失败" : error)); }
                catch (Throwable ignored) { }
                PluginAudit.write(d, report);
                Bus.emit("plugins", new JSONObject());
            }
        });
    }

    /** Write an isolated JS compatibility copy; never carry or load the original DEX. */
    private static synchronized String createRepairCopy(Context c, String originalId, JSONObject original, String code) {
        File targetDir = null;
        try {
            String id = uniqueRepairId(c, originalId);
            if (id.isEmpty()) return "";
            targetDir = new File(dir(c), id);
            if (!targetDir.mkdirs()) return "";
            JSONObject repaired = repairBase(original, id, "（兼容副本）");
            repaired.put("code", code);
            if (original.optBoolean("background", false)) repaired.put("background", true);
            JSONArray hosts = original.optJSONArray("hosts");
            if (hosts != null) repaired.put("hosts", hosts);
            Sg.write(new File(targetDir, "manifest.json"), repaired.toString());
            copyConfigFields(original, repaired);
            Sg.write(new File(targetDir, "manifest.json"), repaired.toString());
            JSONObject candidateAudit = PluginAudit.inspect(targetDir, repaired);
            if ("blocked".equals(candidateAudit.optString("state", ""))) {
                deleteTree(targetDir);
                return "";
            }
            candidateAudit.put("aiState", "complete");
            candidateAudit.put("aiSummary", "这是由 AI 检测后生成的独立修复副本，尚未进行真实网络或手机操作验证。");
            candidateAudit.put("dryRun", true);
            candidateAudit.put("dryRunStatus", "passed");
            candidateAudit.put("dryRunSummary", "脚本入口已通过隔离沙箱试跑，真实操作均被拦截。");
            PluginAudit.write(targetDir, candidateAudit);
            toggle(c, id, false);
            Bus.emit("plugins", new JSONObject());
            return id;
        } catch (Throwable ignored) {
            if (targetDir != null) deleteTree(targetDir);
            return "";
        }
    }

    /** Validate the model-produced config, then save only the supported manifest fields. */
    private static synchronized String createConfigRepairCopy(Context c, String originalId,
                                                               JSONObject original, JSONObject proposed) {
        File targetDir = null;
        try {
            if (proposed == null || proposed.length() == 0
                    || !proposed.optString("main", "").trim().isEmpty()
                    || !proposed.optString("code", "").trim().isEmpty()) return "";
            String id = uniqueRepairId(c, originalId);
            if (id.isEmpty()) return "";
            targetDir = new File(dir(c), id);
            if (!targetDir.mkdirs()) return "";
            JSONObject repaired = new JSONObject();
            repaired.put("id", id);
            repaired.put("name", original.optString("name", originalId) + "（配置修复副本）");
            repaired.put("version", proposed.optString("version", original.optString("version", "1.0.0")));
            repaired.put("description", proposed.optString("description", original.optString("description", "AI 检查后生成的本机配置副本")));
            if (!original.optString("author", "").trim().isEmpty()) repaired.put("author", original.optString("author"));
            for (String key : new String[]{"rules", "quick", "actions"}) {
                JSONArray values = proposed.optJSONArray(key);
                if (values != null) {
                    if (values.length() > 200) throw new Exception("修复配置项目过多");
                    repaired.put(key, values);
                }
            }
            if (repaired.toString().length() > 65536) throw new Exception("修复配置超过 64 KB");
            Sg.write(new File(targetDir, "manifest.json"), repaired.toString());
            JSONObject validation = PluginAudit.inspect(targetDir, repaired);
            if ("blocked".equals(validation.optString("state"))
                    || !PluginAudit.hasConfigFeature(repaired)) {
                deleteTree(targetDir);
                return "";
            }
            validation.put("aiState", "complete");
            validation.put("aiSummary", "这是根据原插件配置生成的本机兼容副本，已通过结构检查。启用前仍需确认实际触发效果。");
            validation.put("repairStatus", "ready");
            validation.put("dryRun", true);
            validation.put("dryRunStatus", "passed");
            validation.put("dryRunSummary", "本机已验证配置包含可用规则、快捷指令或动作；没有对手机执行动作。");
            PluginAudit.write(targetDir, validation);
            toggle(c, id, false);
            Bus.emit("plugins", new JSONObject());
            return id;
        } catch (Throwable ignored) {
            if (targetDir != null) deleteTree(targetDir);
            return "";
        }
    }

    private static JSONObject repairBase(JSONObject original, String id, String suffix) throws Exception {
        JSONObject repaired = new JSONObject();
        repaired.put("id", id);
        repaired.put("name", original.optString("name", "插件") + suffix);
        repaired.put("version", original.optString("version", "1.0.0"));
        repaired.put("description", original.optString("description", "AI 检查后生成的本机兼容副本"));
        if (!original.optString("author", "").trim().isEmpty()) repaired.put("author", original.optString("author"));
        return repaired;
    }

    private static void copyConfigFields(JSONObject source, JSONObject target) throws Exception {
        for (String key : new String[]{"quick", "rules", "actions"}) {
            JSONArray value = source.optJSONArray(key);
            if (value != null) target.put(key, value);
        }
    }

    private static String readContainedSource(File pluginDir, String relative) {
        if (pluginDir == null || relative == null || relative.trim().isEmpty()
                || !relative.toLowerCase(java.util.Locale.ROOT).endsWith(".java")) return "";
        try {
            File root = pluginDir.getCanonicalFile();
            File file = new File(root, relative).getCanonicalFile();
            if (!file.getPath().startsWith(root.getPath() + File.separator)
                    || !file.isFile() || file.length() > 65536L) return "";
            return Sg.read(file, "");
        } catch (Throwable ignored) { return ""; }
    }

    private static String uniqueRepairId(Context c, String originalId) {
        String rootId = originalId == null ? "plugin" : originalId.replaceAll("[^A-Za-z0-9_-]", "");
        if (rootId.isEmpty()) rootId = "plugin";
        File root = dir(c);
        for (int n = 1; n < 1000; n++) {
            String suffix = n == 1 ? "-repair" : "-repair" + n;
            String prefix = rootId.substring(0, Math.min(rootId.length(), 32 - suffix.length()));
            String id = prefix + suffix;
            if (!new File(root, id).exists()) return id;
        }
        return "";
    }

    /** Refuse an imported plugin with a known fatal structural issue when user enables it. */
    public static String enableBlockReason(Context c, String id) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,32}")) return "插件 ID 不合法。";
        File d = new File(dir(c), id);
        JSONObject pluginManifest = manifest(d);
        if (!d.isDirectory() || pluginManifest == null) return "找不到这个插件的有效清单。";
        JSONObject previous = PluginAudit.read(d);
        JSONObject audit = PluginAudit.inspect(d, pluginManifest);
        for (String key : new String[]{"aiState", "aiSummary", "aiIssues", "repairStatus", "repairCandidate",
                "repairSummary", "dryRun", "dryRunStatus", "dryRunSummary", "dryRunOutput", "dryRunAction"}) {
            if (previous.has(key)) {
                try { audit.put(key, previous.get(key)); } catch (Throwable ignored) { }
            }
        }
        PluginAudit.write(d, audit);
        if ("blocked".equals(audit.optString("state", ""))) {
            return audit.optString("summary", "插件存在本机检测到的阻断项。") + " 请先查看检测报告或使用兼容副本。";
        }
        return "";
    }

    public static boolean isNativePlugin(Context c, String id) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,32}")) return false;
        return DexPlugins.isNative(manifest(new File(dir(c), id)));
    }

    public static void remove(Context c, String id) {
        DexPlugins.stop(id);
        File d = new File(dir(c), id);
        deleteTree(d);
        Bus.emit("plugins", new JSONObject());
    }

    /** 快捷指令返回要发给 AI 的提示词。 */
    public static JSONObject quick(Context c, String id, String quickId) {
        JSONObject out = new JSONObject();
        try {
            JSONObject m = manifest(new File(dir(c), id));
            if (m == null) return out;
            JSONArray q = m.optJSONArray("quick");
            if (q == null) return out;
            for (int k = 0; k < q.length(); k++) {
                JSONObject o = q.optJSONObject(k);
                if (o == null || !quickId.equals(o.optString("id"))) continue;
                out = o;
                break;
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** 插件动作台词，本地优先。 */
    public static String actionLine(Context c, String slug) {
        try {
            JSONArray ps = list(c);
            for (int k = 0; k < ps.length(); k++) {
                JSONObject p = ps.optJSONObject(k);
                if (p == null || !p.optBoolean("enabled", true)) continue;
                JSONArray acts = p.optJSONArray("actions");
                if (acts == null) continue;
                for (int j = 0; j < acts.length(); j++) {
                    JSONObject a = acts.optJSONObject(j);
                    if (a != null && slug.equals(a.optString("slug")) && !a.optString("line").isEmpty()) {
                        return a.optString("line");
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    /** 主动规则匹配：keywords 命中即返回 line/action。 */
    public static JSONObject match(Context c, String text) {
        JSONObject out = new JSONObject();
        if (text == null || text.isEmpty()) return out;
        try {
            JSONArray ps = list(c);
            for (int k = 0; k < ps.length(); k++) {
                JSONObject p = ps.optJSONObject(k);
                if (p == null || !p.optBoolean("enabled", true)) continue;
                JSONArray rs = p.optJSONArray("rules");
                if (rs == null) continue;
                for (int j = 0; j < rs.length(); j++) {
                    JSONObject r = rs.optJSONObject(j);
                    if (r == null) continue;
                    JSONArray kw = r.optJSONArray("keywords");
                    if (kw == null) continue;
                    for (int x = 0; x < kw.length(); x++) {
                        String w = kw.optString(x, "");
                        if (!w.isEmpty() && text.contains(w)) {
                            out.put("plugin", p.optString("id"));
                            out.put("line", r.optString("line", ""));
                            out.put("action", r.optString("action", ""));
                            out.put("type", r.optString("type", "plugin"));
                            return out;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /* ---------------- 代码插件：按顺序在沙箱里跑 ---------------- */

    public interface CodeCb {
        void done(JSONObject result);
    }

    /**
     * 依次执行启用的代码插件，第一个返回 say/action 的结果生效；
     * 全部没结果时返回空对象。WebView 沙箱一次只跑一个，避免互相干扰。
     */
    public static void matchCode(Context c, String text, String pkg, CodeCb cb) {
        final java.util.ArrayList<JSONObject> queue = new java.util.ArrayList<>();
        try {
            JSONArray ps = list(c);
            for (int k = 0; k < ps.length(); k++) {
                JSONObject p = ps.optJSONObject(k);
                if (p == null || !p.optBoolean("enabled", true) || !p.optBoolean("hasCode", false)) continue;
                queue.add(p);
            }
        } catch (Throwable ignored) {
        }
        stepCode(c, queue, 0, text, pkg, cb);
    }

    private static void stepCode(Context c, java.util.List<JSONObject> queue, int index,
                                 String text, String pkg, CodeCb cb) {
        if (queue.isEmpty() || index >= queue.size()) {
            cb.done(new JSONObject());
            return;
        }
        JSONObject p = queue.get(index);
        File d = new File(dir(c), p.optString("id", ""));
        JSONObject m = manifest(d);
        String code = PluginCode.code(c, m, d);
        if (code.trim().isEmpty()) {
            stepCode(c, queue, index + 1, text, pkg, cb);
            return;
        }
        // 联网白名单跟着清单走：只有这里列出的域名，插件里的 http() 才发得出去
        java.util.List<String> hosts = new java.util.ArrayList<>();
        JSONArray hs = m == null ? null : m.optJSONArray("hosts");
        for (int k = 0; hs != null && k < hs.length(); k++) hosts.add(hs.optString(k, ""));
        PluginCode.runCode(c, p.optString("id", ""), code, text, pkg, hosts, (line, action, type) -> {
            if (!line.isEmpty() || !action.isEmpty()) {
                JSONObject out = new JSONObject();
                try {
                    out.put("plugin", p.optString("id", ""));
                    out.put("line", line);
                    out.put("action", action);
                    out.put("type", type.isEmpty() ? "plugin" : type);
                } catch (Throwable ignored) {
                }
                cb.done(out);
                return;
            }
            stepCode(c, queue, index + 1, text, pkg, cb);
        });
    }

    /**
     * 定时后台插件：服务每分钟问一次，只有清单里写了 background=true 且到点的才跑。
     * 同一时间只跑一个；多个插件同时到点时挑最早该跑的那个，避免高频插件把别的饿死。
     */
    public static void tickBackground(Context c) {
        try {
            tickOnce(c);
        } finally {
            // 每轮结束（含跳过）都按最新的下次时间重挂闹钟，进程被杀也能被叫起来
            TickAlarm.schedule(c);
        }
    }

    private static void tickOnce(Context c) {
        DexPlugins.dispatchTick(c);
        if (PluginCode.isBusy()) return;
        // 屏幕关着/锁屏、免打扰、夜间、你刚在动手机：都不动手，也不记失败
        try {
            if (!A11y.awake(c)) return;
            if (Policy.isQuiet(c)) return;
            if (Policy.nightNow(Cfg.load(c))) return;
            long lastUser = Interact.lastUserAt(c);
            if (lastUser > 0 && System.currentTimeMillis() - lastUser < 90000L) return;
        } catch (Throwable ignored) {
        }
        JSONObject pick = null;
        File pickDir = null;
        File pickTick = null;
        long pickAt = Long.MAX_VALUE;
        int pickMin = 10;
        try {
            JSONArray ps = list(c);
            long now = System.currentTimeMillis();
            for (int k = 0; k < ps.length(); k++) {
                JSONObject p = ps.optJSONObject(k);
                if (p == null || !p.optBoolean("enabled", true) || !p.optBoolean("hasCode", false)) continue;
                if (!p.optBoolean("background", false)) continue;
                String id = p.optString("id", "");
                File d = new File(dir(c), id);
                JSONObject m = manifest(d);
                if (m == null) continue;
                int tickMin = Math.max(2, Math.min(240, m.optInt("tickMin", 10)));
                long nextAt = 0;
                try {
                    String s = Sg.read(new File(d, "tick.json"), "");
                    if (!s.trim().isEmpty()) nextAt = new JSONObject(s).optLong("nextAt", 0);
                } catch (Throwable ignored) {
                }
                if (nextAt > now) continue;
                // 挑欠得最久的那个，而不是永远第一个
                if (nextAt < pickAt) {
                    pickAt = nextAt;
                    pick = p;
                    pickDir = d;
                    pickTick = new File(d, "tick.json");
                    pickMin = tickMin;
                }
            }
        } catch (Throwable ignored) {
        }
        if (pick == null || pickDir == null) return;
        final String id = pick.optString("id", "");
        final String name = pick.optString("name", "插件");
        try {
            JSONObject t = new JSONObject();
            t.put("nextAt", System.currentTimeMillis() + pickMin * 60000L);
            t.put("fails", 0);
            Sg.write(pickTick, t.toString());
        } catch (Throwable ignored) {
        }
        String code = PluginCode.code(c, pick, pickDir);
        java.util.List<String> hosts = new java.util.ArrayList<>();
        JSONArray hs = pick.optJSONArray("hosts");
        for (int j = 0; hs != null && j < hs.length(); j++) hosts.add(hs.optString(j, ""));
        PluginCode.runTick(c, id, code, hosts, new PluginCode.Cb() {
            @Override
            public void done(String line, String action, String type) {
                if (line != null && !line.isEmpty()) {
                    PetSvc.sayPlugin(c, line);
                    // 刷完说的话也进聊天记录，之后能回看她看过什么
                    try {
                        JSONObject extra = new JSONObject();
                        extra.put("source", "plugin_tick");
                        Data.chatAppend(c, "assistant", line, "", "", extra);
                    } catch (Throwable ignored) {
                    }
                }
            }

            @Override
            public void fail(String reason) {
                failTick(c, id, name, reason);
            }
        });
    }

    /** 连续三次没跑起来就自动停用，只提醒一次，避免每分钟空转报错。 */
    private static void failTick(Context c, String id, String name, String reason) {
        if ("我正忙着跑另一个插件，这次先跳过".equals(reason)) return;
        try {
            File tf = new File(new File(dir(c), id), "tick.json");
            JSONObject o = new JSONObject(Sg.read(tf, "{}"));
            int fails = o.optInt("fails", 0) + 1;
            o.put("fails", fails);
            Sg.write(tf, o.toString());
            if (fails >= 3) {
                o.put("fails", 0);
                Sg.write(tf, o.toString());
                toggle(c, id, false);
                PetSvc.sayPlugin(c, "「" + name + "」连着三次没跑起来，我先把它停了。"
                        + "你说「看一下" + name + "为什么跑不了」，我就能告诉你原因。");
            }
        } catch (Throwable ignored) {
        }
    }

    private static File findRoot(File d) {
        if (new File(d, "manifest.json").isFile() || new File(d, "plugin.json").isFile()) return d;
        File[] fs = d.listFiles();
        if (fs == null) return null;
        for (File f : fs) {
            if (f.isDirectory()) {
                File r = findRoot(f);
                if (r != null) return r;
            }
        }
        return null;
    }

    private static void copyTree(File src, File dst) {
        if (!dst.exists()) //noinspection ResultOfMethodCallIgnored
            dst.mkdirs();
        File[] fs = src.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            File o = new File(dst, f.getName());
            if (f.isDirectory()) copyTree(f, o);
            else {
                try (InputStream in = new FileInputStream(f); java.io.FileOutputStream out = new java.io.FileOutputStream(o)) {
                    byte[] b = new byte[8192];
                    int n;
                    while ((n = in.read(b)) > 0) out.write(b, 0, n);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void deleteTree(File f) {
        File[] fs = f.listFiles();
        if (fs != null) for (File x : fs) {
            if (x.isDirectory()) deleteTree(x);
            else //noinspection ResultOfMethodCallIgnored
                x.delete();
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
