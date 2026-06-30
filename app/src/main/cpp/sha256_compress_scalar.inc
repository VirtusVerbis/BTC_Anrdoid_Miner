// Generated-style include: fully unrolled 64-round SHA-256 block compressor (scalar).
// Included from miner.comp after K[], ROR/SIG/EP macros.

#define SHA256_ROUND(a, b, c, d, e, f, g, h, wi, ki) \
    do { \
        uint ch = (e & f) ^ (~e & g); \
        uint maj = (a & b) ^ (a & c) ^ (b & c); \
        uint t1 = h + EP1(e) + ch + ki + wi; \
        uint t2 = EP0(a) + maj; \
        h = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2; \
    } while (false)

void sha256_compress_16w(inout uint s[8], inout uint w[16]) {
    uint a = s[0], b = s[1], c = s[2], d = s[3], e = s[4], f = s[5], g = s[6], h = s[7];
    uint wi;
    SHA256_ROUND(a, b, c, d, e, f, g, h, w[0], K[0]);
    SHA256_ROUND(a, b, c, d, e, f, g, h, w[1], K[1]);
    SHA256_ROUND(a, b, c, d, e, f, g, h, w[2], K[2]);
    SHA256_ROUND(a, b, c, d, e, f, g, h, w[3], K[3]);
    SHA256_ROUND(a, b, c, d, e, f, g, h, w[4], K[4]);
    SHA256_ROUND(a, b, c, d, e, f, g, h, w[5], K[5]);
    SHA256_ROUND(a, b, c, d, e, f, g, h, w[6], K[6]);
    SHA256_ROUND(a, b, c, d, e, f, g, h, w[7], K[7]);
    SHA256_ROUND(a, b, c, d, e, f, g, h, w[8], K[8]);
    SHA256_ROUND(a, b, c, d, e, f, g, h, w[9], K[9]);
    SHA256_ROUND(a, b, c, d, e, f, g, h, w[10], K[10]);
    SHA256_ROUND(a, b, c, d, e, f, g, h, w[11], K[11]);
    SHA256_ROUND(a, b, c, d, e, f, g, h, w[12], K[12]);
    SHA256_ROUND(a, b, c, d, e, f, g, h, w[13], K[13]);
    SHA256_ROUND(a, b, c, d, e, f, g, h, w[14], K[14]);
    SHA256_ROUND(a, b, c, d, e, f, g, h, w[15], K[15]);
    wi = w[0] = w[0] + SIG0(w[1]) + w[9] + SIG1(w[14]);
    SHA256_ROUND(a, b, c, d, e, f, g, h, wi, K[16]);
    wi = w[1] = w[1] + SIG0(w[2]) + w[10] + SIG1(w[15]);
    SHA256_ROUND(a, b, c, d, e, f, g, h, wi, K[17]);
    wi = w[2] = w[2] + SIG0(w[3]) + w[11] + SIG1(w[0]);
    SHA256_ROUND(a, b, c, d, e, f, g, h, wi, K[18]);
    wi = w[3] = w[3] + SIG0(w[4]) + w[12] + SIG1(w[1]);
    SHA256_ROUND(a, b, c, d, e, f, g, h, wi, K[19]);
    wi = w[4] = w[4] + SIG0(w[5]) + w[13] + SIG1(w[2]);
    SHA256_ROUND(a, b, c, d, e, f, g, h, wi, K[20]);
    wi = w[5] = w[5] + SIG0(w[6]) + w[14] + SIG1(w[3]);
    SHA256_ROUND(a, b, c, d, e, f, g, h, wi, K[21]);
    wi = w[6] = w[6] + SIG0(w[7]) + w[15] + SIG1(w[4]);
    SHA256_ROUND(a, b, c, d, e, f, g, h, wi, K[22]);
    wi = w[7] = w[7] + SIG0(w[8]) + w[0] + SIG1(w[5]);
    SHA256_ROUND(a, b, c, d, e, f, g, h, wi, K[23]);
    wi = w[8] = w[8] + SIG0(w[9]) + w[1] + SIG1(w[6]);
    SHA256_ROUND(a, b, c, d, e, f, g, h, wi, K[24]);
    wi = w[9] = w[9] + SIG0(w[10]) + w[2] + SIG1(w[7]);
    SHA256_ROUND(a, b, c, d, e, f, g, h, wi, K[25]);
    wi = w[10] = w[10] + SIG0(w[11]) + w[3] + SIG1(w[8]);
    SHA256_ROUND(a, b, c, d, e, f, g, h, wi, K[26]);
    wi = w[11] = w[11] + SIG0(w[12]) + w[4] + SIG1(w[9]);
    SHA256_ROUND(a, b, c, d, e, f, g, h, wi, K[27]);
    wi = w[12] = w[12] + SIG0(w[13]) + w[5] + SIG1(w[10]);
    SHA256_ROUND(a, b, c, d, e, f, g, h, wi, K[28]);
    wi = w[13] = w[13] + SIG0(w[14]) + w[6] + SIG1(w[11]);
    SHA256_ROUND(a, b, c, d, e, f, g, h, wi, K[29]);
    wi = w[14] = w[14] + SIG0(w[15]) + w[7] + SIG1(w[12]);
    SHA256_ROUND(a, b, c, d, e, f, g, h, wi, K[30]);
    wi = w[15] = w[15] + SIG0(w[0]) + w[8] + SIG1(w[13]);
    SHA256_ROUND(a, b, c, d, e, f, g, h, wi, K[31]);
    wi = w[0] = w[0] + SIG0(w[1]) + w[9] + SIG1(w[14]);
    SHA256_ROUND(a, b, c, d, e, f, g, h, wi, K[32]);
    wi = w[1] = w[1] + SIG0(w[2]) + w[10] + SIG1(w[15]);
    SHA256_ROUND(a, b, c, d, e, f, g, h, wi, K[33]);
    wi = w[2] = w[2] + SIG0(w[3]) + w[11] + SIG1(w[0]);
    SHA256_ROUND(a, b, c, d, e, f, g, h, wi, K[34]);
    wi = w[3] = w[3] + SIG0(w[4]) + w[12] + SIG1(w[1]);
    SHA256_ROUND(a, b, c, d, e, f, g, h, wi, K[35]);
    wi = w[4] = w[4] + SIG0(w[5]) + w[13] + SIG1(w[2]);
    SHA256_ROUND(a, b, c, d, e, f, g, h, wi, K[36]);
    wi = w[5] = w[5] + SIG0(w[6]) + w[14] + SIG1(w[3]);
    SHA256_ROUND(a, b, c, d, e, f, g, h, wi, K[37]);
    wi = w[6] = w[6] + SIG0(w[7]) + w[15] + SIG1(w[4]);
    SHA256_ROUND(a, b, c, d, e, f, g, h, wi, K[38]);
    wi = w[7] = w[7] + SIG0(w[8]) + w[0] + SIG1(w[5]);
    SHA256_ROUND(a, b, c, d, e, f, g, h, wi, K[39]);
    wi = w[8] = w[8] + SIG0(w[9]) + w[1] + SIG1(w[6]);
    SHA256_ROUND(a, b, c, d, e, f, g, h, wi, K[40]);
    wi = w[9] = w[9] + SIG0(w[10]) + w[2] + SIG1(w[7]);
    SHA256_ROUND(a, b, c, d, e, f, g, h, wi, K[41]);
    wi = w[10] = w[10] + SIG0(w[11]) + w[3] + SIG1(w[8]);
    SHA256_ROUND(a, b, c, d, e, f, g, h, wi, K[42]);
    wi = w[11] = w[11] + SIG0(w[12]) + w[4] + SIG1(w[9]);
    SHA256_ROUND(a, b, c, d, e, f, g, h, wi, K[43]);
    wi = w[12] = w[12] + SIG0(w[13]) + w[5] + SIG1(w[10]);
    SHA256_ROUND(a, b, c, d, e, f, g, h, wi, K[44]);
    wi = w[13] = w[13] + SIG0(w[14]) + w[6] + SIG1(w[11]);
    SHA256_ROUND(a, b, c, d, e, f, g, h, wi, K[45]);
    wi = w[14] = w[14] + SIG0(w[15]) + w[7] + SIG1(w[12]);
    SHA256_ROUND(a, b, c, d, e, f, g, h, wi, K[46]);
    wi = w[15] = w[15] + SIG0(w[0]) + w[8] + SIG1(w[13]);
    SHA256_ROUND(a, b, c, d, e, f, g, h, wi, K[47]);
    wi = w[0] = w[0] + SIG0(w[1]) + w[9] + SIG1(w[14]);
    SHA256_ROUND(a, b, c, d, e, f, g, h, wi, K[48]);
    wi = w[1] = w[1] + SIG0(w[2]) + w[10] + SIG1(w[15]);
    SHA256_ROUND(a, b, c, d, e, f, g, h, wi, K[49]);
    wi = w[2] = w[2] + SIG0(w[3]) + w[11] + SIG1(w[0]);
    SHA256_ROUND(a, b, c, d, e, f, g, h, wi, K[50]);
    wi = w[3] = w[3] + SIG0(w[4]) + w[12] + SIG1(w[1]);
    SHA256_ROUND(a, b, c, d, e, f, g, h, wi, K[51]);
    wi = w[4] = w[4] + SIG0(w[5]) + w[13] + SIG1(w[2]);
    SHA256_ROUND(a, b, c, d, e, f, g, h, wi, K[52]);
    wi = w[5] = w[5] + SIG0(w[6]) + w[14] + SIG1(w[3]);
    SHA256_ROUND(a, b, c, d, e, f, g, h, wi, K[53]);
    wi = w[6] = w[6] + SIG0(w[7]) + w[15] + SIG1(w[4]);
    SHA256_ROUND(a, b, c, d, e, f, g, h, wi, K[54]);
    wi = w[7] = w[7] + SIG0(w[8]) + w[0] + SIG1(w[5]);
    SHA256_ROUND(a, b, c, d, e, f, g, h, wi, K[55]);
    wi = w[8] = w[8] + SIG0(w[9]) + w[1] + SIG1(w[6]);
    SHA256_ROUND(a, b, c, d, e, f, g, h, wi, K[56]);
    wi = w[9] = w[9] + SIG0(w[10]) + w[2] + SIG1(w[7]);
    SHA256_ROUND(a, b, c, d, e, f, g, h, wi, K[57]);
    wi = w[10] = w[10] + SIG0(w[11]) + w[3] + SIG1(w[8]);
    SHA256_ROUND(a, b, c, d, e, f, g, h, wi, K[58]);
    wi = w[11] = w[11] + SIG0(w[12]) + w[4] + SIG1(w[9]);
    SHA256_ROUND(a, b, c, d, e, f, g, h, wi, K[59]);
    wi = w[12] = w[12] + SIG0(w[13]) + w[5] + SIG1(w[10]);
    SHA256_ROUND(a, b, c, d, e, f, g, h, wi, K[60]);
    wi = w[13] = w[13] + SIG0(w[14]) + w[6] + SIG1(w[11]);
    SHA256_ROUND(a, b, c, d, e, f, g, h, wi, K[61]);
    wi = w[14] = w[14] + SIG0(w[15]) + w[7] + SIG1(w[12]);
    SHA256_ROUND(a, b, c, d, e, f, g, h, wi, K[62]);
    wi = w[15] = w[15] + SIG0(w[0]) + w[8] + SIG1(w[13]);
    SHA256_ROUND(a, b, c, d, e, f, g, h, wi, K[63]);
    s[0] += a; s[1] += b; s[2] += c; s[3] += d; s[4] += e; s[5] += f; s[6] += g; s[7] += h;
}
