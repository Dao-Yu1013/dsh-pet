package com.dshpet.android;

import android.content.Context;

import com.dshpet.android.plugin.PluginEntry;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.PrintWriter;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import dalvik.system.DexClassLoader;

/** Compiles a generated Java PluginEntry to DEX entirely on the device. */
final class PluginDexCompiler {
    private static final String ASSET_ROOT = "plugin-compiler/";
    private static final String[] TOOL_ASSETS = {
            "ecj.dex.jar", "d8.dex.jar", "android.jar", "core.jar", "plugin-api.jar"
    };

    static final class Result {
        final File dex;
        final String error;
        Result(File dex, String error) { this.dex = dex; this.error = error; }
        boolean ok() { return dex != null && dex.isFile() && error.isEmpty(); }
    }

    private PluginDexCompiler() { }

    static Result compile(Context context, String mainClass, String source) {
        File work = null;
        try {
            if (context == null) return new Result(null, "桌宠上下文不可用");
            String invalid = validateSource(mainClass, source);
            if (!invalid.isEmpty()) return new Result(null, invalid);
            Context app = context.getApplicationContext() == null ? context : context.getApplicationContext();
            // Bump the private cache path when changing the DEX container format.
            // ART may retain optimized code keyed by the old path across app updates.
            File toolDir = new File(app.getFilesDir(), "plugin-compiler-v2");
            if (!ensureTools(app, toolDir)) return new Result(null, "内置 Java/DEX 编译器资源不完整");

            work = new File(app.getCacheDir(), "plugin-compile-" + System.currentTimeMillis());
            File srcDir = new File(work, "src");
            File classesDir = new File(work, "classes");
            File dexDir = new File(work, "dex");
            if (!classesDir.mkdirs() || !dexDir.mkdirs()) return new Result(null, "无法创建临时编译目录");
            File sourceFile = new File(srcDir, mainClass.replace('.', '/') + ".java");
            File parent = sourceFile.getParentFile();
            if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
                return new Result(null, "无法创建 Java 源码目录");
            }
            Sg.write(sourceFile, source);

            File api = new File(toolDir, "plugin-api.jar");
            File android = new File(toolDir, "android.jar");
            File core = new File(toolDir, "core.jar");
            File ecj = new File(toolDir, "ecj.dex.jar");
            File d8 = new File(toolDir, "d8.dex.jar");
            String cp = api.getAbsolutePath() + File.pathSeparator + android.getAbsolutePath()
                    + File.pathSeparator + core.getAbsolutePath();
            String[] args = {
                    "-proc:none", "-source", "1.8", "-target", "1.8", "-encoding", "UTF-8",
                    "-nowarn", "-d", classesDir.getAbsolutePath(), "-classpath", cp,
                    // ECJ on Android has no desktop JDK boot classes. Supply Android's
                    // core signature jar explicitly so java.lang.Object/String resolve.
                    "-bootclasspath", core.getAbsolutePath() + File.pathSeparator + android.getAbsolutePath(),
                    sourceFile.getAbsolutePath()
            };
            String compilerOutput = compileJava(ecj, toolDir, args);
            if (compilerOutput != null) return new Result(null, compilerOutput);

            File classJar = new File(work, "plugin-classes.jar");
            if (!zipClasses(classesDir, classJar)) return new Result(null, "Java 编译没有生成可用的 class 文件");
            runD8(d8, android, api, toolDir, classJar, dexDir);
            File generatedDex = new File(dexDir, "classes.dex");
            if (!generatedDex.isFile() || generatedDex.length() < 112 || !hasDexMagic(generatedDex)) {
                return new Result(null, "D8 没有生成有效的 DEX 文件");
            }
            if (!generatedDex.setReadOnly() || generatedDex.canWrite()) {
                return new Result(null, "系统不允许将临时 DEX 设为只读，已停止加载以保护设备");
            }
            DexClassLoader check = new DexClassLoader(generatedDex.getAbsolutePath(),
                    new File(work, "opt").getAbsolutePath(), null, PluginDexCompiler.class.getClassLoader());
            Class<?> entry = Class.forName(mainClass, false, check);
            if (!PluginEntry.class.isAssignableFrom(entry)) {
                return new Result(null, "生成的入口类没有实现桌宠原生插件接口 PluginEntry");
            }
            java.lang.reflect.Constructor<?> ctor = entry.getDeclaredConstructor();
            if (!Modifier.isPublic(entry.getModifiers()) || !Modifier.isPublic(ctor.getModifiers())) {
                return new Result(null, "插件入口类和无参构造必须为 public");
            }
            File draftDir = new File(Sg.dir(app), "plugin-drafts");
            if (!draftDir.isDirectory() && !draftDir.mkdirs()) return new Result(null, "无法保存待确认的原生插件");
            File ready = new File(draftDir, "plugin-" + System.currentTimeMillis() + ".dex");
            copy(generatedDex, ready);
            ready.setReadOnly();
            return new Result(ready, "");
        } catch (Throwable t) {
            Throwable root = t;
            while ((root instanceof java.lang.reflect.InvocationTargetException || root.getMessage() == null)
                    && root.getCause() != null) root = root.getCause();
            String message = root.getMessage();
            String detail = root.getClass().getSimpleName()
                    + (message == null || message.trim().isEmpty() ? "" : "：" + message);
            return new Result(null, "手机内编译失败：" + detail);
        } finally {
            if (work != null) deleteTree(work);
        }
    }

    private static String validateSource(String mainClass, String source) {
        if (mainClass == null || !mainClass.matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+")) {
            return "AI 生成的插件入口类名不合法";
        }
        if (source == null || source.trim().isEmpty() || source.length() > 48000) {
            return "AI 生成的 Java 源码为空或超过 48KB 上限";
        }
        java.util.regex.Matcher pkg = java.util.regex.Pattern
                .compile("(?m)^\\s*package\\s+([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*)\\s*;")
                .matcher(source);
        java.util.regex.Matcher cls = java.util.regex.Pattern
                .compile("(?m)\\bpublic\\s+(?:(?:final|abstract|strictfp)\\s+)*class\\s+([A-Za-z_$][A-Za-z0-9_$]*)")
                .matcher(source);
        if (!cls.find()) return "Java 源码中找不到 public 插件入口类";
        String declared = (pkg.find() ? pkg.group(1) + "." : "") + cls.group(1);
        if (!mainClass.equals(declared)) {
            return "插件入口类名不匹配：清单 main 是 " + mainClass + "，源码实际声明 " + declared;
        }
        String low = source.toLowerCase(Locale.ROOT);
        String[] blocked = {"system.exit(", "runtime.getruntime(", "new processbuilder(",
                "java.lang.reflect", "dalvik.system.dexclassloader", "dalvik.system.pathclassloader"};
        for (String token : blocked) if (low.contains(token)) return "源码包含桌宠插件不允许使用的进程、反射或二次加载操作：" + token;
        if (!source.contains("implements PluginEntry") && !source.contains("implements com.dshpet.android.plugin.PluginEntry")) {
            return "AI 生成的入口类没有实现 PluginEntry";
        }
        return "";
    }

    private static boolean ensureTools(Context c, File dir) {
        if (!dir.isDirectory() && !dir.mkdirs()) return false;
        for (String name : TOOL_ASSETS) {
            File out = new File(dir, name);
            // Android 14+ forbids loading writable DEX/JAR files. Always refresh
            // packaged compiler assets, then fsync and seal them before class loading.
            if (out.isFile()) out.delete();
            try (InputStream in = c.getAssets().open(ASSET_ROOT + name); FileOutputStream fos = new FileOutputStream(out)) {
                byte[] b = new byte[32768];
                for (int n; (n = in.read(b)) >= 0;) if (n > 0) fos.write(b, 0, n);
                fos.getFD().sync();
            } catch (Throwable t) { return false; }
            if (!out.setReadOnly() || out.canWrite()) return false;
        }
        return true;
    }

    private static String compileJava(File ecj, File opt, String[] args) throws Exception {
        DexClassLoader loader = new DexClassLoader(ecj.getAbsolutePath(),
                new File(opt, "ecj-opt").getAbsolutePath(), null, PluginDexCompiler.class.getClassLoader());
        Class<?> main = Class.forName("org.eclipse.jdt.internal.compiler.batch.Main", true, loader);
        Class<?> progress = Class.forName("org.eclipse.jdt.core.compiler.CompilationProgress", true, loader);
        Method compile = main.getMethod("compile", String[].class, PrintWriter.class, PrintWriter.class, progress);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintWriter writer = new PrintWriter(output, true);
        Object result = compile.invoke(null, args, writer, writer, null);
        writer.flush();
        if (!(result instanceof Boolean) || !((Boolean) result)) {
            String details = output.toString("UTF-8").trim();
            return "Java 编译错误：" + (details.isEmpty() ? "请检查生成的源码和桌宠插件接口" : details);
        }
        return null;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void runD8(File dexJar, File androidJar, File apiJar, File opt, File classJar, File outDir) throws Exception {
        DexClassLoader loader = new DexClassLoader(dexJar.getAbsolutePath(),
                new File(opt, "d8-opt").getAbsolutePath(), null, PluginDexCompiler.class.getClassLoader());
        Class<?> commandClass = Class.forName("com.android.tools.r8.D8Command", true, loader);
        Class<?> d8Class = Class.forName("com.android.tools.r8.D8", true, loader);
        Class<?> outputModeClass = Class.forName("com.android.tools.r8.OutputMode", true, loader);
        Object builder = commandClass.getMethod("builder").invoke(null);
        Class<?> builderClass = builder.getClass();
        builderClass.getMethod("setMinApiLevel", int.class).invoke(builder, 28);
        Object mode = Enum.valueOf((Class<? extends Enum>) outputModeClass.asSubclass(Enum.class), "DexIndexed");
        builderClass.getMethod("setOutput", Path.class, outputModeClass).invoke(builder, outDir.toPath(), mode);
        Collection<Path> programs = new ArrayList<>();
        programs.add(classJar.toPath());
        builderClass.getMethod("addProgramFiles", Collection.class).invoke(builder, programs);
        Collection<Path> classpath = new ArrayList<>();
        classpath.add(apiJar.toPath());
        builderClass.getMethod("addClasspathFiles", Collection.class).invoke(builder, classpath);
        builderClass.getMethod("addLibraryFiles", Path[].class).invoke(builder, (Object) new Path[]{androidJar.toPath()});
        Object command = builderClass.getMethod("build").invoke(builder);
        d8Class.getMethod("run", commandClass).invoke(null, command);
    }

    private static boolean zipClasses(File root, File out) throws Exception {
        List<File> files = new ArrayList<>();
        collectClasses(root, files);
        if (files.isEmpty()) return false;
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(out))) {
            byte[] buf = new byte[8192];
            for (File f : files) {
                String name = f.getAbsolutePath().substring(root.getAbsolutePath().length() + 1).replace('\\', '/');
                zip.putNextEntry(new ZipEntry(name));
                try (InputStream in = new java.io.FileInputStream(f)) {
                    for (int n; (n = in.read(buf)) >= 0;) if (n > 0) zip.write(buf, 0, n);
                }
                zip.closeEntry();
            }
        }
        return true;
    }

    private static void collectClasses(File f, List<File> out) {
        File[] children = f.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) collectClasses(child, out);
            else if (child.getName().endsWith(".class")) out.add(child);
        }
    }

    private static boolean hasDexMagic(File f) {
        try (InputStream in = new java.io.FileInputStream(f)) {
            if (in.read() != 'd' || in.read() != 'e' || in.read() != 'x' || in.read() != '\n') return false;
            int a = in.read(), b = in.read(), c = in.read();
            int version = (a - '0') * 100 + (b - '0') * 10 + c - '0';
            return a >= '0' && a <= '9' && b >= '0' && b <= '9' && c >= '0' && c <= '9'
                    && version >= 35 && version <= 41 && in.read() == 0;
        } catch (Throwable ignored) { return false; }
    }

    private static void copy(File from, File to) throws Exception {
        try (InputStream in = new java.io.FileInputStream(from); FileOutputStream out = new FileOutputStream(to)) {
            byte[] b = new byte[32768];
            for (int n; (n = in.read(b)) >= 0;) if (n > 0) out.write(b, 0, n);
            out.getFD().sync();
        }
    }

    private static void deleteTree(File f) {
        File[] children = f.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
