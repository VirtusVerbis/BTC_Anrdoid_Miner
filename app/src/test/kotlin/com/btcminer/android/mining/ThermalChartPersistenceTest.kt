package com.btcminer.android.mining

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ThermalChartPersistenceTest {

    @Test
    fun codec_roundTrip_preservesReadings() {
        val meta = ThermalSensorMeta(
            zoneId = 5,
            type = "cpu-0-0-usr",
            group = ThermalSensorGroup.CPU,
            isVirtual = false,
            cluster = 0,
            core = 0,
            shortLabel = "cpu-0-0-usr",
        )
        val original = ThermalUiState(
            access = DeviceTelemetryReader.SysfsAccess.PARTIAL,
            layout = null,
            readings = listOf(ThermalSensorReading(meta, 42.5)),
            discoveredAtMs = 1_000L,
            updatedAtMs = 2_000L,
        )
        val decoded = ThermalChartStateCodec.decode(ThermalChartStateCodec.encode(original))!!
        assertEquals(original.access, decoded.access)
        assertEquals(original.discoveredAtMs, decoded.discoveredAtMs)
        assertEquals(original.updatedAtMs, decoded.updatedAtMs)
        assertEquals(1, decoded.readings.size)
        assertEquals(42.5, decoded.readings.single().tempC, 0.001)
        assertEquals("cpu-0-0-usr", decoded.readings.single().meta.type)
    }

    @Test
    fun codec_decode_invalidVersion_returnsNull() {
        val raw = """{"v":999,"access":"OK","discoveredAtMs":1,"updatedAtMs":2,"readings":[]}"""
        assertNull(ThermalChartStateCodec.decode(raw))
    }

    @Test
    fun restorePersistedState_populatesCachedUiState() {
        DeviceTelemetryReader.resetForSession()
        val meta = ThermalSensorMeta(
            zoneId = 1,
            type = "gpuss-0-usr",
            group = ThermalSensorGroup.GPUSS,
            isVirtual = false,
            cluster = null,
            core = null,
            shortLabel = "gpuss-0-usr",
        )
        val persisted = PersistedThermalChartState(
            access = DeviceTelemetryReader.SysfsAccess.OK,
            readings = listOf(ThermalSensorReading(meta, 55.0)),
            discoveredAtMs = 100L,
            updatedAtMs = 200L,
        )
        DeviceTelemetryReader.restorePersistedState(persisted)
        val state = DeviceTelemetryReader.getCachedUiState()
        assertNotNull(state)
        assertNotNull(state!!.layout)
        assertEquals(1, state.readings.size)
        assertEquals(55.0, state.readings.single().tempC, 0.001)
        DeviceTelemetryReader.resetForSession()
    }

    @Test
    fun restorePersistedState_skipsWhenCacheAlreadyPopulated() {
        DeviceTelemetryReader.resetForSession()
        val first = PersistedThermalChartState(
            access = DeviceTelemetryReader.SysfsAccess.OK,
            readings = listOf(
                ThermalSensorReading(
                    ThermalSensorMeta(
                        zoneId = 1,
                        type = "skin-msm-therm",
                        group = ThermalSensorGroup.SKIN,
                        isVirtual = false,
                        cluster = null,
                        core = null,
                        shortLabel = "skin",
                    ),
                    40.0,
                ),
            ),
            discoveredAtMs = 1L,
            updatedAtMs = 2L,
        )
        DeviceTelemetryReader.restorePersistedState(first)
        val cached = DeviceTelemetryReader.getCachedUiState()!!
        DeviceTelemetryReader.restorePersistedState(
            first.copy(readings = listOf(first.readings.single().copy(tempC = 99.0))),
        )
        assertEquals(cached.updatedAtMs, DeviceTelemetryReader.getCachedUiState()!!.updatedAtMs)
        assertEquals(40.0, DeviceTelemetryReader.getCachedUiState()!!.readings.single().tempC, 0.001)
        DeviceTelemetryReader.resetForSession()
    }
}
