/*
 * CPU nonce scanning: scalar / midstate / ARM SHA2 / NEON 4-way dispatch.
 */

#include "sha256.h"
#include "sha256_arm_sha2.h"
#include "sha256_neon_4way.h"

#include <stdatomic.h>
#include <stdint.h>
#include <string.h>

#if defined(SHA256_SELFTEST_DIAG)
#include <android/log.h>
#endif

#define HEADER_PREFIX_SIZE 76
#define BLOCK_HEADER_SIZE 80
#define HASH_SIZE 32

#define CPU_SHA_FLAVOR_ERROR (-4)

extern atomic_int g_cpu_interrupt_requested;

static int hash_meets_target(const uint8_t *hash, const uint8_t *target) {
    return memcmp(hash, target, HASH_SIZE) <= 0;
}

static void midstate_after_block0(const uint8_t *header76, uint32_t mid[8], void (*compress)(uint32_t *, const uint8_t *, size_t)) {
    sha256_initial_state(mid);
    uint8_t b0[64];
    memcpy(b0, header76, 64);
    compress(mid, b0, 1);
}

static void first_hash_mid(const uint32_t mid[8], const uint8_t *header76, uint32_t nonce, uint8_t digest32[32],
                           void (*compress)(uint32_t *, const uint8_t *, size_t)) {
    uint8_t last16[16];
    memcpy(last16, header76 + 64, 12);
    last16[12] = (uint8_t)nonce;
    last16[13] = (uint8_t)(nonce >> 8);
    last16[14] = (uint8_t)(nonce >> 16);
    last16[15] = (uint8_t)(nonce >> 24);
    uint8_t block[64];
    sha256_pad_second_block_80(last16, block);
    uint32_t s[8];
    memcpy(s, mid, sizeof(s));
    compress(s, block, 1);
    for (int i = 0; i < 8; i++) {
        digest32[i * 4 + 0] = (uint8_t)(s[i] >> 24);
        digest32[i * 4 + 1] = (uint8_t)(s[i] >> 16);
        digest32[i * 4 + 2] = (uint8_t)(s[i] >> 8);
        digest32[i * 4 + 3] = (uint8_t)s[i];
    }
}

static void scalar_compress_fn(uint32_t *st, const uint8_t *c, size_t blocks) {
    while (blocks--) {
        sha256_compress(st, c);
        c += 64;
    }
}

#if defined(__aarch64__)
static void arm_compress_fn(uint32_t *st, const uint8_t *c, size_t blocks) {
    sha256_arm_compress(st, c, blocks);
}
#else
static void arm_compress_fn(uint32_t *st, const uint8_t *c, size_t blocks) {
    (void)st;
    (void)c;
    (void)blocks;
}
#endif

/** Bitcoin double-SHA256 outer step only: [d32] is already SHA256(header80). */
static void double_from_mid_digest(const uint8_t d32[32], uint8_t final_hash[32]) {
    sha256(d32, SHA256_DIGEST_SIZE, final_hash);
}

static void header80_from_76_nonce(const uint8_t *header76, uint32_t nonce, uint8_t *header80) {
    memcpy(header80, header76, HEADER_PREFIX_SIZE);
    header80[76] = (uint8_t)nonce;
    header80[77] = (uint8_t)(nonce >> 8);
    header80[78] = (uint8_t)(nonce >> 16);
    header80[79] = (uint8_t)(nonce >> 24);
}

static int scan_scalar_full(const uint8_t *header76, uint32_t start, uint32_t end, const uint8_t *target) {
    uint8_t h80[BLOCK_HEADER_SIZE];
    uint8_t hash[HASH_SIZE];
    memcpy(h80, header76, HEADER_PREFIX_SIZE);
    for (uint32_t nonce = start; nonce <= end; nonce++) {
        if (((nonce - start) & 0xFFFFu) == 0u &&
            atomic_exchange_explicit(&g_cpu_interrupt_requested, 0, memory_order_acq_rel)) {
            return -3;
        }
        h80[76] = (uint8_t)nonce;
        h80[77] = (uint8_t)(nonce >> 8);
        h80[78] = (uint8_t)(nonce >> 16);
        h80[79] = (uint8_t)(nonce >> 24);
        sha256_double(h80, BLOCK_HEADER_SIZE, hash);
        if (hash_meets_target(hash, target)) return (int)nonce;
    }
    return -1;
}

static int scan_scalar_mid(const uint8_t *header76, uint32_t start, uint32_t end, const uint8_t *target) {
    uint32_t mid[8];
    midstate_after_block0(header76, mid, scalar_compress_fn);
    uint8_t d32[32];
    uint8_t hash[HASH_SIZE];
    for (uint32_t nonce = start; nonce <= end; nonce++) {
        if (((nonce - start) & 0xFFFFu) == 0u &&
            atomic_exchange_explicit(&g_cpu_interrupt_requested, 0, memory_order_acq_rel)) {
            return -3;
        }
        first_hash_mid(mid, header76, nonce, d32, scalar_compress_fn);
        double_from_mid_digest(d32, hash);
        if (hash_meets_target(hash, target)) return (int)nonce;
    }
    return -1;
}

