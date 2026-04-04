#include "btc_header_sha256.h"
#include "sha256.h"

#include <android/log.h>
#include <stdio.h>
#include <string.h>

static void scalar_compress_one(uint32_t *st, const uint8_t *c) {
    sha256_compress(st, c);
}

void btc_midstate_header76(const uint8_t header76[BTC_HEADER76_SIZE], uint32_t mid[8]) {
    sha256_initial_state(mid);
    uint8_t b0[64];
    memcpy(b0, header76, 64);
    scalar_compress_one(mid, b0);
}

void btc_header80_from_76(const uint8_t header76[BTC_HEADER76_SIZE], uint32_t nonce, uint8_t header80[BTC_HEADER80_SIZE]) {
    memcpy(header80, header76, BTC_HEADER76_SIZE);
    header80[76] = (uint8_t)nonce;
    header80[77] = (uint8_t)(nonce >> 8);
    header80[78] = (uint8_t)(nonce >> 16);
    header80[79] = (uint8_t)(nonce >> 24);
}

void btc_first_sha_from_mid(const uint8_t header76[BTC_HEADER76_SIZE], const uint32_t mid[8], uint32_t nonce,
                            uint8_t digest32[BTC_HASH32_SIZE]) {
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
    scalar_compress_one(s, block);
    for (int i = 0; i < 8; i++) {
        digest32[i * 4 + 0] = (uint8_t)(s[i] >> 24);
        digest32[i * 4 + 1] = (uint8_t)(s[i] >> 16);
        digest32[i * 4 + 2] = (uint8_t)(s[i] >> 8);
        digest32[i * 4 + 3] = (uint8_t)s[i];
    }
}

void btc_double_sha_from_mid(const uint8_t header76[BTC_HEADER76_SIZE], const uint32_t mid[8], uint32_t nonce,
                             uint8_t out32[BTC_HASH32_SIZE]) {
    uint8_t d32[BTC_HASH32_SIZE];
    btc_first_sha_from_mid(header76, mid, nonce, d32);
    sha256(d32, SHA256_DIGEST_SIZE, out32);
}

void btc_first_sha_full(const uint8_t header76[BTC_HEADER76_SIZE], uint32_t nonce, uint8_t digest32[BTC_HASH32_SIZE]) {
    uint8_t h80[BTC_HEADER80_SIZE];
    btc_header80_from_76(header76, nonce, h80);
    sha256(h80, BTC_HEADER80_SIZE, digest32);
}

void btc_double_sha_full(const uint8_t header76[BTC_HEADER76_SIZE], uint32_t nonce, uint8_t out32[BTC_HASH32_SIZE]) {
    uint8_t h80[BTC_HEADER80_SIZE];
    btc_header80_from_76(header76, nonce, h80);
    sha256_double(h80, BTC_HEADER80_SIZE, out32);
}

static void bytes32_to_hex(const uint8_t b[32], char out[65]) {
    static const char *const hex = "0123456789abcdef";
    for (int i = 0; i < 32; i++) {
        out[i * 2] = hex[b[i] >> 4];
        out[i * 2 + 1] = hex[b[i] & 0x0f];
    }
    out[64] = '\0';
}

static void mid8_to_hex(const uint32_t midv[8], char *out, size_t cap) {
    snprintf(out, cap, "%08x:%08x:%08x:%08x:%08x:%08x:%08x:%08x", midv[0], midv[1], midv[2], midv[3], midv[4], midv[5],
        midv[6], midv[7]);
}

static const uint8_t kGpuSelftestHeader76[BTC_HEADER76_SIZE] = {
    1,  2,  3,  4,  5,  6,  7,  8,  9,  10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32,
    33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51, 52, 53, 54, 55, 56, 57, 58, 59, 60, 61, 62, 63, 64,
    65, 66, 67, 68, 69, 70, 71, 72, 73, 74, 75, 76,
};

const uint8_t *btc_gpu_selftest_header76(void) { return kGpuSelftestHeader76; }

int gpu_sha_host_selftest(void) {
    uint32_t mid[8];
    btc_midstate_header76(kGpuSelftestHeader76, mid);
    for (uint32_t nonce = 1; nonce <= 4; nonce++) {
        uint8_t ref[BTC_HASH32_SIZE];
        uint8_t got[BTC_HASH32_SIZE];
        btc_double_sha_full(kGpuSelftestHeader76, nonce, ref);
        btc_double_sha_from_mid(kGpuSelftestHeader76, mid, nonce, got);
        char ref_hex[65], got_hex[65];
        bytes32_to_hex(ref, ref_hex);
        bytes32_to_hex(got, got_hex);
        int same = (memcmp(ref, got, 32) == 0);
        char mid_line[128];
        mid8_to_hex(mid, mid_line, sizeof(mid_line));
        __android_log_print(ANDROID_LOG_INFO, "GPU_SHA_SelfTest",
            "host_primary nonce=%u midstate=%s ref=%s got=%s same=%d", (unsigned)nonce, mid_line, ref_hex, got_hex,
            same);
        if (!same) {
            __android_log_print(ANDROID_LOG_INFO, "GPU_SHA_SelfTest", "host_primary all_nonces_ok=0");
            return 0;
        }
    }
    __android_log_print(ANDROID_LOG_INFO, "GPU_SHA_SelfTest", "host_primary all_nonces_ok=1");
    return 1;
}
