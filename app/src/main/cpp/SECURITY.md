# Native code (libminer) security

This directory contains the native library used for Bitcoin block header hashing (SHA-256, nonce scan) and optional Vulkan compute (GPU) path.

## Bounds and input validation

- **miner.c**: JNI entry points validate Java array lengths before copying (`GetArrayLength` checks for `HEADER_PREFIX_SIZE` 76, `HASH_SIZE` 32, `BLOCK_HEADER_SIZE` 80). No user-controlled variable-length buffers are used in the hot path.
- **sha256.c**: Fixed 64-byte blocks; `sha256()` and `sha256_double()` operate on fixed-size or length-checked inputs. Padding logic uses fixed `block[64]` with indices bounded by `len < 64`.
- **vulkan_miner.c**: Should be reviewed for buffer sizes in Vulkan descriptor sets and any host-side buffers passed to the GPU.

## Recommendation

A dedicated native security review (including Vulkan shader and host/device buffer handling) is recommended to rule out buffer overflows, integer overflows in size calculations, and unsafe parsing. Consider running static analysis (e.g. Clang sanitizers, Coverity) on the native code.
