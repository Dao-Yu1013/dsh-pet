package com.dshpet.android;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 皮肤仓库：原版逐帧动画 + 用户自己导入的皮肤。
 * 自定义皮肤存 S-G/skins/，元数据在 S-G/skins.json。
 * 两种自定义形态：单张静态立绘（<id>.png），序列帧动图（<id>/0001.png…）。
 */
public final class SkinStore {
    public static final String ORIGINAL = "original";
    private static final String META = "skins.json";
    private static final String DIR = "skins";
    private static final Object LOCK = new Object();
    private static volatile boolean migrated;

    public static final class SkinDef {
        public String id;
        public String name;
        public boolean builtin;
        public boolean custom;
        public boolean original;
        /** 序列帧动图：多张同画布帧图循环播放。 */
        public boolean anim;
        /** Live2D 模型（Cubism）：走离屏 GL 渲染。 */
        public boolean live2d;
        /** Live2D 模型目录（绝对路径）与 model3.json 相对路径。 */
        public String dir = "";
        public String model3 = "";
        public int fps = 12;
        public String[] framePaths = new String[0];
        public String[] facePaths = new String[0];
        public String standPath = "";
        public String thumbUrl = "";
        public String imageUrl = "";
        /** 窗口高 / 宽：内置立绘约 1.55，原版动态沿用 9/16。 */
        public float heightRatio = 9f / 16f;
        public float hx0 = 0.08f, hx1 = 0.92f, hy0 = 0.01f, hy1 = 0.45f;
        public float sideScale = 0.9f;
    }

    private SkinStore() {
    }

    /* ---------------- 内置清单 ---------------- */

    public static List<SkinDef> builtins() {
        ArrayList<SkinDef> list = new ArrayList<>();
        SkinDef o = new SkinDef();
        o.id = ORIGINAL;
        o.name = "原版动态";
        o.builtin = true;
        o.original = true;
        o.heightRatio = 9f / 16f;
        o.thumbUrl = "file:///android_asset/webp/a032.webp";
        o.imageUrl = o.thumbUrl;
        list.add(o);
        return list;
    }

    /* ---------------- 查询 ---------------- */

    public static SkinDef current(Context c) {
        if (!migrated) {
            migrated = true;
            migrateDefault(c);
        }
        String id = Cfg.load(c).str("skinId", ORIGINAL);
        SkinDef d = get(c, id);
        if (d == null) {
            // 旧版本选过内置皮肤、或皮肤文件被删掉：静默退回原版，别让桌宠空着
            if (!ORIGINAL.equals(id)) select(c, ORIGINAL);
            return get(c, ORIGINAL);
        }
        return d;
    }

    /**
     * 2.5.5 之前的默认皮肤是女仆装；这次改回原版动态。
     * 只迁移「没有主动选过皮肤」的旧配置，用户已经选过的皮肤保留。
     */
    public static void migrateDefault(Context c) {
        try {
            Cfg cfg = Cfg.load(c);
            if (cfg.i("skinDefaultV", 0) >= 2) return;
            boolean chosen = cfg.i("skinChosen", 0) == 1;
            if (!chosen && "maid".equals(cfg.str("skinId", ""))) {
                cfg.set("skinId", ORIGINAL);
            }
            cfg.set("skinDefaultV", 2);
            cfg.save(c);
        } catch (Throwable ignored) {
        }
    }

