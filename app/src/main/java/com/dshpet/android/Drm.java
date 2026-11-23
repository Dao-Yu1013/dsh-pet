package com.dshpet.android;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 微软 Edge「大声朗读」的 Sec-MS-GEC 签名（移植自 edge-tts 的 drm.py）。
 * 2026 年起服务端要求 v2：16 位 CS64 + 64 位改版 SHA-256，
 * 旧的纯 SHA-256 会被 403 拒绝。
 */
final class Drm {
    static final String CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4";
    /** 必须与 UA 的主版本一致，服务端会校验。 */
    static final String CHROMIUM = "143.0.3650.75";
    static final String GEC_VERSION = "2-" + CHROMIUM;
    static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0";

    private static final long MASK = 0xFFFFFFFFL;

    private static final long[] INITIAL = {
            0x6A09E667L, 0xBB67AE85L, 0x3C6EF372L, 0xA54FF53AL,
            0x510E527FL, 0x9B05688CL, 0x1F83D9ABL, 0x5BE0CD19L,
    };
    private static final long[] STATE = {
            0xFD9FBEA2L, 0x91243CE7L, 0xBE499094L, 0xCD2E1E0AL,
            0x6C006F49L, 0x04BD35ABL, 0xFEB0A7E4L, 0x03EFFBD2L,
    };
    private static final long[] SCHEDULE = {
            0xEB24BF9EL, 0x1558691FL, 0xA0C09EE2L, 0x0CFA32BAL,
            0xA622EE8CL, 0xD1DD9491L, 0xC0DD25A0L, 0xF9E70AB6L,
            0xF0C31573L, 0x7058CE4EL, 0x7188E606L, 0x00E5AB33L,
            0xD258B060L, 0x72766564L,
    };
    private static final long[] ROUND = {
            0x428A2F98L, 0x71374491L, 0xB5C0FBCFL, 0xE9B5DBA5L,
            0x3956C25BL, 0x59F111F1L, 0x923F82A4L, 0xAB1C5ED5L,
            0xD807AA98L, 0x12835B01L, 0x243185BEL, 0x550C7DC3L,
            0x72BE5D74L, 0x80DEB1FEL, 0x9BDC06A7L, 0xC19BF174L,
            0xE49B69C1L, 0xEFBE4786L, 0x0FC19DC6L, 0x240CA1CCL,
            0x2DE92C6FL, 0x4A7484AAL, 0x5CB0A9DCL, 0x76F988DAL,
            0x983E5152L, 0xA831C66DL, 0xB00327C8L, 0xBF597FC7L,
            0xC6E00BF3L, 0xD5A79147L, 0x06CA6351L, 0x14292967L,
            0x27B70A85L, 0x2E1B2138L, 0x4D2C6DFCL, 0x53380D13L,
            0x650A7354L, 0x766A0ABBL, 0x81C2C92EL, 0x92722C85L,
            0xA2BFE8A1L, 0xA81A664BL, 0xC24B8B70L, 0xC76C51A3L,
            0xD192E819L, 0xD6990624L, 0xF40E3585L, 0x106AA070L,
            0x19A4C116L, 0x1E376C08L, 0x2748774CL, 0x34B0BCB5L,
            0x391C0CB3L, 0x4ED8AA4AL, 0x5B9CCA4FL, 0x682E6FF3L,
            0x748F82EEL, 0x78A5636FL, 0x84C87814L, 0x8CC70208L,
            0x90BEFFFAL, 0xA4506CEBL, 0xBEF9A3F7L, 0xC67178F2L,
    };
    private static final long ADJUSTMENT = 0xC9DC06A7L;
    /** 第 14 轮常量被替换成 ADJUSTMENT，其余与 ROUND 相同。 */
    private static final long[] BLOCK = new long[64];
    private static final long SIGMA0_ADJUSTMENT = 0x059C0B80L;
    private static final long SIGMA1_ADJUSTMENT = 0x000B92C0L;

    private static final long[] COEF = {
            0xE82E91B1L, 0x44DE24C6L, 0x3BEB65BCL, 0xC89C5933L,
            0x8D1EC211L, 0x314EF5D5L, 0xE92491B1L, 0xF0A5B9D5L,
            0x06064CF3L, 0xDA79FA60L, 0xBED20560L, 0x89F9C9F8L,
            0x35FA38C6L, 0xCB49E3C6L,
    };
    private static final long[] OFFSET = {
            0xB32700D0L, 0x88DFF51DL, 0x29514038L, 0xB328CBDFL,
            0xD405B4D5L, 0x26E55F3CL, 0x607801C6L, 0x82B2C1FBL,
            0xFA51A3DAL, 0x207CC464L, 0x3C3C74F3L, 0xD2D14B61L,
            0xBB04A2C1L, 0xC5626167L,
    };
    private static final long CS64_FACTOR = 0x82893933L;
    private static final long CS64_ADJUSTMENT0 = 0xF2F90B1AL;
    private static final long CS64_ADJUSTMENT1 = 0x0B1A0000L;

