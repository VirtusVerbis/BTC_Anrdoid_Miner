#include "sha256.h"
#include "sha256_scan.h"
#include "btc_header_sha256.h"
#include <jni.h>
#include <stdatomic.h>
#include <stdint.h>
#include <string.h>

#if defined(__aarch64__)
#include <sys/auxv.h>
#include <asm/hwcap.h>
#endif

#define BLOCK_HEADER_SIZE 80
#define HEADER_PREFIX_SIZE 76
#define HASH_SIZE 32

/* Set by cpuRequestInterrupt; checked every 64k iterations in nonce scan. */
atomic_int g_cpu_interrupt_requested = 0;

/* NIST test vector: SHA-256("abc") = 0xba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad */
static const uint8_t TEST_ABC_HASH[HASH_SIZE] = {
    0xba, 0x78, 0x16, 0xbf, 0x8f, 0x01, 0xcf, 0xea,
    0x41, 0x41, 0x40, 0xde, 0x5d, 0xae, 0x22, 0x23,
    0xb0, 0x03, 0x61, 0xa3, 0x96, 0x17, 0x7a, 0x9c,
    0xb4, 0x10, 0xff, 0x61, 0xf2, 0x00, 0x15, 0xad,
};

JNIEXPORT jstring JNICALL
Java_com_btcminer_android_mining_NativeMiner_nativeVersion(JNIEnv *env, jclass clazz) {
    (void)clazz;
    return (*env)->NewStringUTF(env, "1.0.0");
}

JNIEXPORT jboolean JNICALL
Java_com_btcminer_android_mining_NativeMiner_nativeTestSha256(JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
    const uint8_t abc[] = { 'a', 'b', 'c' };
    uint8_t out[HASH_SIZE];
    sha256(abc, sizeof(abc), out);
    return (memcmp(out, TEST_ABC_HASH, HASH_SIZE) == 0) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jbyteArray JNICALL
Java_com_btcminer_android_mining_NativeMiner_nativeHashBlockHeader(JNIEnv *env, jclass clazz,
                                                                   jbyteArray headerJava) {
    (void)clazz;
    if (!headerJava || (*env)->GetArrayLength(env, headerJava) != BLOCK_HEADER_SIZE) {
        return NULL;
    }
    uint8_t header[BLOCK_HEADER_SIZE];
    uint8_t out[HASH_SIZE];
    (*env)->GetByteArrayRegion(env, headerJava, 0, BLOCK_HEADER_SIZE, (jbyte *)header);
    sha256_double(header, BLOCK_HEADER_SIZE, out);
    jbyteArray result = (*env)->NewByteArray(env, HASH_SIZE);
    if (result) {
        (*env)->SetByteArrayRegion(env, result, 0, HASH_SIZE, (jbyte *)out);
    }
    return result;
}

JNIEXPORT void JNICALL
Java_com_btcminer_android_mining_NativeMiner_cpuRequestInterrupt(JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
    atomic_store_explicit(&g_cpu_interrupt_requested, 1, memory_order_release);
}

JNIEXPORT jboolean JNICALL
Java_com_btcminer_android_mining_NativeMiner_nativeHwcapSha2(JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
#if defined(__aarch64__)
    unsigned long hw = getauxval(AT_HWCAP);
    return ((hw & HWCAP_SHA2) != 0) ? JNI_TRUE : JNI_FALSE;
#else
    return JNI_FALSE;
#endif
}

/** GPU midstate vs full double-SHA host check; logs tag GPU_SHA_SelfTest. */
JNIEXPORT jboolean JNICALL
Java_com_btcminer_android_mining_NativeMiner_gpuShaHostSelftest(JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
    return gpu_sha_host_selftest() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_btcminer_android_mining_NativeMiner_nativeSelfTestCpuSha256Flavor(JNIEnv *env, jclass clazz,
                                                                           jint flavor) {
    (void)env;
    (void)clazz;
    if (flavor < 0 || flavor > 5) {
        return JNI_FALSE;
    }
    return cpu_sha_selftest_flavor((int)flavor) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_btcminer_android_mining_NativeMiner_nativeScanNonces(JNIEnv *env, jclass clazz,
                                                             jbyteArray header76Java,
                                                             jint nonceStart, jint nonceEnd,
                                                             jbyteArray targetJava, jint flavor) {
    (void)clazz;
    if (!header76Java || !targetJava ||
        (*env)->GetArrayLength(env, header76Java) != HEADER_PREFIX_SIZE ||
        (*env)->GetArrayLength(env, targetJava) != HASH_SIZE) {
        return (jint)-1;
    }
    if (flavor < 0 || flavor > 5) {
        return (jint)-4;
    }
    uint8_t header76[HEADER_PREFIX_SIZE];
    uint8_t target[HASH_SIZE];
    (*env)->GetByteArrayRegion(env, header76Java, 0, HEADER_PREFIX_SIZE, (jbyte *)header76);
    (*env)->GetByteArrayRegion(env, targetJava, 0, HASH_SIZE, (jbyte *)target);

    uint32_t start = (uint32_t)nonceStart;
    uint32_t end = (uint32_t)nonceEnd;
    atomic_store_explicit(&g_cpu_interrupt_requested, 0, memory_order_relaxed);
    return (jint)scan_nonces_dispatch((int)flavor, header76, start, end, target);
}
