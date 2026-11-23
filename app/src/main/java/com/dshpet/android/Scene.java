package com.dshpet.android;

import android.content.Context;
import android.media.AudioManager;

/**
 * 本地场景判断：视频、听书、聊天。
 * 只用已有事件和媒体会话，不新增轮询、不联网。
 */
public final class Scene {
    /** 聊天应用：用户正在聊天时，不主动插话。 */
    private static final String[] CHAT_PKGS = {
            "com.tencent.mm", "com.tencent.mobileqq", "com.whatsapp", "org.telegram.messenger",
            "com.facebook.orca", "com.snapchat.android", "com.discord"
    };
    /** 听书/小说/播客类应用：属于“在听声音”，但不是歌曲。 */
    private static final String[] SPOKEN_PKGS = {
            "com.ximalaya", "fm.qingting", "com.lr.music", "com.xs.fm", "com.tencent.weread",
            "com.dragon.read", "com.kmxs.reader", "com.qq.reader", "com.qidian", "com.chaozh.ireader",
            "com.missevan", "com.lizhi.fm", "com.fandeng", "changting", "tingbook"
    };
    private static final String[] SPOKEN_WORDS = {
            "有声书", "小说", "评书", "相声", "听书", "电台", "播客", "讲书",
            "懒人听书", "番茄畅听", "喜马拉雅", "蜻蜓", "微信读书", "帆书"
    };
    /** 包名里带这些词的，基本可以确定是视频/短剧类应用。 */
    private static final String[] VIDEO_PKGS = {
            "video", "drama", "duanju", "shortplay", "movie", "cinema", "qqlive", "qiyi",
            "youku", "mgtv", "bilibili", "douyin", "kuaishou", "ixigua", "phoenix", "lekan",
            "pptv", "wetv", "migu", "sohu", "acfun", "huya", "douyu"
    };
    /** 应用名里带这些词的，也算视频/短剧（包名不好认时兜底）。 */
    private static final String[] VIDEO_WORDS = {
            "视频", "短剧", "影视", "剧场", "电影", "电视剧", "直播", "影院", "追剧",
            "动漫", "综艺", "视频播放器", "video", "drama", "movie"
    };
    private static final String[] READER_PKGS = {
            "reader", "reading", "qidian", "weread", "book", "novel", "ebook", "kindle", "ireader"
    };

    private Scene() {
    }

    /** 包名是否属于听书/播客类（这类有声音但不算视频）。 */
    static boolean looksLikeSpokenPkg(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        String p = pkg.toLowerCase(java.util.Locale.ROOT);
        for (String k : SPOKEN_PKGS) if (p.contains(k)) return true;
        return false;
    }

    /** 前台应用的显示名；取不到时退回包名。 */
    public static String appLabel(Context c) {
        String pkg = Bus.currentPackage;
        if (pkg == null || pkg.isEmpty()) return "";
        return Music.appLabel(c, pkg);
    }

    /**
     * 是否正在看视频/追剧/直播：
     * 1) 媒体会话里有非音乐、非听书的播放；
     * 2) 当前应用是明确的视频/短剧类应用（按包名或应用名判断）；
     * 3) 当前应用属于视频/浏览器类，且系统还有音频在播放。
     * 音乐应用不算，避免把听歌当忙碌；听书类也不算，交给 spoken()。
     */
    public static boolean video(Context c) {
        String pkg = Bus.currentPackage;
        // A paused/background media session from the previous app must not override the app
        // currently in front (for example, switching from Douyin to Bilibili).
        if (Music.videoPlaying()) {
            String mediaPkg = Music.videoPkg();
            if (pkg == null || pkg.isEmpty() || mediaPkg.isEmpty()
                    || Music.sameApp(pkg, mediaPkg)) return true;
        }
        if (pkg == null || pkg.isEmpty() || Music.looksLikeMusic(pkg) || looksLikeSpokenPkg(pkg)) return false;
        if (looksLikeVideoPkg(pkg)) return true;
        String label = Music.appLabel(c, pkg).toLowerCase(java.util.Locale.ROOT);
        for (String w : VIDEO_WORDS) if (label.contains(w)) return true;
        if (!Music.looksLikeNonMusic(pkg)) return false;
        try {
            AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            return am != null && am.isMusicActive();
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean looksLikeVideoPkg(String pkg) {
        String p = pkg.toLowerCase(java.util.Locale.ROOT);
        for (String k : VIDEO_PKGS) if (p.contains(k)) return true;
        return false;
    }

    /** Avoid taking over a reading session; normal screen context can still let her follow along. */
    public static boolean reading(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        String p = pkg.toLowerCase(java.util.Locale.ROOT);
        for (String k : READER_PKGS) if (p.contains(k)) return true;
        return false;
    }

    /** 正在看的视频应用名；不在看视频时返回空。 */
    public static String videoApp(Context c) {
        String foreground = Bus.currentPackage;
        if (foreground != null && !foreground.isEmpty() && looksLikeVideoPkg(foreground)) {
            return appLabel(c);
        }
        String mediaPkg = Music.videoPkg();
        if (foreground != null && !foreground.isEmpty() && !mediaPkg.isEmpty()
                && !Music.sameApp(foreground, mediaPkg)) return "";
        String a = Music.videoApp();
        if (!a.isEmpty()) return a;
        if (!video(c)) return "";
        return appLabel(c);
    }

    /** 是否在听小说、有声书、评书或播客；这类声音播放时不适合主动闲聊。 */
    public static boolean spoken() {
        if (!Music.playing()) return false;
        String pkg = Music.sourcePkg();
        if (pkg != null) {
            String p = pkg.toLowerCase(java.util.Locale.ROOT);
            for (String k : SPOKEN_PKGS) if (p.contains(k)) return true;
        }
        String all = (Music.sourceApp() + " " + Music.title() + " " + Music.artist())
                .toLowerCase(java.util.Locale.ROOT);
        for (String k : SPOKEN_WORDS) if (all.contains(k)) return true;
        // 长时间、没有歌词的音频更可能是有声书/播客，而不是歌曲
        return Music.duration() > 15 * 60 * 1000L
                && (Music.lines() == null || Music.lines().isEmpty());
    }

    /** 用户正停留在聊天应用里，先不主动插话。 */
    public static boolean chatting() {
        String pkg = Bus.currentPackage;
        if (pkg == null || pkg.isEmpty()) return false;
        for (String k : CHAT_PKGS) if (pkg.equals(k) || pkg.startsWith(k + ".")) return true;
        return false;
    }
}
