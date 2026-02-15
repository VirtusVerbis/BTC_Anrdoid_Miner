package com.btcminer.android.mining

/**
 * Runtime throttle state computed by the service from config, battery temp, and hashrate.
 * The engine reads this each chunk to apply effective intensity or stop on overheat.
 */
data class ThrottleState(
    val effectiveIntensityPercent: Int,
    val stopDueToOverheat: Boolean,
    val throttleSleepMs: Long = 0,
)
