#include "sha256_btc_fast.h"
#include "sha256.h"

#include <string.h>

#define ROTR(x, n) (((x) >> (n)) | ((x) << (32 - (n))))
#define CH(x, y, z) (((x) & (y)) ^ (~(x) & (z)))
#define MAJ(x, y, z) (((x) & (y)) ^ ((x) & (z)) ^ ((y) & (z)))
#define EP0(x) (ROTR(x, 2) ^ ROTR(x, 13) ^ ROTR(x, 22))
#define EP1(x) (ROTR(x, 6) ^ ROTR(x, 11) ^ ROTR(x, 25))
#define SIG0(x) (ROTR(x, 7) ^ ROTR(x, 18) ^ ((x) >> 3))
#define SIG1(x) (ROTR(x, 17) ^ ROTR(x, 19) ^ ((x) >> 10))

#define SHA256_ROUND(a, b, c, d, e, f, g, h, wi, ki) \
    do { \
        uint32_t ch = CH(e, f, g); \
        uint32_t maj = MAJ(a, b, c); \
        uint32_t t1 = h + EP1(e) + ch + (ki) + (wi); \
        uint32_t t2 = EP0(a) + maj; \
        h = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2; \
    } while (0)

static const uint32_t K[64] = {
    0x428a2f98u, 0x71374491u, 0xb5c0fbcfu, 0xe9b5dba5u, 0x3956c25bu, 0x59f111f1u, 0x923f82a4u, 0xab1c5ed5u,
    0xd807aa98u, 0x12835b01u, 0x243185beu, 0x550c7dc3u, 0x72be5d74u, 0x80deb1feu, 0x9bdc06a7u, 0xc19bf174u,
    0xe49b69c1u, 0xefbe4786u, 0x0fc19dc6u, 0x240ca1ccu, 0x2de92c6fu, 0x4a7484aau, 0x5cb0a9dcu, 0x76f988dau,
    0x983e5152u, 0xa831c66du, 0xb00327c8u, 0xbf597fc7u, 0xc6e00bf3u, 0xd5a79147u, 0x06ca6351u, 0x14292967u,
    0x27b70a85u, 0x2e1b2138u, 0x4d2c6dfcu, 0x53380d13u, 0x650a7354u, 0x766a0abbu, 0x81c2c92eu, 0x92722c85u,
    0xa2bfe8a1u, 0xa81a664bu, 0xc24b8b70u, 0xc76c51a3u, 0xd192e819u, 0xd6990624u, 0xf40e3585u, 0x106aa070u,
    0x19a4c116u, 0x1e376c08u, 0x2748774cu, 0x34b0bcb5u, 0x391c0cb3u, 0x4ed8aa4au, 0x5b9cca4fu, 0x682e6ff3u,
    0x748f82eeu, 0x78a5636fu, 0x84c87814u, 0x8cc70208u, 0x90befffau, 0xa4506cebu, 0xbef9a3f7u, 0xc67178f2u,
};

#include "sha256_compress_first_mid_cpu.inc"
#include "sha256_compress_second_cpu.inc"

static uint32_t be_word(const uint8_t *p) {
    return ((uint32_t)p[0] << 24) | ((uint32_t)p[1] << 16) | ((uint32_t)p[2] << 8) | (uint32_t)p[3];
}

static uint32_t bswap32_word(uint32_t x) {
    return ((x & 0xFFu) << 24) | (((x >> 8) & 0xFFu) << 16) | (((x >> 16) & 0xFFu) << 8) | (x >> 24);
}

static void state_to_be_bytes(const uint32_t s[8], uint8_t out[32]) {
    for (int i = 0; i < 8; i++) {
        out[i * 4 + 0] = (uint8_t)(s[i] >> 24);
        out[i * 4 + 1] = (uint8_t)(s[i] >> 16);
        out[i * 4 + 2] = (uint8_t)(s[i] >> 8);
        out[i * 4 + 3] = (uint8_t)s[i];
    }
}

void sha256_btc_first_mid_from_header(
    const uint32_t mid[8], const uint8_t header76[76],
    uint32_t nonce, uint8_t digest32[32]) {
    uint32_t s[8];
    memcpy(s, mid, sizeof(s));
    uint32_t h0 = be_word(header76 + 64);
    uint32_t h1 = be_word(header76 + 68);
    uint32_t h2 = be_word(header76 + 72);
    uint32_t nonce_word = bswap32_word(nonce);
    sha256_compress_first_mid_16w_cpu(h0, h1, h2, nonce_word, s);
    state_to_be_bytes(s, digest32);
}

void sha256_btc_second_from_digest(const uint8_t d32[32], uint8_t out32[32]) {
    uint32_t h[8];
    for (int i = 0; i < 8; i++)
        h[i] = be_word(d32 + i * 4);
    uint32_t s[8];
    sha256_initial_state(s);
    sha256_compress_second_16w_cpu(h[0], h[1], h[2], h[3], h[4], h[5], h[6], h[7], s);
    state_to_be_bytes(s, out32);
}

#if defined(SHA256_BTC_FAST_TEST)
void sha256_btc_test_second(const uint32_t h[8], uint32_t out[8]) {
    sha256_initial_state(out);
    sha256_compress_second_16w_cpu(h[0], h[1], h[2], h[3], h[4], h[5], h[6], h[7], out);
}

void sha256_btc_test_first_mid(
    const uint32_t mid[8], uint32_t h0, uint32_t h1, uint32_t h2,
    uint32_t nonce_word, uint32_t out[8]) {
    memcpy(out, mid, 32);
    sha256_compress_first_mid_16w_cpu(h0, h1, h2, nonce_word, out);
}
#endif
