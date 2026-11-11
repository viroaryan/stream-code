/*
 * SPDX-FileCopyrightText: 2026 Abhiraj Aryan
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.feedengine

import android.content.Context
import android.util.Log
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import kotlin.random.Random
import org.schabi.newpipe.database.feed.dao.FeedDAO
import org.schabi.newpipe.database.feed.model.FeedGroupEntity
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.local.feed.FeedDatabaseManager
import org.schabi.newpipe.local.shorts.ShortsDiscovery
import org.schabi.newpipe.util.ExtractorHelper

/**
 * **Stream Pulse**, Stream's on-device recommendation engine. No account, no server, no AI model.
 *
 * 1. [InterestProfile] learns topics and channels from watch history, searches, subscriptions
 *    and "Not interested" feedback, with a strong boost for the current session.
 * 2. Candidates come from all of YouTube: related videos of what was just watched (YouTube's
 *    own recommendations), searches for the user's topics, subscriptions, trending lists,
 *    exploration topics and popular Shorts.
 * 3. [Ranker] scores them, sinks videos already shown, and spreads channels apart.
 *
 * Every call uses a new random seed and the latest activity, so each visit gets a fresh feed
 * that follows what the user did a minute ago.
 */
object StreamPulse {
    private const val TAG = "StreamPulse"
    private val YOUTUBE = ServiceList.YouTube.serviceId

    private const val RELATED_SEEDS = 6
    private const val PARALLEL = 5
    private const val SEARCH_FILTER = "all"

    /** Broad topics used to discover new things, and to fill the feed of a brand-new user. */
    private val EXPLORE_TOPICS = listOf(
        "music", "comedy", "cricket", "football", "technology", "science", "news", "gaming",
        "movie trailer", "cooking", "fitness", "travel", "podcast", "motivation", "cars",
        "anime", "history", "space", "standup comedy", "bollywood songs", "education",
        "fashion", "nature", "animals", "dance", "art", "unboxing", "photography"
    )

    private val TRENDING_KIOSKS = listOf(
        "trending_music",
        "trending_gaming",
        "trending_podcasts_episodes",
        "trending_movies_and_shows"
    )

    /** A source that can give another page of results while the user scrolls. */
    internal class PagedSource(val source: Source, val query: String?, val kioskUrl: String?) {
        var page: Page? = null
    }

    /** State kept between the first Home load and the following "load more" calls. */
    class Session internal constructor(
        internal val profile: InterestProfile,
        internal val random: Random,
        internal val shown: MutableSet<String>,
        internal val paged: MutableList<PagedSource>,
        internal val extraSeeds: ArrayDeque<InterestProfile.Seed>,
        internal val shorts: Boolean
    ) {
        val latestActivityMillis: Long get() = profile.latestActivityMillis
        fun hasMore(): Boolean = paged.any { it.page != null } || extraSeeds.isNotEmpty()
    }

    class Result(val videos: List<StreamInfoItem>, val shorts: List<StreamInfoItem>, val session: Session)

    // -----------------------------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------------------------

    /** Home feed: ranked videos plus a ranked Shorts strip. */
    fun home(context: Context): Single<Result> = build(context, shortsOnly = false)

    /** Shorts feed: ranked Shorts from the whole of YouTube. */
    fun shorts(context: Context): Single<Result> = build(context, shortsOnly = true)

    /** Next batch while scrolling; empty when nothing is left. */
    fun more(context: Context, session: Session): Single<List<StreamInfoItem>> {
        val signals = ActivitySignals.get(context)
        val sources = ArrayList<Single<List<Candidate>>>()

        session.paged.filter { it.page != null }.shuffled(session.random).take(3).forEach { paged ->
            sources.add(nextPage(paged))
        }
        repeat(2) {
            session.extraSeeds.removeFirstOrNull()?.let { sources.add(related(context, it, 0.7)) }
        }
        if (sources.isEmpty()) return Single.just(emptyList())

        return gather(sources).map { candidates ->
            val ranked = Ranker.rank(
                candidates,
                session.profile,
                signals,
                session.random,
                shorts = session.shorts,
                exclude = session.shown
            )
            session.shown.addAll(ranked.map { it.url })
            ranked
        }
    }

