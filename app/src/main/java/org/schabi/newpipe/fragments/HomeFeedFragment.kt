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
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.schedulers.Schedulers
import org.schabi.newpipe.R
import org.schabi.newpipe.databinding.FragmentHomeFeedBinding
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.feedengine.ActivitySignals
import org.schabi.newpipe.feedengine.StreamPulse
import org.schabi.newpipe.feedengine.ui.FeedRow
import org.schabi.newpipe.feedengine.ui.HomeFeedAdapter
import org.schabi.newpipe.info_list.dialog.InfoItemDialog
import org.schabi.newpipe.local.history.HistoryRecordManager
import org.schabi.newpipe.local.shorts.ShortsPlayerActivity
import org.schabi.newpipe.util.NavigationHelper

/**
 * Stream's personalised Home ("For you"), powered by [StreamPulse]: big video cards with
 * blocks of Shorts in between, like YouTube's Home. It loads more while scrolling, records
 * what was shown, and rebuilds itself when the user comes back after watching something,
 * so the feed follows the user's latest interests within seconds.
 */
class HomeFeedFragment : Fragment(), HomeFeedAdapter.Callbacks {

    private var binding: FragmentHomeFeedBinding? = null
    private val disposables = CompositeDisposable()
    private val adapter = HomeFeedAdapter(this)

    private var session: StreamPulse.Session? = null
    private val shortsPool = ArrayDeque<StreamInfoItem>()
    private var videosSinceShorts = 0
    private var headerCount = 0
    private var loadingMore = false
    private val pendingImpressions = LinkedHashSet<String>()

