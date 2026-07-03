#ifndef SHA256_SCAN_H
#define SHA256_SCAN_H

#include <stdint.h>

int scan_nonces_dispatch(int flavor, const uint8_t *header76, uint32_t start, uint32_t end, const uint8_t *target);
int scan_nonces_dispatch_session(uint32_t start, uint32_t end);
void cpu_job_session_begin(const uint8_t *header76, const uint8_t *target, int flavor);
void cpu_job_session_end(void);
int cpu_job_session_active(void);
int cpu_job_session_flavor(void);
void cpu_sha256_double_flavor(int flavor, const uint8_t *header76, uint32_t nonce, uint8_t out[32]);
int cpu_sha_selftest_flavor(int flavor);

#endif
