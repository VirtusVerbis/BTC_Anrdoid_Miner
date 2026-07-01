#!/usr/bin/env python3
"""Generate sha256_compress_first_mid_scalar.inc for Bitcoin first-SHA256 block 1 (midstate tail)."""

from __future__ import annotations

import hashlib
import random
import struct
import sys
from enum import Enum, auto
from pathlib import Path
from typing import List, Tuple

MASK32 = 0xFFFFFFFF

K = [
    0x428A2F98, 0x71374491, 0xB5C0FBCF, 0xE9B5DBA5, 0x3956C25B, 0x59F111F1, 0x923F82A4, 0xAB1C5ED5,
    0xD807AA98, 0x12835B01, 0x243185BE, 0x550C7DC3, 0x72BE5D74, 0x80DEB1FE, 0x9BDC06A7, 0xC19BF174,
    0xE49B69C1, 0xEFBE4786, 0x0FC19DC6, 0x240CA1CC, 0x2DE92C6F, 0x4A7484AA, 0x5CB0A9DC, 0x76F988DA,
    0x983E5152, 0xA831C66D, 0xB00327C8, 0xBF597FC7, 0xC6E00BF3, 0xD5A79147, 0x06CA6351, 0x14292967,
    0x27B70A85, 0x2E1B2138, 0x4D2C6DFC, 0x53380D13, 0x650A7354, 0x766A0ABB, 0x81C2C92E, 0x92722C85,
    0xA2BFE8A1, 0xA81A664B, 0xC24B8B70, 0xC76C51A3, 0xD192E819, 0xD6990624, 0xF40E3585, 0x106AA070,
    0x19A4C116, 0x1E376C08, 0x2748774C, 0x34B0BCB5, 0x391C0CB3, 0x4ED8AA4A, 0x5B9CCA4F, 0x682E6FF3,
    0x748F82EE, 0x78A5636F, 0x84C87814, 0x8CC70208, 0x90BEFFFA, 0xA4506CEB, 0xBEF9A3F7, 0xC67178F2,
]

SHA256_IV = [
    0x6A09E667, 0xBB67AE85, 0x3C6EF372, 0xA54FF53A,
    0x510E527F, 0x9B05688C, 0x1F83D9AB, 0x5BE0CD19,
]

SCRIPT_DIR = Path(__file__).resolve().parent
OUT_INC = SCRIPT_DIR.parent / "sha256_compress_first_mid_scalar.inc"
GPU_SELFTEST_HEADER76 = bytes(range(1, 77))


class SlotKind(Enum):
    CONST = auto()
    VAR = auto()


def sig0_int(x: int) -> int:
    x &= MASK32
    return (((x >> 7) | (x << 25)) ^ ((x >> 18) | (x << 14)) ^ (x >> 3)) & MASK32


def sig1_int(x: int) -> int:
    x &= MASK32
    return (((x >> 17) | (x << 15)) ^ ((x >> 19) | (x << 13)) ^ (x >> 10)) & MASK32


def glsl_const(v: int) -> str:
    return f"0x{v & MASK32:08x}u"


class WSlot:
    __slots__ = ("kind", "value")

    def __init__(self, kind: SlotKind, value: int = 0) -> None:
        self.kind = kind
        self.value = value & MASK32

    @staticmethod
    def const(v: int) -> "WSlot":
        return WSlot(SlotKind.CONST, v)

    @staticmethod
    def var() -> "WSlot":
        return WSlot(SlotKind.VAR, 0)

    def eval(self, w_int: List[int], idx: int) -> int:
        if self.kind == SlotKind.CONST:
            return self.value
        return w_int[idx] & MASK32


def term_glsl(slot: WSlot, idx: int) -> str:
    if slot.kind == SlotKind.CONST:
        return glsl_const(slot.value)
    return f"w[{idx}]"


def sig0_glsl(slot: WSlot, idx: int) -> str:
    if slot.kind == SlotKind.CONST:
        return glsl_const(sig0_int(slot.value))
    return f"SIG0(w[{idx}])"


def sig1_glsl(slot: WSlot, idx: int) -> str:
    if slot.kind == SlotKind.CONST:
        return glsl_const(sig1_int(slot.value))
    return f"SIG1(w[{idx}])"


def initial_slots() -> List[WSlot]:
    return (
        [WSlot.var() for _ in range(3)]
        + [WSlot.var()]
        + [WSlot.const(0x80000000)]
        + [WSlot.const(0) for _ in range(10)]
        + [WSlot.const(0x00000280)]
    )


