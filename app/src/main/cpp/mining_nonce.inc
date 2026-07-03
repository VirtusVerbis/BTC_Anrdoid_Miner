// Shared nonce -> SHA schedule word helpers for Bitcoin header byte 76-79 (LE nonce, BE SHA word).
// LE nonce byte 0 (LSB) maps to bits 24-31 of the scheduled word; increment propagates MSB-first.

/* Last 32-bit word of the 80-byte Bitcoin header: bytes 76-79 are little-endian nonce on the wire;
 * SHA-256 schedules big-endian words, so word = bswap32(nonce) (e.g. nonce 1 -> 0x01000000). */
uint nonce_sha_word(uint nonce) {
    return ((nonce & 0xffu) << 24) | (((nonce >> 8) & 0xffu) << 16)
         | (((nonce >> 16) & 0xffu) << 8) | ((nonce >> 24) & 0xffu);
}

/* bswap32(nonce+1) given w = bswap32(nonce); carry-safe across LE byte rollovers. */
uint increment_nonce_sha_word(uint w) {
    if ((w & 0xFF000000u) != 0xFF000000u)
        return w + 0x01000000u;
    uint b0 = ((w >> 24) & 0xFFu) + 1u;
    w = (w & 0x00FFFFFFu) | ((b0 & 0xFFu) << 24);
    if (b0 <= 0xFFu)
        return w;
    uint b1 = ((w >> 16) & 0xFFu) + 1u;
    w = (w & 0xFF00FFFFu) | ((b1 & 0xFFu) << 16);
    if (b1 <= 0xFFu)
        return w;
    uint b2 = ((w >> 8) & 0xFFu) + 1u;
    w = (w & 0xFFFF00FFu) | ((b2 & 0xFFu) << 8);
    if (b2 <= 0xFFu)
        return w;
    uint b3 = (w & 0xFFu) + 1u;
    return (w & 0xFFFFFF00u) | (b3 & 0xFFu);
}

uvec2 nonce_words_uvec2_from_base(uint nword0) {
    return uvec2(nword0, increment_nonce_sha_word(nword0));
}

uvec4 nonce_words_uvec4_from_base(uint nword0) {
    uint n1 = increment_nonce_sha_word(nword0);
    uint n2 = increment_nonce_sha_word(n1);
    uint n3 = increment_nonce_sha_word(n2);
    return uvec4(nword0, n1, n2, n3);
}
