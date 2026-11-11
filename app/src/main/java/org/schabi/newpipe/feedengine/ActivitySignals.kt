/*
 * SPDX-FileCopyrightText: 2026 Abhiraj Aryan
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.feedengine

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONObject
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/**
 * Stream Pulse: small on-device memory of how the user reacted to the feed itself
 * (the watch history lives in the database, see [InterestProfile]):
 *  - impressions: which videos were shown and when, so unclicked ones sink and the feed
 *    looks different on every visit,
 *  - "Not interested" videos and "Don't recommend channel" channels,
 *  - topics the user pushed away.
 *
 * Stored in a private SharedPreferences file; capped so it never grows without bound.
 */
class ActivitySignals private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val impressions: MutableMap<String, Impression> = loadImpressions()
    private val hiddenVideos: MutableSet<String> = prefs.getStringSet(KEY_HIDDEN_VIDEOS, null)?.toMutableSet()
        ?: mutableSetOf()
    private val blockedChannels: MutableSet<String> = prefs.getStringSet(KEY_BLOCKED_CHANNELS, null)
        ?.toMutableSet() ?: mutableSetOf()
    private val dislikedTopics: MutableMap<String, Double> = loadDoubles(KEY_DISLIKED_TOPICS)

    data class Impression(val count: Int, val lastShownMillis: Long)

    // --- impressions --------------------------------------------------------------------

    @Synchronized
    fun recordShown(urls: Collection<String>, now: Long = System.currentTimeMillis()) {
        if (urls.isEmpty()) return
        for (url in urls) {
            val old = impressions[url]
            // count one impression per hour at most, so scrolling up and down is not punished
            if (old != null && now - old.lastShownMillis < HOUR) continue
            impressions[url] = Impression((old?.count ?: 0) + 1, now)
        }
        trimAndSaveImpressions()
    }

    @Synchronized
    fun impressionOf(url: String): Impression? = impressions[url]

    // --- explicit feedback ----------------------------------------------------------------

    @Synchronized
    fun markNotInterested(item: StreamInfoItem) {
        hiddenVideos.add(item.url)
        Tokenizer.tokens(item.name).forEach { dislikedTopics.merge(it, 1.0, Double::plus) }
        prefs.edit {
            putStringSet(KEY_HIDDEN_VIDEOS, hiddenVideos.toList().takeLast(MAX_HIDDEN).toSet())
            putString(KEY_DISLIKED_TOPICS, JSONObject(dislikedTopics.toMap()).toString())
        }
    }

    @Synchronized
    fun blockChannel(uploaderUrl: String?) {
        if (uploaderUrl.isNullOrBlank()) return
        blockedChannels.add(uploaderUrl)
        prefs.edit { putStringSet(KEY_BLOCKED_CHANNELS, blockedChannels.toSet()) }
    }

    @Synchronized
    fun isHidden(item: StreamInfoItem): Boolean = item.url in hiddenVideos || (item.uploaderUrl != null && item.uploaderUrl in blockedChannels)

    @Synchronized
    fun dislikedTopics(): Map<String, Double> = dislikedTopics.toMap()

    @Synchronized
    fun clear() {
        impressions.clear()
        hiddenVideos.clear()
        blockedChannels.clear()
        dislikedTopics.clear()
        prefs.edit { clear() }
    }

    // --- persistence ------------------------------------------------------------------------

    private fun loadImpressions(): MutableMap<String, Impression> {
        val json = prefs.getString(KEY_IMPRESSIONS, null) ?: return LinkedHashMap()
        return runCatching {
            val obj = JSONObject(json)
            val map = LinkedHashMap<String, Impression>()
            obj.keys().forEach { url ->
                val parts = obj.getString(url).split(',')
                if (parts.size == 2) {
                    map[url] = Impression(parts[0].toInt(), parts[1].toLong())
                }
            }
            map
        }.getOrDefault(LinkedHashMap())
    }

    private fun loadDoubles(key: String): MutableMap<String, Double> {
        val json = prefs.getString(key, null) ?: return HashMap()
        return runCatching {
            val obj = JSONObject(json)
            obj.keys().asSequence().associateWith { obj.getDouble(it) }.toMutableMap()
        }.getOrDefault(HashMap())
    }

    private fun trimAndSaveImpressions() {
        if (impressions.size > MAX_IMPRESSIONS) {
            val keep = impressions.entries
                .sortedByDescending { it.value.lastShownMillis }
                .take(MAX_IMPRESSIONS)
                .associate { it.key to it.value }
            impressions.clear()
            impressions.putAll(keep)
        }
        val obj = JSONObject()
        impressions.forEach { (url, imp) -> obj.put(url, "${imp.count},${imp.lastShownMillis}") }
        prefs.edit { putString(KEY_IMPRESSIONS, obj.toString()) }
    }

    companion object {
        private const val FILE = "stream_pulse"
        private const val KEY_IMPRESSIONS = "impressions"
        private const val KEY_HIDDEN_VIDEOS = "hidden_videos"
        private const val KEY_BLOCKED_CHANNELS = "blocked_channels"
        private const val KEY_DISLIKED_TOPICS = "disliked_topics"
        private const val MAX_IMPRESSIONS = 3000
        private const val MAX_HIDDEN = 2000
        const val HOUR = 60L * 60L * 1000L

        @Volatile
        private var instance: ActivitySignals? = null

        @JvmStatic
        fun get(context: Context): ActivitySignals = instance ?: synchronized(this) {
            instance ?: ActivitySignals(context).also { instance = it }
        }
    }
}
