package org.schabi.newpipe.util

import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import org.schabi.newpipe.BuildConfig

object ReleaseVersionUtil {
    /**
     * Stream ships its own release builds from GitHub Releases, so every non-debug
     * build is treated as a release build that may check for app updates.
     */
    val isReleaseApk: Boolean
        get() = !BuildConfig.DEBUG

    fun isLastUpdateCheckExpired(expiry: Long): Boolean {
        return Instant.ofEpochSecond(expiry) < Instant.now()
    }

    /**
     * Coerce expiry date time in between 6 hours and 72 hours from now
     *
     * @return Epoch second of expiry date time
     */
    fun coerceUpdateCheckExpiry(expiryString: String?): Long {
        val nowPlus6Hours = ZonedDateTime.now().plusHours(6)
        val expiry = expiryString?.let {
            ZonedDateTime.from(DateTimeFormatter.RFC_1123_DATE_TIME.parse(it))
                .coerceIn(nowPlus6Hours, nowPlus6Hours.plusHours(66))
        } ?: nowPlus6Hours
        return expiry.toEpochSecond()
    }
}
