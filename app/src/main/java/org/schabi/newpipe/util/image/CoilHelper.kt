package org.schabi.newpipe.util.image

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.widget.ImageView
import androidx.annotation.DrawableRes
import coil3.executeBlocking
import coil3.imageLoader
import coil3.request.Disposable
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.error
import coil3.request.placeholder
import coil3.request.target
import coil3.request.transformations
import coil3.size.Size
import coil3.target.Target
import coil3.toBitmap
import coil3.transform.Transformation
import kotlin.math.min
import org.schabi.newpipe.MainActivity
import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.ktx.scale

object CoilHelper {
    private val TAG = CoilHelper::class.java.simpleName

    @JvmOverloads
    fun loadBitmapBlocking(
        context: Context,
        url: String?,
        @DrawableRes placeholderResId: Int = 0
    ): Bitmap? = context.imageLoader
        .executeBlocking(getImageRequest(context, url, placeholderResId).build())
        .image
        ?.toBitmap()

    fun loadAvatar(
        target: ImageView,
        images: List<Image>
    ) {
        loadImageDefault(target, images, R.drawable.placeholder_person)
    }

    fun loadAvatar(
        target: ImageView,
        url: String?
    ) {
        loadImageDefault(target, url, R.drawable.placeholder_person)
    }

    fun loadThumbnail(
        target: ImageView,
        images: List<Image>
    ) {
        loadImageDefault(target, images, R.drawable.placeholder_thumbnail_video)
    }

    fun loadThumbnail(
        target: ImageView,
        url: String?
    ) {
        loadImageDefault(target, url, R.drawable.placeholder_thumbnail_video)
    }

    fun loadScaledDownThumbnail(
        context: Context,
        images: List<Image>,
        target: Target
    ): Disposable {
        val url = ImageStrategy.choosePreferredImage(images)
        val request =
            getImageRequest(context, url, R.drawable.placeholder_thumbnail_video)
                .target(target)
                .transformations(
                    object : Transformation() {
                        override val cacheKey = "COIL_PLAYER_THUMBNAIL_TRANSFORMATION_KEY"

                        override suspend fun transform(
                            input: Bitmap,
                            size: Size
                        ): Bitmap {
                            if (MainActivity.DEBUG) {
                                Log.d(TAG, "Thumbnail - transform() called")
                            }

                            val notificationThumbnailWidth =
                                min(
                                    context.resources.getDimension(R.dimen.player_notification_thumbnail_width),
                                    input.width.toFloat()
                                ).toInt()

                            var newHeight = input.height / (input.width / notificationThumbnailWidth)
                            val result = input.scale(notificationThumbnailWidth, newHeight)

                            return if (result == input || !result.isMutable) {
                                // create a new mutable bitmap to prevent strange crashes on some
                                // devices (see #4638)
                                newHeight = input.height / (input.width / (notificationThumbnailWidth - 1))
                                input.scale(notificationThumbnailWidth, newHeight)
                            } else {
                                result
                            }
                        }
                    }
                ).build()

        return context.imageLoader.enqueue(request)
    }

    fun loadDetailsThumbnail(
        target: ImageView,
        images: List<Image>
    ) {
        val url = ImageStrategy.choosePreferredImage(images)
        loadImageDefault(target, url, R.drawable.placeholder_thumbnail_video, false)
    }

    fun loadBanner(
        target: ImageView,
        images: List<Image>
    ) {
        loadImageDefault(target, images, R.drawable.placeholder_channel_banner)
    }

    fun loadPlaylistThumbnail(
        target: ImageView,
        images: List<Image>
    ) {
        loadImageDefault(target, images, R.drawable.placeholder_thumbnail_playlist)
    }

    fun loadPlaylistThumbnail(
        target: ImageView,
        url: String?
    ) {
        loadImageDefault(target, url, R.drawable.placeholder_thumbnail_playlist)
    }

    private fun loadImageDefault(
        target: ImageView,
        images: List<Image>,
        @DrawableRes placeholderResId: Int
    ) {
        loadImageDefault(target, ImageStrategy.choosePreferredImage(images), placeholderResId)
    }

    private fun loadImageDefault(
        target: ImageView,
        url: String?,
        @DrawableRes placeholderResId: Int,
        showPlaceholder: Boolean = true
    ) {
        val request =
            getImageRequest(target.context, url, placeholderResId, showPlaceholder)
                .target(target)
                .build()
        target.context.imageLoader.enqueue(request)
    }

    private fun getImageRequest(
        context: Context,
        url: String?,
        @DrawableRes placeholderResId: Int,
        showPlaceholderWhileLoading: Boolean = true
    ): ImageRequest.Builder {
        // if the URL was chosen with `choosePreferredImage` it will be null, but check again
        // `shouldLoadImages` in case the URL was chosen with `imageListToDbUrl` (which is the case
        // for URLs stored in the database)
        val takenUrl = url?.takeIf { it.isNotEmpty() && ImageStrategy.shouldLoadImages() }

        return ImageRequest
            .Builder(context)
            .data(takenUrl)
            .error(placeholderResId)
            .memoryCacheKey(takenUrl)
            .diskCacheKey(takenUrl)
            .apply {
                if (takenUrl != null || showPlaceholderWhileLoading) {
                    placeholder(placeholderResId)
                }
            }
    }

    // ---------------------------------------------------------------------------------------
    // Stream: HD thumbnails
    // ---------------------------------------------------------------------------------------

    private val YOUTUBE_ID =
        Regex("(?:[?&]v=|/shorts/|youtu[.]be/|/embed/|/live/)([A-Za-z0-9_-]{11})")

    /**
     * Stream: loads YouTube's high-quality thumbnail of a video (1280x720, or the portrait
     * original for Shorts) built from the video id, and falls back to the thumbnails the
     * extractor gave when it doesn't exist. List thumbnails from the extractor are often
     * small and look blurry on big cards.
     */
    fun loadHdThumbnail(
        target: ImageView,
        videoUrl: String?,
        isShort: Boolean,
        fallback: List<Image>
    ) {
        loadHd(target, videoUrl, isShort) { loadThumbnail(target, fallback) }
    }

    /** Same as [loadHdThumbnail], with the fallback thumbnail given as a URL. */
    fun loadHdThumbnail(
        target: ImageView,
        videoUrl: String?,
        isShort: Boolean,
        fallbackUrl: String?
    ) {
        loadHd(target, videoUrl, isShort) { loadThumbnail(target, fallbackUrl) }
    }

    private fun loadHd(
        target: ImageView,
        videoUrl: String?,
        isShort: Boolean,
        fallback: () -> Unit
    ) {
        val id = videoUrl?.let { YOUTUBE_ID.find(it)?.groupValues?.get(1) }
        if (id == null || !ImageStrategy.shouldLoadImages()) {
            fallback()
            return
        }
        val hdUrl = "https://i.ytimg.com/vi/$id/" + if (isShort) "oar2.jpg" else "hq720.jpg"
        val request = ImageRequest.Builder(target.context)
            .data(hdUrl)
            .memoryCacheKey(hdUrl)
            .diskCacheKey(hdUrl)
            // a recycled card never shows the previous video's picture
            .placeholder(R.drawable.placeholder_thumbnail_video)
            .crossfade(HD_CROSSFADE_MS)
            .target(target)
            .listener(onError = { _, _ -> fallback() })
            .build()
        target.context.imageLoader.enqueue(request)
    }

    private const val HD_CROSSFADE_MS = 120
}
