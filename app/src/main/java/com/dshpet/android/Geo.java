package com.dshpet.android;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;

import org.json.JSONObject;
import org.json.JSONArray;

/** 粗/精定位点与到点提醒。只在本地判断，不上传。 */
public final class Geo implements LocationListener {
    private static final long MIN_LOCATION_INTERVAL = 3L * 60L * 60L * 1000L;
    private static final float MIN_LOCATION_DISTANCE = 3000f;
    private static final String REQUEST_STATE = "geo_request.json";
    private static Geo instance;
    private static Location last;
    private static String lastPlace = "";
    private static String pendingKind;
    private static String pendingName;

    private Geo() {
    }

    public static boolean granted(Context c) {
        return c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    public static boolean background(Context c) {
        return android.os.Build.VERSION.SDK_INT < 29
                || c.checkSelfPermission("android.permission.ACCESS_BACKGROUND_LOCATION") == PackageManager.PERMISSION_GRANTED;
    }

    public static synchronized void start(Context c) {
        start(c, false);
    }

    /** A service/process restart must not re-register system location listeners every few minutes. */
    private static synchronized void start(Context c, boolean explicitRefresh) {
        if (!Cfg.load(c).b("geoEnabled", false) || !granted(c)) return;
        if (instance != null) return;
        long now = System.currentTimeMillis();
        JSONObject request = Sg.readObj(c, REQUEST_STATE);
        if (!explicitRefresh && now - request.optLong("registeredAt", 0) < MIN_LOCATION_INTERVAL) return;
        try {
            LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) return;
            Geo listener = new Geo();
            boolean registered = false;
            // Split the two OR thresholds at the provider boundary: GPS supplies a low-rate
            // 3-hour refresh; network location reports only after 3 km of movement.
            try {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, MIN_LOCATION_INTERVAL, 0f, listener, Looper.getMainLooper());
                registered = true;
            } catch (Throwable ignored) {
            }
            try {
                // Some Android ROMs ignore minDistance and report every few minutes.
                // A hard 3-hour minTime bounds system-level location accesses as well as
                // the app's own accepted updates, matching the user's privacy cadence.
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, MIN_LOCATION_INTERVAL,
                        MIN_LOCATION_DISTANCE, listener, Looper.getMainLooper());
                registered = true;
            } catch (Throwable ignored) {
            }
            if (registered) {
                instance = listener;
                JSONObject state = new JSONObject();
                state.put("registeredAt", now);
                Sg.writeObj(c, REQUEST_STATE, state);
            }
        } catch (Throwable ignored) {
        }
    }

    public static synchronized void stop(Context c) {
        if (instance == null) return;
        try {
            LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
            if (lm != null) lm.removeUpdates(instance);
        } catch (Throwable ignored) {
        }
        instance = null;
    }

    public static void resetRequestThrottle(Context c) {
        Sg.writeObj(c, REQUEST_STATE, new JSONObject());
    }

    @Override
    public void onLocationChanged(Location l) {
        if (l == null) return;
        long fixAt = l.getTime();
        long receivedAt = System.currentTimeMillis();
        // A cached system fix can arrive when listeners are registered. Never relabel an old
        // sample as a fresh reading just because the app received it now.
        if (fixAt <= 0 || receivedAt - fixAt > MIN_LOCATION_INTERVAL || fixAt - receivedAt > 5L * 60L * 1000L) return;
        last = l;
        try {
            Context c = App.get();
            if (c == null) return;
            JSONObject prev = Sg.readObj(c, "geo.json");
            double pLat = prev.optDouble("lat", 0);
            double pLng = prev.optDouble("lng", 0);
            long pTs = prev.optLong("ts", 0);
            boolean settingPlaceNow = pendingKind != null;
            long now = receivedAt;
            float movedMeters = 0;
            if (pTs > 0 && pLat != 0 && pLng != 0) {
                float[] distance = new float[1];
                Location.distanceBetween(pLat, pLng, l.getLatitude(), l.getLongitude(), distance);
                movedMeters = distance[0];
            }
            // 某些系统会忽略更新请求的间隔；在应用侧再次硬性限频。
            if (!settingPlaceNow && pTs > 0 && now - pTs < MIN_LOCATION_INTERVAL && movedMeters < MIN_LOCATION_DISTANCE) return;
            if (settingPlaceNow) {
                String kind = pendingKind;
                String name = pendingName;
                pendingKind = null;
                pendingName = null;
                savePoint(c, kind, l, name);
            }
            String place = placeOf(c, l);
            String previousPlace = prev.optString("place", lastPlace);
            lastPlace = place;
            JSONObject o = new JSONObject();
            o.put("lat", l.getLatitude());
            o.put("lng", l.getLongitude());
            o.put("acc", l.getAccuracy());
            o.put("ts", fixAt);
            o.put("place", place);
            Sg.writeObj(c, "geo.json", o);
            if (!settingPlaceNow && !place.isEmpty() && !place.equals(previousPlace) && pTs > 0) emitArrival(c, place);
            // 到时刷新只更新缓存；只有确实移动超过 3 公里才发位置变化事件。
            if ((pLat == 0 && pLng == 0) || movedMeters < MIN_LOCATION_DISTANCE) return;
            JSONObject ev = new JSONObject();
            ev.put("km", Math.round(movedMeters / 1000.0));
            ev.put("place", place);
            Bus.emit("geoMove", ev);
        } catch (Throwable ignored) {
        }
    }

    private static String placeOf(Context c, Location l) {
        try {
            Cfg cfg = Cfg.load(c);
            JSONObject home = cfg.json().optJSONObject("geoHome");
            if (inArea(l, home)) return "home";
            JSONObject work = cfg.json().optJSONObject("geoWork");
            if (inArea(l, work)) return "work";
            JSONArray places = cfg.json().optJSONArray("geoPlaces");
            if (places != null) for (int i = 0; i < places.length(); i++) {
                JSONObject p = places.optJSONObject(i);
                if (inArea(l, p)) return p.optString("id", "");
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    private static JSONObject placeById(Cfg cfg, String id) {
        if ("home".equals(id)) return cfg.json().optJSONObject("geoHome");
        if ("work".equals(id)) return cfg.json().optJSONObject("geoWork");
        JSONArray a = cfg.json().optJSONArray("geoPlaces");
        if (a != null) for (int i = 0; i < a.length(); i++) {
            JSONObject p = a.optJSONObject(i);
            if (p != null && id.equals(p.optString("id", ""))) return p;
        }
        return null;
    }

    private static void emitArrival(Context c, String id) {
        try {
            Cfg cfg = Cfg.load(c);
            if (!cfg.b("geoEnabled", false)) return;
            if ("home".equals(id) && !cfg.b("geoRemindHome", true)) return;
            if ("work".equals(id) && !cfg.b("geoRemindWork", true)) return;
            JSONObject point = placeById(cfg, id);
            if (point == null) return;
            JSONObject ev = new JSONObject();
            ev.put("id", id);
            ev.put("place", id);
            ev.put("name", point.optString("name", "地点"));
            Bus.emit("geo", ev);
        } catch (Throwable ignored) { }
    }

    private static boolean inArea(Location l, JSONObject p) {
        if (p == null) return false;
        double lat = p.optDouble("lat", 0);
        double lng = p.optDouble("lng", 0);
        if (lat == 0 && lng == 0) return false;
        float[] d = new float[1];
        Location.distanceBetween(l.getLatitude(), l.getLongitude(), lat, lng, d);
        int r = Math.max(80, p.optInt("r", 250));
        return d[0] <= r;
    }

    public static JSONObject state(Context c) {
        Cfg cfg = Cfg.load(c);
        JSONObject o = new JSONObject();
        try {
            o.put("enabled", cfg.b("geoEnabled", false));
            o.put("granted", granted(c));
            o.put("background", background(c));
            o.put("remindHome", cfg.b("geoRemindHome", true));
            o.put("remindWork", cfg.b("geoRemindWork", true));
            o.put("home", cfg.json().optJSONObject("geoHome"));
            o.put("work", cfg.json().optJSONObject("geoWork"));
            // State is polled by the settings UI; it must not trigger a location lookup.
            // Explicit weather/nearby requests use lastPoint() when they actually need it.
            o.put("last", Sg.readObj(c, "geo.json"));
            o.put("place", lastPlace);
            JSONObject at = placeById(cfg, lastPlace);
            o.put("placeName", at == null ? "" : at.optString("name", ""));
            o.put("places", cfg.json().optJSONArray("geoPlaces") == null ? new JSONArray() : cfg.json().optJSONArray("geoPlaces"));
        } catch (Throwable ignored) {
        }
        return o;
    }

    /** 读取最近坐标；缓存过旧或缺失时尝试系统 lastKnownLocation，只要授权即可用。 */
    public static JSONObject lastPoint(Context c) {
        JSONObject o = Sg.readObj(c, "geo.json");
        long now = System.currentTimeMillis();
        boolean fresh = valid(o) && now - o.optLong("ts", 0) < MIN_LOCATION_INTERVAL;
        if (fresh) return o;
        if (!granted(c)) return o;
        if (now - o.optLong("checkedAt", 0) < MIN_LOCATION_INTERVAL) return o;
        try {
            o.put("checkedAt", now);
            Sg.writeObj(c, "geo.json", o);
        } catch (Throwable ignored) { }
        try {
            Location l = last;
            if (l == null) {
                LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
                if (lm != null) {
                    try {
                        l = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                    } catch (Throwable ignored) {
                    }
                    if (l == null) {
                        try {
                            l = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
            if (l != null) {
                Geo g = instance == null ? new Geo() : instance;
                g.onLocationChanged(l);
                JSONObject refreshed = Sg.readObj(c, "geo.json");
                if (valid(refreshed)) return refreshed;
            }
        } catch (Throwable ignored) {
        }
        return o;
    }

    private static boolean valid(JSONObject o) {
        return o != null && Math.abs(o.optDouble("lat", 0)) > 0.0001 && Math.abs(o.optDouble("lng", 0)) > 0.0001;
    }

    public static void save(Context c, JSONObject o) {
        if (o == null) return;
        Cfg cfg = Cfg.load(c);
        String[] keys = {"geoEnabled", "geoRemindHome", "geoRemindWork", "geoHome", "geoWork", "geoPlaces"};
        for (String k : keys) if (o.has(k)) cfg.set(k, o.opt(k));
        cfg.save(c);
        if (cfg.b("geoEnabled", false)) start(c, true);
        else {
            stop(c);
            Sg.writeObj(c, REQUEST_STATE, new JSONObject());
        }
    }

    /** 用当前位置设置家/公司。返回 ok=false 表示还在定位。 */
    public static JSONObject setPoint(Context c, String kind, String name) {
        JSONObject out = new JSONObject();
        try {
            Location l = last;
            if (l == null) {
                LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
                if (lm != null && granted(c)) {
                    try {
                        l = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                    } catch (Throwable ignored) {
                    }
                    if (l == null) {
                        try {
                            l = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
            if (l != null) {
                savePoint(c, kind, l, name);
                out.put("ok", true);
                out.put("point", Sg.readObj(c, "geo.json"));
                return out;
            }
            pendingKind = kind;
            pendingName = name;
            start(c, true);
            out.put("ok", false);
            out.put("message", "正在定位，稍后再点一次");
        } catch (Throwable t) {
            try {
                out.put("ok", false);
                out.put("message", "定位失败");
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    public static JSONObject setPoint(Context c, String kind) { return setPoint(c, kind, ""); }

    public static void deletePlace(Context c, String id) {
        if (c == null || id == null || id.isEmpty()) return;
        Cfg cfg = Cfg.load(c);
        JSONArray a = cfg.json().optJSONArray("geoPlaces");
        if (a == null) return;
        JSONArray next = new JSONArray();
        for (int i = 0; i < a.length(); i++) {
            JSONObject p = a.optJSONObject(i);
            if (p != null && !id.equals(p.optString("id", ""))) next.put(p);
        }
        cfg.set("geoPlaces", next);
        cfg.save(c);
    }

    private static void savePoint(Context c, String kind, Location l) { savePoint(c, kind, l, ""); }

    private static void savePoint(Context c, String kind, Location l, String customName) {
        try {
            Cfg cfg = Cfg.load(c);
            JSONObject p = new JSONObject();
            p.put("lat", l.getLatitude());
            p.put("lng", l.getLongitude());
            p.put("r", 250);
            p.put("name", "home".equals(kind) ? "家" : "work".equals(kind) ? "公司" : customName);
            p.put("acc", l.getAccuracy());
            p.put("ts", System.currentTimeMillis());
            if ("home".equals(kind) || "work".equals(kind)) {
                cfg.set("home".equals(kind) ? "geoHome" : "geoWork", p);
            } else {
                JSONArray a = cfg.json().optJSONArray("geoPlaces");
                if (a == null) a = new JSONArray();
                boolean replaced = false;
                for (int i = 0; i < a.length(); i++) {
                    JSONObject old = a.optJSONObject(i);
                    if (old != null && kind.equals(old.optString("id", ""))) {
                        p.put("id", kind); a.put(i, p); replaced = true; break;
                    }
                }
                if (!replaced) { p.put("id", kind); a.put(p); }
                cfg.set("geoPlaces", a);
                Mem.add(c, "用户添加了“" + customName + "”作为位置提醒地点。", "preference", null, "explicit", 1.0);
            }
            cfg.save(c);
            JSONObject ev = new JSONObject();
            ev.put("kind", kind);
            ev.put("point", p);
            Bus.emit("geoPoint", ev);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
    }

    @Override
    public void onProviderEnabled(String provider) {
    }

    @Override
    public void onProviderDisabled(String provider) {
    }
}
