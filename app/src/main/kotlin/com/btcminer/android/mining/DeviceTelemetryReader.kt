package com.btcminer.android.mining

import java.io.File
import java.util.Locale

/**
 * Reads Qualcomm thermal/clock sysfs for debug logcat (cpuss-*, cpu-*, gpuss-*, skin, battery,
 * cpuClk, gpuClk). Best-effort: many devices deny app access; probe line records sysfs=ok|partial|denied.
 */
object DeviceTelemetryReader {

    private const val THERMAL_BASE = "/sys/class/thermal"
    private const val CPUFREQ_BASE = "/sys/devices/system/cpu/cpufreq"
    private const val KGSL_CUR_FREQ = "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq"
    private const val KGSL_MAX_FREQ = "/sys/class/kgsl/kgsl-3d0/devfreq/max_freq"

    private var cpussZoneIds: List<Int> = emptyList()
    private var cpuZoneIds: List<Int> = emptyList()
    private var gpussZoneIds: List<Int> = emptyList()
    private var skinZoneId: Int? = null
    private var batteryZoneId: Int? = null
    private var cpuPolicyIds: List<Int> = emptyList()
    private var firstError: String? = null

    data class Snapshot(
        val access: SysfsAccess,
        val cpussTempsC: List<Double>,
        val cpuTempsC: List<Double>,
        val gpussTempsC: List<Double>,
        val skinC: Double?,
        val batterySysfsC: Double?,
        val cpuCurKhz: Long?,
        val cpuMaxKhz: Long?,
        val gpuCurHz: Long?,
        val gpuMaxHz: Long?,
    )

    enum class SysfsAccess {
        OK,
        PARTIAL,
        DENIED,
    }

    fun resetForSession() {
        cpussZoneIds = emptyList()
        cpuZoneIds = emptyList()
        gpussZoneIds = emptyList()
        skinZoneId = null
        batteryZoneId = null
        cpuPolicyIds = emptyList()
        firstError = null
    }

    fun discoverZones() {
        cpussZoneIds = emptyList()
        cpuZoneIds = emptyList()
        gpussZoneIds = emptyList()
        skinZoneId = null
        batteryZoneId = null
        cpuPolicyIds = emptyList()
        firstError = null

        discoverThermalZones()
        discoverCpuPolicies()
    }