    public static SkinDef get(Context c, String id) {
        if (id == null) return null;
        for (SkinDef d : builtins()) {
            if (d.id.equals(id)) return d;
        }
        for (JSONObject o : customMeta(c)) {
            if (!id.equals(o.optString("id", ""))) continue;
            SkinDef d = new SkinDef();
            d.id = id;
            d.name = o.optString("name", id);
            d.custom = true;
            File base = new File(Sg.dir(c), DIR);
            File thumb = new File(new File(base, "thumb"), o.optString("thumb", id + ".png"));
            d.thumbUrl = "file://" + thumb.getAbsolutePath();
            int w = o.optInt("w", 0), h = o.optInt("h", 0);
            if (w > 0 && h > 0) d.heightRatio = h / (float) w;
            String type = o.optString("type", "");
            if ("live2d".equals(type)) {
                d.live2d = true;
                d.dir = new File(live2dDir(c), o.optString("dir", id)).getAbsolutePath();
                d.model3 = o.optString("model3", "");
                d.heightRatio = (float) o.optDouble("ratio", 1.5);
                d.standPath = thumb.getAbsolutePath();
                d.imageUrl = d.thumbUrl;
                return d;
            }
            if ("anim".equals(o.optString("type", ""))) {
                d.anim = true;
                d.fps = Math.max(1, Math.min(30, o.optInt("fps", 12)));
                int n = o.optInt("count", 0);
                d.framePaths = new String[n];
                for (int i = 0; i < n; i++) {
                    d.framePaths[i] = new File(base, o.optString("dir", id) + "/" + frameName(i)).getAbsolutePath();
                }
                d.standPath = n > 0 ? d.framePaths[0] : "";
                d.imageUrl = "file://" + d.standPath;
                return d;
            }
            File f = new File(base, o.optString("file", id + ".png"));
            d.standPath = f.getAbsolutePath();
            d.imageUrl = "file://" + d.standPath;
            return d;
        }
        return null;
    }

    /** 序列帧文件名：0001.png、0002.png…顺序即播放顺序。 */
    public static String frameName(int i) {
        return String.format(java.util.Locale.ROOT, "%04d.png", i + 1);
    }

