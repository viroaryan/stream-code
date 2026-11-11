/*
 * SPDX-FileCopyrightText: 2018-2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.util

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import java.util.concurrent.TimeUnit
import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.StreamingService

object ServiceHelper {
    private val DEFAULT_FALLBACK_SERVICE: StreamingService = ServiceList.YouTube

    @JvmStatic
    @DrawableRes
    fun getIcon(serviceId: Int): Int {
        return when (serviceId) {
            0 -> R.drawable.ic_smart_display
            1 -> R.drawable.ic_cloud
            2 -> R.drawable.ic_placeholder_media_ccc
            3 -> R.drawable.ic_placeholder_peertube
            4 -> R.drawable.ic_placeholder_bandcamp
            else -> R.drawable.ic_circle
        }
    }

    @JvmStatic
    fun getTranslatedFilterString(filter: String, context: Context): String {
        return when (filter) {
            "all" -> context.getString(R.string.all)
            "videos", "sepia_videos", "music_videos" -> context.getString(R.string.videos_string)
            "channels" -> context.getString(R.string.channels)
            "playlists", "music_playlists" -> context.getString(R.string.playlists)
            "tracks" -> context.getString(R.string.tracks)
            "users" -> context.getString(R.string.users)
            "conferences" -> context.getString(R.string.conferences)
            "events" -> context.getString(R.string.events)
            "music_songs" -> context.getString(R.string.songs)
            "music_albums" -> context.getString(R.string.albums)
            "music_artists" -> context.getString(R.string.artists)
            else -> filter
        }
    }

    /**
     * Get a resource string with instructions for importing subscriptions for each service.
     *
     * @param serviceId service to get the instructions for
     * @return the string resource containing the instructions or -1 if the service don't support it
     */
    @JvmStatic
    @StringRes
    fun getImportInstructions(serviceId: Int): Int {
        return when (serviceId) {
            0 -> R.string.import_youtube_instructions
            1 -> R.string.import_soundcloud_instructions
            else -> -1
        }
    }

    /**
     * For services that support importing from a channel url, return a hint that will
     * be used in the EditText that the user will type in his channel url.
     *
     * @param serviceId service to get the hint for
     * @return the hint's string resource or -1 if the service don't support it
     */
    @JvmStatic
    @StringRes
    fun getImportInstructionsHint(serviceId: Int): Int {
        return when (serviceId) {
            1 -> R.string.import_soundcloud_instructions_hint
            else -> -1
        }
    }

    /**
     * Stream is YouTube-only: the selected service is always YouTube, whatever an older
     * version or an imported backup stored in the preferences.
     */
    @JvmStatic
    fun getSelectedServiceId(context: Context): Int = DEFAULT_FALLBACK_SERVICE.serviceId

    /** Stream is YouTube-only, see [getSelectedServiceId]. */
    @JvmStatic
    fun getSelectedService(context: Context): StreamingService = DEFAULT_FALLBACK_SERVICE

    /** Whether Stream supports this service, i.e. whether it is YouTube. */
    @JvmStatic
    fun isSupportedService(serviceId: Int): Boolean = serviceId == DEFAULT_FALLBACK_SERVICE.serviceId

    @JvmStatic
    fun getNameOfServiceById(serviceId: Int): String {
        return ServiceList.all().stream()
            .filter { it.serviceId == serviceId }
            .findFirst()
            .map(StreamingService::getServiceInfo)
            .map(StreamingService.ServiceInfo::getName)
            .orElse("<unknown>")
    }

    /**
     * @param serviceId the id of the service
     * @return the service corresponding to the provided id
     * @throws java.util.NoSuchElementException if there is no service with the provided id
     */
    @JvmStatic
    fun getServiceById(serviceId: Int): StreamingService {
        return ServiceList.all().firstNotNullOf { it.takeIf { it.serviceId == serviceId } }
    }

    @JvmStatic
    fun getCacheExpirationMillis(serviceId: Int): Long {
        return TimeUnit.MILLISECONDS.convert(1, TimeUnit.HOURS)
    }
}
