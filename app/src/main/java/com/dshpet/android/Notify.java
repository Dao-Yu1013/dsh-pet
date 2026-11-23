package com.dshpet.android;

import android.app.Notification;
import android.content.Context;
import android.content.pm.PackageManager;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 通知：音乐兜底、未读消息、快递、外卖、出行提醒。 */
public final class Notify extends NotificationListenerService {
    private static volatile Notification media;
    private static final Set<String> msgKeys = new HashSet<>();
    private static final String[] CHAT_PKGS = {
            "com.tencent.mm", "com.tencent.mobileqq", "com.tencent.tim", "com.ss.android.lark",
            "com.alibaba.android.rimet", "com.whatsapp", "org.telegram.messenger",
            "com.instagram.android", "com.discord", "com.slack", "com.google.android.apps.messaging",
            "com.android.mms", "com.samsung.android.messaging", "com.tencent.androidqqmail"
    };
    private static final String[] PARCEL = {"快递", "快件", "包裹", "取件", "驿站", "丰巢", "菜鸟",
            "待取", "取货码", "派送", "签收", "投递", "物流"};
    private static final String[] PARCEL_PICK = {"取件", "取货码", "驿站", "丰巢", "快递柜", "待取",
            "自提", "代收点", "取货"};
    private static final String[] PARCEL_SHIP = {"发货", "已发出", "运输", "派送", "派件", "运送", "已揽收"};
    private static final String[] SHIP_IN_TRANSIT = {"已发货", "已发出", "运输中", "已揽收", "运送中"};
    private static final String[] PICK_READY = {"已到", "到达", "待取", "已放入", "请取", "可取件", "自提", "驿站", "快递柜"};
    private static final String[] PARCEL_SIGNED = {"已签收", "签收成功", "已取件", "取件成功", "已取出",
            "已投递", "投递成功", "已送达", "已完成配送", "领取成功"};
    private static final String[] FOOD = {"外卖", "美团", "饿了么", "骑手", "预计送达", "配送", "出餐", "取餐", "订单已送达"};
    private static final String[] TRAVEL = {"航班", "高铁", "火车", "动车", "登机", "行程", "出发", "酒店", "车次", "改签", "检票"};
    private static final String[] CAR = {"接驾", "车牌", "上车点", "滴滴", "打车", "行程中", "司机已", "司机正在"};
    private static final String[] WEATHER_RAIN = {"下雨", "降雨", "阵雨", "雷阵雨", "暴雨",
            "小雨", "中雨", "大雨", "雨夹雪"};
    private static final String[] PROMO_WORDS = {"广告", "优惠券", "领券", "限时特惠", "限时优惠",
            "促销", "大促", "特价", "推广", "猜你喜欢", "为你推荐", "立即抢购", "满减活动",
            "好价", "专享好价", "人气推荐", "人气早餐", "人气美食", "早餐特惠", "超值优惠",
            "限时福利", "会员价", "优惠来袭", "快来抢", "马上抢", "新品上市"};
    private static final String[] PARCEL_PROMO_WORDS = {"快递员手滑", "手滑发重", "快递员发重",
            "期待你拆箱", "期待拆箱", "等你拆箱", "等着你拆箱", "拆箱惊喜", "开箱惊喜"};
    private static final String[] REAL_PARCEL_STATUS = {"已发货", "已发出", "已揽收", "运输中", "运送中",
            "正在派送", "派送中", "开始派送", "正在派件", "派件中", "配送中", "快递员正在", "派送员正在",
            "已到驿站", "已到达驿站", "已到快递柜", "已到自提点", "已放入驿站", "已放入快递柜",
            "待取件", "请取件", "可取件", "取件通知", "取件码", "取货码", "提货码", "开柜码",
            "运单号", "物流单号", "快递单号", "已签收", "已投递", "已送达", "预计送达", "取件成功"};
    private static final String[] TRANSACTION_WORDS = {"支付成功", "已支付", "扣款", "到账", "收款成功",
            "退款成功", "退款已到账", "交易提醒", "已发货", "派送中", "已签收", "订单已送达",
            "骑手已接单", "司机已接单", "航班", "高铁", "火车票", "登机", "检票"};
    private static String lastWeatherKey = "";
    private static long lastWeatherTs;
    private static final String[][] CARRIERS = {
            {"顺丰"}, {"京东"}, {"中通"}, {"圆通"}, {"申通"}, {"韵达"}, {"极兔"}, {"EMS", "邮政"},
            {"德邦"}, {"菜鸟"}, {"丰巢"}, {"百世"}, {"丹鸟"}, {"宅急送"}, {"苏宁"}, {"天天"}
    };
    private static final Pattern BRACKET = Pattern.compile("[【\\[]([^】\\]]{2,40})[】\\]]");
    private static final Pattern BOUGHT = Pattern.compile("(?:您|你)购买的?([^，,。;；\\s]{2,30})");
    private static final Pattern PICK_CODE = Pattern.compile(
            "(?:取件码|取货码|提货码|取件密码|开柜码)[:：\\s]*([A-Za-z0-9\\-]{3,12})");
    private static final Map<String, Long> lastChatNotify = new HashMap<>();
    private static String lastAlertKey = "";
    private static long lastAlertTs;

