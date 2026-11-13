/*
 * SPDX-FileCopyrightText: 2026 Abhiraj Aryan
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.local.shorts

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.audio.AudioAttributes
import com.google.android.exoplayer2.ui.StyledPlayerView
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.schedulers.Schedulers
import org.schabi.newpipe.R
import org.schabi.newpipe.databinding.FragmentShortsPagerBinding
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.VideoStream
import org.schabi.newpipe.feedengine.ActivitySignals
import org.schabi.newpipe.feedengine.StreamPulse
import org.schabi.newpipe.info_list.dialog.InfoItemDialog
import org.schabi.newpipe.local.dialog.PlaylistDialog
import org.schabi.newpipe.local.history.HistoryRecordManager
import org.schabi.newpipe.player.helper.LoadController
import org.schabi.newpipe.player.helper.PlayerDataSource
import org.schabi.newpipe.player.resolver.VideoPlaybackResolver
import org.schabi.newpipe.util.ExtractorHelper
import org.schabi.newpipe.util.ListHelper
import org.schabi.newpipe.util.NavigationHelper
import org.schabi.newpipe.util.external_communication.ShareUtils

/**
 * Stream's Shorts player: full-screen pages, swipe up/down for the next/previous Short, like
 * YouTube Shorts. One ExoPlayer moves from page to page; the next Short's data is fetched
 * ahead so swiping feels instant; every watched Short is recorded in the history, which is
 * what [StreamPulse] learns from.
 *
 * Two modes:
 *  - feed mode (the Shorts tab): Shorts come from [StreamPulse] and more are loaded on the way,
 *  - list mode (opened from a Shorts card): plays [pendingItems] starting at the tapped one.
 */
class ShortsPagerFragment : Fragment(), ShortsPageAdapter.Callbacks {

    private var binding: FragmentShortsPagerBinding? = null
    private val disposables = CompositeDisposable()
    private var loadDisposable: Disposable? = null
    private val adapter = ShortsPageAdapter(this)
    private val handler = Handler(Looper.getMainLooper())

    private var player: ExoPlayer? = null
    private var resolver: VideoPlaybackResolver? = null
    private var currentPosition = -1
    private var currentInfo: StreamInfo? = null
    private var currentView: StyledPlayerView? = null
    private var userPaused = false
    private var loops = 0

    // Stream: the next Short is loaded and buffered in a second player while the current
    // one plays, so a swipe starts it instantly
    private var nextPlayer: ExoPlayer? = null
    private var nextPosition = -1
    private var nextInfo: StreamInfo? = null
    private var nextDisposable: Disposable? = null

    private var session: StreamPulse.Session? = null
    private var loadingMore = false
    private var feedMode = true

    private val progressTicker = object : Runnable {
        override fun run() {
            val p = player
            val holder = holderAt(currentPosition)
            if (p != null && holder != null && p.duration > 0) {
                holder.b.shortProgress.progress = (p.currentPosition * PROGRESS_MAX / p.duration).toInt()
            }
            handler.postDelayed(this, PROGRESS_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        feedMode = arguments?.getBoolean(ARG_FEED_MODE, true) ?: true
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = FragmentShortsPagerBinding.inflate(inflater, container, false)
        .also { binding = it }
        .root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val b = binding!!
        b.shortsPager.adapter = adapter
        b.shortsPager.offscreenPageLimit = 1
        b.shortsPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                // wait for the page's view to be laid out
                b.shortsPager.post { playPage(position) }
                if (feedMode && position > adapter.itemCount - LOAD_AHEAD) loadMore()
            }
        })

        if (!feedMode) {
            b.shortsPagerBack.isVisible = true
            b.shortsPagerBack.setOnClickListener { requireActivity().finish() }
        }

