/*
 * SPDX-FileCopyrightText: 2026 Abhiraj Aryan
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.fragments

import android.content.res.ColorStateList
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.RecyclerView
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.schedulers.Schedulers
import java.util.function.Supplier
import org.schabi.newpipe.R
import org.schabi.newpipe.error.ErrorInfo
import org.schabi.newpipe.error.UserAction
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.fragments.list.BaseListFragment
import org.schabi.newpipe.local.shorts.ShortsAdapter
import org.schabi.newpipe.local.shorts.ShortsDiscovery
import org.schabi.newpipe.player.playqueue.SinglePlayQueue
import org.schabi.newpipe.util.ExtractorHelper
import org.schabi.newpipe.util.NavigationHelper

/**
 * Stream's Home "All" feed, built like YouTube's Home: a Shorts shelf on top, then big video
 * cards mixing YouTube's Music, Gaming, Podcasts, Movies and Live lists one after another,
 * with more items loaded while scrolling.
 */
class HomeFeedFragment : BaseListFragment<HomeFeedFragment.Feed, List<StreamInfoItem>>() {

    data class Feed(val videos: List<StreamInfoItem>, val shorts: List<StreamInfoItem>)

    /** One YouTube list ("kiosk") feeding the Home mix and where its next page is. */
    private data class Source(val kioskId: String, val url: String, var nextPage: Page?)

    private val disposables = CompositeDisposable()
    private val sources = mutableListOf<Source>()
    private val seenUrls = HashSet<String>()