    private fun discoverThermalZones() {
        val thermalDir = File(THERMAL_BASE)
        if (!thermalDir.isDirectory) {
            recordError("$THERMAL_BASE not accessible")
            return
        }

        val cpuss = mutableListOf<Int>()
        val cpu = mutableListOf<Int>()
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
                type.startsWith("cpuss") -> cpuss.add(id)
                type.startsWith("cpu-") -> cpu.add(id)
                type.startsWith("gpuss") -> gpuss.add(id)
                type == "skin-msm-therm" -> skinZoneId = id
                type == "battery" -> batteryZoneId = id
            }
        }
        cpussZoneIds = cpuss.sorted()
        cpuZoneIds = cpu.sorted()
        gpussZoneIds = gpuss.sorted()
    }

    private fun discoverCpuPolicies() {
        val cpufreqDir = File(CPUFREQ_BASE)
        if (!cpufreqDir.isDirectory) {
            recordError("$CPUFREQ_BASE not accessible")
            return
        }
        val policies = cpufreqDir.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("policy") }
            ?.mapNotNull { it.name.removePrefix("policy").toIntOrNull() }
            ?.sorted()
            ?: emptyList()
        if (policies.isEmpty()) {
            recordError("no policy entries under $CPUFREQ_BASE")
            return
        }
        cpuPolicyIds = policies
    }

    fun readSnapshot(): Snapshot {
        val cpussTemps = cpussZoneIds.mapNotNull { id -> readZoneTempC(id) }
        val cpuTemps = cpuZoneIds.mapNotNull { id -> readZoneTempC(id) }
        val gpussTemps = gpussZoneIds.mapNotNull { id -> readZoneTempC(id) }
        val skinC = skinZoneId?.let { readZoneTempC(it) }
        val batteryC = batteryZoneId?.let { readZoneTempC(it) }
        val (cpuCurKhz, cpuMaxKhz) = readCpuClockKhz()
        val gpuCurHz = readSysfsLong(KGSL_CUR_FREQ)
        val gpuMaxHz = readSysfsLong(KGSL_MAX_FREQ)

        val anyThermalRead = cpussTemps.isNotEmpty() || cpuTemps.isNotEmpty() ||
            gpussTemps.isNotEmpty() || skinC != null || batteryC != null
        val anyClockRead = cpuCurKhz != null || cpuMaxKhz != null || gpuCurHz != null || gpuMaxHz != null
        val noZonesDiscovered = gpussZoneIds.isEmpty() && cpussZoneIds.isEmpty() &&
            cpuZoneIds.isEmpty() && skinZoneId == null && batteryZoneId == null && cpuPolicyIds.isEmpty()
        val access = when {
            gpussZoneIds.isNotEmpty() && gpussTemps.isNotEmpty() -> SysfsAccess.OK
            anyThermalRead || anyClockRead -> SysfsAccess.PARTIAL
            firstError != null -> SysfsAccess.DENIED
            noZonesDiscovered -> SysfsAccess.DENIED
            else -> SysfsAccess.DENIED
        }
        return Snapshot(
            access,
            cpussTemps,
            cpuTemps,
            gpussTemps,
            skinC,
            batteryC,
            cpuCurKhz,
            cpuMaxKhz,
            gpuCurHz,
            gpuMaxHz,
        )
    }

    fun formatProbeLine(): String {
        val snap = readSnapshot()
        val sampleCpuss = formatTempRange(snap.cpussTempsC)
        val sampleCpu = formatTempRange(snap.cpuTempsC)
        val sampleGpuss = formatTempRange(snap.gpussTempsC)
        val errSuffix = if (snap.access == SysfsAccess.DENIED && firstError != null) {
            " err=${firstError}"
        } else {
            ""
        }
        return buildString {
            append("Telemetry probe: sysfs=${accessLabel(snap.access)} ")
            append("cpussZones=${cpussZoneIds.size} ids=${formatZoneIdRange(cpussZoneIds)} ")
            append("sampleCpuss=$sampleCpuss ")
            append("cpuZones=${cpuZoneIds.size} ids=${formatZoneIdRange(cpuZoneIds)} ")
            append("sampleCpu=$sampleCpu ")
            append("gpussZones=${gpussZoneIds.size} ids=${formatZoneIdRange(gpussZoneIds)} ")
            append("sampleGpuss=$sampleGpuss ")
            append("skin=${snap.skinC?.let { formatTempC(it) } ?: DeviceTelemetryFormat.UNAVAILABLE} ")
            append("battSysfs=${snap.batterySysfsC?.let { formatTempC(it) } ?: DeviceTelemetryFormat.UNAVAILABLE} ")
            append("cpuClk=${formatCpuClk(snap.cpuCurKhz, snap.cpuMaxKhz)} ")
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
            "Telemetry: cpuss=${formatTempRange(snap.cpussTempsC)} " +
            "cpu=${formatTempRange(snap.cpuTempsC)} " +
            "gpuss=${formatTempRange(snap.gpussTempsC)} " +
            "skin=${snap.skinC?.let { formatTempC(it) } ?: DeviceTelemetryFormat.UNAVAILABLE} " +
            "battSysfs=${snap.batterySysfsC?.let { formatTempC(it) } ?: DeviceTelemetryFormat.UNAVAILABLE} " +
            "cpuClk=${formatCpuClk(snap.cpuCurKhz, snap.cpuMaxKhz)} " +
            "gpuClk=${formatGpuClk(snap.gpuCurHz, snap.gpuMaxHz)}"
    }

    fun formatScanLine(workMs: Long): String {
        val snap = readSnapshot()
        return "Telemetry@scan: cpuss=${formatTempAverage(snap.cpussTempsC)} " +
            "cpu=${formatTempAverage(snap.cpuTempsC)} " +
            "gpuss=${formatTempAverage(snap.gpussTempsC)} " +
            "skin=${snap.skinC?.let { formatTempC(it) } ?: DeviceTelemetryFormat.UNAVAILABLE} " +
            "cpuClk=${formatCpuClk(snap.cpuCurKhz, snap.cpuMaxKhz)} " +
            "gpuClk=${formatGpuClk(snap.gpuCurHz, snap.gpuMaxHz)} " +
            "workMs=$workMs"
    }

    private fun readCpuClockKhz(): Pair<Long?, Long?> {
        if (cpuPolicyIds.isEmpty()) {
            return null to null
        }
        var maxCur: Long? = null
        var maxMax: Long? = null
        for (policyId in cpuPolicyIds) {
            val base = "$CPUFREQ_BASE/policy$policyId"
            readSysfsLong("$base/scaling_cur_freq")?.let { cur ->
                maxCur = if (maxCur == null) cur else maxOf(maxCur!!, cur)
            }
            readSysfsLong("$base/scaling_max_freq")?.let { max ->
                maxMax = if (maxMax == null) max else maxOf(maxMax!!, max)
            }
        }
        return maxCur to maxMax
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

    enum class ClkInputUnit {
        HZ,
        KHZ,
    }

    fun parseMillidegreeC(raw: String): Double? {
        val md = raw.trim().toIntOrNull() ?: return null
        if (md <= 0 || md >= 150_000 || md == -273_000 || md < -100_000) {
            return null
        }
        return md / 1000.0
    }

    fun formatTempRange(tempsC: List<Double>): String {
        if (tempsC.isEmpty()) return UNAVAILABLE
        val min = tempsC.min()
        val max = tempsC.max()
        return if (max - min < 0.05) {
            formatTempC(min)
        } else {
            "${formatTempValue(min)}-${formatTempValue(max)}\u00B0C"
        }
    }

    fun formatGpussRange(tempsC: List<Double>): String = formatTempRange(tempsC)

    fun formatTempAverage(tempsC: List<Double>): String {
        if (tempsC.isEmpty()) return UNAVAILABLE
        return formatTempC(tempsC.average())
    }

    fun formatTempC(celsius: Double): String =
        "${formatTempValue(celsius)}\u00B0C"

    private fun formatTempValue(celsius: Double): String =
        String.format(Locale.US, "%.1f", celsius)

    fun formatClkPair(cur: Long?, max: Long?, inputUnit: ClkInputUnit): String {
        val curMhz = cur?.let { toMhzValue(it, inputUnit) } ?: UNAVAILABLE
        val maxMhz = max?.let { toMhzValue(it, inputUnit) } ?: UNAVAILABLE
        return "$curMhz/${maxMhz}MHz"
    }

    fun formatGpuClk(curHz: Long?, maxHz: Long?): String =
        formatClkPair(curHz, maxHz, ClkInputUnit.HZ)

    fun formatCpuClk(curKhz: Long?, maxKhz: Long?): String =
        formatClkPair(curKhz, maxKhz, ClkInputUnit.KHZ)

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

    private fun toMhzValue(value: Long, inputUnit: ClkInputUnit): String {
        val mhz = when (inputUnit) {
            ClkInputUnit.HZ -> value / 1_000_000.0
            ClkInputUnit.KHZ -> value / 1_000.0
        }
        return if (mhz >= 100) {
            String.format(Locale.US, "%.0f", mhz)
        } else {
            String.format(Locale.US, "%.1f", mhz)
        }
    }
}

private fun formatTempC(celsius: Double): String = DeviceTelemetryFormat.formatTempC(celsius)

private fun formatTempRange(tempsC: List<Double>): String = DeviceTelemetryFormat.formatTempRange(tempsC)

private fun formatTempAverage(tempsC: List<Double>): String = DeviceTelemetryFormat.formatTempAverage(tempsC)

private fun formatCpuClk(curKhz: Long?, maxKhz: Long?): String = DeviceTelemetryFormat.formatCpuClk(curKhz, maxKhz)

private fun formatGpuClk(curHz: Long?, maxHz: Long?): String = DeviceTelemetryFormat.formatGpuClk(curHz, maxHz)

private fun formatZoneIdRange(ids: List<Int>): String = DeviceTelemetryFormat.formatZoneIdRange(ids)
