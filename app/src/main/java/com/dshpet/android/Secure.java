package com.dshpet.android;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;

import java.security.MessageDigest;

/**
 * 加固第二道门：密钥、期望签名、反调试都在 native 层（libdshcore.so），dex 里只留调用。
 *
 * 判定不通过时的策略是「拒绝工作」，绝不删除用户的 S-G 数据 ——
 * 那是用户自己的记忆和日记，误判的代价远大于被逆向。
 */
public final class Secure {
    private static Boolean soLoaded;
    /** 缓存检查结果，避免每次调 UI 都去读 /proc。 */
    private static String cached;

    private Secure() {
    }

    private static native byte[] nativeKey();

    private static native boolean nativeSigOk(byte[] certSha256);

    private static native boolean nativeTraced();

    /** so 是否加载成功；失败即为被剥离或被替换。 */
    public static synchronized boolean libReady() {
        if (soLoaded == null) {
            try {
                System.loadLibrary("dshcore");
                soLoaded = true;
            } catch (Throwable t) {
                soLoaded = false;
            }
        }
        return soLoaded;
    }

    /** 前端解密密钥（32 字节）。取不到就抛，不留 Java 兜底密钥。 */
    public static byte[] key() {
        if (!libReady()) throw new IllegalStateException("native 未就绪");
        byte[] k = nativeKey();
        if (k == null || k.length != 32) throw new IllegalStateException("密钥异常");
        return k;
    }

    /** 本机安装包的签名证书 SHA-256。 */
    private static byte[] certSha256(Context c) {
        try {
            PackageManager pm = c.getPackageManager();
            PackageInfo pi = pm.getPackageInfo(c.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
            Signature[] arr = pi.signingInfo == null ? null : pi.signingInfo.getApkContentsSigners();
            if (arr == null || arr.length == 0) return null;
            return MessageDigest.getInstance("SHA-256").digest(arr[0].toByteArray());
        } catch (Throwable t) {
            return null;
        }
    }

    /** 签名是否与预期一致（改包重签名这里就断了）。 */
    public static boolean signatureOk(Context c) {
        if (!libReady()) return false;
        byte[] cert = certSha256(c);
        if (cert == null) return false;
        try {
            return nativeSigOk(cert);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 是否处于调试 / 注入环境。 */
    public static boolean traced() {
        if (!libReady()) return true;
        try {
            return nativeTraced();
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * 环境检查：返回空串表示正常，否则返回给用户看的原因。
     * needDebug 为 true 时连调试环境一起拦（只给控制台用）。
     */
    public static synchronized String problem(Context c, boolean needDebug) {
        if (cached != null) return cached;
        String r = "";
        if (!libReady()) {
            r = "核心组件缺失，安装包可能已被修改。";
        } else if (!signatureOk(c)) {
            r = "安装包签名校验不通过，可能被重新打包过。";
        } else if (needDebug && traced()) {
            r = "检测到调试或注入环境，为保护你的数据已停止运行。";
        }
        cached = r;
        return r;
    }

    /** 供设置页显示当前防护状态。 */
    public static String status(Context c) {
        if (!libReady()) return "未启用";
        if (!signatureOk(c)) return "签名异常";
        return traced() ? "调试环境" : "正常";
    }
}