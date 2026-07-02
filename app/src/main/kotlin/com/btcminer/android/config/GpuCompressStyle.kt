package com.btcminer.android.config

/** GPU SHA-256 compressor codegen style for uvec2/uvec4 midstate shaders. Scalar modes ignore this. */
enum class GpuCompressStyle {
    FULL_UNROLL,
    COMPACT_LOOP;

    companion object {
        fun fromOrdinal(ord: Int): GpuCompressStyle = values().getOrNull(ord) ?: FULL_UNROLL
    }
}
