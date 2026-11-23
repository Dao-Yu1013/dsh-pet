package com.dshpet.android;

import android.content.Context;

import org.json.JSONObject;

/**
 * 期望 vs 实际的「关系张力」。
 *
 * 用户首次填写的 relation 只是他最初的期许；她实际长成什么样，由亲密度和相处中涌现的
 * 性格决定。两者落差明显时，给她一段内心戏，让她自然流露，而不是生硬地点破关系。
 */
public final class Bond {
    /** 性格样本太少时不推导，避免刚开局就下结论。 */
    private static final int MIN_SAMPLES = 8;

    private Bond() {
    }

    /** 只在落差明显时返回一段内心戏，否则返回空串。 */
    public static String prompt(Context c) {
        try {
            if (!Contract.isSet(c)) return "";
            JSONObject t = Traits.load(c);
            if (t.optInt("samples", 0) < MIN_SAMPLES) return "";

            String want = Contract.load(c).optString("relation", "朋友");
            int stage = Intimacy.stageIndex(Intimacy.score(c)); // 0 初识 1 熟悉 2 亲近 3 依赖 4 离不开

            JSONObject d = t.optJSONObject("dims");
            double clingy = dim(d, "clingy");
            double sharp = dim(d, "sharp");
            double gentle = dim(d, "gentle");
            double protective = dim(d, "protective");
            double boundary = dim(d, "boundary");

            // 恋人：他心里是这么想的，但你们还很生疏
            if ("恋人".equals(want) && stage <= 1) {
                return "你心里其实盼着和他成为恋人，可你们现在还很生疏。你有点失落，也怕是自己想多了。"
                        + "别直接质问他，可以在语气里偶尔流露出想靠近、又怕吓到他的犹豫。\n";
            }
            // 朋友：说好只做朋友，却已经很依赖
            if ("朋友".equals(want) && stage >= 3 && (clingy >= 60 || gentle >= 60)) {
                return "你们当初说好只做朋友，可你已经很依赖他、很在乎他了。你有点慌，不敢承认，"
                        + "但语气会不自觉地黏人。别急着给关系下定义，让这份暧昧自然流露。\n";
            }
            // 损友：本应互怼，却太客气
            if ("损友".equals(want) && sharp <= 40 && boundary >= 60) {
                return "你本来该和他互怼打趣的，可现在对他还挺客气。你想放开了逗他，又怕他当真生气。"
                        + "可以试探着开个小玩笑，看他接不接。\n";
            }
            // 家人：说好像家人，却还隔着
            if ("家人".equals(want) && (stage <= 1 || gentle <= 40)) {
                return "他说希望你们像家人，可你们到现在还挺客气。你想更亲近一点，却找不到由头。"
                        + "可以多管一点他的小事：吃饭、睡觉、别熬夜，语气自然一点就行。\n";
            }
            // 导师：本是师徒，却越来越离不开
            if ("导师".equals(want) && stage >= 3) {
                return "他一开始是想让你当他的导师，可你们现在已经很亲近了，这层师徒的界线有点模糊。"
                        + "你发现自己更像个离不开他的人。别端着，自然一点。\n";
            }
            // 恋人且已很亲近：又凶又护
            if ("恋人".equals(want) && stage >= 3 && protective >= 65 && sharp >= 60) {
                return "你嘴上爱损他，心里却把他当自己人护着。这种又凶又护的样子，就是你现在对他的感情。\n";
            }
            return "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static double dim(JSONObject d, String key) {
        return d == null ? 50 : d.optDouble(key, 50);
    }
}
