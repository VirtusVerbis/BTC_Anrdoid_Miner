package com.btcminer.android.config

/** GPU compute double-SHA256 path: full header, scalar midstate, or uvec4 midstate (4 nonces per thread). */
enum class GpuSha256Mode {
    GPU_FULL,
    GPU_MIDSTATE,
    GPU_UVEC4_MIDSTATE;

    companion object {
        fun fromOrdinal(ord: Int): GpuSha256Mode = values().getOrNull(ord) ?: GPU_FULL
    }
}