        if (adapter.itemCount == 0) {
            if (feedMode) {
                loadFeed()
            } else {
                val items = pendingItems.orEmpty()
                val index = pendingIndex.coerceIn(0, maxOf(0, items.lastIndex))
                pendingItems = null
                b.shortsPagerLoading.isVisible = false
                adapter.submit(items)
                b.shortsPager.setCurrentItem(index, false)
                if (index == 0) b.shortsPager.post { playPage(0) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ensurePlayer()
        if (currentPosition >= 0 && currentInfo == null) {
            playPage(currentPosition, force = true)
        } else if (!userPaused) {
            player?.playWhenReady = true
        }
        handler.post(progressTicker)
    }

    override fun onPause() {
        handler.removeCallbacks(progressTicker)
        player?.playWhenReady = false
        super.onPause()
    }

    override fun onStop() {
        // free the decoder while the Shorts player is not visible
        releasePlayer()
        super.onStop()
    }

    override fun onDestroyView() {
        loadDisposable?.dispose()
        releasePlayer()
        binding = null
        super.onDestroyView()
    }

    override fun onDestroy() {
        disposables.dispose()
        super.onDestroy()
    }

    // -------------------------------------------------------------------------------------
    // Feed
    // -------------------------------------------------------------------------------------

    private fun loadFeed() {
        val b = binding ?: return
        b.shortsPagerLoading.isVisible = true
        b.shortsPagerMessage.isVisible = false
        disposables.add(
            StreamPulse.shorts(requireContext())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({ result ->
                    session = result.session
                    adapter.submit(result.shorts)
                    binding?.let {
                        it.shortsPagerLoading.isVisible = false
                        it.shortsPagerMessage.isVisible = result.shorts.isEmpty()
                        it.shortsPagerMessage.setText(R.string.shorts_empty)
                        if (result.shorts.isNotEmpty()) it.shortsPager.post { playPage(0) }
                    }
                }, { e ->
                    Log.e(TAG, "Could not load shorts", e)
                    binding?.let {
                        it.shortsPagerLoading.isVisible = false
                        it.shortsPagerMessage.isVisible = true
                        it.shortsPagerMessage.setText(R.string.shorts_load_error)
                        it.shortsPagerMessage.setOnClickListener { loadFeed() }
                    }
                })
        )
    }

    private fun loadMore() {
        val current = session ?: return
        if (loadingMore || !current.hasMore()) return
        loadingMore = true
        disposables.add(
            StreamPulse.more(requireContext(), current)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({ more ->
                    if (session === current) adapter.append(more)
                    loadingMore = false
                }, { e ->
                    Log.w(TAG, "Could not load more shorts", e)
                    loadingMore = false
                })
        )
    }

    // -------------------------------------------------------------------------------------
    // Playback
    // -------------------------------------------------------------------------------------

    private fun ensurePlayer() {
        if (player != null) return
        player = buildPlayer().apply { addListener(playerListener) }
    }

    /** Builds a Shorts player; the resolver is shared by the current and the preloaded one. */
    private fun buildPlayer(): ExoPlayer {
        val context = requireContext()
        if (resolver != null) return newExoPlayer(context)
        val dataSource = PlayerDataSource(context, DefaultBandwidthMeter.Builder(context).build())
        resolver = VideoPlaybackResolver(
            context,
            dataSource,
            object : VideoPlaybackResolver.QualityResolver {
                // Stream: Shorts play at 720p, sharp on a phone and quick to start
                override fun getDefaultResolutionIndex(sortedVideos: List<VideoStream>) = ListHelper.getResolutionIndex(context, sortedVideos, SHORTS_RESOLUTION)

                override fun getOverrideResolutionIndex(
                    sortedVideos: List<VideoStream>,
                    playbackQuality: String?
                ) = ListHelper.getResolutionIndex(context, sortedVideos, SHORTS_RESOLUTION)
            }
        )
        return newExoPlayer(context)
    }

    private fun newExoPlayer(context: android.content.Context): ExoPlayer = ExoPlayer.Builder(context)
        .setLoadControl(LoadController())
        .build()
        .apply {
            repeatMode = Player.REPEAT_MODE_ONE
            // Stream: no auto-captions over Shorts
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true
            )
        }

    private fun releasePlayer() {
        currentView?.player = null
        currentView = null
        player?.removeListener(playerListener)
        player?.release()
        player = null
        nextDisposable?.dispose()
        nextPlayer?.release()
        nextPlayer = null
        nextPosition = -1
        nextInfo = null
        resolver = null
        currentInfo = null
    }

    private val playerListener = object : Player.Listener {
        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) loops++
        }

