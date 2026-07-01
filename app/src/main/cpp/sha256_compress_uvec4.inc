// SHA256_ROUND_4WAY macro for constant-folded compressors.
// Included from miner_uvec4.comp after K[], ROR/SIG/EP macros, broadcast_uvec4.

#define SHA256_ROUND_4WAY(a, b, c, d, e, f, g, h, wi, ki) \
    do { \
        uvec4 ch = (e & f) ^ (~e & g); \
        uvec4 maj = (a & b) ^ (a & c) ^ (b & c); \
        uvec4 t1 = h + EP1(e) + ch + broadcast_uvec4(ki) + (wi); \
        uvec4 t2 = EP0(a) + maj; \
        h = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2; \
    } while (false)
