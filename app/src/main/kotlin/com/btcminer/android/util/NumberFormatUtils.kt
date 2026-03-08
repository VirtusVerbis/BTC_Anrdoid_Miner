package com.btcminer.android.util

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

object NumberFormatUtils {
    private val longSymbols = DecimalFormatSymbols(Locale.US).apply { groupingSeparator = '\u2009' }
    private val longFormat = DecimalFormat("#,###", longSymbols)
    private val doubleSymbols = DecimalFormatSymbols(Locale.US).apply { groupingSeparator = '\u2009' }
    private val hashrateFormat = DecimalFormat("#,###.00", doubleSymbols)

    /** 3000000000 -> "3 000 000 000" */
    fun formatWithSpaces(value: Long): String = longFormat.format(value)

    /** 4000000.00 -> "4 000 000.00" */
    fun formatHashrateWithSpaces(value: Double): String = hashrateFormat.format(value)

    /** 1234 -> "1 234" (for blockTemplate, share counts, etc.) */
    fun formatIntWithSpaces(value: Int): String = longFormat.format(value.toLong())

    /** 113838 -> "1:54" (minutes:seconds from milliseconds) */
    fun formatDurationMmSs(durationMs: Long): String {
        val totalSec = (durationMs / 1000).coerceAtLeast(0)
        val minutes = totalSec / 60
        val seconds = totalSec % 60
        return String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}