def compress_reference_int(w_words: List[int], state: List[int]) -> List[int]:
    w = [x & MASK32 for x in w_words]
    a, b, c_, d, e, f, g, h = [x & MASK32 for x in state]

    def rnd(i: int, wi: int) -> None:
        nonlocal a, b, c_, d, e, f, g, h
        ch_v = ((e & f) ^ ((~e) & g)) & MASK32
        maj_v = ((a & b) ^ (a & c_) ^ (b & c_)) & MASK32
        ep1_v = (((e >> 6) | (e << 26)) ^ ((e >> 11) | (e << 21)) ^ ((e >> 25) | (e << 7))) & MASK32
        ep0_v = (((a >> 2) | (a << 30)) ^ ((a >> 13) | (a << 19)) ^ ((a >> 22) | (a << 10))) & MASK32
        t1 = (h + ep1_v + ch_v + K[i] + (wi & MASK32)) & MASK32
        t2 = (ep0_v + maj_v) & MASK32
        h, g, f, e, d, c_, b, a = g, f, e, (d + t1) & MASK32, c_, b, a, (t1 + t2) & MASK32

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

    return [(state[i] + x) & MASK32 for i, x in enumerate([a, b, c_, d, e, f, g, h])]


def be_word_from_bytes(data: bytes, off: int) -> int:
    return (
        (data[off] << 24)
        | (data[off + 1] << 16)
        | (data[off + 2] << 8)
        | data[off + 3]
    ) & MASK32


def bswap32(x: int) -> int:
    x &= MASK32
    return (
        ((x & 0xFF) << 24)
        | (((x >> 8) & 0xFF) << 16)
        | (((x >> 16) & 0xFF) << 8)
        | ((x >> 24) & 0xFF)
    ) & MASK32


def header_tail_words(header76: bytes) -> Tuple[int, int, int]:
    return (
        be_word_from_bytes(header76, 64),
        be_word_from_bytes(header76, 68),
        be_word_from_bytes(header76, 72),
    )


def block1_w_words(h0: int, h1: int, h2: int, nonce_word: int) -> List[int]:
    return [h0 & MASK32, h1 & MASK32, h2 & MASK32, nonce_word & MASK32, 0x80000000] + [0] * 10 + [0x00000280]


def midstate_reference(header76: bytes) -> List[int]:
    w = [be_word_from_bytes(header76, i * 4) for i in range(16)]
    return compress_reference_int(w, list(SHA256_IV))


def first_mid_reference(mid: List[int], h0: int, h1: int, h2: int, nonce_word: int) -> List[int]:
    return compress_reference_int(block1_w_words(h0, h1, h2, nonce_word), list(mid))


def simulate_slots(mid: List[int], h0: int, h1: int, h2: int, nonce_word: int) -> List[int]:
    w_int = block1_w_words(h0, h1, h2, nonce_word)
    slots = initial_slots()
    a, b, c_, d, e, f, g, h = [x & MASK32 for x in mid]

    def rnd(i: int, wi: int) -> None:
        nonlocal a, b, c_, d, e, f, g, h
        ch_v = ((e & f) ^ ((~e) & g)) & MASK32
        maj_v = ((a & b) ^ (a & c_) ^ (b & c_)) & MASK32
        ep1_v = (((e >> 6) | (e << 26)) ^ ((e >> 11) | (e << 21)) ^ ((e >> 25) | (e << 7))) & MASK32
        ep0_v = (((a >> 2) | (a << 30)) ^ ((a >> 13) | (a << 19)) ^ ((a >> 22) | (a << 10))) & MASK32
        t1 = (h + ep1_v + ch_v + K[i] + wi) & MASK32
        t2 = (ep0_v + maj_v) & MASK32
        h, g, f, e, d, c_, b, a = g, f, e, (d + t1) & MASK32, c_, b, a, (t1 + t2) & MASK32

    for i in range(16):
        rnd(i, w_int[i])
    for i in range(16, 64):
        idx = i & 15
        i16, i15, i7, i2 = (i - 16) & 15, (i - 15) & 15, (i - 7) & 15, (i - 2) & 15
        wi = (
            slots[i16].eval(w_int, i16)
            + sig0_int(slots[i15].eval(w_int, i15))
            + slots[i7].eval(w_int, i7)
            + sig1_int(slots[i2].eval(w_int, i2))
        ) & MASK32
        w_int[idx] = wi
        slots[idx] = WSlot.var()
        rnd(i, wi)

    return [(mid[i] + x) & MASK32 for i, x in enumerate([a, b, c_, d, e, f, g, h])]