    public static Notification mediaNotification() {
        return media;
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        Bus.emit("listener", new JSONObject());
        try {
            cleanupOldSigned(this);
        } catch (Throwable ignored) {
        }
    }

    /** 旧版把“已签收”也当成待取快件存了下来，升级后自动清理。 */
    public static void cleanupOldSigned(Context c) {
        JSONArray a = Data.reminders(c);
        JSONArray keep = new JSONArray();
        int n = 0;
        for (int k = 0; k < a.length(); k++) {
            JSONObject o = a.optJSONObject(k);
            if (o == null) continue;
            if ("parcel".equals(o.optString("type")) && looksSigned(o.optString("detail", ""))) {
                n++;
                continue;
            }
            keep.put(o);
        }
        if (n > 0) {
            Sg.writeArr(c, "reminders.json", keep);
            JSONObject o = new JSONObject();
            try {
                o.put("count", Data.pendingCount(c));
                o.put("unread", Bus.unreadCount);
                o.put("silent", true);
                Bus.emit("reminders", o);
            } catch (Throwable ignored) {
            }
        }
    }

    public static boolean looksSigned(String s) {
        return containsAny(s, PARCEL_SIGNED);
    }

    private static boolean isVerifyCode(String s) {
        return containsAny(s, new String[]{"验证码", "校验码", "动态码", "验证代码"});
    }

    /** 微信支付/支付宝/银行等支付类通知的标题，才允许进入记账。 */
    private static boolean paymentTitle(String t) {
        if (t == null) return false;
        return t.contains("支付") || t.contains("收款") || t.contains("账单")
                || t.contains("银行") || t.contains("到账") || t.contains("扣款");
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;
        String pkg = sbn.getPackageName() == null ? "" : sbn.getPackageName();
        if (pkg.equals(getPackageName())) return;
        Notification n = sbn.getNotification();
        if (n == null) return;
        CharSequence ct = n.extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence cx = n.extras.getCharSequence(Notification.EXTRA_TEXT);
        if (cx == null) cx = n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
        String title = ct == null ? "" : ct.toString();
        String text = cx == null ? "" : cx.toString();
        String all = title + " " + text;

        boolean isMedia = (n.category != null && n.category.equals(Notification.CATEGORY_TRANSPORT)
                || n.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)
                || pkg.contains("music") || pkg.contains("spotify") || pkg.contains("ximalaya")
                || pkg.equals("com.netease.cloudmusic") || pkg.equals("com.kugou.android"))
                && !Music.looksLikeNonMusic(pkg);
        if (isMedia) {
            media = n;
            Music.maybeFromNotification(title, text, true);
            // 播放器开启「通知栏歌词」时，正文就是正在唱的那句，直接拿来用
            Music.fromNotificationLine(pkg, title, text);
        }

