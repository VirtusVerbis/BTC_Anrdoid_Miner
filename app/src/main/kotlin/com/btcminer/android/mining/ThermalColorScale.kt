package com.btcminer.android.mining

import com.btcminer.android.config.MiningConfig
import kotlin.math.roundToInt

object ThermalColorScale {

    data class Band(val breakpointsC: DoubleArray) {
        val size: Int get() = breakpointsC.size
    }

    val UNIFIED_BAND = Band(doubleArrayOf(30.0, 50.0, 70.0, 90.0, 110.0))
    val CPU_BAND = Band(doubleArrayOf(35.0, 42.0, 48.0, 55.0))
    val GPU_BAND = Band(doubleArrayOf(35.0, 45.0, 55.0, 65.0))
    val BATTERY_BAND = Band(doubleArrayOf(25.0, 32.0, 38.0, 43.0))

    private const val DANGER_COMPUTE_C = 80.0
    private const val DANGER_SKIN_C = 45.0

    private val STOPS = intArrayOf(
        argb(255, 76, 175, 80),
        argb(255, 255, 235, 59),
        argb(255, 255, 152, 0),
        argb(255, 244, 67, 54),
        argb(255, 183, 28, 28),
    )

    fun colorForGroup(group: ThermalSensorGroup, tempC: Double): Int =
        colorForBand(UNIFIED_BAND, tempC)

    fun dangerThresholdC(group: ThermalSensorGroup): Double? = when (group) {
        ThermalSensorGroup.CPU, ThermalSensorGroup.CPUSS, ThermalSensorGroup.GPUSS -> DANGER_COMPUTE_C
        ThermalSensorGroup.BATTERY_SYSFS, ThermalSensorGroup.BATTERY_API ->
            MiningConfig.BATTERY_TEMP_HARD_STOP_C.toDouble()
        ThermalSensorGroup.SKIN -> DANGER_SKIN_C
    }

    fun isInDangerZone(group: ThermalSensorGroup, tempC: Double): Boolean {
        val threshold = dangerThresholdC(group) ?: return false
        return tempC >= threshold
    }

    fun colorForBand(band: Band, tempC: Double): Int {
        val bp = band.breakpointsC
        if (tempC <= bp.first()) return STOPS.first()
        if (tempC >= bp.last()) return STOPS.last()
        for (i in 0 until bp.size - 1) {
            val lo = bp[i]
            val hi = bp[i + 1]
            if (tempC <= hi) {
                val t = ((tempC - lo) / (hi - lo)).toFloat().coerceIn(0f, 1f)
                return blend(STOPS[i], STOPS[i + 1], t)
            }
        }
        return STOPS.last()
    }

    fun legendGradientColors(band: Band): IntArray {
        val n = 32
        return IntArray(n) { i ->
            val tempC = band.breakpointsC.first() +
                (band.breakpointsC.last() - band.breakpointsC.first()) * i / (n - 1).coerceAtLeast(1)
            colorForBand(band, tempC)
        }
    }

    private fun blend(c1: Int, c2: Int, t: Float): Int {
        val a = lerpChannel(alpha(c1), alpha(c2), t)
        val r = lerpChannel(red(c1), red(c2), t)
        val g = lerpChannel(green(c1), green(c2), t)
        val b = lerpChannel(blue(c1), blue(c2), t)
        return argb(a, r, g, b)
    }

    private fun lerpChannel(c1: Int, c2: Int, t: Float): Int =
        (c1 + t * (c2 - c1)).roundToInt().coerceIn(0, 255)

    private fun argb(a: Int, r: Int, g: Int, b: Int): Int =
        (a shl 24) or (r shl 16) or (g shl 8) or b

    private fun alpha(c: Int): Int = (c shr 24) and 0xFF
    private fun red(c: Int): Int = (c shr 16) and 0xFF
    private fun green(c: Int): Int = (c shr 8) and 0xFF
    private fun blue(c: Int): Int = c and 0xFF
}
