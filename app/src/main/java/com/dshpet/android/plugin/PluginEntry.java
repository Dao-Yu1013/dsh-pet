package com.dshpet.android.plugin;

/**
 * 原生（Dex）插件的入口。
 * 插件 dex 里写一个实现本接口的类，并在 manifest.json 的 main 字段填上它的完整类名。
 * start 在后台线程调用；stop 在插件被停用、删除或进程退出前调用。
 */
public interface PluginEntry {
    void start(Host host);

    /** Called for each user chat message. Implementations should return quickly and use their own worker for long jobs. */
    default void onMessage(Host host, String text) { }

    /** Called by the app's scheduled plugin runner. */
    default void onTick(Host host) { }

    void stop();
}
