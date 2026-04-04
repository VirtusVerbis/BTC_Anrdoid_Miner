#ifndef SHA256_SCAN_H
#define SHA256_SCAN_H

#include <stdint.h>

int scan_nonces_dispatch(int flavor, const uint8_t *header76, uint32_t start, uint32_t end, const uint8_t *target);
int cpu_sha_selftest_flavor(int flavor);

#endif
