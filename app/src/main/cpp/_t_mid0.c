#include "btc_header_sha256.h"
#include <stdio.h>
#include <string.h>
extern const uint8_t *btc_gpu_selftest_header76(void);
int main() {
  const uint8_t *h = btc_gpu_selftest_header76();
  uint32_t zmid[8] = {0};
  uint8_t d[32];
  btc_first_sha_from_mid(h, zmid, 1u, d);
  for (int i = 0; i < 32; i++) printf("%02x", d[i]);
  printf("\n");
  return 0;
}
