/*
 * SPDX-FileCopyrightText: 2026 Abhiraj Aryan
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.local.shorts

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.disposables.Disposable
import org.schabi.newpipe.R
import org.schabi.newpipe.databinding.FragmentShortsBinding
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.info_list.dialog.InfoItemDialog
import org.schabi.newpipe.player.playqueue.SinglePlayQueue
import org.schabi.newpipe.util.NavigationHelper
import org.schabi.newpipe.util.ThemeHelper

/**
 * Stream's Shorts tab: Shorts from the user's subscriptions mixed with popular channels'
 * Shorts (see [ShortsDiscovery]), as a grid of portrait cards. Tapping one plays it and
 * queues all the others, so "next" keeps going like on YouTube.
 */
class ShortsFragment : Fragment() {

    private var binding: FragmentShortsBinding? = null
    private var disposable: Disposable? = null
    private var loaded = false

    private val adapter = ShortsAdapter(
        onClick = ::play,
        onLongClick = ::showMenu
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = FragmentShortsBinding.inflate(inflater, container, false)
        .also { binding = it }
        .root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val b = binding!!
        val span = maxOf(
            2,
            ThemeHelper.getGridSpanCount(
                requireContext(),
                resources.getDimensionPixelSize(R.dimen.shorts_card_min_width)
            )
        )
        b.shortsGrid.layoutManager = GridLayoutManager(requireContext(), span)
        b.shortsGrid.adapter = adapter
        b.shortsRefresh.setOnRefreshListener { load(forceRefresh = true) }
        load(forceRefresh = false)
    }

    override fun onDestroyView() {
        disposable?.dispose()
        binding = null
        super.onDestroyView()
    }

    private fun load(forceRefresh: Boolean) {
        disposable?.dispose()
        binding?.let {
            it.shortsMessage.isVisible = false
            it.shortsProgress.isVisible = !loaded && !it.shortsRefresh.isRefreshing
        }
        disposable = ShortsDiscovery.load(requireContext(), forceRefresh)
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                { items ->
                    loaded = items.isNotEmpty()
                    adapter.submit(items)
                    binding?.let {
                        it.shortsProgress.isVisible = false
                        it.shortsRefresh.isRefreshing = false
                        it.shortsMessage.isVisible = items.isEmpty()
                        it.shortsMessage.setText(R.string.shorts_empty)
                    }
                },
                { e ->
                    Log.e(TAG, "Could not load shorts", e)
                    binding?.let {
                        it.shortsProgress.isVisible = false
                        it.shortsRefresh.isRefreshing = false
                        it.shortsMessage.isVisible = adapter.itemCount == 0
                        it.shortsMessage.setText(R.string.shorts_load_error)
                    }
                }
            )
    }

    private fun play(items: List<StreamInfoItem>, position: Int) {
        if (position !in items.indices) return
        val activity = activity as? AppCompatActivity ?: return
        NavigationHelper.playOnMainPlayer(activity, SinglePlayQueue(items, position))
    }

    private fun showMenu(item: StreamInfoItem) {
        val activity = activity ?: return
        try {
            InfoItemDialog.Builder(activity, requireContext(), this, item).create().show()
        } catch (e: IllegalArgumentException) {
            InfoItemDialog.Builder.reportErrorDuringInitialization(e, item)
        }
    }

    companion object {
        private const val TAG = "ShortsFragment"
    }
}
