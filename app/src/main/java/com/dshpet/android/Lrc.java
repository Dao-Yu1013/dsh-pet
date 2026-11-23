package com.dshpet.android;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** LRCLIB 歌词（公开接口，无需 Key）。 */
public final class Lrc {
    public static final class Line {
        public final long ms;
        public final String text;

        Line(long ms, String text) {
            this.ms = ms;
            this.text = text;
        }
    }

    public interface Cb {
        void ok(List<Line> lines);

        void fail();
    }

    private static final Pattern TS = Pattern.compile("\\[(\\d{1,2}):(\\d{1,2})(?:[.:](\\d{1,3}))?]");

    private Lrc() {
    }

    public static void fetch(String title, String artist, Cb cb) {
        fetch(title, artist, 0L, cb);
    }

    /** duration 为当前播放时长（毫秒）；有值时用来排除翻唱、现场版等同名歌词。 */
    public static void fetch(String title, String artist, long duration, Cb cb) {
        if (title == null || title.trim().isEmpty()) {
            cb.fail();
            return;
        }
        new Thread(() -> {
            // 播放器给的歌名常带 (Live)（电影版）feat. 这类后缀，曲库按原名搜不到，
            // 先用原名，再退一步用去掉后缀的干净歌名
            String clean = cleanTitle(title);
            String cleanAr = cleanArtist(artist);
            String[][] tries = {{title, artist}, {clean, cleanAr}};
            List<Line> lines = new ArrayList<>();
            for (String[] q : tries) {
                if (q[0].isEmpty()) continue;
                try {
                    lines = netease(q[0], q[1], duration);
                } catch (Throwable ignored) {
                }
                if (!lines.isEmpty()) break;
            }
            if (lines.isEmpty()) {
                for (String[] q : tries) {
                    if (q[0].isEmpty()) continue;
                    try {
                        lines = lrclib(q[0], q[1], duration);
                    } catch (Throwable ignored) {
                    }
                    if (!lines.isEmpty()) break;
                }
            }
            if (lines.isEmpty()) {
                cb.fail();
            } else {
                cb.ok(lines);
            }
        }, "dsh-lrc").start();
    }

    /** 去掉 (Live)、【...】、- Remastered、feat. xxx 之类的尾巴，提高曲库命中率。 */
    static String cleanTitle(String title) {
        if (title == null) return "";
        String s = title.trim();
        s = s.replaceAll("(?i)\\s*[（(\\[【][^）)\\]】]{0,30}[）)\\]】]", "");
        s = s.replaceAll("(?i)\\s*-\\s*(live|remaster(ed)?|acoustic|demo|伴奏|纯音乐|现场版|live版).*$", "");
        s = s.replaceAll("(?i)\\s*(feat\\.?|ft\\.?)\\s*[^,，\\s].*$", "");
        s = s.replaceAll("\\s+", " ").trim();
        return s;
    }

    static String cleanArtist(String artist) {
        if (artist == null) return "";
        String s = artist.trim();
        // 多歌手用 / & 、 连接时只取第一个，曲库接口对第一个歌手命中率更高
        s = s.split("[/&、,，]")[0].trim();
        return s;
    }

    /* ---------------- 网易云（公开接口，无需 Key） ---------------- */
    private static List<Line> netease(String title, String artist, long duration) {
        String q = URLEncoder.encode((title.trim() + " " + (artist == null ? "" : artist.trim())).trim(),
                StandardCharsets.UTF_8) + "";
        String search = get("https://music.163.com/api/search/get/web?s=" + q
                + "&type=1&offset=0&limit=6", true);
        if (search == null) return new ArrayList<>();
        org.json.JSONArray songs;
        try {
            org.json.JSONObject o = new org.json.JSONObject(search);
            org.json.JSONObject result = o.optJSONObject("result");
            songs = result == null ? null : result.optJSONArray("songs");
        } catch (Throwable t) {
            return new ArrayList<>();
        }
        if (songs == null) return new ArrayList<>();
        for (int k = 0; k < songs.length(); k++) {
            org.json.JSONObject s = songs.optJSONObject(k);
            if (s == null) continue;
            String id = s.optString("id", "");
            if (id.isEmpty()) continue;
            // 歌名、歌手、时长至少要对得上，避免搜到同名翻唱或现场版
            if (!similar(s.optString("name", ""), title)) continue;
            String ar = "";
            org.json.JSONArray artists = s.optJSONArray("artists");
            if (artists != null && artists.length() > 0) {
                org.json.JSONObject a0 = artists.optJSONObject(0);
                ar = a0 == null ? "" : a0.optString("name", "");
            }
            if (!similar(ar, artist)) continue;
            long d = s.optLong("duration", 0);
            if (duration > 0 && d > 0 && Math.abs(d - duration) > 15000L) continue;
            String lyric = get("https://music.163.com/api/song/lyric?id=" + id + "&lv=-1&kv=-1&tv=-1", true);
            if (lyric == null) continue;
            try {
                org.json.JSONObject lo = new org.json.JSONObject(lyric);
                org.json.JSONObject lrc = lo.optJSONObject("lrc");
                List<Line> lines = parseSynced(lrc == null ? "" : lrc.optString("lyric", ""));
                if (!lines.isEmpty()) return lines;
                org.json.JSONObject klyric = lo.optJSONObject("klyric");
                lines = parseSynced(klyric == null ? "" : klyric.optString("lyric", ""));
                if (!lines.isEmpty()) return lines;
            } catch (Throwable ignored) {
            }
        }
        return new ArrayList<>();
    }

