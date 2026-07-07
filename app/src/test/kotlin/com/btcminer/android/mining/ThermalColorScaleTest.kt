package com.btcminer.android.mining

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThermalColorScaleTest {

    @Test
    fun colorForBand_clampsAtEnds() {
        val cold = ThermalColorScale.colorForBand(ThermalColorScale.UNIFIED_BAND, 20.0)
        val hot = ThermalColorScale.colorForBand(ThermalColorScale.UNIFIED_BAND, 120.0)
        assertEquals(ThermalColorScale.colorForBand(ThermalColorScale.UNIFIED_BAND, 30.0), cold)
        assertEquals(ThermalColorScale.colorForBand(ThermalColorScale.UNIFIED_BAND, 110.0), hot)
        assertNotEquals(cold, hot)
    }

    @Test
    fun colorForBand_midRangeInterpolates() {
        val at70 = ThermalColorScale.colorForBand(ThermalColorScale.UNIFIED_BAND, 70.0)
        val at90 = ThermalColorScale.colorForBand(ThermalColorScale.UNIFIED_BAND, 90.0)
        val at50 = ThermalColorScale.colorForBand(ThermalColorScale.UNIFIED_BAND, 50.0)
        assertNotEquals(at70, at50)
        assertNotEquals(at90, at70)
    }

    @Test
    fun bandForGroup_batteryVsCompute() {
        assertEquals(ThermalColorScale.BATTERY_BAND, ThermalColorScale.bandForGroup(ThermalSensorGroup.BATTERY_API))
        assertEquals(ThermalColorScale.BATTERY_BAND, ThermalColorScale.bandForGroup(ThermalSensorGroup.BATTERY_SYSFS))
        assertEquals(ThermalColorScale.BATTERY_BAND, ThermalColorScale.bandForGroup(ThermalSensorGroup.SKIN))
        assertEquals(ThermalColorScale.UNIFIED_BAND, ThermalColorScale.bandForGroup(ThermalSensorGroup.CPU))
        assertEquals(ThermalColorScale.UNIFIED_BAND, ThermalColorScale.bandForGroup(ThermalSensorGroup.GPUSS))
    }

    @Test
    fun colorForGroup_batteryUsesBatteryBand() {
        val c = ThermalColorScale.colorForGroup(ThermalSensorGroup.BATTERY_API, 43.0)
        assertEquals(ThermalColorScale.colorForBand(ThermalColorScale.BATTERY_BAND, 43.0), c)
        assertEquals(
            ThermalColorScale.colorForBand(ThermalColorScale.UNIFIED_BAND, 43.0),
            ThermalColorScale.colorForGroup(ThermalSensorGroup.CPU, 43.0),
        )
        assertNotEquals(
            ThermalColorScale.colorForBand(ThermalColorScale.UNIFIED_BAND, 43.0),
            c,
        )
    }

    @Test
    fun colorForGroup_skinUsesBatteryBand() {
        val c = ThermalColorScale.colorForGroup(ThermalSensorGroup.SKIN, 40.0)
        assertEquals(ThermalColorScale.colorForBand(ThermalColorScale.BATTERY_BAND, 40.0), c)
        assertNotEquals(
            ThermalColorScale.colorForBand(ThermalColorScale.UNIFIED_BAND, 40.0),
            c,
        )
    }

    @Test
    fun colorForGroup_computeUsesUnifiedBand() {
        val c = ThermalColorScale.colorForGroup(ThermalSensorGroup.CPUSS, 55.0)
        assertEquals(ThermalColorScale.colorForBand(ThermalColorScale.UNIFIED_BAND, 55.0), c)
    }

    @Test
    fun legendGradientColors_hasExpectedLength() {
        assertEquals(32, ThermalColorScale.legendGradientColors(ThermalColorScale.UNIFIED_BAND).size)
    }

    @Test
    fun isInDangerZone_cpuThreshold() {
        assertFalse(ThermalColorScale.isInDangerZone(ThermalSensorGroup.CPU, 79.9))
        assertTrue(ThermalColorScale.isInDangerZone(ThermalSensorGroup.CPU, 80.0))
        assertTrue(ThermalColorScale.isInDangerZone(ThermalSensorGroup.CPUSS, 80.0))
    }

    @Test
    fun isInDangerZone_gpuThreshold() {
        assertFalse(ThermalColorScale.isInDangerZone(ThermalSensorGroup.GPUSS, 79.9))
        assertTrue(ThermalColorScale.isInDangerZone(ThermalSensorGroup.GPUSS, 80.0))
    }

    @Test
    fun isInDangerZone_batteryThreshold() {
        assertFalse(ThermalColorScale.isInDangerZone(ThermalSensorGroup.BATTERY_API, 42.9))
        assertTrue(ThermalColorScale.isInDangerZone(ThermalSensorGroup.BATTERY_API, 43.0))
        assertTrue(ThermalColorScale.isInDangerZone(ThermalSensorGroup.BATTERY_SYSFS, 43.0))
    }

    @Test
    fun isInDangerZone_skinThreshold() {
        assertFalse(ThermalColorScale.isInDangerZone(ThermalSensorGroup.SKIN, 44.9))
        assertTrue(ThermalColorScale.isInDangerZone(ThermalSensorGroup.SKIN, 45.0))
    }

    @Test
    fun dangerThresholdC_matchesBandTops() {
        assertEquals(80.0, ThermalColorScale.dangerThresholdC(ThermalSensorGroup.CPU)!!, 0.001)
        assertEquals(80.0, ThermalColorScale.dangerThresholdC(ThermalSensorGroup.GPUSS)!!, 0.001)
        assertEquals(43.0, ThermalColorScale.dangerThresholdC(ThermalSensorGroup.BATTERY_API)!!, 0.001)
        assertEquals(45.0, ThermalColorScale.dangerThresholdC(ThermalSensorGroup.SKIN)!!, 0.001)
    }
}
