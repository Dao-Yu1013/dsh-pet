# ============================================================================
# R8 混淆规则（只用于正式打包；-Fast 参数会跳过 R8）
#
# 新增下面这类东西时，必须回到本文件补 keep 行，否则打包后运行会崩：
#   1. 新的 @JavascriptInterface 桥接方法（WebView 通过方法名调用）
#   2. 新写进 AndroidManifest.xml 的组件（Activity/Service/Receiver/Provider）
#   3. 新用反射调用的自有类
# ============================================================================

# ---------- 组件与入口（名字被系统硬引用，不能改） ----------
-keep class com.dshpet.android.App { *; }
-keep class com.dshpet.android.MainAct { *; }
-keep class com.dshpet.android.PetSvc { *; }
-keep class com.dshpet.android.A11y { *; }
-keep class com.dshpet.android.Notify { *; }
-keep class com.dshpet.android.Boot { *; }
-keep class com.dshpet.android.PickAct { *; }
-keep class com.dshpet.android.Alive { *; }
-keep class com.dshpet.android.Battery { *; }

# ---------- 原生加固层（JNI 靠类名与方法名对接 so） ----------
-keep class com.dshpet.android.Secure {
    native <methods>;
}

# ---------- WebView 桥 ----------
# @JavascriptInterface 标注的方法会被 window.Dsh.call 按名字调用
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# ---------- Live2D Cubism Core（JNI 按 Java 类名 / 方法名查找，不能混淆） ----------
-keep class com.live2d.sdk.cubism.core.** { *; }
-keepclassmembers class com.live2d.sdk.cubism.core.** { *; }
-dontwarn com.live2d.sdk.cubism.**

# ---------- 原生（Dex）插件 API ----------
# 外部插件 dex 是按这些类名/方法名编译的，混淆后插件会找不到接口而加载失败
-keep interface com.dshpet.android.plugin.** { *; }
-keep class com.dshpet.android.plugin.** { *; }
-keep class com.dshpet.android.DexPlugins { *; }
-dontwarn com.dshpet.android.plugin.**

# ---------- 需要保留的元信息 ----------
# 注意：这里不保留 SourceFile / LineNumberTable，所以异常栈只有
# 混淆后的类名和方法名，还原要靠 mapping.txt
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod, Exceptions

# 平台类不需要警告
-dontwarn android.webkit.**
-dontwarn org.json.**