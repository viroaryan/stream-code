/*
 * SPDX-FileCopyrightText: 2026 Abhiraj Aryan
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.fragments

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import com.google.android.material.tabs.TabLayout
import org.schabi.newpipe.R
import org.schabi.newpipe.databinding.FragmentHomeBinding
import org.schabi.newpipe.error.ErrorInfo
import org.schabi.newpipe.error.UserAction
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.fragments.list.kiosk.KioskFragment

/**
 * Stream's Home tab: YouTube's kiosks (Music, Gaming, Live, Podcasts, Movies) as
 * scrollable text tabs on top, each showing its videos below. Switching tabs keeps
 * every list (and its scroll position) alive, like YouTube's own top tabs.
 */
class HomeFragment : Fragment() {

    private data class HomeTab(val kioskId: String, @StringRes val title: Int)

    private var binding: FragmentHomeBinding? = null
    private var selected = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selected = savedInstanceState?.getInt(KEY_SELECTED, 0) ?: 0
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = FragmentHomeBinding.inflate(inflater, container, false)
        .also { binding = it }
        .root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val tabs = binding!!.homeTabs
        TABS.forEach { tabs.addTab(tabs.newTab().setText(it.title), false) }
        selected = selected.coerceIn(0, TABS.lastIndex)
        tabs.getTabAt(selected)?.select()
        show(selected)

        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                selected = tab.position
                show(selected)
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_SELECTED, selected)
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    /** Shows the list of [TABS]`[index]`, creating it the first time, hiding the others. */
    private fun show(index: Int) {
        val fm = childFragmentManager
        val transaction = fm.beginTransaction().setReorderingAllowed(true)

        TABS.indices.forEach { i ->
            val existing = fm.findFragmentByTag(tagFor(i))
            if (i == index) {
                if (existing == null) {
                    transaction.add(R.id.home_content, createList(TABS[i]), tagFor(i))
                } else {
                    transaction.show(existing)
                }
            } else if (existing != null) {
                transaction.hide(existing)
            }
        }
        transaction.commitAllowingStateLoss()
    }

    private fun createList(tab: HomeTab): Fragment = try {
        // keep the Stream logo in the toolbar instead of the list name
        if (tab.kioskId == ALL) {
            HomeFeedFragment().apply { useAsFrontPage(true) }
        } else {
            KioskFragment.getInstance(ServiceList.YouTube.serviceId, tab.kioskId).apply {
                useAsFrontPage(true)
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "Could not create kiosk ${tab.kioskId}", e)
        BlankFragment(ErrorInfo(e, UserAction.REQUESTED_KIOSK, "Home tab ${tab.kioskId}"))
    }

    private fun tagFor(index: Int) = "home_tab_${TABS[index].kioskId}"

    companion object {
        private const val TAG = "HomeFragment"
        private const val KEY_SELECTED = "home_selected_tab"

        /** Pseudo kiosk id of the mixed "All" feed, see [HomeFeedFragment]. */
        private const val ALL = "stream_all"

        private val TABS = listOf(
            HomeTab(ALL, R.string.home_tab_all),
            HomeTab("trending_music", R.string.home_tab_music),
            HomeTab("trending_gaming", R.string.home_tab_gaming),
            HomeTab("live", R.string.home_tab_live),
            HomeTab("trending_podcasts_episodes", R.string.home_tab_podcasts),
            HomeTab("trending_movies_and_shows", R.string.home_tab_movies)
        )
    }
}