#if defined(__aarch64__)

static int scan_arm_full(const uint8_t *header76, uint32_t start, uint32_t end, const uint8_t *target) {
    uint8_t h80[BLOCK_HEADER_SIZE];
    uint8_t hash[HASH_SIZE];
    uint8_t dig32[32];
    for (uint32_t nonce = start; nonce <= end; nonce++) {
        if (((nonce - start) & 0xFFFFu) == 0u &&
            atomic_exchange_explicit(&g_cpu_interrupt_requested, 0, memory_order_acq_rel)) {
            return -3;
        }
        header80_from_76_nonce(header76, nonce, h80);
        uint32_t st[8];
        sha256_initial_state(st);
        sha256_arm_compress(st, h80, 1);
        uint8_t b1[64];
        sha256_pad_second_block_80(h80 + 64, b1);
        sha256_arm_compress(st, b1, 1);
        for (int i = 0; i < 8; i++) {
            dig32[i * 4 + 0] = (uint8_t)(st[i] >> 24);
            dig32[i * 4 + 1] = (uint8_t)(st[i] >> 16);
            dig32[i * 4 + 2] = (uint8_t)(st[i] >> 8);
            dig32[i * 4 + 3] = (uint8_t)st[i];
        }
        sha256(dig32, SHA256_DIGEST_SIZE, hash);
        if (hash_meets_target(hash, target)) return (int)nonce;
    }
    return -1;
}

static int scan_arm_mid(const uint8_t *header76, uint32_t start, uint32_t end, const uint8_t *target) {
    uint32_t mid[8];
    midstate_after_block0(header76, mid, arm_compress_fn);
    uint8_t d32[32];
    uint8_t hash[HASH_SIZE];
    for (uint32_t nonce = start; nonce <= end; nonce++) {
        if (((nonce - start) & 0xFFFFu) == 0u &&
            atomic_exchange_explicit(&g_cpu_interrupt_requested, 0, memory_order_acq_rel)) {
            return -3;
        }
        first_hash_mid(mid, header76, nonce, d32, arm_compress_fn);
        double_from_mid_digest(d32, hash);
        if (hash_meets_target(hash, target)) return (int)nonce;
    }
    return -1;
}

static int scan_neon4_full(const uint8_t *header76, uint32_t start, uint32_t end, const uint8_t *target) {
    uint32_t n = start;
    uint8_t dig[4][32];
    while (n <= end) {
        if (((n - start) & 0xFFFFu) == 0u &&
            atomic_exchange_explicit(&g_cpu_interrupt_requested, 0, memory_order_acq_rel)) {
            return -3;
        }
        if (n + 3 <= end) {
            sha256_neon4_double(header76, n, n + 1, n + 2, n + 3, dig);
            for (int l = 0; l < 4; l++) {
                if (hash_meets_target(dig[l], target)) return (int)(n + (uint32_t)l);
            }
            n += 4;
        } else {
            uint8_t h80[80];
            header80_from_76_nonce(header76, n, h80);
            uint8_t one[32];
            sha256_double(h80, BLOCK_HEADER_SIZE, one);
            if (hash_meets_target(one, target)) return (int)n;
            n++;
        }
    }
    return -1;
}

static int scan_neon4_mid(const uint8_t *header76, uint32_t start, uint32_t end, const uint8_t *target) {
    uint32_t mid[8];
    midstate_after_block0(header76, mid, scalar_compress_fn);
    uint32_t n = start;
    uint8_t dig[4][32];
    uint8_t d32[32];
    uint8_t hash[HASH_SIZE];
    while (n <= end) {
        if (((n - start) & 0xFFFFu) == 0u &&
            atomic_exchange_explicit(&g_cpu_interrupt_requested, 0, memory_order_acq_rel)) {
            return -3;
        }
        if (n + 3 <= end) {
            sha256_neon4_double_mid(mid, header76, n, n + 1, n + 2, n + 3, dig);
            for (int l = 0; l < 4; l++) {
                if (hash_meets_target(dig[l], target)) return (int)(n + (uint32_t)l);
            }
            n += 4;
        } else {
            first_hash_mid(mid, header76, n, d32, scalar_compress_fn);
            double_from_mid_digest(d32, hash);
            if (hash_meets_target(hash, target)) return (int)n;
            n++;
        }
    }
    return -1;
}

#else

static int scan_arm_full(const uint8_t *h, uint32_t a, uint32_t b, const uint8_t *t) {
    (void)h;
    (void)a;
    (void)b;
    (void)t;
    return CPU_SHA_FLAVOR_ERROR;
}
static int scan_arm_mid(const uint8_t *h, uint32_t a, uint32_t b, const uint8_t *t) {
    (void)h;
    (void)a;
    (void)b;
    (void)t;
    return CPU_SHA_FLAVOR_ERROR;
}
static int scan_neon4_full(const uint8_t *h, uint32_t a, uint32_t b, const uint8_t *t) {
    (void)h;
    (void)a;
    (void)b;
    (void)t;
    return CPU_SHA_FLAVOR_ERROR;
}
static int scan_neon4_mid(const uint8_t *h, uint32_t a, uint32_t b, const uint8_t *t) {
    (void)h;
    (void)a;
    (void)b;
    (void)t;
    return CPU_SHA_FLAVOR_ERROR;
}

