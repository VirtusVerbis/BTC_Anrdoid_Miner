package com.btcminer.android.config

/** GPU compute double-SHA256 path: full 80-byte header vs precomputed midstate + last 16 bytes. */
enum class GpuSha256Mode {
    GPU_FULL,
    GPU_MIDSTATE;

    companion object {
        fun fromOrdinal(ord: Int): GpuSha256Mode = values().getOrNull(ord) ?: GPU_FULL
    }
}
