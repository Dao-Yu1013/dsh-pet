package com.dshpet.android;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import org.json.JSONObject;

/** Fixed, locally validated actions exposed to the optional remote AstrBot tool bridge. */
public final class MobileActionExecutor {
    private static final int MAX_SCREEN_TEXT = 1800;

    private MobileActionExecutor() { }

    public static JSONObject confirmation(Context context, JSONObject command) {
        JSONObject out = new JSONObject();
        String action = command == null ? "" : command.optString("action", "");
        JSONObject args = command == null ? null : command.optJSONObject("args");
        String target = args == null ? "" : args.optString("target", "").trim();
        String direction = args == null ? "" : args.optString("direction", "").trim();
        String pkg = args == null ? "" : args.optString("target_package", "").trim();
        target = safeDisplayValue(target, 64);
        direction = safeDisplayValue(direction, 8);
        pkg = safeDisplayValue(pkg.isEmpty() ? A11y.foregroundPackage() : pkg, 180);
        try {
            out.put("requestId", command == null ? "" : command.optString("request_id", ""));
            out.put("action", action);
            out.put("target", target);
            out.put("direction", direction);
            out.put("targetPackage", pkg);
            out.put("title", actionTitle(action));
            out.put("description", actionDescription(context, action, target, direction, pkg));
        } catch (Throwable ignored) { }
        return out;
    }

    public static JSONObject execute(Context context, JSONObject command) {
        String action = command == null ? "" : command.optString("action", "").trim();
        JSONObject args = command == null ? null : command.optJSONObject("args");
        if (args == null) args = new JSONObject();
        String target = args.optString("target", "").trim();
        String direction = args.optString("direction", "").trim().toLowerCase(java.util.Locale.ROOT);
        String expectedPackage = args.optString("target_package", "").trim();
        try {
            switch (action) {
                case "read_screen": {
                    if (!A11y.ready()) return error("手机无障碍服务未连接；没有读取屏幕");
                    if (!validPackage(expectedPackage) || !expectedPackage.equals(A11y.foregroundPackage()))
                        return error("前台应用已变化或无法确认；没有读取屏幕");
                    String pkg = A11y.foregroundPackage();
                    if (pkg.isEmpty()) return error("无法确认当前前台应用；没有读取屏幕");
                    String text = A11y.visibleTextForRemote(pkg);
                    if (text == null || text.trim().isEmpty()) return error("当前应用没有提供可读取的屏幕文字");
                    text = text.trim();
                    if (text.length() > MAX_SCREEN_TEXT) text = text.substring(0, MAX_SCREEN_TEXT);
                    return new JSONObject().put("ok", true).put("package", pkg)
                            .put("untrusted_screen_text", text);
                }
                case "open_app": {
                    if (!validPackage(target) || context.getPackageName().equals(target))
                        return error("应用包名无效或不允许打开桌宠自身");
                    PackageManager pm = context.getPackageManager();
                    ApplicationInfo info;
                    try { info = pm.getApplicationInfo(target, 0); }
                    catch (PackageManager.NameNotFoundException e) { return error("手机上没有安装指定应用"); }
                    if (pm.getLaunchIntentForPackage(target) == null) return error("该应用没有可启动的主界面");
                    boolean ok = A11y.openApp(context, target);
                    String label = String.valueOf(pm.getApplicationLabel(info));
                    return new JSONObject().put("ok", ok).put("message", ok ? "已请求打开应用：" + label : "系统未能打开该应用")
                            .put("package", target);
                }
                case "tap_text": {
                    if (!A11y.ready()) return error("手机无障碍服务未连接；没有执行点击");
                    if (target.isEmpty() || target.length() > 64 || !validPackage(expectedPackage))
                        return error("点击请求缺少有效的按钮文字或目标应用");
                    if (!expectedPackage.equals(A11y.foregroundPackage())) return error("前台应用已变化；拒绝点击旧页面");
                    boolean ok = A11y.tapTextExact(target, expectedPackage);
                    return new JSONObject().put("ok", ok).put("message", ok ? "已点击当前页面中唯一匹配的文字" : "没有找到唯一、可点击的精确文字；未执行点击")
                            .put("package", expectedPackage);
                }
                case "swipe": {
                    if (!A11y.ready()) return error("手机无障碍服务未连接；没有执行滑动");
                    if (!validPackage(expectedPackage)) return error("滑动请求缺少目标应用");
                    if (!expectedPackage.equals(A11y.foregroundPackage())) return error("前台应用已变化；拒绝滑动旧页面");
                    if (!"up".equals(direction) && !"down".equals(direction)) return error("只允许固定距离的上滑或下滑");
                    boolean ok = A11y.swipeVertical(direction, expectedPackage);
                    return new JSONObject().put("ok", ok).put("message", ok ? "已执行一次固定距离滑动" : "系统未接受滑动操作")
                            .put("package", expectedPackage).put("direction", direction);
                }
                case "back": {
                    if (!A11y.ready()) return error("手机无障碍服务未连接；没有执行返回");
                    if (!validPackage(expectedPackage)) return error("返回请求缺少目标应用");
                    if (!expectedPackage.equals(A11y.foregroundPackage())) return error("前台应用已变化；拒绝返回旧页面");
                    boolean ok = A11y.back();
                    return new JSONObject().put("ok", ok).put("message", ok ? "已请求返回一次" : "系统未接受返回操作")
                            .put("package", expectedPackage);
                }
                default:
                    return error("AstrBot 请求了未开放的手机操作；没有执行远端代码");
            }
        } catch (Throwable t) {
            return error("本机操作失败：" + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage()));
        }
    }

    private static JSONObject error(String message) {
        try { return new JSONObject().put("ok", false).put("message", message); }
        catch (Throwable ignored) { return new JSONObject(); }
    }

    private static boolean validPackage(String value) {
        return value != null && value.length() <= 180
                && value.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+");
    }

    private static String safeDisplayValue(String value, int max) {
        if (value == null) return "";
        StringBuilder safe = new StringBuilder(Math.min(value.length(), max));
        for (int i = 0; i < value.length() && safe.length() < max; i++) {
            char ch = value.charAt(i);
            if (!Character.isISOControl(ch) && Character.getType(ch) != Character.FORMAT) safe.append(ch);
        }
        return safe.toString();
    }

    private static String actionTitle(String action) {
        switch (action) {
            case "read_screen": return "读取当前屏幕文字";
            case "open_app": return "打开手机应用";
            case "tap_text": return "点击页面文字";
            case "swipe": return "滑动当前页面";
            case "back": return "返回上一级";
            default: return "请求手机操作";
        }
    }

    private static String actionDescription(Context context, String action, String target,
                                            String direction, String expectedPackage) {
        String pkg = expectedPackage == null || expectedPackage.isEmpty()
                ? A11y.foregroundPackage() : expectedPackage;
        switch (action) {
            case "read_screen": return "将读取“" + pkg + "”当前可见的无障碍文字，并把文字发给你连接的 AstrBot。屏幕文字可能包含私人信息。";
            case "open_app": return "请求打开应用包名：" + target + "。";
            case "tap_text": return "将在“" + pkg + "”中只点击唯一匹配的精确文字：“" + target + "”。";
            case "swipe": return "将在“" + pkg + "”中执行一次固定距离的" + ("down".equals(direction) ? "下滑" : "上滑") + "。";
            case "back": return "将在“" + pkg + "”中执行一次系统返回。";
            default: return "请求来自远端 AstrBot；桌宠只执行固定白名单内的操作。";
        }
    }
}
