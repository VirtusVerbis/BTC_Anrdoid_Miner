#!/usr/bin/env python3
"""Host golden-vector runner for CPU constant-folded SHA-256 compressors (phase 4)."""

from __future__ import annotations

import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
CPP_DIR = SCRIPT_DIR.parent
sys.path.insert(0, str(SCRIPT_DIR))

import gen_sha256_first_mid_compress as first_mid  # noqa: E402
import gen_sha256_second_compress as second  # noqa: E402


def find_cc() -> str | None:
    for name in ("cc", "gcc", "clang", "cl"):
        path = shutil.which(name)
        if path:
            return path
    return None


def emit_test_main(path: Path) -> None:
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


def run_host_test() -> bool:
    cc = find_cc()
    if not cc:
        print("warning: no C compiler found; skipping CPU golden-vector harness", file=sys.stderr)
        return True

    with tempfile.TemporaryDirectory() as tmp:
        tmp_path = Path(tmp)
        main_c = tmp_path / "test_main.c"
        emit_test_main(main_c)
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


def main() -> int:
    if not run_host_test():
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
