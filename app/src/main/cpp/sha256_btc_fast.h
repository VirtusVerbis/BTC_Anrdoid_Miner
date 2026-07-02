#ifndef SHA256_BTC_FAST_H
#define SHA256_BTC_FAST_H

#include <stdint.h>

/** First SHA-256 over header80 using precomputed block-0 midstate + constant-folded block 1. */
void sha256_btc_first_mid_from_header(
    const uint32_t mid[8], const uint8_t header76[76],
    uint32_t nonce, uint8_t digest32[32]);

/** Outer SHA-256 over a 32-byte first digest (constant-folded second compress). */
void sha256_btc_second_from_digest(const uint8_t d32[32], uint8_t out32[32]);

/** Outer SHA-256 with progressive pool-target rejection. Returns 1 if share meets target. */
int sha256_btc_second_from_digest_target(const uint8_t d32[32], const uint8_t *target, uint8_t out32[32]);

#if defined(SHA256_BTC_FAST_TEST)
void sha256_btc_test_second(const uint32_t h[8], uint32_t out[8]);
void sha256_btc_test_first_mid(
    const uint32_t mid[8], uint32_t h0, uint32_t h1, uint32_t h2,
    uint32_t nonce_word, uint32_t out[8]);
#endif

#endif
