package com.btcminer.android

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.btcminer.android.databinding.FragmentChartThermalBinding
import com.btcminer.android.mining.ThermalCellRect
import com.btcminer.android.mining.ThermalColorScale
import com.btcminer.android.mining.ThermalSensorGroup
import com.btcminer.android.mining.ThermalTempFormat
import com.btcminer.android.mining.ThermalUiState

class ChartThermalFragment : Fragment() {

    private var _binding: FragmentChartThermalBinding? = null
    val chartBinding get() = _binding

    private var useFahrenheit: Boolean = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentChartThermalBinding.inflate(inflater, container, false)
        val binding = _binding!!
        binding.thermalTreemapView.onCellTapped = { cell -> showCellTooltip(cell) }
        binding.thermalTreemapView.onBackgroundTapped = { dismissTooltip() }
        binding.thermalLegendView.setOnClickListener { dismissTooltip() }
        binding.thermalBatteryLegendView.setOnClickListener { dismissTooltip() }
        configureLegendBands(useFahrenheit, state = null)
        return binding.root
    }

    fun updateThermalChart(state: ThermalUiState?, useFahrenheit: Boolean) {
        this.useFahrenheit = useFahrenheit
        val binding = _binding ?: return
        configureLegendBands(useFahrenheit, state)
        binding.thermalTreemapView.bind(state, useFahrenheit)
        binding.thermalLegendView.setUseFahrenheit(useFahrenheit)
        binding.thermalBatteryLegendView.setUseFahrenheit(useFahrenheit)
    }

    private fun hasSkinSensor(state: ThermalUiState?): Boolean =
        state?.readings?.any { it.meta.group == ThermalSensorGroup.SKIN } == true

    private fun configureLegendBands(useFahrenheit: Boolean, state: ThermalUiState?) {
        val binding = _binding ?: return
        val unit = ThermalTempFormat.unitSuffix(useFahrenheit)
        binding.thermalLegendView.setLegendBand(
            ThermalColorScale.UNIFIED_BAND,
            getString(R.string.thermal_legend_temp_unit, unit),
        )
        val battTitle = if (hasSkinSensor(state)) {
            getString(R.string.thermal_legend_batt_skin)
        } else {
            getString(R.string.thermal_legend_batt)
        }
        binding.thermalBatteryLegendView.setLegendBand(
            ThermalColorScale.BATTERY_BAND,
            battTitle,
        )
    }

    private fun showCellTooltip(cell: ThermalCellRect) {
        val binding = _binding ?: return
        val zonePart = cell.meta.zoneId?.let { getString(R.string.thermal_tooltip_zone, it) } ?: ""
        val text = buildString {
            append(cell.meta.type)
            if (zonePart.isNotEmpty()) {
                append('\n')
                append(zonePart)
            }
            append('\n')
            append(ThermalTempFormat.formatTempForDisplay(cell.reading.tempC, useFahrenheit))
        }
        binding.thermalLegendView.showTooltip(text)
    }

    private fun dismissTooltip() {
        _binding?.thermalLegendView?.dismissTooltip()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
