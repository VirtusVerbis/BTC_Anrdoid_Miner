/*
 * Four-lane SHA-256 compression (NEON) for parallel nonce hashing.
 * Round structure follows Intel SSE4 multi-lane layout in Bitcoin Core src/crypto/sha256_sse4.cpp (MIT);
 * expressed with ARM NEON intrinsics for AArch64.
 */

#include "sha256_neon_4way.h"
#include "sha256.h"

#if defined(__aarch64__)

#include <arm_neon.h>
#include <string.h>

static const uint32_t K256[64] __attribute__((aligned(16))) = {
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
};

/* Immediate shifts only: (n) must be a literal — see NDK Clang v sh r q _ n requirements. */
#define NEON_ROTR32(x, n) vorrq_u32(vshrq_n_u32((x), (n)), vshlq_n_u32((x), 32 - (n)))

static inline uint32x4_t ch_n(uint32x4_t e, uint32x4_t f, uint32x4_t g) {
    return veorq_u32(vandq_u32(e, f), vandq_u32(vmvnq_u32(e), g));
}

static inline uint32x4_t maj_n(uint32x4_t a, uint32x4_t b, uint32x4_t c) {
    return veorq_u32(veorq_u32(vandq_u32(a, b), vandq_u32(a, c)), vandq_u32(b, c));
}

static inline uint32x4_t ep0_n(uint32x4_t x) {
    return veorq_u32(veorq_u32(NEON_ROTR32(x, 2), NEON_ROTR32(x, 13)), NEON_ROTR32(x, 22));
}

static inline uint32x4_t ep1_n(uint32x4_t x) {
    return veorq_u32(veorq_u32(NEON_ROTR32(x, 6), NEON_ROTR32(x, 11)), NEON_ROTR32(x, 25));
}

static inline uint32x4_t sig0_n(uint32x4_t x) {
    return veorq_u32(veorq_u32(NEON_ROTR32(x, 7), NEON_ROTR32(x, 18)), vshrq_n_u32(x, 3));
}

static inline uint32x4_t sig1_n(uint32x4_t x) {
    return veorq_u32(veorq_u32(NEON_ROTR32(x, 17), NEON_ROTR32(x, 19)), vshrq_n_u32(x, 10));
}

static inline uint32_t be32(const uint8_t *p) {
    return ((uint32_t)p[0] << 24) | ((uint32_t)p[1] << 16) | ((uint32_t)p[2] << 8) | (uint32_t)p[3];
}

static inline uint32x4_t w_word_from_blocks(const uint8_t bl[4][64], int wi) {
    uint32x4_t w = vdupq_n_u32(0);
    w = vsetq_lane_u32(be32(bl[0] + wi * 4), w, 0);
    w = vsetq_lane_u32(be32(bl[1] + wi * 4), w, 1);
    w = vsetq_lane_u32(be32(bl[2] + wi * 4), w, 2);
    w = vsetq_lane_u32(be32(bl[3] + wi * 4), w, 3);
    return w;
}

static void sha256_4way_one_block(uint32x4_t *A, uint32x4_t *B, uint32x4_t *C, uint32x4_t *D, uint32x4_t *E,
                                  uint32x4_t *F, uint32x4_t *G, uint32x4_t *H, const uint8_t blocks[4][64]) {
    uint32x4_t w[64];
    for (int i = 0; i < 16; i++) {
        w[i] = w_word_from_blocks(blocks, i);
    }
    for (int i = 16; i < 64; i++) {
        w[i] = vaddq_u32(vaddq_u32(sig1_n(w[i - 2]), w[i - 7]), vaddq_u32(sig0_n(w[i - 15]), w[i - 16]));
    }

    uint32x4_t a = *A, b = *B, c = *C, d = *D, e = *E, f = *F, g = *G, h = *H;

    for (int i = 0; i < 64; i++) {
        uint32x4_t t1 =
            vaddq_u32(vaddq_u32(vaddq_u32(vaddq_u32(h, ep1_n(e)), ch_n(e, f, g)), vdupq_n_u32(K256[i])), w[i]);
        uint32x4_t t2 = vaddq_u32(ep0_n(a), maj_n(a, b, c));
        h = g;
        g = f;
        f = e;
        e = vaddq_u32(d, t1);
        d = c;
        c = b;
        b = a;
        a = vaddq_u32(t1, t2);
    }

    *A = vaddq_u32(*A, a);
    *B = vaddq_u32(*B, b);
    *C = vaddq_u32(*C, c);
    *D = vaddq_u32(*D, d);
    *E = vaddq_u32(*E, e);
    *F = vaddq_u32(*F, f);
    *G = vaddq_u32(*G, g);
    *H = vaddq_u32(*H, h);
}

