#ifndef SHA256_SCAN_H
#define SHA256_SCAN_H

#include <stdint.h>

int scan_nonces_dispatch(int flavor, const uint8_t *header76, uint32_t start, uint32_t end, const uint8_t *target);
void cpu_sha256_double_flavor(int flavor, const uint8_t *header76, uint32_t nonce, uint8_t out[32]);
int cpu_sha_selftest_flavor(int flavor);

#endif
