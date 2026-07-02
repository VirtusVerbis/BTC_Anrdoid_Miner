#!/usr/bin/env python3
"""Progressive pool-target rejection reference for second SHA-256 compress."""

from __future__ import annotations

import random
import struct
import sys
from pathlib import Path
from typing import List, Optional, Tuple

from gen_sha256_second_compress import (
    K,
    MASK32,
    SHA256_IV,
    second_hash_reference,
    sig0_int,
    sig1_int,
)

# After round N completes, output word hW equals IV[W] + register R (verified by simulation).
CHECKPOINT_H7_ROUND = 60
CHECKPOINT_H7_REG = "e"
CHECKPOINT_H7_IV = 7

CHECKPOINT_H6_ROUND = 61
CHECKPOINT_H6_REG = "e"
CHECKPOINT_H6_IV = 6

SHA256_IV7 = SHA256_IV[7]
SHA256_IV6 = SHA256_IV[6]


def bswap32(x: int) -> int:
    x &= MASK32
    return (
        ((x & 0xFF) << 24)
        | (((x >> 8) & 0xFF) << 16)
        | (((x >> 16) & 0xFF) << 8)
        | ((x >> 24) & 0xFF)
    )


def target_be_word(target: bytes, word_index: int) -> int:
    """First comparison word uses target bytes [0..3], second [4..7], ... as big-endian uint32."""
    off = word_index * 4
    return struct.unpack(">I", target[off : off + 4])[0]


def hash_meets_target_words(out_words: List[int], target: bytes) -> bool:
    """Mirror miner.comp / sha256_scan.c PoW ordering."""
    for i in range(8):
        d = bswap32(out_words[7 - i])
        t = target_be_word(target, i)
        if d != t:
            return d < t
    return True


def hash_meets_target_bytes(out_bytes: bytes, target: bytes) -> bool:
    rev = bytes(out_bytes[31 - i] for i in range(32))
    return rev <= target


def words_to_be_bytes(words: List[int]) -> bytes:
    out = bytearray(32)
    for i, w in enumerate(words):
        out[i * 4 : i * 4 + 4] = struct.pack(">I", w & MASK32)
    return bytes(out)


def second_compress_progressive(
    h_words: List[int], target: bytes
) -> Tuple[bool, Optional[int], List[int]]:
    """
    Run second compress with progressive rejection.
    Returns (meets_target, abort_after_round_or_none, out_words_if_completed).
    abort_after_round is set when sound early reject triggers (hash cannot meet target).
    """
    w = list(h_words) + [0x80000000, 0, 0, 0, 0, 0, 0, 0x00000100]
    a, b, c, d, e, f, g, h = SHA256_IV
    aborted_at: Optional[int] = None
    h7_tied = False

    def rnd(i: int, wi: int) -> None:
        nonlocal a, b, c, d, e, f, g, h
        ch_v = ((e & f) ^ ((~e) & g)) & MASK32
        maj_v = ((a & b) ^ (a & c) ^ (b & c)) & MASK32
        ep1_v = (((e >> 6) | (e << 26)) ^ ((e >> 11) | (e << 21)) ^ ((e >> 25) | (e << 7))) & MASK32
        ep0_v = (((a >> 2) | (a << 30)) ^ ((a >> 13) | (a << 19)) ^ ((a >> 22) | (a << 10))) & MASK32
        t1 = (h + ep1_v + ch_v + K[i] + (wi & MASK32)) & MASK32
        t2 = (ep0_v + maj_v) & MASK32
        h, g, f, e, d, c, b, a = g, f, e, (d + t1) & MASK32, c, b, a, (t1 + t2) & MASK32

    for i in range(16):
        rnd(i, w[i])
    for i in range(16, 64):
        wi = (
            w[(i - 16) & 15]
            + sig0_int(w[(i - 15) & 15])
            + w[(i - 7) & 15]
            + sig1_int(w[(i - 2) & 15])
        ) & MASK32
        w[i & 15] = wi
        rnd(i, wi)

        if i == CHECKPOINT_H7_ROUND:
            h7 = (SHA256_IV[CHECKPOINT_H7_IV] + e) & MASK32
            d7 = bswap32(h7)
            t0 = target_be_word(target, 0)
            if d7 > t0:
                aborted_at = i
                return False, aborted_at, []
            h7_tied = d7 == t0

        if i == CHECKPOINT_H6_ROUND and h7_tied:
            h6 = (SHA256_IV[CHECKPOINT_H6_IV] + e) & MASK32
            d6 = bswap32(h6)
            t1 = target_be_word(target, 1)
            if d6 > t1:
                aborted_at = i
                return False, aborted_at, []

    out = [(SHA256_IV[j] + x) & MASK32 for j, x in enumerate([a, b, c, d, e, f, g, h])]
    return hash_meets_target_words(out, target), aborted_at, out


