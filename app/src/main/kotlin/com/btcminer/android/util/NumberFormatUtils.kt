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

    /** Same as dashboard page 1 mining timer: DD:HH:MM:SS (days capped at 99). */
    fun formatElapsedDdHhMmSs(elapsedMs: Long): String {
        var totalSeconds = (elapsedMs / 1000).coerceAtLeast(0)
        val days = (totalSeconds / 86400).coerceAtMost(99)
        totalSeconds %= 86400
        val hours = totalSeconds / 3600
        totalSeconds %= 3600
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.US, "%02d:%02d:%02d:%02d", days, hours, minutes, seconds)
    }

    /**
     * Dashboard page 2 total mining time: YY:MM:DD-HH:MM:SS as elapsed breakdown
     * (365-day years, 30-day months, not calendar).
     */
    fun formatTotalMiningTimeYyMmDdHhMmSs(elapsedMs: Long): String {
        var totalSeconds = (elapsedMs / 1000).coerceAtLeast(0)
        val secPerYear = 365L * 86400L
        val secPerMonth = 30L * 86400L
        val years = (totalSeconds / secPerYear).coerceAtMost(99L)
        totalSeconds %= secPerYear
        val months = totalSeconds / secPerMonth
        totalSeconds %= secPerMonth
        val days = totalSeconds / 86400L
        totalSeconds %= 86400L
        val hours = totalSeconds / 3600L
        totalSeconds %= 3600L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return String.format(
            Locale.US,
            "%02d:%02d:%02d-%02d:%02d:%02d",
            years.toInt(),
            months.toInt(),
            days.toInt(),
            hours.toInt(),
            minutes.toInt(),
            seconds.toInt(),
        )
    }

    /** Compact chain difficulty (e.g. from `nbits`): uses T / G / M suffixes for large values. */
    fun formatNetworkDifficultyForUi(value: Double): String {
        if (!value.isFinite() || value <= 0.0) return "—"
        return when {
            value >= 1e18 -> String.format(Locale.US, "%.2f\u2009E", value / 1e18)
            value >= 1e15 -> String.format(Locale.US, "%.2f\u2009P", value / 1e15)
            value >= 1e12 -> String.format(Locale.US, "%.2f\u2009T", value / 1e12)
            value >= 1e9 -> String.format(Locale.US, "%.2f\u2009G", value / 1e9)
            value >= 1e6 -> String.format(Locale.US, "%.2f\u2009M", value / 1e6)
            value >= 1e3 -> String.format(Locale.US, "%.2f\u2009k", value / 1e3)
            else -> String.format(Locale.US, "%.2f", value)
        }
    }
}
