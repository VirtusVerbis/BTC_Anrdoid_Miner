# GPU Occupancy A/B Benchmark Protocol

Manual benchmark guide for comparing **Full unroll** vs **Compact loop + spirv-opt** on uvec2/uvec4 midstate GPU modes.

## Prerequisites

- Device with Vulkan compute (Adreno or Mali recommended).
- Build with Vulkan SDK (`glslangValidator` + `spirv-opt` on PATH).
- Logcat access filtered by tag `Mining`.

## Config matrix

| Dimension | Values to sweep |
|-----------|-----------------|
| **GPU SHA mode** | `GPU_UVEC2_MIDSTATE`, `GPU_UVEC4_MIDSTATE` |
| **GPU compress style** | Full unroll (default), Compact loop |
| **GPU hash per thread** | 1 (baseline), **2**, **4** |
| **GPU local_size_x** | 32, 64, 128, 256 (clamp to device max) |

Other settings for a fair run:

- **CPU cores:** 0 (GPU-only)
- **GPU utilization:** 100%
- **Hashrate target:** empty (no throttle)
- **Battery:** unplugged OK if thermals stable; prefer cool ambient for repeatability

## Procedure

1. Open **Config** and set the matrix row (mode, compress style, hpt, local_size_x). Save.
2. Start mining against a pool (or fake stratum). Let run **≥5 minutes** after thermals stabilize.
3. Record from logcat (`adb logcat -s Mining`):
   - **GPU H/s** from periodic `Stats:` lines
   - **`avgWorkMs`** suffix on the same line (e.g. `avgWorkMs=42.3`)
4. Repeat for each combination.
5. Winner = highest sustained **GPU H/s**; confirm **`avgWorkMs` drops** proportionally.

## Example Stats line

```
Stats: CPU 0.00 GPU 1,234,567.89 H/s avgWorkMs=38.2, noncesCpu=0, noncesGpu=...
```

GPU scan debug lines also include `compress=FULL_UNROLL` or `compress=COMPACT_LOOP` when a chunk is slow or hits.

## Interpreting results

| Outcome | Typical meaning |
|---------|-----------------|
| Compact loop **+10–30%** GPU H/s on uvec4, lower avgWorkMs | Register/occupancy bound; loop variant helps |
| Flat or **<5%** difference | Already not spill-bound, or wrong local_size/hpt |
| Compact loop **slower** | Device prefers full unroll at that hpt/local_size; keep Full unroll |

Expected bands (not acceptance criteria):

- **Mali / Adreno + uvec4:** often +5–30% when loop wins
- **uvec2 or near-optimal config:** 0–15% or flat

## Notes

- Compress style applies only to **uvec2/uvec4** modes; scalar GPU modes always use full unroll.
- Changing compress style creates a separate Vulkan pipeline; first chunk after config change may be slightly slower (pipeline compile).
- Compare like with like: same block template era, same screen-on/off policy, same device temperature band.