static void digest_from_state(uint32x4_t A, uint32x4_t B, uint32x4_t C, uint32x4_t D, uint32x4_t E, uint32x4_t F,
                              uint32x4_t G, uint32x4_t H, uint8_t out[4][32]) {
    uint32_t tmp[4];
    vst1q_u32(tmp, A);
    for (int l = 0; l < 4; l++) {
        uint32_t x = tmp[l];
        out[l][0] = (uint8_t)(x >> 24);
        out[l][1] = (uint8_t)(x >> 16);
        out[l][2] = (uint8_t)(x >> 8);
        out[l][3] = (uint8_t)x;
    }
    vst1q_u32(tmp, B);
    for (int l = 0; l < 4; l++) {
        uint32_t x = tmp[l];
        out[l][4] = (uint8_t)(x >> 24);
        out[l][5] = (uint8_t)(x >> 16);
        out[l][6] = (uint8_t)(x >> 8);
        out[l][7] = (uint8_t)x;
    }
    vst1q_u32(tmp, C);
    for (int l = 0; l < 4; l++) {
        uint32_t x = tmp[l];
        out[l][8] = (uint8_t)(x >> 24);
        out[l][9] = (uint8_t)(x >> 16);
        out[l][10] = (uint8_t)(x >> 8);
        out[l][11] = (uint8_t)x;
    }
    vst1q_u32(tmp, D);
    for (int l = 0; l < 4; l++) {
        uint32_t x = tmp[l];
        out[l][12] = (uint8_t)(x >> 24);
        out[l][13] = (uint8_t)(x >> 16);
        out[l][14] = (uint8_t)(x >> 8);
        out[l][15] = (uint8_t)x;
    }
    vst1q_u32(tmp, E);
    for (int l = 0; l < 4; l++) {
        uint32_t x = tmp[l];
        out[l][16] = (uint8_t)(x >> 24);
        out[l][17] = (uint8_t)(x >> 16);
        out[l][18] = (uint8_t)(x >> 8);
        out[l][19] = (uint8_t)x;
    }
    vst1q_u32(tmp, F);
    for (int l = 0; l < 4; l++) {
        uint32_t x = tmp[l];
        out[l][20] = (uint8_t)(x >> 24);
        out[l][21] = (uint8_t)(x >> 16);
        out[l][22] = (uint8_t)(x >> 8);
        out[l][23] = (uint8_t)x;
    }
    vst1q_u32(tmp, G);
    for (int l = 0; l < 4; l++) {
        uint32_t x = tmp[l];
        out[l][24] = (uint8_t)(x >> 24);
        out[l][25] = (uint8_t)(x >> 16);
        out[l][26] = (uint8_t)(x >> 8);
        out[l][27] = (uint8_t)x;
    }
    vst1q_u32(tmp, H);
    for (int l = 0; l < 4; l++) {
        uint32_t x = tmp[l];
        out[l][28] = (uint8_t)(x >> 24);
        out[l][29] = (uint8_t)(x >> 16);
        out[l][30] = (uint8_t)(x >> 8);
        out[l][31] = (uint8_t)x;
    }
}

