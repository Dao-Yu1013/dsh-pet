package com.dshpet.android;

import android.content.ComponentName;
import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.SystemClock;

import org.json.JSONObject;

import java.util.List;
import java.util.Locale;

/** 读取系统正在播放的音乐、歌词，并驱动哼歌动作。 */
public final class Music {
    private static volatile String title = "";
    private static volatile String artist = "";
    private static volatile boolean playing;
    private static volatile long basePos;
    private static volatile long baseAt;
    private static volatile long duration;
    private static volatile boolean hasSession;
    private static volatile String lastKey = "";
    private static volatile List<Lrc.Line> lines;
    private static volatile int lineIdx = -1;
    private static volatile long lastEmit;
    private static volatile MediaController controller;
    private static volatile long lastFetchAt;
    private static volatile int fetchTries;
    private static volatile String fetchKey = "";
    private static volatile String sourcePkg = "";
    private static volatile String sourceApp = "";
    private static volatile String notifyLine = "";
    private static volatile long notifyLineAt;
    /** K歌类上一次从歌名字段拿到的歌词行：标题不变时不重复续命，避免一直盖住曲库歌词。 */
    private static volatile String lastKtvLine = "";
    private static volatile String lastNotifyLine = "";
    private static volatile long fallbackAt;
    /** 非音乐媒体（短视频/长视频/直播/短剧）是否在播放；只用于场景判断。 */
    private static volatile boolean videoPlaying;
    private static volatile long videoAt;
    private static volatile String videoPkg = "";
    private static volatile String videoApp = "";

    /** 当前在播放的音乐应用包名。 */
    public static String sourcePkg() {
        return sourcePkg;
    }

    /** 当前在播放的音乐应用名，例如「汽水音乐」。 */
    public static String sourceApp() {
        return sourceApp;
    }

    /** 通知栏歌词的有效期：只要还算新鲜就一直以它为准（长间奏可能几十秒不换行）。 */
    private static final long NOTIFY_LINE_TTL = 300000L;

    /** 当前是否拿到了播放器推到通知栏的歌词。 */
    public static boolean hasNotifyLyric() {
        return !notifyLine.isEmpty() && System.currentTimeMillis() - notifyLineAt < NOTIFY_LINE_TTL;
    }

    /** 歌词来自哪里：notify=播放器通知栏，library=公开曲库，空=暂无。 */
    public static String lyricSource() {
        if (hasNotifyLyric()) return "notify";
        List<Lrc.Line> l = lines;
        if (l != null && !l.isEmpty()) return "library";
        return "";
    }

    /** 媒体会话的包名有时带进程后缀（com.tencent.qqmusic:remote），通知里不带，比较时先剥掉。 */
    static String basePkg(String p) {
        if (p == null) return "";
        int i = p.indexOf(':');
        return (i > 0 ? p.substring(0, i) : p).trim();
    }

    static boolean sameApp(String a, String b) {
        String x = basePkg(a);
        String y = basePkg(b);
        return !x.isEmpty() && x.equals(y);
    }

    /**
     * 播放器推到通知栏的当前歌词行。
     * 网易云 / QQ音乐 / 酷狗等开启「通知栏歌词」后，通知正文就是正在唱的那句，
     * 这是唯一能拿到「播放器自己那份歌词」的正规途径。
     */
    public static void fromNotificationLine(String pkg, String title, String text) {
        if (text == null || pkg == null) return;
        if (!sourcePkg.isEmpty() && !sameApp(pkg, sourcePkg)) return;
        String s = text.trim();
        if (badLine(s, title, artist())) return;
        if (s.equals(notifyLine)) {
            notifyLineAt = System.currentTimeMillis();
            return;
        }
        notifyLine = s;
        notifyLineAt = System.currentTimeMillis();
    }

    private static final String[] LINE_NOISE = {"点击", "暂停", "继续", "上一首", "下一首", "播放中", "正在播放", "正在进行",
            "当前播放", "分享歌词", "桌面歌词", "一起听", "打开歌词", "歌词：", "歌词:",
            "音质", "下载", "订阅", "收藏", "评论", "倍速", "跳转"};

