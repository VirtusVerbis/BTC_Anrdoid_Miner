package com.btcminer.android.ui.chart

import java.util.Locale

fun formatDualScaleLabel(mhzTick: Float, msAtTick: Float): String =
    String.format(Locale.US, "%.0f Mhz\n%.0f Ms", mhzTick, msAtTick)

/** Max single-line width in dp for left-axis margin cap (Mhz line only, not full two-line string). */
fun dualScaleMaxLabelWidthDp(mhzMax: Float): Float {
    val sample = String.format(Locale.US, "%.0f Mhz", mhzMax)
    return sample.length * 4.5f + 4f
}

fun mapMsAtMhzTick(
    mhzTick: Float,
    mhzMin: Float,
    mhzRange: Float,
    msMin: Float,
    msRange: Float,
): Float = msMin + (mhzTick - mhzMin) / mhzRange * msRange
