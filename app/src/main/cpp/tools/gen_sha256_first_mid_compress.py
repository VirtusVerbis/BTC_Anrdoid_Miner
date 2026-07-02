#!/usr/bin/env python3
"""Generate sha256_compress_first_mid_*.inc for Bitcoin first-SHA256 block 1 (midstate tail)."""

from __future__ import annotations

import hashlib
import random
import struct
import sys
from pathlib import Path
from typing import List, Tuple

from sha256_compress_codegen import (
    ALL_WIDTHS,
    MASK32,
    WSlot,
    WidthConfig,
    WIDTH_UVEC2,
    WIDTH_UVEC4,
    c_const,
    c_zero,
    emit_compress_body,
    emit_compress_body_c,
    emit_compress_loop_body,
    glsl_const,
    glsl_zero,
    header_comment,
    header_comment_c,
)

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
OUT_DIR = SCRIPT_DIR.parent
GPU_SELFTEST_HEADER76 = bytes(range(1, 77))


def sig0_int(x: int) -> int:
    x &= MASK32
    return (((x >> 7) | (x << 25)) ^ ((x >> 18) | (x << 14)) ^ (x >> 3)) & MASK32


def sig1_int(x: int) -> int:
    x &= MASK32
    return (((x >> 17) | (x << 15)) ^ ((x >> 19) | (x << 13)) ^ (x >> 10)) & MASK32


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


def generate_inc(cfg: WidthConfig, *, use_loop: bool = False) -> str:
    t = cfg.glsl_type
    z = glsl_zero(cfg)
    nonce_type = t if cfg.width > 1 else "uint"
    desc = (
        "Bitcoin first-SHA256 block 1: header tail + nonce + 80-byte padding (640-bit length, compact loop)."
        if use_loop
        else "Bitcoin first-SHA256 block 1: header tail + nonce + 80-byte padding (640-bit length)."
    )
    if cfg.width == 1:
        w_header = "    w[0] = header64_67; w[1] = header68_71; w[2] = header72_75; w[3] = nonceW_sha_be;"
    else:
        b = cfg.broadcast_fn
        w_header = (
            f"    w[0] = {b}(header64_67); w[1] = {b}(header68_71); w[2] = {b}(header72_75);"
            " w[3] = nonceW_sha_be;"
        )
    lines: List[str] = [
        header_comment(
            "gen_sha256_first_mid_compress.py",
            desc,
            cfg,
        ),
        "",
        f"void sha256_compress_first_mid_16w{cfg.fn_suffix}(",
        f"    uint header64_67, uint header68_71, uint header72_75, {nonce_type} nonceW_sha_be,",
        f"    inout {t} s[8]) {{",
        f"    {t} w[16];",
        w_header,
        f"    w[4] = {glsl_const(0x80000000, cfg)}; w[5] = {z}; w[6] = {z}; w[7] = {z};",
        f"    w[8] = {z}; w[9] = {z}; w[10] = {z}; w[11] = {z};",
        f"    w[12] = {z}; w[13] = {z}; w[14] = {z}; w[15] = {glsl_const(0x00000280, cfg)};",
        f"    {t} a = s[0], b = s[1], c = s[2], d = s[3], e = s[4], f = s[5], g = s[6], h = s[7];",
    ]
    if use_loop:
        lines.extend(emit_compress_loop_body(cfg))
    else:
        lines.extend(emit_compress_body(initial_slots(), cfg))
    lines.extend([
        "    s[0] += a; s[1] += b; s[2] += c; s[3] += d; s[4] += e; s[5] += f; s[6] += g; s[7] += h;",
        "}",
        "",
    ])
    return "\n".join(lines)


def out_path(cfg: WidthConfig, *, loop: bool = False) -> Path:
    suffix = f"{cfg.file_suffix}_loop" if loop else cfg.file_suffix
    return OUT_DIR / f"sha256_compress_first_mid_{suffix}.inc"


LOOP_WIDTHS = (WIDTH_UVEC2, WIDTH_UVEC4)


def cpu_out_path() -> Path:
    return OUT_DIR / "sha256_compress_first_mid_cpu.inc"


