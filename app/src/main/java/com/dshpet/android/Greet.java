package com.dshpet.android;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * 打开应用时的本地问候。
 * 只挂在 MainActivity.onResume，不注册屏幕解锁广播，不调用云端模型。
 */
public final class Greet {
    private static final String FILE = "greet_state.json";
    private static final long GAP_MS = 4 * 3600 * 1000L;
    private static final long CHAT_GRACE_MS = 30 * 60 * 1000L;
    /** 超过 3 天没打开应用，回来时用一句“你回来啦”，不复述具体天数、不施压。 */
    private static final long BACK_MS = 3 * 24 * 3600 * 1000L;

    private Greet() {
    }

    /** prevUserAt 是本次 onResume 之前的用户互动时间，用来判断是不是刚聊过。 */
    public static void maybe(Context c, long prevUserAt) {
        try {
            Cfg cfg = Cfg.load(c);
            if (!cfg.b("greetEnabled", true)) return;
            if (!Contract.isSet(c)) return;
            if (prevUserAt <= 0) return;
            long now = System.currentTimeMillis();
            if (now - prevUserAt < CHAT_GRACE_MS) return;
            if (now - Interact.lastTypeAt(c, "chat") < CHAT_GRACE_MS) return;
            if (now - Interact.lastTypeAt(c, "greet") < CHAT_GRACE_MS) return;

            JSONObject st = Sg.readObj(c, FILE);
            long lastOpen = st.optLong("lastOpen", 0L);
            boolean backAfterDays = lastOpen > 0 && now - lastOpen >= BACK_MS;
            // 每次真正回到前台都记一笔；1 分钟内的重复 onResume 不重复落盘
            if (lastOpen == 0L || now - lastOpen > 60_000L) {
                st.put("lastOpen", now);
                Sg.writeObj(c, FILE, st);
            }

            String day = Sg.now().substring(0, 10);
            int count = day.equals(st.optString("day", "")) ? st.optInt("count", 0) : 0;
            int max = Math.max(1, Math.min(4, cfg.i("greetPerDay", 2)));
            if (count >= max) return;
            if (now - st.optLong("lastAt", 0) < GAP_MS) return;
            if (Policy.nightNow(cfg)) return;

            Policy.Decision d = Policy.decide(c, "greet", 1);
            if (!d.ok) return;

            String line = pick(st, backAfterDays);
            if (line.isEmpty()) return;

            JSONArray recent = st.optJSONArray("recent");
            if (recent == null) recent = new JSONArray();
            recent.put(line);
            JSONArray keep = new JSONArray();
            for (int i = Math.max(0, recent.length() - 20); i < recent.length(); i++) {
                Object v = recent.opt(i);
                if (v != null) keep.put(v);
            }
            st.put("day", day);
            st.put("count", count + 1);
            st.put("lastAt", now);
            st.put("recent", keep);
            Sg.writeObj(c, FILE, st);

            PetSvc.sayGreet(c, line);
        } catch (Throwable ignored) {
        }
    }

    private static String pick(JSONObject st, boolean backAfterDays) {
        List<String> pool = new ArrayList<>();
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (backAfterDays) {
            pool.add("你回来啦。这几天我都在，不用解释。");
            pool.add("好久不见，看到你就好。");
            pool.add("你回来啦，先忙你的，我在这儿。");
        } else if (h < 11) {
            pool.add("早，今天也慢慢来。");
            pool.add("早，先吃点东西再忙。");
            pool.add("早上好，今天想先做什么？");
            pool.add("我在这儿，你忙你的就好。");
        } else if (h < 14) {
            pool.add("中午了，先吃点再忙。");
            pool.add("我在这儿，忙完叫我。");
            pool.add("上午辛苦了，下午也慢慢来。");
        } else if (h < 18) {
            pool.add("下午好，记得起来动一动。");
            pool.add("我在这儿，不用急着回我。");
            pool.add("忙到一半也可以歇一下。");
        } else {
            pool.add("忙完啦？先歇会儿。");
            pool.add("晚上好，今天辛苦了。");
            pool.add("我安静待着，你想说再说。");
            pool.add("今天也慢慢来，别硬撑。");
        }

        JSONArray recent = st.optJSONArray("recent");
        List<String> fresh = new ArrayList<>();
        for (String line : pool) {
            boolean used = false;
            if (recent != null) {
                for (int i = 0; i < recent.length(); i++) {
                    if (line.equals(recent.optString(i, ""))) {
                        used = true;
                        break;
                    }
                }
            }
            if (!used) fresh.add(line);
        }
        if (fresh.isEmpty()) return "";
        String[] a = fresh.toArray(new String[0]);
        return Care.pick(a);
    }
}
