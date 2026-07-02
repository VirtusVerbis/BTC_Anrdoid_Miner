package com.btcminer.android.config

import org.junit.Assert.assertEquals
import org.junit.Test

class GpuCompressStyleTest {

    @Test
    fun fromOrdinal_mapsKnownValues() {
        assertEquals(GpuCompressStyle.FULL_UNROLL, GpuCompressStyle.fromOrdinal(0))
        assertEquals(GpuCompressStyle.COMPACT_LOOP, GpuCompressStyle.fromOrdinal(1))
    }

    @Test
    fun fromOrdinal_unknownDefaultsToFullUnroll() {
        assertEquals(GpuCompressStyle.FULL_UNROLL, GpuCompressStyle.fromOrdinal(99))
        assertEquals(GpuCompressStyle.FULL_UNROLL, GpuCompressStyle.fromOrdinal(-1))
    }

    @Test
    fun defaultMiningConfig_usesFullUnroll() {
        assertEquals(GpuCompressStyle.FULL_UNROLL, MiningConfig().gpuCompressStyle)
    }
}
