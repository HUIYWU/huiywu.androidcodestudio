package com.tom.rv2ide.adapters

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.tom.rv2ide.fragments.ChatFragment
import com.tom.rv2ide.fragments.AIHistoryFragment

class ViewPagerAdapter(
    fragmentActivity: FragmentActivity
) : FragmentStateAdapter(fragmentActivity) {

    override fun getItemCount(): Int = 2

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> ChatFragment()
            1 -> AIHistoryFragment()
            else -> throw IllegalArgumentException("Invalid position: $position")
        }
    }
}