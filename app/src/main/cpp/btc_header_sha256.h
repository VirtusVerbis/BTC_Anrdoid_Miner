#ifndef BTC_HEADER_SHA256_H
#define BTC_HEADER_SHA256_H

#include <stdint.h>

#define BTC_HEADER76_SIZE 76
#define BTC_HEADER80_SIZE 80
#define BTC_HASH32_SIZE 32

/** SHA-256 state after processing first 64 bytes of header76 (scalar compress). */
void btc_midstate_header76(const uint8_t header76[BTC_HEADER76_SIZE], uint32_t mid[8]);

/** First SHA-256 of 80-byte block header using precomputed midstate + nonce. */
void btc_first_sha_from_mid(const uint8_t header76[BTC_HEADER76_SIZE], const uint32_t mid[8], uint32_t nonce,
                            uint8_t digest32[BTC_HASH32_SIZE]);

/** Double SHA-256 from midstate path (matches miner midstate logic). */
void btc_double_sha_from_mid(const uint8_t header76[BTC_HEADER76_SIZE], const uint32_t mid[8], uint32_t nonce,
                             uint8_t out32[BTC_HASH32_SIZE]);

void btc_header80_from_76(const uint8_t header76[BTC_HEADER76_SIZE], uint32_t nonce, uint8_t header80[BTC_HEADER80_SIZE]);

void btc_first_sha_full(const uint8_t header76[BTC_HEADER76_SIZE], uint32_t nonce, uint8_t digest32[BTC_HASH32_SIZE]);
void btc_double_sha_full(const uint8_t header76[BTC_HEADER76_SIZE], uint32_t nonce, uint8_t out32[BTC_HASH32_SIZE]);

/** Host-only: primary self-test mid vs full double for valid header bytes; logs to GPU_SHA_SelfTest. Returns 1 if ok. */
int gpu_sha_host_selftest(void);

/** Fixed 76-byte test vector (same pattern as CPU SHA self-test). */
const uint8_t *btc_gpu_selftest_header76(void);

#endif