    private var shelfView: View? = null
    private var shelfShorts: List<StreamInfoItem> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // the feed is rebuilt from the (cached) network lists instead of saved items
        setUseDefaultStateSaving(false)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_kiosk, container, false)

    override fun onDestroy() {
        disposables.dispose()
        super.onDestroy()
    }

    // ---------------------------------------------------------------------------------------
    // Shorts shelf (list header)
    // ---------------------------------------------------------------------------------------

    override fun getListHeaderSupplier(): Supplier<View> = Supplier { shelfView ?: createShelf() }

    private fun createShelf(): View {
        val view = layoutInflater.inflate(R.layout.home_shorts_shelf, itemsList, false)
        val list = view.findViewById<RecyclerView>(R.id.shelf_list)
        val width = resources.getDimensionPixelSize(R.dimen.home_shelf_card_width)
        val adapter = ShortsAdapter(
            fixedWidthPx = width,
            onClick = ::playShorts,
            onLongClick = ::showInfoItemDialog
        )
        list.adapter = adapter
        adapter.submit(shelfShorts)
        ImageViewCompat.setImageTintList(
            view.findViewById<ImageView>(R.id.shelf_icon),
            ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.stream_rose))
        )
        view.isVisible = shelfShorts.isNotEmpty()
        view.tag = adapter
        shelfView = view
        return view
    }

    private fun updateShelf(shorts: List<StreamInfoItem>) {
        shelfShorts = shorts
        val view = shelfView ?: return
        (view.tag as? ShortsAdapter)?.submit(shorts)
        view.isVisible = shorts.isNotEmpty()
    }

    private fun playShorts(items: List<StreamInfoItem>, position: Int) {
        if (position !in items.indices) return
        val activity = activity as? AppCompatActivity ?: return
        NavigationHelper.playOnMainPlayer(activity, SinglePlayQueue(items, position))
    }

    // ---------------------------------------------------------------------------------------
    // Loading
    // ---------------------------------------------------------------------------------------

    override fun doInitialLoadLogic() {
        startLoading(false)
    }

    override fun startLoading(forceLoad: Boolean) {
        super.startLoading(forceLoad)
        disposables.clear()
        val serviceId = ServiceList.YouTube.serviceId

        val lists = KIOSKS.map { kioskId ->
            Single.fromCallable {
                ServiceList.YouTube.kioskList.getListLinkHandlerFactoryByType(kioskId)
                    .fromId(kioskId).url
            }
                .flatMap { url ->
                    ExtractorHelper.getKioskInfo(serviceId, url, forceLoad).map { info ->
                        Source(kioskId, url, info.nextPage) to
                            info.relatedItems.filterIsInstance<StreamInfoItem>()
                    }
                }
                .subscribeOn(Schedulers.io())
                .onErrorReturn { e ->
                    Log.w(LOG_TAG, "Home list $kioskId unavailable", e)
                    Source(kioskId, "", null) to emptyList()
                }
        }
        val shorts = ShortsDiscovery.load(requireContext(), forceLoad)
            .onErrorReturn { emptyList() }

        disposables.add(
            Single.zip(Single.zip(lists) { it.toList() }, shorts) { results, shortsList ->
                @Suppress("UNCHECKED_CAST")
                val pairs = results as List<Pair<Source, List<StreamInfoItem>>>
                synchronized(sources) {
                    sources.clear()
                    sources.addAll(pairs.map { it.first }.filter { it.url.isNotEmpty() })
                }
                seenUrls.clear()
                Feed(mixVideos(pairs.map { it.second }), shortsList.take(SHELF_SIZE))
            }
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(::handleResult) { e ->
                    showError(ErrorInfo(e, UserAction.REQUESTED_KIOSK, "Home feed"))
                }
        )
    }

    override fun handleResult(result: Feed) {
        super.handleResult(result)
        isLoading.set(false)
        updateShelf(result.shorts)
        infoListAdapter.clearStreamItemList()
        infoListAdapter.addInfoItemList(result.videos)
        if (result.videos.isEmpty() && result.shorts.isEmpty()) {
            showEmptyState()
        }
    }

    override fun hasMoreItems(): Boolean = synchronized(sources) { sources.any { it.nextPage != null } }

    override fun loadMoreItems() {
        isLoading.set(true)
        showListFooter(true)
        val serviceId = ServiceList.YouTube.serviceId
        val pending = synchronized(sources) { sources.filter { it.nextPage != null } }
        val pages = pending.map { source ->
            ExtractorHelper.getMoreKioskItems(serviceId, source.url, source.nextPage)
                .map { page ->
                    synchronized(sources) { source.nextPage = page.nextPage }
                    page.items
                }
                .subscribeOn(Schedulers.io())
                .onErrorReturn { e ->
                    Log.w(LOG_TAG, "More of ${source.kioskId} unavailable", e)
                    synchronized(sources) { source.nextPage = null }
                    emptyList()
                }
        }
        if (pages.isEmpty()) {
            handleNextItems(emptyList())
            return
        }
        disposables.add(
            Single.zip(pages) { results ->
                @Suppress("UNCHECKED_CAST")
                mixVideos(results.map { it as List<StreamInfoItem> })
            }
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(::handleNextItems) { e ->
                    Log.w(LOG_TAG, "Could not load more Home items", e)
                    handleNextItems(emptyList())
                }
        )
    }

    override fun handleNextItems(result: List<StreamInfoItem>) {
        super.handleNextItems(result)
        showListFooter(false)
        infoListAdapter.addInfoItemList(result)
    }

    /** One video from each list in turn, without Shorts and without repeats. */
    private fun mixVideos(lists: List<List<StreamInfoItem>>): List<StreamInfoItem> {
        val result = ArrayList<StreamInfoItem>()
        val longest = lists.maxOfOrNull { it.size } ?: 0
        for (i in 0 until longest) {
            for (list in lists) {
                val item = list.getOrNull(i) ?: continue
                if (!item.isShortFormContent && seenUrls.add(item.url)) {
                    result.add(item)
                }
            }
        }
        return result
    }

    companion object {
        private const val LOG_TAG = "HomeFeedFragment"
        private const val SHELF_SIZE = 20

        /** YouTube lists mixed into the Home feed, in this order. */
        private val KIOSKS = listOf(
            "trending_music",
            "trending_gaming",
            "trending_podcasts_episodes",
            "trending_movies_and_shows",
            "live"
        )
    }
}
