package com.btcminer.android.mining

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThermalColorScaleTest {

    @Test
    fun colorForBand_clampsAtEnds() {
        val cold = ThermalColorScale.colorForBand(ThermalColorScale.UNIFIED_BAND, 20.0)
        val hot = ThermalColorScale.colorForBand(ThermalColorScale.UNIFIED_BAND, 90.0)
        assertEquals(ThermalColorScale.colorForBand(ThermalColorScale.UNIFIED_BAND, 35.0), cold)
        assertEquals(ThermalColorScale.colorForBand(ThermalColorScale.UNIFIED_BAND, 65.0), hot)
        assertNotEquals(cold, hot)
    }

    @Test
    fun colorForGroup_usesUnifiedBand() {
        val c = ThermalColorScale.colorForGroup(ThermalSensorGroup.BATTERY_API, 43.0)
        assertEquals(ThermalColorScale.colorForBand(ThermalColorScale.UNIFIED_BAND, 43.0), c)
    }

    @Test
    fun legendGradientColors_hasExpectedLength() {
        assertEquals(32, ThermalColorScale.legendGradientColors(ThermalColorScale.UNIFIED_BAND).size)
    }
}
