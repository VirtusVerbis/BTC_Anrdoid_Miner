package com.btcminer.android.mining

import org.junit.Assert.assertEquals
import org.junit.Test

class GpuWorkMsStatsTest {

    @Test
    fun formatAvgWorkMsSuffix_emptyCount_returnsEmpty() {
        assertEquals("", formatAvgWorkMsSuffix(0L, 0L))
        assertEquals("", formatAvgWorkMsSuffix(100L, 0L))
    }

    @Test
    fun formatAvgWorkMsSuffix_dividesIntegerMs() {
        assertEquals(" avgWorkMs=92", formatAvgWorkMsSuffix(920L, 10L))
        assertEquals(" avgWorkMs=100", formatAvgWorkMsSuffix(100L, 1L))
    }

    @Test
    fun formatAvgWorkMsSuffix_truncatesTowardZero() {
        assertEquals(" avgWorkMs=92", formatAvgWorkMsSuffix(925L, 10L))
    }
}
