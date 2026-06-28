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
    fun formatTempRange_singleValueWhenClose() {
        assertEquals("33.0°C", DeviceTelemetryFormat.formatTempRange(listOf(33.0, 33.02, 32.98)))
    }

    @Test
    fun formatTempRange_minMaxWhenSpread() {
        assertEquals("32.3-33.0°C", DeviceTelemetryFormat.formatTempRange(listOf(33.0, 32.3, 32.6)))
    }

    @Test
    fun formatTempRange_empty() {
        assertEquals(DeviceTelemetryFormat.UNAVAILABLE, DeviceTelemetryFormat.formatTempRange(emptyList()))
    }

    @Test
    fun formatGpussRange_delegatesToFormatTempRange() {
        assertEquals("32.3-33.0°C", DeviceTelemetryFormat.formatGpussRange(listOf(33.0, 32.3, 32.6)))
    }

    @Test
    fun formatTempAverage_emptyAndValue() {
        assertEquals(DeviceTelemetryFormat.UNAVAILABLE, DeviceTelemetryFormat.formatTempAverage(emptyList()))
        assertEquals("33.0°C", DeviceTelemetryFormat.formatTempAverage(listOf(32.0, 34.0)))
    }

    @Test
    fun formatClkPair_gpuHzToMhz() {
        assertEquals(
            "650/1100MHz",
            DeviceTelemetryFormat.formatClkPair(
                650_000_000L,
                1_100_000_000L,
                DeviceTelemetryFormat.ClkInputUnit.HZ,
            ),
        )
        assertEquals(
            "${DeviceTelemetryFormat.UNAVAILABLE}/1100MHz",
            DeviceTelemetryFormat.formatClkPair(
                null,
                1_100_000_000L,
                DeviceTelemetryFormat.ClkInputUnit.HZ,
            ),
        )
        assertEquals(
            "650/${DeviceTelemetryFormat.UNAVAILABLE}MHz",
            DeviceTelemetryFormat.formatClkPair(
                650_000_000L,
                null,
                DeviceTelemetryFormat.ClkInputUnit.HZ,
            ),
        )
    }

    @Test
    fun formatClkPair_cpuKhzToMhz() {
        assertEquals("3530/4320MHz", DeviceTelemetryFormat.formatCpuClk(3_530_000L, 4_320_000L))
    }

    @Test
    fun formatGpuClk_hzToMhz() {
        assertEquals("650/1100MHz", DeviceTelemetryFormat.formatGpuClk(650_000_000L, 1_100_000_000L))
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
            cpussTempsC = emptyList(),
            cpuTempsC = emptyList(),
            gpussTempsC = emptyList(),
            skinC = 32.7,
            batterySysfsC = null,
            cpuCurKhz = 3_530_000L,
            cpuMaxKhz = 4_320_000L,
            gpuCurHz = 650_000_000L,
            gpuMaxHz = 1_100_000_000L,
        )
        assertEquals(
            "sysfs=partial Telemetry: cpuss=${DeviceTelemetryFormat.UNAVAILABLE} " +
                "cpu=${DeviceTelemetryFormat.UNAVAILABLE} " +
                "gpuss=${DeviceTelemetryFormat.UNAVAILABLE} " +
                "skin=32.7°C battSysfs=${DeviceTelemetryFormat.UNAVAILABLE} " +
                "cpuClk=3530/4320MHz gpuClk=650/1100MHz",
            line,
        )
    }

    @Test
    fun formatPeriodicLine_noSysfsPrefixWhenOk() {
        val line = buildPeriodicLineForTest(
            DeviceTelemetryReader.SysfsAccess.OK,
            cpussTempsC = listOf(42.1, 43.0),
            cpuTempsC = listOf(41.0, 44.5),
            gpussTempsC = listOf(32.3, 33.0),
            skinC = 32.7,
            batterySysfsC = 32.2,
            cpuCurKhz = 3_530_000L,
            cpuMaxKhz = 4_320_000L,
            gpuCurHz = 525_000_000L,
            gpuMaxHz = 1_100_000_000L,
        )
        assertEquals(
            "Telemetry: cpuss=42.1-43.0°C cpu=41.0-44.5°C gpuss=32.3-33.0°C " +
                "skin=32.7°C battSysfs=32.2°C cpuClk=3530/4320MHz gpuClk=525/1100MHz",
            line,
        )
    }

    @Test
    fun formatPeriodicLine_fieldOrder_cpuBeforeGpu() {
        val line = buildPeriodicLineForTest(
            DeviceTelemetryReader.SysfsAccess.OK,
            cpussTempsC = listOf(40.0),
            cpuTempsC = listOf(39.0),
            gpussTempsC = listOf(50.0),
            skinC = null,
            batterySysfsC = null,
            cpuCurKhz = null,
            cpuMaxKhz = null,
            gpuCurHz = null,
            gpuMaxHz = null,
        )
        val cpussIdx = line.indexOf("cpuss=")
        val cpuIdx = line.indexOf("cpu=")
        val gpussIdx = line.indexOf("gpuss=")
        val cpuClkIdx = line.indexOf("cpuClk=")
        val gpuClkIdx = line.indexOf("gpuClk=")
        assert(cpussIdx >= 0 && cpuIdx > cpussIdx && gpussIdx > cpuIdx && cpuClkIdx > gpussIdx && gpuClkIdx > cpuClkIdx)
    }

    /** Mirrors [DeviceTelemetryReader.formatPeriodicLine] body for snapshot injection in tests. */
    private fun buildPeriodicLineForTest(
        access: DeviceTelemetryReader.SysfsAccess,
        cpussTempsC: List<Double>,
        cpuTempsC: List<Double>,
        gpussTempsC: List<Double>,
        skinC: Double?,
        batterySysfsC: Double?,
        cpuCurKhz: Long?,
        cpuMaxKhz: Long?,
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
            "Telemetry: cpuss=${DeviceTelemetryFormat.formatTempRange(cpussTempsC)} " +
            "cpu=${DeviceTelemetryFormat.formatTempRange(cpuTempsC)} " +
            "gpuss=${DeviceTelemetryFormat.formatTempRange(gpussTempsC)} " +
            "skin=${skinC?.let { DeviceTelemetryFormat.formatTempC(it) } ?: DeviceTelemetryFormat.UNAVAILABLE} " +
            "battSysfs=${batterySysfsC?.let { DeviceTelemetryFormat.formatTempC(it) } ?: DeviceTelemetryFormat.UNAVAILABLE} " +
            "cpuClk=${DeviceTelemetryFormat.formatCpuClk(cpuCurKhz, cpuMaxKhz)} " +
            "gpuClk=${DeviceTelemetryFormat.formatGpuClk(gpuCurHz, gpuMaxHz)}"
    }
}
