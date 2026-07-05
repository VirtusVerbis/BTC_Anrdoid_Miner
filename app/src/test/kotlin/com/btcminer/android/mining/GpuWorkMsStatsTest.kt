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

    @Test
    fun avgWorkMsOrNaN_emptyCount_returnsNaN() {
        assertEquals(Float.NaN, avgWorkMsOrNaN(0L, 0L), 0f)
        assertEquals(Float.NaN, avgWorkMsOrNaN(100L, 0L), 0f)
    }

    @Test
    fun avgWorkMsOrNaN_dividesIntegerMs() {
        assertEquals(92f, avgWorkMsOrNaN(920L, 10L), 0.01f)
        assertEquals(100f, avgWorkMsOrNaN(100L, 1L), 0.01f)
        assertEquals(92f, avgWorkMsOrNaN(925L, 10L), 0.01f)
    }
}