    /** 一行文本是不是「歌词」：排除歌名、歌手、控制栏、时间戳和噪音文案。 */
    static boolean badLine(String text, String title, String artist) {
        if (text == null) return true;
        String s = text.trim();
        if (s.length() < 2 || s.length() > 80) return true;
        if (s.equals(title == null ? "" : title.trim())) return true;
        String t = title();
        String a = artist == null ? "" : artist;
        if (!t.isEmpty() && s.equals(t)) return true;
        if (!a.isEmpty() && s.equals(a)) return true;
        // 有些播放器把「歌手 - 歌名」放在正文，这不是歌词
        if (s.contains(" - ") && ((!t.isEmpty() && s.contains(t)) || (!a.isEmpty() && s.contains(a)))) return true;
        if (ktvMeta(s) != null) return true;
        for (String k : LINE_NOISE) {
            if (s.contains(k)) return true;
        }
        return s.matches(".*\\d{1,2}:\\d{2}.*") && s.length() < 12;
    }

    /** 有些播放器把当前歌词写进媒体会话的副标题/描述，或自定义 key（lyric / 歌词）。 */
    private static String metaLyric(MediaMetadata md, String t, String a) {
        if (md == null) return "";
        String[] std = {MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION};
        for (String k : std) {
            String v = mdText(md, k);
            if (!v.isEmpty() && !v.equals(a) && !badLine(v, t, a)) return v;
        }
        try {
            for (String k : md.keySet()) {
                if (k == null) continue;
                String low = k.toLowerCase(Locale.ROOT);
                if (!low.contains("lyric") && !k.contains("歌词")) continue;
                String v = mdText(md, k);
                if (!v.isEmpty() && !badLine(v, t, a)) return v;
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    /** 通知里的副标题、信息行、大文本也可能是歌词（部分机型只把歌词塞在这里）。 */
    private static String extraLyric(String t, String a) {
        try {
            android.app.Notification n = Notify.mediaNotification();
            if (n == null || n.extras == null) return "";
            CharSequence[] cs = {
                    n.extras.getCharSequence(android.app.Notification.EXTRA_SUB_TEXT),
                    n.extras.getCharSequence(android.app.Notification.EXTRA_INFO_TEXT),
                    n.extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT),
                    n.extras.getCharSequence(android.app.Notification.EXTRA_TEXT)
            };
            for (CharSequence v : cs) {
                String s = csText(v);
                if (!s.isEmpty() && !s.equals(t) && !s.equals(a) && !badLine(s, t, a)) return s;
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    /** 通知读取权限是否已授予（没授权时媒体会话也拿不到，整条链路会静默失败）。 */
    public static boolean listenerOk(Context c) {
        try {
            String flat = android.provider.Settings.Secure.getString(c.getContentResolver(),
                    "enabled_notification_listeners");
            return flat != null && flat.contains(c.getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    /** 诊断快照：用来定位「为什么这台手机不显示歌词」。 */
    public static JSONObject diag(Context c) {
        JSONObject o = new JSONObject();
        try {
            o.put("listener", listenerOk(c));
            o.put("playing", playing);
            o.put("hasSession", hasSession);
            o.put("pkg", sourcePkg);
            o.put("app", sourceApp);
            o.put("title", title);
            o.put("artist", artist);
            o.put("source", lyricSource());
            o.put("lines", lines == null ? 0 : lines.size());
            o.put("notifyLine", notifyLine);
            o.put("notifyAge", notifyLine.isEmpty() ? 0 : System.currentTimeMillis() - notifyLineAt);
            android.app.Notification n = Notify.mediaNotification();
            o.put("notifTitle", n == null ? "" : csText(n.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)));
            o.put("notifText", n == null ? "" : csText(n.extras.getCharSequence(android.app.Notification.EXTRA_TEXT)));
            o.put("notifBig", n == null ? "" : csText(n.extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT)));
            return o;
        } catch (Throwable t) {
            return o;
        }
    }

    private Music() {
    }

    public static boolean playing() {
        return playing;
    }

    /** 是否有非音乐媒体正在播放（短视频/长视频/直播/短剧）。15 秒内刷新过才算。 */
    public static boolean videoPlaying() {
        return videoPlaying && SystemClock.elapsedRealtime() - videoAt < 15000L;
    }

    /** 正在播放视频的应用包名；没有时为空。 */
    public static String videoPkg() {
        return videoPlaying() ? videoPkg : "";
    }

    /** 正在播放视频的应用名，例如「红果短剧」。 */
    public static String videoApp() {
        return videoPlaying() ? videoApp : "";
    }

    /** 直播、短视频、长视频、浏览器、电商等：会建媒体会话，但不是「听音乐」。 */
    private static final String[] NOT_MUSIC = {
            "aweme", "douyin", "kuaishou", "gifmaker", "huajiao", "inke", "meipai", "pipixia",
            "ixigua", "xiaohongshu", "weibo", "zhihu", "bili", "danmaku", "douyu", "huya",
            "acfun", "qqlive", "qiyi", "youku", "mgtv", "pptv", "sohu", "lekan", "youtube",
            "twitch", "netflix", "browser", "chrome", "firefox", "opera", "mtt", "ucmobile",
            "searchbox", "emmx", "quark", "sogou", "baidu", "taobao", "tmall", "jdmobile",
            "pinduoduo", "game", "shopping"
    };

    /** 明确的音乐应用（含洛雪、汽水音乐），优先级高于下面的黑名单。 */
    private static final String[] MUSIC_PKGS = {
            "cn.toside.music", "luna.music", "cloudmusic", "kugou", "kuwo", "qqmusic",
            "spotify", "ximalaya", "qting", "music"
    };

    /** 是否为明确的音乐来源。 */
    public static boolean looksLikeMusic(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        String p = pkg.toLowerCase(Locale.ROOT);
        for (String k : MUSIC_PKGS) {
            if (p.contains(k)) return true;
        }
        return false;
    }

    /** 该包名是否属于「明显不是音乐」的来源。 */
    public static boolean looksLikeNonMusic(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        // 洛雪、汽水等明确音乐应用优先，避免被短视频/直播规则误伤
        if (looksLikeMusic(pkg)) return false;
        String p = pkg.toLowerCase(Locale.ROOT);
        for (String k : NOT_MUSIC) {
            if (p.contains(k)) return true;
        }
        return false;
    }

    public static String title() {
        return title;
    }

    /** 包名对应的应用名；查不到时退回包名。 */
    static String appLabel(Context c, String pkg) {
        if (pkg == null || pkg.isEmpty()) return "";
        try {
            android.content.pm.PackageManager pm = c.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Throwable t) {
            return pkg;
        }
    }

    public static String artist() {
        return artist;
    }

    public static String key() {
        return (title + "|" + artist).toLowerCase();
    }

    public static long position() {
        if (!playing) return basePos;
        return basePos + (SystemClock.elapsedRealtime() - baseAt);
    }

    public static long duration() {
        return duration;
    }

    public static List<Lrc.Line> lines() {
        return lines;
    }

    /**
     * 慢歌判定：用歌词行与行之间的平均间隔当节奏代理指标。
     * 行切换越慢，歌越安静（抒情、民谣）；没有歌词时返回 false，不硬猜。
     */
    public static boolean slow() {
        List<Lrc.Line> ls = lines;
        if (ls == null || ls.size() < 6) return false;
        long sum = 0;
        int n = 0;
        for (int k = 1; k < ls.size() && n < 40; k++) {
            long gap = ls.get(k).ms - ls.get(k - 1).ms;
            // 跳过间奏、空行造成的超长间隔，避免把歌误判成极慢
            if (gap <= 0 || gap > 12000) continue;
            sum += gap;
            n++;
        }
        if (n < 4) return false;
        return sum / n >= 6500;
    }

    public static void refresh(Context c) {
        try {
            MediaSessionManager m = (MediaSessionManager) c.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (m == null) return;
            List<MediaController> list = m.getActiveSessions(new ComponentName(c, Notify.class));
            boolean video = false;
            String vPkg = "";
            String vApp = "";
            for (MediaController mc : list) {
                String pn = mc.getPackageName() == null ? "" : mc.getPackageName();
                // 音乐/听书类不算视频；其余任何正在播放的会话都按视频/短剧处理
                if (pn.isEmpty() || looksLikeMusic(pn) || Scene.looksLikeSpokenPkg(pn)) continue;
                PlaybackState ps = mc.getPlaybackState();
                if (ps != null && ps.getState() == PlaybackState.STATE_PLAYING) {
                    video = true;
                    vPkg = pn;
                    vApp = appLabel(c, pn);
                    break;
                }
            }
            videoPlaying = video;
            videoPkg = vPkg;
            videoApp = vApp;
            videoAt = SystemClock.elapsedRealtime();
            MediaController best = null;
            PlaybackState bestState = null;
            // 第一轮：优先选洛雪、汽水这类明确的音乐应用
            for (MediaController mc : list) {
                if (!looksLikeMusic(mc.getPackageName())) continue;
                PlaybackState ps = mc.getPlaybackState();
                if (ps != null && ps.getState() == PlaybackState.STATE_PLAYING) {
                    best = mc;
                    bestState = ps;
                    break;
                }
                if (best == null && mc.getMetadata() != null) {
                    best = mc;
                    bestState = ps;
                }
            }
            // 第二轮：其他音乐来源；直播、短视频、视频网站会话直接跳过
            if (best == null) {
                for (MediaController mc : list) {
                    if (looksLikeNonMusic(mc.getPackageName())) continue;
                    PlaybackState ps = mc.getPlaybackState();
                    if (ps != null && ps.getState() == PlaybackState.STATE_PLAYING) {
                        best = mc;
                        bestState = ps;
                        break;
                    }
                    if (best == null && mc.getMetadata() != null) {
                        best = mc;
                        bestState = ps;
                    }
                }
            }
            hasSession = best != null;
            controller = best;
            if (best == null) {
                sourcePkg = "";
                sourceApp = "";
                notifyLine = "";
                update("", "", false, 0, 0);
                return;
            }
            String pn = best.getPackageName() == null ? "" : best.getPackageName();
            // 包名带 :remote 之类进程后缀时，应用名查不到，先剥掉再查
            if (!pn.equals(sourcePkg)) {
                sourcePkg = pn;
                notifyLine = "";
                lastNotifyLine = "";
                try {
                    android.content.pm.PackageManager pm = c.getPackageManager();
                    sourceApp = pm.getApplicationLabel(pm.getApplicationInfo(basePkg(pn), 0)).toString();
                } catch (Throwable t) {
                    sourceApp = pn;
                }
            }
            MediaMetadata md = best.getMetadata();
            String t = mdText(md, MediaMetadata.METADATA_KEY_TITLE);
            String a = mdText(md, MediaMetadata.METADATA_KEY_ARTIST);
            // 有些播放器只填 DISPLAY_TITLE / DISPLAY_SUBTITLE，歌名歌手就丢了
            if (t.isEmpty()) t = mdText(md, MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
            if (a.isEmpty()) a = mdText(md, MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
            if (a.isEmpty()) {
                // 副标题常是整行「歌名 — 歌手」，直接当歌手会显示成一长串；
                // 歌名本来就属于标题字段，这里只取破折号后面的歌手
                String sub = mdText(md, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
                int sep = sub.indexOf(" — ");
                if (sep < 0) sep = sub.indexOf(" - ");
                if (sep > 0) a = sub.substring(sep + 3).trim();
                else if (!sub.isEmpty()) a = sub;
            }
            if (t.isEmpty() || a.isEmpty()) {
                // 会话里没给完整信息时，从播放器通知里补；通知正文常是歌词，不能当歌手
                String[] na = notifyMeta();
                if (t.isEmpty()) t = na[0];
                if (a.isEmpty()) a = na[1];
            }
            // K歌类播放器：歌名字段其实是「正在唱的那句」，副标题才是「歌名（版本）— 正在进行歌手」。
            // 不换回来就会拿歌词去当歌名搜，永远搜不到，也就永远没有歌词气泡。
            String ktvLine = null;
            String[] km = ktvMeta(a);
            if (km != null && !t.isEmpty() && t.length() <= 60) {
                ktvLine = t;
                t = km[0];
                a = km[1];
            }
            // 另一种常见摆法（汽水音乐）：标题是「正在唱的那句」，歌手里塞的是「歌名 — 歌手」
            String metaLine = "";
            if (ktvLine == null) {
                String[] dm = dashMeta(a);
                if (dm != null && !t.isEmpty() && !t.equals(dm[0])) {
                    metaLine = t;
                    t = dm[0];
                    a = dm[1];
                }
            }
            boolean play = bestState != null && bestState.getState() == PlaybackState.STATE_PLAYING;
            long pos = bestState == null ? 0 : bestState.getPosition();
            // PlaybackState 里的位置只是「上报那一刻」的值，必须补上流逝时间，
            // 否则播放器不主动刷新时进度永远停在 0，歌词会卡在第一句不动。
            if (play && bestState != null && pos >= 0) {
                long upd = bestState.getLastPositionUpdateTime();
                if (upd > 0) {
                    long elapsed = SystemClock.elapsedRealtime() - upd;
                    if (elapsed > 0) pos += elapsed;
                }
            }
            if (pos < 0) pos = 0;
            long dur = md == null ? 0 : md.getLong(MediaMetadata.METADATA_KEY_DURATION);
            update(t == null ? "" : t, a == null ? "" : a, play, pos, dur);
            // update() 换歌时会清空通知栏歌词，所以 K歌 的那句要在 update 之后写入。
            // 只有歌词行变化才续期：如果播放器标题一直不换，就让曲库歌词有机会接上。
            if (ktvLine != null && !ktvLine.isEmpty() && !ktvLine.equals(lastKtvLine)) {
                lastKtvLine = ktvLine;
                notifyLine = ktvLine;
                notifyLineAt = System.currentTimeMillis();
            }
            if (metaLine != null && !metaLine.isEmpty() && !metaLine.equals(lastKtvLine)) {
                lastKtvLine = metaLine;
                notifyLine = metaLine;
                notifyLineAt = System.currentTimeMillis();
            }
            // 通知正文没有歌词时，再试媒体会话副标题/自定义歌词字段和通知的副文本
            if (!hasNotifyLyric()) {
                String extra = metaLyric(md, t == null ? "" : t, a == null ? "" : a);
                if (extra.isEmpty()) extra = extraLyric(t == null ? "" : t, a == null ? "" : a);
                if (!extra.isEmpty()) {
                    notifyLine = extra;
                    notifyLineAt = System.currentTimeMillis();
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** 由通知兜底（MediaSession 拿不到时）。 */
    public static void maybeFromNotification(String t, String a, boolean play) {
        if (hasSession) return;
        if (t == null || t.trim().isEmpty()) return;
        String title = t.trim();
        String artist = a == null ? "" : a.trim();
        // K歌类：标题是在唱的那句，正文才是「歌名 — 正在进行歌手」
        String[] km = ktvMeta(artist);
        if (km != null) {
            update(km[0], km[1], play, 0, 0);
            notifyLine = title;
            notifyLineAt = System.currentTimeMillis();
            return;
        }
        // 汽水这类：正文是「歌名 — 歌手」，标题里是正在唱的那句
        String[] dm = dashMeta(artist);
        if (dm != null && looksLyric(title, dm[0])) {
            update(dm[0], dm[1], play, 0, 0);
            notifyLine = title;
            notifyLineAt = System.currentTimeMillis();
            return;
        }
        // 通知标题常见「歌名 - 歌手」；正文在通知栏歌词模式下是歌词，不能当歌手
        int sep = title.indexOf(" - ");
        if (sep > 0) {
            artist = title.substring(sep + 3).trim();
            title = title.substring(0, sep).trim();
        }
        if (looksLyric(artist, title)) artist = "";
        update(title, artist, play, 0, 0);
    }

    private static String mdText(MediaMetadata md, String key) {
        if (md == null) return "";
        try {
            String v = md.getString(key);
            return v == null ? "" : v.trim();
        } catch (Throwable t) {
            return "";
        }
    }

    private static String csText(CharSequence cs) {
        return cs == null ? "" : String.valueOf(cs).trim();
    }

    /** K歌/直播类播放器会把这些词写在「歌名 — 状态」里。 */
    private static final String[] KTV_STATUS = {"正在进行", "正在演唱", "正在合唱", "正在唱", "正在PK", "正在连麦"};

    /**
     * K歌类播放器（如全民K歌）会把「当前唱到的那句」放在通知标题，
     * 把「歌名（版本） — 正在进行歌手」放在正文或副标题。
     * 返回 {歌名, 歌手}，不像这种格式就返回 null。
     */
    static String[] ktvMeta(String s) {
        if (s == null) return null;
        String v = s.trim();
        if (v.length() < 4 || v.length() > 80) return null;
        int sep = v.lastIndexOf('—');
        if (sep < 0) sep = v.lastIndexOf('–');
        if (sep <= 0 || sep >= v.length() - 1) return null;
        String song = v.substring(0, sep).trim();
        String right = v.substring(sep + 1).trim();
        boolean status = false;
        for (String k : KTV_STATUS) {
            if (right.startsWith(k)) {
                status = true;
                break;
            }
        }
        if (!status) return null;
        String singer = right;
        for (String k : KTV_STATUS) singer = singer.replace(k, "");
        singer = singer.trim();
        if (song.isEmpty() || singer.isEmpty()) return null;
        return new String[]{song, singer};
    }

    /**
     * 「歌名 — 歌手」型的元信息（汽水音乐常把这种整行塞进歌手里）。
     * 返回 {歌名, 歌手}；破折号两边都得像名字，否则返回 null，避免把含破折号的歌词拆坏。
     */
    static String[] dashMeta(String s) {
        if (s == null) return null;
        String v = s.trim();
        int sep = v.indexOf(" — ");
        if (sep <= 0) sep = v.indexOf(" - ");
        if (sep <= 0) return null;
        String song = v.substring(0, sep).trim();
        String singer = v.substring(sep + 3).trim();
        if (song.isEmpty() || singer.isEmpty()) return null;
        if (song.length() > 40 || singer.length() > 40) return null;
        if (looksLyric(singer, song)) return null;
        return new String[]{song, singer};
    }

    /** 一行文本更像歌词而不是歌手名。 */
    private static boolean looksLyric(String s, String title) {
        if (s == null) return false;
        String v = s.trim();
        if (v.isEmpty()) return false;
        if (!title.isEmpty() && v.equals(title)) return false;
        if (v.length() > 14) return true;
        return v.contains("，") || v.contains("。") || v.contains("！") || v.contains("？");
    }

    /**
     * 从播放器通知里补 [歌名, 歌手]。
     * 通知正文在开启「通知栏歌词」后是正在唱的那句，不能直接当歌手用。
     */
    private static String[] notifyMeta() {
        String[] out = {"", ""};
        android.app.Notification n;
        try {
            n = Notify.mediaNotification();
        } catch (Throwable t) {
            return out;
        }
        if (n == null) return out;
        String nt = csText(n.extras.getCharSequence(android.app.Notification.EXTRA_TITLE));
        String nx = csText(n.extras.getCharSequence(android.app.Notification.EXTRA_TEXT));
        if (nt.isEmpty()) nt = csText(n.extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT));
        if (!nt.isEmpty()) {
            int sep = nt.indexOf(" - ");
            if (sep > 0) {
                out[0] = nt.substring(0, sep).trim();
                out[1] = nt.substring(sep + 3).trim();
            } else {
                out[0] = nt;
            }
        }
        if (out[1].isEmpty() && !nx.isEmpty() && !looksLyric(nx, out[0])) out[1] = nx;
        return out;
    }

    public static synchronized void update(String t, String a, boolean play, long pos, long dur) {
        boolean changed = !t.equals(title) || !a.equals(artist) || play != playing;
        // 播放器只重复上报同一个旧位置（常见于洛雪、汽水）时，沿用推算进度，
        // 否则歌词会一直停在开头。
        if (!changed && play && playing && pos == 0) {
            long cur = position();
            if (cur > 1500) pos = cur;
        }
        title = t;
        artist = a;
        playing = play;
        basePos = pos;
        baseAt = SystemClock.elapsedRealtime();
        if (dur > 0) duration = dur;
        Bus.musicTitle = t;
        Bus.musicArtist = a;
        Bus.musicPlaying = play;
        String k = key();
        long now = System.currentTimeMillis();
        if (changed && !k.equals(lastKey)) {
            lastKey = k;
            lines = null;
            lineIdx = -1;
            // 换歌后旧通知栏歌词不再属于当前歌曲，先清掉等新歌词
            notifyLine = "";
            notifyLineAt = 0;
            lastNotifyLine = "";
            lastKtvLine = "";
            fetchKey = k;
            fetchTries = 0;
            lastFetchAt = 0;
            // 先给「播放器通知栏歌词」留出机会，这段时间不去曲库查
            fallbackAt = now + 9000L;
        } else if (!t.isEmpty() && play && (lines == null || lines.isEmpty())
                && !hasNotifyLyric() && now >= fallbackAt
                && fetchTries < 6 && now - lastFetchAt > 25000L) {
            // 通知栏没有歌词时，才按歌名去网易云 / LRCLIB 取
            if (!k.equals(fetchKey)) {
                fetchKey = k;
                fetchTries = 0;
            }
            fetchLyrics();
        }
        if (changed || now - lastEmit > 8000) {
            lastEmit = now;
            try {
                JSONObject o = new JSONObject();
                o.put("title", title);
                o.put("artist", artist);
                o.put("playing", playing);
                o.put("position", position());
                o.put("duration", duration);
                Bus.emit("music", o);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void fetchLyrics() {
        final String t = title, a = artist;
        if (t.isEmpty()) return;
        lastFetchAt = System.currentTimeMillis();
        fetchTries++;
        Lrc.fetch(t, a, duration, new Lrc.Cb() {
            @Override
            public void ok(List<Lrc.Line> l) {
                if (!t.equals(title) || !a.equals(artist)) return;
                lines = l;
                lineIdx = -1;
                try {
                    JSONObject o = new JSONObject();
                    o.put("title", t);
                    o.put("lines", l.size());
                    Bus.emit("lyrics", o);
                } catch (Throwable ignored) {
                }
            }

            @Override
            public void fail() {
            }
        });
    }

    /** 当前应显示的歌词行；同一句不会重复返回。 */
    public static String nextLine() {
        // 播放器自己推到通知栏的歌词最准，优先用它
        String nn = notifyLine;
        if (!nn.isEmpty() && System.currentTimeMillis() - notifyLineAt < NOTIFY_LINE_TTL) {
            if (!nn.equals(lastNotifyLine)) {
                lastNotifyLine = nn;
                return nn;
            }
        } else {
            lastNotifyLine = "";
        }
        List<Lrc.Line> l = lines;
        if (l == null || l.isEmpty() || !playing) return "";
        long p = position() + 350;
        int idx = -1;
        for (int i = 0; i < l.size(); i++) {
            if (l.get(i).ms <= p) idx = i;
            else break;
        }
        if (idx < 0) return "";
        if (idx != lineIdx) {
            lineIdx = idx;
            return l.get(idx).text;
        }
        return "";
    }

    public static void resetLine() {
        lineIdx = -1;
    }

    /** 当前播放到第几行，不改变轮播状态。 */
    public static int lineIndex() {
        List<Lrc.Line> l = lines;
        if (l == null || l.isEmpty() || !playing) return -1;
        long p = position() + 350;
        int idx = -1;
        for (int i = 0; i < l.size(); i++) {
            if (l.get(i).ms <= p) idx = i;
            else break;
        }
        return idx;
    }

    public static String currentLineText() {
        // 通知栏歌词往往没有本地时间轴，但播放器推来的那句就是当前歌词
        if (hasNotifyLyric()) return notifyLine;
        int idx = lineIndex();
        List<Lrc.Line> l = lines;
        if (idx < 0 || l == null || idx >= l.size()) return "";
        return l.get(idx).text;
    }

    public static boolean transport(boolean next) {
        return transport(next, null);
    }

    /** 切歌：优先走媒体会话，失败时回退为广播媒体按键。 */
    public static boolean transport(boolean next, Context c) {
        try {
            MediaController ctl = controller;
            if (ctl != null && ctl.getTransportControls() != null) {
                if (next) ctl.getTransportControls().skipToNext();
                else ctl.getTransportControls().skipToPrevious();
                return true;
            }
        } catch (Throwable ignored) {
        }
        if (c != null) {
            try {
                android.media.AudioManager am =
                        (android.media.AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
                int code = next ? android.view.KeyEvent.KEYCODE_MEDIA_NEXT
                        : android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS;
                am.dispatchMediaKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code));
                am.dispatchMediaKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, code));
                return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }
}
