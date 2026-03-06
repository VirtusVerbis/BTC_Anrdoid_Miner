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
}
