package com.btcminer.android.mining

/**
 * Pure classification/parsing for thermal zone type strings (unit-tested without sysfs).
 */
object ThermalSensorClassification {

    private val CPU_CLUSTER_CORE = Regex("^cpu-(\\d+)-(\\d+)-")
    private val VIRTUAL_SUFFIX = Regex("-(step|max-step|max|lowf)$")

    fun classifyType(type: String, zoneId: Int): ThermalSensorMeta? {
        val group = when {
            type.startsWith("cpuss") -> ThermalSensorGroup.CPUSS
            type.startsWith("cpu-") -> ThermalSensorGroup.CPU
            type.startsWith("gpuss") -> ThermalSensorGroup.GPUSS
            type == "skin-msm-therm" -> ThermalSensorGroup.SKIN
            type == "battery" -> ThermalSensorGroup.BATTERY_SYSFS
            else -> return null
        }
        val isVirtual = VIRTUAL_SUFFIX.containsMatchIn(type)
        val clusterCore = CPU_CLUSTER_CORE.find(type)
        val cluster = clusterCore?.groupValues?.get(1)?.toIntOrNull()
        val core = clusterCore?.groupValues?.get(2)?.toIntOrNull()
        return ThermalSensorMeta(
            zoneId = zoneId,
            type = type,
            group = group,
            isVirtual = isVirtual,
            cluster = cluster,
            core = core,
            shortLabel = shortLabel(type, group, isVirtual, cluster, core),
        )
    }

    fun batteryApiMeta(): ThermalSensorMeta = ThermalSensorMeta(
        zoneId = null,
        type = "battery-api",
        group = ThermalSensorGroup.BATTERY_API,
        isVirtual = false,
        cluster = null,
        core = null,
        shortLabel = "batt-api",
    )

    private fun shortLabel(
        type: String,
        group: ThermalSensorGroup,
        isVirtual: Boolean,
        cluster: Int?,
        core: Int?,
    ): String {
        if (cluster != null && core != null) {
            return "$cluster-$core"
        }
        val base = when (group) {
            ThermalSensorGroup.SKIN -> "skin"
            ThermalSensorGroup.BATTERY_SYSFS -> "batt"
            ThermalSensorGroup.BATTERY_API -> "batt-api"
            ThermalSensorGroup.CPUSS -> trailingIndex(type, "cpuss") ?: type
            ThermalSensorGroup.GPUSS -> trailingIndex(type, "gpuss") ?: type
            ThermalSensorGroup.CPU -> abbreviateCpuExtra(type)
        }
        return if (isVirtual) "V:$base" else base
    }

    private fun trailingIndex(type: String, prefix: String): String? {
        val rest = type.removePrefix(prefix).trim('-', '_')
        if (rest.isEmpty()) return null
        val idx = rest.takeWhile { it.isDigit() || it == '-' }
        return idx.ifEmpty { rest.take(8) }
    }

    private fun abbreviateCpuExtra(type: String): String {
        val stripped = type.removePrefix("cpu-")
        return if (stripped.length > 10) stripped.take(8) + "…" else stripped
    }
}

enum class ThermalSensorGroup {
    CPUSS,
    CPU,
    GPUSS,
    SKIN,
    BATTERY_SYSFS,
    BATTERY_API,
}

data class ThermalSensorMeta(
    val zoneId: Int?,
    val type: String,
    val group: ThermalSensorGroup,
    val isVirtual: Boolean,
    val cluster: Int?,
    val core: Int?,
    val shortLabel: String,
)

data class ThermalSensorReading(
    val meta: ThermalSensorMeta,
    val tempC: Double,
)

data class ThermalUiState(
    val access: DeviceTelemetryReader.SysfsAccess,
    val layout: ThermalTreemapLayout?,
    val readings: List<ThermalSensorReading>,
    val discoveredAtMs: Long,
    val updatedAtMs: Long,
)
