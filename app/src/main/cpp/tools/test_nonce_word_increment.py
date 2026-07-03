#!/usr/bin/env python3
"""Verify increment_nonce_sha_word matches bswap32(nonce+1) for sequential nonces."""

from __future__ import annotations

MASK32 = 0xFFFFFFFF


def bswap32(x: int) -> int:
    x &= MASK32
    return (
        ((x & 0xFF) << 24)
        | (((x >> 8) & 0xFF) << 16)
        | (((x >> 16) & 0xFF) << 8)
        | ((x >> 24) & 0xFF)
    ) & MASK32


def nonce_sha_word(nonce: int) -> int:
    return bswap32(nonce)


def increment_nonce_sha_word(w: int) -> int:
    w &= MASK32
    if (w & 0xFF000000) != 0xFF000000:
        return (w + 0x01000000) & MASK32
    b0 = ((w >> 24) & 0xFF) + 1
    w = (w & 0x00FFFFFF) | ((b0 & 0xFF) << 24)
    if b0 <= 0xFF:
        return w & MASK32
    b1 = ((w >> 16) & 0xFF) + 1
    w = (w & 0xFF00FFFF) | ((b1 & 0xFF) << 16)
    if b1 <= 0xFF:
        return w & MASK32
    b2 = ((w >> 8) & 0xFF) + 1
    w = (w & 0xFFFF00FF) | ((b2 & 0xFF) << 8)
    if b2 <= 0xFF:
        return w & MASK32
    b3 = (w & 0xFF) + 1
    return ((w & 0xFFFFFF00) | (b3 & 0xFF)) & MASK32


def test_chained_increment_range(limit: int) -> None:
    w = 0
    for n in range(limit):
        expected = nonce_sha_word(n)
        if w != expected:
            raise SystemExit(f"chain mismatch at n={n}: got {w:#010x} expected {expected:#010x}")
        w = increment_nonce_sha_word(w)


def test_explicit_boundaries() -> None:
    cases = [
        (0, 1),
        (0xFF, 0x100),
        (0xFFFF, 0x10000),
        (0xFFFFFF, 0x1000000),
        (0xFFFFFFFF, 0),
        (254, 255),
        (255, 256),
        (65534, 65535),
        (65535, 65536),
    ]
    for n, n_next in cases:
        w = nonce_sha_word(n)
        got = increment_nonce_sha_word(w)
        expected = nonce_sha_word(n_next)
        if got != expected:
            raise SystemExit(
                f"boundary {n:#x}->{n_next:#x}: increment({w:#010x})={got:#010x} expected {expected:#010x}"
            )


def test_uvec2_batch_builder() -> None:
    for n0 in (0, 1, 255, 256, 65535, 65536, 0xDEADBEEF):
        nword0 = nonce_sha_word(n0)
        n1 = increment_nonce_sha_word(nword0)
        expected = (nonce_sha_word(n0), nonce_sha_word(n0 + 1))
        if (nword0, n1) != expected:
            raise SystemExit(f"uvec2 batch mismatch at n0={n0:#x}: got {(nword0, n1)} expected {expected}")


def test_uvec4_batch_builder() -> None:
    for n0 in (0, 100, 255, 256, 65535, 0xFFFFFF00):
        w = nonce_sha_word(n0)
        chain = [w]
        for _ in range(3):
            chain.append(increment_nonce_sha_word(chain[-1]))
        expected = tuple(nonce_sha_word(n0 + i) for i in range(4))
        if tuple(chain) != expected:
            raise SystemExit(f"uvec4 batch mismatch at n0={n0:#x}: got {tuple(chain)} expected {expected}")


def main() -> int:
    test_explicit_boundaries()
    test_chained_increment_range(65536)
    test_uvec2_batch_builder()
    test_uvec4_batch_builder()
    print("test_nonce_word_increment OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