        override fun onRenderedFirstFrame() {
            holderAt(currentPosition)?.b?.shortThumb?.isVisible = false
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            holderAt(currentPosition)?.b?.shortLoading?.isVisible =
                playbackState == Player.STATE_BUFFERING
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.w(TAG, "Short playback failed", error)
            showError(currentPosition)
        }
    }

    private fun playPage(position: Int, force: Boolean = false) {
        if (!isResumed || position !in 0 until adapter.itemCount) return
        if (position == currentPosition && !force) return
        ensurePlayer()
        val p = player ?: return
        val holder = holderAt(position) ?: return
        val item = adapter.itemAt(position)

        finishWatch(position)
        loops = 0

        // reset the page we are leaving
        holderAt(currentPosition)?.takeIf { currentPosition != position }?.let {
            it.b.shortThumb.isVisible = true
            it.b.shortPauseIcon.isVisible = false
            it.b.shortProgress.progress = 0
        }

        currentPosition = position
        currentInfo = null
        userPaused = false
        loadDisposable?.dispose()
        holder.b.shortError.isVisible = false
        holder.b.shortPauseIcon.isVisible = false

        // Stream: the Short was already buffered in the second player: swap and play at once
        val preloaded = nextPlayer
        val preloadedInfo = nextInfo
        if (preloaded != null && preloadedInfo != null && nextPosition == position &&
            preloadedInfo.url == item.url
        ) {
            p.removeListener(playerListener)
            p.stop()
            p.clearMediaItems()
            nextPlayer = p
            nextPosition = -1
            nextInfo = null
            player = preloaded
            preloaded.addListener(playerListener)
            StyledPlayerView.switchTargetView(preloaded, currentView, holder.b.shortPlayerView)
            currentView = holder.b.shortPlayerView
            currentInfo = preloadedInfo
            holder.b.shortLoading.isVisible = preloaded.playbackState == Player.STATE_BUFFERING
            preloaded.seekTo(0)
            preloaded.playWhenReady = isResumed
            recordWatched(preloadedInfo)
            preloadNext(position + 1)
            prefetch(position + 2)
            return
        }

        p.stop()
        p.clearMediaItems()

        StyledPlayerView.switchTargetView(p, currentView, holder.b.shortPlayerView)
        currentView = holder.b.shortPlayerView
        holder.b.shortThumb.isVisible = true
        holder.b.shortLoading.isVisible = true

        loadDisposable = ExtractorHelper.getStreamInfo(requireContext(), item.serviceId, item.url, false)
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ info ->
                if (currentPosition != position) return@subscribe
                val source = resolver?.resolve(info)
                if (source == null) {
                    showError(position)
                    return@subscribe
                }
                currentInfo = info
                p.setMediaSource(source)
                p.prepare()
                p.playWhenReady = isResumed
                recordWatched(info)
            }, { e ->
                Log.w(TAG, "Could not load short ${item.url}", e)
                if (currentPosition == position) showError(position)
            })

        preloadNext(position + 1)
        prefetch(position + 2)
    }

    /** Stream: loads and buffers the Short at [position] in the second, paused player. */
    private fun preloadNext(position: Int) {
        if (position !in 0 until adapter.itemCount) return
        if (nextPosition == position && nextInfo != null) return
        val item = adapter.itemAt(position)
        val next = nextPlayer ?: buildPlayer().also { nextPlayer = it }
        nextDisposable?.dispose()
        next.stop()
        next.clearMediaItems()
        nextPosition = position
        nextInfo = null
        nextDisposable = ExtractorHelper.getStreamInfo(requireContext(), item.serviceId, item.url, false)
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ info ->
                if (nextPosition != position || nextPlayer !== next) return@subscribe
                val source = resolver?.resolve(info) ?: return@subscribe
                next.setMediaSource(source)
                next.playWhenReady = false
                next.prepare()
                nextInfo = info
            }, { e -> Log.w(TAG, "Could not preload short ${item.url}", e) })
    }

    /** Fetches (and caches) the next Short's data so the next swipe starts quickly. */
    private fun prefetch(position: Int) {
        if (position !in 0 until adapter.itemCount) return
        val next = adapter.itemAt(position)
        disposables.add(
            ExtractorHelper.getStreamInfo(requireContext(), next.serviceId, next.url, false)
                .subscribeOn(Schedulers.io())
                .subscribe({}, { })
        )
    }

    /**
     * Stream Pulse: tells the engine how the Short we are leaving was watched and reacts
     * at once, like Instagram: a loved Short pulls similar Shorts right ahead, a quick
     * swipe-away pushes that channel further down.
     */
    private fun finishWatch(nextPosition: Int) {
        val p = player ?: return
        val info = currentInfo ?: return
        if (currentPosition !in 0 until adapter.itemCount || currentPosition == nextPosition) return
        val item = adapter.itemAt(currentPosition)
        val durationMs = if (p.duration != C.TIME_UNSET) p.duration else info.duration * 1000
        val watchedMs = loops * durationMs + p.currentPosition
        val score = ActivitySignals.get(requireContext())
            .recordShortWatch(item.url, item.name, item.uploaderUrl, watchedMs, durationMs, loops)
        loops = 0
        if (!feedMode) return
        if (score >= 1.0) {
            val signals = ActivitySignals.get(requireContext())
            val similar = info.relatedItems.filterIsInstance<StreamInfoItem>()
                .filter { (it.isShortFormContent || it.url.contains("/shorts/")) && !signals.isHidden(it) }
                .filter { !adapter.contains(it.url) }
                .take(SIMILAR_AFTER_LOVE)
            adapter.insertAt(nextPosition + 1, similar)
        } else if (score < 0) {
            adapter.sinkChannel(nextPosition + 1, SINK_WINDOW, item.uploaderUrl)
        }
    }

    private fun recordWatched(info: StreamInfo) {
        disposables.add(
            HistoryRecordManager(requireContext()).onViewed(info)
                .subscribeOn(Schedulers.io())
                .onErrorComplete()
                .subscribe()
        )
    }

    private fun showError(position: Int) {
        holderAt(position)?.b?.let {
            it.shortLoading.isVisible = false
            it.shortError.isVisible = true
            it.shortError.setText(R.string.short_error)
        }
    }

    private fun holderAt(position: Int): ShortsPageAdapter.Holder? {
        if (position < 0) return null
        val recycler = binding?.shortsPager?.getChildAt(0) as? RecyclerView ?: return null
        return recycler.findViewHolderForAdapterPosition(position) as? ShortsPageAdapter.Holder
    }

    // -------------------------------------------------------------------------------------
    // Page callbacks
    // -------------------------------------------------------------------------------------

    override fun onTogglePause(position: Int) {
        val p = player ?: return
        if (position != currentPosition) return
        userPaused = p.playWhenReady
        p.playWhenReady = !userPaused
        holderAt(position)?.b?.shortPauseIcon?.isVisible = userPaused
    }

    override fun onLike(item: StreamInfoItem): Boolean = ActivitySignals.get(requireContext()).toggleLike(item.url, item.name, item.uploaderUrl)

    override fun isLiked(item: StreamInfoItem): Boolean = ActivitySignals.get(requireContext()).isLiked(item.url)

    override fun onComments(item: StreamInfoItem) {
        // the full video page has comments, description and related videos
        player?.playWhenReady = false
        userPaused = true
        holderAt(currentPosition)?.b?.shortPauseIcon?.isVisible = true
        if (feedMode) {
            NavigationHelper.openVideoDetailFragment(
                requireContext(),
                requireActivity().supportFragmentManager,
                item.serviceId,
                item.url,
                item.name,
                null,
                false
            )
        } else {
            NavigationHelper.openVideoDetail(requireContext(), item.serviceId, item.url, item.name, null, false)
            requireActivity().finish()
        }
    }

    override fun onShare(item: StreamInfoItem) {
        ShareUtils.shareText(requireContext(), item.name, item.url, item.thumbnails)
    }

    override fun onSave(item: StreamInfoItem) {
        val info = currentInfo
        if (info != null && info.url == item.url) {
            disposables.add(
                PlaylistDialog.createCorrespondingDialog(
                    requireContext(),
                    listOf(org.schabi.newpipe.database.stream.model.StreamEntity(info))
                ) { dialog -> dialog.show(parentFragmentManager, "shorts_save") }
            )
        } else {
            onMore(item)
        }
    }

    override fun onMore(item: StreamInfoItem) {
        val activity = activity ?: return
        try {
            InfoItemDialog.Builder(activity, requireContext(), this, item).create().show()
        } catch (e: IllegalArgumentException) {
            InfoItemDialog.Builder.reportErrorDuringInitialization(e, item)
        }
    }

    override fun onChannel(item: StreamInfoItem) {
        val url = item.uploaderUrl ?: return
        if (!feedMode) return // the standalone player has no channel screen behind it
        player?.playWhenReady = false
        NavigationHelper.openChannelFragment(
            requireActivity().supportFragmentManager,
            item.serviceId,
            url,
            item.uploaderName ?: ""
        )
    }

    companion object {
        private const val SHORTS_RESOLUTION = "720p"
        private const val SIMILAR_AFTER_LOVE = 3
        private const val SINK_WINDOW = 15

        private const val TAG = "ShortsPagerFragment"
        private const val ARG_FEED_MODE = "feed_mode"
        private const val LOAD_AHEAD = 5
        private const val PROGRESS_MAX = 1000L
        private const val PROGRESS_INTERVAL_MS = 200L

        /** Shorts handed over to a list-mode player (too big for a Bundle). */
        private var pendingItems: List<StreamInfoItem>? = null
        private var pendingIndex = 0

        /** The Shorts tab: personalised feed. */
        fun newFeedInstance() = ShortsPagerFragment().apply {
            arguments = bundleOf(ARG_FEED_MODE to true)
        }

        /** Plays [items] starting at [index] (used by the standalone Shorts player). */
        fun newListInstance(items: List<StreamInfoItem>, index: Int): ShortsPagerFragment {
            pendingItems = items
            pendingIndex = index
            return ShortsPagerFragment().apply {
                arguments = bundleOf(ARG_FEED_MODE to false)
            }
        }
    }
}
