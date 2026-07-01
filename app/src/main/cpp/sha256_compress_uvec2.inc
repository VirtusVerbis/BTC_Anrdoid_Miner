// SHA256_ROUND_2WAY macro for constant-folded compressors.
// Included from miner_uvec2.comp after K[], ROR/SIG/EP macros, broadcast_uvec2.

#define SHA256_ROUND_2WAY(a, b, c, d, e, f, g, h, wi, ki) \
    do { \
        uvec2 ch = (e & f) ^ (~e & g); \
        uvec2 maj = (a & b) ^ (a & c) ^ (b & c); \
        uvec2 t1 = h + EP1(e) + ch + broadcast_uvec2(ki) + (wi); \
        uvec2 t2 = EP0(a) + maj; \
        h = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2; \
    } while (false)
