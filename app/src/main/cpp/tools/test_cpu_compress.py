#!/usr/bin/env python3
"""Host golden-vector runner for CPU constant-folded SHA-256 compressors (phase 4 + 5)."""

from __future__ import annotations

import platform
import shutil
import struct
import subprocess
import sys
import tempfile
from dataclasses import dataclass
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
CPP_DIR = SCRIPT_DIR.parent
sys.path.insert(0, str(SCRIPT_DIR))

import gen_sha256_first_mid_compress as first_mid  # noqa: E402
import gen_sha256_second_compress as second  # noqa: E402

SELFTEST_HEADER76 = bytes(range(1, 77))

# Mirror com.btcminer.android.config.CpuSha256Flavor ordinals 0–3.
CPU_FLAVORS: tuple[tuple[str, int, tuple[str, ...]], ...] = (
    ("HW_SHA2_MIDSTATE", 0, ("aarch64", "hwcap_sha2")),
    ("HW_SHA2", 1, ("aarch64", "hwcap_sha2")),
    ("NEON4_MIDSTATE", 2, ("aarch64",)),
    ("NEON4", 3, ("aarch64",)),
)


@dataclass(frozen=True)
class FlavorSpec:
    name: str
    ordinal: int


def find_cc() -> str | None:
    for name in ("cc", "gcc", "clang", "cl"):
        path = shutil.which(name)
        if path:
            return path
    return None


def host_is_aarch64() -> bool:
    return platform.machine().lower() in ("aarch64", "arm64")


def _linux_hwcap() -> int | None:
    auxv_path = Path("/proc/self/auxv")
    if not auxv_path.is_file():
        return None
    data = auxv_path.read_bytes()
    hwcap = None
    for i in range(0, len(data) - 16, 16):
        tag, val = struct.unpack_from("QQ", data, i)
        if tag == 0:
            break
        if tag == 16:  # AT_HWCAP
            hwcap = val
    return hwcap


def host_has_hwcap_sha2() -> bool:
    if not host_is_aarch64():
        return False
    if sys.platform == "darwin":
        return True
    if sys.platform == "linux":
        hwcap = _linux_hwcap()
        if hwcap is None:
            return False
        return (hwcap & (1 << 17)) != 0
    return False


def active_cpu_flavors() -> list[FlavorSpec]:
    if not host_is_aarch64():
        return []
    out: list[FlavorSpec] = []
    for name, ordinal, requires in CPU_FLAVORS:
        if "aarch64" in requires and not host_is_aarch64():
            continue
        if "hwcap_sha2" in requires and not host_has_hwcap_sha2():
            continue
        out.append(FlavorSpec(name=name, ordinal=ordinal))
    return out


def emit_compress_test_main(path: Path) -> None:
    lines = [
        '#include "sha256_btc_fast.h"',
        "#include <stdio.h>",
        "#include <string.h>",
        "",
        "static int eq8(const uint32_t *a, const uint32_t *b) {",
        "    return memcmp(a, b, 32) == 0;",
        "}",
        "",
        "int main(void) {",
        "    uint32_t out[8];",
    ]

    for label, h_words, expected in second.iter_second_vectors():
        h_str = ", ".join(f"0x{w:08x}u" for w in h_words)
        e_str = ", ".join(f"0x{w:08x}u" for w in expected)
        lines.extend([
            f"    {{ const uint32_t h[8] = {{{h_str}}};",
            f"      const uint32_t exp[8] = {{{e_str}}};",
            "      sha256_btc_test_second(h, out);",
            f'      if (!eq8(out, exp)) {{ fprintf(stderr, "second mismatch: {label}\\n"); return 1; }} }}',
        ])

    for label, mid, h0, h1, h2, nonce_word, expected in first_mid.iter_first_mid_vectors():
        m_str = ", ".join(f"0x{w:08x}u" for w in mid)
        e_str = ", ".join(f"0x{w:08x}u" for w in expected)
        lines.extend([
            f"    {{ const uint32_t mid[8] = {{{m_str}}};",
            f"      const uint32_t exp[8] = {{{e_str}}};",
            f"      sha256_btc_test_first_mid(mid, 0x{h0:08x}u, 0x{h1:08x}u, 0x{h2:08x}u, 0x{nonce_word:08x}u, out);",
            f'      if (!eq8(out, exp)) {{ fprintf(stderr, "first_mid mismatch: {label}\\n"); return 1; }} }}',
        ])

    lines.extend([
        "    return 0;",
        "}",
        "",
    ])
    path.write_text("\n".join(lines), encoding="utf-8", newline="\n")


