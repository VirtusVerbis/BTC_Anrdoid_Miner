#!/usr/bin/env python3
"""Regression tests for progressive pool-target rejection (second SHA-256 compress)."""

from __future__ import annotations

import sys
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPT_DIR))

import sha256_target_checkpoints as tc  # noqa: E402
from gen_sha256_second_compress import second_hash_reference  # noqa: E402


def test_progressive_matches_full_reference() -> None:
    import random

    rng = random.Random(0x7A9E)
    diff1 = bytes.fromhex("00000000ffff0000000000000000000000000000000000000000000000000000")
    for target in [diff1, bytes([0xFF] * 32), bytes([0] * 32)]:
        for _ in range(500):
            h = [rng.getrandbits(32) for _ in range(8)]
            ref = second_hash_reference(h)
            full = tc.hash_meets_target_words(ref, target)
            prog, _, out = tc.second_compress_progressive(h, target)
            assert prog == full, (h, target.hex()[:16], full, prog)
            if prog:
                assert out == ref


def test_no_false_reject_on_valid_shares() -> None:
    import random

    rng = random.Random(0xFA1E)
    for _ in range(2000):
        h = [rng.getrandbits(32) for _ in range(8)]
        ref = second_hash_reference(h)
        target = bytes(rng.getrandbits(8) for _ in range(32))
        if not tc.hash_meets_target_words(ref, target):
            continue
        prog, aborted, out = tc.second_compress_progressive(h, target)
        assert prog, f"false reject aborted_at={aborted}"
        assert out == ref


def main() -> int:
    test_progressive_matches_full_reference()
    test_no_false_reject_on_valid_shares()
    print("test_target_rejection OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
