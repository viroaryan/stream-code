/*
 * SPDX-FileCopyrightText: 2026 Abhiraj Aryan
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.feedengine

import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.random.Random
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/** Where a recommendation came from; better sources start with a higher score. */
enum class Source(val weight: Double) {
    /** YouTube's own "related videos" of something the user watched */
    RELATED(1.0),

    /** a channel the user subscribed to */
    SUBSCRIPTION(0.85),

    /** a YouTube search for the user's topics or searches */
    SEARCH(0.75),

    /** broad topics to discover something new */
    EXPLORE(0.5),

    /** YouTube's trending lists */
    TRENDING(0.45),

    /** Shorts of popular channels */
    POPULAR(0.4)
}

/** One recommendation candidate, merged across the sources that suggested it. */
class Candidate(val item: StreamInfoItem) {
    val sources: MutableSet<Source> = mutableSetOf()

    /** for [Source.RELATED]: how strong the watched video that led here was (0..1) */
    var seedStrength = 0.0
}

/**
 * Stream Pulse ranking: scores every candidate against the [InterestProfile] and the
 * [ActivitySignals], then orders them so the same channel or topic doesn't repeat back to back.
 */
object Ranker {

    /**
     * @param shorts rank Shorts (true) or regular videos (false); the other kind is dropped
     * @param random different per session so every visit gets a slightly different order
     */
    fun rank(
        candidates: Collection<Candidate>,
        profile: InterestProfile,
        signals: ActivitySignals,
        random: Random,
        shorts: Boolean,
        exclude: Set<String> = emptySet()
    ): List<StreamInfoItem> {
        val nowMillis = System.currentTimeMillis()
        val now = OffsetDateTime.now()

        val scored = candidates.mapNotNull { c ->
            val item = c.item
            if (item.url in exclude || item.isShortFormContent != shorts || signals.isHidden(item)) {
                return@mapNotNull null
            }
            val watched = profile.watched[item.url]
            if (watched != null && watched > 0.85) return@mapNotNull null // already seen fully

            val words = Tokenizer.tokens(item.name)
            val topic = profile.topicScore(words)
            val channel = profile.channelScore(item.uploaderUrl)

            val bestSource = c.sources.maxOf { it.weight }
            val source = bestSource + 0.25 * (c.sources.size - 1) + 0.3 * c.seedStrength

            val fresh = item.uploadDate?.offsetDateTime()?.let {
                val days = ChronoUnit.HOURS.between(it, now) / 24.0
                exp(-days.coerceAtLeast(0.0) / 10.0)
            } ?: 0.3
            val popularity = if (item.viewCount > 0) {
                (log10(item.viewCount + 10.0) / 7.0).coerceAtMost(1.3)
            } else {
                0.2
            }

            val partlyWatched = if (watched != null) 0.2 + 0.8 * watched else 0.0
            val impression = signals.impressionOf(item.url)?.let { imp ->
                val hours = (nowMillis - imp.lastShownMillis) / ActivitySignals.HOUR.toDouble()
                imp.count * 0.45 * 0.5.pow(hours / 18.0)
            } ?: 0.0

            val score = 1.1 * source + 1.6 * topic + 1.0 * channel + 0.45 * fresh +
                0.35 * popularity - partlyWatched - impression + random.nextDouble() * 0.35
            item to score
        }

        return diversify(scored.sortedByDescending { it.second }.map { it.first })
    }

    /**
     * Keeps the score order but avoids the same channel within [CHANNEL_GAP] items and more than
     * [MAX_PER_CHANNEL] items of a channel in each block of [BLOCK]; skipped items come later.
     */
    private fun diversify(sorted: List<StreamInfoItem>): List<StreamInfoItem> {
        val pending = ArrayDeque(sorted)
        val result = ArrayList<StreamInfoItem>(sorted.size)
        val perBlock = HashMap<String, Int>()

        while (pending.isNotEmpty()) {
            if (result.size % BLOCK == 0) perBlock.clear()
            val recent = result.takeLast(CHANNEL_GAP).map { channelKey(it) }.toSet()
            val index = pending.indexOfFirst {
                val key = channelKey(it)
                key !in recent && (perBlock[key] ?: 0) < MAX_PER_CHANNEL
            }
            val next = if (index >= 0) pending.removeAt(index) else pending.removeFirst()
            val key = channelKey(next)
            perBlock[key] = (perBlock[key] ?: 0) + 1
            result.add(next)
        }
        return result
    }

    private fun channelKey(item: StreamInfoItem) = item.uploaderUrl ?: item.uploaderName ?: ""

    private const val CHANNEL_GAP = 3
    private const val MAX_PER_CHANNEL = 2
    private const val BLOCK = 12
}