    /** newest watch-history time the feed already reacted to */
    private var handledActivity = Long.MAX_VALUE
    private var historyDisposable: Disposable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        adapter.setHasStableIds(true)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = FragmentHomeFeedBinding.inflate(inflater, container, false)
        .also { binding = it }
        .root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val b = binding!!
        val layoutManager = GridLayoutManager(requireContext(), HomeFeedAdapter.SPAN)
        layoutManager.spanSizeLookup = adapter.spanSizeLookup
        b.homeFeedList.layoutManager = layoutManager
        b.homeFeedList.adapter = adapter
        b.homeFeedList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy > 0 && layoutManager.findLastVisibleItemPosition() > adapter.itemCount - LOAD_AHEAD) {
                    loadMore()
                }
            }
        })
        b.homeFeedRefresh.setOnRefreshListener { load() }

        if (adapter.itemCount == 0) load()
        watchHistory()
    }

    override fun onResume() {
        super.onResume()
        // missed some activity while away (e.g. app was in the background)? rebuild the feed
        if (session == null) return
        disposables.add(
            StreamPulse.latestActivity(requireContext())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({ latest ->
                    if (latest > handledActivity) {
                        binding?.homeFeedList?.scrollToPosition(0)
                        load()
                    }
                }, { e -> Log.w(TAG, "Could not check activity", e) })
        )
    }

    /**
     * Live adaptation: as soon as the user starts watching something (the player is a bottom
     * sheet, so this fragment stays visible), videos related to it are ranked and slotted in
     * just below what is on screen, without reloading or losing the scroll position.
     */
    private fun watchHistory() {
        historyDisposable?.dispose()
        historyDisposable = HistoryRecordManager(requireContext())
            .streamHistorySortedById
            .map { entries -> entries.maxByOrNull { it.accessDate }?.let { listOf(it) } ?: emptyList() }
            .filter { it.isNotEmpty() }
            .map { it.first() }
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ entry ->
                val millis = entry.accessDate.toInstant().toEpochMilli()
                if (session != null && millis > handledActivity) {
                    handledActivity = millis
                    injectRelated(entry.streamEntity.serviceId, entry.streamEntity.url, entry.streamEntity.title)
                }
            }, { e -> Log.w(TAG, "History watcher failed", e) })
    }

    private fun injectRelated(serviceId: Int, url: String, title: String) {
        val current = session ?: return
        disposables.add(
            StreamPulse.becauseYouWatched(requireContext(), current, serviceId, url, title)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({ related ->
                    val list = binding?.homeFeedList ?: return@subscribe
                    if (session !== current || related.isEmpty()) return@subscribe
                    val lm = list.layoutManager as GridLayoutManager
                    val at = lm.findLastVisibleItemPosition() + 2
                    adapter.insertRows(at, related.map { FeedRow.Video(it) })
                }, { e -> Log.w(TAG, "Could not adapt feed", e) })
        )
    }

    override fun onPause() {
        flushImpressions()
        super.onPause()
    }

    override fun onDestroyView() {
        historyDisposable?.dispose()
        historyDisposable = null
        binding = null
        super.onDestroyView()
    }

    override fun onDestroy() {
        disposables.dispose()
        super.onDestroy()
    }

    // -------------------------------------------------------------------------------------
    // Loading
    // -------------------------------------------------------------------------------------

    private fun load() {
        val b = binding ?: return
        b.homeFeedMessage.isVisible = false
        b.homeFeedProgress.isVisible = adapter.itemCount == 0 && !b.homeFeedRefresh.isRefreshing
        loadingMore = true

        disposables.add(
            StreamPulse.home(requireContext())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({ result ->
                    session = result.session
                    handledActivity = result.session.latestActivityMillis
                    shortsPool.clear()
                    shortsPool.addAll(result.shorts)
                    videosSinceShorts = 0
                    headerCount = 0
                    adapter.setRows(firstRows(result.videos))
                    loadingMore = false
                    binding?.let {
                        it.homeFeedProgress.isVisible = false
                        it.homeFeedRefresh.isRefreshing = false
                        it.homeFeedMessage.isVisible = adapter.itemCount == 0
                        it.homeFeedMessage.setText(R.string.home_feed_empty)
                    }
                }, { e ->
                    Log.e(TAG, "Could not build Home feed", e)
                    loadingMore = false
                    binding?.let {
                        it.homeFeedProgress.isVisible = false
                        it.homeFeedRefresh.isRefreshing = false
                        it.homeFeedMessage.isVisible = adapter.itemCount == 0
                        it.homeFeedMessage.setText(R.string.home_feed_error)
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
                .subscribe({ videos ->
                    if (session === current && videos.isNotEmpty()) {
                        adapter.appendRows(moreRows(videos))
                    }
                    loadingMore = false
                }, { e ->
                    Log.w(TAG, "Could not load more", e)
                    loadingMore = false
                })
        )
    }

    /** First video, then a Shorts block, then the rest with a Shorts block every few videos. */
    private fun firstRows(videos: List<StreamInfoItem>): List<FeedRow> {
        val rows = ArrayList<FeedRow>()
        videos.firstOrNull()?.let { rows.add(FeedRow.Video(it)) }
        addShortsBlock(rows)
        rows.addAll(moreRows(videos.drop(1)))
        return rows
    }

    private fun moreRows(videos: List<StreamInfoItem>): List<FeedRow> {
        val rows = ArrayList<FeedRow>()
        for (video in videos) {
            rows.add(FeedRow.Video(video))
            if (++videosSinceShorts >= VIDEOS_BETWEEN_SHORTS) {
                addShortsBlock(rows)
            }
        }
        return rows
    }

    private fun addShortsBlock(rows: MutableList<FeedRow>) {
        if (shortsPool.size < 2) return
        rows.add(FeedRow.ShortsHeader(headerCount++))
        repeat(minOf(SHORTS_PER_BLOCK, shortsPool.size / 2 * 2)) {
            rows.add(FeedRow.Short(shortsPool.removeFirst()))
        }
        videosSinceShorts = 0
    }

    // -------------------------------------------------------------------------------------
    // Callbacks
    // -------------------------------------------------------------------------------------

    override fun onVideoClick(item: StreamInfoItem) {
        NavigationHelper.openVideoDetailFragment(
            requireContext(),
            requireActivity().supportFragmentManager,
            item.serviceId,
            item.url,
            item.name,
            null,
            false
        )
    }

    override fun onChannelClick(item: StreamInfoItem) {
        val url = item.uploaderUrl ?: return
        NavigationHelper.openChannelFragment(
            requireActivity().supportFragmentManager,
            item.serviceId,
            url,
            item.uploaderName ?: ""
        )
    }

    override fun onShortClick(item: StreamInfoItem) {
        val shorts = adapter.rows().filterIsInstance<FeedRow.Short>().map { it.item }
        val index = shorts.indexOfFirst { it.url == item.url }.coerceAtLeast(0)
        val activity = activity as? AppCompatActivity ?: return
        ShortsPlayerActivity.start(activity, shorts, index)
    }

    override fun onVideoMenu(item: StreamInfoItem, anchor: View) = showMenu(item, anchor)

    override fun onShortMenu(item: StreamInfoItem, anchor: View) = showMenu(item, anchor)

    override fun onItemShown(item: StreamInfoItem) {
        pendingImpressions.add(item.url)
        if (pendingImpressions.size >= IMPRESSION_BATCH) flushImpressions()
    }

    private fun showMenu(item: StreamInfoItem, anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menu.add(0, MENU_NOT_INTERESTED, 0, R.string.feed_not_interested)
        popup.menu.add(0, MENU_BLOCK_CHANNEL, 1, R.string.feed_dont_recommend_channel)
        popup.menu.add(0, MENU_MORE, 2, R.string.more_options)
        popup.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                MENU_NOT_INTERESTED -> {
                    ActivitySignals.get(requireContext()).markNotInterested(item)
                    removeFromFeed { it.url == item.url }
                    Toast.makeText(requireContext(), R.string.feed_hidden_video, Toast.LENGTH_SHORT).show()
                }

                MENU_BLOCK_CHANNEL -> {
                    ActivitySignals.get(requireContext()).blockChannel(item.uploaderUrl)
                    removeFromFeed { it.uploaderUrl != null && it.uploaderUrl == item.uploaderUrl }
                    Toast.makeText(requireContext(), R.string.feed_hidden_channel, Toast.LENGTH_SHORT).show()
                }

                MENU_MORE -> showInfoDialog(item)
            }
            true
        }
        popup.show()
    }

    private fun removeFromFeed(predicate: (StreamInfoItem) -> Boolean) {
        val kept = adapter.rows().filterNot {
            when (it) {
                is FeedRow.Video -> predicate(it.item)
                is FeedRow.Short -> predicate(it.item)
                is FeedRow.ShortsHeader -> false
            }
        }
        adapter.setRows(kept)
    }

    private fun showInfoDialog(item: StreamInfoItem) {
        val activity = activity ?: return
        try {
            InfoItemDialog.Builder(activity, requireContext(), this, item).create().show()
        } catch (e: IllegalArgumentException) {
            InfoItemDialog.Builder.reportErrorDuringInitialization(e, item)
        }
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

    companion object {
        private const val TAG = "HomeFeedFragment"
        private const val LOAD_AHEAD = 6
        private const val VIDEOS_BETWEEN_SHORTS = 8
        private const val SHORTS_PER_BLOCK = 4
        private const val IMPRESSION_BATCH = 20
        private const val MENU_NOT_INTERESTED = 1
        private const val MENU_BLOCK_CHANNEL = 2
        private const val MENU_MORE = 3
    }
}
