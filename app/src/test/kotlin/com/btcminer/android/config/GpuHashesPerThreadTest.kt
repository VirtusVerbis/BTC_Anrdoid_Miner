package com.btcminer.android.config

import org.junit.Assert.assertEquals
import org.junit.Test

class GpuHashesPerThreadTest {

    @Test
    fun clampGpuHashesPerThread_acceptsAllowedValues() {
        for (v in MiningConfig.GPU_HASHES_PER_THREAD_OPTIONS) {
            assertEquals(v, MiningConfig.clampGpuHashesPerThread(v))
        }
    }

    @Test
    fun clampGpuHashesPerThread_snapsInvalidValues() {
        assertEquals(1, MiningConfig.clampGpuHashesPerThread(0))
        assertEquals(2, MiningConfig.clampGpuHashesPerThread(3))
        assertEquals(4, MiningConfig.clampGpuHashesPerThread(5))
        assertEquals(8, MiningConfig.clampGpuHashesPerThread(9))
    }

    @Test
    fun sliderIndex_roundTrip() {
        MiningConfig.GPU_HASHES_PER_THREAD_OPTIONS.forEachIndexed { index, value ->
            assertEquals(value, MiningConfig.gpuHashesPerThreadFromSliderIndex(index))
            assertEquals(index, MiningConfig.gpuHashesPerThreadSliderIndex(value))
        }
    }

    @Test
    fun defaultIsOne() {
        assertEquals(1, MiningConfig.GPU_HASHES_PER_THREAD_DEFAULT)
        assertEquals(0, MiningConfig.gpuHashesPerThreadSliderIndex(MiningConfig.GPU_HASHES_PER_THREAD_DEFAULT))
    }
}
