package com.dshpet.android;

import android.content.Context;

import java.util.Locale;

/** Turns bridge failures into honest, actionable companion replies without recursively calling the model. */
final class AgentErrorHandler {
    private AgentErrorHandler() { }

    static String astrBot(Context context, String error) {
        String raw = error == null ? "" : error.trim();
        String lower = raw.toLowerCase(Locale.ROOT);
        if (raw.contains("未找到任何可用的对话模型") || raw.contains("请先在 WebUI 中配置并启用可用模型"))
            return "这条消息发去了远端 AstrBot，但远端没有配置或启用对话模型，没法回答。请在 AstrBot WebUI 里添加并启用一个对话模型，或在插件页关闭“远端 AstrBot 智能体”回退本机模型。";
        if (lower.contains("http 401") || lower.contains("api key") && (lower.contains("invalid") || lower.contains("expired")))
            return "我现在没能通过 AstrBot 的身份校验。主人可以到插件页重新检查 OpenAPI Key 和权限。";
        if (lower.contains("http 403") || lower.contains("scope") || lower.contains("权限"))
            return "我连上 AstrBot 了，但这把 API Key 没有当前功能需要的权限。插件市场需要 plugin，智能体聊天需要 chat；补好权限后再试就行。";
        if (lower.contains("http 429") || lower.contains("rate limit") || lower.contains("too many requests"))
            return "AstrBot 现在处理请求有些频繁，或模型额度暂时达到上限。等一会儿再试，也可以检查远端模型的调用额度。";
        if (lower.contains("http 503") || lower.contains("service_unavailable") || lower.contains("service is too busy")
                || lower.contains("overloaded") || lower.contains("temporarily unavailable"))
            return "AstrBot 的聊天模型暂时繁忙或正在维护。主人可以稍后重试，或检查电脑上的模型服务并切换可用模型。";
        if (lower.contains("insufficient_quota") || lower.contains("余额不足") || lower.contains("billing"))
            return "AstrBot 已收到请求，但远端模型的可用额度可能不足。请检查 AstrBot 配置的模型服务商余额或套餐。";
        if (lower.contains("timed out") || lower.contains("timeout") || lower.contains("超时"))
            return "远端 AstrBot 暂时没有及时回应，可能是电脑休眠、网络变慢或服务正忙。等它恢复后再发一次就好。";
        if (lower.contains("unable to resolve") || lower.contains("unknownhost") || lower.contains("connection refused")
                || lower.contains("failed to connect") || lower.contains("network is unreachable") || lower.contains("无法连接"))
            return "我暂时联系不上远端 AstrBot。主人可以检查电脑上的服务、手机和电脑是否在同一网络，以及服务地址和防火墙设置。";
        if (lower.contains("重定向"))
            return "AstrBot 地址把请求转到了另一个地址，我已停止发送，避免把访问凭据带过去。请直接填写最终的 HTTPS 服务地址。";
        if (lower.contains("没有可显示的文字") || lower.contains("empty"))
            return "AstrBot 收到了这条消息，但没有给我可显示的文字回复。可以检查它的模型和插件日志后再试。";
        return "我刚才没能从远端 AstrBot 拿到可靠回复，主人可以在插件页重新检查连接，原消息没有被我当成已答复。";
    }

    static String groupChat(String error) {
        String safe = Ai.userFacingError(error);
        if (safe == null || safe.trim().isEmpty())
            return "我这次没能处理好群里的消息。请主人检查群聊模型或 AstrBot 连接后再试。";
        return safe;
    }
}
