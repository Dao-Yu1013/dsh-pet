package com.dshpet.android;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/** 天气/空气质量提醒（Open-Meteo 免费接口），只用本地坐标，不上传其他信息。 */
public final class Weather {
    private static volatile boolean checking;

    private Weather() {
    }

    public static JSONObject state(Context c) {
        return Sg.readObj(c, "weather.json");
    }

    /** Whether a chat message explicitly asks for current/local weather information. */
    public static boolean isWeatherRequest(String text) {
        if (text == null) return false;
        String s = text.replaceAll("\\s+", "");
        return s.contains("天气") || s.contains("下雨") || s.contains("降雨") || s.contains("温度")
                || s.contains("气温") || s.contains("带伞") || s.contains("紫外线") || s.contains("空气质量");
    }

    /** Travel planning should use a real nearby forecast as context for the AI's itinerary. */
    public static boolean isTravelPlanningRequest(String text) {
        if (text == null) return false;
        String s = text.replaceAll("\\s+", "");
        boolean asksPlan = s.contains("去哪玩") || s.contains("去哪里玩") || s.contains("周末去哪")
                || s.contains("出游") || s.contains("旅游攻略") || s.contains("旅行攻略")
                || s.contains("安排路线") || s.contains("规划路线") || s.contains("出去玩")
                || s.contains("玩一天") || s.contains("一日游") || s.contains("行程安排");
        return asksPlan;
    }

    /** Follow-up such as “那你看看” after a weather question should perform the real lookup. */
    public static boolean isWeatherFollowUp(String text, JSONArray history) {
        if (text == null) return false;
        String q = text.replaceAll("[\\s，。！？!?]", "");
        // Only explicit short weather follow-ups inherit weather intent; never infer it from
        // generic words such as “看看/查查”, which commonly start a new topic.
        boolean explicitWeatherFollowUp = q.matches("^(那)?(天气|气温|温度|降雨|下雨)(呢|怎么样|如何|查下|查一下|看看)?$")
                || q.matches("^(那)?(查|看看)(天气|气温|温度|降雨|下雨)(吧|呗|呢)?$");
        if (!explicitWeatherFollowUp) return false;
        if (history == null) return false;
        for (int i = history.length() - 1, seen = 0; i >= 0 && seen < 8; i--, seen++) {
            JSONObject item = history.optJSONObject(i);
            if (item == null) continue;
            String content = item.optString("content", "");
            if (isWeatherRequest(content)) return true;
        }
        return false;
    }

    /**
     * Synchronous one-shot lookup for the current user request. The result is machine-readable
     * so the chat model can distinguish a real observation from an unavailable tool.
     */
    public static JSONObject lookupForChat(Context c) {
        JSONObject result = new JSONObject();
        try {
            result.put("tool", "weather");
            JSONObject point = Geo.lastPoint(c);
            double lat = point.optDouble("lat", 0);
            double lng = point.optDouble("lng", 0);
            if (Math.abs(lat) < 0.0001 || Math.abs(lng) < 0.0001) {
                result.put("ok", false);
                result.put("status", "failed");
                result.put("error", "我这边还没有可用的位置，先在权限设置里允许定位，再告诉我查天气就行。");
                return result;
            }
            long locationAt = point.optLong("ts", 0L);
            if (locationAt <= 0 || System.currentTimeMillis() - locationAt > 3L * 60L * 60L * 1000L) {
                result.put("ok", false);
                result.put("status", "failed");
                result.put("error", "我这边的位置记录已经超过 3 小时了，这次不拿旧位置冒充附近天气。等定位更新后我再帮你查。");
                return result;
            }
            JSONObject weather = fetch(lat, lng);
            if (weather == null || weather.optString("summary", "").isEmpty()) {
                result.put("ok", false);
                result.put("status", "failed");
                result.put("error", "天气服务这次没有返回有效结果，我没法确认你附近的天气，过一会儿我再查。");
                return result;
            }
            long checkedAt = System.currentTimeMillis();
            weather.put("ts", checkedAt);
            weather.put("date", Sg.now().substring(0, 10));
            Sg.writeObj(c, "weather.json", weather);
            JSONObject facts = new JSONObject();
            for (String key : new String[]{"summary", "temp", "feels", "tmin", "tmax", "humidity",
                    "weather", "precip", "rain", "rainProb", "umbrella", "uv", "aqi", "outdoor", "date"}) {
                if (weather.has(key)) facts.put(key, weather.opt(key));
            }
            result.put("ok", true);
            result.put("status", "success");
            result.put("source", "Open-Meteo 实时接口");
            result.put("checkedAt", checkedAt);
            result.put("facts", facts);
            return result;
        } catch (Throwable t) {
            String detail = t.getMessage() == null ? "" : t.getMessage().toLowerCase(Locale.ROOT);
            String userError;
            if (detail.contains("unknownhost") || detail.contains("timeout") || detail.contains("connect")) {
                userError = "我这次没连上天气服务，网络恢复后再帮你查。";
            } else {
                userError = "天气这次没查成功，我不想拿旧数据糊弄你，稍后再试一次吧。";
            }
            try {
                result.put("tool", "weather");
                result.put("ok", false);
                result.put("status", "failed");
                result.put("error", userError);
            } catch (Throwable ignored) { }
            return result;
        }
    }