    static {
        System.arraycopy(ROUND, 0, BLOCK, 0, 64);
        BLOCK[14] = ADJUSTMENT;
    }

    private Drm() {
    }

    /* ---------------- 对外 ---------------- */

    /** 当前时间的 v2 签名，格式 16 位 hex + '-' + 64 位 hex。 */
    static String gec() {
        return gec(System.currentTimeMillis() / 1000.0);
    }

    static String gec(double unixSeconds) {
        double ticks = unixSeconds + 11644473600.0;
        ticks -= ticks % 300.0;
        ticks *= 10000000.0;
        byte[] data = (String.format(Locale.US, "%.0f", ticks) + CLIENT_TOKEN)
                .getBytes(StandardCharsets.US_ASCII);
        long[] digest = sha256Digest(data);
        long[] cs = cs64Digest(data, digest);
        return hexLe(cs[0]) + hexLe(cs[1]) + "-" + hexBe(digest);
    }

    /** 随机的 32 位大写 hex，作为 Cookie 里的 muid。 */
    static String muid() {
        byte[] b = new byte[16];
        new java.security.SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder(32);
        for (byte x : b) sb.append(String.format(Locale.US, "%02X", x & 0xFF));
        return sb.toString();
    }

    /* ---------------- SHA-256 改版 ---------------- */

    private static long[] sha256Init(byte[] data) {
        long[] w = new long[64];
        w[14] = ((data[0] & 0xFFL) << 16) | ((data[1] & 0xFFL) << 8) | (data[2] & 0xFFL);
        w[15] = ((data[3] & 0xFFL) << 24) | ((data[4] & 0xFFL) << 16)
                | ((data[5] & 0xFFL) << 8) | (data[6] & 0xFFL);
        for (int i = 16; i < 30; i++) {
            long value = SCHEDULE[i - 16];
            if (i - 2 >= 14) {
                value += (i == 16)
                        ? (sigma1(w[i - 2]) ^ SIGMA1_ADJUSTMENT)
                        : sigma1(w[i - 2]);
            }
            if (i - 7 >= 14) value += w[i - 7];
            if (i - 15 >= 14) {
                value += (i == 29)
                        ? (sigma0(w[i - 15]) ^ SIGMA0_ADJUSTMENT)
                        : sigma0(w[i - 15]);
            }
            w[i] = value & MASK;
        }
        w[30] = (sigma1(w[28]) + w[23] + sigma0(w[15]) + w[14]
                + ADJUSTMENT - ROUND[14]) & MASK;
        expand(w, 31);
        long[] work = rounds(STATE, w, 14);
        long[] out = new long[8];
        for (int i = 0; i < 8; i++) out[i] = (INITIAL[i] + work[i]) & MASK;
        return out;
    }

    private static long[] sha256Digest(byte[] data) {
        int length = 57 + data.length;
        int pad = (((55 - length) % 64) + 64) % 64;
        int head = data.length - 7;
        byte[] buf = new byte[head + 1 + pad + 8];
        System.arraycopy(data, 7, buf, 0, head);
        buf[head] = (byte) 0x80;
        long bits = (long) length * 8L;
        for (int i = 0; i < 8; i++) buf[head + 1 + pad + i] = (byte) (bits >>> (56 - 8 * i));
        long[] st = sha256Init(data);
        for (int off = 0; off < buf.length; off += 64) {
            long[] w = new long[64];
            for (int i = 0; i < 16; i++) {
                int b = off + i * 4;
                w[i] = ((buf[b] & 0xFFL) << 24) | ((buf[b + 1] & 0xFFL) << 16)
                        | ((buf[b + 2] & 0xFFL) << 8) | (buf[b + 3] & 0xFFL);
            }
            expand(w, 16);
            long[] work = rounds(st, w, 0);
            for (int i = 0; i < 8; i++) st[i] = (st[i] + work[i]) & MASK;
        }
        return st;
    }