def verify_checkpoint_mapping(samples: int = 500) -> None:
    rng = random.Random(0xC0DEC)
    for _ in range(samples):
        h = [rng.getrandbits(32) for _ in range(8)]
        ref = second_hash_reference(h)
        w = list(h) + [0x80000000, 0, 0, 0, 0, 0, 0, 0x100]
        a, b, c, d, e, f, g, h_reg = SHA256_IV

        def rnd(i: int, wi: int) -> None:
            nonlocal a, b, c, d, e, f, g, h_reg
            ch_v = ((e & f) ^ ((~e) & g)) & MASK32
            maj_v = ((a & b) ^ (a & c) ^ (b & c)) & MASK32
            ep1_v = (((e >> 6) | (e << 26)) ^ ((e >> 11) | (e << 21)) ^ ((e >> 25) | (e << 7))) & MASK32
            ep0_v = (((a >> 2) | (a << 30)) ^ ((a >> 13) | (a << 19)) ^ ((a >> 22) | (a << 10))) & MASK32
            t1 = (h_reg + ep1_v + ch_v + K[i] + wi) & MASK32
            t2 = (ep0_v + maj_v) & MASK32
            h_reg, g, f, e, d, c, b, a = g, f, e, (d + t1) & MASK32, c, b, a, (t1 + t2) & MASK32
            if i == CHECKPOINT_H7_ROUND:
                got = (SHA256_IV[7] + e) & MASK32
                if got != ref[7]:
                    raise SystemExit(f"h7 checkpoint mismatch: {got:#x} vs {ref[7]:#x}")
            if i == CHECKPOINT_H6_ROUND:
                got = (SHA256_IV[6] + e) & MASK32
                if got != ref[6]:
                    raise SystemExit(f"h6 checkpoint mismatch: {got:#x} vs {ref[6]:#x}")

        for i in range(16):
            rnd(i, w[i])
        for i in range(16, 64):
            wi = (
                w[(i - 16) & 15]
                + sig0_int(w[(i - 15) & 15])
                + w[(i - 7) & 15]
                + sig1_int(w[(i - 2) & 15])
            ) & MASK32
            w[i & 15] = wi
            rnd(i, wi)


def run_property_tests(samples: int = 5000) -> None:
    rng = random.Random(0xB17C0DE)
    diff1_target = bytes.fromhex(
        "00000000ffff0000000000000000000000000000000000000000000000000000"
    )
    edge_targets = [
        diff1_target,
        bytes([0xFF] * 32),
        bytes([0x00] * 32),
        bytes.fromhex("80000000" + "00" * 28),
        bytes.fromhex("00000000" + "ff" * 28),
    ]

    for _ in range(samples):
        h = [rng.getrandbits(32) for _ in range(8)]
        target = bytes(rng.getrandbits(8) for _ in range(32))
        ref = second_hash_reference(h)
        full = hash_meets_target_words(ref, target)
        prog, _, out = second_compress_progressive(h, target)
        if prog != full:
            raise SystemExit(
                f"progressive mismatch: prog={prog} full={full} h={h} target={target.hex()}"
            )
        if prog and out != ref:
            raise SystemExit("progressive hit but wrong digest")

    for target in edge_targets:
        for _ in range(200):
            h = [rng.getrandbits(32) for _ in range(8)]
            ref = second_hash_reference(h)
            full = hash_meets_target_words(ref, target)
            prog, _, out = second_compress_progressive(h, target)
            if prog != full:
                raise SystemExit(f"edge target mismatch target={target.hex()[:16]} prog={prog} full={full}")

    # Early reject must never reject a valid share.
    for _ in range(samples):
        h = [rng.getrandbits(32) for _ in range(8)]
        ref = second_hash_reference(h)
        target = bytes(rng.getrandbits(8) for _ in range(32))
        if not hash_meets_target_words(ref, target):
            continue
        prog, aborted, out = second_compress_progressive(h, target)
        if not prog:
            raise SystemExit(f"false reject on valid share aborted_at={aborted}")
        if out != ref:
            raise SystemExit("progressive hit but wrong digest")


def main() -> int:
    verify_checkpoint_mapping()
    run_property_tests()
    print("sha256_target_checkpoints OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
