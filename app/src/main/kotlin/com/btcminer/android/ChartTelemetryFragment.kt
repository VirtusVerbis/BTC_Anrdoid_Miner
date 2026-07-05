package com.btcminer.android

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.btcminer.android.databinding.FragmentChartTelemetryBinding

class ChartTelemetryFragment : Fragment() {

    private var _binding: FragmentChartTelemetryBinding? = null
    val chartBinding get() = _binding

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentChartTelemetryBinding.inflate(inflater, container, false)
        return _binding!!.root
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
