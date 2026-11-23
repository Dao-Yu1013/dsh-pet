package com.dshpet.android;

import android.content.Context;
import android.location.Location;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Locale;

/** End-user-triggered, approximate nearby POI lookup backed by OpenStreetMap/Overpass. */
public final class NearbySearch {
    private static final String PENDING = "nearby_search_consent.json";
    private static final String CACHE = "nearby_search_cache.json";
    private static final long CACHE_TTL = 15L * 60L * 1000L;
    private static final long LOCATION_MAX_AGE = 3L * 60L * 60L * 1000L;

    private NearbySearch() { }

    public static JSONObject lookupForChat(Context c, String kind) {
        JSONObject out = new JSONObject();
        try {
            out.put("tool", "nearby_search");
            out.put("kind", kind == null ? "food" : kind);
            Cfg cfg = Cfg.load(c);
            if (!cfg.b("nearbyOsmConsent", false)) {
                Sg.writeObj(c, PENDING, new JSONObject().put("at", System.currentTimeMillis()));
                out.put("ok", false);
                out.put("status", "consent_required");
                out.put("error", "第一次搜索附近地点前需要说明：应用会把约 100 米精度的模糊位置发给 OpenStreetMap 查询附近地点；不会发送姓名或聊天记录。回复“允许”继续，回复“不允许”取消。地点资料可能不完整，也不含实时营业、评分和价格。");
                return out;
            }
            JSONObject point = Geo.lastPoint(c);
            double lat = point.optDouble("lat", 0);
            double lon = point.optDouble("lng", 0);
            long fixAt = point.optLong("ts", 0L);
            long age = System.currentTimeMillis() - fixAt;
            if (Math.abs(lat) < 0.0001 || Math.abs(lon) < 0.0001 || fixAt <= 0 || age < -300000L || age > LOCATION_MAX_AGE) {
                out.put("ok", false);
                out.put("status", "location_unavailable");
                out.put("error", "我现在没有 3 小时内的有效位置，暂时没法可靠地查附近地点。检查定位权限和定位开关后，等位置更新再问我就行。");
                return out;
            }
            // Round before any network request. This intentionally limits location precision to
            // roughly 100 m and keeps exact coordinates out of the request, cache and AI receipt.
            double approxLat = Math.round(lat * 1000.0) / 1000.0;
            double approxLon = Math.round(lon * 1000.0) / 1000.0;
            String category = normalizeKind(kind);
            String cacheKey = String.format(Locale.US, "%s:%.3f:%.3f", category, approxLat, approxLon);
            JSONObject cached = Sg.readObj(c, CACHE);
            if (cacheKey.equals(cached.optString("key"))
                    && System.currentTimeMillis() - cached.optLong("ts", 0) < CACHE_TTL) {
                out.put("ok", cached.optJSONArray("places") != null && cached.optJSONArray("places").length() > 0);
                out.put("status", "cached");
                out.put("source", "OpenStreetMap / Overpass（15 分钟缓存）");
                out.put("checkedAt", cached.optLong("ts", 0));
                out.put("locationPrecision", "约 100 米");
                out.put("facts", cached.optJSONArray("places") == null ? new JSONArray() : cached.optJSONArray("places"));
                if (!out.optBoolean("ok")) out.put("error", "OpenStreetMap 这次没有找到附近地点；附近数据可能尚未收录。");
                return out;
            }
            JSONArray places = query(approxLat, approxLon, category);
            if (places.length() == 0) {
                out.put("ok", false);
                out.put("status", "empty");
                out.put("source", "OpenStreetMap / Overpass");
                out.put("locationPrecision", "约 100 米");
                out.put("error", "OpenStreetMap 没有返回附近地点。当地地图资料可能不完整，不能据此断定附近没有店。");
                return out;
            }
            long now = System.currentTimeMillis();
            JSONObject save = new JSONObject().put("key", cacheKey).put("ts", now).put("places", places);
            Sg.writeObj(c, CACHE, save);
            out.put("ok", true);
            out.put("status", "success");
            out.put("source", "OpenStreetMap / Overpass");
            out.put("checkedAt", now);
            out.put("locationPrecision", "约 100 米");
            out.put("facts", places);
            out.put("limits", "这是地图收录信息，不含实时营业状态、评分、库存或价格；未收录的地点不会出现。距离按模糊位置估算。");
            return out;
        } catch (Throwable t) {
            try {
                out.put("ok", false);
                out.put("status", "failed");
                out.put("source", "OpenStreetMap / Overpass");
                out.put("error", "附近地点查询没有完成：" + safeError(t));
            } catch (Throwable ignored) { }
            return out;
        }
    }

    /** Return a local reply only for an explicit answer to the one-time location disclosure. */
    public static String handleConsent(Context c, String text) {
        JSONObject pending = Sg.readObj(c, PENDING);
        long at = pending.optLong("at", 0L);
        if (at <= 0 || System.currentTimeMillis() - at > 30L * 60L * 1000L) return "";
        String s = text == null ? "" : text.trim().replaceAll("[\\s，。！？!?、]+", "");
        if (equalsAny(s, "不允许", "不可以", "不同意", "取消", "算了", "不用", "我不允许", "我不同意")) {
            Sg.writeObj(c, PENDING, new JSONObject());
            return "好，那我不把位置发给地图服务。你也可以告诉我一个地名，我按那个地方帮你找。";
        }
        // Require a short, standalone confirmation so ordinary phrases like “天气好不好”
        // cannot accidentally authorize sending an approximate location to the map service.
        if (!(equalsAny(s, "允许", "同意", "可以", "好", "好的", "行", "继续", "我允许", "我同意", "可以查", "开始查"))) return "";
        Cfg cfg = Cfg.load(c);
        cfg.set("nearbyOsmConsent", true);
        cfg.save(c);
        Sg.writeObj(c, PENDING, new JSONObject());
        return "好，我记下这个授权了。以后你问附近地点时，我会用模糊到约 100 米的位置查询 OpenStreetMap；你再问一次附近吃什么或去哪玩，我就按查到的地点和你明确说过的偏好给建议。";
    }

