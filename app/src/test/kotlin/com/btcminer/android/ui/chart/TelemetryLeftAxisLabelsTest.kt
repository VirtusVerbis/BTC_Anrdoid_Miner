package com.btcminer.android.ui.chart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryLeftAxisLabelsTest {

    @Test
    fun formatDualScaleLabel_usesNewlineSeparator() {
        val label = formatDualScaleLabel(4000f, 14f)
        assertEquals("4000 Mhz\n14 Ms", label)
    }

    @Test
    fun mapMsAtMhzTick_linearMapping() {
        val ms = mapMsAtMhzTick(
            mhzTick = 3000f,
            mhzMin = 1000f,
            mhzRange = 3000f,
            msMin = 8f,
            msRange = 6f,
        )
        assertEquals(12f, ms, 0.01f)
    }

    @Test
    fun dualScaleMaxLabelWidthDp_scalesWithLabelLength() {
        assertTrue(dualScaleMaxLabelWidthDp(4000f) > dualScaleMaxLabelWidthDp(100f))
    }
}