def generate_inc() -> str:
    lines: List[str] = [
        "// Auto-generated by tools/gen_sha256_first_mid_compress.py — do not edit.",
        "// Bitcoin first-SHA256 block 1: header tail + nonce + 80-byte padding (640-bit length).",
        "// Requires SHA256_ROUND, SIG0, SIG1, and K[] from miner.comp.",
        "",
        "void sha256_compress_first_mid_16w(",
        "    uint header64_67, uint header68_71, uint header72_75, uint nonceW_sha_be,",
        "    inout uint s[8]) {",
        "    uint w[16];",
        "    w[0] = header64_67; w[1] = header68_71; w[2] = header72_75; w[3] = nonceW_sha_be;",
        "    w[4] = 0x80000000u; w[5] = 0u; w[6] = 0u; w[7] = 0u; w[8] = 0u; w[9] = 0u; w[10] = 0u; w[11] = 0u;",
        "    w[12] = 0u; w[13] = 0u; w[14] = 0u; w[15] = 0x00000280u;",
        "    uint a = s[0], b = s[1], c = s[2], d = s[3], e = s[4], f = s[5], g = s[6], h = s[7];",
    ]

    slots = initial_slots()
    for i in range(64):
        if i < 16:
            lines.append(f"    SHA256_ROUND(a, b, c, d, e, f, g, h, w[{i}], K[{i}]);")
        else:
            idx = i & 15
            i16, i15, i7, i2 = (i - 16) & 15, (i - 15) & 15, (i - 7) & 15, (i - 2) & 15
            s16, s15, s7, s2 = slots[i16], slots[i15], slots[i7], slots[i2]
            update = (
                f"w[{idx}] = {term_glsl(s16, i16)} + {sig0_glsl(s15, i15)} + "
                f"{term_glsl(s7, i7)} + {sig1_glsl(s2, i2)};"
            )
            lines.append(f"    {update}")
            lines.append(f"    SHA256_ROUND(a, b, c, d, e, f, g, h, w[{idx}], K[{i}]);")
            slots[idx] = WSlot.var()

    lines.extend([
        "    s[0] += a; s[1] += b; s[2] += c; s[3] += d; s[4] += e; s[5] += f; s[6] += g; s[7] += h;",
        "}",
        "",
    ])
    return "\n".join(lines)


def digest_to_be_words(d32: bytes) -> List[int]:
    return [struct.unpack(">I", d32[i : i + 4])[0] for i in range(0, 32, 4)]


def gpu_selftest_first_digest_words() -> List[int]:
    h80 = GPU_SELFTEST_HEADER76 + struct.pack("<I", 1)
    return digest_to_be_words(hashlib.sha256(h80).digest())


def run_selftests() -> None:
    rng = random.Random(0xF157B101)

    def check(mid: List[int], h0: int, h1: int, h2: int, nonce_word: int, label: str) -> None:
        ref = first_mid_reference(mid, h0, h1, h2, nonce_word)
        got = simulate_slots(mid, h0, h1, h2, nonce_word)
        if ref != got:
            raise SystemExit(f"mismatch {label}: ref={ref} got={got}")

    mid_gpu = midstate_reference(GPU_SELFTEST_HEADER76)
    h0, h1, h2 = header_tail_words(GPU_SELFTEST_HEADER76)
    nonce1 = bswap32(1)
    check(mid_gpu, h0, h1, h2, nonce1, "gpu_selftest")
    if first_mid_reference(mid_gpu, h0, h1, h2, nonce1) != gpu_selftest_first_digest_words():
        raise SystemExit("gpu_selftest hashlib cross-check failed")

    check([0] * 8, 0, 0, 0, 0, "zero_mid_zero_tail")
    check([MASK32] * 8, MASK32, MASK32, MASK32, bswap32(0xDEADBEEF), "all_ones")
    for bit in range(8):
        mid = [0] * 8
        mid[bit] = 1
        check(mid, 1, 2, 3, bswap32(4), f"single_bit_mid{bit}")

    for _ in range(256):
        mid = [rng.getrandbits(32) for _ in range(8)]
        h0 = rng.getrandbits(32)
        h1 = rng.getrandbits(32)
        h2 = rng.getrandbits(32)
        nonce_word = bswap32(rng.getrandbits(32))
        check(mid, h0, h1, h2, nonce_word, "random")

    for _ in range(64):
        header76 = bytes(rng.getrandbits(8) for _ in range(76))
        nonce = rng.getrandbits(32)
        h80 = header76 + struct.pack("<I", nonce)
        mid = midstate_reference(header76)
        h0, h1, h2 = header_tail_words(header76)
        nonce_word = bswap32(nonce)
        ref_words = digest_to_be_words(hashlib.sha256(h80).digest())
        got_ref = first_mid_reference(mid, h0, h1, h2, nonce_word)
        got_sim = simulate_slots(mid, h0, h1, h2, nonce_word)
        if got_ref != ref_words or got_sim != ref_words:
            raise SystemExit("hashlib cross-check failed")

    print(f"self-test OK ({256 + 64 + 11} vectors)")


def main() -> int:
    run_selftests()
    content = generate_inc()
    OUT_INC.write_text(content, encoding="utf-8", newline="\n")
    print(f"Wrote {OUT_INC} ({len(content)} bytes)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
