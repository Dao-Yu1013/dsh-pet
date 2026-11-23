package com.dshpet.android;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 自动记账：从支付类通知里识别金额入账，也可以手动记一笔；
 * 设了月度预算后，超支时由桌宠说一句。数据只存 S-G/ledger.jsonl。
 */
public final class Ledger {
    private static final String FILE = "ledger.jsonl";
    private static final String CFG = "ledger_cfg.json";
    private static final Object LOCK = new Object();
    /** 金额必须带钱符号或“元/块”，避免把验证码、订单号当成金额。 */
    private static final Pattern AMOUNT = Pattern.compile(
            "(?:¥|￥|RMB|人民币)\\s*(\\d{1,7}(?:\\.\\d{1,2})?)|(\\d{1,7}(?:\\.\\d{1,2})?)\\s*(?:元|块钱|块)");
    private static final String[] PAY_WORDS = {"支付成功", "付款成功", "已支付", "支付金额", "付款金额",
            "消费", "扣款", "支出", "收款", "到账", "收入", "退款", "转账", "入账"};
    private static final String[] INCOME_WORDS = {"收款", "收入", "退款", "到账", "入账", "转入"};

    private Ledger() {
    }

    /* ---------------- 通知识别 ---------------- */

    /** 只处理支付/银行类通知，且必须同时命中“支付动作 + 明确金额”。 */
    public static void onNotification(Context c, String pkg, String app, String title, String text) {
        try {
            JSONObject cfg = cfg(c);
            if (!cfg.optBoolean("auto", true)) return;
            String all = (title == null ? "" : title) + " " + (text == null ? "" : text);
            if (!payApp(pkg, all)) return;
            if (!contains(all, PAY_WORDS)) return;
            double amount = amount(all);
            if (amount <= 0) return;
            boolean income = contains(all, INCOME_WORDS)
                    && !contains(all, new String[]{"支出", "付款", "消费", "扣款"});
            String cat = category(pkg, all, income);
            String note = (app == null ? "" : app) + " · " + clean(title, 24);
            String detail = clean(text, 36);
            if (!detail.isEmpty()) note = note + " " + detail;
            if (recentDuplicate(c, pkg, amount)) return;
            JSONObject r = add(c, amount, income ? "income" : "expense", cat, note, "auto", pkg);
            String over = r.optString("over", "");
            if (!over.isEmpty()) PetSvc.send(c, PetSvc.A_SAY, "text", over);
        } catch (Throwable ignored) {
        }
    }

    private static boolean payApp(String pkg, String all) {
        String p = pkg == null ? "" : pkg.toLowerCase(Locale.US);
        if (p.contains("alipay") || p.equals("com.tencent.mm") || p.contains("unionpay")
                || p.contains("bank") || p.contains("chinamworld") || p.contains("icbc")
                || p.contains("jd") || p.contains("taobao") || p.contains("tmall")
                || p.contains("meituan") || p.contains("eleme") || p.contains("sankuai")
                || p.contains("didi")) return true;
        return contains(all, new String[]{"微信支付", "支付宝", "云闪付", "银行", "信用卡", "储蓄卡"});
    }

