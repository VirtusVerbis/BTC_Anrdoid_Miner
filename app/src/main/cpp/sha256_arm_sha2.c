/*
 * SHA-256 compression using ARMv8 Cryptography Extensions.
 * Algorithm from Bitcoin Core src/crypto/sha256_arm_shani.cpp (MIT) and public-domain
 * SHA-Intrinsics (Jeffrey Walton) per Core file header; adapted to C for this project.
 */

#include "sha256_arm_sha2.h"

#if defined(__aarch64__)

#include <arm_neon.h>

static const uint32_t K256[64] __attribute__((aligned(16))) = {
    0x428A2F98, 0x71374491, 0xB5C0FBCF, 0xE9B5DBA5, 0x3956C25B, 0x59F111F1, 0x923F82A4, 0xAB1C5ED5,
    0xD807AA98, 0x12835B01, 0x243185BE, 0x550C7DC3, 0x72BE5D74, 0x80DEB1FE, 0x9BDC06A7, 0xC19BF174,
    0xE49B69C1, 0xEFBE4786, 0x0FC19DC6, 0x240CA1CC, 0x2DE92C6F, 0x4A7484AA, 0x5CB0A9DC, 0x76F988DA,
    0x983E5152, 0xA831C66D, 0xB00327C8, 0xBF597FC7, 0xC6E00BF3, 0xD5A79147, 0x06CA6351, 0x14292967,
    0x27B70A85, 0x2E1B2138, 0x4D2C6DFC, 0x53380D13, 0x650A7354, 0x766A0ABB, 0x81C2C92E, 0x92722C85,
    0xA2BFE8A1, 0xA81A664B, 0xC24B8B70, 0xC76C51A3, 0xD192E819, 0xD6990624, 0xF40E3585, 0x106AA070,
    0x19A4C116, 0x1E376C08, 0x2748774C, 0x34B0BCB5, 0x391C0CB3, 0x4ED8AA4A, 0x5B9CCA4F, 0x682E6FF3,
    0x748F82EE, 0x78A5636F, 0x84C87814, 0x8CC70208, 0x90BEFFFA, 0xA4506CEB, 0xBEF9A3F7, 0xC67178F2,
};