#endif

int scan_nonces_dispatch(int flavor, const uint8_t *header76, uint32_t start, uint32_t end, const uint8_t *target) {
    switch (flavor) {
        case 0:
            return scan_arm_mid(header76, start, end, target);
        case 1:
            return scan_arm_full(header76, start, end, target);
        case 2:
            return scan_neon4_mid(header76, start, end, target);
        case 3:
            return scan_neon4_full(header76, start, end, target);
        case 4:
            return scan_scalar_mid(header76, start, end, target);
        case 5:
            return scan_scalar_full(header76, start, end, target);
        default:
            return CPU_SHA_FLAVOR_ERROR;
    }
}

void cpu_sha256_double_flavor(int flavor, const uint8_t *header76, uint32_t nonce, uint8_t out[32]) {
    uint8_t h80[BLOCK_HEADER_SIZE];
    header80_from_76_nonce(header76, nonce, h80);
    uint8_t d32[32];
    switch (flavor) {
        case 0: {
            uint32_t mid[8];
            midstate_after_block0(header76, mid, arm_compress_fn);
            first_hash_mid(mid, header76, nonce, d32, arm_compress_fn);
            double_from_mid_digest(d32, out);
            break;
        }
        case 1: {
#if defined(__aarch64__)
            uint32_t st[8];
            sha256_initial_state(st);
            sha256_arm_compress(st, h80, 1);
            uint8_t b1[64];
            sha256_pad_second_block_80(h80 + 64, b1);
            sha256_arm_compress(st, b1, 1);
            for (int i = 0; i < 8; i++) {
                d32[i * 4 + 0] = (uint8_t)(st[i] >> 24);
                d32[i * 4 + 1] = (uint8_t)(st[i] >> 16);
                d32[i * 4 + 2] = (uint8_t)(st[i] >> 8);
                d32[i * 4 + 3] = (uint8_t)st[i];
            }
            sha256(d32, SHA256_DIGEST_SIZE, out);
#else
            memset(out, 0, 32);
#endif
            break;
        }
        case 2: {
#if defined(__aarch64__)
            uint32_t mid[8];
            midstate_after_block0(header76, mid, scalar_compress_fn);
            uint8_t quad[4][32];
            sha256_neon4_double_mid(mid, header76, nonce, nonce, nonce, nonce, quad);
            memcpy(out, quad[0], 32);
#else
            memset(out, 0, 32);
#endif
            break;
        }
        case 3: {
#if defined(__aarch64__)
            uint8_t quad[4][32];
            sha256_neon4_double(header76, nonce, nonce, nonce, nonce, quad);
            memcpy(out, quad[0], 32);
#else
            memset(out, 0, 32);
#endif
            break;
        }
        case 4: {
            uint32_t mid[8];
            midstate_after_block0(header76, mid, scalar_compress_fn);
            first_hash_mid(mid, header76, nonce, d32, scalar_compress_fn);
            double_from_mid_digest(d32, out);
            break;
        }
        case 5:
        default:
            sha256_double(h80, BLOCK_HEADER_SIZE, out);
            break;
    }
}

/* Fixed test header (76 bytes); tweak bytes for regression vectors. */
static const uint8_t kSelftestHeader76[HEADER_PREFIX_SIZE] = {
    1,  2,  3,  4,  5,  6,  7,  8,  9,  10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32,
    33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51, 52, 53, 54, 55, 56, 57, 58, 59, 60, 61, 62, 63, 64,
    65, 66, 67, 68, 69, 70, 71, 72, 73, 74, 75, 76,
};

int cpu_sha_selftest_flavor(int flavor) {
    if (flavor < 0 || flavor > 5) return 0;
    uint8_t ref[32];
    uint8_t got[32];
    for (uint32_t nonce = 1; nonce <= 4; nonce++) {
        uint8_t h80[BLOCK_HEADER_SIZE];
        header80_from_76_nonce(kSelftestHeader76, nonce, h80);
#if defined(SHA256_SELFTEST_DIAG)
        if (flavor == 4 && nonce == 1) {
            uint8_t first_ref[32];
            sha256(h80, BLOCK_HEADER_SIZE, first_ref);
            uint32_t mid_dbg[8];
            midstate_after_block0(kSelftestHeader76, mid_dbg, scalar_compress_fn);
            uint8_t first_mid[32];
            first_hash_mid(mid_dbg, kSelftestHeader76, nonce, first_mid, scalar_compress_fn);
            if (memcmp(first_ref, first_mid, 32) != 0) {
                __android_log_print(ANDROID_LOG_ERROR, "SHA256_SelfTest",
                    "first SHA mismatch: scalar ref vs midstate+second block (nonce=1)");
            }
        }
#endif
        sha256_double(h80, BLOCK_HEADER_SIZE, ref);
        cpu_sha256_double_flavor(flavor, kSelftestHeader76, nonce, got);
        if (memcmp(ref, got, 32) != 0) return 0;
    }
    return 1;
}