    /** Synchronous lookup compatibility wrapper; callers should prefer the structured result. */
    public static String chatAnswer(Context c) {
        JSONObject result = lookupForChat(c);
        if (result.optBoolean("ok", false)) {
            JSONObject facts = result.optJSONObject("facts");
            return "我刚查了你附近的天气：" + (facts == null ? "" : facts.optString("summary", ""));
        }
        return result.optString("error", "天气这次没查成功，稍后再试一次吧。");
    }

    /** 后台检查一次；force=true 时忽略“同状态不重复播报”的限制。 */
    public static synchronized void check(final Context c, final boolean force) {
        if (checking) return;
        Cfg cfg = Cfg.load(c);
        if (!cfg.b("weatherEnabled", true)) return;
        checking = true;
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            try {
                JSONObject p = Geo.lastPoint(app);
                double lat = p.optDouble("lat", 0);
                double lng = p.optDouble("lng", 0);
                if (Math.abs(lat) < 0.0001 || Math.abs(lng) < 0.0001) return;
                JSONObject w = fetch(lat, lng);
                if (w == null || w.optString("summary", "").isEmpty()) return;
                w.put("ts", System.currentTimeMillis());
                w.put("date", Sg.now().substring(0, 10));
                JSONObject old = Sg.readObj(app, "weather.json");
                boolean first = old.length() == 0;
                boolean changed = !w.optString("stateKey", "").equals(old.optString("stateKey", ""));
                Sg.writeObj(app, "weather.json", w);
                int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
                boolean daytime = hour >= 7 && hour < 22;
                if (force || (daytime && (first || changed))) {
                    // 不念完整预报，只给一句能用的提醒
                    String tip = Care.weather(w);
                    if (tip.isEmpty()) {
                        if (!force) return;
                        tip = "今天" + w.optString("weather", "天气") + " "
                                + Math.round(w.optDouble("temp", 0)) + "℃，挺舒服的，记得出去走走";
                    }
                    // 天气不再绕过打扰策略：交给 PetSvc 走 Policy 的 weather 类型
                    PetSvc.sayWeather(app, tip);
                }
            } catch (Throwable ignored) {
            } finally {
                checking = false;
            }
        }, "dsh-weather").start();
    }

    private static volatile boolean alertChecking;

    /**
     * 每 3 小时一次的天气 + 气象预警巡检。
     * 命中「正在下雨 / 12 小时内大概率下雨」或「黄色以上预警」时，让 AI 写一句关心话，
     * 交给 PetSvc 发通知栏消息并写进聊天记录（用户可以直接在聊天框回复）。
     */
    public static synchronized void checkAlert(final Context c, final boolean force) {
        if (alertChecking) return;
        final Cfg cfg = Cfg.load(c);
        if (!cfg.b("weatherAlertCheck", true) || !cfg.b("weatherEnabled", true)) return;
        final long now = System.currentTimeMillis();
        long last = cfg.l("weatherAlertLastAt", 0L);
        // 3 小时内不重复巡检；force 用于手动触发
        if (!force && last > 0 && now - last < 3 * 3600 * 1000L) return;
        alertChecking = true;
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            try {
                JSONObject p = Geo.lastPoint(app);
                double lat = p.optDouble("lat", 0);
                double lng = p.optDouble("lng", 0);
                if (Math.abs(lat) < 0.0001 || Math.abs(lng) < 0.0001) return;
                JSONObject w = fetch(lat, lng);
                if (w == null) return;
                w.put("ts", now);
                w.put("date", Sg.now().substring(0, 10));
                Sg.writeObj(app, "weather.json", w);
                try {
                    cfg.set("weatherAlertLastAt", now);
                    cfg.save(app);
                } catch (Throwable ignored) {
                }

                JSONObject mem = Sg.readObj(app, "weather_alert.json");
                boolean raining = w.optBoolean("rain", false);
                int rainProb = w.optInt("rainProb", 0);
                long rainAt = mem.optLong("rainAt", 0L);
                // 同一场雨 6 小时内只提醒一次
                boolean rainHit = (raining || rainProb >= 70)
                        && (force || now - rainAt > 6 * 3600 * 1000L);

                Place place = placeOf(app, lat, lng);
                JSONObject alarm = place == null ? null : findAlarm(place);
                String alarmId = alarm == null ? "" : alarm.optString("alertid", "");
                JSONArray seen = mem.optJSONArray("seen");
                if (seen == null) seen = new JSONArray();
                boolean alarmHit = !alarmId.isEmpty() && !has(seen, alarmId);
                if (!rainHit && !alarmHit) return;
                // 夜间休息不打扰：这次不提醒，也不写去重标记，留给白天那一轮
                if (Policy.nightNow(cfg)) return;

                // 先落去重标记再请求 AI，避免 AI 超时后下一轮又重复提醒
                if (rainHit) mem.put("rainAt", now);
                if (alarmHit) {
                    JSONArray keep = new JSONArray();
                    int start = Math.max(0, seen.length() - 39);
                    for (int i = start; i < seen.length(); i++) keep.put(seen.optString(i, ""));
                    keep.put(alarmId);
                    mem.put("seen", keep);
                }
                mem.put("ts", now);
                Sg.writeObj(app, "weather_alert.json", mem);

                final String fallback;
                final String ask;
                // 话术要结合地点、时间、习惯和今天的天气
                final String ctx = Care.context(app);
                if (alarmHit) {
                    String title = alarm.optString("title", "");
                    fallback = "气象台发了" + levelOf(title) + "预警：" + whatOf(title)
                            + "。今天多留个心，能不出门就别出门。";
                    ask = ctx + "气象预警：" + title
                            + "\n请只回一句 20 字以内的关心话，先提到预警，再给一个具体建议。";
                } else {
                    String tip = Care.weather(w);
                    fallback = tip.isEmpty()
                            ? (raining ? "外面下雨了，出门记得带伞。" : "看着要下雨，伞先带着。") : tip;
                    ask = ctx + "请只回一句 20 字以内的关心话，说清要不要带伞，结合现在的时间和地点。";
                }
                speakAsk(app, ask, fallback);
            } catch (Throwable ignored) {
            } finally {
                alertChecking = false;
            }
        }, "dsh-weather-alert").start();
    }

    /** 让 AI 把天气/预警说成人话；没有 Key 或失败时用本地模板。 */
    private static void speakAsk(final Context app, final String ask, final String fallback) {
        try {
            String name = "她";
            try {
                name = Data.persona(app).optString("name", "她");
            } catch (Throwable ignored) {
            }
            JSONArray msgs = new JSONArray();
            JSONObject sys = new JSONObject();
            sys.put("role", "system");
            sys.put("content", "你是用户的桌宠「" + name + "」，说话口语化、亲昵、简短。"
                    + "只输出一句 20 字以内的中文关心话，不要解释、不要引号、不要换行。");
            msgs.put(sys);
            JSONObject u = new JSONObject();
            u.put("role", "user");
            u.put("content", ask);
            msgs.put(u);
            Ai.rawChat(app, msgs, 80, 0.9, new Ai.Cb() {
                @Override
                public void ok(String text) {
                    PetSvc.weatherAlert(app, tidy(text, fallback));
                }

                @Override
                public void fail(String err) {
                    PetSvc.weatherAlert(app, fallback);
                }
            });
        } catch (Throwable t) {
            PetSvc.weatherAlert(app, fallback);
        }
    }

    private static String tidy(String s, String fallback) {
        if (s == null) return fallback;
        String t = s.trim().replace("\r", " ").replace("\n", " ");
        while (t.startsWith("\"") || t.startsWith("“") || t.startsWith("「")) t = t.substring(1).trim();
        while (t.endsWith("\"") || t.endsWith("”") || t.endsWith("」")) {
            t = t.substring(0, t.length() - 1).trim();
        }
        if (t.length() > 60) t = t.substring(0, 60);
        return t.isEmpty() ? fallback : t;
    }

    /** 行政区：用于匹配中央气象台的预警标题与行政区划代码。 */
    private static final class Place {
        String province = "";
        String city = "";
        String code = "";
    }

    /** 反查当前位置的省市与行政区划代码，缓存 12 小时（免 Key 的 BigDataCloud 接口）。 */
    private static Place placeOf(Context app, double lat, double lng) {
        try {
            JSONObject cache = Sg.readObj(app, "geo_place.json");
            if (cache.length() > 0
                    && System.currentTimeMillis() - cache.optLong("ts", 0L) < 12 * 3600 * 1000L
                    && Math.abs(cache.optDouble("lat", 0) - lat) < 0.2
                    && Math.abs(cache.optDouble("lng", 0) - lng) < 0.2) {
                Place o = new Place();
                o.province = cache.optString("province", "");
                o.city = cache.optString("city", "");
                o.code = cache.optString("code", "");
                return o;
            }
            String url = String.format(Locale.US,
                    "https://api.bigdatacloud.net/data/reverse-geocode-client"
                            + "?latitude=%.4f&longitude=%.4f&localityLanguage=zh", lat, lng);
            JSONObject root = new JSONObject(get(url));
            Place p = new Place();
            p.city = root.optString("city", "");
            p.province = root.optString("principalSubdivision", "");
            JSONObject info = root.optJSONObject("localityInfo");
            JSONArray adm = info == null ? null : info.optJSONArray("administrative");
            if (adm != null) {
                for (int i = 0; i < adm.length(); i++) {
                    JSONObject o = adm.optJSONObject(i);
                    if (o == null) continue;
                    String cc = o.optString("chinaAdminCode", "").replace(" ", "");
                    if (cc.length() >= 4) {
                        p.code = cc;
                        break;
                    }
                }
            }
            JSONObject o = new JSONObject();
            o.put("lat", lat);
            o.put("lng", lng);
            o.put("ts", System.currentTimeMillis());
            o.put("province", p.province);
            o.put("city", p.city);
            o.put("code", p.code);
            Sg.writeObj(app, "geo_place.json", o);
            return p;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 拉中央气象台的预警列表，按行政区划前 4 位或城市名匹配，只保留黄色以上。 */
    private static JSONObject findAlarm(Place p) {
        String code4 = p.code.length() >= 4 ? p.code.substring(0, 4) : "";
        if (code4.isEmpty() && p.city.isEmpty()) return null;
        try {
            for (int page = 1; page <= 6; page++) {
                String url = "https://www.nmc.cn/rest/findAlarm?pageNo=" + page + "&pageSize=20";
                JSONObject root = new JSONObject(get(url));
                JSONObject data = root.optJSONObject("data");
                JSONObject pg = data == null ? null : data.optJSONObject("page");
                JSONArray list = pg == null ? null : pg.optJSONArray("list");
                if (list == null || list.length() == 0) break;
                for (int i = 0; i < list.length(); i++) {
                    JSONObject a = list.optJSONObject(i);
                    if (a == null) continue;
                    String title = a.optString("title", "");
                    if (levelOf(title).isEmpty()) continue;
                    String id = a.optString("alertid", "");
                    if (!p.city.isEmpty() && title.contains(p.city)) return a;
                    if (!code4.isEmpty() && id.length() >= 4 && id.startsWith(code4)) return a;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 预警级别：红色/橙色/黄色返回级别名，蓝色或识别不出返回空（不提醒）。 */
    private static String levelOf(String title) {
        if (title.contains("红色")) return "红色";
        if (title.contains("橙色")) return "橙色";
        if (title.contains("黄色")) return "黄色";
        return "";
    }

    /** 从「XX气象台发布大风黄色预警信号」里取出「大风」。 */
    private static String whatOf(String title) {
        String s = title == null ? "" : title;
        int i = s.lastIndexOf("发布");
        if (i >= 0) s = s.substring(i + 2);
        s = s.replace("预警信号", "").replace("预警", "")
                .replace("蓝色", "").replace("黄色", "").replace("橙色", "").replace("红色", "").trim();
        return s.isEmpty() ? "天气变化" : s;
    }

    private static boolean has(JSONArray a, String v) {
        if (a == null || v.isEmpty()) return false;
        for (int i = 0; i < a.length(); i++) {
            if (v.equals(a.optString(i, ""))) return true;
        }
        return false;
    }

    private static JSONObject fetch(double lat, double lng) throws Exception {
        String base = String.format(Locale.US,
                "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f", lat, lng);
        String url = base
                + "&current=temperature_2m,apparent_temperature,precipitation,weather_code,relative_humidity_2m"
                + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,uv_index_max"
                + "&hourly=precipitation_probability,uv_index"
                + "&timezone=auto&forecast_days=2";
        JSONObject root = new JSONObject(get(url));
        JSONObject cur = root.optJSONObject("current");
        JSONObject daily = root.optJSONObject("daily");
        JSONObject hourly = root.optJSONObject("hourly");
        if (cur == null || daily == null) return null;

        double temp = cur.optDouble("temperature_2m", 0);
        double feels = cur.optDouble("apparent_temperature", temp);
        double precip = cur.optDouble("precipitation", 0);
        int code = cur.optInt("weather_code", 0);
        int humidity = cur.optInt("relative_humidity_2m", 0);

        JSONArray tmaxA = daily.optJSONArray("temperature_2m_max");
        JSONArray tminA = daily.optJSONArray("temperature_2m_min");
        JSONArray probA = daily.optJSONArray("precipitation_probability_max");
        JSONArray uvA = daily.optJSONArray("uv_index_max");
        double tmax = tmaxA == null ? temp : tmaxA.optDouble(0, temp);
        double tmin = tminA == null ? temp : tminA.optDouble(0, temp);
        int dailyProb = probA == null ? -1 : probA.optInt(0, -1);
        double dailyUv = uvA == null ? 0 : uvA.optDouble(0, 0);

        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        String localCurrentTime = cur.optString("time", "");
        try {
            int t = localCurrentTime.indexOf('T');
            if (t >= 0 && t + 3 <= localCurrentTime.length())
                hour = Math.max(0, Math.min(23, Integer.parseInt(localCurrentTime.substring(t + 1, t + 3))));
        } catch (Throwable ignored) { }
        int rainProb = Math.max(0, dailyProb);
        boolean hasNextHoursRainForecast = false;
        double uvNow = dailyUv;
        JSONArray times = hourly == null ? null : hourly.optJSONArray("time");
        JSONArray hProb = hourly == null ? null : hourly.optJSONArray("precipitation_probability");
        JSONArray hUv = hourly == null ? null : hourly.optJSONArray("uv_index");
        int idx = -1;
        if (times != null) {
            String now = localCurrentTime;
            if (now.isEmpty()) now = new SimpleDateFormat("yyyy-MM-dd'T'HH:00", Locale.US).format(new Date());
            for (int i = 0; i < times.length(); i++) {
                String t = times.optString(i, "");
                if (t.compareTo(now) >= 0) {
                    idx = i;
                    break;
                }
            }
            if (idx >= 0 && hProb != null) {
                rainProb = 0;
                hasNextHoursRainForecast = true;
                for (int i = idx; i < Math.min(times.length(), idx + 12); i++) {
                    rainProb = Math.max(rainProb, hProb.optInt(i, 0));
                }
            }
            if (idx >= 0 && hUv != null) uvNow = hUv.optDouble(idx, dailyUv);
            else if (hour < 8 || hour > 17) uvNow = 0;
        }

        boolean raining = precip > 0.1 || isRainCode(code);
        boolean umbrella = raining || rainProb >= 50;
        boolean uvStrong = uvNow >= 6;
        int aqi = -1;
        try {
            String aqUrl = String.format(Locale.US,
                    "https://air-quality-api.open-meteo.com/v1/air-quality?latitude=%.4f&longitude=%.4f"
                            + "&current=us_aqi,pm2_5&timezone=auto", lat, lng);
            JSONObject aq = new JSONObject(get(aqUrl)).optJSONObject("current");
            if (aq != null) aqi = aq.optInt("us_aqi", -1);
        } catch (Throwable ignored) {
        }

        boolean airBad = aqi >= 0 && aqi > 100;
        boolean outdoor = !raining && rainProb < 70 && !airBad && tmax < 35 && tmin > -5;

        StringBuilder sb = new StringBuilder();
        sb.append(period(hour)).append("，现在").append(codeText(code))
                .append("，").append(Math.round(temp)).append("℃");
        if (Math.abs(feels - temp) >= 3) sb.append("，体感 ").append(Math.round(feels)).append("℃");
        sb.append("。今天 ").append(Math.round(tmin)).append("~").append(Math.round(tmax)).append("℃。");
        if (raining) sb.append("正在下雨，出门记得带伞。");
        else if (rainProb >= 50) sb.append(hasNextHoursRainForecast ? "未来12小时" : "今天")
                .append("降雨概率最高 ").append(rainProb).append("%，带伞更稳。");
        else if (rainProb >= 30) sb.append(hasNextHoursRainForecast ? "未来12小时可能有阵雨，伞先备着。" : "今天可能有阵雨，伞先备着。");
        else sb.append(hasNextHoursRainForecast ? "未来12小时降雨概率不高。" : "今天降雨概率不高。");
        if (uvStrong) sb.append("紫外线较强，注意防晒。");
        else if (uvNow >= 3) sb.append("紫外线中等。");
        if (aqi >= 0) {
            if (aqi <= 50) sb.append("空气优。");
            else if (aqi <= 100) sb.append("空气良。");
            else if (aqi <= 150) sb.append("空气一般，AQI ").append(aqi).append("。");
            else sb.append("空气较差，AQI ").append(aqi).append("，敏感人群少出门。");
        }
        if (tmax >= 35) sb.append("高温，尽量避开中午。");
        if (tmin <= 0) sb.append("很冷，注意保暖。");
        sb.append(outdoor ? "整体适合出门。" : "不太适合长时间在户外。");

        JSONObject o = new JSONObject();
        o.put("temp", temp);
        o.put("feels", feels);
        o.put("tmin", tmin);
        o.put("tmax", tmax);
        o.put("humidity", humidity);
        o.put("code", code);
        o.put("weather", codeText(code));
        o.put("precip", precip);
        o.put("rain", raining);
        o.put("rainProb", rainProb);
        o.put("rainProbToday", Math.max(0, dailyProb));
        o.put("rainForecastWindow", hasNextHoursRainForecast ? "未来12小时" : "今日预报");
        o.put("umbrella", umbrella);
        o.put("uv", uvNow);
        o.put("uvStrong", uvStrong);
        o.put("aqi", aqi);
        o.put("airBad", airBad);
        o.put("outdoor", outdoor);
        o.put("summary", sb.toString());
        StringBuilder key = new StringBuilder();
        key.append(raining ? 'R' : 'N');
        key.append(umbrella ? 'U' : 'u');
        key.append(uvStrong ? 'V' : 'v');
        key.append(airBad ? 'A' : 'a');
        key.append(outdoor ? 'O' : 'o');
        key.append(tmax >= 35 ? 'H' : 'h');
        key.append(tmin <= 0 ? 'C' : 'c');
        o.put("stateKey", key.toString());
        return o;
    }

    private static String period(int hour) {
        if (hour < 6) return "夜深了";
        if (hour < 12) return "早上好";
        if (hour < 18) return "下午好";
        return "晚上好";
    }

    private static boolean isRainCode(int code) {
        return (code >= 51 && code <= 67) || (code >= 80 && code <= 82) || (code >= 95 && code <= 99);
    }

    private static String codeText(int code) {
        if (code == 0) return "晴";
        if (code <= 2) return "少云";
        if (code == 3) return "多云";
        if (code == 45 || code == 48) return "有雾";
        if (code >= 51 && code <= 57) return "毛毛雨";
        if (code >= 61 && code <= 67) return "下雨";
        if (code >= 71 && code <= 77) return "下雪";
        if (code >= 80 && code <= 82) return "阵雨";
        if (code >= 85 && code <= 86) return "阵雪";
        if (code >= 95) return "雷雨";
        return "天气一般";
    }

    private static String get(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(12000);
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("User-Agent", "DSH-Pet/2.3");
        int code = conn.getResponseCode();
        InputStream in = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
        StringBuilder sb = new StringBuilder();
        if (in != null) {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
        }
        conn.disconnect();
        if (code < 200 || code >= 300) throw new Exception("HTTP " + code);
        return sb.toString();
    }
}
