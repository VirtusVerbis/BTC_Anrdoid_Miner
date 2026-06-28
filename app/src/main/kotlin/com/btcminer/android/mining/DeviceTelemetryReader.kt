package com.btcminer.android.mining

import java.io.File
import java.util.Locale

/**
 * Reads Qualcomm thermal/GPU sysfs for debug logcat (gpuss-*, skin, battery, kgsl clocks).
 * Best-effort: many devices deny app access; probe line records sysfs=ok|partial|denied.
 */
object DeviceTelemetryReader {

    private const val THERMAL_BASE = "/sys/class/thermal"
    private const val KGSL_CUR_FREQ = "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq"
    private const val KGSL_MAX_FREQ = "/sys/class/kgsl/kgsl-3d0/devfreq/max_freq"

    private var gpussZoneIds: List<Int> = emptyList()
    private var skinZoneId: Int? = null
    private var batteryZoneId: Int? = null
    private var firstError: String? = null

    data class Snapshot(
        val access: SysfsAccess,
        val gpussTempsC: List<Double>,
        val skinC: Double?,
        val batterySysfsC: Double?,
        val gpuCurHz: Long?,
        val gpuMaxHz: Long?,
    )

    enum class SysfsAccess {
        OK,
        PARTIAL,
        DENIED,
    }

    fun resetForSession() {
        gpussZoneIds = emptyList()
        skinZoneId = null
        batteryZoneId = null
        firstError = null
    }

    fun discoverZones() {
        gpussZoneIds = emptyList()
        skinZoneId = null
        batteryZoneId = null
        firstError = null

        val thermalDir = File(THERMAL_BASE)
        if (!thermalDir.isDirectory) {
            recordError("$THERMAL_BASE not accessible")
            return
        }

        val gpuss = mutableListOf<Int>()
        val zoneDirs = thermalDir.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("thermal_zone") }
            ?.sortedBy { zoneDir ->
                zoneDir.name.removePrefix("thermal_zone").toIntOrNull() ?: Int.MAX_VALUE
            }
            ?: emptyList()

        if (zoneDirs.isEmpty()) {
            recordError("no thermal_zone entries under $THERMAL_BASE")
            return
        }

        for (zoneDir in zoneDirs) {
            val id = zoneDir.name.removePrefix("thermal_zone").toIntOrNull() ?: continue
            val typePath = "$THERMAL_BASE/thermal_zone$id/type"
            val type = readSysfsText(typePath)
            if (type == null) {
                recordError("$typePath unreadable")
                continue
            }
            when {
                type.startsWith("gpuss") -> gpuss.add(id)
                type == "skin-msm-therm" -> skinZoneId = id
                type == "battery" -> batteryZoneId = id
            }
        }
        gpussZoneIds = gpuss.sorted()
    }

    fun readSnapshot(): Snapshot {
        val gpussTemps = gpussZoneIds.mapNotNull { id ->
            readZoneTempC(id)
        }
        val skinC = skinZoneId?.let { readZoneTempC(it) }
        val batteryC = batteryZoneId?.let { readZoneTempC(it) }
        val gpuCurHz = readSysfsLong(KGSL_CUR_FREQ)
        val gpuMaxHz = readSysfsLong(KGSL_MAX_FREQ)

        val anyThermalRead = gpussTemps.isNotEmpty() || skinC != null || batteryC != null
        val access = when {
            gpussZoneIds.isNotEmpty() && gpussTemps.isNotEmpty() -> SysfsAccess.OK
            anyThermalRead || gpuCurHz != null || gpuMaxHz != null -> SysfsAccess.PARTIAL
            firstError != null -> SysfsAccess.DENIED
            gpussZoneIds.isEmpty() && skinZoneId == null && batteryZoneId == null -> SysfsAccess.DENIED
            else -> SysfsAccess.DENIED
        }
        return Snapshot(access, gpussTemps, skinC, batteryC, gpuCurHz, gpuMaxHz)
    }

    fun formatProbeLine(): String {
        val snap = readSnapshot()
        val gpussCount = gpussZoneIds.size
        val idsLabel = formatZoneIdRange(gpussZoneIds)
        val sampleGpuss = snap.gpussTempsC.firstOrNull()?.let { formatTempC(it) } ?: DeviceTelemetryFormat.UNAVAILABLE
        val errSuffix = if (snap.access == SysfsAccess.DENIED && firstError != null) {
            " err=${firstError}"
        } else {
            ""
        }
        return buildString {
            append("Telemetry probe: sysfs=${accessLabel(snap.access)} ")
            append("gpussZones=$gpussCount ids=$idsLabel ")
            append("sampleGpuss=$sampleGpuss ")
            append("skin=${snap.skinC?.let { formatTempC(it) } ?: DeviceTelemetryFormat.UNAVAILABLE} ")
            append("battSysfs=${snap.batterySysfsC?.let { formatTempC(it) } ?: DeviceTelemetryFormat.UNAVAILABLE} ")
            append("gpuClk=${formatGpuClk(snap.gpuCurHz, snap.gpuMaxHz)}")
            append(errSuffix)
        }
    }

    fun formatPeriodicLine(): String {
        val snap = readSnapshot()
        val prefix = if (snap.access == SysfsAccess.OK) {
            ""
        } else {
            "sysfs=${accessLabel(snap.access)} "
        }
        return prefix +
            "Telemetry: gpuss=${formatGpussRange(snap.gpussTempsC)} " +
            "skin=${snap.skinC?.let { formatTempC(it) } ?: DeviceTelemetryFormat.UNAVAILABLE} " +
            "battSysfs=${snap.batterySysfsC?.let { formatTempC(it) } ?: DeviceTelemetryFormat.UNAVAILABLE} " +
            "gpuClk=${formatGpuClk(snap.gpuCurHz, snap.gpuMaxHz)}"
    }

    fun formatScanLine(workMs: Long): String {
        val snap = readSnapshot()
        val gpussLabel = if (snap.gpussTempsC.isEmpty()) {
            DeviceTelemetryFormat.UNAVAILABLE
        } else {
            formatTempC(snap.gpussTempsC.average())
        }
        return "Telemetry@scan: gpuss=$gpussLabel " +
            "skin=${snap.skinC?.let { formatTempC(it) } ?: DeviceTelemetryFormat.UNAVAILABLE} " +
            "gpuClk=${formatGpuClk(snap.gpuCurHz, snap.gpuMaxHz)} " +
            "workMs=$workMs"
    }

    private fun readZoneTempC(zoneId: Int): Double? {
        val raw = readSysfsText("$THERMAL_BASE/thermal_zone$zoneId/temp") ?: run {
            recordError("thermal_zone$zoneId/temp unreadable")
            return null
        }
        return DeviceTelemetryFormat.parseMillidegreeC(raw)
    }

    private fun readSysfsText(path: String): String? {
        return try {
            File(path).readText().trim().takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            recordError("$path: ${e.message ?: e.javaClass.simpleName}")
            null
        }
    }

    private fun readSysfsLong(path: String): Long? {
        val text = readSysfsText(path) ?: return null
        return text.toLongOrNull()
    }

    private fun recordError(msg: String) {
        if (firstError == null) {
            firstError = msg
        }
    }

    private fun accessLabel(access: SysfsAccess): String = when (access) {
        SysfsAccess.OK -> "ok"
        SysfsAccess.PARTIAL -> "partial"
        SysfsAccess.DENIED -> "denied"
    }
}

