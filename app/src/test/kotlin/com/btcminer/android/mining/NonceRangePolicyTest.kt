package com.btcminer.android.mining

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NonceRangePolicyTest {

    @Test
    fun cpuRange_bothActive_lowerHalf() {
        val range = NonceRangePolicy.cpuRange(threadCount = 4, gpuActive = true)
        assertEquals(NonceRange(0L, 0x7FFFFFFFL), range)
    }

    @Test
    fun gpuRange_bothActive_upperHalfNoOverlap() {
        val cpu = NonceRangePolicy.cpuRange(threadCount = 4, gpuActive = true)!!
        val gpu = NonceRangePolicy.gpuRange(threadCount = 4, gpuActive = true)!!
        assertEquals(NonceRange(0x80000000L, 0xFFFFFFFFL), gpu)
        assertEquals(cpu.endInclusive + 1, gpu.start)
        assertEquals(NonceRangePolicy.MAX_NONCE + 1, gpu.endInclusive - cpu.start + 1)
    }

    @Test
    fun cpuRange_cpuOnly_fullRange() {
        val range = NonceRangePolicy.cpuRange(threadCount = 4, gpuActive = false)
        assertEquals(NonceRange(0L, 0xFFFFFFFFL), range)
    }

    @Test
    fun gpuRange_cpuOnly_null() {
        assertNull(NonceRangePolicy.gpuRange(threadCount = 4, gpuActive = false))
    }

    @Test
    fun gpuRange_gpuOnly_fullRange() {
        val range = NonceRangePolicy.gpuRange(threadCount = 0, gpuActive = true)
        assertEquals(NonceRange(0L, 0xFFFFFFFFL), range)
    }

    @Test
    fun cpuRange_gpuOnly_null() {
        assertNull(NonceRangePolicy.cpuRange(threadCount = 0, gpuActive = true))
    }

    @Test
    fun ranges_neitherActive_bothNull() {
        assertNull(NonceRangePolicy.cpuRange(threadCount = 0, gpuActive = false))
        assertNull(NonceRangePolicy.gpuRange(threadCount = 0, gpuActive = false))
    }

    @Test
    fun formatRangeLog_bothActive() {
        val cpu = NonceRangePolicy.cpuRange(4, gpuActive = true)
        val gpu = NonceRangePolicy.gpuRange(4, gpuActive = true)
        assertEquals(
            "Nonce ranges: CPU=00000000-7fffffff GPU=80000000-ffffffff",
            NonceRangePolicy.formatRangeLog(cpu, gpu),
        )
    }

    @Test
    fun formatRangeLog_cpuOnly() {
        val cpu = NonceRangePolicy.cpuRange(4, gpuActive = false)
        assertEquals(
            "Nonce ranges: CPU=00000000-ffffffff GPU=off",
            NonceRangePolicy.formatRangeLog(cpu, null),
        )
    }

    @Test
    fun formatRangeLog_gpuOnly() {
        val gpu = NonceRangePolicy.gpuRange(0, gpuActive = true)
        assertEquals(
            "Nonce ranges: CPU=off GPU=00000000-ffffffff",
            NonceRangePolicy.formatRangeLog(null, gpu),
        )
    }
}
