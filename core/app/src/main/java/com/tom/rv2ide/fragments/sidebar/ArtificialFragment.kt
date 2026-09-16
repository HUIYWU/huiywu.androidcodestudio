package com.tom.rv2ide.fragments.sidebar

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.tom.rv2ide.R
import com.tom.rv2ide.adapters.ViewPagerAdapter

/**
 * AI assistant sidebar entry.
 *
 * MUST keep a no-arg constructor: the sidebar framework instantiates this fragment
 * reflectively (via [com.tom.rv2ide.utils.EditorSidebarActions]) and Android may also
 * recreate it on configuration changes / process death.
 *
 * This fragment is only the host — Chat/History/Settings are child fragments, and each of them
 * resolves its own dependencies from the activity-scoped `AISharedViewModel` rather than through a
 * constructor.
 */
class ArtificialFragment : Fragment() {

    private lateinit var viewPager: ViewPager2
    private lateinit var tabLayout: TabLayout

    private var savedViewPagerPosition = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedViewPagerPosition = savedInstanceState?.getInt(KEY_VIEWPAGER_POSITION, 0) ?: 0
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_artificial, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewPager = view.findViewById(R.id.viewPager)
        tabLayout = view.findViewById(R.id.tabLayout)

        view.post {
            setupViewPager()

            if (savedInstanceState != null) {
                viewPager.setCurrentItem(savedViewPagerPosition, false)
            }
        }
    }

    private fun setupViewPager() {
        val adapter = ViewPagerAdapter(requireActivity())
        viewPager.adapter = adapter
        viewPager.offscreenPageLimit = 1
        viewPager.isUserInputEnabled = true

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            when (position) {
                0 -> {
                    tab.text = "Chat"
                    tab.setIcon(R.drawable.ic_chat)
                }
                1 -> {
                    tab.text = "History"
                    tab.setIcon(R.drawable.ic_history)
                }
                2 -> {
                    tab.text = "Settings"
                    tab.setIcon(R.drawable.ic_settings)
                }
            }
        }.attach()

        viewPager.post {
            viewPager.requestLayout()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::viewPager.isInitialized) {
            outState.putInt(KEY_VIEWPAGER_POSITION, viewPager.currentItem)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        com.tom.rv2ide.utils.EditorSidebarActions.removeFragmentFromCache("ide.editor.sidebar.ai_agent")
    }

    companion object {
        private const val KEY_VIEWPAGER_POSITION = "viewpager_position"
    }
}
