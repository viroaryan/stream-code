/*
 * SPDX-FileCopyrightText: 2026 Abhiraj Aryan
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.feedengine

/**
 * Stream Pulse: turns video titles, channel names and searches into topic words.
 *
 * Keeps letters of every script (so Hindi and other languages work), lower-cases them,
 * keeps hashtags as plain words and drops words that say nothing about a topic
 * ("official", "video", "the", "hai", ...).
 */
object Tokenizer {

    // \p{M}: combining marks (Hindi matras etc.) belong to the word
    private val SPLIT = Regex("[^\\p{L}\\p{M}\\p{Nd}#]+")

    private val STOPWORDS: Set<String> = """
        a an the and or but of to in on at by for from with without into over under about as is are
        was were be been being it its this that these those i me my you your he she we they them our
        not no yes so too very can will just do does did done has have had how what why when where who
        which all any some more most new latest full hd 4k 8k 1080p 720p official video videos audio
        lyric lyrics lyrical song songs ft feat featuring vs v s x ep episode part pt live stream
        shorts short reels reel viral trending today now part1 part2 watch must best top first last one
        two three day days time year years make made get got go going vlog
        hai hain ho ka ki ke ko se me mein par aur ya bhi to na nahi kya kaise kyu kyun ek ye yeh wo woh
        jo tha thi the hua hui raha rahi rahe kar karo karna wala wali wale sab apna apni apne bhai log
        का की के को से में पर और भी तो ना नहीं क्या कैसे क्यों एक ये यह वो वह जो था थी थे हुआ कर
    """.trimIndent().split(Regex("\\s+")).filter { it.isNotBlank() }.toSet()

    /** Distinct topic words of [text], in order of appearance. */
    fun tokens(text: String?): List<String> {
        if (text.isNullOrBlank()) return emptyList()
        val seen = LinkedHashSet<String>()
        for (raw in text.lowercase().split(SPLIT)) {
            val word = raw.trim('#')
            if (word.length < MIN_LENGTH || word in STOPWORDS || word.all { it.isDigit() }) {
                continue
            }
            seen.add(word)
        }
        return seen.toList()
    }

    private const val MIN_LENGTH = 3
}
