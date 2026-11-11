/*
 * SPDX-FileCopyrightText: 2026 Abhiraj Aryan
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.local.shorts

import android.content.Context
import android.util.Log
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import java.util.concurrent.TimeUnit
import org.schabi.newpipe.database.feed.dao.FeedDAO
import org.schabi.newpipe.database.feed.model.FeedGroupEntity
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.local.feed.FeedDatabaseManager
import org.schabi.newpipe.util.ExtractorHelper

/**
 * Stream: where Shorts come from. YouTube personalises Shorts with the viewer's account; Stream
 * has none, so it mixes
 *  - the Shorts of the user's subscriptions (stored by the feed, see FeedDatabaseManager), and
 *  - the Shorts tabs of a few popular channels, so the section is never empty.
 *
 * Channels are interleaved round-robin so consecutive Shorts come from different creators,
 * and the network part is cached for an hour.
 */
object ShortsDiscovery {
    private const val TAG = "ShortsDiscovery"
    private val CACHE_MILLIS = TimeUnit.HOURS.toMillis(1)

    /** Popular channels whose Shorts tabs work without an account (checked 2026-11-11). */
    private val DISCOVERY_CHANNELS = listOf(
        "MrBeast",
        "CarryMinati",
        "BBKiVines",
        "TechnicalGuruji",
        "dhruvrathee",
        "tseries",
        "KhabyLame",
        "zachking",
        "FactTechz"
    )

    @Volatile
    private var cachedDiscovery: List<StreamInfoItem>? = null

    @Volatile
    private var cachedAt = 0L

    /**
     * @param forceRefresh ignore the cached popular-channel Shorts
     * @return Shorts to show, newest subscription Shorts mixed with popular ones, no duplicates
     */
    fun load(context: Context, forceRefresh: Boolean = false): Single<List<StreamInfoItem>> {
        val appContext = context.applicationContext
        val subscriptions = Single.fromCallable { subscriptionShorts(appContext) }
            .subscribeOn(Schedulers.io())
            .onErrorReturn { e ->
                Log.w(TAG, "Could not read subscription shorts", e)
                emptyList()
            }

        return Single.zip(subscriptions, discoveryShorts(forceRefresh)) { subs, popular ->
            interleave(listOf(subs) + popular.chunkedByUploader())
                .distinctBy { it.url }
        }
    }

    private fun subscriptionShorts(context: Context): List<StreamInfoItem> = FeedDatabaseManager(context)
        .getStreams(
            FeedGroupEntity.GROUP_ALL_ID,
            includePlayedStreams = true,
            includePartiallyPlayedStreams = true,
            includeFutureStreams = false,
            shortsFilter = FeedDAO.SHORTS_FILTER_ONLY
        )
        .blockingGet(emptyList())
        .map { it.stream.toStreamInfoItem() }

    private fun discoveryShorts(forceRefresh: Boolean): Single<List<StreamInfoItem>> {
        val cached = cachedDiscovery
        if (!forceRefresh && cached != null &&
            System.currentTimeMillis() - cachedAt < CACHE_MILLIS
        ) {
            return Single.just(cached)
        }

        val serviceId = ServiceList.YouTube.serviceId
        return Flowable.fromIterable(DISCOVERY_CHANNELS)
            .flatMapSingle(
                { handle ->
                    ExtractorHelper.getChannelInfo(serviceId, "https://www.youtube.com/@$handle", forceRefresh)
                        .flatMap { channel ->
                            val tab = channel.tabs.firstOrNull { it.contentFilters.contains(ChannelTabs.SHORTS) }
                                ?: return@flatMap Single.just(emptyList<StreamInfoItem>())
                            ExtractorHelper.getChannelTab(serviceId, tab, forceRefresh)
                                .map { info ->
                                    info.relatedItems.filterIsInstance<StreamInfoItem>()
                                        .filter { it.isShortFormContent }
                                }
                        }
                        .subscribeOn(Schedulers.io())
                        .onErrorReturn { e ->
                            Log.w(TAG, "Shorts of @$handle unavailable", e)
                            emptyList()
                        }
                },
                false,
                MAX_PARALLEL_REQUESTS
            )
            .toList()
            .map { perChannel -> interleave(perChannel) }
            .doOnSuccess {
                if (it.isNotEmpty()) {
                    cachedDiscovery = it
                    cachedAt = System.currentTimeMillis()
                }
            }
    }

    /** Takes one item from each list in turn: a1, b1, c1, a2, b2, c2, ... */
    private fun interleave(lists: List<List<StreamInfoItem>>): List<StreamInfoItem> {
        val result = ArrayList<StreamInfoItem>(lists.sumOf { it.size })
        val longest = lists.maxOfOrNull { it.size } ?: 0
        for (i in 0 until longest) {
            lists.forEach { list -> list.getOrNull(i)?.let(result::add) }
        }
        return result
    }

    /** Re-splits an interleaved list by uploader so it can be interleaved again with others. */
    private fun List<StreamInfoItem>.chunkedByUploader(): List<List<StreamInfoItem>> = groupBy { it.uploaderUrl ?: it.uploaderName }.values.toList()

    private const val MAX_PARALLEL_REQUESTS = 4
}