        if (sbn.isOngoing()) return;

        Cfg cfg = Cfg.load(this);
        if (!allowed(cfg, pkg)) return;
        String app = label(pkg);
        boolean verifyCode = isVerifyCode(all);
        String safeTitle = verifyCode ? title.replaceAll("\\d{4,8}", "****") : title;
        String safeText = verifyCode ? "验证码短信（内容已隐藏）" : text;
        boolean muted = NotifyStore.muted(this, pkg);
        boolean marketing = isMarketingNotification(n, pkg, title, text);
        String noticeKind = marketing ? "广告营销" : classifyNotification(n, pkg, all, verifyCode);
        if (cfg.b("notifSummary", true) && !muted) {
            NotifyStore.capture(this, pkg, app, safeTitle, safeText,
                    isMedia ? "音乐" : noticeKind);
        }
        // 天气也遵守应用筛选、应用静音和实时提醒总开关；不额外请求网络天气。
        if (weatherRain(pkg, title, text)) {
            if (!muted && cfg.b("remindersEnabled", true)) {
                String wkey = sbn.getKey() + "|" + text;
                long now = System.currentTimeMillis();
                if (!wkey.equals(lastWeatherKey) || now - lastWeatherTs > 6 * 3600000L) {
                    lastWeatherKey = wkey;
                    lastWeatherTs = now;
                    emitRealtime("weather", withDetail("天气预报说有雨，出门记得带伞。",
                                    notificationDetail("weather", safeTitle, safeText, app, verifyCode)),
                            app + " · 天气", pkg, sbn.getKey());
                }
            }
            return;
        }
        if (!cfg.b("remindersEnabled", true)) return;
        boolean isChat = n.category != null && n.category.equals(Notification.CATEGORY_MESSAGE);
        if (!isChat) {
            for (String p : CHAT_PKGS) {
                if (pkg.equals(p) || pkg.startsWith(p + ".")) {
                    isChat = true;
                    break;
                }
            }
        }
        // 记账只认支付/银行类通知；群聊、私聊正文不参与，避免把聊天里的金额当成消费
        if (!marketing && !verifyCode && (!isChat || paymentTitle(title))) {
            Ledger.onNotification(this, pkg, app, title, text);
        }
        // 聊天应用的推送、订阅号、公众号、动态一律不管，只回真正的聊天消息
        if (isChat && isPushLike(n, title, text)) return;

        // 每条通知到达时先做本地即时分拣：应用静音、营销推送不进入桌宠气泡。
        // 交易/物流/出行状态通知保留，避免通知正文带“优惠”等词时误过滤。
        if (muted) return;
        if (!isChat && marketing) return;

        String realtime = "";
        String realtimeType = "msg";
        String realtimeTag = "";
        long now = System.currentTimeMillis();
        if (isChat && !title.isEmpty() && cfg.b("msgEnabled", true)) {
            // 群聊只在 @ 自己、有人@我或明确回复/提到自己时才提示；私聊照常
            boolean group = isGroupChat(n, title, text);
            if (!group || mentionsMe(this, text)) {
                Long last = lastChatNotify.get(pkg);
                if (last == null || now - last > 120000L) {
                    lastChatNotify.put(pkg, now);
                    msgKeys.add(sbn.getKey());
                    Bus.unreadCount = Math.max(msgKeys.size(), NotifyStore.unread(this));
                    realtime = verifyCode
                            ? "收到一条验证码短信，已帮你保密，去" + app + "里查看。"
                            : Care.msg(this, app);
                    realtimeTag = app + " · " + (verifyCode ? "验证码" : "消息");
                }
            }
        }

