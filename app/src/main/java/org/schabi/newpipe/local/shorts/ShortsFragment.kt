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
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.schedulers.Schedulers
import org.schabi.newpipe.R
import org.schabi.newpipe.databinding.FragmentShortsBinding
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.feedengine.ActivitySignals
import org.schabi.newpipe.feedengine.StreamPulse
import org.schabi.newpipe.feedengine.ui.FeedRow
import org.schabi.newpipe.feedengine.ui.HomeFeedAdapter
import org.schabi.newpipe.info_list.dialog.InfoItemDialog
import org.schabi.newpipe.player.playqueue.SinglePlayQueue
import org.schabi.newpipe.util.NavigationHelper

/**
 * Stream's Shorts tab: Shorts from all of YouTube ranked by [StreamPulse] for this user
 * (related to what they watched, their topics, subscriptions and popular channels), as big
 * portrait cards in two columns. Tapping one plays it and queues the rest.
 */
class ShortsFragment : Fragment(), HomeFeedAdapter.Callbacks {

    private var binding: FragmentShortsBinding? = null
    private val disposables = CompositeDisposable()
    private val adapter = HomeFeedAdapter(this)
    private var session: StreamPulse.Session? = null
    private var loadingMore = false
    private val pendingImpressions = LinkedHashSet<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        adapter.setHasStableIds(true)
    }

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
        val layoutManager = GridLayoutManager(requireContext(), HomeFeedAdapter.SPAN)
        layoutManager.spanSizeLookup = adapter.spanSizeLookup
        b.shortsGrid.layoutManager = layoutManager
        b.shortsGrid.adapter = adapter
        b.shortsGrid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy > 0 && layoutManager.findLastVisibleItemPosition() > adapter.itemCount - 8) {
                    loadMore()
                }
            }
        })
        b.shortsRefresh.setOnRefreshListener { load() }
        if (adapter.itemCount == 0) load()
    }

    override fun onPause() {
        flushImpressions()
        super.onPause()
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    override fun onDestroy() {
        disposables.dispose()
        super.onDestroy()
    }

    private fun load() {
        val b = binding ?: return
        b.shortsMessage.isVisible = false
        b.shortsProgress.isVisible = adapter.itemCount == 0 && !b.shortsRefresh.isRefreshing
        loadingMore = true
        disposables.add(
            StreamPulse.shorts(requireContext())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({ result ->
                    session = result.session
                    adapter.setRows(result.shorts.map { FeedRow.Short(it) })
                    loadingMore = false
                    binding?.let {
                        it.shortsProgress.isVisible = false
                        it.shortsRefresh.isRefreshing = false
                        it.shortsMessage.isVisible = adapter.itemCount == 0
                        it.shortsMessage.setText(R.string.shorts_empty)
                    }
                }, { e ->
                    Log.e(TAG, "Could not load shorts", e)
                    loadingMore = false
                    binding?.let {
                        it.shortsProgress.isVisible = false
                        it.shortsRefresh.isRefreshing = false
                        it.shortsMessage.isVisible = adapter.itemCount == 0
                        it.shortsMessage.setText(R.string.shorts_load_error)
                    }
                })
        )
    }

    private fun loadMore() {
        val current = session ?: return
        if (loadingMore || !current.hasMore()) return
        loadingMore = true
        disposables.add(
            StreamPulse.more(requireContext(), current)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({ shorts ->
                    if (session === current && shorts.isNotEmpty()) {
                        // keep the grid even
                        val even = shorts.take(shorts.size / 2 * 2)
                        adapter.appendRows(even.map { FeedRow.Short(it) })
                    }
                    loadingMore = false
                }, { e ->
                    Log.w(TAG, "Could not load more shorts", e)
                    loadingMore = false
                })
        )
    }

    override fun onShortClick(item: StreamInfoItem) {
        val shorts = adapter.rows().filterIsInstance<FeedRow.Short>().map { it.item }
        val index = shorts.indexOfFirst { it.url == item.url }.coerceAtLeast(0)
        val activity = activity as? AppCompatActivity ?: return
        NavigationHelper.playOnMainPlayer(activity, SinglePlayQueue(shorts, index))
    }

    override fun onShortMenu(item: StreamInfoItem, anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menu.add(0, 1, 0, R.string.feed_not_interested)
        popup.menu.add(0, 2, 1, R.string.feed_dont_recommend_channel)
        popup.menu.add(0, 3, 2, R.string.more_options)
        popup.setOnMenuItemClickListener { menuItem ->
            val signals = ActivitySignals.get(requireContext())
            when (menuItem.itemId) {
                1 -> {
                    signals.markNotInterested(item)
                    removeWhere { it.url == item.url }
                    Toast.makeText(requireContext(), R.string.feed_hidden_video, Toast.LENGTH_SHORT).show()
                }

                2 -> {
                    signals.blockChannel(item.uploaderUrl)
                    removeWhere { it.uploaderUrl != null && it.uploaderUrl == item.uploaderUrl }
                    Toast.makeText(requireContext(), R.string.feed_hidden_channel, Toast.LENGTH_SHORT).show()
                }

                3 -> activity?.let {
                    try {
                        InfoItemDialog.Builder(it, requireContext(), this, item).create().show()
                    } catch (e: IllegalArgumentException) {
                        InfoItemDialog.Builder.reportErrorDuringInitialization(e, item)
                    }
                }
            }
            true
        }
        popup.show()
    }

    private fun removeWhere(predicate: (StreamInfoItem) -> Boolean) {
        adapter.setRows(adapter.rows().filterNot { it is FeedRow.Short && predicate(it.item) })
    }

    override fun onItemShown(item: StreamInfoItem) {
        pendingImpressions.add(item.url)
        if (pendingImpressions.size >= 20) flushImpressions()
    }

    private fun flushImpressions() {
        if (pendingImpressions.isEmpty()) return
        val urls = pendingImpressions.toList()
        pendingImpressions.clear()
        val signals = ActivitySignals.get(requireContext())
        disposables.add(
            Completable.fromAction { signals.recordShown(urls) }
                .subscribeOn(Schedulers.io())
                .subscribe({}, { e -> Log.w(TAG, "Could not save impressions", e) })
        )
    }

    // Shorts tab only shows Shorts
    override fun onVideoClick(item: StreamInfoItem) = onShortClick(item)
    override fun onVideoMenu(item: StreamInfoItem, anchor: View) = onShortMenu(item, anchor)
    override fun onChannelClick(item: StreamInfoItem) = Unit

    companion object {
        private const val TAG = "ShortsFragment"
    }
}
