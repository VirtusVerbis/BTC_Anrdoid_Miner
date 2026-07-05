package com.btcminer.android

import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter

class ChartPagerAdapter(activity: AppCompatActivity) : FragmentStateAdapter(activity) {

    override fun getItemCount(): Int = 6

    override fun createFragment(position: Int): Fragment = when (position) {
        0 -> ChartHashrateFragment()
        1 -> ChartTelemetryFragment()
        2 -> ChartThermalFragment()
        3 -> ChartSharesDonutFragment()
        4 -> ChartBestDifficultyFragment()
        5 -> ChartMandelbrotFragment()
        else -> throw IllegalArgumentException("position=$position")
    }
}