void sha256_arm_compress(uint32_t *s, const uint8_t *chunk, size_t blocks) {
    uint32x4_t STATE0, STATE1, ABEF_SAVE, CDGH_SAVE;
    uint32x4_t MSG0, MSG1, MSG2, MSG3;
    uint32x4_t TMP0, TMP2;

    STATE0 = vld1q_u32(&s[0]);
    STATE1 = vld1q_u32(&s[4]);

    while (blocks--) {
        ABEF_SAVE = STATE0;
        CDGH_SAVE = STATE1;

        MSG0 = vreinterpretq_u32_u8(vrev32q_u8(vld1q_u8(chunk + 0)));
        MSG1 = vreinterpretq_u32_u8(vrev32q_u8(vld1q_u8(chunk + 16)));
        MSG2 = vreinterpretq_u32_u8(vrev32q_u8(vld1q_u8(chunk + 32)));
        MSG3 = vreinterpretq_u32_u8(vrev32q_u8(vld1q_u8(chunk + 48)));
        chunk += 64;

        TMP0 = vaddq_u32(MSG0, vld1q_u32(&K256[0]));
        TMP2 = STATE0;
        MSG0 = vsha256su0q_u32(MSG0, MSG1);
        STATE0 = vsha256hq_u32(STATE0, STATE1, TMP0);
        STATE1 = vsha256h2q_u32(STATE1, TMP2, TMP0);
        MSG0 = vsha256su1q_u32(MSG0, MSG2, MSG3);

        TMP0 = vaddq_u32(MSG1, vld1q_u32(&K256[4]));
        TMP2 = STATE0;
        MSG1 = vsha256su0q_u32(MSG1, MSG2);
        STATE0 = vsha256hq_u32(STATE0, STATE1, TMP0);
        STATE1 = vsha256h2q_u32(STATE1, TMP2, TMP0);
        MSG1 = vsha256su1q_u32(MSG1, MSG3, MSG0);

        TMP0 = vaddq_u32(MSG2, vld1q_u32(&K256[8]));
        TMP2 = STATE0;
        MSG2 = vsha256su0q_u32(MSG2, MSG3);
        STATE0 = vsha256hq_u32(STATE0, STATE1, TMP0);
        STATE1 = vsha256h2q_u32(STATE1, TMP2, TMP0);
        MSG2 = vsha256su1q_u32(MSG2, MSG0, MSG1);

        TMP0 = vaddq_u32(MSG3, vld1q_u32(&K256[12]));
        TMP2 = STATE0;
        MSG3 = vsha256su0q_u32(MSG3, MSG0);
        STATE0 = vsha256hq_u32(STATE0, STATE1, TMP0);
        STATE1 = vsha256h2q_u32(STATE1, TMP2, TMP0);
        MSG3 = vsha256su1q_u32(MSG3, MSG1, MSG2);

        TMP0 = vaddq_u32(MSG0, vld1q_u32(&K256[16]));
        TMP2 = STATE0;
        MSG0 = vsha256su0q_u32(MSG0, MSG1);
        STATE0 = vsha256hq_u32(STATE0, STATE1, TMP0);
        STATE1 = vsha256h2q_u32(STATE1, TMP2, TMP0);
        MSG0 = vsha256su1q_u32(MSG0, MSG2, MSG3);

        TMP0 = vaddq_u32(MSG1, vld1q_u32(&K256[20]));
        TMP2 = STATE0;
        MSG1 = vsha256su0q_u32(MSG1, MSG2);
        STATE0 = vsha256hq_u32(STATE0, STATE1, TMP0);
        STATE1 = vsha256h2q_u32(STATE1, TMP2, TMP0);
        MSG1 = vsha256su1q_u32(MSG1, MSG3, MSG0);

        TMP0 = vaddq_u32(MSG2, vld1q_u32(&K256[24]));
        TMP2 = STATE0;
        MSG2 = vsha256su0q_u32(MSG2, MSG3);
        STATE0 = vsha256hq_u32(STATE0, STATE1, TMP0);
        STATE1 = vsha256h2q_u32(STATE1, TMP2, TMP0);
        MSG2 = vsha256su1q_u32(MSG2, MSG0, MSG1);

        TMP0 = vaddq_u32(MSG3, vld1q_u32(&K256[28]));
        TMP2 = STATE0;
        MSG3 = vsha256su0q_u32(MSG3, MSG0);
        STATE0 = vsha256hq_u32(STATE0, STATE1, TMP0);
        STATE1 = vsha256h2q_u32(STATE1, TMP2, TMP0);
        MSG3 = vsha256su1q_u32(MSG3, MSG1, MSG2);

        TMP0 = vaddq_u32(MSG0, vld1q_u32(&K256[32]));
        TMP2 = STATE0;
        MSG0 = vsha256su0q_u32(MSG0, MSG1);
        STATE0 = vsha256hq_u32(STATE0, STATE1, TMP0);
        STATE1 = vsha256h2q_u32(STATE1, TMP2, TMP0);
        MSG0 = vsha256su1q_u32(MSG0, MSG2, MSG3);

        TMP0 = vaddq_u32(MSG1, vld1q_u32(&K256[36]));
        TMP2 = STATE0;
        MSG1 = vsha256su0q_u32(MSG1, MSG2);
        STATE0 = vsha256hq_u32(STATE0, STATE1, TMP0);
        STATE1 = vsha256h2q_u32(STATE1, TMP2, TMP0);
        MSG1 = vsha256su1q_u32(MSG1, MSG3, MSG0);

        TMP0 = vaddq_u32(MSG2, vld1q_u32(&K256[40]));
        TMP2 = STATE0;
        MSG2 = vsha256su0q_u32(MSG2, MSG3);
        STATE0 = vsha256hq_u32(STATE0, STATE1, TMP0);
        STATE1 = vsha256h2q_u32(STATE1, TMP2, TMP0);
        MSG2 = vsha256su1q_u32(MSG2, MSG0, MSG1);

        TMP0 = vaddq_u32(MSG3, vld1q_u32(&K256[44]));
        TMP2 = STATE0;
        MSG3 = vsha256su0q_u32(MSG3, MSG0);
        STATE0 = vsha256hq_u32(STATE0, STATE1, TMP0);
        STATE1 = vsha256h2q_u32(STATE1, TMP2, TMP0);
        MSG3 = vsha256su1q_u32(MSG3, MSG1, MSG2);

        TMP0 = vaddq_u32(MSG0, vld1q_u32(&K256[48]));
        TMP2 = STATE0;
        STATE0 = vsha256hq_u32(STATE0, STATE1, TMP0);
        STATE1 = vsha256h2q_u32(STATE1, TMP2, TMP0);

        TMP0 = vaddq_u32(MSG1, vld1q_u32(&K256[52]));
        TMP2 = STATE0;
        STATE0 = vsha256hq_u32(STATE0, STATE1, TMP0);
        STATE1 = vsha256h2q_u32(STATE1, TMP2, TMP0);

        TMP0 = vaddq_u32(MSG2, vld1q_u32(&K256[56]));
        TMP2 = STATE0;
        STATE0 = vsha256hq_u32(STATE0, STATE1, TMP0);
        STATE1 = vsha256h2q_u32(STATE1, TMP2, TMP0);

        TMP0 = vaddq_u32(MSG3, vld1q_u32(&K256[60]));
        TMP2 = STATE0;
        STATE0 = vsha256hq_u32(STATE0, STATE1, TMP0);
        STATE1 = vsha256h2q_u32(STATE1, TMP2, TMP0);

        STATE0 = vaddq_u32(STATE0, ABEF_SAVE);
        STATE1 = vaddq_u32(STATE1, CDGH_SAVE);
    }

    vst1q_u32(&s[0], STATE0);
    vst1q_u32(&s[4], STATE1);
}

#endif