    /**
     * Live adaptation: right after the user starts watching [url], returns videos related to it,
     * ranked for this session and not shown yet, so they can be slotted into the upcoming part
     * of the feed within seconds.
     */
    fun becauseYouWatched(
        context: Context,
        session: Session,
        serviceId: Int,
        url: String,
        title: String
    ): Single<List<StreamInfoItem>> {
        val signals = ActivitySignals.get(context)
        val seed = InterestProfile.Seed(serviceId, url, title, 1.0)
        return related(context, seed, 1.0).map { candidates ->
            val ranked = Ranker.rank(
                candidates,
                session.profile,
                signals,
                session.random,
                shorts = session.shorts,
                exclude = session.shown + url
            ).take(LIVE_INJECT)
            session.shown.addAll(ranked.map { it.url })
            ranked
        }
    }

    private const val LIVE_INJECT = 6

    /** Epoch millis of the newest watch-history entry, to notice new activity cheaply. */
    fun latestActivity(context: Context): Single<Long> = Single.fromCallable {
        org.schabi.newpipe.local.history.HistoryRecordManager(context)
            .streamStatistics.blockingFirst(emptyList())
            .maxOfOrNull { it.latestAccessDate.toInstant().toEpochMilli() } ?: 0L
    }.subscribeOn(Schedulers.io()).onErrorReturnItem(0L)

    // -----------------------------------------------------------------------------------------
    // Building
    // -----------------------------------------------------------------------------------------

    private fun build(context: Context, shortsOnly: Boolean): Single<Result> {
        val appContext = context.applicationContext
        val signals = ActivitySignals.get(appContext)
        val random = Random(System.nanoTime())

        return Single.fromCallable { InterestProfile.build(appContext, signals) }
            .subscribeOn(Schedulers.io())
            .flatMap { profile ->
                val paged = ArrayList<PagedSource>()
                val sources = ArrayList<Single<List<Candidate>>>()

                // related videos of what the user watched: YouTube's own recommendations
                val maxSeed = profile.seeds.maxOfOrNull { it.weight } ?: 1.0
                profile.seeds.take(RELATED_SEEDS).forEach { seed ->
                    sources.add(related(appContext, seed, seed.weight / maxSeed))
                }

                // searches for the user's topics, recent searches and some exploration
                queriesFor(profile, random, shortsOnly).forEach { (query, source) ->
                    val p = PagedSource(source, query, null)
                    paged.add(p)
                    sources.add(firstSearchPage(p))
                }

                // subscriptions (stored by the feed updater)
                sources.add(subscriptions(appContext, shortsOnly))

                if (shortsOnly) {
                    sources.add(popularShorts(appContext))
                } else {
                    TRENDING_KIOSKS.forEach { kioskId ->
                        sources.add(firstKioskPage(kioskId, paged))
                    }
                    // a strip of Shorts on Home as well
                    sources.add(popularShorts(appContext))
                }

                gather(sources).map { candidates ->
                    val session = Session(
                        profile,
                        random,
                        HashSet(),
                        paged,
                        ArrayDeque(profile.seeds.drop(RELATED_SEEDS)),
                        shortsOnly
                    )
                    val shorts = Ranker.rank(candidates, profile, signals, random, shorts = true)
                    val videos = if (shortsOnly) {
                        emptyList()
                    } else {
                        Ranker.rank(candidates, profile, signals, random, shorts = false)
                    }
                    session.shown.addAll(videos.map { it.url })
                    session.shown.addAll(shorts.map { it.url })
                    Result(videos, shorts, session)
                }
            }
    }

    private fun queriesFor(
        profile: InterestProfile,
        random: Random,
        shortsOnly: Boolean
    ): List<Pair<String, Source>> {
        val queries = LinkedHashMap<String, Source>()
        profile.searches.take(2).forEach { queries[it] = Source.SEARCH }

        val topics = profile.topTopics(8)
        if (topics.size >= 2) {
            queries["${topics[0]} ${topics[1]}"] = Source.SEARCH
        }
        if (topics.size >= 4) {
            queries["${topics[2]} ${topics[3]}"] = Source.SEARCH
        }
        if (topics.size >= 6) {
            val a = topics[random.nextInt(4, topics.size)]
            queries["${topics[0]} $a"] = Source.SEARCH
        }

        val exploreCount = if (profile.isColdStart) 4 else 1
        EXPLORE_TOPICS.shuffled(random).take(exploreCount).forEach { queries[it] = Source.EXPLORE }

        return queries.entries.map { (q, s) -> (if (shortsOnly) "$q #shorts" else q) to s }
    }

    // -----------------------------------------------------------------------------------------
    // Sources
    // -----------------------------------------------------------------------------------------

