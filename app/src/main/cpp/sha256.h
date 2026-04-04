#ifndef SHA256_H
#define SHA256_H

#include <stddef.h>
#include <stdint.h>

#define SHA256_DIGEST_SIZE 32

void sha256(const uint8_t *data, size_t len, uint8_t *out);
/** Double SHA-256; authoritative reference for validation and JNI header hash. */
void sha256_double(const uint8_t *data, size_t len, uint8_t *out);

/** One SHA-256 block compression (big-endian 16 words in [block]); updates [state] (8 words, a..h). */
void sha256_compress(uint32_t state[8], const uint8_t block[64]);

/** Standard SHA-256 IV into [state] (same as start of [sha256]). */
void sha256_initial_state(uint32_t state[8]);

/**
 * Build the final 64-byte block for a message that is exactly 80 bytes (one full block + 16-byte tail).
 * Matches [sha256] padding/length for tailBytes == last 16 bytes of the message; total_bitlen must be 80*8.
 */
void sha256_pad_second_block_80(const uint8_t last16[16], uint8_t block[64]);

#endif
