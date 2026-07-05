package com.btcminer.android.mining

/**
 * One telemetry chart sample point (temps in °C, clocks in MHz). [Float.NaN] when unavailable.
 */
data class TelemetryChartSample(
    val cpussAvgC: Float,
    val cpuAvgC: Float,
    val gpussAvgC: Float,
    val gpuAvgC: Float,
    val skinC: Float,
    val batteryAvgC: Float,
    val cpuClkMhz: Float,
    val gpuClkMhz: Float,
) {
    companion object {
        fun aggregateForChart(
            snapshot: DeviceTelemetryReader.Snapshot,
            batteryApiTempC: Double?,
        ): TelemetryChartSample = TelemetryChartSample(
            cpussAvgC = averageOrNaN(snapshot.cpussTempsC),
            cpuAvgC = averageOrNaN(snapshot.cpuTempsC),
            gpussAvgC = averageOrNaN(snapshot.gpussTempsC),
            gpuAvgC = averageOrNaN(snapshot.gpuTempsC),
            skinC = snapshot.skinC?.toFloat()?.takeIf { it.isFinite() } ?: Float.NaN,
            batteryAvgC = averageBatteryC(snapshot.batterySysfsC, batteryApiTempC),
            cpuClkMhz = snapshot.cpuCurKhz?.let { (it / 1000f).takeIf { m -> m.isFinite() } } ?: Float.NaN,
            gpuClkMhz = snapshot.gpuCurHz?.let { (it / 1_000_000f).takeIf { m -> m.isFinite() } } ?: Float.NaN,
        )

        fun averageOrNaN(tempsC: List<Double>): Float {
            if (tempsC.isEmpty()) return Float.NaN
            return tempsC.average().toFloat()
        }

        fun averageBatteryC(batterySysfsC: Double?, batteryApiTempC: Double?): Float {
            val values = buildList {
                batterySysfsC?.toFloat()?.takeIf { it.isFinite() }?.let { add(it) }
                batteryApiTempC?.toFloat()?.takeIf { it.isFinite() }?.let { add(it) }
            }
            if (values.isEmpty()) return Float.NaN
            return values.average().toFloat()
        }
    }
}

fun hasFiniteTelemetryValues(slice: List<Float>): Boolean = slice.any { it.isFinite() }