def generate_cpu_inc() -> str:
    z = c_zero()
    lines: List[str] = [
        header_comment_c(
            "gen_sha256_first_mid_compress.py",
            "Bitcoin first-SHA256 block 1: header tail + nonce + 80-byte padding (640-bit length).",
        ),
        "",
        "static void sha256_compress_first_mid_16w_cpu(",
        "    uint32_t header64_67, uint32_t header68_71, uint32_t header72_75,",
        "    uint32_t nonceW_sha_be, uint32_t s[8]) {",
        "    uint32_t w[16];",
        "    w[0] = header64_67; w[1] = header68_71; w[2] = header72_75; w[3] = nonceW_sha_be;",
        f"    w[4] = {c_const(0x80000000)}; w[5] = {z}; w[6] = {z}; w[7] = {z};",
        f"    w[8] = {z}; w[9] = {z}; w[10] = {z}; w[11] = {z};",
        f"    w[12] = {z}; w[13] = {z}; w[14] = {z}; w[15] = {c_const(0x00000280)};",
        "    uint32_t a = s[0], b = s[1], c = s[2], d = s[3], e = s[4], f = s[5], g = s[6], h = s[7];",
    ]
    lines.extend(emit_compress_body_c(initial_slots()))
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


def iter_first_mid_vectors():
    """Yield (label, mid, h0, h1, h2, nonce_word, expected_out) golden vectors for phase 2."""
    rng = random.Random(0xF157B101)

    def vec(mid, h0, h1, h2, nonce_word, label):
        return label, list(mid), h0, h1, h2, nonce_word, first_mid_reference(mid, h0, h1, h2, nonce_word)

    mid_gpu = midstate_reference(GPU_SELFTEST_HEADER76)
    h0, h1, h2 = header_tail_words(GPU_SELFTEST_HEADER76)
    nonce1 = bswap32(1)
    yield vec(mid_gpu, h0, h1, h2, nonce1, "gpu_selftest")
    if first_mid_reference(mid_gpu, h0, h1, h2, nonce1) != gpu_selftest_first_digest_words():
        raise SystemExit("gpu_selftest hashlib cross-check failed")

    yield vec([0] * 8, 0, 0, 0, 0, "zero_mid_zero_tail")
    yield vec([MASK32] * 8, MASK32, MASK32, MASK32, bswap32(0xDEADBEEF), "all_ones")
    for bit in range(8):
        mid = [0] * 8
        mid[bit] = 1
        yield vec(mid, 1, 2, 3, bswap32(4), f"single_bit_mid{bit}")

    for i in range(256):
        mid = [rng.getrandbits(32) for _ in range(8)]
        h0 = rng.getrandbits(32)
        h1 = rng.getrandbits(32)
        h2 = rng.getrandbits(32)
        nonce_word = bswap32(rng.getrandbits(32))
        yield vec(mid, h0, h1, h2, nonce_word, f"random_{i}")

    for i in range(64):
        header76 = bytes(rng.getrandbits(8) for _ in range(76))
        nonce = rng.getrandbits(32)
        h80 = header76 + struct.pack("<I", nonce)
        mid = midstate_reference(header76)
        h0, h1, h2 = header_tail_words(header76)
        nonce_word = bswap32(nonce)
        ref_words = digest_to_be_words(hashlib.sha256(h80).digest())
        yield f"hashlib_{i}", mid, h0, h1, h2, nonce_word, ref_words


def run_selftests() -> None:
    count = 0
    for label, mid, h0, h1, h2, nonce_word, expected in iter_first_mid_vectors():
        got_sim = simulate_slots(mid, h0, h1, h2, nonce_word)
        if got_sim != expected:
            raise SystemExit(f"mismatch {label}: expected={expected} got={got_sim}")
        count += 1
    print(f"self-test OK ({count} vectors)")


def main() -> int:
    run_selftests()
    for cfg in ALL_WIDTHS:
        content = generate_inc(cfg)
        path = out_path(cfg)
        path.write_text(content, encoding="utf-8", newline="\n")
        print(f"Wrote {path} ({len(content)} bytes)")
    for cfg in LOOP_WIDTHS:
        content = generate_inc(cfg, use_loop=True)
        path = out_path(cfg, loop=True)
        path.write_text(content, encoding="utf-8", newline="\n")
        print(f"Wrote {path} ({len(content)} bytes)")
    cpu_content = generate_cpu_inc()
    cpu_path = cpu_out_path()
    cpu_path.write_text(cpu_content, encoding="utf-8", newline="\n")
    print(f"Wrote {cpu_path} ({len(cpu_content)} bytes)")
    try:
        import test_cpu_compress  # noqa: WPS433

        if test_cpu_compress.main() != 0:
            return 1
    except ImportError:
        pass
    return 0


if __name__ == "__main__":
    sys.exit(main())
