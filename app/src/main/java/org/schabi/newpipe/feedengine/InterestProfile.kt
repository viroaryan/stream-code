/*
 * SPDX-FileCopyrightText: 2026 Abhiraj Aryan
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.feedengine

import android.content.Context
import android.util.Log
import java.time.OffsetDateTime
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.local.history.HistoryRecordManager

/**
 * Stream Pulse: what the user likes, learned only from what happened on this phone.
 *
 * Every watched video adds weight to the words of its title and to its channel. The weight
 * grows with how much of the video was watched and how often, decays with time (half-life
 * [HALF_LIFE_HOURS]) and is doubled for the current session ([SESSION_HOURS]) so the feed
 * follows what the user is into *right now*. Videos left within seconds count against their
 * topic and channel. Searches and subscriptions add interest, "Not interested" removes it.
 */
class InterestProfile(
    /** topic word -> interest in [-1, 1] */
    val topics: Map<String, Double>,
    /** channel url -> interest in [-1, 1] */
    val channels: Map<String, Double>,
    /** videos whose related videos should feed the recommendations, best first */
    val seeds: List<Seed>,
    /** url -> how much of it was watched (0..1) */
    val watched: Map<String, Double>,
    /** recent searches, newest first */
    val searches: List<String>,
    /** channel urls the user subscribed to */
    val subscriptions: Set<String>,
    /** epoch millis of the newest history entry, used to notice new activity */
    val latestActivityMillis: Long
) {
    data class Seed(val serviceId: Int, val url: String, val title: String, val weight: Double)

    /** true when there is almost nothing to learn from yet */
    val isColdStart: Boolean get() = seeds.isEmpty() && searches.isEmpty() && subscriptions.isEmpty()

    fun topTopics(count: Int): List<String> = topics.entries
        .filter { it.value > 0 }
        .sortedByDescending { it.value }
        .take(count)
        .map { it.key }

    /** How well a title matches the user's topics, roughly in [-1, 1]. */
    fun topicScore(words: List<String>): Double {
        if (words.isEmpty() || topics.isEmpty()) return 0.0
        val sum = words.sumOf { topics[it] ?: 0.0 }
        return (sum / sqrt(words.size.toDouble())).coerceIn(-1.0, 1.0)
    }

    fun channelScore(uploaderUrl: String?): Double = if (uploaderUrl == null) 0.0 else channels[uploaderUrl] ?: 0.0

    companion object {
        private const val TAG = "InterestProfile"
        private const val HALF_LIFE_HOURS = 96.0
        private const val SESSION_HOURS = 6.0
        private const val MAX_HISTORY = 400
        private const val MAX_SEEDS = 8
        private const val MAX_SEARCHES = 15

        val EMPTY = InterestProfile(
            emptyMap(),
            emptyMap(),
            emptyList(),
            emptyMap(),
            emptyList(),
            emptySet(),
            0L
        )

        /** Builds the profile from the local database. Call off the main thread. */
        fun build(context: Context, signals: ActivitySignals): InterestProfile = try {
            buildInternal(context, signals)
        } catch (e: Exception) {
            Log.w(TAG, "Could not build interest profile", e)
            EMPTY
        }

        private fun buildInternal(context: Context, signals: ActivitySignals): InterestProfile {
            val now = OffsetDateTime.now()
            val topics = HashMap<String, Double>()
            val channels = HashMap<String, Double>()
            val watched = HashMap<String, Double>()
            val seedCandidates = ArrayList<Seed>()
            var latest = 0L

            // 1. watch history: the strongest signal
            val stats = HistoryRecordManager(context).streamStatistics.blockingFirst(emptyList())
                .sortedByDescending { it.latestAccessDate }
                .take(MAX_HISTORY)

            for (entry in stats) {
                val stream = entry.streamEntity
                val accessMillis = entry.latestAccessDate.toInstant().toEpochMilli()
                latest = maxOf(latest, accessMillis)
                val ageHours = (now.toInstant().toEpochMilli() - accessMillis) / 3_600_000.0
                val decay = 0.5.pow(ageHours / HALF_LIFE_HOURS)
                val session = if (ageHours < SESSION_HOURS) 2.0 else 1.0

                val completion = if (stream.duration > 0) {
                    (entry.progressMillis / 1000.0 / stream.duration).coerceIn(0.0, 1.0)
                } else {
                    0.5 // live streams / unknown length
                }
                watched[stream.url] = maxOf(watched[stream.url] ?: 0.0, completion)

                val skipped = stream.duration > 60 && entry.progressMillis < 15_000 && completion < 0.1
                val weight = if (skipped) {
                    -0.6 * decay
                } else {
                    (0.35 + completion) * (1 + ln(entry.watchCount.toDouble().coerceAtLeast(1.0))) *
                        session * decay
                }

                Tokenizer.tokens(stream.title).forEach { topics.merge(it, weight, Double::plus) }
                Tokenizer.tokens(stream.uploader).forEach { topics.merge(it, weight * 0.4, Double::plus) }
                stream.uploaderUrl?.let { channels.merge(it, weight, Double::plus) }

                if (!skipped && (completion > 0.25 || entry.watchCount > 1 || stream.duration <= 0)) {
                    seedCandidates.add(Seed(stream.serviceId, stream.url, stream.title, weight))
                }
            }

            // 2. searches: what the user explicitly asked for
            val db = NewPipeDatabase.getInstance(context)
            val searches = runCatching {
                db.searchHistoryDAO().getUniqueEntries(MAX_SEARCHES).blockingFirst(mutableListOf())
            }.getOrDefault(mutableListOf())
            searches.forEachIndexed { rank, query ->
                val w = 1.2 * 0.85.pow(rank)
                Tokenizer.tokens(query).forEach { topics.merge(it, w, Double::plus) }
            }

            // 3. subscriptions: channels the user chose
            val subscriptions = runCatching {
                db.subscriptionDAO().getAll().blockingFirst(emptyList())
            }.getOrDefault(emptyList())
            subscriptions.forEach { sub ->
                sub.url?.let { channels.merge(it, 0.8, Double::plus) }
                Tokenizer.tokens(sub.name).forEach { topics.merge(it, 0.15, Double::plus) }
            }

            // 4. explicit "Not interested"
            signals.dislikedTopics().forEach { (word, count) -> topics.merge(word, -1.5 * count, Double::plus) }

            // 5. local likes: a strong, explicit signal
            signals.likedTopics().forEach { (word, count) -> topics.merge(word, 1.5 * count, Double::plus) }
            signals.likedChannels().forEach { (url, count) -> channels.merge(url, 1.5 * count, Double::plus) }

            val seeds = seedCandidates
                .groupBy { it.url }
                .map { (_, list) -> list.maxBy { it.weight } }
                .sortedByDescending { it.weight }
                .take(MAX_SEEDS)

            return InterestProfile(
                topics = normalize(topics),
                channels = normalize(channels),
                seeds = seeds,
                watched = watched,
                searches = searches,
                subscriptions = subscriptions.mapNotNull { it.url }.toSet(),
                latestActivityMillis = latest
            )
        }

        private fun normalize(map: Map<String, Double>): Map<String, Double> {
            val max = map.values.maxOfOrNull { abs(it) } ?: return emptyMap()
            if (max == 0.0) return emptyMap()
            return map.mapValues { it.value / max }
        }
    }
}
