package com.dshpet.android;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.Locale;
import java.util.Random;

/** 把系统通知翻译成「有人味」的关心话术。全部本地生成，不消耗 token。 */
public final class Care {
    private static final Random RND = new Random();
    private static final String AI_FILE = "care_ai.json";
    private static volatile long lastAiAt;
    private static volatile boolean aiBusy;

    private Care() {
    }

    public static String pick(String[] a) {
        if (a == null || a.length == 0) return "";
        return a[RND.nextInt(a.length)];
    }

    /** 优先用本地句；有当天缓存的 AI 变体时随机混用，避免每次完全一样。 */
    private static String mix(Context c, String type, String local) {
        String ai = aiLine(c, type);
        if (ai.isEmpty()) return local;
        return RND.nextBoolean() ? ai : local;
    }

    private static String aiLine(Context c, String type) {
        try {
            JSONObject cache = Sg.readObj(c, AI_FILE);
            JSONObject pools = cache.optJSONObject("pools");
            JSONArray a = pools == null ? null : pools.optJSONArray(type);
            if (a == null || a.length() == 0) return "";
            String s = a.optString(RND.nextInt(a.length()), "").trim();
            return s.length() >= 2 && s.length() <= 80 ? s : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    /**
     * 每天最多生成一次通知话术变体，放在后台，不阻塞通知气泡。
     * 生成失败继续用本地池，不影响功能。
     */
    public static void maybeRefreshAI(Context c, String type) {
        if (aiBusy || c == null) return;
        Cfg cfg = Cfg.load(c);
        if (!cfg.b("notifyAi", true) || !cfg.aiReady()) return;
        String day = Sg.now().substring(0, 10);
        try {
            JSONObject cache = Sg.readObj(c, AI_FILE);
            if (day.equals(cache.optString("day", "")) && cache.optJSONObject("pools") != null) return;
        } catch (Throwable ignored) {
        }
        long now = System.currentTimeMillis();
        if (now - lastAiAt < 6 * 3600000L) return;
        aiBusy = true;
        lastAiAt = now;
        String name = "她";
        String style = "中文口语，短句，像真人";
        StringBuilder desc = new StringBuilder();
        try {
            name = Data.persona(c).optString("name", "她");
            style = Data.persona(c).optString("style", style);
            JSONObject tr = Traits.summary(c);
            JSONArray ds = tr.optJSONArray("descriptors");
            if (ds != null) {
                for (int i = 0; i < ds.length() && i < 3; i++) {
                    String s = ds.optString(i, "");
                    if (!s.isEmpty()) desc.append(s).append("；");
                }
            }
        } catch (Throwable ignored) {
        }
        JSONArray msgs = new JSONArray();
        try {
            JSONObject sys = new JSONObject();
            sys.put("role", "system");
            sys.put("content", "你在扮演桌宠「" + name + "」。写用户收到系统通知时她的一句自然反应。\n"
                    + "说话风格：" + style + "\n"
                    + (desc.length() > 0 ? "性格：" + desc + "\n" : "")
                    + "要求：口语、短句，不超过 30 字；不编造具体人名、金额、商品；"
                    + "不要客服腔、不要内疚、不要催回复。\n"
                    + "输出 JSON 对象，键为 msg、food、parcel、parcel_done、ride、travel，"
                    + "每个值是 4 条互不相同的短句。只输出 JSON。");
            msgs.put(sys);
            JSONObject user = new JSONObject();
            user.put("role", "user");
            user.put("content", "生成通知反应话术。");
            msgs.put(user);
        } catch (Throwable ignored) {
        }
        Ai.rawChat(c, msgs, 600, new Ai.Cb() {
            @Override
            public void ok(String text) {
                try {
                    int a = text.indexOf('{');
                    int b = text.lastIndexOf('}');
                    if (a >= 0 && b > a) {
                        JSONObject root = new JSONObject(text.substring(a, b + 1));
                        JSONObject pools = root.optJSONObject("pools");
                        if (pools == null) pools = root;
                        JSONObject out = new JSONObject();
                        for (String key : new String[]{"msg", "food", "parcel", "parcel_done", "ride", "travel"}) {
                            JSONArray src = pools.optJSONArray(key);
                            if (src == null) continue;
                            JSONArray clean = new JSONArray();
                            for (int i = 0; i < src.length() && clean.length() < 4; i++) {
                                String s = src.optString(i, "").trim();
                                if (s.length() >= 2 && s.length() <= 80) clean.put(s);
                            }
                            if (clean.length() > 0) out.put(key, clean);
                        }
                        if (out.length() > 0) {
                            JSONObject cache = Sg.readObj(c, AI_FILE);
                            cache.put("day", day);
                            cache.put("pools", out);
                            Sg.writeObj(c, AI_FILE, cache);
                        }
                    }
                } catch (Throwable ignored) {
                }
                aiBusy = false;
            }

            @Override
            public void fail(String err) {
                aiBusy = false;
            }
        });
    }

    /** 根据当前性格选语气，避免每个用户都收到同一套模板。 */
    private static String pickTone(Context c, String[] humor, String[] gentle,
                                   String[] reserved, String[] neutral) {
        try {
            JSONObject tr = Traits.summary(c);
            JSONArray dims = tr.optJSONArray("dims");
            int h = 0, g = 0, r = 0;
            if (dims != null) {
                for (int k = 0; k < dims.length(); k++) {
                    JSONObject d = dims.optJSONObject(k);
                    if (d == null) continue;
                    String key = d.optString("key", "");
                    int v = d.optInt("value", 50);
                    if ("humor".equals(key)) h = v;
                    else if ("gentle".equals(key)) g = v;
                    else if ("reserved".equals(key)) r = v;
                }
            }
            if (h >= 60 && h >= g && h >= r) return pick(humor);
            if (r >= 60 && r >= g) return pick(reserved);
            if (g >= 60) return pick(gentle);
        } catch (Throwable ignored) {
        }
        return pick(neutral);
    }

    /* ---------------- 生活提醒：结合地点、时间和习惯 ---------------- */

    private static volatile long lastLifeAt;

    /** 把外卖/快递/叫车/出行说成一句贴合当下的话；AI 不可用时回退本地模板。 */
    public static void life(final Context c, final String type, final String detail,
                            final String local, final Ai.Cb cb) {
        long now = System.currentTimeMillis();
        if (!Cfg.load(c).aiReady() || now - lastLifeAt < 180000L) {
            cb.ok(local);
            return;
        }
        lastLifeAt = now;
        String name = "她";
        String style = "";
        try {
            JSONObject p = Data.persona(c);
            name = p.optString("name", "她");
            style = p.optString("style", "");
        } catch (Throwable ignored) {
        }
        try {
            JSONArray msgs = new JSONArray();
            JSONObject sys = new JSONObject();
            sys.put("role", "system");
            sys.put("content", "你在扮演用户的桌宠「" + name + "」。"
                    + (style.isEmpty() ? "" : "说话风格：" + style + "。")
                    + "\n只输出一句 20~40 字的中文，口语、亲昵、自然；"
                    + "不要解释、不要引号、不要换行、不要编造金额/人名/商品。\n" + context(c));
            msgs.put(sys);
            JSONObject u = new JSONObject();
            u.put("role", "user");
            u.put("content", "我刚收到一条" + label(type) + "通知：" + clip(detail, 120)
                    + "\n结合现在的时间、我所在的地方和我的习惯，回我一句话。");
            msgs.put(u);
            Ai.rawChat(c, msgs, 120, 0.9, new Ai.Cb() {
                @Override
                public void ok(String text) {
                    cb.ok(clean(text, local));
                }

                @Override
                public void fail(String err) {
                    cb.ok(local);
                }
            });
        } catch (Throwable t) {
            cb.ok(local);
        }
    }

    private static String label(String type) {
        if ("food".equals(type)) return "外卖";
        if ("parcel".equals(type)) return "快递";
        if ("parcel_done".equals(type)) return "快递已送达";
        if ("ride".equals(type)) return "叫车";
        if ("travel".equals(type)) return "出行";
        return "消息";
    }

    /** 生成话术共用的上下文：时间、所在地点、用户习惯和今天天气。 */
    public static String context(Context c) {
        StringBuilder sb = new StringBuilder();
        try {
            Calendar cal = Calendar.getInstance();
            int hour = cal.get(Calendar.HOUR_OF_DAY);
            String week = new String[]{"周日", "周一", "周二", "周三", "周四", "周五", "周六"}
                    [cal.get(Calendar.DAY_OF_WEEK) - 1];
            sb.append("现在：").append(week)
                    .append(String.format(Locale.US, " %02d:%02d", hour, cal.get(Calendar.MINUTE)))
                    .append(hour < 6 ? "（深夜）" : hour < 11 ? "（早上）"
                            : hour < 14 ? "（中午）" : hour < 18 ? "（下午）"
                            : hour < 22 ? "（晚上）" : "（深夜）")
                    .append('\n');
        } catch (Throwable ignored) {
        }
        try {
            JSONObject gp = Sg.readObj(c, "geo_place.json");
            String city = gp.optString("city", "");
            String prov = gp.optString("province", "");
            if (!city.isEmpty() || !prov.isEmpty()) {
                sb.append("他所在的地方：").append(prov).append(city).append('\n');
            }
        } catch (Throwable ignored) {
        }
        try {
            String tr = Traits.prompt(c);
            if (!tr.isEmpty()) sb.append(tr);
        } catch (Throwable ignored) {
        }
        try {
            String mem = SelfMem.prompt(c, 3);
            if (!mem.isEmpty()) sb.append("你记得他的事：\n").append(mem).append('\n');
        } catch (Throwable ignored) {
        }
        try {
            JSONObject w = Weather.state(c);
            String s = w.optString("summary", "");
            if (!s.isEmpty()) sb.append("今天的天气：").append(clip(s, 160)).append('\n');
        } catch (Throwable ignored) {
        }
        return sb.toString();
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        String t = s.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    private static String clean(String s, String fallback) {
        if (s == null) return fallback;
        String t = s.trim().replace("\r", " ").replace("\n", " ");
        while (t.startsWith("\"") || t.startsWith("“") || t.startsWith("「")) t = t.substring(1).trim();
        while (t.endsWith("\"") || t.endsWith("”") || t.endsWith("」")) {
            t = t.substring(0, t.length() - 1).trim();
        }
        t = clip(t, 60);
        return t.isEmpty() ? fallback : t;
    }

    /* ---------------- 外卖：问吃什么，她也想吃 ---------------- */
    public static String food(Context c, String app) {
        String local = pickTone(c,
                new String[]{
                        "外卖到啦，今天又背着我吃好吃的？说，点什么了。",
                        "骑手来了，快说吃什么，我看看值不值得馋。",
                        "有外卖动静！你负责吃，我负责闻味道。",
                },
                new String[]{
                        "外卖到啦，先趁热吃，别凉了。",
                        "有外卖消息，记得先吃饭再忙。",
                        "骑手在路上了，到了先吃口热的。",
                },
                new String[]{
                        "外卖到了。先吃。",
                        "有外卖。趁热。",
                },
                new String[]{
                        "外卖来啦～你点了什么好吃的呀？我也馋了",
                        "有外卖动静！今天吃什么，能分我一口吗",
                        "闻到香味了（假装）～点什么啦，快告诉我",
                        "又点外卖啦，这次是什么好吃的？",
                        "骑手在路上了～你点的东西听起来就很好吃",
                        "外卖有更新～我也好想知道你晚上吃什么",
                });
        return mix(c, "food", local);
    }

    /* ---------------- 快递：问买了什么好东西 ---------------- */
    public static String parcel(Context c, String item) {
        if (item != null && !item.isEmpty()) {
            return pickTone(c,
                    new String[]{
                            "「" + item + "」有动静了，我赌你拆开先拍照。",
                            "你的「" + item + "」在路上了，别急，跑不了。",
                    },
                    new String[]{
                            "「" + item + "」有动静啦，到了记得告诉我。",
                            "看到「" + item + "」的物流了，应该快到了。",
                    },
                    new String[]{
                            "「" + item + "」有动静。",
                            "「" + item + "」快到了。",
                    },
                    new String[]{
                            "「" + item + "」有动静啦，好东西呀，到了给我看看",
                            "你买的「" + item + "」在路上啦～好期待你拆箱",
                            "是关于「" + item + "」的快递消息，听起来不错",
                    });
        }
        return mix(c, "parcel", pickTone(c,
                new String[]{
                        "包裹在动了，拆的时候叫上我，我赌是好东西。",
                        "又买什么了？到了别藏着，给我看看。",
                },
                new String[]{
                        "你的包裹有动静啦，到了记得告诉我。",
                        "快递在路上，拆的时候慢一点。",
                },
                new String[]{
                        "包裹有动静。",
                        "快递快了。",
                },
                new String[]{
                        "你的包裹有动静啦，买了什么好东西呀？",
                        "快递在动了～拆的时候叫上我，我也想看",
                        "又买好东西啦？到了记得给我看看",
                        "包裹在路上啦，这次是什么宝贝？",
                        "看到快递消息了，好想知道你买了什么",
                        "快递有更新～好期待你拆箱的样子",
                }));
    }

    public static String parcelDone(Context c, String item) {
        if (item != null && !item.isEmpty()) {
            return pickTone(c,
                    new String[]{
                            "「" + item + "」到啦，拆箱现场记得叫我。",
                            "你的宝贝到了，快去拆，我等着看。",
                    },
                    new String[]{
                            "「" + item + "」到了，去拿的时候慢一点。",
                            "东西到了，记得先检查一下。",
                    },
                    new String[]{
                            "到了。去拿。",
                            "「" + item + "」到了。",
                    },
                    new String[]{
                            "「" + item + "」到啦，快去看看，拆箱快乐！",
                    });
        }
        return mix(c, "parcel_done", pickTone(c,
                new String[]{
                        "包裹到啦，拆箱现场记得叫我。",
                        "东西到了，我赌你拆开先拍照。",
                },
                new String[]{
                        "包裹到啦，去拿的时候慢一点。",
                        "东西到了，记得先检查一下。",
                },
                new String[]{
                        "到了。去拿。",
                        "包裹到了。",
                },
                new String[]{
                        "包裹到啦！快去看看是什么好东西",
                        "东西到了～拆箱快乐！",
                }));
    }

    /* ---------------- 叫车 / 出行：问去哪玩，给建议 ---------------- */
    public static String ride(Context c) {
        return mix(c, "ride", pickTone(c,
                new String[]{
                        "出门啦？去哪儿，带上我（精神上）。",
                        "叫车了？目的地报一下，我看看好不好玩。",
                },
                new String[]{
                        "出门注意安全，到了跟我说一声。",
                        "车在路上了，路上小心。",
                },
                new String[]{
                        "出门小心。到了说。",
                        "车来了。注意安全。",
                },
                new String[]{
                        "要出门啦？去哪儿玩呀，我可以给你出主意",
                        "看到你叫车了，目的地是哪儿？好玩的话我也想去",
                        "出门注意安全～到地方了跟我说说风景",
                        "去玩吗？要不要我帮你想想路线",
                        "车在路上了，路上小心，到了记得报平安",
                }));
    }

    public static String travel(Context c) {
        return mix(c, "travel", pickTone(c,
                new String[]{
                        "要出发啦？行李别落下，我赌你会忘充电器。",
                        "这次去哪儿？好玩的话记得带上我（精神上）。",
                },
                new String[]{
                        "要去旅行啦？路上注意安全，到了报个平安。",
                        "出行消息看到了，行李和证件再检查一遍。",
                },
                new String[]{
                        "出发了。注意安全。",
                        "出行顺利。",
                },
                new String[]{
                        "要去旅行啦？行李收拾好了吗，我可以帮你列清单",
                        "看到出行消息了～这次去哪儿，好玩记得带上我",
                        "又要出发啦，路上注意安全，风景记得拍给我看",
                }));
    }

    /* ---------------- 微信/QQ：先忙你的，我不拿消息绑架你 ---------------- */
    public static String msg(Context c, String app) {
        return mix(c, "msg", pickTone(c,
                new String[]{
                        "哟，消息来了。先去看看，别让人家等急了。",
                        "有人找你，我先把嘴闭上，等你回来。",
                },
                new String[]{
                        "有消息啦，先去回他吧，我在这儿等你。",
                        "有人在找你，先忙你的，不用急着回来。",
                },
                new String[]{
                        "有消息。先看。",
                        "消息来了。你去。",
                },
                new String[]{
                        "有消息来啦，先忙你的，我在这儿。",
                        "有人找你，去看看吧，不着急回我。",
                        "消息来了～先处理你的事，我等你。",
                        "有人给你发消息了，先回他，我在旁边待着。",
                        "来消息了，看看是什么事；我这边不催你。",
                        "有朋友找你呀，去聊吧，忙完再来。",
                }));
    }

    /**
     * 音乐共情：只有「深夜 + 慢歌 + 他最近情绪低落」三件事凑齐才开口，
     * 不猜歌词内容，也不强行解读他在想什么。
     */
    public static String musicMood(Context c) {
        int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        if (hour >= 3 && hour < 22) return "";
        double mood = State.load(c).optDouble("moodScore", 0);
        if (mood > -30) return "";
        return pick(new String[]{
                "这首歌好安静……你今天是不是有点累，陪你把这首听完。",
                "这么晚还听这么慢的歌，是不是心里装着事？不用跟我说，我就在旁边。",
                "夜深了，歌也慢下来了。想说的话就说，不想说我就安静陪着。",
        });
    }

    /** 到点问候的后半句：她自己接上「到家啦，…」。调用方负责拼接地点。 */
    public static String arrive(boolean home, boolean rain, double tmax, double tmin) {
        if (rain) {
            return home ? pick(new String[]{
                    "外面雨下得挺大，有没有淋湿？湿了先冲个热水澡，别感冒。",
                    "淋到雨没有？湿衣服赶紧换掉，我可不想听你打喷嚏。",
            }) : pick(new String[]{
                    "路上雨大不大？没淋着吧，坐下先擦擦鞋。",
                    "外面雨还在下，坐下先喝口热的暖暖。",
            });
        }
        if (tmax >= 35) return home ? "这么热的天，先喝口水降降温。" : "外面晒得厉害，先缓一缓再开工。";
        if (tmin <= 0) return home ? "外面这么冷，先暖暖手。" : "天冷，到了先缓一下再忙。";
        return home ? "先歇口气。" : "今天也慢慢来。";
    }

    /** 到达用户自己命名的地点时，带地点信息自然关心；不推测用户在此做了什么。 */
    public static void geoArrival(Context c, String placeName, Ai.Cb cb) {
        String name = placeName == null || placeName.trim().isEmpty() ? "这个地方" : clip(placeName.trim(), 30);
        String fallback = "到" + name + "啦？你常来这里吗，今天怎么样呀？";
        try {
            JSONArray messages = new JSONArray();
            JSONObject system = new JSONObject();
            system.put("role", "system");
            system.put("content", "你是用户的桌宠伙伴。根据当前角色设定，用自然、简短、亲近的中文说一句到达提醒：提到用户刚到“" + name + "”，表现一点好奇并关心用户。不要假装知道用户来这里的原因、做过什么或看到了什么；不要连续追问。最多约45字，可自然使用语气词或颜文字。\n" + context(c));
            messages.put(system);
            JSONObject user = new JSONObject();
            user.put("role", "user");
            user.put("content", "用户到达了自己添加的位置：" + name + "。请生成一句自然的关心话。");
            messages.put(user);
            Ai.rawChat(c, messages, 80, 0.8, new Ai.Cb() {
                @Override public void ok(String text) { cb.ok(clean(text, fallback)); }
                @Override public void fail(String err) { cb.ok(fallback); }
            });
        } catch (Throwable t) { cb.ok(fallback); }
    }

    /** 深夜低电量：这时候她该急一点，不该客气。 */
    public static String battery(int level) {
        if (level <= 5) {
            return pick(new String[]{
                    "电量只剩 " + level + "% 了！先去插上电，然后乖乖睡觉。",
                    "手机马上要关机了，别玩了，充电睡觉，明天再说。",
            });
        }
        return pick(new String[]{
                "电量只剩 " + level + "% 了，别玩啦，插上电去睡觉。",
                "都这个点了，手机也快没电了，去充电睡觉吧。",
        });
    }

    /* ---------------- 天气：只给结论式提醒，不念预报 ---------------- */
    public static String weather(JSONObject w) {
        if (w == null) return "";
        boolean rain = w.optBoolean("rain", false);
        int prob = w.optInt("rainProb", 0);
        boolean uv = w.optBoolean("uvStrong", false);
        boolean air = w.optBoolean("airBad", false);
        double tmax = w.optDouble("tmax", 0);
        double tmin = w.optDouble("tmin", 0);
        if (rain || prob >= 60) {
            if (!rain) return pick(new String[]{
                    "未来12小时有降雨可能，出门记得带伞",
                    "接下来半天有阵雨机会，伞先放包里吧",
                    "现在没在下雨，不过未来12小时有雨的可能，出门带把伞更稳",
            });
            return pick(new String[]{
                    "要下雨啦，出门记得带伞，别淋成落汤鸡",
                    "今天有雨，伞带上，感冒了可没人照顾你",
                    "看着要下雨了，伞别忘了，我可不想看你湿着回来",
            });
        }
        if (uv) {
            return pick(new String[]{
                    "今天太阳好晒，记得防晒，别晒黑啦",
                    "紫外线有点强，帽子或者伞挑一个带上",
                    "日头挺毒的，出门挡一挡，皮肤会谢谢你",
            });
        }
        if (air) {
            return pick(new String[]{
                    "空气不太好，今天就别去户外折腾啦",
                    "外面空气一般，少出去，我陪你在家",
            });
        }
        if (tmax >= 35) {
            return pick(new String[]{
                    "今天特别热，多喝水，别中暑了",
                    "热得有点离谱，中午尽量待在屋里",
            });
        }
        if (tmin <= 0) {
            return pick(new String[]{
                    "降温了，穿厚点，别冻着",
                    "外面很冷，外套记得加上",
            });
        }
        return "";
    }
}
