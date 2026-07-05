package com.btcminer.android.mining

import java.util.Locale

object ThermalTempFormat {

    fun formatTempForDisplay(tempC: Double, useFahrenheit: Boolean): String =
        if (useFahrenheit) {
            String.format(Locale.US, "%.1f °F", tempC * 9.0 / 5.0 + 32.0)
        } else {
            String.format(Locale.US, "%.1f °C", tempC)
        }

    fun formatTickLabel(tempC: Double, useFahrenheit: Boolean): String =
        if (useFahrenheit) {
            String.format(Locale.US, "%.0f", tempC * 9.0 / 5.0 + 32.0)
        } else {
            String.format(Locale.US, "%.0f", tempC)
        }

    fun formatCellLabel(tempC: Double, useFahrenheit: Boolean): String = formatTickLabel(tempC, useFahrenheit)

    fun unitSuffix(useFahrenheit: Boolean): String = if (useFahrenheit) "°F" else "°C"
}
