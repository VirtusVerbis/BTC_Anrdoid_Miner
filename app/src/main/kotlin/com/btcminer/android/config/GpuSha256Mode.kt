package com.btcminer.android.config

/** GPU compute double-SHA256 path: full header, scalar midstate, uvec2 midstate (2 nonces/thread), or uvec4 midstate (4 nonces/thread). */
enum class GpuSha256Mode {
    GPU_FULL,
    GPU_MIDSTATE,
    GPU_UVEC4_MIDSTATE,
    GPU_UVEC2_MIDSTATE;

    companion object {
        fun fromOrdinal(ord: Int): GpuSha256Mode = values().getOrNull(ord) ?: GPU_FULL
    }
}