    public static JSONArray list(Context c) {
        JSONArray out = new JSONArray();
        for (SkinDef d : builtins()) {
            JSONObject o = new JSONObject();
            try {
                o.put("id", d.id);
                o.put("name", d.name);
                o.put("builtin", true);
                o.put("custom", false);
                o.put("original", d.original);
                o.put("thumb", d.thumbUrl);
                o.put("image", d.imageUrl);
                o.put("heightRatio", d.heightRatio);
                out.put(o);
            } catch (Throwable ignored) {
            }
        }
        for (JSONObject m : customMeta(c)) {
            try {
                String id = m.optString("id", "");
                SkinDef d = get(c, id);
                if (d == null) continue;
                JSONObject o = new JSONObject();
                o.put("id", d.id);
                o.put("name", d.name);
                o.put("builtin", false);
                o.put("custom", true);
                o.put("original", false);
                o.put("anim", d.anim);
                o.put("live2d", d.live2d);
                o.put("fps", d.fps);
                o.put("frames", d.framePaths.length);
                o.put("thumb", d.thumbUrl);
                o.put("image", d.imageUrl);
                o.put("heightRatio", d.heightRatio);
                out.put(o);
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    public static void select(Context c, String id) {
        if (get(c, id) == null) return;
        Cfg cfg = Cfg.load(c);
        cfg.set("skinId", id);
        // 记住这是用户主动选的，后续默认迁移不再覆盖
        cfg.set("skinChosen", 1);
        cfg.save(c);
    }

    /* ---------------- 读写立绘 ---------------- */

    public static Bitmap loadStand(Context c, SkinDef d) {
        if (d == null || d.original) return null;
        if (d.live2d) return loadThumb(c, d);
        try {
            Bitmap raw;
            if (d.custom) {
                raw = BitmapFactory.decodeFile(d.standPath);
            } else {
                try (java.io.InputStream in = c.getAssets().open(d.standPath)) {
                    raw = BitmapFactory.decodeStream(in);
                }
            }
            if (raw == null) return null;
            Bitmap small = scaleMax(raw, 720);
            if (small != raw) raw.recycle();
            return small;
        } catch (Throwable t) {
            return null;
        }
    }

    public static Bitmap loadThumb(Context c, SkinDef d) {
        if (d == null || d.original) return null;
        try {
            if (d.custom) {
                java.io.File f = new java.io.File(d.thumbUrl.substring("file://".length()));
                return BitmapFactory.decodeFile(f.getAbsolutePath());
            }
            try (java.io.InputStream in = c.getAssets().open("skins/" + d.id + "/thumb.png")) {
                return BitmapFactory.decodeStream(in);
            }
        } catch (Throwable t) {
            return null;
        }
    }

    /** WebView 兜底用：PNG data URL，保留透明通道。 */
    public static String dataUrl(Bitmap b) {
        if (b == null) return "";
        try {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.PNG, 100, bo);
            byte[] bytes = bo.toByteArray();
            if (bytes.length == 0) return "";
            return "data:image/png;base64," + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP);
        } catch (Throwable t) {
            return "";
        }
    }

    public static Bitmap loadFace(Context c, SkinDef d, int idx) {
        if (d == null || d.original || idx <= 0 || idx > d.facePaths.length) return null;
        if (d.custom) return null;
        try (java.io.InputStream in = c.getAssets().open(d.facePaths[idx - 1])) {
            return BitmapFactory.decodeStream(in);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 动作/点击时按名称选一个表情：0 基准，1 开心，2 难过，3 生气，4 惊讶，5 害羞，6 疑惑。 */
    public static int faceForName(String name) {
        if (name == null) return 0;
        if (has(name, "害羞", "脸红", "羞")) return 5;
        if (has(name, "生气", "傲娇", "怒", "恼")) return 3;
        if (has(name, "难过", "伤心", "委屈", "哭", "低落")) return 2;
        if (has(name, "惊讶", "吓", "惊", "吃鲸")) return 4;
        if (has(name, "疑惑", "困惑", "歪头", "思考", "为什么")) return 6;
        if (has(name, "开心", "笑", "高兴", "庆祝", "跳", "舞", "吃", "喝", "礼物", "烟花")) return 1;
        return 0;
    }

    private static boolean has(String s, String... keys) {
        for (String k : keys) if (s.contains(k)) return true;
        return false;
    }

    /* ---------------- 自定义皮肤 ---------------- */

    private static List<JSONObject> customMeta(Context c) {
        ArrayList<JSONObject> out = new ArrayList<>();
        JSONArray a = Sg.readArr(c, META);
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && !o.optString("id", "").isEmpty()) out.add(o);
        }
        return out;
    }

    private static void saveCustomMeta(Context c, List<JSONObject> list) {
        JSONArray a = new JSONArray();
        for (JSONObject o : list) a.put(o);
        Sg.writeArr(c, META, a);
    }

    /** 导出皮肤包用：自定义皮肤的元数据副本。 */
    public static List<JSONObject> customList(Context c) {
        synchronized (LOCK) {
            return customMeta(c);
        }
    }

    /** 自定义皮肤的 PNG 目录（S-G/skins）。 */
    public static File customDir(Context c) {
        return new File(Sg.dir(c), DIR);
    }

    /** 处理白底/透明底图片并保存为自定义皮肤；返回 {ok,id} 或 {ok:false,error}。 */
    public static JSONObject importCustom(Context c, String name, Bitmap src) {
        JSONObject r = new JSONObject();
        try {
            if (src == null) {
                r.put("ok", false);
                r.put("error", "没有读到图片");
                return r;
            }
            JSONObject norm = normalize(src);
            if (!norm.optBoolean("ok", false)) {
                r.put("ok", false);
                r.put("error", norm.optString("error", "图片处理失败"));
                return r;
            }
            Bitmap out = (Bitmap) norm.get("bitmap");
            int w = out.getWidth(), h = out.getHeight();
            String nm = cleanName(name);
            synchronized (LOCK) {
                File dir = new File(Sg.dir(c), DIR);
                File thumbDir = new File(dir, "thumb");
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
                //noinspection ResultOfMethodCallIgnored
                thumbDir.mkdirs();
                String id = "c" + System.currentTimeMillis();
                File f = new File(dir, id + ".png");
                try (FileOutputStream fo = new FileOutputStream(f)) {
                    out.compress(Bitmap.CompressFormat.PNG, 100, fo);
                }
                Bitmap thumb = scaleMax(out, 180);
                File tf = new File(thumbDir, id + ".png");
                try (FileOutputStream fo = new FileOutputStream(tf)) {
                    thumb.compress(Bitmap.CompressFormat.PNG, 100, fo);
                }
                JSONObject meta = new JSONObject();
                meta.put("id", id);
                meta.put("name", nm);
                meta.put("file", id + ".png");
                meta.put("thumb", id + ".png");
                meta.put("w", w);
                meta.put("h", h);
                meta.put("ts", System.currentTimeMillis());
                List<JSONObject> list = customMeta(c);
                list.add(meta);
                saveCustomMeta(c, list);
                select(c, id);
                r.put("ok", true);
                r.put("id", id);
                r.put("name", nm);
            }
        } catch (Throwable t) {
            try {
                r.put("ok", false);
                r.put("error", "保存皮肤失败");
            } catch (Throwable ignored) {
            }
        }
        return r;
    }

    /**
     * 登记一套序列帧皮肤。帧文件由调用方按 frameName(i) 命名写进 dirName 目录，
     * 这里只负责缩略图、元数据和切换。
     */
    public static JSONObject addAnimSkin(Context c, String name, String dirName, int count,
                                         int w, int h, int fps, Bitmap firstFrame) {
        JSONObject r = new JSONObject();
        try {
            if (count <= 0) {
                r.put("ok", false);
                r.put("error", "皮肤包里没有可用的图片");
                return r;
            }
            String nm = cleanName(name);
            synchronized (LOCK) {
                File dir = new File(Sg.dir(c), DIR);
                File thumbDir = new File(dir, "thumb");
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
                //noinspection ResultOfMethodCallIgnored
                thumbDir.mkdirs();
                if (firstFrame != null) {
                    Bitmap thumb = scaleMax(firstFrame, 180);
                    File tf = new File(thumbDir, dirName + ".png");
                    try (FileOutputStream fo = new FileOutputStream(tf)) {
                        thumb.compress(Bitmap.CompressFormat.PNG, 100, fo);
                    }
                    if (thumb != firstFrame) thumb.recycle();
                }
                JSONObject meta = new JSONObject();
                meta.put("id", dirName);
                meta.put("name", nm);
                meta.put("type", "anim");
                meta.put("dir", dirName);
                meta.put("count", count);
                meta.put("fps", Math.max(1, Math.min(30, fps)));
                meta.put("thumb", dirName + ".png");
                meta.put("w", w);
                meta.put("h", h);
                meta.put("ts", System.currentTimeMillis());
                List<JSONObject> list = customMeta(c);
                list.add(meta);
                saveCustomMeta(c, list);
                select(c, dirName);
                r.put("ok", true);
                r.put("id", dirName);
                r.put("name", nm);
            }
        } catch (Throwable t) {
            try {
                r.put("ok", false);
                r.put("error", "保存皮肤失败");
            } catch (Throwable ignored) {
            }
        }
        return r;
    }

    private static String cleanName(String name) {
        String nm = name == null ? "" : name.trim();
        if (nm.isEmpty()) nm = "我的皮肤";
        if (nm.length() > 12) nm = nm.substring(0, 12);
        return nm;
    }

    /** Live2D 模型目录（S-G/live2d），每个模型一个子目录。 */
    public static File live2dDir(Context c) {
        return new File(Sg.dir(c), "live2d");
    }

    /**
     * 登记一套 Live2D 皮肤。模型文件由调用方解压到 live2d/<dirName>/ 下，
     * 这里只负责缩略图、元数据和切换。ratio 是模型画布的高/宽，用来定桌宠窗口比例。
     */
    public static JSONObject addLive2dSkin(Context c, String name, String dirName, String model3,
                                            float ratio, Bitmap thumb) {
        JSONObject r = new JSONObject();
        try {
            String nm = cleanName(name);
            synchronized (LOCK) {
                File dir = new File(Sg.dir(c), DIR);
                File thumbDir = new File(dir, "thumb");
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
                //noinspection ResultOfMethodCallIgnored
                thumbDir.mkdirs();
                if (thumb != null) {
                    Bitmap small = scaleMax(thumb, 180);
                    File tf = new File(thumbDir, dirName + ".png");
                    try (FileOutputStream fo = new FileOutputStream(tf)) {
                        small.compress(Bitmap.CompressFormat.PNG, 100, fo);
                    }
                    if (small != thumb) small.recycle();
                }
                JSONObject meta = new JSONObject();
                meta.put("id", dirName);
                meta.put("name", nm);
                meta.put("type", "live2d");
                meta.put("dir", dirName);
                meta.put("model3", model3);
                meta.put("ratio", Math.max(0.6f, Math.min(4f, ratio)));
                meta.put("thumb", dirName + ".png");
                meta.put("ts", System.currentTimeMillis());
                List<JSONObject> list = customMeta(c);
                list.add(meta);
                saveCustomMeta(c, list);
                select(c, dirName);
                r.put("ok", true);
                r.put("id", dirName);
                r.put("name", nm);
            }
        } catch (Throwable t) {
            try {
                r.put("ok", false);
                r.put("error", "保存 Live2D 皮肤失败");
            } catch (Throwable ignored) {
            }
        }
        return r;
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) deleteTree(k);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    /**
     * 序列帧用：把白底帧抠成透明底，但**不裁剪、不缩放**，
     * 保证每一帧画布尺寸一致，播放时不会抖动。
     */
    public static Bitmap stripWhiteKeepCanvas(Bitmap src) {
        if (src == null) return null;
        Bitmap work;
        try {
            work = src.copy(Bitmap.Config.ARGB_8888, true);
        } catch (Throwable t) {
            return null;
        }
        if (work == null) return null;
        int w = work.getWidth(), h = work.getHeight();
        if (w <= 0 || h <= 0) return work;
        int[] px = new int[w * h];
        work.getPixels(px, 0, w, 0, 0, w, h);
        if (hasTransparent(px)) return work;
        if (!mostlyWhiteBorder(px, w, h, 0.10f)) return work;
        removeWhiteBackground(px, w, h);
        work.setPixels(px, 0, w, 0, 0, w, h);
        return work;
    }

    public static void delete(Context c, String id) {
        if (id == null || id.isEmpty()) return;
        synchronized (LOCK) {
            List<JSONObject> list = customMeta(c);
            List<JSONObject> keep = new ArrayList<>();
            JSONObject hit = null;
            for (JSONObject o : list) {
                if (id.equals(o.optString("id", ""))) hit = o;
                else keep.add(o);
            }
            if (hit == null) return;
            saveCustomMeta(c, keep);
            File dir = new File(Sg.dir(c), DIR);
            try {
                if ("anim".equals(hit.optString("type", ""))) {
                    deleteTree(new File(dir, hit.optString("dir", id)));
                } else if ("live2d".equals(hit.optString("type", ""))) {
                    deleteTree(new File(live2dDir(c), hit.optString("dir", id)));
                } else {
                    File f = new File(dir, hit.optString("file", id + ".png"));
                    //noinspection ResultOfMethodCallIgnored
                    if (f.isFile()) f.delete();
                }
                File tf = new File(new File(dir, "thumb"), hit.optString("thumb", id + ".png"));
                //noinspection ResultOfMethodCallIgnored
                if (tf.isFile()) tf.delete();
            } catch (Throwable ignored) {
            }
            if (id.equals(Cfg.load(c).str("skinId", ""))) select(c, ORIGINAL);
        }
    }

    /* ---------------- 白底抠图 ---------------- */

    /**
     * 输入一张白底或透明底立绘，返回透明底 Bitmap。
     * 只从四边泛洪，避免把角色身上的白色衣服掏空。
     */
    public static JSONObject normalize(Bitmap src) {
        JSONObject r = new JSONObject();
        Bitmap work = null;
        try {
            int max = 1024;
            int w = src.getWidth(), h = src.getHeight();
            if (w <= 0 || h <= 0) {
                r.put("ok", false);
                r.put("error", "图片为空");
                return r;
            }
            if (Math.max(w, h) > max) {
                float s = max / (float) Math.max(w, h);
                work = Bitmap.createScaledBitmap(src, Math.max(1, (int) (w * s)),
                        Math.max(1, (int) (h * s)), true);
            } else {
                work = src.copy(Bitmap.Config.ARGB_8888, true);
            }
            if (work == null) {
                r.put("ok", false);
                r.put("error", "图片读取失败");
                return r;
            }
            w = work.getWidth();
            h = work.getHeight();
            if (Math.min(w, h) < 256) {
                if (work != src) work.recycle();
                r.put("ok", false);
                r.put("error", "图片太小，至少 256×256");
                return r;
            }
            int[] px = new int[w * h];
            work.getPixels(px, 0, w, 0, 0, w, h);
            Bitmap out;
            if (hasTransparent(px)) {
                // 已经是透明底：直接去边
                out = trimAndPad(px, w, h);
            } else {
                if (!mostlyWhiteBorder(px, w, h)) {
                    if (work != src) work.recycle();
                    r.put("ok", false);
                    r.put("error", "背景不是白底，无法自动抠图");
                    return r;
                }
                removeWhiteBackground(px, w, h);
                out = trimAndPad(px, w, h);
            }
            if (work != src) work.recycle();
            if (out == null) {
                r.put("ok", false);
                r.put("error", "图片里没有可用角色");
                return r;
            }
            out = scaleMax(out, 1024);
            r.put("ok", true);
            r.put("bitmap", out);
            r.put("w", out.getWidth());
            r.put("h", out.getHeight());
        } catch (Throwable t) {
            try {
                r.put("ok", false);
                r.put("error", "图片处理失败");
            } catch (Throwable ignored) {
            }
        }
        return r;
    }

    private static boolean hasTransparent(int[] px) {
        int n = 0;
        for (int p : px) {
            if ((p >>> 24) < 250) n++;
            if (n > px.length / 50) return true;
        }
        return false;
    }

    private static boolean white(int p) {
        int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
        int mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b));
        return r >= 228 && g >= 228 && b >= 228 && mx - mn <= 26;
    }

    private static boolean mostlyWhiteBorder(int[] px, int w, int h) {
        return mostlyWhiteBorder(px, w, h, 0.30f);
    }

    /** need：四边白色像素占比达到多少就认为是白底。序列帧放宽一些，避免小角色被判成非白底。 */
    private static boolean mostlyWhiteBorder(int[] px, int w, int h, float need) {
        int total = 0, white = 0;
        for (int x = 0; x < w; x++) {
            total += 2;
            if (white(px[x])) white++;
            if (white(px[(h - 1) * w + x])) white++;
        }
        for (int y = 0; y < h; y++) {
            total += 2;
            if (white(px[y * w])) white++;
            if (white(px[y * w + w - 1])) white++;
        }
        return total > 0 && white >= total * need;
    }

    private static void removeWhiteBackground(int[] px, int w, int h) {
        java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>();
        boolean[] mark = new boolean[w * h];
        for (int x = 0; x < w; x++) {
            seed(px, mark, queue, x, w, h);
            seed(px, mark, queue, (h - 1) * w + x, w, h);
        }
        for (int y = 0; y < h; y++) {
            seed(px, mark, queue, y * w, w, h);
            seed(px, mark, queue, y * w + w - 1, w, h);
        }
        while (!queue.isEmpty()) {
            int i = queue.poll();
            int x = i % w, y = i / w;
            if (x > 0) seed(px, mark, queue, i - 1, w, h);
            if (x + 1 < w) seed(px, mark, queue, i + 1, w, h);
            if (y > 0) seed(px, mark, queue, i - w, w, h);
            if (y + 1 < h) seed(px, mark, queue, i + w, w, h);
        }
        for (int i = 0; i < px.length; i++) {
            if (mark[i]) px[i] = 0;
        }
    }

    private static void seed(int[] px, boolean[] mark, java.util.ArrayDeque<Integer> q, int i, int w, int h) {
        if (i < 0 || i >= px.length || mark[i] || !white(px[i])) return;
        mark[i] = true;
        q.add(i);
    }

    private static Bitmap trimAndPad(int[] px, int w, int h) {
        int minX = w, minY = h, maxX = -1, maxY = -1;
        int opaque = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int a = px[y * w + x] >>> 24;
                if (a > 12) {
                    opaque++;
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }
        if (maxX <= minX || maxY <= minY) return null;
        float ratio = opaque / (float) (w * h);
        if (ratio < 0.04f || ratio > 0.94f) return null;
        int tw = maxX - minX + 1, th = maxY - minY + 1;
        int pad = Math.max(4, Math.round(Math.max(tw, th) * 0.04f));
        int ow = tw + pad * 2, oh = th + pad * 2;
        int[] outPx = new int[ow * oh];
        for (int y = 0; y < th; y++) {
            System.arraycopy(px, (minY + y) * w + minX, outPx, (y + pad) * ow + pad, tw);
        }
        Bitmap out = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888);
        out.setPixels(outPx, 0, ow, 0, 0, ow, oh);
        return out;
    }

    private static Bitmap scaleMax(Bitmap src, int max) {
        int w = src.getWidth(), h = src.getHeight();
        if (w <= max && h <= max) return src;
        float s = max / (float) Math.max(w, h);
        return Bitmap.createScaledBitmap(src, Math.max(1, (int) (w * s)),
                Math.max(1, (int) (h * s)), true);
    }
}
