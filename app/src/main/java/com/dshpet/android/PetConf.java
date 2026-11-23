package com.dshpet.android;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 解析 assets/pet-config.json。 */
public final class PetConf {
    public static final double REF_W = 462.0;

    public static final class Spec {
        public final String id, name, corner;
        public final int size, marginX, marginY;

        Spec(String id, String name, int size, String corner, int mx, int my) {
            this.id = id;
            this.name = name;
            this.size = size;
            this.corner = corner;
            this.marginX = mx;
            this.marginY = my;
        }
    }

    public static final class Move {
        public final String slug;
        public final double min, max;

        Move(String slug, double min, double max) {
            this.slug = slug;
            this.min = min;
            this.max = max;
        }
    }

    public static final class Cat {
        public final String id;
        public final int weight;
        public final List<String> actions;
        public final boolean noMirror;

        Cat(String id, int weight, List<String> actions, boolean noMirror) {
            this.id = id;
            this.weight = weight;
            this.actions = actions;
            this.noMirror = noMirror;
        }
    }

    public final List<Spec> pets = new ArrayList<>();
    public final Map<String, String> names = new HashMap<>();
    public final List<String> idle = new ArrayList<>();
    public final List<String> turn = new ArrayList<>();
    public final List<String> drag = new ArrayList<>();
    public final List<String> clicks = new ArrayList<>();
    public final List<Move> moves = new ArrayList<>();
    public final List<Cat> cats = new ArrayList<>();
    public double moveMargin = 20;
    public int wIdle = 10, wTurn = 5, wMove = 5;
    public Phys.P physics = new Phys.P();

    public static PetConf load(Context c) {
        PetConf p = new PetConf();
        try {
            // 加固包里配置是密文，WebUI 会从解密目录取；-Fast 包自动回退 assets
            byte[] raw = WebUI.read(c, "pet-config.json");
            if (raw.length == 0) {
                try (InputStream in = c.getAssets().open("pet-config.json")) {
                    raw = new byte[in.available()];
                    int off = 0;
                    while (off < raw.length) {
                        int n = in.read(raw, off, raw.length - off);
                        if (n < 0) break;
                        off += n;
                    }
                }
            }
            JSONObject o = new JSONObject(new String(raw, StandardCharsets.UTF_8));
            JSONArray pets = o.optJSONArray("pets");
            if (pets == null) pets = new JSONArray();
            for (int k = 0; k < pets.length(); k++) {
                JSONObject x = pets.optJSONObject(k);
                if (x == null) continue;
                JSONObject pos = x.optJSONObject("position");
                if (pos == null) pos = new JSONObject();
                p.pets.add(new Spec(
                        x.optString("id", "main" + k),
                        x.optString("name", "蓝毛小女仆"),
                        x.optInt("size", 150),
                        pos.optString("corner", "top-right"),
                        pos.optInt("marginX", 24),
                        pos.optInt("marginY", 100)));
            }
            JSONObject names = o.optJSONObject("names");
            if (names != null) {
                java.util.Iterator<String> it = names.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    p.names.put(k, names.optString(k, k));
                }
            }
            JSONObject a = o.optJSONObject("animations");
            if (a != null) {
                fill(a.optJSONArray("idle"), p.idle);
                fill(a.optJSONArray("turn"), p.turn);
                fill(a.optJSONArray("drag"), p.drag);
                fill(a.optJSONArray("clicks"), p.clicks);
                JSONObject mv = a.optJSONObject("moves");
                if (mv != null) {
                    JSONObject def = mv.optJSONObject("default");
                    if (def != null) p.moveMargin = def.optDouble("margin", 20);
                    double dmin = def == null ? 60 : def.optDouble("minDist", 60);
                    double dmax = def == null ? 240 : def.optDouble("maxDist", 240);
                    JSONArray acts = mv.optJSONArray("actions");
                    if (acts != null) {
                        for (int k = 0; k < acts.length(); k++) {
                            JSONObject x = acts.optJSONObject(k);
                            if (x == null) continue;
                            String slug = x.optString("name", "");
                            if (slug.isEmpty()) continue;
                            JSONArray pa = x.optJSONArray("params");
                            JSONObject pao = x.optJSONObject("params");
                            double mn = x.optDouble("minDist", dmin);
                            double mx = x.optDouble("maxDist", dmax);
                            if (pao != null) {
                                mn = pao.optDouble("minDist", mn);
                                mx = pao.optDouble("maxDist", mx);
                            }
                            p.moves.add(new Move(slug, mn, mx));
                        }
                    }
                }
                JSONArray cats = a.optJSONArray("categories");
                if (cats != null) {
                    for (int k = 0; k < cats.length(); k++) {
                        JSONObject x = cats.optJSONObject(k);
                        if (x == null) continue;
                        List<String> list = new ArrayList<>();
                        fill(x.optJSONArray("actions"), list);
                        p.cats.add(new Cat(x.optString("id", "cat" + k), x.optInt("weight", 0), list, x.optBoolean("noMirror", false)));
                    }
                }
            }
            JSONObject w = o.optJSONObject("animationWeights");
            if (w != null) {
                p.wIdle = w.optInt("idle", 10);
                p.wTurn = w.optInt("turn", 5);
                p.wMove = w.optInt("move", 5);
            }
            JSONObject ph = o.optJSONObject("physics");
            if (ph != null) {
                p.physics = new Phys.P();
                p.physics.gravity = ph.optDouble("gravity", 1400);
                p.physics.restitution = ph.optDouble("restitution", 0.78);
                p.physics.friction = ph.optDouble("groundFriction", 2.5);
                p.physics.ceiling = ph.optBoolean("ceilingBounce", true);
                p.physics.power = ph.optDouble("throwPower", 1);
            }
        } catch (Throwable t) {
            try {
                p.pets.add(new Spec("main", "蓝毛小女仆", 150, "top-right", 24, 100));
            } catch (Throwable ignored) {
            }
        }
        if (p.pets.isEmpty()) p.pets.add(new Spec("main", "蓝毛小女仆", 150, "top-right", 24, 100));
        if (p.idle.isEmpty()) p.idle.add("a032");
        if (p.clicks.isEmpty()) p.clicks.add("a036");
        return p;
    }

    private static void fill(JSONArray a, List<String> out) {
        if (a == null) return;
        for (int k = 0; k < a.length(); k++) {
            String s = a.optString(k, "");
            if (!s.isEmpty()) out.add(s);
        }
    }

    public String nameOf(String slug) {
        String s = names.get(slug);
        return s == null ? slug : s;
    }
}