    private static String norm(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT)
                .replaceAll("\\([^)]*\\)|（[^）]*）|\\[[^]]*]", "")
                .replaceAll("[\\s\\-_.·、,，'\"“”‘’]", "");
    }

    private static boolean similar(String a, String b) {
        String x = norm(a);
        String y = norm(b);
        return x.isEmpty() || y.isEmpty() || x.contains(y) || y.contains(x);
    }

    /* ---------------- LRCLIB（海外曲库兜底） ---------------- */
    private static List<Line> lrclib(String title, String artist, long duration) {
        List<Line> lines = new ArrayList<>();
        try {
            String t = URLEncoder.encode(title.trim(), StandardCharsets.UTF_8).replace("+", "%20");
            String a = URLEncoder.encode(artist == null ? "" : artist.trim(), StandardCharsets.UTF_8).replace("+", "%20");
            String json = get("https://lrclib.net/api/get?track_name=" + t + "&artist_name=" + a, false);
            if (json != null) {
                org.json.JSONObject o = new org.json.JSONObject(json);
                long sec = o.optLong("duration", 0);
                boolean ok = duration <= 0 || sec <= 0 || Math.abs(sec * 1000L - duration) <= 15000L;
                if (ok) {
                    lines = parseSynced(o.optString("syncedLyrics", ""));
                    if (lines.isEmpty()) lines = parsePlain(o.optString("plainLyrics", ""));
                }
            }
            if (lines.isEmpty()) {
                String s = get("https://lrclib.net/api/search?track_name=" + t + "&artist_name=" + a, false);
                if (s != null) {
                    org.json.JSONArray arr = new org.json.JSONArray(s);
                    for (int k = 0; k < arr.length(); k++) {
                        org.json.JSONObject o = arr.optJSONObject(k);
                        if (o == null) continue;
                        if (!similar(o.optString("trackName", ""), title)) continue;
                        if (!similar(o.optString("artistName", ""), artist)) continue;
                        long sec = o.optLong("duration", 0);
                        if (duration > 0 && sec > 0 && Math.abs(sec * 1000L - duration) > 15000L) continue;
                        List<Line> p = parseSynced(o.optString("syncedLyrics", ""));
                        if (p.isEmpty()) p = parsePlain(o.optString("plainLyrics", ""));
                        if (!p.isEmpty()) {
                            lines = p;
                            break;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return lines;
    }

    private static String get(String url, boolean netease) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(6000);
            c.setRequestProperty("User-Agent", netease
                    ? "Mozilla/5.0 (Linux; Android 13) DSH-Pet/2.0"
                    : "DSH-Pet/2.0 (lyrics)");
            if (netease) {
                c.setRequestProperty("Referer", "https://music.163.com/");
                c.setRequestProperty("Cookie", "appver=2.0.2");
            } else {
                c.setRequestProperty("Accept", "application/json");
            }
            int code = c.getResponseCode();
            if (code != 200) {
                c.disconnect();
                return null;
            }
            InputStream in = c.getInputStream();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            c.disconnect();
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    public static List<Line> parseSynced(String lrc) {
        List<Line> out = new ArrayList<>();
        if (lrc == null || lrc.trim().isEmpty()) return out;
        for (String raw : lrc.split("\n")) {
            Matcher m = TS.matcher(raw);
            List<Long> times = new ArrayList<>();
            int end = 0;
            while (m.find()) {
                try {
                    int min = Integer.parseInt(m.group(1));
                    int sec = Integer.parseInt(m.group(2));
                    String frac = m.group(3);
                    long ms = 0;
                    if (frac != null) {
                        if (frac.length() == 1) ms = Integer.parseInt(frac) * 100;
                        else if (frac.length() == 2) ms = Integer.parseInt(frac) * 10;
                        else ms = Integer.parseInt(frac.length() > 3 ? frac.substring(0, 3) : frac);
                    }
                    times.add(min * 60000L + sec * 1000L + ms);
                    end = m.end();
                } catch (Throwable ignored) {
                }
            }
            String text = raw.substring(end).trim();
            if (text.isEmpty() || isCredit(text)) continue;
            for (Long t : times) out.add(new Line(t, text));
        }
        out.sort((a, b) -> Long.compare(a.ms, b.ms));
        return out;
    }

    /** 词曲作者、制作团队这类信息不是歌词，不要显示在气泡里。 */
    private static boolean isCredit(String s) {
        String[] keys = {"作词", "作曲", "编曲", "混音", "母带", "制作人", "出品", "监制", "录音",
                "吉他", "和声", "统筹", "词：", "曲：", "OP", "SP", "混缩", "封面", "企划", "发行", "录音师"};
        for (String k : keys) {
            if (s.startsWith(k)) return true;
        }
        return false;
    }

    /** 没有时间轴的歌词按每 4 秒一句轮播。 */
    public static List<Line> parsePlain(String plain) {
        List<Line> out = new ArrayList<>();
        if (plain == null || plain.trim().isEmpty()) return out;
        int i = 0;
        for (String raw : plain.split("\n")) {
            String s = raw.trim();
            if (s.isEmpty()) continue;
            out.add(new Line(i * 4000L, s));
            i++;
            if (i > 200) break;
        }
        return out;
    }
}