    private static long[] rounds(long[] st, long[] w, int firstRound) {
        long a = st[0], b = st[1], c = st[2], d = st[3];
        long e = st[4], f = st[5], g = st[6], h = st[7];
        long[] k = firstRound == 14 ? BLOCK : ROUND;
        int aligned = Math.min(64, ((firstRound + 7) / 8) * 8);
        for (int i = firstRound; i < aligned; i++) {
            long rotE = e * 0x100000001L;
            long rotA = a * 0x100000001L;
            long t1 = h + ((rotE >>> 6) ^ (rotE >>> 11) ^ (rotE >>> 25))
                    + (g ^ (e & (f ^ g))) + k[i] + w[i];
            long t2 = ((rotA >>> 2) ^ (rotA >>> 13) ^ (rotA >>> 22))
                    + ((a & b) | (c & (a | b)));
            long na = (t1 + t2) & MASK;
            h = g;
            g = f;
            f = e;
            e = (d + t1) & MASK;
            d = c;
            c = b;
            b = a;
            a = na;
        }
        // 每 8 轮寄存器角色回到原位：只更新两个寄存器，省掉搬移
        for (int i = aligned; i < 64; i += 8) {
            long re = e * 0x100000001L, ra = a * 0x100000001L;
            long t1 = h + ((re >>> 6) ^ (re >>> 11) ^ (re >>> 25))
                    + (g ^ (e & (f ^ g))) + k[i] + w[i];
            d = (d + t1) & MASK;
            h = (t1 + ((ra >>> 2) ^ (ra >>> 13) ^ (ra >>> 22))
                    + ((a & b) | (c & (a | b)))) & MASK;

            re = d * 0x100000001L;
            ra = h * 0x100000001L;
            t1 = g + ((re >>> 6) ^ (re >>> 11) ^ (re >>> 25))
                    + (f ^ (d & (e ^ f))) + k[i + 1] + w[i + 1];
            c = (c + t1) & MASK;
            g = (t1 + ((ra >>> 2) ^ (ra >>> 13) ^ (ra >>> 22))
                    + ((h & a) | (b & (h | a)))) & MASK;

            re = c * 0x100000001L;
            ra = g * 0x100000001L;
            t1 = f + ((re >>> 6) ^ (re >>> 11) ^ (re >>> 25))
                    + (e ^ (c & (d ^ e))) + k[i + 2] + w[i + 2];
            b = (b + t1) & MASK;
            f = (t1 + ((ra >>> 2) ^ (ra >>> 13) ^ (ra >>> 22))
                    + ((g & h) | (a & (g | h)))) & MASK;

            re = b * 0x100000001L;
            ra = f * 0x100000001L;
            t1 = e + ((re >>> 6) ^ (re >>> 11) ^ (re >>> 25))
                    + (d ^ (b & (c ^ d))) + k[i + 3] + w[i + 3];
            a = (a + t1) & MASK;
            e = (t1 + ((ra >>> 2) ^ (ra >>> 13) ^ (ra >>> 22))
                    + ((f & g) | (h & (f | g)))) & MASK;

            re = a * 0x100000001L;
            ra = e * 0x100000001L;
            t1 = d + ((re >>> 6) ^ (re >>> 11) ^ (re >>> 25))
                    + (c ^ (a & (b ^ c))) + k[i + 4] + w[i + 4];
            h = (h + t1) & MASK;
            d = (t1 + ((ra >>> 2) ^ (ra >>> 13) ^ (ra >>> 22))
                    + ((e & f) | (g & (e | f)))) & MASK;

            re = h * 0x100000001L;
            ra = d * 0x100000001L;
            t1 = c + ((re >>> 6) ^ (re >>> 11) ^ (re >>> 25))
                    + (b ^ (h & (a ^ b))) + k[i + 5] + w[i + 5];
            g = (g + t1) & MASK;
            c = (t1 + ((ra >>> 2) ^ (ra >>> 13) ^ (ra >>> 22))
                    + ((d & e) | (f & (d | e)))) & MASK;

            re = g * 0x100000001L;
            ra = c * 0x100000001L;
            t1 = b + ((re >>> 6) ^ (re >>> 11) ^ (re >>> 25))
                    + (a ^ (g & (h ^ a))) + k[i + 6] + w[i + 6];
            f = (f + t1) & MASK;
            b = (t1 + ((ra >>> 2) ^ (ra >>> 13) ^ (ra >>> 22))
                    + ((c & d) | (e & (c | d)))) & MASK;

            re = f * 0x100000001L;
            ra = b * 0x100000001L;
            t1 = a + ((re >>> 6) ^ (re >>> 11) ^ (re >>> 25))
                    + (h ^ (f & (g ^ h))) + k[i + 7] + w[i + 7];
            e = (e + t1) & MASK;
            a = (t1 + ((ra >>> 2) ^ (ra >>> 13) ^ (ra >>> 22))
                    + ((b & c) | (d & (b | c)))) & MASK;
        }
        return new long[]{a, b, c, d, e, f, g, h};
    }