    private static double amount(String s) {
        try {
            Matcher m = AMOUNT.matcher(s);
            while (m.find()) {
                String g = m.group(1) != null ? m.group(1) : m.group(2);
                double v = Double.parseDouble(g);
                if (v >= 0.01 && v <= 1000000) return Math.round(v * 100) / 100.0;
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private static String category(String pkg, String all, boolean income) {
        String p = pkg == null ? "" : pkg.toLowerCase(Locale.US);
        if (income) {
            if (contains(all, new String[]{"退款"})) return "退款";
            if (contains(all, new String[]{"工资", "薪"})) return "工资";
            return "收入";
        }
        if (contains(all, new String[]{"美团", "饿了么", "外卖", "餐", "饭", "咖啡", "奶茶", "肯德基", "麦当劳", "星巴克", "瑞幸"})
                || p.contains("meituan") || p.contains("eleme") || p.contains("sankuai")) return "餐饮";
        if (contains(all, new String[]{"淘宝", "天猫", "京东", "拼多多", "商城", "超市", "购买", "订单"})
                || p.contains("taobao") || p.contains("jd") || p.contains("pinduoduo")) return "购物";
        if (contains(all, new String[]{"滴滴", "打车", "地铁", "公交", "加油", "停车", "高铁", "机票", "出行"})
                || p.contains("didi")) return "出行";
        if (contains(all, new String[]{"话费", "电费", "水费", "燃气", "物业", "充值", "缴费"})) return "生活";
        if (contains(all, new String[]{"电影", "游戏", "会员", "音乐", "视频", "演出"})) return "娱乐";
        if (contains(all, new String[]{"医院", "药", "挂号", "体检"})) return "医疗";
        if (contains(all, new String[]{"红包", "转账"})) return "转账";
        return "其他";
    }

    private static boolean recentDuplicate(Context c, String pkg, double amount) {
        List<JSONObject> list = tail(c, 30);
        long now = System.currentTimeMillis();
        for (JSONObject o : list) {
            if (!"auto".equals(o.optString("source", ""))) continue;
            if (now - o.optLong("ts", 0) > 180000L) continue;
            if (Math.abs(o.optDouble("amount", 0) - amount) > 0.001) continue;
            String p = o.optString("pkg", "");
            if (p.equals(pkg)) return true;
        }
        return false;
    }

    /* ---------------- 存取 ---------------- */

    public static JSONObject add(Context c, double amount, String type, String cat, String note,
                                 String source, String pkg) {
        JSONObject r = new JSONObject();
        try {
            double v = Math.round(amount * 100) / 100.0;
            if (v <= 0) {
                r.put("ok", false);
                r.put("error", "金额不对");
                return r;
            }
            JSONObject o = new JSONObject();
            o.put("id", "l" + System.currentTimeMillis() + (int) (Math.random() * 900 + 100));
            o.put("ts", System.currentTimeMillis());
            o.put("amount", v);
            o.put("type", "income".equals(type) ? "income" : "expense");
            o.put("cat", cat == null || cat.isEmpty() ? "其他" : cat);
            o.put("note", clean(note, 60));
            o.put("source", source == null ? "manual" : source);
            if (pkg != null && !pkg.isEmpty()) o.put("pkg", pkg);
            String over = "";
            synchronized (LOCK) {
                Sg.appendLine(c, FILE, o);
                if ("expense".equals(o.optString("type", ""))) over = alertIfOver(c);
            }
            r.put("ok", true);
            r.put("id", o.optString("id", ""));
            r.put("over", over);
        } catch (Throwable t) {
            try {
                r.put("ok", false);
                r.put("error", "记账失败");
            } catch (Throwable ignored) {
            }
        }
        return r;
    }

    public static void delete(Context c, String id) {
        if (id == null || id.isEmpty()) return;
        synchronized (LOCK) {
            try {
                String s = Sg.read(Sg.file(c, FILE), "");
                StringBuilder sb = new StringBuilder();
                for (String line : s.split("\n")) {
                    if (line.trim().isEmpty()) continue;
                    try {
                        JSONObject o = new JSONObject(line);
                        if (id.equals(o.optString("id", ""))) continue;
                    } catch (Throwable ignored) {
                    }
                    sb.append(line).append('\n');
                }
                Sg.write(Sg.file(c, FILE), sb.toString());
            } catch (Throwable ignored) {
            }
        }
    }

    public static JSONObject cfg(Context c) {
        JSONObject o = Sg.readObj(c, CFG);
        if (!o.has("auto")) {
            try {
                o.put("auto", true);
            } catch (Throwable ignored) {
            }
        }
        return o;
    }

    public static void saveCfg(Context c, double monthly, boolean auto) {
        synchronized (LOCK) {
            try {
                JSONObject o = cfg(c);
                o.put("monthly", Math.max(0, Math.round(monthly * 100) / 100.0));
                o.put("auto", auto);
                Sg.writeObj(c, CFG, o);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 页面数据：本月/今日支出、预算、最近记录。 */
    public static JSONObject state(Context c) {
        JSONObject out = new JSONObject();
        try {
            synchronized (LOCK) {
                JSONObject cfg = cfg(c);
                double monthly = cfg.optDouble("monthly", 0);
                double[] sum = sums(c);
                JSONArray items = new JSONArray();
                List<JSONObject> list = tail(c, 60);
                for (int i = list.size() - 1; i >= 0; i--) items.put(list.get(i));
                out.put("month", monthKey(System.currentTimeMillis()));
                out.put("monthSpent", sum[0]);
                out.put("todaySpent", sum[1]);
                out.put("budget", monthly);
                out.put("auto", cfg.optBoolean("auto", true));
                out.put("items", items);
                out.put("over", monthly > 0 && sum[0] > monthly);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** 点击账本里的一笔时，给她一句可读的确认。 */
    public static String describe(JSONObject o) {
        if (o == null) return "";
        double v = o.optDouble("amount", 0);
        String type = "income".equals(o.optString("type", "")) ? "收入" : "支出";
        return type + " " + fmt(v) + " 元 · " + o.optString("cat", "其他");
    }

    public static String fmt(double v) {
        if (Math.abs(v - Math.round(v)) < 0.005) return String.valueOf(Math.round(v));
        return String.format(Locale.US, "%.2f", v);
    }

    /* ---------------- 汇总 ---------------- */

    private static double[] sums(Context c) {
        double month = 0, today = 0;
        long now = System.currentTimeMillis();
        String mk = monthKey(now);
        String day = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US)
                .format(new java.util.Date(now));
        for (JSONObject o : all(c)) {
            if (!"expense".equals(o.optString("type", "expense"))) continue;
            long ts = o.optLong("ts", 0);
            if (!mk.equals(monthKey(ts))) continue;
            double v = o.optDouble("amount", 0);
            month += v;
            if (day.equals(new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US)
                    .format(new java.util.Date(ts)))) today += v;
        }
        return new double[]{Math.round(month * 100) / 100.0, Math.round(today * 100) / 100.0};
    }

    private static String alertIfOver(Context c) {
        try {
            JSONObject cfg = cfg(c);
            double budget = cfg.optDouble("monthly", 0);
            if (budget <= 0) return "";
            double spent = sums(c)[0];
            if (spent <= budget) return "";
            String month = monthKey(System.currentTimeMillis());
            if (month.equals(cfg.optString("alertMonth", ""))) return "";
            cfg.put("alertMonth", month);
            Sg.writeObj(c, CFG, cfg);
            return "这个月花超预算了…已经花了 " + fmt(spent) + " 元，预算 " + fmt(budget)
                    + " 元。接下来我帮你盯着点。";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static List<JSONObject> all(Context c) {
        ArrayList<JSONObject> out = new ArrayList<>();
        try {
            String s = Sg.read(Sg.file(c, FILE), "");
            for (String line : s.split("\n")) {
                if (line.trim().isEmpty()) continue;
                try {
                    out.add(new JSONObject(line));
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static List<JSONObject> tail(Context c, int n) {
        List<JSONObject> all = all(c);
        return all.subList(Math.max(0, all.size() - n), all.size());
    }

    private static String monthKey(long ts) {
        return new java.text.SimpleDateFormat("yyyy-MM", Locale.US).format(new java.util.Date(ts));
    }

    private static boolean contains(String s, String[] keys) {
        if (s == null) return false;
        for (String k : keys) {
            if (s.contains(k)) return true;
        }
        return false;
    }

    private static String clean(String s, int max) {
        if (s == null) return "";
        String t = s.replace("\r", "").replace("\n", " ").trim();
        return t.length() > max ? t.substring(0, max) : t;
    }
}
