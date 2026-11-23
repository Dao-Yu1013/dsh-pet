package com.dshpet.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class PetSvc extends Service implements Pet.Host, Bus.Sink {
    public static final String A_START = "dsh.START";
    public static final String A_STOP = "dsh.STOP";
    public static final String A_RELOAD = "dsh.RELOAD";
    public static final String A_LOCK = "dsh.LOCK";
    public static final String A_HIDE = "dsh.HIDE";
    public static final String A_SHOW = "dsh.SHOW";
    public static final String A_SAY = "dsh.SAY";
    public static final String A_SAY_IMPORTANT = "dsh.SAY_IMPORTANT";
    public static final String A_SAY_WEATHER = "dsh.SAY_WEATHER";
    /** 天气/气象预警提醒：气泡 + 通知栏 + 写进聊天记录。 */
    public static final String A_WEATHER_ALERT = "dsh.WEATHER_ALERT";
    public static final String A_SAY_GREET = "dsh.SAY_GREET";
    public static final String A_SAY_PLUGIN = "dsh.SAY_PLUGIN";
    public static final String A_PREVIEW = "dsh.PREVIEW";
    public static final String A_SCENE_ACTION = "dsh.SCENE_ACTION";
    public static final String A_SIZE = "dsh.SIZE";
    /** 临时放大：dp + ms，到时自动恢复 config 里的尺寸（不写 config）。 */
    public static final String A_GROW = "dsh.GROW";
    public static final String A_OPACITY = "dsh.OPACITY";
    public static final String A_POMO_START = "dsh.POMO_START";
    public static final String A_POMO_STOP = "dsh.POMO_STOP";
    public static final String A_FEED = "dsh.FEED";
    public static final String A_READING = "dsh.READING";
    /** 只重读配置，不重建桌宠窗口（滑杆等轻量设置）。 */
    public static final String A_CONFIG = "dsh.CONFIG";
    /** 打开桌宠长按菜单里的聊天窗口。 */
    public static final String A_CHAT = "dsh.CHAT";
    /** 皮肤热切换：重新读取 skinId 并换成立绘/原版动态。 */
    public static final String A_SKIN = "dsh.SKIN";

    private static final String CH = "dsh_pet";
    /** 她主动找你时发的消息通知，独立通道，可以在系统设置里单独关掉。 */
    public static final String CH_MSG = "dsh_pet_msg";
    private static final int NID_MSG = 24177;
    private static final int NID = 24176;
    public static volatile boolean running;
    private static volatile PetSvc activeInstance;

    private final Handler h = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock freeActionWakeLock;
    private WindowManager wm;
    private Cfg cfg;
    private PetConf conf;
    private Pet pet;
    private WindowManager.LayoutParams lp;
    private Bubble bubble;
    private Runnable growRestore;
    private Bubble alertBubble;
    private View menu;
    private Panel panel;
    private ClipPanel clipPanel;
    private ActionPanel actionPanel;
    private ChatBox chatBox;
    private View remoteActionPrompt;
    private CompletableFuture<Boolean> remoteActionAnswer;
    private double chatSavedX, chatSavedY;
    private long lastPlugin = 0;
    private long lastGeoLine = 0;
    private boolean hiddenUser, hiddenGame, hiddenSleep, edgeHidden, readingHidden;
    private long lastPosSave, lastBubble, lastHum, lastAiLine, lastReminderShow, lastAffTap, lastRealtimeBubble, lastCalTip, lastMusicLine, lastLyricAt, lastActionLine, lastLyricKeyAt, lastTapAt, lastTapRequestAt;
    private String lastLyricKey = "";
    /** 已经提示过「打开通知栏歌词」的歌曲，避免每首歌反复念同一句。 */
    private String lastLyricHintKey = "";
    private String lastRealtimeType = "";
    private boolean suppressBubble;
    private String lastMusicKey = "";
    /** 上一次聊歌曲的时间：同一段时间内不反复聊歌，避免刷屏和烧 token。 */
    private long lastSongChat = 0;
    private String notifExtra = "";
    /** 主动说话的 AI 请求是否在途：防止并发叠请求导致重复生成与重复记账。 */
    private volatile boolean proactiveBusy = false;
    /** busy 开始时间：超过 90 秒还没回调就强制复位，避免一个丢掉的回调永久卡住主动链。 */
    private long proactiveBusyAt = 0;
    /** 用户说话 / 看门狗复位时自增，旧请求的迟到回调用它判断自己是否已经作废。 */
    private volatile int proactiveToken = 0;
    /** 音乐/屏幕闲聊的错峰队列：两条挤在一起时，后一条自动顺延到下一个空位。 */
    private long pendingSocialAt;
    /**
     * 本 Service 实例是否还活着（实例级，刻意不用 static）。
     * running 是静态的，只表示「进程里有服务在跑」，无法区分「旧实例已停止、新实例已启动」；
     * 旧实例的 AI 迟到回调用它判断自己是否还有资格执行记账 / 亲密度 / 气泡 / 语音。
     * 注意：不能在 teardown() 里置 false —— A_RELOAD 会走 teardown → spawn → startTicker 复用同一实例。
     */
    private volatile boolean alive = true;
    private static volatile JSONObject pomoState = new JSONObject();
    private boolean pomoRunning;
    private String pomoPhase = "idle";
    private long pomoEndAt;
    private int pomoWork = 25, pomoRest = 5;
    /** 单击桌宠的鸭叫音效；用户可关。 */
    private SoundPool sounds;
    private final int[] duckIds = new int[8];

    private void initSounds() {
        if (sounds != null) return;
        try {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            sounds = new SoundPool.Builder().setMaxStreams(2).setAudioAttributes(attrs).build();
            int[] raw = {R.raw.duck1, R.raw.duck2, R.raw.duck3, R.raw.duck4,
                    R.raw.duck5, R.raw.duck6, R.raw.duck7, R.raw.duck8};
            for (int i = 0; i < raw.length; i++) duckIds[i] = sounds.load(this, raw[i], 1);
        } catch (Throwable t) {
            sounds = null;
        }
    }

    private void playDuck() {
        if (cfg == null || !cfg.b("petSound", true)) return;
        if (sounds == null) initSounds();
        if (sounds == null) return;
        int id = duckIds[(int) (Math.random() * duckIds.length)];
        if (id == 0) return;
        try {
            sounds.play(id, 0.9f, 0.9f, 1, 0, 1f);
        } catch (Throwable ignored) {
        }
    }

    private void releaseSounds() {
        if (sounds == null) return;
        try {
            sounds.release();
        } catch (Throwable ignored) {
        }
        sounds = null;
    }

    private final Runnable pomo = new Runnable() {
        @Override
        public void run() {
            if (!pomoRunning) return;
            long now = System.currentTimeMillis();
            if ("work".equals(pomoPhase)) {
                Taskbook.finishFocus(PetSvc.this, true);
                proactiveSay("focus", "专注结束，站起来活动一下。", 2, 6000);
                if (pet != null && !hidden() && !pet.isBusy()) {
                    suppressBubble = true;
                    pet.playAction("a007");
                }
                pomoPhase = "rest";
                pomoEndAt = now + pomoRest * 60000L;
                h.postDelayed(this, pomoRest * 60000L);
            } else {
                proactiveSay("focus", "休息结束，继续加油。", 2, 5000);
                pomoPhase = "work";
                pomoEndAt = now + pomoWork * 60000L;
                Taskbook.startFocus(PetSvc.this, "", "番茄钟", pomoWork);
                h.postDelayed(this, pomoWork * 60000L);
            }
            emitPomo();
        }
    };

    public static JSONObject pomoState() {
        try {
            return new JSONObject(pomoState.toString());
        } catch (Throwable t) {
            return new JSONObject();
        }
    }

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            boolean active = false;
            try {
                if (pet != null) active = pet.tick();
            } catch (Throwable t) {
                Sg.write(new java.io.File(Sg.dir(PetSvc.this), "diag.txt"), Sg.now() + " tick " + t + "\n");
            }
            h.postDelayed(this, active ? 16 : 500);
        }
    };

    private final Runnable gameCheck = new Runnable() {
        @Override
        public void run() {
            try {
                boolean game = false;
                if (cfg != null && cfg.b("gameEnabled", true) && cfg.arr("games").length() > 0) {
                    String pkg = Perm.a11y(PetSvc.this) ? Bus.currentPackage : Fg.current(PetSvc.this);
                    game = cfg.isGame(pkg);
                }
                if (game != hiddenGame) {
                    hiddenGame = game;
                    applyVisibility();
                    // 退出游戏后立刻按正常间隔重新排一次，不必等上一个 30 分钟
                    if (!game) safeScheduleProactive(0);
                }
            } catch (Throwable ignored) {
            }
            h.postDelayed(this, 4000);
        }
    };

    /** 定时刷新屏幕文字：只有亮屏 + 开了应用时才按设定频率读，锁屏/灭屏不识别也不计时。 */
    private final Runnable screenPoll = new Runnable() {
        @Override
        public void run() {
            long next = 3000L;
            try {
                boolean ready = cfg != null && cfg.b("screenContext", true) && !hidden()
                        && A11y.awake(PetSvc.this)
                        && A11y.hasForegroundApp(PetSvc.this);
                if (ready) {
                    A11y.refreshNow();
                    // 两个条件都满足后，才按「识别频率」计算下一次；下限 3 秒
                    next = Math.max(3, cfg.i("screenScanSec", 3)) * 1000L;
                }
            } catch (Throwable ignored) {
            }
            h.postDelayed(this, next);
        }
    };

    /** 天气/气象预警巡检：每 10 分钟醒一次，真正查询由 Weather 内部按 3 小时节流。 */
    private final Runnable weatherTick = new Runnable() {
        @Override
        public void run() {
            try {
                if (cfg == null || cfg.b("weatherAlertCheck", true)) {
                    Weather.checkAlert(PetSvc.this, false);
                }
            } catch (Throwable ignored) {
            }
            h.postDelayed(this, 10 * 60 * 1000L);
        }
    };

    private final Runnable musicPoll = new Runnable() {
        @Override
        public void run() {
            try {
                if (cfg != null && (cfg.b("musicAware", true) || cfg.b("lyricBubble", true))) {
                    Music.refresh(PetSvc.this);
                    if (Music.playing() && cfg.b("musicAware", true) && !hidden() && cfg.b("musicHum", true)) {
                        long now = System.currentTimeMillis();
                        int sec = Math.max(5, cfg.i("musicHumSec", 55));
                        if (now - lastHum > sec * 1000L) {
                            lastHum = now;
                            hum();
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            h.postDelayed(this, Music.playing() ? 3000 : 8000);
        }
    };

    private final Runnable lyricTick = new Runnable() {
        @Override
        public void run() {
            try {
                if (cfg != null && cfg.b("lyricBubble", true) && Music.playing() && !hidden()
                        && !Scene.spoken()) {
                    String key = Music.key();
                    long now = System.currentTimeMillis();
                    if (!key.equals(lastLyricKey)) {
                        lastLyricKey = key;
                        lastLyricAt = 0;
                        lastLyricKeyAt = now;
                    }
                    // 歌词跟着句子走（默认最快 5 秒一句），不再和「哼歌间隔」绑在一起，
                    // 否则默认 55 秒才飘一句，用户会以为根本没识别到歌词
                    int gap = Math.max(5, cfg.i("lyricGapSec", 5));
                    // 她正在说话时不抢气泡，等这句说完再飘歌词
                    boolean busy = bubble != null && bubble.isShowing();
                    if (!busy && now - lastLyricAt >= gap * 1000L) {
                        String line = Music.nextLine();
                        if (line != null && !line.isEmpty()) {
                            lastLyricAt = now;
                            sayLyric("♪ " + line, (int) Math.max(2800, Math.min(6000, gap * 1000L)));
                        } else if (Music.lyricSource().isEmpty() && now - lastLyricKeyAt > 25000L
                                && !key.equals(lastLyricHintKey)) {
                            // 一首歌放了 25 秒还拿不到歌词：直接告诉她去开「通知栏歌词」，别一直沉默
                            lastLyricHintKey = key;
                            sayLyric("♪ 在音乐 App 里打开「通知栏歌词」，我就能跟着这句唱啦", 5200);
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            h.postDelayed(this, Music.playing() ? 1000 : 10000);
        }
    };

    /** 歌词气泡：单独走一条通道，不更新 lastBubble，避免把主动说话的节奏挤没。 */
    private boolean sayLyric(String text, int ms) {
        text = Sticker.strip(text);
        if (text == null || text.trim().isEmpty() || pet == null || bubble == null || hidden()) return false;
        bubble.show(text, (int) pet.x(), (int) pet.y(), pet.sizePx(), pet.heightPx(), ms,
                alertBubble != null && alertBubble.placedAbove());
        return true;
    }

    /** 换歌后让 AI 认识这首歌（有缓存就直接用），稍后再自然聊一句。 */
    private void startSongAi(final String title, final String artist) {
        if (cfg == null || !cfg.b("musicAi", true) || !cfg.aiReady()) return;
        // 等 12 秒再查资料：连着切歌时不会每首都花一次 token
        h.postDelayed(() -> {
            if (!alive || cfg == null || !cfg.b("musicAi", true)) return;
            if (!Music.playing() || !title.equals(Music.title()) || !artist.equals(Music.artist())) return;
            SongAi.learn(this, title, artist, new Ai.Cb() {
                @Override
                public void ok(String knowledge) {
                    // 先听一会儿再开口，避免刚点播放就开始点评
                    h.postDelayed(() -> songComment(title, artist, knowledge), 18000);
                }

                @Override
                public void fail(String err) {
                }
            });
        }, 12000);
    }

    private void songComment(String title, String artist, String knowledge) {
        try {
            if (!alive || cfg == null || !cfg.b("musicAi", true)) return;
            // 18 秒后已经换歌或暂停，就不说了
            if (!Music.playing() || !title.equals(Music.title()) || !artist.equals(Music.artist())) return;
            long now = System.currentTimeMillis();
            long gap = Math.max(2, cfg.i("songChatMin", 8)) * 60000L;
            if (lastSongChat > 0 && now - lastSongChat < gap) return;
            lastSongChat = now;
            SongAi.line(this, title, artist, knowledge, new Ai.Cb() {
                @Override
                public void ok(String text) {
                    h.post(() -> {
                        if (alive) proactiveSay("music", text, 2, 6000, null, true);
                    });
                }

                @Override
                public void fail(String err) {
                }
            });
        } catch (Throwable ignored) {
        }
    }


    private final Runnable sleepCheck = new Runnable() {
        @Override
        public void run() {
            try {
                boolean sleep = cfg != null && cfg.b("sleepEnabled", false) && inSleep();
                if (sleep != hiddenSleep) {
                    hiddenSleep = sleep;
                    applyVisibility();
                }
            } catch (Throwable ignored) {
            }
            h.postDelayed(this, 60000);
        }
    };

    private final Runnable proactive = new Runnable() {
        @Override
        public void run() {
            // 实例已停止：在 try 之前直接返回，且不进入 finally，
            // 否则 finally 里的重新调度会让死实例无限自我续命
            if (!alive) return;
            try {
                Cfg c = PetSvc.this.cfg;
                if (CompanionSession.isRunning(PetSvc.this)) return;
                final boolean freeActionDue = CompanionSession.freeActionPromptDue(PetSvc.this);
                if (c == null || !c.aiReady()
                        || (!c.b("proactive", true) && !freeActionDue)
                        || (hidden() && !canAnnounceFreeActionWhileGameHidden(freeActionDue))) return;
                // 评估前刷新一次屏幕文字，让她有机会就你正在看的东西找话题
                if (c.b("screenContext", true)) A11y.refreshNow();
                // 用户正在聊天：先让路，不排队、不抢先开口
                if (chatBox != null && chatBox.isOpen()) {
                    Policy.recordBlocked(PetSvc.this, "chat", "用户正在聊天", "");
                    return;
                }
                if (Interact.isUserTalking(PetSvc.this)) {
                    Policy.recordBlocked(PetSvc.this, "chat", "用户刚说过话，先让他说", "");
                    return;
                }
                // 上一次主动请求还在等模型返回时不要再叠一次，避免并发超发与重复记账
                if (proactiveBusy) {
                    if (proactiveBusyAt > 0 && System.currentTimeMillis() - proactiveBusyAt > 90000L) {
                        proactiveToken++;
                        proactiveBusy = false;
                        diagLog("proactive.watchdog busy>90s");
                    } else {
                        return;
                    }
                }
                // 普通主动按原策略预判；自由行动走独立策略，避免游戏/视频场景把明确授权挡掉。
                // 静音、专注、夜间仍在请求前拦截，避免空烧 token。
                Policy.Decision pre = freeActionDue
                        ? Policy.decideFreeAction(PetSvc.this)
                        : Policy.decide(PetSvc.this, "chat", 2);
                if (!pre.ok) {
                    Policy.recordBlocked(PetSvc.this, "chat", pre.reason, "");
                    return;
                }
                // 屏幕上读到内容时，直接围绕它找话题，不再被“价值分不足”拦掉；
                // 没有屏幕内容时仍走原来的本地价值评估，避免空聊烧 token
                boolean screenTopic = c.b("screenContext", true)
                        && Bus.screenText != null && !Bus.screenText.trim().isEmpty();
                if (!screenTopic && !freeActionDue) {
                    int value = Value.chatScore(PetSvc.this);
                    if (value < Value.localScore(PetSvc.this)) {
                        Policy.recordBlocked(PetSvc.this, "chat", "价值分不足（" + value + "）", "");
                        return;
                    }
                    if (value < Value.aiScore(PetSvc.this)) {
                        proactiveSay("chat", Value.localLine(PetSvc.this), 2, 3000);
                        return;
                    }
                }
                final int token = ++proactiveToken;
                proactiveBusyAt = System.currentTimeMillis();
                proactiveBusy = true;
                final int[] aiTokens = {0};
                Brain.proactive(PetSvc.this, new Ai.Cb() {
                    @Override
                    public void usage(int totalTokens) {
                        aiTokens[0] = Math.max(0, totalTokens);
                    }

                    @Override
                    public void ok(String text) {
                        h.post(() -> {
                            // 用户已经开口或看门狗已复位：这次迟到结果直接丢弃
                            if (token != proactiveToken) return;
                            proactiveBusy = false;
                            // 本实例已停止：这次生成直接丢弃，不记账、不发声。
                            // 用实例级 alive 而不是静态 running：否则新实例把 running 置回 true 后，
                            // 旧实例的迟到回调会被放行，造成幽灵朗读与额度漂移。
                            if (!alive) return;
                            // 二次校验：等模型返回期间设置可能变了、额度可能被别的主动行为用掉
                            String outgoing = freeActionDue
                                    ? CompanionSession.freeActionAnnouncement(PetSvc.this, text)
                                    : text;
                            boolean freeActionReady = freeActionDue
                                    && CompanionSession.pendingFreeActionStartAllowed(PetSvc.this);
                            if (freeActionDue && !freeActionReady) {
                                // The idle window, setting, or destination changed while AI was thinking.
                                // Drop this stale free-action response instead of presenting a false intention.
                                Curiosity.clearPendingFreeAction(PetSvc.this);
                                return;
                            }
                            Policy.Decision post = freeActionReady
                                    ? Policy.decideFreeAction(PetSvc.this)
                                    : Policy.decide(PetSvc.this, "chat", 2);
                            boolean delivered = freeActionReady
                                    ? announceFreeAction(outgoing, post, aiTokens[0])
                                    : proactiveSay("chat", outgoing, 2, 3000, post, aiTokens[0]);
                            if (delivered && freeActionReady) {
                                if (!CompanionSession.startFreeAction(PetSvc.this, Sticker.strip(outgoing)))
                                    Curiosity.clearPendingFreeAction(PetSvc.this);
                            } else {
                                Curiosity.clearPendingFreeAction(PetSvc.this);
                            }
                        });
                    }

                    @Override
                    public void fail(String err) {
                        h.post(() -> {
                            if (token != proactiveToken) return;
                            proactiveBusy = false;
                            Curiosity.clearPendingFreeAction(PetSvc.this);
                        });
                    }
                });
            } catch (Throwable t) {
                // 运行期异常也必须落日志，不能静默吞掉
                proactiveBusy = false;
                diagLog("proactive.run " + t);
            } finally {
                // 唯一的调度入口：正常 / 早退 / 异常都只经过这里一次，不会重复注册
                safeScheduleProactive(0);
            }
        }
    };

    /** 日历只在每天 0:00 同步一次；其他时间最多读取已有提醒。 */
    private final Runnable calMidnight = new Runnable() {
        @Override
        public void run() {
            try { ChatFiles.cleanup(PetSvc.this); } catch (Throwable ignored) { }
            try { LongDocs.cleanup(PetSvc.this); } catch (Throwable ignored) { }
            try {
                if (Perm.calendar(PetSvc.this) && cfg.b("calEnabled", true)) {
                    Cal.dailySync(PetSvc.this);
                }
            } catch (Throwable ignored) {
            }
            scheduleCalMidnight();
        }
    };

    private void scheduleCalMidnight() {
        h.removeCallbacks(calMidnight);
        java.util.Calendar cc = java.util.Calendar.getInstance();
        long now = System.currentTimeMillis();
        cc.set(java.util.Calendar.HOUR_OF_DAY, 0);
        cc.set(java.util.Calendar.MINUTE, 0);
        cc.set(java.util.Calendar.SECOND, 0);
        cc.set(java.util.Calendar.MILLISECOND, 0);
        long next = cc.getTimeInMillis();
        if (next <= now) next += 86400000L;
        h.postDelayed(calMidnight, Math.max(1000L, next - now));
    }

    private final Runnable minute = new Runnable() {
        @Override
        public void run() {
            try {
                ChatFiles.cleanup(PetSvc.this);
                LongDocs.cleanup(PetSvc.this);
                reminderTick();
                JSONObject w = Wellness.tick(PetSvc.this);
                String type = w.optString("type", "");
                if (!type.isEmpty()) wellnessPrompt(type);
                if (cfg.b("meetRemind", true)) {
                    JSONObject m = Taskbook.meetingTick(PetSvc.this);
                    if (m.optBoolean("ok", false)) proactiveSay("plan", m.optString("text"), 3, 8000);
                }
                // 通知只做实时关心，不再进待办；主动提醒只来自日历和我手动加的提醒
                if (Perm.calendar(PetSvc.this) && cfg.b("calEnabled", true)) {
                    int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
                    long now0 = System.currentTimeMillis();
                    if (hour >= 8 && hour < 22 && now0 - lastCalTip > 1800000L) {
                        String tip = Cal.tip(PetSvc.this);
                        if (!tip.isEmpty()) {
                            lastCalTip = now0;
                            proactiveSay("plan", tip, 3, 8000);
                        }
                    }
                }
                if (cfg.b("geoEnabled", false)) Geo.start(PetSvc.this);
                // 关系成长：跨天结算亲密度、写日记、看有没有值得纪念的里程碑
                Intimacy.dailyRoll(PetSvc.this);
                // 每天用了哪些应用：每小时最多写一次当天快照，页面打开时也会实时刷新
                Usage.snapshot(PetSvc.this);
                // 应用感知：她每隔一段时间会对你在应用里的动作说一句
                String aware = InApp.maybeRemark(PetSvc.this);
                if (!aware.isEmpty()) proactiveSay("aware", aware, 1, 6000);
                // 只做本地静置判断；达到条件后才提前触发一次 AI 决策。
                boolean freeActionDue = CompanionSession.freeActionPromptDue(PetSvc.this);
                if (alive && (!hidden() || canAnnounceFreeActionWhileGameHidden(freeActionDue))
                        && !proactiveBusy && (chatBox == null || !chatBox.isOpen())
                        && !Interact.isUserTalking(PetSvc.this) && freeActionDue) {
                    h.removeCallbacks(proactive);
                    h.post(proactive);
                }
                Diary.maybeGenerate(PetSvc.this);
                String mark = Timeline.touch(PetSvc.this);
                if (!mark.isEmpty()) proactiveSay("plan", mark, 2, 7000);
                // 共同经历：第一次聊天入库；第 7/30/365 天静默写一份回顾，不播报
                Recap.maybeAuto(PetSvc.this);
                if (cfg.b("traitEvolve", true)) Traits.maybeReflect(PetSvc.this);
                // 人设自动演化：每天检查一次，距上次重写满 7 天才真正改语气/开场白/边界
                PersonaAuto.dailyMaybe(PetSvc.this);
                // 定时插件：装了带 background 的插件后，由插件自己判断要不要动
                try {
                    Plugins.tickBackground(PetSvc.this);
                } catch (Throwable ignored) {
                }
                long now = System.currentTimeMillis();
                if (!Bus.screenText.isEmpty() && now - lastPlugin > 600000L) {
                    JSONObject pm = Plugins.match(PetSvc.this, Bus.screenText);
                    if (!pm.optString("line", "").isEmpty()) {
                        lastPlugin = now;
                        proactiveSay(pm.optString("type", "plugin"), pm.optString("line"), 1, 5200);
                    } else {
                        // 配置插件没命中时，再给代码插件一次机会（断网沙箱，无原生能力）
                        Plugins.matchCode(PetSvc.this, Bus.screenText, Bus.currentPackage, res -> {
                            if (!res.optString("line", "").isEmpty()) {
                                lastPlugin = System.currentTimeMillis();
                                proactiveSay(res.optString("type", "plugin"), res.optString("line"), 1, 5200);
                            }
                        });
                    }
                }
            } catch (Throwable ignored) {
            }
            h.postDelayed(this, 60000);
        }
    };

    /**
     * 手动/NLU 提醒到点检查：每分钟跑一次，只处理带 at 且未提醒过的条目。
     * 只有气泡真正显示后才标记 fired，避免被隐藏/静音吞掉后永远丢失。
     */
    private void reminderTick() {
        try {
            org.json.JSONArray a = Data.reminders(this);
            long now = System.currentTimeMillis();
            JSONObject due = null;
            long best = Long.MAX_VALUE;
            for (int k = 0; k < a.length(); k++) {
                JSONObject x = a.optJSONObject(k);
                if (x == null || x.optBoolean("fired", false)) continue;
                String key = x.optString("key", "");
                if (!key.startsWith("manual:") && !key.startsWith("nlu:")) continue;
                long at = x.optLong("at", 0);
                if (at <= 0 || at > now) continue;
                if (at < best) {
                    best = at;
                    due = x;
                }
            }
            if (due == null) return;
            String title = due.optString("title", "");
            if (title.isEmpty()) title = "提醒";
            boolean delivered = proactiveSay("reminder", "该「" + title + "」了。", 3, 8000);
            if (delivered && Data.markReminderFired(this, due.optString("id", ""))) {
                JSONObject o = new JSONObject();
                o.put("count", Data.pendingCount(this));
                // silent=true：只刷新列表，不再触发「记下啦」的二次确认
                o.put("silent", true);
                Bus.emit("reminders", o);
            }
        } catch (Throwable ignored) {
        }
    }

    private void wellnessPrompt(String type) {
        // 健康提醒单独放行，但如果正好和音乐/闲聊挤在一起，自动顺延，避免连刷
        long nowMs = System.currentTimeMillis();
        long gap = 15000L;
        if (lastBubble > 0 && nowMs - lastBubble < gap) {
            long delay = gap - (nowMs - lastBubble);
            Policy.recordBlocked(this, "health", "时间挤在一起，错开 " + Math.max(1, delay / 1000) + " 秒", "");
            h.postDelayed(() -> {
                if (alive) wellnessPrompt(type);
            }, delay);
            return;
        }
        String text;
        if ("water".equals(type)) text = "喝口水吧，我等你。";
        else if ("eye".equals(type)) text = "看远处 20 秒，让眼睛歇一下。";
        else text = "坐久了，跟我拉伸 20 秒。";
        Policy.Decision d = Policy.decide(this, "health", 2);
        if (!d.ok) {
            Policy.recordBlocked(this, "health", d.reason, text);
            return;
        }
        Policy.record(this, "health", d.level, d.reason, text);
        say(text, 5000);
        Voice.speak(this, text, false);
        if (!"stretch".equals(type) || pet == null || hidden()) return;
        org.json.JSONArray steps = Wellness.steps();
        for (int k = 0; k < steps.length(); k++) {
            final JSONObject s = steps.optJSONObject(k);
            if (s == null) continue;
            h.postDelayed(() -> {
                String line = s.optString("text");
                say(line, 6500);
                Voice.speak(PetSvc.this, line, false);
                if (pet != null && !hidden() && !pet.isBusy()) {
                    suppressBubble = true;
                    pet.playAction(s.optString("action"));
                }
            }, k * 7200L);
        }
        Wellness.log(this, type, "guide");
    }

    /** Show the actual destination notice before launching; unlike normal chatter it is never time-queued. */
    private boolean announceFreeAction(String text, Policy.Decision decision, int tokenUsage) {
        if (!alive || text == null || text.trim().isEmpty() || decision == null || !decision.ok
                || pet == null || bubble == null || hiddenUser || hiddenSleep || readingHidden) return false;
        String spokenText = Sticker.strip(text);
        if (spokenText == null || spokenText.trim().isEmpty()) return false;
        lastBubble = System.currentTimeMillis();
        bubble.show(text, (int) pet.x(), (int) pet.y(), pet.sizePx(), pet.heightPx(), 5000,
                alertBubble != null && alertBubble.placedAbove());
        Policy.record(this, "chat", decision.level, decision.reason, text, tokenUsage);
        postSay(spokenText);
        Intimacy.onProactive(this, "chat");
        Interact.onDelivery(this, "chat", text);
        try {
            JSONObject extra = new JSONObject();
            extra.put("improvised", true);
            extra.put("source", "free_action");
            Data.chatAppend(this, "assistant", text, "", "", extra);
        } catch (Throwable ignored) { }
        return true;
    }

    private boolean proactiveSay(String type, String text, int want, int ms) {
        return proactiveSay(type, text, want, ms, null);
    }

    /**
     * decided 不为空时直接复用已经算好的策略结论。
     * 只有主动说话的「生成前预判 + 生成后复核」链路会传入它；其余调用点走上面的重载，行为不变。
     * 复用只是免掉第三次重复评估，计数仍由 Policy.record 唯一一次完成。
     * 返回 true 表示这句话真的送到了用户眼前（用于到点提醒只标记一次）。
     */
    private boolean proactiveSay(String type, String text, int want, int ms, Policy.Decision decided) {
        return proactiveSay(type, text, want, ms, decided,
                "chat".equals(type) || "greet".equals(type));
    }

    private boolean proactiveSay(String type, String text, int want, int ms,
                                 Policy.Decision decided, int tokenUsage) {
        return proactiveSay(type, text, want, ms, decided,
                "chat".equals(type) || "greet".equals(type), tokenUsage);
    }

    private boolean proactiveSay(String type, String text, int want, int ms, Policy.Decision decided,
                                 boolean improv) {
        return proactiveSay(type, text, want, ms, decided, improv, 0);
    }

    private boolean proactiveSay(String type, String text, int want, int ms, Policy.Decision decided,
                                 boolean improv, int tokenUsage) {
        // 本实例已停止时不再执行任何主动副作用（气泡 / 朗读 / 记账 / 记账后的关系更新）
        if (!alive) return false;
        if (text == null || text.trim().isEmpty()) return false;
        if (CompanionSession.isRunning(this)
                && (improv || "chat".equals(type) || "music".equals(type) || "greet".equals(type))) {
            Policy.recordBlocked(this, type, "共同浏览期间只显示屏幕心得", text);
            return false;
        }
        // 生成后去重：闲聊不重复最近说过的话；重要提醒不参与，避免「该喝水」被误杀
        if ("chat".equals(type) && Interact.isDuplicate(this, text)) {
            Policy.recordBlocked(this, type, "最近说过类似的话", text);
            return false;
        }
        Policy.Decision d = decided != null ? decided : Policy.decide(this, type, want);
        if (!d.ok) {
            Policy.recordBlocked(this, type, d.reason, text);
            return false;
        }
        boolean important = Policy.isImportantType(type);
        // 音乐、屏幕闲聊不占主动预算，也不再受 120 秒全局冷却；两条挤在一起时自动顺延
        boolean independent = "chat".equals(type) || "music".equals(type)
                || "weather".equals(type) || "geo".equals(type);
        if (independent && !important) {
            long now = System.currentTimeMillis();
            long gap = 20000L;
            long slot = Math.max(now, Math.max(lastBubble + gap, pendingSocialAt));
            if (slot > now) {
                pendingSocialAt = slot + gap;
                final String qType = type;
                final String qText = text;
                final int qWant = want;
                final int qMs = ms;
                final boolean qImprov = improv;
                final int qTokens = tokenUsage;
                final long userAtQueue = Interact.lastUserAt(this);
                h.postDelayed(() -> {
                    // 延迟期间用户可能已经接着聊了；旧的即兴气泡必须作废，不能插进新对话。
                    if (!alive || Interact.lastUserAt(this) != userAtQueue) return;
                    proactiveSay(qType, qText, qWant, qMs, null, qImprov, qTokens);
                }, slot - now);
                Policy.recordBlocked(this, type, "时间挤在一起，错开 " + Math.max(1, (slot - now) / 1000) + " 秒", text);
                return true;
            }
            pendingSocialAt = now + gap;
        }
        // 其他类型继续用全局冷却，避免多类主动消息连续刷屏
        boolean cooled = "greet".equals(type) || "ad".equals(type) || "plugin".equals(type) || "aware".equals(type);
        if (cooled && !important && !"health".equals(type) && !"focus".equals(type)) {
            int cool = Math.max(30, cfg.i("speakCoolSec", 120));
            long since = System.currentTimeMillis() - lastBubble;
            if (lastBubble > 0 && since < cool * 1000L) {
                Policy.recordBlocked(this, type, "主动说话冷却中（" + cool + " 秒）", text);
                return false;
            }
        }
        boolean delivered;
        if (important && d.level <= 1) {
            // 静音 / 游戏 / 专注 / 夜间：重要提醒只留一个气泡，不朗读、不要求桌宠可见
            delivered = sayImportantBubble(text, ms);
        } else {
            delivered = say(text, ms);
            if (delivered && (d.level >= 3 || important)) {
                Voice.speak(this, Sticker.strip(text), true);
            }
        }
        if (!delivered) {
            Policy.recordBlocked(this, type, "当前状态无法显示（已隐藏）", text);
            return false;
        }
        Policy.record(this, type, d.level, d.reason, text, tokenUsage);
        if (improv && chatBox != null && chatBox.isOpen()) {
            String visible = Sticker.strip(text);
            if (!visible.isEmpty()) {
                JSONObject line = new JSONObject();
                try { line.put("text", visible); Bus.emit("drawMessage", line); }
                catch (Throwable ignored) { }
            }
            String stickerName = Sticker.usableNameIn(text, this);
            if (!stickerName.isEmpty()) {
                JSONObject sticker = new JSONObject();
                try { sticker.put("name", stickerName); Bus.emit("chatSticker", sticker); }
                catch (Throwable ignored) { }
            }
        }
        // 她主动开口时同步写一条通知，用户在别的界面也能收到
        String spokenText = Sticker.strip(text);
        if (("chat".equals(type) || "greet".equals(type))
                && spokenText != null && !spokenText.trim().isEmpty()) postSay(spokenText);
        Intimacy.onProactive(this, type);
        Interact.onDelivery(this, type, text);
        if (improv) {
            try {
                JSONObject extra = new JSONObject();
                extra.put("improvised", true);
                extra.put("source", "music".equals(type) ? "music_mood" : "proactive");
                Data.chatAppend(this, "assistant", text, "", "", extra);
            } catch (Throwable ignored) {
            }
        }
        return true;
    }

    /**
     * 重要提醒的纯气泡通道：静音和游戏中也能看到，但不朗读。
     * 用户自己手动隐藏、睡眠隐藏、阅读隐藏仍然尊重，不弹出。
     */
    private boolean sayImportantBubble(String text, int ms) {
        if (text == null || text.trim().isEmpty() || pet == null || bubble == null) return false;
        if (hiddenUser || hiddenSleep || readingHidden) return false;
        lastBubble = System.currentTimeMillis();
        bubble.show(text, (int) pet.x(), (int) pet.y(), pet.sizePx(), pet.heightPx(), ms,
                alertBubble != null && alertBubble.placedAbove());
        return true;
    }

    public static void start(Context c) {
        Intent i = new Intent(c, PetSvc.class).setAction(A_START);
        try {
            c.startForegroundService(i);
        } catch (Throwable t) {
            try {
                c.startService(i);
            } catch (Throwable ignored) {
            }
        }
    }

    public static void send(Context c, String action, String key, String value) {
        try {
            Intent i = new Intent(c, PetSvc.class).setAction(action);
            if (key != null) i.putExtra(key, value);
            c.startService(i);
        } catch (Throwable ignored) {
        }
    }

    /** 播放聊天回复中由模型选择、且通过本地动作目录校验的情境动作。 */
    public static void sceneAction(Context c, String slug) {
        if (slug == null || slug.trim().isEmpty()) return;
        try {
            Intent i = new Intent(c, PetSvc.class).setAction(A_SCENE_ACTION)
                    .putExtra("slug", slug.trim());
            c.startService(i);
        } catch (Throwable ignored) {
        }
    }

    public static void grow(Context c, int dp, long ms) {
        try {
            Intent i = new Intent(c, PetSvc.class).setAction(A_GROW)
                    .putExtra("dp", dp).putExtra("ms", ms);
            c.startService(i);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 重要但不占主动预算的提醒（例如深夜手机快没电）。
     * 绕过打扰预算与静默拦截，但静默模式下只出气泡、不朗读。
     */
    public static void sayImportant(Context c, String text) {
        send(c, A_SAY_IMPORTANT, "text", text);
    }

    /** 天气提醒：走正常主动策略，不再绕过静音/夜间/预算。 */
    public static void sayWeather(Context c, String text) {
        send(c, A_SAY_WEATHER, "text", text);
    }

    /** 天气或气象预警：进通知栏，并写进聊天记录，用户可以直接在聊天框回复。 */
    public static void weatherAlert(Context c, String text) {
        send(c, A_WEATHER_ALERT, "text", text);
    }

    /** 打开应用时的本地问候：只气泡，不朗读。 */
    public static void sayGreet(Context c, String text) {
        send(c, A_SAY_GREET, "text", text);
    }

    /** 原生插件主动开口：按主动说话的策略走（进历史、留通知、静音时闭嘴）。 */
    public static void sayPlugin(Context c, String text) {
        send(c, A_SAY_PLUGIN, "text", text);
    }

    /** Keep the display awake only while an explicitly enabled autonomous session is active. */
    static void setFreeActionScreenOn(Context context, boolean enabled) {
        PetSvc service = activeInstance;
        if (service == null) return;
        service.h.post(() -> service.updateFreeActionWakeLock(enabled));
    }

    private void updateFreeActionWakeLock(boolean enabled) {
        try {
            if (!enabled) {
                if (freeActionWakeLock != null && freeActionWakeLock.isHeld()) freeActionWakeLock.release();
                freeActionWakeLock = null;
                return;
            }
            if (freeActionWakeLock != null && freeActionWakeLock.isHeld()) return;
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm == null) return;
            // Keep the display on at dim brightness to avoid interrupting a session while reducing drain.
            freeActionWakeLock = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "DSHPet:FreeAction");
            freeActionWakeLock.setReferenceCounted(false);
            freeActionWakeLock.acquire(65 * 60 * 1000L);
        } catch (Throwable ignored) { }
    }

    /** Confirm remote commands in a non-focusable overlay so the target app stays foreground. */
    public static boolean requestRemoteActionApproval(JSONObject details, long timeoutMs) {
        PetSvc service = activeInstance;
        if (service == null || !running || service.wm == null || !Settings.canDrawOverlays(service)) return false;
        CompletableFuture<Boolean> answer = new CompletableFuture<>();
        service.h.post(() -> service.showRemoteActionPrompt(details, answer));
        try {
            return answer.get(Math.max(1000L, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (Throwable ignored) {
            return false;
        } finally {
            service.h.post(() -> service.finishRemoteActionPrompt(answer, false));
        }
    }

    private void showRemoteActionPrompt(JSONObject details, CompletableFuture<Boolean> answer) {
        if (answer == null || answer.isDone() || !running || wm == null || !Settings.canDrawOverlays(this)) {
            if (answer != null) answer.complete(false);
            return;
        }
        if (remoteActionAnswer != null) finishRemoteActionPrompt(remoteActionAnswer, false);
        float density = getResources().getDisplayMetrics().density;
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18, density), dp(14, density), dp(18, density), dp(14, density));
        GradientDrawable background = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xF5222632, 0xF5111420});
        background.setCornerRadius(dp(20, density));
        background.setStroke(dp(1, density), 0x665D65A5);
        card.setBackground(background);

        TextView title = new TextView(this);
        title.setText("AstrBot 请求手机操作");
        title.setTextColor(0xFFF0F2FF);
        title.setTextSize(16);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        card.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView action = new TextView(this);
        String actionTitle = details == null ? "请求手机操作" : details.optString("title", "请求手机操作");
        String description = details == null ? "请确认是否执行。" : details.optString("description", "请确认是否执行。");
          action.setText(actionTitle + "\n" + description + "\n每次操作都需要你确认；执行前会再次核对条件，超时自动取消。 ");
        action.setTextColor(0xFFCBD2E5);
        action.setTextSize(13);
        action.setLineSpacing(dp(2, density), 1f);
        action.setPadding(0, dp(8, density), 0, dp(12, density));
        card.addView(action, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        TextView reject = remoteActionButton("取消", false, density);
        TextView approve = remoteActionButton("允许这一步", true, density);
        LinearLayout.LayoutParams rejectLp = new LinearLayout.LayoutParams(0, dp(42, density), 1f);
        rejectLp.setMargins(0, 0, dp(7, density), 0);
        buttons.addView(reject, rejectLp);
        LinearLayout.LayoutParams approveLp = new LinearLayout.LayoutParams(0, dp(42, density), 1f);
        buttons.addView(approve, approveLp);
        card.addView(buttons, new LinearLayout.LayoutParams(-1, -2));

        remoteActionAnswer = answer;
        remoteActionPrompt = card;
        reject.setOnClickListener(v -> finishRemoteActionPrompt(answer, false));
        approve.setOnClickListener(v -> finishRemoteActionPrompt(answer, true));
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                Math.max(dp(1, density), Math.min(dp(340, density),
                        getResources().getDisplayMetrics().widthPixels - dp(24, density))),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.graphics.PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        params.y = dp(92, density);
        try {
            wm.addView(card, params);
        } catch (Throwable t) {
            remoteActionPrompt = null;
            remoteActionAnswer = null;
            answer.complete(false);
        }
    }

    private TextView remoteActionButton(String label, boolean primary, float density) {
        TextView button = new TextView(this);
        button.setText(label);
        button.setTextSize(14);
        button.setGravity(Gravity.CENTER);
        button.setTextColor(primary ? 0xFFFFFFFF : 0xFFD5D9E5);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14, density));
        bg.setColor(primary ? 0xFF7255D9 : 0x332F3546);
        if (!primary) bg.setStroke(dp(1, density), 0x557D8498);
        button.setBackground(bg);
        button.setClickable(true);
        button.setFocusable(false);
        return button;
    }

    private void finishRemoteActionPrompt(CompletableFuture<Boolean> answer, boolean approved) {
        if (remoteActionAnswer != answer) {
            if (answer != null) answer.complete(false);
            return;
        }
        View prompt = remoteActionPrompt;
        remoteActionPrompt = null;
        remoteActionAnswer = null;
        if (prompt != null && wm != null) {
            try { wm.removeView(prompt); } catch (Throwable ignored) { }
        }
        if (answer != null) answer.complete(approved);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        activeInstance = this;
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        cfg = Cfg.load(this);
        // 桌宠服务也会被单独拉起：签名不符就只保留前台通知，不出桌宠。
        // 只看签名（不看调试器），避免用户自己调试时桌宠消失。
        if (!Secure.signatureOk(this)) {
            QQOneBot.stop();
            channel();
            goForeground();
            h.post(this::stopAll);
            return;
        }
        QQOneBot.sync(this);
        AdRules.load(this);
        Bus.add(this);
        channel();
        goForeground();
        initSounds();
        A11y.reload();
        try {
            DexPlugins.sync(this);
        } catch (Throwable ignored) {
        }
        h.postDelayed(() -> CompanionSession.resume(PetSvc.this), 5000L);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        cfg = Cfg.load(this);
        if (Secure.signatureOk(this)) QQOneBot.sync(this);
        else QQOneBot.stop();
        String a = intent == null ? null : intent.getAction();
        if (a == null) a = A_START;
        // 保活：服务在跑就挂一个 15 分钟的看门狗；用户主动收起则撤掉闹钟
        if (A_STOP.equals(a)) {
            Alive.cancel(this);
            TickAlarm.cancel(this);
        } else if (cfg.b("keepAlive", true)) {
            Alive.schedule(this);
        }
        if (!A_STOP.equals(a)) TickAlarm.schedule(this);
        if (!A_STOP.equals(a) && !A_RELOAD.equals(a) && cfg.b("running", false) && pet == null
                && Settings.canDrawOverlays(this)) {
            alive = true;
            spawn();
            startTicker();
            running = true;
        }
        switch (a) {
            case A_STOP:
                stopAll();
                return START_NOT_STICKY;
            case A_RELOAD:
                // 热重载复用同一实例：这里显式复活，避免 teardown 后实例被误判为已停止
                alive = true;
                teardown(false);
                spawn();
                startTicker();
                break;
            case A_CONFIG:
                // 间隔、气泡时长、通知强弱等设置只更新 cfg；重建窗口会让桌宠跳位置
                cfg = Cfg.load(this);
                applyPetAttrs();
                notifyUpdate();
                break;
            case A_CHAT:
                showChatBox();
                break;
            case A_SKIN:
                if (pet != null) pet.applySkin();
                break;
            case A_LOCK: {
                boolean lock = intent != null && intent.getBooleanExtra("lock", !cfg.b("locked", false));
                setLockState(lock, true);
                break;
            }
            case A_HIDE:
                hiddenUser = false;
                edgeHidden = true;
                if (pet != null) pet.hideToEdge();
                notifyUpdate();
                Bus.emit("state", stateJson());
                break;
            case A_SHOW:
                hiddenUser = false;
                hiddenSleep = false;
                hiddenGame = false;
                edgeHidden = false;
                readingHidden = false;
                if (pet != null) pet.showFromEdge();
                applyVisibility();
                applyPetAttrs();
                notifyUpdate();
                Bus.emit("state", stateJson());
                break;
            case A_READING:
                readingHidden = intent != null && "1".equals(intent.getStringExtra("on"));
                applyVisibility();
                if (readingHidden && bubble != null) bubble.hideNow();
                notifyUpdate();
                Bus.emit("state", stateJson());
                break;
            case A_POMO_START:
                startPomo(intent == null ? 25 : intent.getIntExtra("work", 25),
                        intent == null ? 5 : intent.getIntExtra("rest", 5),
                        intent == null ? "" : intent.getStringExtra("taskId"),
                        intent == null ? "" : intent.getStringExtra("title"));
                break;
            case A_FEED:
                feed();
                break;
            case A_POMO_STOP:
                stopPomo();
                break;
            case A_SAY:
                if (intent != null) say(intent.getStringExtra("text"), 6500);
                break;
            case A_SAY_IMPORTANT:
                if (intent != null) {
                    String it = intent.getStringExtra("text");
                    sayForce(it, 8000);
                    if (canSpeakNow(cfg)) Voice.speak(this, it, true);
                }
                break;
            case A_SAY_WEATHER:
                if (intent != null) proactiveSay("weather", intent.getStringExtra("text"), 2, 7000);
                break;
            case A_WEATHER_ALERT:
                if (intent != null) weatherAlertNow(intent.getStringExtra("text"));
                break;
            case A_SAY_GREET:
                if (intent != null) proactiveSay("greet", intent.getStringExtra("text"), 1, 6000);
                break;
            case A_SAY_PLUGIN:
                if (intent != null) proactiveSay("chat", intent.getStringExtra("text"), 1, 6000);
                break;
            case A_PREVIEW:
                if (intent != null && intent.getStringExtra("slug") != null && pet != null) {
                    Interact.onPetEvent(this, "让你做动作：" + intent.getStringExtra("slug"));
                    pet.playAction(intent.getStringExtra("slug"));
                }
                break;
            case A_SCENE_ACTION:
                if (intent != null && intent.getStringExtra("slug") != null && pet != null
                        && !hidden() && !pet.isDragging() && !pet.isBusy()) {
                    String slug = intent.getStringExtra("slug");
                    Interact.onPetEvent(this, "按聊天情境播放动作：" + PetConf.load(this).nameOf(slug));
                    suppressBubble = true;
                    pet.playAction(slug);
                }
                break;
            case A_GROW:
                if (pet != null && intent != null) {
                    int dp = intent.getIntExtra("dp", cfg.sizeDp());
                    long ms = intent.getLongExtra("ms", 7000L);
                    pet.applyScale(Math.max(Cfg.MIN_SIZE, Math.min(Cfg.MAX_SIZE, dp)));
                    if (growRestore != null) h.removeCallbacks(growRestore);
                    growRestore = () -> {
                        if (pet != null) pet.applyScale(cfg.sizeDp());
                    };
                    h.postDelayed(growRestore, Math.max(1000L, ms));
                }
                break;
            case A_SIZE:
                if (pet != null) pet.applyScale(cfg.sizeDp());
                break;
            case A_OPACITY:
                applyPetAttrs();
                break;
            default:
                alive = true;
                running = true;
                cfg.set("running", true);
                cfg.save(this);
                if (pet == null) {
                    spawn();
                } else {
                    applyVisibility();
                    pet.refreshWindow();
                }
                startTicker();
                break;
        }
        goForeground();
        return START_STICKY;
    }

    private void teardown() {
        teardown(true);
    }

    private void teardown(boolean stopLocation) {
        if (remoteActionAnswer != null) finishRemoteActionPrompt(remoteActionAnswer, false);
        h.removeCallbacks(ticker);
        h.removeCallbacks(gameCheck);
        h.removeCallbacks(screenPoll);
        h.removeCallbacks(weatherTick);
        h.removeCallbacks(musicPoll);
        h.removeCallbacks(lyricTick);
        h.removeCallbacks(sleepCheck);
        h.removeCallbacks(proactive);
        h.removeCallbacks(minute);
        h.removeCallbacks(calMidnight);
        h.removeCallbacks(pomo);
        Battery.detach(this);
        if (stopLocation) Geo.stop(this);
        Voice.stopSpeak();
        MusicX.stopNoise();
        if (panel != null) panel.dismiss();
        if (clipPanel != null) clipPanel.dismiss();
        if (actionPanel != null) actionPanel.dismiss();
        if (chatBox != null) chatBox.dismiss();
        if (pomoRunning) {
            pomoRunning = false;
            pomoPhase = "idle";
            emitPomo();
        }
        if (bubble != null) bubble.hideNow();
        if (alertBubble != null) alertBubble.hideNow();
        alertBubble = null;
        dismissMenu();
        if (pet != null) {
            try {
                pet.release();
            } catch (Throwable ignored) {
            }
            if (wm != null) {
                try {
                    wm.removeView(pet.view());
                } catch (Throwable ignored) {
                }
            }
        }
        pet = null;
        lp = null;
    }

    private void stopAll() {
        boolean keepQqLink = cfg != null && cfg.b("qqLinkEnabled", false) && Secure.signatureOk(this);
        CompanionSession.onPetServiceStopped(this);
        updateFreeActionWakeLock(false);
        alive = keepQqLink;
        running = false;
        cfg.set("running", false);
        cfg.save(this);
        teardown();
        Geo.resetRequestThrottle(this);
        Bus.emit("state", stateJson());
        if (keepQqLink) {
            QQOneBot.sync(this);
            goForeground();
            return;
        }
        QQOneBot.stop();
        try {
            stopForeground(true);
        } catch (Throwable ignored) {
        }
        stopSelf();
    }

    private void spawn() {
        if (wm == null || !Settings.canDrawOverlays(this)) return;
        try {
            conf = PetConf.load(this);
            PetConf.Spec spec = conf.pets.get(0);
            pet = new Pet(this, conf, spec, this);
            pet.setLocked(cfg.b("locked", false));
            pet.applyScale(cfg.sizeDp());
            // FLAG_LAYOUT_NO_LIMITS：窗口尺寸放大后仍允许越过屏幕边界，
            // 否则系统会把窗口强行夹在屏幕内，桌宠越大越拖不到两侧
            lp = new WindowManager.LayoutParams(pet.windowW(), pet.heightPx(), 2038,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            | 0x10000000,
                    android.graphics.PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.LEFT;
            lp.x = pet.windowX();
            lp.y = (int) pet.y();
            applyPetAttrs();
            wm.addView(pet.view(), lp);
            pet.start();
            hiddenUser = false;
            edgeHidden = false;
            readingHidden = false;
            hiddenSleep = cfg.b("sleepEnabled", false) && inSleep();
            bubble = new Bubble(this);
            bubble.setTapAction(this::showChatBox);
            alertBubble = new Bubble(this);
            Battery.attach(this);
            applyVisibility();
            Data.dropOldReminders(this, 30);
            Notify.cleanupOldSigned(this);
            if (cfg.i("remMigrate", 0) < 1) {
                // 升级一次：清掉旧版由通知生成的待办，只留日历和我手动加的
                Data.keepOnlyOwn(this);
                cfg.set("remMigrate", 1);
                cfg.save(this);
            }
            Bus.emit("state", stateJson());
        } catch (Throwable t) {
            Sg.write(new java.io.File(Sg.dir(this), "diag.txt"), Sg.now() + " spawn " + t + "\n");
        }
    }

    private void startTicker() {
        h.removeCallbacks(ticker);
        h.post(ticker);
        h.removeCallbacks(gameCheck);
        h.postDelayed(gameCheck, 4000);
        h.removeCallbacks(screenPoll);
        h.postDelayed(screenPoll, 10000);
        h.removeCallbacks(weatherTick);
        h.postDelayed(weatherTick, 60000);
        h.removeCallbacks(musicPoll);
        h.postDelayed(musicPoll, 3000);
        h.removeCallbacks(lyricTick);
        h.postDelayed(lyricTick, 3000);
        h.removeCallbacks(sleepCheck);
        h.postDelayed(sleepCheck, 3000);
        h.removeCallbacks(minute);
        h.postDelayed(minute, 15000);
        h.removeCallbacks(calMidnight);
        scheduleCalMidnight();
        // 天气改成 3 小时一次的预警巡检（weatherTick），不再按状态变化随时插话
        // 主动链的启动入口也走自保路径，失败时同样有诊断记录
        safeScheduleProactive(0);
    }

    /**
     * 调度下一次主动检查。
     * 返回 false 表示消息没有成功入队（典型是 Looper 正在退出）——
     * Handler.postDelayed 的失败是通过返回值报告的，不会抛异常，所以调用方必须检查返回值。
     */
    private boolean scheduleProactive() {
        h.removeCallbacks(proactive);
        int min = Math.max(3, Math.min(120, cfg == null ? 20 : cfg.i("proactiveMin", 20)));
        // 用户刚聊过：无论这次检查是正常结束还是早退，下一次都至少等 30 分钟
        if (Interact.isUserTalking(this)) min = Math.max(30, min);
        if (cfg != null) {
            if (cfg.b("polQuiet", false)) min = Math.max(30, min);
            String mode = Policy.mode(this, cfg);
            if ("night".equals(mode)) {
                // 夜间不再每 20 分钟醒来一次，直接睡到夜间结束
                int left = Policy.nightRemainingMin(cfg);
                if (left > 0) min = Math.max(min, Math.min(480, left + 5));
            } else if ("game".equals(mode) || "focus".equals(mode)) {
                min = Math.max(30, min);
            }
        }
        // 看视频不额外轮询，只把下一次检查拉长；退出后再按正常节奏评估
        if (Scene.video(this)) min = Math.max(30, min);
        return h.postDelayed(proactive, min * 60000L);
    }

    /**
     * 主动调度链的自保（R1）：scheduleProactive 失败时不能让调度链无声断裂。
     * 三种失败都会落 diag 日志并区分原因：抛异常 / 入队返回 false / 重试投递失败；
     * 重试按 30s / 60s / 120s 退避，最多 3 次，间隔递增，避免无限快速重试与主线程忙循环；
     * 每次重试仍走 removeCallbacks + postDelayed，不会重复注册。
     */
    private void safeScheduleProactive(int attempt) {
        // 实例已停止：已经排队的退避重试即使无法被取消，到期后也不能再为旧实例安排主动互动
        if (!alive) return;
        boolean ok = false;
        Throwable err = null;
        try {
            ok = scheduleProactive();
        } catch (Throwable t) {
            err = t;
        }
        if (ok) return;
        // 区分「抛异常」与「入队返回 false」两种失败原因
        diagLog("proactive.schedule attempt=" + attempt
                + (err == null ? " queued=false" : " " + err));
        if (attempt >= 3) {
            // 有意设定的恢复上限：三次退避重试仍失败则放弃，不用无限重试掩盖 Looper / 生命周期故障
            diagLog("proactive.give-up retries=3");
            return;
        }
        boolean posted = false;
        try {
            posted = h.postDelayed(() -> safeScheduleProactive(attempt + 1), 30000L * (1L << attempt));
        } catch (Throwable t2) {
            diagLog("proactive.retry attempt=" + attempt + " " + t2);
            return;
        }
        if (!posted) diagLog("proactive.retry attempt=" + attempt + " queued=false");
    }

    /** 追加一条诊断日志到 diag.txt，只保留最近约 32KB；写日志本身失败时不再抛，避免二次异常。 */
    private void diagLog(String msg) {
        try {
            java.io.File f = new java.io.File(Sg.dir(this), "diag.txt");
            String old = Sg.read(f, "");
            if (old.length() > 32768) old = old.substring(old.length() - 16384);
            Sg.write(f, old + Sg.now() + " " + msg + "\n");
        } catch (Throwable ignored) {
        }
    }

    private boolean hidden() {
        return hiddenUser || hiddenGame || hiddenSleep || readingHidden || pet == null;
    }

    /** Explicit free action may announce over an auto-hidden game, but not over manual/sleep hides. */
    private boolean canAnnounceFreeActionWhileGameHidden(boolean due) {
        return due && hiddenGame && !hiddenUser && !hiddenSleep && !readingHidden
                && pet != null && bubble != null;
    }

    /** 锁定时桌宠半透明且完全穿透触摸；解锁由应用内控制台同步处理。 */
    private void applyPetAttrs() {
        if (lp == null || wm == null || pet == null || cfg == null) return;
        boolean lock = cfg.b("locked", false);
        float a = lock ? 0.49f : cfg.opacity();
        lp.alpha = a;
        if (lock || a < 0.5f) lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        else lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        try {
            if (pet.view().getParent() != null) wm.updateViewLayout(pet.view(), lp);
        } catch (Throwable ignored) {
        }
    }

    private void applyVisibility() {
        if (pet == null || wm == null || lp == null) return;
        boolean show = !hidden();
        boolean attached = pet.view().getParent() != null;
        try {
            if (show && !attached) {
                wm.addView(pet.view(), lp);
                pet.resumePeek();
            } else if (!show && attached) {
                wm.removeView(pet.view());
                pet.pausePeek();
            }
        } catch (Throwable ignored) {
        }
        if (!show && bubble != null) bubble.hideNow();
        if (!show && alertBubble != null) alertBubble.hideNow();
        if (!show) dismissMenu();
    }

    private boolean inSleep() {
        try {
            String a = cfg.str("sleepStart", "23:30");
            String b = cfg.str("sleepEnd", "07:00");
            int now = minutesNow();
            int s = parse(a), e = parse(b);
            if (s == e) return false;
            if (s < e) return now >= s && now < e;
            return now >= s || now < e;
        } catch (Throwable t) {
            return false;
        }
    }

    private int minutesNow() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        return c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE);
    }

    private int parse(String s) {
        try {
            String[] p = s.split(":");
            return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
        } catch (Throwable t) {
            return 0;
        }
    }

    private boolean say(String text, int ms) {
        if (Policy.isQuiet(this)) return false;
        return sayForce(text, ms);
    }

    private boolean sayForce(String text, int ms) {
        // 表情标记由 Bubble 解析成真实图片；文字气泡和语音仍只使用可见正文。
        String stickerName = Sticker.usableNameIn(text, this);
        String visible = Sticker.strip(text);
        if ((visible == null || visible.trim().isEmpty()) && stickerName.isEmpty()) return false;
        if (pet == null || bubble == null || hidden()) return false;
        lastBubble = System.currentTimeMillis();
        bubble.show(text, (int) pet.x(), (int) pet.y(), pet.sizePx(), pet.heightPx(), ms,
                alertBubble != null && alertBubble.placedAbove());
        return true;
    }

    /** 长按菜单里的余额：文本、视觉各一条气泡，先后播报；地址相同就只播一条。 */
    private void queryBalances() {
        // 两个模型指向同一个余额地址：只查一次、只显示一条
        if (Ai.sameBalanceTarget(this)) {
            Ai.balance(this, new Ai.Cb() {
                @Override
                public void ok(String text) {
                    emitBalance("balance", text);
                    emitBalance("balanceVision", "");
                    final String line = balOnly(text, null);
                    h.post(() -> {
                        if (alive) sayForce(line, 5000);
                    });
                }

                @Override
                public void fail(String err) {
                    final String line = balOnly(null, err);
                    h.post(() -> {
                        if (alive) sayForce(line, 5000);
                    });
                }
            });
            return;
        }
        final String[] txt = new String[1];
        final String[] vis = new String[1];
        final String[] tErr = new String[1];
        final String[] vErr = new String[1];
        final boolean[] done = new boolean[2];
        final java.util.concurrent.atomic.AtomicBoolean once = new java.util.concurrent.atomic.AtomicBoolean();

        Ai.balance(this, new Ai.Cb() {
            @Override
            public void ok(String text) {
                txt[0] = text;
                done[0] = true;
                emitBalance("balance", text);
                showBalances(txt, vis, tErr, vErr, done, once);
            }

            @Override
            public void fail(String err) {
                tErr[0] = err;
                done[0] = true;
                showBalances(txt, vis, tErr, vErr, done, once);
            }
        });
        Ai.balanceVision(this, new Ai.Cb() {
            @Override
            public void ok(String text) {
                vis[0] = text;
                done[1] = true;
                emitBalance("balanceVision", text);
                showBalances(txt, vis, tErr, vErr, done, once);
            }

            @Override
            public void fail(String err) {
                vErr[0] = err;
                done[1] = true;
                showBalances(txt, vis, tErr, vErr, done, once);
            }
        });
    }

    private void showBalances(String[] txt, String[] vis, String[] tErr, String[] vErr,
                              boolean[] done, java.util.concurrent.atomic.AtomicBoolean once) {
        if (!done[0] || !done[1]) return;
        if (!once.compareAndSet(false, true)) return;
        final String t = balLine("文本", txt[0], tErr[0]);
        final String v = balLine("视觉", vis[0], vErr[0]);
        h.post(() -> {
            sayForce(t, 4500);
            h.postDelayed(() -> {
                if (alive) sayForce(v, 4500);
            }, 4900);
        });
    }

    private static String balLine(String who, String text, String err) {
        if (err != null && !err.isEmpty()) return who + "余额：查询失败，" + err;
        if (text == null || text.trim().isEmpty()) return who + "余额：--";
        String body = text.startsWith("余额") ? text.substring(2).trim() : text;
        return who + "余额：" + body;
    }

    /** 两个模型共用同一余额地址时的一句话，不带「文本/视觉」前缀。 */
    private static String balOnly(String text, String err) {
        if (err != null && !err.isEmpty()) return "余额：查询失败，" + err;
        if (text == null || text.trim().isEmpty()) return "余额：--";
        String body = text.startsWith("余额") ? text.substring(2).trim() : text;
        return "余额：" + body;
    }

    private void emitBalance(String name, String text) {
        try {
            JSONObject o = new JSONObject();
            o.put("text", text);
            Bus.emit(name, o);
        } catch (Throwable ignored) {
        }
    }

    /** 快递/外卖/未读消息的实时播报：独立气泡，默认常驻，不挤掉普通说话。 */
    private void showRealtimeAlert(String type, String text, String tag, String pkg) {
        if (text == null || text.trim().isEmpty() || pet == null || alertBubble == null || hidden()) return;
        // 夜间休息时不弹、不响
        if (cfg != null && Policy.nightNow(cfg)) return;
        // 已经在该应用里，消息类不再弹气泡
        if ("msg".equals(type) && pkg != null && !pkg.isEmpty() && pkg.equals(Bus.currentPackage)) return;
        long now = System.currentTimeMillis();
        long gap = type.equals(lastRealtimeType) ? 6000L : 2500L;
        if (now - lastRealtimeBubble < gap) return;
        lastRealtimeBubble = now;
        lastRealtimeType = type;
        alertBubble.showAlert(type, tag, text, pkg,
                (int) pet.x(), (int) pet.y(), pet.sizePx(), pet.heightPx(),
                bubble != null && bubble.isShowing());
        if (cfg.b("realtimeSpeak", true) && canSpeakNow(cfg)) {
            Voice.speak(this, text, true);
        }
    }

    /**
     * 外卖/快递/叫车/出行：通知栏留一条，并写进聊天记录。
     * 用户点通知打开聊天页，看到的正是这句话，可以直接回复；夜间休息不打扰。
     */
    private void lifeNotify(String text) {
        if (text == null || text.trim().isEmpty()) return;
        if (cfg != null && Policy.nightNow(cfg)) return;
        final String t = text.trim();
        try {
            postSay(t);
            JSONObject extra = new JSONObject();
            extra.put("source", "life");
            Data.chatAppend(this, "assistant", t, "", "", extra);
        } catch (Throwable ignored) {
        }
    }

    /** 当前是否允许朗读：静音、夜间、专注、视频时只留气泡。 */
    private boolean canSpeakNow(Cfg c) {
        if (c == null) c = cfg;
        if (c == null) return false;
        if (Policy.isQuiet(this)) return false;
        if (Policy.nightNow(c)) return false;
        if (Policy.focusRunning()) return false;
        return !Scene.video(this);
    }

    private void hum() {
        if (pet == null || hidden() || pet.isDragging() || pet.isBusy()) return;
        String slug = cfg.str("humAction", "a088");
        suppressBubble = true;
        pet.playAction(slug);
    }

    /* ---------------- Pet.Host ---------------- */
    @Override
    public void onWindow(int x, int y, int w, int h) {
        if (wm == null || lp == null || pet == null) return;
        lp.x = x;
        lp.y = y;
        lp.width = w;
        lp.height = h;
        try {
            if (pet.view().getParent() != null) wm.updateViewLayout(pet.view(), lp);
        } catch (Throwable ignored) {
        }
        if (bubble != null && bubble.isShowing()) bubble.reposition(x, y, w, h);
        if (alertBubble != null && alertBubble.isShowing()) alertBubble.reposition(x, y, w, h);
    }

    @Override
    public void onAction(String slug, String name) {
        if (suppressBubble) {
            suppressBubble = false;
            return;
        }
        new Thread(() -> Data.bump(this, "actions", 1), "dsh-stat").start();
        Interact.onPetEvent(this, "让你做动作：" + (name == null || name.isEmpty() ? slug : name));
        // 单击回应由 onTap 单独生成，避免又按动作台词重复说一遍。
        if (System.currentTimeMillis() - lastTapAt < 2500L) return;
        String local = Plugins.actionLine(this, slug);
        if (local.isEmpty()) local = Data.actionLine(this, slug);
        boolean forceAI = Data.actionAI(this, slug);
        if (!forceAI && !local.isEmpty()) {
            sayActionLine(local);
            return;
        }
        if (forceAI || cfg.b("aiActionLines", true)) {
            long now = System.currentTimeMillis();
            if (now - lastAiLine < Math.max(15, cfg.i("aiCoolSec", 40)) * 1000L) {
                if (!local.isEmpty()) sayActionLine(local);
                return;
            }
            lastAiLine = now;
            final String fallback = local;
            Brain.actionLine(this, slug, name, new Ai.Cb() {
                @Override
                public void ok(String text) {
                    h.post(() -> sayActionLine(text));
                }

                @Override
                public void fail(String err) {
                    if (!fallback.isEmpty()) h.post(() -> sayActionLine(fallback));
                }
            });
        } else if (!local.isEmpty()) {
            sayActionLine(local);
        }
    }

    /** 动作台词统一冷却，最短 10 秒一句；动作本身照常播放。 */
    private void sayActionLine(String text) {
        if (text == null || text.trim().isEmpty() || cfg == null) return;
        long now = System.currentTimeMillis();
        int cool = Math.max(10, cfg.i("actionCoolSec", 10));
        if (now - lastActionLine < cool * 1000L) return;
        // 动作碎碎念也进主动拦截：静音、夜间、连续未回应等场景不说；单独每小时 6 条
        Policy.Decision d = Policy.decide(this, "action", 1);
        if (!d.ok) {
            Policy.recordBlocked(this, "action", d.reason, text);
            return;
        }
        if (say(text, cfg.i("bubbleSec", 6) * 1000)) {
            lastActionLine = now;
            Policy.record(this, "action", d.level, d.reason, text);
        }
    }

    @Override
    public void onPosition(double x, double y, int facing) {
        if (cfg == null) return;
        cfg.set("posX", (int) x);
        cfg.set("posY", (int) y);
        cfg.set("facing", facing);
        long now = System.currentTimeMillis();
        if (now - lastPosSave > 2000) {
            lastPosSave = now;
            cfg.save(this);
        }
    }

    @Override
    public void onTap() {
        // 点桌宠也算一次互动：主动闲聊先让路，未回应计数清零
        lastTapAt = System.currentTimeMillis();
        playDuck();
        Interact.onEngage(this);
        Interact.onPetEvent(this, "戳了戳你");
        long tapNow = System.currentTimeMillis();
        if (tapNow - lastTapRequestAt >= 10000L) {
            lastTapRequestAt = tapNow;
            Brain.tapLine(this, new Ai.Cb() {
                @Override
                public void ok(String text) {
                    h.post(() -> sayActionLine(text));
                }

                @Override
                public void fail(String err) {
                    // 网络不可用时不编造或反复提示，点击动作仍照常播放。
                }
            });
        }
        new Thread(() -> {
            Data.bump(this, "interactions", 1);
            long now = System.currentTimeMillis();
            if (now - lastAffTap > 30000) {
                lastAffTap = now;
                Data.addAffection(this, 1);
            }
            final JSONObject m = Data.mood(this);
            h.post(() -> Bus.emit("mood", m));
        }, "dsh-stat").start();
    }

    @Override
    public void onLongPress() {
        Interact.onPetEvent(this, "长按你，打开了菜单");
        showPanel();
    }

    @Override
    public void onDoubleTap() {
        Interact.onPetEvent(this, "双击了你");
        if (CompanionSession.stopByDoubleTap(this)) {
            if (pet != null && !hidden() && !pet.isBusy()) pet.playAction("a007");
            return;
        }
        // 正在放音乐时双击 = 切歌；没放音乐才打开动作面板
        if (Music.playing()) {
            boolean ok = MusicX.next(this);
            say(ok ? "切歌啦~" : "没找到能控制的音乐哦", 3200);
            if (pet != null && !hidden() && !pet.isBusy()) pet.playAction("a007");
            return;
        }
        showActionPanel();
    }

    @Override
    public void onDragEnd() {
        Interact.onPetEvent(this, "把你拖到了别处");
    }

    /** 拖动开始时立刻跑一帧，别等空闲的 500ms 循环。 */
    @Override
    public void wake() {
        h.removeCallbacks(ticker);
        h.post(ticker);
    }

    private void showActionPanel() {
        if (pet == null || wm == null) return;
        dismissMenu();
        if (panel != null) panel.dismiss();
        try {
            if (conf == null) conf = PetConf.load(this);
        } catch (Throwable ignored) {
        }
        if (conf == null) return;
        if (actionPanel == null) {
            actionPanel = new ActionPanel(this, wm, conf, slug -> {
                if ("__reader".equals(slug)) {
                    openPage("readerLast");
                    return;
                }
                if (pet != null) {
                    Interact.onPetEvent(this, "让你做动作：" + slug);
                    pet.playAction(slug);
                }
            });
        }
        actionPanel.show((int) pet.x(), (int) pet.y(), pet.sizePx(), pet.heightPx());
    }

    private void showPanel() {
        if (pet == null || wm == null) return;
        dismissMenu();
        if (panel == null) panel = new Panel(this, wm, this::panelAct, new Panel.Tune() {
            @Override
            public void onSize(int v, boolean commit) {
                if (cfg == null) return;
                cfg.set("sizeDp", v);
                if (pet != null) pet.applyScale(cfg.sizeDp());
                if (commit) cfg.save(PetSvc.this);
            }

            @Override
            public void onOpacity(int pct, boolean commit) {
                if (cfg == null) return;
                cfg.set("opacity", pct / 100.0);
                applyPetAttrs();
                if (commit) cfg.save(PetSvc.this);
            }
        });
        panel.show((int) pet.x(), (int) pet.y(), pet.sizePx(), pet.heightPx());
    }

    private void showChatBox() {
        if (pet == null || wm == null) return;
        final float dens = getResources().getDisplayMetrics().density;
        if (chatBox == null) {
            chatBox = new ChatBox(this, wm, new ChatBox.Host() {
                @Override
                public void onClosed() {
                    if (pet != null) {
                        pet.setChatOpen(false);
                        pet.moveTo(chatSavedX, chatSavedY);
                    }
                }

                @Override
                public void onLayout(int left, int top, int right, int bottom) {
                    if (pet != null) pet.placeAtCorner(left, bottom + dp(6, dens));
                }

                @Override
                public void setPetVisible(boolean visible) {
                    if (pet != null) {
                        pet.view().setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
                    }
                }

                @Override
                public void pickImage() {
                    if (chatBox == null) return;
                    // 先把系统的相册选择器拉起来，再把悬浮窗藏起来：
                    // 先把窗口隐藏会被系统判定成「后台启动 Activity」而直接拦掉，表现就是点了没反应。
                    final boolean[] done = {false};
                    PickAct.open(PetSvc.this, bmp -> {
                        done[0] = true;
                        if (chatBox != null) {
                            chatBox.suspendForPick(false);
                            if (bmp != null) chatBox.setPendingImage(bmp, "图片已插入");
                        }
                        if (pet != null) pet.view().setVisibility(View.VISIBLE);
                    });
                    h.postDelayed(() -> {
                        if (done[0]) return;
                        if (chatBox != null) chatBox.suspendForPick(true);
                        if (pet != null) pet.view().setVisibility(View.INVISIBLE);
                    }, 260);
                }

                @Override
                public void onReply(String text) {
                    // 回复已经在悬浮聊天框里显示了，桌宠不再重复冒气泡
                }
            });
        }
        if (!chatBox.isOpen()) {
            chatSavedX = pet.x();
            chatSavedY = pet.y();
        }
        pet.setChatOpen(true);
        chatBox.show();
        // 悬浮聊天框已经打开，主动消息通知就不用在通知栏留着了
        clearSayNotif(this);
    }

    /** Temporarily hide the floating chat while plugins inspect or operate the app underneath it. */
    static boolean setChatOverlayVisibleForObservation(boolean visible) {
        PetSvc s = activeInstance;
        if (s == null || Looper.myLooper() != Looper.getMainLooper()) return false;
        if (s.chatBox == null || !s.chatBox.isOpen()) return false;
        s.chatBox.suspendForPick(!visible);
        if (s.pet != null) {
            if (!visible) s.pet.view().setVisibility(View.INVISIBLE);
            else if (!s.hidden()) s.pet.view().setVisibility(View.VISIBLE);
        }
        return true;
    }

    /** Session observations use only a transient bubble, never a spontaneous chat message. */
    static void showSessionInsight(Context c, String text) {
        PetSvc s = activeInstance;
        if (s == null || text == null || text.trim().isEmpty()) return;
        s.h.post(() -> {
            if (!s.alive || !CompanionSession.isRunning(s) || s.hidden() || s.pet == null || s.bubble == null) return;
            s.lastBubble = System.currentTimeMillis();
            s.bubble.show(text.trim(), (int) s.pet.x(), (int) s.pet.y(), s.pet.sizePx(),
                    s.pet.heightPx(), 7000, s.alertBubble != null && s.alertBubble.placedAbove());
        });
    }

    private void panelAct(String id) {
        switch (id) {
            case "chat":
                showChatBox();
                break;
            case "balance":
                queryBalances();
                break;
            case "quiet":
                notifyUpdate();
                break;
            case "lock":
                toggleLock();
                break;
            case "feed":
                feed();
                break;
            case "clip":
                if (pet != null && wm != null) {
                    if (clipPanel == null) clipPanel = new ClipPanel(this, wm);
                    clipPanel.show((int) pet.x(), (int) pet.y(), pet.sizePx(), pet.heightPx());
                }
                break;
            case "hide":
                if (pet != null) pet.hideToEdge();
                break;
            case "console":
                openPage("home");
                break;
            default:
                break;
        }
    }

    private void toggleLock() {
        boolean lock = !cfg.b("locked", false);
        setLockState(lock, true);
    }

    private void setLockState(boolean lock, boolean speak) {
        cfg.set("locked", lock);
        cfg.save(this);
        if (pet != null) pet.setLocked(lock);
        applyPetAttrs();
        notifyUpdate();
        Bus.emit("state", stateJson());
        if (speak) say(lock ? "好，我不乱跑啦。" : "又可以拉着我跑啦。", 3200);
    }

    private void startPomo(int work, int rest, String taskId, String title) {
        pomoWork = Math.max(1, Math.min(180, work));
        pomoRest = Math.max(1, Math.min(60, rest));
        pomoRunning = true;
        pomoPhase = "work";
        pomoEndAt = System.currentTimeMillis() + pomoWork * 60000L;
        h.removeCallbacks(pomo);
        h.postDelayed(pomo, pomoWork * 60000L);
        Taskbook.startFocus(this, taskId, title == null ? "" : title, pomoWork);
        proactiveSay("focus", "开始专注 " + pomoWork + " 分钟，我陪着你。", 2, 5000);
        emitPomo();
    }

    private void stopPomo() {
        pomoRunning = false;
        pomoPhase = "idle";
        pomoEndAt = 0;
        h.removeCallbacks(pomo);
        Taskbook.finishFocus(this, false);
        say("番茄钟停啦，随时可以再开始。", 4200);
        emitPomo();
    }

    private void emitPomo() {
        JSONObject o = new JSONObject();
        try {
            o.put("running", pomoRunning);
            o.put("phase", pomoPhase);
            o.put("work", pomoWork);
            o.put("rest", pomoRest);
            o.put("endAt", pomoEndAt);
            o.put("remaining", pomoRunning ? Math.max(0, pomoEndAt - System.currentTimeMillis()) : 0);
        } catch (Throwable ignored) {
        }
        pomoState = o;
        notifExtra = pomoRunning ? ("番茄钟 · " + ("work".equals(pomoPhase) ? "专注中" : "休息中")) : "";
        notifyUpdate();
        Bus.emit("pomo", o);
    }

    /* ---------------- Bus ---------------- */
    @Override
    public void on(String event, JSONObject data) {
        switch (event) {
            case "userTalk":
                // 用户开始聊天：作废在途的主动生成，并把下一次主动检查推到 30 分钟后
                h.post(() -> {
                    proactiveToken++;
                    proactiveBusy = false;
                    proactiveBusyAt = 0;
                    if (cfg == null) return;
                    int min = Math.max(30, Math.min(120, cfg.i("proactiveMin", 20)));
                    h.removeCallbacks(proactive);
                    if (!h.postDelayed(proactive, min * 60000L)) {
                        diagLog("proactive.userTalk queued=false");
                    }
                });
                break;
            case "ad_skipped":
                h.post(() -> {
                    if (cfg.b("adShowLine", true)) proactiveSay("ad", Data.randomAdLine(this), 1, 5000);
                    if (pet != null && !hidden() && !pet.isBusy()) {
                        suppressBubble = true;
                        pet.playAction("a036");
                    }
                });
                break;
            case "diaryPeek":
                // 用户打开关系页看日记时，如果桌宠在场就直接给一句害羞/生气的气泡
                h.post(() -> {
                    if (pet == null || hidden()) return;
                    String[] lines = {
                            "喂……你怎么偷看我的日记。",
                            "别、别看了，我还没写完……",
                            "你看到哪一页了？快合上啦。"
                    };
                    sayForce(lines[(int) (Math.random() * lines.length)], 5200);
                });
                break;
            case "music": {
                String t = data.optString("title");
                String a = data.optString("artist");
                String key = t + "|" + a;
                if (!t.isEmpty() && data.optBoolean("playing") && cfg.b("musicAware", true) && !key.equals(lastMusicKey)) {
                    lastMusicKey = key;
                    MusicX.note(this, t, a, true);
                    // 换歌时让 AI 认识这首歌：歌名/歌手 + 背景资料进缓存，之后聊天能接着聊
                    startSongAi(t, a);
                    // 不再主动报歌名：歌词由 lyricTick 按「歌词换句间隔」从通知栏/曲库读取，
                    // 这样不会再出现“分不清是歌名还是歌词”的两套说法
                }
                break;
            }
            case "reminders":
                if (data.optBoolean("silent", false)) break;
                if (cfg.b("remindersEnabled", true)) {
                    h.postDelayed(() -> {
                        long now = System.currentTimeMillis();
                        if (now - lastReminderShow < 60000) return;
                        org.json.JSONArray a = Data.reminders(this);
                        JSONObject o = null;
                        long bestTs = 0;
                        for (int k = 0; k < a.length(); k++) {
                            JSONObject x = a.optJSONObject(k);
                            if (x == null) continue;
                            String key = x.optString("key", "");
                            // 主动提醒只针对我手动加的提醒；日历走每日限额那套
                            if (!key.startsWith("manual:") && !key.startsWith("nlu:")) continue;
                            long ts = x.optLong("ts", 0);
                            if (o == null || ts > bestTs) {
                                o = x;
                                bestTs = ts;
                            }
                        }
                        if (o == null) return;
                        lastReminderShow = now;
                        String title = o.optString("title", "");
                        proactiveSay("reminder", "记下啦：" + title + "，到点我会提醒你。", 3, 6000);
                        notifExtra = "提醒：" + title;
                        notifyUpdate();
                    }, 900);
                }
                break;
            case "realtime":
                if (cfg.b("realtimeEnabled", true)) {
                    String rtype = data.optString("type", "msg");
                    if ("weather".equals(rtype)) {
                        // 天气来自通知栏的下雨预报，走主动说话，不用常驻提醒气泡
                        proactiveSay("weather", data.optString("text", ""), 2, 7000);
                    } else {
                        String rtext = data.optString("text", "");
                        showRealtimeAlert(rtype, rtext, data.optString("tag", ""),
                                data.optString("pkg", ""));
                        if ("food".equals(rtype) || "parcel".equals(rtype)
                                || "parcel_done".equals(rtype) || "ride".equals(rtype)
                                || "travel".equals(rtype)) {
                            lifeNotify(rtext);
                        }
                    }
                }
                break;
            case "app":
            case "realtimeCancel":
                if (alertBubble != null) alertBubble.dismissForApp(data.optString("pkg", ""));
                break;
            case "geo": {
                long now = System.currentTimeMillis();
                if (now - lastGeoLine > 300000L) {
                    lastGeoLine = now;
                    String id = data.optString("id", data.optString("place", ""));
                    String name = data.optString("name", "地点").trim();
                    if (!name.isEmpty()) {
                        Mem.add(this, "用户到达了自己设置的位置“" + name + "”。", "event", null, "auto", 0.7);
                        Care.geoArrival(this, name, new Ai.Cb() {
                            @Override public void ok(String text) { proactiveSay("geo", text, 2, 6200); }
                            @Override public void fail(String err) { proactiveSay("geo", "到" + name + "啦？今天怎么样呀？", 2, 6200); }
                        });
                    }
                }
                break;
            }
            case "geoMove": {
                // 显著位置变化事件仅在至少间隔 1 小时且移动超过 1 公里后触发。
                double km = data.optDouble("km", 0);
                if (km >= 10) {
                    String place = data.optString("place", "");
                    String line;
                    if ("home".equals(place)) line = "你走了 " + Math.round(km) + " 公里，到家啦？";
                    else if ("work".equals(place)) line = "你走了 " + Math.round(km) + " 公里，到公司啦？";
                    else line = "你一下子走了 " + Math.round(km) + " 公里，到哪儿啦？";
                    proactiveSay("geo", line, 2, 7000);
                }
                break;
            }
            case "notif":
                Bus.unreadCount = NotifyStore.unread(this);
                break;
            case "quiet":
                notifyUpdate();
                break;
            case "a11y":
            case "listener":
                A11y.reload();
                break;
            default:
                break;
        }
    }

    /* ---------------- 长按菜单 ---------------- */
    private void showMenu() {
        if (pet == null || wm == null) return;
        dismissMenu();
        float d = getResources().getDisplayMetrics().density;
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0x99000000);
        root.setOnClickListener(v -> dismissMenu());
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18, d), dp(14, d), dp(18, d), dp(14, d));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(22, d));
        bg.setColor(0xF0121622);
        bg.setStroke(dp(1, d), 0x33FFFFFF);
        card.setBackground(bg);
        card.setOnClickListener(v -> {
        });
        TextView title = new TextView(this);
        title.setText(conf == null ? "桌宠" : conf.pets.get(0).name);
        title.setTextColor(0xFFEDF2FF);
        title.setTextSize(16);
        title.setPadding(dp(6, d), dp(2, d), dp(6, d), dp(10, d));
        card.addView(title);
        row(card, "喂食", () -> {
            dismissMenu();
            feed();
        });
        row(card, "聊天", () -> {
            dismissMenu();
            openPage("chat");
        });
        row(card, "动作库", () -> {
            dismissMenu();
            openPage("actions");
        });
        row(card, cfg.b("locked", false) ? "解除锁定" : "锁定位置", () -> {
            dismissMenu();
            boolean lock = !cfg.b("locked", false);
            setLockState(lock, true);
        });
        row(card, "回到初始位置", () -> {
            dismissMenu();
            if (pet != null) pet.resetPosition();
        });
        row(card, "打开控制台", () -> {
            dismissMenu();
            openPage("home");
        });
        row(card, "收起来", () -> {
            dismissMenu();
            stopAll();
        });
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        cp.setMargins(dp(14, d), 0, dp(14, d), dp(24, d));
        root.addView(card, cp);
        WindowManager.LayoutParams wp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, 2038,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.graphics.PixelFormat.TRANSLUCENT);
        wp.gravity = Gravity.TOP | Gravity.LEFT;
        try {
            wm.addView(root, wp);
            menu = root;
        } catch (Throwable ignored) {
        }
    }

    private void row(LinearLayout card, String text, Runnable r) {
        float d = getResources().getDisplayMetrics().density;
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(0xFFD5DEF0);
        tv.setTextSize(15);
        tv.setPadding(dp(8, d), dp(12, d), dp(8, d), dp(12, d));
        tv.setClickable(true);
        tv.setOnClickListener(v -> r.run());
        card.addView(tv);
    }

    private void feed() {
        Data.feed(this);
        Interact.onPetEvent(this, "喂你吃了东西");
        Bus.emit("mood", Data.mood(this));
        if (pet != null && !pet.isBusy()) {
            suppressBubble = true;
            pet.playAction("a010");
        }
        // 说什么按她此刻的心情和关系现编；AI 不可用才退回固定台词
        Cfg cfgNow = Cfg.load(this);
        if (cfgNow != null && cfgNow.aiReady()) {
            Brain.feedLine(this, new Ai.Cb() {
                @Override
                public void ok(String text) {
                    if (text == null || text.trim().isEmpty()) {
                        h.post(() -> say("谢谢主人，好吃！", 4200));
                        return;
                    }
                    String line = text;
                    h.post(() -> say(line, 4200));
                }

                @Override
                public void fail(String err) {
                    h.post(() -> say("谢谢主人，好吃！", 4200));
                }
            });
        } else {
            say("谢谢主人，好吃！", 4200);
        }
    }

    private void dismissMenu() {
        if (menu == null) return;
        try {
            wm.removeView(menu);
        } catch (Throwable ignored) {
        }
        menu = null;
    }

    private void openPage(String page) {
        try {
            Intent i = new Intent(this, MainAct.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            i.putExtra("page", page);
            startActivity(i);
        } catch (Throwable ignored) {
        }
    }

    private static int dp(float v, float d) {
        return (int) (v * d + 0.5f);
    }

    /* ---------------- 通知 ---------------- */
    private void channel() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (nm.getNotificationChannel(CH) == null) {
            NotificationChannel c = new NotificationChannel(CH, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW);
            c.setShowBadge(false);
            nm.createNotificationChannel(c);
        }
        if (nm.getNotificationChannel(CH_MSG) == null) {
            NotificationChannel c = new NotificationChannel(CH_MSG, "她主动发的消息",
                    NotificationManager.IMPORTANCE_DEFAULT);
            c.setShowBadge(true);
            c.setDescription("她主动找你说话时，在这里留一条消息；不想收可以去系统设置里关掉这个通道");
            nm.createNotificationChannel(c);
        }
        // 旧版本的「静默通知」通道已废弃，顺手删掉，别在系统设置里留一条没用的
        try {
            nm.deleteNotificationChannel("dsh_pet_weak");
        } catch (Throwable ignored) {
        }
    }

    /** 主动说的话同步发一条通知：没盯着桌宠时，也能在通知栏看到她说了什么。 */
    private void postSay(String text) {
        if (text == null || text.trim().isEmpty()) return;
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) return;
            channel();
            Intent i = new Intent(this, MainAct.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("page", "chat");
            PendingIntent open = PendingIntent.getActivity(this, 21, i, 201326592);
            String name = Data.persona(this).optString("name", "她");
            Notification n = new Notification.Builder(this, CH_MSG)
                    .setContentTitle(name)
                    .setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setSmallIcon(R.drawable.ic_launcher)
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .setShowWhen(true)
                    .setLocalOnly(true)
                    .setVisibility(Notification.VISIBILITY_PRIVATE)
                    .build();
            nm.notify(NID_MSG, n);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 天气/预警提醒：气泡立刻出现，通知栏也留一条，并写进聊天记录。
     * 用户点通知就打开聊天页，看到的那句话已经在历史里，可以直接回复。
     */
    private void weatherAlertNow(String text) {
        if (text == null || text.trim().isEmpty()) return;
        final String t = text.trim();
        say(t, 9000);
        h.post(() -> {
            if (!alive) return;
            postSay(t);
            try {
                JSONObject extra = new JSONObject();
                extra.put("source", "weather_alert");
                Data.chatAppend(this, "assistant", t, "", "", extra);
            } catch (Throwable ignored) {
            }
            if (cfg != null && canSpeakNow(cfg)) Voice.speak(this, t, true);
        });
    }

    /** 用户看到聊天后，把「她主动发的消息」通知收掉。 */
    public static void clearSayNotif(Context c) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(NID_MSG);
        } catch (Throwable ignored) {
        }
    }

    private Notification build() {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainAct.class), 201326592);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, PetSvc.class).setAction(A_STOP), 201326592);
        PendingIntent lock = PendingIntent.getService(this, 2, new Intent(this, PetSvc.class).setAction(A_LOCK), 201326592);
        boolean edged = hiddenUser || edgeHidden;
        PendingIntent vis = PendingIntent.getService(this, 3,
                new Intent(this, PetSvc.class).setAction(edged ? A_SHOW : A_HIDE), 201326592);
        Icon icon = Icon.createWithResource(this, R.drawable.ic_launcher);
        boolean qqOnly = cfg.b("qqLinkEnabled", false) && !cfg.b("running", false);
        Notification.Builder b = new Notification.Builder(this, CH)
                .setContentTitle(qqOnly ? "个人 QQ 连接中" : (cfg.b("locked", false) ? "桌宠已锁定" : getString(R.string.notif_title)))
                .setContentText(qqOnly ? "NapCat 私聊桥接已启用" : (notifExtra.isEmpty() ? getString(R.string.notif_text) : notifExtra))
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(true)
                .setLocalOnly(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE);
        // 三个操作都放上：藏起来 / 锁定 / 收起来，不用打开应用
        b.addAction(new Notification.Action.Builder(icon, edged ? "叫出来" : "藏起来", vis).build())
                .addAction(new Notification.Action.Builder(icon, cfg.b("locked", false) ? "解锁" : "锁定", lock).build())
                .addAction(new Notification.Action.Builder(icon, "收起来", stop).build());
        return b.build();
    }

    private void goForeground() {
        try {
            Notification n = build();
            if (Build.VERSION.SDK_INT >= 34) {
                int type = 1073741824;
                if (cfg.b("geoEnabled", false) && Geo.granted(this) && Geo.background(this)) {
                    type |= 8;
                }
                try {
                    startForeground(NID, n, type);
                } catch (Throwable t) {
                    startForeground(NID, n, 1073741824);
                }
            } else {
                startForeground(NID, n);
            }
        } catch (Throwable ignored) {
        }
    }

    private void notifyUpdate() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NID, build());
        } catch (Throwable ignored) {
        }
    }

    private JSONObject stateJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("running", running && pet != null);
            o.put("locked", cfg.b("locked", false));
            o.put("hidden", hidden());
            o.put("posX", pet == null ? -1 : (int) pet.x());
            o.put("posY", pet == null ? -1 : (int) pet.y());
            o.put("size", pet == null ? cfg.sizeDp() : pet.sizePx());
        } catch (Throwable ignored) {
        }
        return o;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        h.postDelayed(() -> {
            try {
                // 横竖屏切换后旧坐标、旧弹层都不能继续用
                dismissMenu();
                if (panel != null) panel.dismiss();
                if (actionPanel != null) actionPanel.dismiss();
                if (chatBox != null && chatBox.isOpen()) chatBox.reposition();
                else if (pet != null) pet.onScreenChanged();
                if (pet != null) {
                    applyPetAttrs();
                    if (bubble != null) {
                        bubble.reposition((int) pet.x(), (int) pet.y(), pet.sizePx(), pet.heightPx());
                    }
                    if (alertBubble != null) {
                        alertBubble.reposition((int) pet.x(), (int) pet.y(), pet.sizePx(), pet.heightPx());
                    }
                }
                Bus.emit("state", stateJson());
            } catch (Throwable ignored) {
            }
        }, 400);
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // 从最近任务里划掉时，前台服务通常还活着；这里再挂一次看门狗，防止部分 ROM 顺手杀进程
        try {
            if (cfg != null && cfg.b("running", false) && cfg.b("keepAlive", true)) {
                Alive.schedule(this);
            }
            if (cfg != null && cfg.b("running", false)) TickAlarm.schedule(this);
        } catch (Throwable ignored) {
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        alive = false;
        running = false;
        updateFreeActionWakeLock(false);
        QQOneBot.stop();
        if (activeInstance == this) activeInstance = null;
        Bus.remove(this);
        teardown();
        releaseSounds();
        super.onDestroy();
    }
}