def emit_flavor_test_main(path: Path, flavors: list[FlavorSpec]) -> None:
    header_bytes = ", ".join(str(b) for b in SELFTEST_HEADER76)
    lines = [
        '#include "sha256.h"',
        '#include "sha256_scan.h"',
        "#include <stdatomic.h>",
        "#include <stdio.h>",
        "#include <string.h>",
        "",
        "atomic_int g_cpu_interrupt_requested = 0;",
        "",
        f"static const uint8_t kSelftestHeader76[76] = {{{header_bytes}}};",
        "",
        "static void header80_from_76_nonce(const uint8_t *header76, uint32_t nonce, uint8_t *header80) {",
        "    memcpy(header80, header76, 76);",
        "    header80[76] = (uint8_t)nonce;",
        "    header80[77] = (uint8_t)(nonce >> 8);",
        "    header80[78] = (uint8_t)(nonce >> 16);",
        "    header80[79] = (uint8_t)(nonce >> 24);",
        "}",
        "",
        "int main(void) {",
        "    uint8_t ref[32];",
        "    uint8_t got[32];",
        "    uint8_t h80[80];",
    ]

    for flavor in flavors:
        for nonce in range(1, 5):
            lines.extend([
                f"    header80_from_76_nonce(kSelftestHeader76, {nonce}u, h80);",
                f"    sha256_double(h80, 80, ref);",
                f"    cpu_sha256_double_flavor({flavor.ordinal}, kSelftestHeader76, {nonce}u, got);",
                f'    if (memcmp(ref, got, 32) != 0) {{',
                f'        fprintf(stderr, "flavor mismatch: {flavor.name} nonce={nonce}\\n");',
                "        return 1;",
                "    }",
            ])

    lines.extend([
        "    return 0;",
        "}",
        "",
    ])
    path.write_text("\n".join(lines), encoding="utf-8", newline="\n")


def run_host_compress_test() -> bool:
    cc = find_cc()
    if not cc:
        print("warning: no C compiler found; skipping CPU golden-vector harness", file=sys.stderr)
        return True

    with tempfile.TemporaryDirectory() as tmp:
        tmp_path = Path(tmp)
        main_c = tmp_path / "test_main.c"
        emit_compress_test_main(main_c)
        exe = tmp_path / ("test_cpu_compress.exe" if sys.platform == "win32" else "test_cpu_compress")
        cmd = [
            cc,
            "-std=c11",
            "-Wall",
            "-Wextra",
            "-DSHA256_BTC_FAST_TEST",
            "-I",
            str(CPP_DIR),
            str(CPP_DIR / "sha256_btc_fast.c"),
            str(CPP_DIR / "sha256.c"),
            str(main_c),
            "-o",
            str(exe),
        ]
        try:
            subprocess.run(cmd, check=True, capture_output=True, text=True)
        except subprocess.CalledProcessError as exc:
            print(f"CPU harness compile failed:\n{exc.stderr}", file=sys.stderr)
            return False

        try:
            result = subprocess.run([str(exe)], check=True, capture_output=True, text=True)
        except subprocess.CalledProcessError as exc:
            print(f"CPU harness run failed:\n{exc.stderr}", file=sys.stderr)
            return False

        if result.stdout:
            print(result.stdout.strip())

    second_count = sum(1 for _ in second.iter_second_vectors())
    first_mid_count = sum(1 for _ in first_mid.iter_first_mid_vectors())
    total = second_count + first_mid_count
    print(f"CPU golden-vector harness OK ({total} vectors)")
    return True


def run_arm_neon_flavor_test() -> bool:
    flavors = active_cpu_flavors()
    if not flavors:
        print(
            "warning: skipping ARM/NEON flavor harness (host is not aarch64 or no active matrix flavors)",
            file=sys.stderr,
        )
        return True

    cc = find_cc()
    if not cc:
        print("warning: no C compiler found; skipping ARM/NEON flavor harness", file=sys.stderr)
        return True

    with tempfile.TemporaryDirectory() as tmp:
        tmp_path = Path(tmp)
        main_c = tmp_path / "test_flavor_main.c"
        emit_flavor_test_main(main_c, flavors)
        exe = tmp_path / ("test_arm_neon_flavors.exe" if sys.platform == "win32" else "test_arm_neon_flavors")
        cmd = [
            cc,
            "-std=c11",
            "-Wall",
            "-Wextra",
            "-I",
            str(CPP_DIR),
            "-march=armv8-a+crypto",
            str(CPP_DIR / "sha256.c"),
            str(CPP_DIR / "sha256_btc_fast.c"),
            str(CPP_DIR / "sha256_scan.c"),
            str(CPP_DIR / "sha256_arm_sha2.c"),
            str(CPP_DIR / "sha256_neon_4way.c"),
            str(main_c),
            "-o",
            str(exe),
        ]
        try:
            subprocess.run(cmd, check=True, capture_output=True, text=True)
        except subprocess.CalledProcessError as exc:
            print(f"ARM/NEON flavor harness compile failed:\n{exc.stderr}", file=sys.stderr)
            return False

        try:
            subprocess.run([str(exe)], check=True, capture_output=True, text=True)
        except subprocess.CalledProcessError as exc:
            print(f"ARM/NEON flavor harness run failed:\n{exc.stderr}", file=sys.stderr)
            return False

    names = ", ".join(f.name for f in flavors)
    checks = len(flavors) * 4
    print(f"ARM/NEON flavor harness OK ({checks} checks: {names})")
    return True


def main() -> int:
    if not run_host_compress_test():
        return 1
    if not run_arm_neon_flavor_test():
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
