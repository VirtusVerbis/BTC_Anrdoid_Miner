package com.btcminer.android.config

import org.junit.Assert.assertEquals
import org.junit.Test

class GpuSha256ModeTest {

    @Test
    fun fromOrdinal_mapsKnownValues() {
        assertEquals(GpuSha256Mode.GPU_FULL, GpuSha256Mode.fromOrdinal(0))
        assertEquals(GpuSha256Mode.GPU_MIDSTATE, GpuSha256Mode.fromOrdinal(1))
        assertEquals(GpuSha256Mode.GPU_UVEC4_MIDSTATE, GpuSha256Mode.fromOrdinal(2))
    }

    @Test
    fun fromOrdinal_fallsBackToFullForUnknown() {
        assertEquals(GpuSha256Mode.GPU_FULL, GpuSha256Mode.fromOrdinal(99))
        assertEquals(GpuSha256Mode.GPU_FULL, GpuSha256Mode.fromOrdinal(-1))
    }
}
