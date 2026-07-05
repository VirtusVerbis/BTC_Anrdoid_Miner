package com.btcminer.android

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.btcminer.android.databinding.FragmentChartThermalBinding
import com.btcminer.android.mining.ThermalCellRect
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
        return binding.root
    }

    fun updateThermalChart(state: ThermalUiState?, useFahrenheit: Boolean) {
        this.useFahrenheit = useFahrenheit
        val binding = _binding ?: return
        binding.thermalTreemapView.bind(state, useFahrenheit)
        binding.thermalLegendView.setUseFahrenheit(useFahrenheit)
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
