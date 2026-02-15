#ifndef SHA256_H
#define SHA256_H

#include <stddef.h>
#include <stdint.h>

#define SHA256_DIGEST_SIZE 32

void sha256(const uint8_t *data, size_t len, uint8_t *out);
void sha256_double(const uint8_t *data, size_t len, uint8_t *out);

#endif
