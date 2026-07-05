package com.btcminer.android.mining

import org.junit.Assert.assertEquals
import org.junit.Test

class ThermalTempFormatTest {

    @Test
    fun formatTempForDisplay_celsiusAndFahrenheit() {
        assertEquals("43.0 °C", ThermalTempFormat.formatTempForDisplay(43.0, false))
        assertEquals("109.4 °F", ThermalTempFormat.formatTempForDisplay(43.0, true))
        assertEquals("32.0 °F", ThermalTempFormat.formatTempForDisplay(0.0, true))
    }

    @Test
    fun formatCellLabel_wholeNumberNoUnit() {
        assertEquals("43", ThermalTempFormat.formatCellLabel(43.0, false))
        assertEquals("109", ThermalTempFormat.formatCellLabel(43.0, true))
    }

    @Test
    fun formatTickLabel_andUnitSuffix() {
        assertEquals("43", ThermalTempFormat.formatTickLabel(43.0, false))
        assertEquals("109", ThermalTempFormat.formatTickLabel(43.0, true))
        assertEquals("°C", ThermalTempFormat.unitSuffix(false))
        assertEquals("°F", ThermalTempFormat.unitSuffix(true))
    }
}
