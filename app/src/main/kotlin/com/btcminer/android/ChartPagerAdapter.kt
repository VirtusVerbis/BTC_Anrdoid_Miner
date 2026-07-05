package com.btcminer.android

import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter

class ChartPagerAdapter(activity: AppCompatActivity) : FragmentStateAdapter(activity) {

    override fun getItemCount(): Int = 5

    override fun createFragment(position: Int): Fragment = when (position) {
        0 -> ChartHashrateFragment()
        1 -> ChartThermalFragment()
        2 -> ChartSharesDonutFragment()
        3 -> ChartBestDifficultyFragment()
        4 -> ChartMandelbrotFragment()
        else -> throw IllegalArgumentException("position=$position")
    }
}
