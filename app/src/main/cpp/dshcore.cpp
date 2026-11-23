/*
 * dshcore —— 加固核心（A+B 档的原生部分）
 *
 * 这里放三件不能留在 dex 里的东西：
 *   1. 前端解密密钥的派生逻辑（dex 里只有 JNI 调用，看不到密钥）
 *   2. 期望的签名证书哈希（改包重签名直接对不上）
 *   3. 调试器 / frida 检测
 *
 * 对外只用 RegisterNatives 动态注册，so 里不导出 Java_com_* 符号，
 * 配合 -fvisibility=hidden，符号表里只留一个 JNI_OnLoad。
 */
#include <jni.h>
#include <cstring>
#include <cstdlib>
#include <cstdio>
#include <unistd.h>

namespace {

/* ---------------- 密钥派生（必须与 build.ps1 的加密脚本完全一致） ---------------- */

// 四段分片，故意拆开放在不同函数里，避免在 so 里连成一句可读的密钥
const unsigned char kA[8] = {'D', 's', 'h', 'P', '3', 't', '#', '2'};
const unsigned char kB[8] = {'s', '3', 'c', 'u', 'r', 'e', '!', 'U'};
const unsigned char kC[8] = {'1', 'q', 'a', 'z', '2', 'w', 's', 'x'};
const unsigned char kD[8] = {'Z', 'x', '9', '!', 'k', 'L', 'm', 'N'};

void mix(int i, unsigned char* out) {
    out[i]      = (unsigned char)(((kA[i] + kB[i]) & 0xFF) ^ kC[i]);
    out[i + 8]  = (unsigned char)(((kB[i] ^ kC[i]) + kD[i]) & 0xFF);
    out[i + 16] = (unsigned char)(((kC[i] + kD[i]) & 0xFF) ^ kA[i]);
    out[i + 24] = (unsigned char)(((kD[i] ^ kA[i]) + kB[i]) & 0xFF);
}

// 32 字节 AES-256 密钥
void deriveKey(unsigned char* out) {
    for (int i = 0; i < 8; i++) mix(i, out);
}

/* ---------------- 期望的签名证书 SHA-256 ---------------- */
// 必须与 build.ps1 的 $expectedCert、dshpet-2.5.3.keystore 的证书指纹一致；
// 换签名密钥时必须同步改这三处，否则新版会被自己拦住（“安装包签名校验不通过”）
const unsigned char kCert[32] = {
        0xF7, 0x8C, 0x8E, 0x3C, 0xD6, 0x00, 0x5A, 0xB0,
        0xD3, 0x33, 0xA9, 0x1B, 0x9C, 0x9E, 0x67, 0x28,
        0x50, 0x85, 0x7B, 0xE7, 0xBE, 0x5B, 0xFB, 0x49,
        0x1C, 0xBA, 0x09, 0x0E, 0x1D, 0x24, 0x8F, 0xE4
};

/* ---------------- 反调试 ---------------- */

// /proc/self/status 里的 TracerPid 非 0 说明有进程在 ptrace 我
bool tracedBy() {
    FILE* f = fopen("/proc/self/status", "r");
    if (f == nullptr) return false;
    char line[256];
    int tracer = 0;
    while (fgets(line, sizeof(line), f) != nullptr) {
        if (strncmp(line, "TracerPid:", 10) == 0) {
            tracer = atoi(line + 10);
            break;
        }
    }
    fclose(f);
    return tracer != 0;
}

// 内存映射里出现 frida / xposed 的特征串，说明被注入了
bool injected() {
    FILE* f = fopen("/proc/self/maps", "r");
    if (f == nullptr) return false;
    char line[512];
    bool hit = false;
    while (fgets(line, sizeof(line), f) != nullptr) {
        if (strstr(line, "frida") || strstr(line, "gum-js-loop") || strstr(line, "gadget")
            || strstr(line, "xposed") || strstr(line, "substrate")) {
            hit = true;
            break;
        }
    }
    fclose(f);
    return hit;
}

/* ---------------- JNI ---------------- */

jbyteArray jKey(JNIEnv* env, jclass) {
    unsigned char key[32];
    deriveKey(key);
    jbyteArray arr = env->NewByteArray(32);
    if (arr != nullptr) env->SetByteArrayRegion(arr, 0, 32, reinterpret_cast<jbyte*>(key));
    memset(key, 0, sizeof(key));
    return arr;
}

jboolean jSigOk(JNIEnv* env, jclass, jbyteArray cert) {
    if (cert == nullptr || env->GetArrayLength(cert) != 32) return JNI_FALSE;
    unsigned char buf[32];
    env->GetByteArrayRegion(cert, 0, 32, reinterpret_cast<jbyte*>(buf));
    // 固定时间比较，避免通过耗时差异推测哈希
    unsigned char diff = 0;
    for (int i = 0; i < 32; i++) diff |= (unsigned char)(buf[i] ^ kCert[i]);
    memset(buf, 0, sizeof(buf));
    return diff == 0 ? JNI_TRUE : JNI_FALSE;
}

jboolean jTraced(JNIEnv*, jclass) {
    return (tracedBy() || injected()) ? JNI_TRUE : JNI_FALSE;
}

const JNINativeMethod kMethods[] = {
        {"nativeKey",  "()[B",  reinterpret_cast<void*>(jKey)},
        {"nativeSigOk", "([B)Z", reinterpret_cast<void*>(jSigOk)},
        {"nativeTraced", "()Z", reinterpret_cast<void*>(jTraced)},
};

}  // namespace

extern "C" __attribute__((visibility("default"))) jint JNI_OnLoad(JavaVM* vm, void*) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass cls = env->FindClass("com/dshpet/android/Secure");
    if (cls == nullptr) return JNI_ERR;
    if (env->RegisterNatives(cls, kMethods, sizeof(kMethods) / sizeof(kMethods[0])) != JNI_OK) return JNI_ERR;
    return JNI_VERSION_1_6;
}