static void neon4_first_sha_four(const uint8_t header76[76], uint32_t n0, uint32_t n1, uint32_t n2, uint32_t n3,
                                 uint8_t mid_digests[4][32], const uint32_t *prefix_midstate_in) {
    uint8_t blk[4][64];
    for (int l = 0; l < 4; l++) {
        memcpy(blk[l], header76, 64);
    }

    uint32x4_t A, B, C, D, E, F, G, H;
    if (prefix_midstate_in) {
        A = vdupq_n_u32(prefix_midstate_in[0]);
        B = vdupq_n_u32(prefix_midstate_in[1]);
        C = vdupq_n_u32(prefix_midstate_in[2]);
        D = vdupq_n_u32(prefix_midstate_in[3]);
        E = vdupq_n_u32(prefix_midstate_in[4]);
        F = vdupq_n_u32(prefix_midstate_in[5]);
        G = vdupq_n_u32(prefix_midstate_in[6]);
        H = vdupq_n_u32(prefix_midstate_in[7]);
    } else {
        A = vdupq_n_u32(0x6a09e667);
        B = vdupq_n_u32(0xbb67ae85);
        C = vdupq_n_u32(0x3c6ef372);
        D = vdupq_n_u32(0xa54ff53a);
        E = vdupq_n_u32(0x510e527f);
        F = vdupq_n_u32(0x9b05688c);
        G = vdupq_n_u32(0x1f83d9ab);
        H = vdupq_n_u32(0x5be0cd19);
        sha256_4way_one_block(&A, &B, &C, &D, &E, &F, &G, &H, blk);
    }

    uint32_t nonces[4] = {n0, n1, n2, n3};
    for (int l = 0; l < 4; l++) {
        uint8_t last16[16];
        memcpy(last16, header76 + 64, 12);
        uint32_t n = nonces[l];
        last16[12] = (uint8_t)n;
        last16[13] = (uint8_t)(n >> 8);
        last16[14] = (uint8_t)(n >> 16);
        last16[15] = (uint8_t)(n >> 24);
        sha256_pad_second_block_80(last16, blk[l]);
    }

    sha256_4way_one_block(&A, &B, &C, &D, &E, &F, &G, &H, blk);
    digest_from_state(A, B, C, D, E, F, G, H, mid_digests);
}

static void neon4_second_sha_four(const uint8_t digests[4][32], uint8_t out[4][32]) {
    uint8_t blk[4][64];
    for (int l = 0; l < 4; l++) {
        memcpy(blk[l], digests[l], 32);
        blk[l][32] = 0x80;
        memset(blk[l] + 33, 0, 23);
        uint64_t bitlen = 32u * 8u;
        blk[l][63] = (uint8_t)bitlen;
        blk[l][62] = (uint8_t)(bitlen >> 8);
        blk[l][61] = (uint8_t)(bitlen >> 16);
        blk[l][60] = (uint8_t)(bitlen >> 24);
        blk[l][59] = (uint8_t)(bitlen >> 32);
        blk[l][58] = (uint8_t)(bitlen >> 40);
        blk[l][57] = (uint8_t)(bitlen >> 48);
        blk[l][56] = (uint8_t)(bitlen >> 56);
    }

    uint32x4_t A = vdupq_n_u32(0x6a09e667);
    uint32x4_t B = vdupq_n_u32(0xbb67ae85);
    uint32x4_t C = vdupq_n_u32(0x3c6ef372);
    uint32x4_t D = vdupq_n_u32(0xa54ff53a);
    uint32x4_t E = vdupq_n_u32(0x510e527f);
    uint32x4_t F = vdupq_n_u32(0x9b05688c);
    uint32x4_t G = vdupq_n_u32(0x1f83d9ab);
    uint32x4_t H = vdupq_n_u32(0x5be0cd19);
    sha256_4way_one_block(&A, &B, &C, &D, &E, &F, &G, &H, blk);
    digest_from_state(A, B, C, D, E, F, G, H, out);
}

void sha256_neon4_double(const uint8_t header76[76], uint32_t n0, uint32_t n1, uint32_t n2, uint32_t n3,
                         uint8_t digests[4][32]) {
    uint8_t mid[4][32];
    neon4_first_sha_four(header76, n0, n1, n2, n3, mid, NULL);
    neon4_second_sha_four(mid, digests);
}

void sha256_neon4_double_mid(const uint32_t midstate[8], const uint8_t header76[76], uint32_t n0, uint32_t n1,
                             uint32_t n2, uint32_t n3, uint8_t digests[4][32]) {
    uint8_t mid[4][32];
    neon4_first_sha_four(header76, n0, n1, n2, n3, mid, midstate);
    neon4_second_sha_four(mid, digests);
}

#endif
