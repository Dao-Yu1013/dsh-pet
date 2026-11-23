package com.dshpet.android;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Narrow IPC client for the optional DSHBox second plugin-analysis runtime. */
public final class DshBoxBridge {
    private static final String PACKAGE = "com.dshbox.app";
    private static final String SERVICE = "com.dshbox.app.integration.PetPluginBridgeService";
    private static final int MSG_STATUS = 1;
    private static final int MSG_ANALYZE = 2;
    private static final int MSG_RESULT = 10;
    private static final String KEY_PROMPT = "prompt";
    private static final String KEY_SUCCESS = "success";
    private static final String KEY_ERROR = "error";
    private static final String KEY_PAYLOAD = "payload";
    private static final ExecutorService WORKER = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "dshbox-bridge-client");
        t.setDaemon(true);
        return t;
    });

    private DshBoxBridge() { }

    public static JSONObject status(Context context) {
        try {
            JSONObject result = request(context, MSG_STATUS, null, 6000L);
            result.put("installed", true);
            return result;
        } catch (Throwable t) {
            JSONObject unavailable = new JSONObject();
            try {
                boolean installed;
                try {
                    context.getPackageManager().getPackageInfo(PACKAGE, 0);
                    installed = true;
                } catch (Throwable ignored) {
                    installed = false;
                }
                unavailable.put("installed", installed);
                unavailable.put("ready", false);
                unavailable.put("message", installed
                        ? diagnosticMessage(t)
                        : "还没有安装 DSHBox；桌宠本机插件仍可独立使用。");
                unavailable.put("error", safeError(t));
            } catch (Throwable ignored) { }
            return unavailable;
        }
    }

    private static String safeError(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String message = root.getMessage();
        return message == null || message.trim().isEmpty() ? root.getClass().getSimpleName() : message.trim();
    }

    private static String diagnosticMessage(Throwable error) {
        String detail = safeError(error);
        String lower = detail.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("not exported") || lower.contains("not allowed to bind") || lower.contains("permission denial")) {
            return "检测到 DSHBox，但 Android 拒绝绑定插件桥（服务导出或权限不匹配）。请更新 DSHBox，并确认两款应用来自可信且配套的版本。";
        }
        if (lower.contains("unable to find explicit activity") || lower.contains("service not found") || lower.contains("componentinfo")) {
            return "检测到 DSHBox，但当前版本没有找到桌宠插件桥服务。请更新 DSHBox 到包含桌宠桥接服务的版本。";
        }
        if (lower.contains("调用方未授权")) {
            return "DSHBox 插件桥已连上，但拒绝了此桌宠调用方。请确认 DSHBox 信任的桌宠包名为 com.dshpet.android，并使用配套版本。";
        }
        if (lower.contains("超时")) {
            return "DSHBox 插件桥已找到，但状态响应超时。请先打开 DSHBox 等待运行环境启动，再点重新检测。";
        }
        if (lower.contains("连接失败") || lower.contains("无法连接")) {
            return "检测到 DSHBox，但无法绑定插件桥服务。请更新 DSHBox 并重新打开两个应用；详细原因：" + detail;
        }
        return "检测到 DSHBox，但桥接状态请求失败：" + detail;
    }

    public static boolean wantsRemote(String text) {
        if (text == null) return false;
        String s = text.trim();
        return s.startsWith("用DSHBox分析插件") || s.startsWith("用 DSHBox 分析插件")
                || s.startsWith("DSHBox插件分析:") || s.startsWith("DSHBox插件分析：")
                || s.startsWith("让DSHBox检查插件") || s.startsWith("让 DSHBox 检查插件");
    }

    public static String remotePrompt(String text) {
        if (text == null) return "";
        return text.trim()
                .replaceFirst("^(用\\s*DSHBox\\s*分析插件|让\\s*DSHBox\\s*检查插件)\\s*[:：,，]?\\s*", "")
                .replaceFirst("^DSHBox插件分析\\s*[:：]\\s*", "").trim();
    }

    public static JSONObject analyze(Context context, String prompt) throws Exception {
        String task = prompt == null ? "" : prompt.trim();
        if (task.isEmpty()) throw new IllegalArgumentException("请先填写要分析的插件问题或日志。");
        return request(context, MSG_ANALYZE, task, 180_000L);
    }

    public static void ask(Context context, String text, Ai.Cb callback) {
        WORKER.execute(() -> {
            try {
                JSONObject result = request(context, MSG_ANALYZE, remotePrompt(text), 180_000L);
                callback.ok(result.optString("text", "DSHBox 没有返回内容。"));
            } catch (Throwable t) {
                callback.fail(t.getMessage() == null ? "DSHBox 当前不可用。" : t.getMessage());
            }
        });
    }

    private static JSONObject request(Context context, int what, String prompt, long timeoutMs) throws Exception {
        Context app = context.getApplicationContext();
        CountDownLatch connected = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<Messenger> remote = new AtomicReference<>();
        AtomicReference<JSONObject> payload = new AtomicReference<>();
        AtomicReference<String> failure = new AtomicReference<>();
        HandlerThread callbackThread = new HandlerThread("dshbox-bridge-reply");
        callbackThread.start();
        Messenger replyTo = new Messenger(new Handler(callbackThread.getLooper(), message -> {
            Bundle data = message.getData();
            if (!data.getBoolean(KEY_SUCCESS, false)) {
                failure.set(data.getString(KEY_ERROR, "DSHBox 请求失败。"));
            } else {
                try {
                    payload.set(new JSONObject(data.getString(KEY_PAYLOAD, "{}")));
                } catch (Throwable t) {
                    failure.set("DSHBox 返回的数据无法读取。");
                }
            }
            completed.countDown();
            return true;
        }));
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                remote.set(new Messenger(binder));
                connected.countDown();
            }

            @Override public void onServiceDisconnected(ComponentName name) {
                failure.compareAndSet(null, "DSHBox 连接中断。");
                connected.countDown();
                completed.countDown();
            }
        };
        boolean bound = false;
        try {
            Intent intent = new Intent().setComponent(new ComponentName(PACKAGE, SERVICE));
            bound = app.bindService(intent, connection, Context.BIND_AUTO_CREATE);
            if (!bound) throw new IllegalStateException("无法连接 DSHBox 插件桥。");
            if (!connected.await(5, TimeUnit.SECONDS) || remote.get() == null) {
                throw new IllegalStateException("连接 DSHBox 超时。");
            }
            Message request = Message.obtain(null, what);
            request.arg1 = (int) (System.nanoTime() & 0x7fffffff);
            request.replyTo = replyTo;
            if (prompt != null) request.setData(BundleForPrompt(prompt));
            try {
                remote.get().send(request);
            } catch (RemoteException e) {
                throw new IllegalStateException("DSHBox 连接中断。", e);
            }
            if (!completed.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("DSHBox 分析超时；你可以稍后再试。");
            }
            if (failure.get() != null) throw new IllegalStateException(failure.get());
            JSONObject result = payload.get();
            if (result == null) throw new IllegalStateException("DSHBox 没有返回结果。");
            return result;
        } finally {
            if (bound) {
                try { app.unbindService(connection); } catch (Throwable ignored) { }
            }
            callbackThread.quitSafely();
        }
    }

    private static Bundle BundleForPrompt(String prompt) {
        Bundle data = new Bundle();
        data.putString(KEY_PROMPT, prompt);
        return data;
    }
}