        boolean food = !isChat && (containsAny(all, FOOD) || pkg.contains("meituan")
                || pkg.contains("eleme") || pkg.contains("sankuai"));
        boolean parcel = !isChat && !food && containsAny(all, PARCEL)
                && !isParcelMarketingText(title, text);
        if (parcel && cfg.b("parcelEnabled", true)) {
            String carrier = carrierOf(all);
            String item = itemOf(all);
            boolean signed = containsAny(all, PARCEL_SIGNED);
            realtime = signed ? Care.parcelDone(this, item) : Care.parcel(this, item);
            realtimeType = signed ? "parcel_done" : "parcel";
            realtimeTag = (carrier.isEmpty() ? "快递" : carrier)
                    + (signed ? " · 已送达" : " · 有动静");
        } else if (food && cfg.b("parcelEnabled", true)) {
            realtime = Care.food(this, app);
            realtimeType = "food";
            realtimeTag = app + " · 外卖";
        }
        boolean travel = !isChat && (containsAny(all, TRAVEL) || containsAny(all, CAR) || pkg.contains("didi"));
        if (travel && !parcel && !food && cfg.b("travelEnabled", true)) {
            boolean car = containsAny(all, CAR);
            realtime = car ? Care.ride(this) : Care.travel(this);
            realtimeType = "travel";
            realtimeTag = app + " · " + (car ? "叫车" : "出行");
        }
        if (realtime.isEmpty() && !isChat && paymentTitle(title)
                && containsAny(all, new String[]{"支付成功", "已支付", "扣款", "到账", "收款成功", "退款成功", "交易提醒"})) {
            realtime = "我看到一条账单或支付动态，提醒你确认一下。";
            realtimeType = "payment";
            realtimeTag = app + " · 账单";
        }
        if (!realtime.isEmpty()) {
            final String rType = realtimeType;
            final String rLocal = realtime;
            final String rTag = realtimeTag;
            final String rKey = sbn.getKey();
            // Carry a short, privacy-safe description all the way to the pet bubble.
            // The line may be playful, but it must still tell the user what arrived.
            final String detail = notificationDetail(rType, safeTitle, safeText, app, verifyCode);
            if (isLife(rType)) {
                // 外卖/快递/叫车/出行：先按地点、时间和习惯生成一句，再统一进气泡、通知栏和聊天记录
                Care.life(this, rType, safeTitle + " " + safeText, rLocal, new Ai.Cb() {
                    @Override
                    public void ok(String text) {
                        emitRealtime(rType, withDetail(text, detail), rTag, pkg, rKey);
                    }

                    @Override
                    public void fail(String err) {
                        emitRealtime(rType, withDetail(rLocal, detail), rTag, pkg, rKey);
                    }
                });
            } else {
                emitRealtime(rType, withDetail(rLocal, detail), rTag, pkg, rKey);
            }
            // 低频（每天最多一次）在后台补充几句符合性格的变体，失败不影响本地话术
            Care.maybeRefreshAI(this, realtimeType);
        }
    }

    private static String notificationDetail(String type, String title, String text, String app, boolean verifyCode) {
        if (verifyCode) return "收到一条验证码通知（内容已隐藏），请查看" + app + "。";
        String detail = (title == null ? "" : title.trim());
        String body = (text == null ? "" : text.trim());
        if (!body.isEmpty() && !body.equals(detail) && !"验证码短信（内容已隐藏）".equals(body)) {
            detail = detail.isEmpty() ? body : detail + "：" + body;
        }
        if (detail.isEmpty()) {
            if ("msg".equals(type)) return "收到" + app + "的新消息。";
            return "收到" + app + "的通知。";
        }
        detail = detail.replaceAll("\\s+", " ");
        if (detail.length() > 72) detail = detail.substring(0, 72) + "…";
        return "通知内容：" + detail;
    }

    /** 通知到达时同步分型，只作本地关键词与系统类别判断，不等待网络请求。 */
    private static String classifyNotification(Notification n, String pkg, String all, boolean verifyCode) {
        if (verifyCode) return "验证码";
        if (n != null && (Notification.CATEGORY_PROMO.equals(n.category)
                || Notification.CATEGORY_RECOMMENDATION.equals(n.category))) return "广告营销";
        if (containsAny(all, WEATHER_RAIN)) return "天气预警";
        if (isMarketingText("", all)) return "广告营销";
        if (containsAny(all, PARCEL)) return "快递物流";
        if (containsAny(all, FOOD)) return "外卖餐饮";
        if (containsAny(all, TRAVEL) || containsAny(all, CAR) || (pkg != null && pkg.contains("didi"))) return "出行交通";
        if (paymentTitle(all)) return "账单扣款";
        if ((n != null && Notification.CATEGORY_MESSAGE.equals(n.category)) || isChatPackage(pkg)) return "消息";
        return "其他";
    }

    /**
     * 物流广告常伪装成“派送通知”，但正文用“手滑发重/期待拆箱”等带货玩笑话术，
     * 没有运单、取件码或真实物流状态。此类消息不进入桌宠实时提醒。
     */
    static boolean isParcelMarketingText(String title, String text) {
        String all = ((title == null ? "" : title) + " " + (text == null ? "" : text))
                .replaceAll("\\s+", "");
        boolean playful = containsAny(all, PARCEL_PROMO_WORDS);
        boolean productTease = all.contains("你买的")
                && containsAny(all, new String[]{"在路上", "期待", "拆箱", "开箱", "等你"});
        if (!playful && !productTease) return false;
        return !containsAny(all, REAL_PARCEL_STATUS) && !PICK_CODE.matcher(all).find();
    }

    /** 明确促销词要与真实物流/交易状态区分；如“专享好价、早餐特惠、人气推荐”。 */
    static boolean isMarketingText(String title, String text) {
        String all = (title == null ? "" : title) + " " + (text == null ? "" : text);
        if (isParcelMarketingText(title, text)) return true;
        boolean realParcel = containsAny(all, REAL_PARCEL_STATUS) || PICK_CODE.matcher(all).find();
        return containsAny(all, PROMO_WORDS) && !containsAny(all, TRANSACTION_WORDS) && !realParcel;
    }

    private static boolean isMarketingNotification(Notification n, String pkg, String title, String text) {
        if (n != null && (Notification.CATEGORY_PROMO.equals(n.category)
                || Notification.CATEGORY_RECOMMENDATION.equals(n.category))) return true;
        String all = (title == null ? "" : title) + " " + (text == null ? "" : text);
        return isMarketingText(title, text) && !weatherRain(pkg, title, text) && !isVerifyCode(all);
    }

    private static boolean isChatPackage(String pkg) {
        if (pkg == null) return false;
        for (String p : CHAT_PKGS) if (pkg.equals(p) || pkg.startsWith(p + ".")) return true;
        return false;
    }

    private static String withDetail(String line, String detail) {
        String base = line == null ? "" : line.trim();
        if (detail == null || detail.isEmpty()) return base;
        if (base.contains(detail)) return base;
        return (base.isEmpty() ? "" : base + "，") + detail;
    }

    /** 这些类型她主动说的话要同时进通知栏和聊天记录。 */
    private static boolean isLife(String t) {
        return "food".equals(t) || "parcel".equals(t) || "parcel_done".equals(t)
                || "ride".equals(t) || "travel".equals(t);
    }

    private static final String[] PUSH_TITLES = {"服务通知", "订阅号", "公众号", "微信支付", "微信团队",
            "腾讯新闻", "新闻", "推荐", "活动", "系统通知", "官方", "广告", "优惠", "红包"};

    /** 判断通知是不是群聊；不确定时按私聊处理，避免误吞私聊。 */
    private static boolean isGroupChat(Notification n, String title, String text) {
        try {
            if (n.extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false)) return true;
            CharSequence conv = n.extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE);
            if (conv != null && !conv.toString().trim().isEmpty()) return true;
            CharSequence sub = n.extras.getCharSequence(Notification.EXTRA_SUB_TEXT);
            if (sub != null && sub.toString().contains("群")) return true;
        } catch (Throwable ignored) {
        }
        String t = title == null ? "" : title;
        return t.contains("群");
    }

    /** 群聊里是否 @ 了自己，或明确提到/回复了自己。 */
    private static boolean mentionsMe(android.content.Context c, String text) {
        if (text == null) return false;
        String s = text.trim();
        if (s.isEmpty()) return false;
        String[] keys = {"@所有人", "@全体成员", "@all", "@All", "有人@我", "提到了你", "回复了你", "@你"};
        for (String k : keys) if (s.contains(k)) return true;
        try {
            String nick = Data.profile(c).optString("nickname", "").trim();
            if (!nick.isEmpty() && s.contains("@" + nick)) return true;
            String name = Data.profile(c).optString("name", "").trim();
            if (!name.isEmpty() && s.contains("@" + name)) return true;
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 推送、订阅号、公众号、好友动态这类不进实时提醒。 */
    private static boolean isPushLike(Notification n, String title, String text) {
        String cat = n.category == null ? "" : n.category;
        if (Notification.CATEGORY_RECOMMENDATION.equals(cat)
                || Notification.CATEGORY_PROMO.equals(cat)
                || Notification.CATEGORY_EMAIL.equals(cat)
                || Notification.CATEGORY_PROGRESS.equals(cat)
                || Notification.CATEGORY_TRANSPORT.equals(cat)) {
            return true;
        }
        String t = (title == null ? "" : title) + (text == null ? "" : text);
        for (String k : PUSH_TITLES) {
            if (t.contains(k)) return true;
        }
        return t.contains("订阅号消息") || t.contains("公众号消息");
    }

    private static String parcelTitle(String all) {
        if (isPickupReady(all)) return "快递取件";
        if (containsAny(all, PARCEL_SHIP)) {
            if (containsAny(all, new String[]{"发货", "已发出", "运输", "已揽收"})) return "快递发货";
            return "快递派送";
        }
        if (containsAny(all, PARCEL_PICK)) return "快递取件";
        return "快递提醒";
    }

    /** 包裹已经到驿站/快递柜，才算“待取件”；运输和发货都不算。 */
    private static boolean isPickupReady(String all) {
        if (containsAny(all, SHIP_IN_TRANSIT)) return false;
        return containsAny(all, new String[]{"已到", "到达", "待取", "已放入", "请取", "可取件", "自提", "驿站", "快递柜"});
    }

    private static String parcelStage(String all) {
        if (isPickupReady(all)) return "pickup";
        if (containsAny(all, SHIP_IN_TRANSIT) || containsAny(all, new String[]{"派送", "派件"})) return "shipped";
        if (containsAny(all, PARCEL_PICK)) return "pickup";
        return "unknown";
    }

    private static String carrierOf(String all) {
        if (all == null || all.isEmpty()) return "";
        for (String[] names : CARRIERS) {
            for (String n : names) {
                if (all.contains(n)) return names[0];
            }
        }
        return "";
    }

    private static String itemOf(String all) {
        if (all == null || all.isEmpty()) return "";
        try {
            Matcher m = BRACKET.matcher(all);
            while (m.find()) {
                String s = m.group(1).trim();
                if (!s.isEmpty() && !s.contains("快递") && !s.contains("包裹")
                        && !s.contains("取件") && !s.contains("驿站")) {
                    return clip(s, 24);
                }
            }
            m = BOUGHT.matcher(all);
            if (m.find()) {
                String s = m.group(1).trim();
                if (!s.isEmpty()) return clip(s, 24);
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    private static String codeOf(String all) {
        if (all == null || all.isEmpty()) return "";
        try {
            Matcher m = PICK_CODE.matcher(all);
            if (m.find()) return m.group(1);
        } catch (Throwable ignored) {
        }
        return "";
    }

    private static String doneLabel(JSONObject o) {
        if (o == null) return "快递";
        String detail = o.optString("detail", "");
        String carrier = o.optString("carrier", "");
        String item = o.optString("item", "");
        if (carrier.isEmpty()) carrier = carrierOf(detail);
        if (item.isEmpty()) item = itemOf(detail);
        if (!carrier.isEmpty() && !item.isEmpty()) return carrier + " · " + item;
        if (!carrier.isEmpty()) return carrier;
        if (!item.isEmpty()) return item;
        String t = o.optString("title", "");
        return t.isEmpty() ? "快递" : t;
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        s = s.trim();
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static boolean allowed(Cfg cfg, String pkg) {
        JSONArray a = cfg.arr("notifyApps");
        if (a == null || a.length() == 0) return true;
        for (int k = 0; k < a.length(); k++) {
            if (pkg.equals(a.optString(k))) return true;
        }
        return false;
    }

    private void emitRealtime(String type, String text, String tag, String pkg, String key) {
        long now = System.currentTimeMillis();
        if (key != null && key.equals(lastAlertKey) && now - lastAlertTs < 20000L) return;
        lastAlertKey = key == null ? "" : key;
        lastAlertTs = now;
        try {
            JSONObject o = new JSONObject();
            o.put("type", type);
            o.put("text", text.length() > 180 ? text.substring(0, 180) : text);
            o.put("tag", tag);
            o.put("pkg", pkg);
            Bus.emit("realtime", o);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn == null) return;
        String pkg = sbn.getPackageName() == null ? "" : sbn.getPackageName();
        if (msgKeys.remove(sbn.getKey())) {
            Bus.unreadCount = Math.max(msgKeys.size(), NotifyStore.unread(this));
            Data.removeReminderByKey(this, "notif:" + sbn.getKey());
            emitReminder();
        }
        // 用户打开聊天应用后通知通常会被移除，用它作为气泡自动消失的兜底
        if (!pkg.isEmpty() && !pkg.equals(getPackageName())) {
            try {
                JSONObject o = new JSONObject();
                o.put("pkg", pkg);
                Bus.emit("realtimeCancel", o);
            } catch (Throwable ignored) {
            }
        }
        if (media != null && sbn.getNotification() == media) {
            media = null;
            if (!Music.playing()) Music.update("", "", false, 0, 0);
        }
    }

    private void emitReminder() {
        emitReminder(false);
    }

    private void emitReminder(boolean silent) {
        JSONObject o = new JSONObject();
        try {
            o.put("count", Data.pendingCount(this));
            o.put("unread", Bus.unreadCount);
            o.put("silent", silent);
        } catch (Throwable ignored) {
        }
        Bus.emit("reminders", o);
    }

    /** 只认天气应用/标题里带「天气」的通知，并且正文必须明确提到下雨。 */
    private static boolean weatherRain(String pkg, String title, String text) {
        String p = pkg == null ? "" : pkg;
        String t = title == null ? "" : title;
        boolean weatherApp = p.contains("weather") || p.contains("moji") || p.contains("caiyun")
                || p.contains("qweather") || p.contains("meteo") || t.contains("天气");
        if (!weatherApp) return false;
        String all = t + " " + (text == null ? "" : text);
        return containsAny(all, WEATHER_RAIN);
    }

    private static boolean containsAny(String s, String[] arr) {
        if (s == null || s.isEmpty()) return false;
        for (String k : arr) if (s.contains(k)) return true;
        return false;
    }

    private String label(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Throwable t) {
            return pkg;
        }
    }
}