    private static JSONArray query(double lat, double lon, String kind) throws Exception {
        String q = overpassQuery(lat, lon, kind);
        String body = "data=" + URLEncoder.encode(q, "UTF-8");
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL("https://overpass-api.de/api/interpreter").openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(9000);
            conn.setReadTimeout(16000);
            conn.setDoOutput(true);
            conn.setRequestProperty("User-Agent", "DSH-Pet/2.6.0 Android nearby search");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream os = conn.getOutputStream()) { os.write(bytes); }
            int code = conn.getResponseCode();
            InputStream in = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
            String response = readLimited(in, 1500000);
            if (code < 200 || code >= 300) throw new Exception("地图服务返回 HTTP " + code);
            JSONObject root = new JSONObject(response);
            JSONArray elements = root.optJSONArray("elements");
            JSONArray out = new JSONArray();
            if (elements == null) return out;
            ArrayList<JSONObject> ranked = new ArrayList<>();
            for (int i = 0; i < elements.length(); i++) {
                JSONObject e = elements.optJSONObject(i);
                if (e == null) continue;
                JSONObject tags = e.optJSONObject("tags");
                if (tags == null) continue;
                String name = first(tags, "name", "name:zh", "name:en");
                if (name.trim().isEmpty()) continue;
                double pLat = e.optDouble("lat", e.optJSONObject("center") == null ? 0 : e.optJSONObject("center").optDouble("lat", 0));
                double pLon = e.optDouble("lon", e.optJSONObject("center") == null ? 0 : e.optJSONObject("center").optDouble("lon", 0));
                if (pLat == 0 || pLon == 0) continue;
                float[] dist = new float[1];
                Location.distanceBetween(lat, lon, pLat, pLon, dist);
                double km = Math.round(dist[0] / 10.0) / 100.0;
                String type = typeOf(tags, kind);
                String address = first(tags, "addr:full", "addr:street", "addr:place");
                String number = tags.optString("addr:housenumber", "").trim();
                if (!number.isEmpty() && !address.isEmpty()) address += " " + number;
                JSONObject item = new JSONObject().put("name", name).put("type", type)
                        .put("distanceKmApprox", km).put("address", address)
                        .put("cuisine", tags.optString("cuisine", ""));
                ranked.add(item);
            }
            ranked.sort(Comparator.comparingDouble(a -> a.optDouble("distanceKmApprox", 9999)));
            for (int i = 0; i < Math.min(10, ranked.size()); i++) out.put(ranked.get(i));
            return out;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String overpassQuery(double lat, double lon, String kind) {
        String c = String.format(Locale.US, "%.3f", lat);
        String d = String.format(Locale.US, "%.3f", lon);
        StringBuilder q = new StringBuilder("[out:json][timeout:12];(");
        if ("food".equals(kind)) {
            appendPair(q, "amenity", "^(restaurant|cafe|fast_food|food_court|ice_cream)$", c, d);
            appendPair(q, "shop", "^(bakery|confectionery)$", c, d);
        } else if ("place".equals(kind)) {
            appendPair(q, "tourism", "^(attraction|museum|viewpoint|zoo|theme_park)$", c, d);
            appendPair(q, "leisure", "^park$", c, d);
            appendPair(q, "historic", ".*", c, d);
        } else {
            appendPair(q, "shop", "^(supermarket|convenience|department_store|mall|bookshop|sports|clothes)$", c, d);
        }
        return q.append(");out center tags;").toString();
    }

    private static void appendPair(StringBuilder q, String key, String regex, String lat, String lon) {
        // We only recommend named places; fetching unnamed POIs adds noise and response size.
        q.append("node[\"name\"][\"").append(key).append("\"~\"").append(regex).append("\"](around:3500,").append(lat).append(',').append(lon).append(");")
                .append("way[\"name\"][\"").append(key).append("\"~\"").append(regex).append("\"](around:3500,").append(lat).append(',').append(lon).append(");");
    }

    private static String normalizeKind(String kind) {
        if ("place".equals(kind)) return "place";
        if ("shop".equals(kind)) return "shop";
        return "food";
    }

    private static String typeOf(JSONObject tags, String kind) {
        if ("food".equals(kind)) return tags.optString("cuisine", tags.optString("amenity", tags.optString("shop", "餐饮")));
        if ("place".equals(kind)) return tags.optString("tourism", tags.optString("leisure", tags.optString("historic", "景点")));
        return tags.optString("shop", "商店");
    }

    private static String first(JSONObject o, String... keys) {
        for (String key : keys) {
            String s = o.optString(key, "").trim();
            if (!s.isEmpty()) return s;
        }
        return "";
    }

    private static String readLimited(InputStream in, int max) throws Exception {
        if (in == null) return "";
        try (InputStream stream = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int total = 0;
            for (int n; (n = stream.read(buf)) >= 0;) {
                total += n;
                if (total > max) throw new Exception("地图结果超过读取上限");
                out.write(buf, 0, n);
            }
            return out.toString("UTF-8");
        }
    }

    private static String safeError(Throwable t) {
        String s = t == null ? "未知错误" : t.getMessage();
        if (s == null || s.trim().isEmpty()) return "网络或定位暂时不可用";
        return s.length() > 220 ? s.substring(0, 220) : s;
    }

    private static boolean equalsAny(String text, String... values) {
        for (String value : values) if (text.equals(value)) return true;
        return false;
    }
}