    private static void expand(long[] w, int firstWord) {
        for (int i = firstWord; i < 64; i++) {
            long x = w[i - 2], y = w[i - 15];
            long s1 = (x >>> 17) ^ (x << 15) ^ (x >>> 19) ^ (x << 13) ^ (x >>> 10);
            long s0 = (y >>> 7) ^ (y << 25) ^ (y >>> 18) ^ (y << 14) ^ (y >>> 3);
            w[i] = (s1 + w[i - 7] + s0 + w[i - 16]) & MASK;
        }
    }

    private static long sigma0(long v) {
        return ((v >>> 7) ^ (v << 25) ^ (v >>> 18) ^ (v << 14) ^ (v >>> 3)) & MASK;
    }

    private static long sigma1(long v) {
        return ((v >>> 17) ^ (v << 15) ^ (v >>> 19) ^ (v << 13) ^ (v >>> 10)) & MASK;
    }

    /* ---------------- CS64 ---------------- */

    private static long[] cs64Digest(byte[] data, long[] digest) {
        long keyA = (Integer.reverseBytes((int) digest[0]) & MASK) | 1L;
        long keyB = (Integer.reverseBytes((int) digest[1]) & MASK) | 1L;
        long[] factors = {(keyA * CS64_FACTOR) & MASK, (keyB * CS64_FACTOR) & MASK};

        byte[] buf = new byte[data.length + 1];
        System.arraycopy(data, 0, buf, 1, data.length);
        long[] words = wordsLe4Even(buf);
        int n = COEF.length + words.length;
        long[] terms = new long[n];
        long[] offsets = new long[n];
        for (int i = 0; i < COEF.length; i++) {
            terms[i] = (COEF[i] * factors[i % 2]) & MASK;
            offsets[i] = OFFSET[i];
        }
        for (int i = 0; i < words.length; i++) {
            long term = words[i] * (i % 2 == 0 ? keyA : keyB);
            long offset = rol16(words[i]) * (i % 2 == 0 ? 0xEF0569FBL : 0xC31713DBL);
            if (i == 0) {
                term += CS64_ADJUSTMENT0 * factors[0];
                offset += CS64_ADJUSTMENT1;
            }
            terms[COEF.length + i] = term & MASK;
            offsets[COEF.length + i] = offset & MASK;
        }
        long swapAcc = 0, swapLast = 0, state = 0, acc = 0;
        for (int i = 0; i < n; i += 2) {
            long x = terms[i], y = terms[i + 1];
            long t = rol16((offsets[i] + x) & MASK);
            long q = (t * 0x76F50F34L) & MASK;
            swapAcc += (rol16(q) * 0xC3EFEA97L + q) & MASK;
            t = rol16((offsets[i + 1] + y) & MASK);
            q = (t * 0x1881CE3CL) & MASK;
            swapLast = (rol16(q) * 0x35BD1EC9L + q) & MASK;
            swapAcc = (swapAcc + swapLast) & MASK;

            state = (state * keyA + x) & MASK;
            state = (rol16(state) * 0xCF98B111L) & MASK;
            state = (rol16(state) * 0x87085B9FL) & MASK;
            state = (rol16(state) * 0x12CEB96DL) & MASK;
            state = (rol16(state) * 0x257E1D83L) & MASK;
            acc += state;
            t = (state * keyB + y) & MASK;
            t = (rol16(t) * 0xA27416F5L) & MASK;
            t = (rol16(t) * 0xD38396FFL) & MASK;
            state = (rol16((rol16(t) * 0x7C932B89L) & MASK) * 0xBFA49F69L) & MASK;
            acc = (acc + state) & MASK;
        }
        return new long[]{swapLast ^ state, swapAcc ^ acc};
    }

    /** 按 4 字节小端切分，末尾补零到偶数个。 */
    private static long[] wordsLe4Even(byte[] data) {
        int n = (data.length + 3) / 4;
        long[] w = new long[n + (n % 2)];
        for (int i = 0; i < n; i++) {
            long v = 0;
            for (int b = 0; b < 4; b++) {
                int idx = i * 4 + b;
                if (idx < data.length) v |= (data[idx] & 0xFFL) << (8 * b);
            }
            w[i] = v;
        }
        return w;
    }

    private static long rol16(long v) {
        v &= MASK;
        return ((v << 16) | (v >>> 16)) & MASK;
    }

    private static String hexLe(long v) {
        return String.format(Locale.US, "%08X", Integer.reverseBytes((int) v) & MASK);
    }

    private static String hexBe(long[] words) {
        StringBuilder sb = new StringBuilder(64);
        for (long w : words) sb.append(String.format(Locale.US, "%08X", w));
        return sb.toString();
    }
}