    private fun related(context: Context, seed: InterestProfile.Seed, strength: Double): Single<List<Candidate>> = ExtractorHelper.getStreamInfo(context, seed.serviceId, seed.url, false)
        .map { info ->
            info.relatedItems.filterIsInstance<StreamInfoItem>().map { item ->
                Candidate(item).apply {
                    sources.add(Source.RELATED)
                    seedStrength = strength
                }
            }
        }
        .safe("related of ${seed.url}")

    private fun firstSearchPage(paged: PagedSource): Single<List<Candidate>> = ExtractorHelper.searchFor(YOUTUBE, paged.query!!, listOf(SEARCH_FILTER), "")
        .map { info ->
            paged.page = info.nextPage
            info.relatedItems.filterIsInstance<StreamInfoItem>().map { it.asCandidate(paged.source) }
        }
        .safe("search ${paged.query}")

    private fun firstKioskPage(kioskId: String, paged: MutableList<PagedSource>): Single<List<Candidate>> = Single.fromCallable {
        ServiceList.YouTube.kioskList.getListLinkHandlerFactoryByType(kioskId).fromId(kioskId).url
    }
        .flatMap { url ->
            ExtractorHelper.getKioskInfo(YOUTUBE, url, false).map { info ->
                val p = PagedSource(Source.TRENDING, null, url).apply { page = info.nextPage }
                synchronized(paged) { paged.add(p) }
                info.relatedItems.filterIsInstance<StreamInfoItem>().map { it.asCandidate(Source.TRENDING) }
            }
        }
        .safe("kiosk $kioskId")

    private fun nextPage(paged: PagedSource): Single<List<Candidate>> {
        val page = paged.page ?: return Single.just(emptyList())
        val single: Single<List<StreamInfoItem>> = if (paged.kioskUrl != null) {
            ExtractorHelper.getMoreKioskItems(YOUTUBE, paged.kioskUrl, page).map { result ->
                paged.page = result.nextPage
                result.items
            }
        } else {
            ExtractorHelper.getMoreSearchItems(YOUTUBE, paged.query, listOf(SEARCH_FILTER), "", page)
                .map { result ->
                    paged.page = result.nextPage
                    result.items.filterIsInstance<StreamInfoItem>()
                }
        }
        return single
            .map { items -> items.map { it.asCandidate(paged.source) } }
            .doOnError { paged.page = null }
            .safe("next page ${paged.query ?: paged.kioskUrl}")
    }

    private fun subscriptions(context: Context, shortsOnly: Boolean): Single<List<Candidate>> = Single.fromCallable {
        FeedDatabaseManager(context)
            .getStreams(
                FeedGroupEntity.GROUP_ALL_ID,
                includePlayedStreams = true,
                includePartiallyPlayedStreams = true,
                includeFutureStreams = false,
                shortsFilter = if (shortsOnly) FeedDAO.SHORTS_FILTER_ONLY else FeedDAO.SHORTS_FILTER_ALL
            )
            .blockingGet(emptyList())
            .take(120)
            .map { it.stream.toStreamInfoItem().asCandidate(Source.SUBSCRIPTION) }
    }.safe("subscriptions")

    private fun popularShorts(context: Context): Single<List<Candidate>> = ShortsDiscovery.load(context, false)
        .map { list -> list.map { it.asCandidate(Source.POPULAR) } }
        .safe("popular shorts")

    // -----------------------------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------------------------

    /** Runs the sources in parallel and merges candidates that more than one source suggested. */
    private fun gather(sources: List<Single<List<Candidate>>>): Single<Collection<Candidate>> = Flowable.fromIterable(sources)
        .flatMapSingle({ it }, false, PARALLEL)
        .toList()
        .map { lists ->
            val merged = LinkedHashMap<String, Candidate>()
            lists.flatten().forEach { c ->
                val existing = merged[c.item.url]
                if (existing == null) {
                    merged[c.item.url] = c
                } else {
                    existing.sources.addAll(c.sources)
                    existing.seedStrength = maxOf(existing.seedStrength, c.seedStrength)
                }
            }
            merged.values
        }

    private fun StreamInfoItem.asCandidate(source: Source) = Candidate(this).apply { sources.add(source) }

    private fun Single<List<Candidate>>.safe(what: String): Single<List<Candidate>> = subscribeOn(Schedulers.io()).onErrorReturn { e ->
        Log.w(TAG, "Source failed: $what", e)
        emptyList()
    }
}
