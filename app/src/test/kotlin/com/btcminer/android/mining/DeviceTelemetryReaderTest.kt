package com.btcminer.android.mining

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceTelemetryReaderTest {

    @Test
    fun parseMillidegreeC_validValues() {
        assertEquals(32.2, DeviceTelemetryFormat.parseMillidegreeC("32200")!!, 0.001)
        assertEquals(33.0, DeviceTelemetryFormat.parseMillidegreeC("33000")!!, 0.001)
        assertEquals(32.723, DeviceTelemetryFormat.parseMillidegreeC("32723")!!, 0.001)
    }

    @Test
    fun parseMillidegreeC_rejectsInvalid() {
        assertNull(DeviceTelemetryFormat.parseMillidegreeC("0"))
        assertNull(DeviceTelemetryFormat.parseMillidegreeC("-40960"))
        assertNull(DeviceTelemetryFormat.parseMillidegreeC("-273000"))
        assertNull(DeviceTelemetryFormat.parseMillidegreeC("150000"))
        assertNull(DeviceTelemetryFormat.parseMillidegreeC("not-a-number"))
        assertNull(DeviceTelemetryFormat.parseMillidegreeC(""))
    }

    @Test
    fun formatGpussRange_singleValueWhenClose() {
        assertEquals("33.0°C", DeviceTelemetryFormat.formatGpussRange(listOf(33.0, 33.02, 32.98)))
    }

    @Test
    fun formatGpussRange_minMaxWhenSpread() {
        assertEquals("32.3-33.0°C", DeviceTelemetryFormat.formatGpussRange(listOf(33.0, 32.3, 32.6)))
    }

    @Test
    fun formatGpussRange_empty() {
        assertEquals(DeviceTelemetryFormat.UNAVAILABLE, DeviceTelemetryFormat.formatGpussRange(emptyList()))
    }

    @Test
    fun formatGpuClk_hzToMhz() {
        assertEquals("650/1100MHz", DeviceTelemetryFormat.formatGpuClk(650_000_000L, 1_100_000_000L))
        assertEquals("${DeviceTelemetryFormat.UNAVAILABLE}/1100MHz", DeviceTelemetryFormat.formatGpuClk(null, 1_100_000_000L))
        assertEquals("650/${DeviceTelemetryFormat.UNAVAILABLE}MHz", DeviceTelemetryFormat.formatGpuClk(650_000_000L, null))
    }

    @Test
    fun formatZoneIdRange_contiguousAndSparse() {
        assertEquals("32-39", DeviceTelemetryFormat.formatZoneIdRange(listOf(32, 33, 34, 35, 36, 37, 38, 39)))
        assertEquals("5", DeviceTelemetryFormat.formatZoneIdRange(listOf(5)))
        assertEquals("1,3,5", DeviceTelemetryFormat.formatZoneIdRange(listOf(5, 1, 3)))
        assertEquals(DeviceTelemetryFormat.UNAVAILABLE, DeviceTelemetryFormat.formatZoneIdRange(emptyList()))
    }

    @Test
    fun formatPeriodicLine_includesSysfsPrefixWhenNotOk() {
        val line = buildPeriodicLineForTest(
            DeviceTelemetryReader.SysfsAccess.PARTIAL,
            gpussTempsC = emptyList(),
            skinC = 32.7,
            batterySysfsC = null,
            gpuCurHz = 650_000_000L,
            gpuMaxHz = 1_100_000_000L,
        )
        assertEquals(
            "sysfs=partial Telemetry: gpuss=${DeviceTelemetryFormat.UNAVAILABLE} skin=32.7°C battSysfs=${DeviceTelemetryFormat.UNAVAILABLE} gpuClk=650/1100MHz",
            line,
        )
    }

    @Test
    fun formatPeriodicLine_noSysfsPrefixWhenOk() {
        val line = buildPeriodicLineForTest(
            DeviceTelemetryReader.SysfsAccess.OK,
            gpussTempsC = listOf(32.3, 33.0),
            skinC = 32.7,
            batterySysfsC = 32.2,
            gpuCurHz = 650_000_000L,
            gpuMaxHz = 1_100_000_000L,
        )
        assertEquals(
            "Telemetry: gpuss=32.3-33.0°C skin=32.7°C battSysfs=32.2°C gpuClk=650/1100MHz",
            line,
        )
    }

    /** Mirrors [DeviceTelemetryReader.formatPeriodicLine] body for snapshot injection in tests. */
    private fun buildPeriodicLineForTest(
        access: DeviceTelemetryReader.SysfsAccess,
        gpussTempsC: List<Double>,
        skinC: Double?,
        batterySysfsC: Double?,
        gpuCurHz: Long?,
        gpuMaxHz: Long?,
    ): String {
        val prefix = if (access == DeviceTelemetryReader.SysfsAccess.OK) {
            ""
        } else {
            "sysfs=${when (access) {
                DeviceTelemetryReader.SysfsAccess.OK -> "ok"
                DeviceTelemetryReader.SysfsAccess.PARTIAL -> "partial"
                DeviceTelemetryReader.SysfsAccess.DENIED -> "denied"
            }} "
        }
        return prefix +
            "Telemetry: gpuss=${DeviceTelemetryFormat.formatGpussRange(gpussTempsC)} " +
            "skin=${skinC?.let { DeviceTelemetryFormat.formatTempC(it) } ?: DeviceTelemetryFormat.UNAVAILABLE} " +
            "battSysfs=${batterySysfsC?.let { DeviceTelemetryFormat.formatTempC(it) } ?: DeviceTelemetryFormat.UNAVAILABLE} " +
            "gpuClk=${DeviceTelemetryFormat.formatGpuClk(gpuCurHz, gpuMaxHz)}"
    }
}
