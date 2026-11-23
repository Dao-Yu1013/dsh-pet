package com.dshpet.android;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Retrieves verified in-app help from the bundled, versioned feature guide. */
public final class AppGuide {
    private static final String ASSET = "app-guide.md";
    private static volatile String cached;

    private AppGuide() { }

    private static final class Section {
        String title = "";
        String keywords = "";
        StringBuilder body = new StringBuilder();
    }

    /** Add the complete feature index and the most relevant verified sections to chat context. */
    public static String prompt(Context c, String query) {
        String q = query == null ? "" : query.trim();
        if (q.isEmpty() || !isProductHelp(q)) return "";
        String doc = load(c);
        if (doc.isEmpty()) return "";
        List<Section> sections = parse(doc);
        if (sections.isEmpty()) return "";
        StringBuilder out = new StringBuilder(1800);
        out.append("应用功能知识库（按当前版本源码和界面整理）：先理解用户具体卡在哪里，再用自然语言给出所在页面、操作步骤及必要权限；"
                + "不要把不同子系统混为一谈，不要编造按钮、已安装插件、运行结果或设备权限。说明实现原理时可引用下列模块；"
                + "文档标明‘尚不支持/未接入’的能力必须如实说清。\n");
        boolean broad = has(q, "所有功能", "全部功能", "完整功能", "功能总览", "有哪些功能", "能做什么", "应用介绍", "这个软件有什么", "应用内的功能", "应用内所有");
        if (broad) {
            out.append(doc);
            return trim(out, 10500);
        }
        out.append("功能目录：聊天与附件、书架阅读、表情包、插件、广告跳过、记忆日记、动作陪伴、语音音乐、任务日程提醒、位置、健康、记账、通知、剪贴板、应用使用、备份同步、皮肤、设置和权限体检。\n");
        int[] scores = new int[sections.size()];
        String normalized = q.toLowerCase(Locale.ROOT);
        for (int i = 0; i < sections.size(); i++) {
            Section s = sections.get(i);
            for (String token : (s.title + "|" + s.keywords).split("[|，,、/ ]+")) {
                if (token.length() >= 2 && normalized.contains(token.toLowerCase(Locale.ROOT))) scores[i] += token.length() > 3 ? 2 : 1;
            }
        }
        boolean[] used = new boolean[sections.size()];
        int included = 0;
        while (included < 4) {
            int best = -1;
            for (int i = 0; i < scores.length; i++) if (!used[i] && (best < 0 || scores[i] > scores[best])) best = i;
            if (best < 0 || scores[best] == 0) break;
            used[best] = true;
            Section s = sections.get(best);
            out.append("\n## ").append(s.title).append('\n').append(s.body);
            included++;
        }
        if (included == 0) {
            out.append("\n无法从当前问题判断具体功能。不要猜具体按钮；先说明可回答应用功能问题，并只追问用户想了解哪个页面或现象。\n");
        }
        return trim(out, 5600);
    }

    /** Local fallback for users without a configured AI endpoint: concise and evidence-based. */
    public static String answer(Context c, String query) {
        String q = query == null ? "" : query.trim();
        if (!isProductHelp(q)) return "";
        String help = prompt(c, q);
        if (help.isEmpty()) return "";
        StringBuilder answer = new StringBuilder();
        List<Section> sections = parse(load(c));
        for (Section s : sections) {
            if (has(q, s.title)) {
                answer.append(s.title).append("：").append(s.body.toString().trim());
                break;
            }
        }
        if (answer.length() == 0) {
            if (has(q, "表情", "贴图")) return "聊天输入框旁边的笑脸用于发送表情；点加号，再点“表情包”可管理、导入和编辑表情。";
            if (has(q, "广告", "跳过")) return "广告跳过是“功能中心 → 广告跳过”里的无障碍规则，不是“插件”页的代码插件；需要开启无障碍服务。";
            if (has(q, "插件")) return "插件在“功能中心 → 插件与扩展”管理。聊天里生成的代码插件先是草稿，回复“装上”才安装；广告跳过规则在单独的广告跳过页。";
            if (has(q, "阅读", "看书", "书架")) return "书籍在“功能中心 → 阅读书架”导入和管理。聊天中可以说“开始阅读”，她会列出书架书目供你选。";
            return "我能介绍首页、聊天、书架、记忆、语音、插件、提醒、位置、日程和设置。告诉我你想找哪个功能，我按实际界面给你说。";
        }
        return answer.toString();
    }

    private static boolean isProductHelp(String q) {
        if (has(q, "这个软件", "本应用", "应用功能", "软件功能", "所有功能", "全部功能", "完整功能", "功能总览", "有哪些功能", "有什么功能",
                "能做什么", "应用介绍", "怎么实现", "实现原理", "工作原理", "架构", "原理", "應用內", "应用内", "功能中心", "桌宠功能", "怎么用", "怎样用",
                "如何用", "在哪", "哪里", "找不到", "不会用", "怎么开", "怎么关", "怎么设置", "如何设置",
                "权限", "操作步骤", "怎么操作", "怎么使用", "什么功能", "功能是什么", "有什么用")) return true;
        return has(q, "插件", "表情包", "书架", "看书", "阅读器", "提醒", "日程", "位置提醒", "广告跳过",
                "记忆图谱", "性格", "亲密度", "日记", "动作", "换装", "通知摘要", "剪贴板", "记账", "白噪音",
                "歌词", "WebDAV", "跨设备", "权限体检", "悬浮窗", "API Key", "关于", "输入框", "加号", "文件上传");
    }

    private static List<Section> parse(String markdown) {
        List<Section> list = new ArrayList<>();
        Section current = null;
        for (String line : markdown.split("\\r?\\n")) {
            if (line.startsWith("## ")) {
                current = new Section();
                current.title = line.substring(3).trim();
                list.add(current);
            } else if (current != null && line.startsWith("关键词：")) {
                current.keywords = line.substring(4).trim();
            } else if (current != null) {
                current.body.append(line).append('\n');
            }
        }
        return list;
    }

    private static String load(Context c) {
        String hit = cached;
        if (hit != null) return hit;
        if (c == null) return "";
        StringBuilder b = new StringBuilder();
        try (InputStream in = c.getAssets().open(ASSET);
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null && b.length() < 24000) b.append(line).append('\n');
        } catch (Throwable ignored) { return ""; }
        cached = b.toString();
        return cached;
    }

    private static boolean has(String q, String... words) {
        for (String word : words) if (q.contains(word) || q.toLowerCase(Locale.ROOT).contains(word.toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    private static String trim(StringBuilder b, int max) {
        return b.length() <= max ? b.toString() : b.substring(0, max) + "\n（知识片段已截短；仅根据可见内容回答。）";
    }
}
