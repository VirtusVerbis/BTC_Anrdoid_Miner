#ifndef SHA256_NEON_4WAY_H
#define SHA256_NEON_4WAY_H

#include <stdint.h>

#if defined(__aarch64__)

/**
 * Double SHA-256 for four 80-byte Bitcoin headers that share the first 76 bytes;
 * nonces are little-endian at offsets 76–79. Writes 32-byte digests per lane.
 */
void sha256_neon4_double(const uint8_t header76[76], uint32_t n0, uint32_t n1, uint32_t n2, uint32_t n3,
                          uint8_t digests[4][32]);

/**
 * Midstate path: [mid] is state after first 64 bytes of header; same tail/nonce rules as scalar midstate.
 */
void sha256_neon4_double_mid(const uint32_t midstate[8], const uint8_t header76[76],
                             uint32_t n0, uint32_t n1, uint32_t n2, uint32_t n3, uint8_t digests[4][32]);

#else

static inline void sha256_neon4_double(const uint8_t header76[76], uint32_t n0, uint32_t n1, uint32_t n2,
                                       uint32_t n3, uint8_t digests[4][32]) {
    (void)header76;
    (void)n0;
    (void)n1;
    (void)n2;
    (void)n3;
    (void)digests;
}

static inline void sha256_neon4_double_mid(const uint32_t midstate[8], const uint8_t header76[76], uint32_t n0,
                                           uint32_t n1, uint32_t n2, uint32_t n3, uint8_t digests[4][32]) {
    (void)midstate;
    (void)header76;
    (void)n0;
    (void)n1;
    (void)n2;
    (void)n3;
    (void)digests;
}

#endif

#endif
