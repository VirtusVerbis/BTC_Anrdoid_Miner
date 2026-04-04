package com.btcminer.android

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.btcminer.android.databinding.FragmentDashboardPage2Binding

class DashboardStatsPage2Fragment : Fragment() {

    private var _binding: FragmentDashboardPage2Binding? = null
    val pageBinding get() = _binding

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentDashboardPage2Binding.inflate(inflater, container, false)
        return _binding!!.root
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
