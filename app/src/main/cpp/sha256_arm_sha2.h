#ifndef SHA256_ARM_SHA2_H
#define SHA256_ARM_SHA2_H

#include <stddef.h>
#include <stdint.h>

#if defined(__aarch64__)

/** ARMv8 SHA256 crypto extension; one or more 64-byte big-endian blocks. */
void sha256_arm_compress(uint32_t state[8], const uint8_t *chunk, size_t blocks);

#else

static inline void sha256_arm_compress(uint32_t state[8], const uint8_t *chunk, size_t blocks) {
    (void)state;
    (void)chunk;
    (void)blocks;
}

#endif

#endif
