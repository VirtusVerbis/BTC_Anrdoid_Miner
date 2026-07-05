package com.btcminer.android.mining

import org.json.JSONArray
import org.json.JSONObject

/** JSON encode/decode for idle thermal treemap restore; see [MiningStatsRepository.saveThermalChartState]. */
data class PersistedThermalChartState(
    val access: DeviceTelemetryReader.SysfsAccess,
    val readings: List<ThermalSensorReading>,
    val discoveredAtMs: Long,
    val updatedAtMs: Long,
)

internal object ThermalChartStateCodec {

    const val VERSION = 1

    fun encode(state: ThermalUiState): String {
        val readingsArr = JSONArray()
        for (reading in state.readings) {
            val meta = reading.meta
            readingsArr.put(
                JSONObject().apply {
                    put("type", meta.type)
                    if (meta.zoneId != null) put("zoneId", meta.zoneId) else put("zoneId", JSONObject.NULL)
                    put("group", meta.group.name)
                    put("isVirtual", meta.isVirtual)
                    if (meta.cluster != null) put("cluster", meta.cluster) else put("cluster", JSONObject.NULL)
                    if (meta.core != null) put("core", meta.core) else put("core", JSONObject.NULL)
                    put("shortLabel", meta.shortLabel)
                    put("tempC", reading.tempC)
                },
            )
        }
        return JSONObject().apply {
            put("v", VERSION)
            put("access", state.access.name)
            put("discoveredAtMs", state.discoveredAtMs)
            put("updatedAtMs", state.updatedAtMs)
            put("readings", readingsArr)
        }.toString()
    }

    fun decode(raw: String): PersistedThermalChartState? {
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        if (root.optInt("v", 0) != VERSION) return null
        val access = runCatching {
            DeviceTelemetryReader.SysfsAccess.valueOf(root.optString("access", ""))
        }.getOrNull() ?: return null
        val discoveredAtMs = root.optLong("discoveredAtMs", 0L).coerceAtLeast(0L)
        val updatedAtMs = root.optLong("updatedAtMs", 0L).coerceAtLeast(0L)
        val arr = root.optJSONArray("readings") ?: return null
        val readings = ArrayList<ThermalSensorReading>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: return null
            val type = o.optString("type", "")
            if (type.isEmpty()) return null
            val group = runCatching {
                ThermalSensorGroup.valueOf(o.optString("group", ""))
            }.getOrNull() ?: return null
            val tempC = o.optDouble("tempC", Double.NaN)
            if (!tempC.isFinite()) return null
            val zoneId = if (o.isNull("zoneId")) null else o.optInt("zoneId")
            val cluster = if (o.isNull("cluster")) null else o.optInt("cluster")
            val core = if (o.isNull("core")) null else o.optInt("core")
            val meta = ThermalSensorMeta(
                zoneId = zoneId,
                type = type,
                group = group,
                isVirtual = o.optBoolean("isVirtual", false),
                cluster = cluster,
                core = core,
                shortLabel = o.optString("shortLabel", type),
            )
            readings.add(ThermalSensorReading(meta, tempC))
        }
        if (readings.isEmpty()) return null
        return PersistedThermalChartState(
            access = access,
            readings = readings,
            discoveredAtMs = discoveredAtMs,
            updatedAtMs = updatedAtMs,
        )
    }
}