/** Pure formatting/parsing helpers (unit-tested without sysfs I/O). */
internal object DeviceTelemetryFormat {

    fun parseMillidegreeC(raw: String): Double? {
        val md = raw.trim().toIntOrNull() ?: return null
        if (md <= 0 || md >= 150_000 || md == -273_000 || md < -100_000) {
            return null
        }
        return md / 1000.0
    }

    fun formatGpussRange(tempsC: List<Double>): String {
        if (tempsC.isEmpty()) return UNAVAILABLE
        val min = tempsC.min()
        val max = tempsC.max()
        return if (max - min < 0.05) {
            formatTempC(min)
        } else {
            "${formatTempValue(min)}-${formatTempValue(max)}\u00B0C"
        }
    }

    fun formatTempC(celsius: Double): String =
        "${formatTempValue(celsius)}\u00B0C"

    private fun formatTempValue(celsius: Double): String =
        String.format(Locale.US, "%.1f", celsius)

    fun formatGpuClk(curHz: Long?, maxHz: Long?): String {
        val cur = curHz?.let { hzToMhzValue(it) } ?: UNAVAILABLE
        val max = maxHz?.let { hzToMhzValue(it) } ?: UNAVAILABLE
        return "$cur/${max}MHz"
    }

    const val UNAVAILABLE = "\u2014"

    fun formatZoneIdRange(ids: List<Int>): String {
        if (ids.isEmpty()) return UNAVAILABLE
        if (ids.size == 1) return ids[0].toString()
        val sorted = ids.sorted()
        if (sorted.last() - sorted.first() + 1 == sorted.size) {
            return "${sorted.first()}-${sorted.last()}"
        }
        return sorted.joinToString(",")
    }

    private fun hzToMhzValue(hz: Long): String {
        val mhz = hz / 1_000_000.0
        return if (mhz >= 100) {
            String.format(Locale.US, "%.0f", mhz)
        } else {
            String.format(Locale.US, "%.1f", mhz)
        }
    }
}

private fun formatTempC(celsius: Double): String = DeviceTelemetryFormat.formatTempC(celsius)

private fun formatGpussRange(tempsC: List<Double>): String = DeviceTelemetryFormat.formatGpussRange(tempsC)

private fun formatGpuClk(curHz: Long?, maxHz: Long?): String = DeviceTelemetryFormat.formatGpuClk(curHz, maxHz)

private fun formatZoneIdRange(ids: List<Int>): String = DeviceTelemetryFormat.formatZoneIdRange(ids)
