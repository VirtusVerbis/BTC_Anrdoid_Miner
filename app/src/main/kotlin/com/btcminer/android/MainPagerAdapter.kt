package com.btcminer.android

import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter

class MainPagerAdapter(activity: AppCompatActivity) : FragmentStateAdapter(activity) {

    override fun getItemCount(): Int = 3

    override fun createFragment(position: Int): Fragment = when (position) {
        0 -> DashboardStatsPage1Fragment()
        1 -> DashboardStatsPage2Fragment()
        2 -> DashboardStatsPage3Fragment()
        else -> throw IllegalArgumentException("position=$position")
    }
}
