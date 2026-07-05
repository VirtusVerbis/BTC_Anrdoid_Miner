package com.btcminer.android.mining

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryChartSampleTest {

    @Test
    fun aggregateForChart_averagesAndClocks() {
        val snap = DeviceTelemetryReader.Snapshot(
            access = DeviceTelemetryReader.SysfsAccess.OK,
            cpussTempsC = listOf(40.0, 42.0),
            cpuTempsC = listOf(50.0),
            gpussTempsC = listOf(30.0, 34.0),
            gpuTempsC = listOf(55.0, 57.0),
            skinC = 32.5,
            batterySysfsC = 30.0,
            cpuCurKhz = 3_530_000L,
            cpuMaxKhz = 4_320_000L,
            gpuCurHz = 650_000_000L,
            gpuMaxHz = 1_100_000_000L,
        )
        val sample = TelemetryChartSample.aggregateForChart(snap, batteryApiTempC = 34.0)
        assertEquals(41f, sample.cpussAvgC, 0.01f)
        assertEquals(50f, sample.cpuAvgC, 0.01f)
        assertEquals(32f, sample.gpussAvgC, 0.01f)
        assertEquals(56f, sample.gpuAvgC, 0.01f)
        assertEquals(32.5f, sample.skinC, 0.01f)
        assertEquals(32f, sample.batteryAvgC, 0.01f)
        assertEquals(3530f, sample.cpuClkMhz, 0.01f)
        assertEquals(650f, sample.gpuClkMhz, 0.01f)
    }

    @Test
    fun aggregateForChart_missingValuesAreNaN() {
        val snap = DeviceTelemetryReader.Snapshot(
            access = DeviceTelemetryReader.SysfsAccess.DENIED,
            cpussTempsC = emptyList(),
            cpuTempsC = emptyList(),
            gpussTempsC = emptyList(),
            gpuTempsC = emptyList(),
            skinC = null,
            batterySysfsC = null,
            cpuCurKhz = null,
            cpuMaxKhz = null,
            gpuCurHz = null,
            gpuMaxHz = null,
        )
        val sample = TelemetryChartSample.aggregateForChart(snap, batteryApiTempC = null)
        assertFalse(sample.cpussAvgC.isFinite())
        assertFalse(sample.gpuAvgC.isFinite())
        assertFalse(sample.batteryAvgC.isFinite())
        assertFalse(sample.cpuClkMhz.isFinite())
    }

    @Test
    fun averageBatteryC_sysfsOnly_apiOnly_both() {
        assertEquals(30f, TelemetryChartSample.averageBatteryC(30.0, null), 0.01f)
        assertEquals(34f, TelemetryChartSample.averageBatteryC(null, 34.0), 0.01f)
        assertEquals(32f, TelemetryChartSample.averageBatteryC(30.0, 34.0), 0.01f)
        assertFalse(TelemetryChartSample.averageBatteryC(null, null).isFinite())
    }

    @Test
    fun hasFiniteTelemetryValues() {
        assertTrue(hasFiniteTelemetryValues(listOf(Float.NaN, 1f)))
        assertFalse(hasFiniteTelemetryValues(listOf(Float.NaN, Float.NaN)))
        assertFalse(hasFiniteTelemetryValues(emptyList()))
    }

    @Test
    fun gpuZoneClassification_distinctFromGpuss() {
        val gpuss = ThermalSensorClassification.classifyType("gpuss-0", 1)
        val gpu = ThermalSensorClassification.classifyType("gpu-0-usr", 2)
        assertEquals(ThermalSensorGroup.GPUSS, gpuss?.group)
        assertEquals(ThermalSensorGroup.GPU, gpu?.group)
    }
}
