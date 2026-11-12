/*
 * SPDX-FileCopyrightText: 2026 Abhiraj Aryan
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.feedengine.ui

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.schabi.newpipe.R
import org.schabi.newpipe.databinding.ItemHomeShortBinding
import org.schabi.newpipe.databinding.ItemHomeShortsHeaderBinding
import org.schabi.newpipe.databinding.ItemHomeVideoBinding
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.util.Localization
import org.schabi.newpipe.util.StreamTypeUtil
import org.schabi.newpipe.util.image.CoilHelper

/** One row of the Home feed. */
sealed class FeedRow {
    data class Video(val item: StreamInfoItem) : FeedRow()
    data class ShortsHeader(val id: Int) : FeedRow()
    data class Short(val item: StreamInfoItem) : FeedRow()
}

/**
 * Stream: YouTube-style Home feed: big video cards across the full width, and blocks of
 * portrait Shorts in two columns under a "Shorts" heading.
 */
class HomeFeedAdapter(private val callbacks: Callbacks) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    interface Callbacks {
        fun onVideoClick(item: StreamInfoItem)
        fun onVideoMenu(item: StreamInfoItem, anchor: View)
        fun onChannelClick(item: StreamInfoItem)
        fun onShortClick(item: StreamInfoItem)
        fun onShortMenu(item: StreamInfoItem, anchor: View)
        fun onItemShown(item: StreamInfoItem)
    }

    private val rows = ArrayList<FeedRow>()

    val spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
        override fun getSpanSize(position: Int) = if (rows.getOrNull(position) is FeedRow.Short) 1 else SPAN
    }

    fun setRows(newRows: List<FeedRow>) {
        rows.clear()
        rows.addAll(newRows)
        @Suppress("NotifyDataSetChanged")
        notifyDataSetChanged()
    }

    fun appendRows(more: List<FeedRow>) {
        val start = rows.size
        rows.addAll(more)
        notifyItemRangeInserted(start, more.size)
    }

    fun insertRows(position: Int, more: List<FeedRow>) {
        val at = position.coerceIn(0, rows.size)
        rows.addAll(at, more)
        notifyItemRangeInserted(at, more.size)
    }

    fun rows(): List<FeedRow> = rows

    override fun getItemCount() = rows.size

    override fun getItemViewType(position: Int) = when (rows[position]) {
        is FeedRow.Video -> TYPE_VIDEO
        is FeedRow.ShortsHeader -> TYPE_HEADER
        is FeedRow.Short -> TYPE_SHORT
    }

    override fun getItemId(position: Int): Long = when (val row = rows[position]) {
        is FeedRow.Video -> row.item.url.hashCode().toLong()
        is FeedRow.ShortsHeader -> -row.id.toLong() - 1
        is FeedRow.Short -> row.item.url.hashCode().toLong() xor SHORT_ID_SALT
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_VIDEO -> VideoHolder(ItemHomeVideoBinding.inflate(inflater, parent, false))
            TYPE_HEADER -> HeaderHolder(ItemHomeShortsHeaderBinding.inflate(inflater, parent, false))
            else -> ShortHolder(ItemHomeShortBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is FeedRow.Video -> (holder as VideoHolder).bind(row.item)
            is FeedRow.ShortsHeader -> (holder as HeaderHolder).bind()
            is FeedRow.Short -> (holder as ShortHolder).bind(row.item)
        }
    }

    inner class VideoHolder(private val b: ItemHomeVideoBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: StreamInfoItem) {
            val context = b.root.context
            b.homeVideoTitle.text = item.name
            CoilHelper.loadHdThumbnail(b.homeVideoThumb, item.url, false, item.thumbnails)
            CoilHelper.loadAvatar(b.homeVideoAvatar, item.uploaderAvatars)

            val live = StreamTypeUtil.isLiveStream(item.streamType)
            when {
                live -> {
                    b.homeVideoDuration.isVisible = true
                    b.homeVideoDuration.setText(R.string.duration_live)
                    b.homeVideoDuration.setBackgroundResource(R.drawable.bg_live_badge)
                }

                item.duration > 0 -> {
                    b.homeVideoDuration.isVisible = true
                    b.homeVideoDuration.text = Localization.getDurationString(item.duration)
                    b.homeVideoDuration.setBackgroundResource(R.drawable.bg_duration_badge)
                }

                else -> b.homeVideoDuration.isVisible = false
            }

            val parts = ArrayList<String>(3)
            item.uploaderName?.takeIf { it.isNotBlank() }?.let(parts::add)
            if (item.viewCount >= 0) {
                parts.add(
                    if (live) {
                        Localization.shortWatchingCount(context, item.viewCount)
                    } else {
                        Localization.shortViewCount(context, item.viewCount)
                    }
                )
            }
            if (!live) {
                Localization.relativeTimeOrTextual(context, item.uploadDate, item.textualUploadDate)
                    ?.takeIf { it.isNotBlank() }?.let(parts::add)
            }
            b.homeVideoMeta.text = parts.joinToString(" · ")

            b.root.setOnClickListener { callbacks.onVideoClick(item) }
            b.root.setOnLongClickListener {
                callbacks.onVideoMenu(item, b.homeVideoMore)
                true
            }
            b.homeVideoMore.setOnClickListener { callbacks.onVideoMenu(item, it) }
            b.homeVideoAvatar.setOnClickListener { callbacks.onChannelClick(item) }
            callbacks.onItemShown(item)
        }
    }

    inner class HeaderHolder(private val b: ItemHomeShortsHeaderBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind() {
            ImageViewCompat.setImageTintList(
                b.homeShortsHeaderIcon,
                ColorStateList.valueOf(ContextCompat.getColor(b.root.context, R.color.stream_rose))
            )
        }
    }

    inner class ShortHolder(private val b: ItemHomeShortBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: StreamInfoItem) {
            b.homeShortTitle.text = item.name
            b.homeShortViews.text = if (item.viewCount >= 0) {
                Localization.shortViewCount(b.root.context, item.viewCount)
            } else {
                ""
            }
            CoilHelper.loadHdThumbnail(b.homeShortThumb, item.url, true, item.thumbnails)
            b.homeShortThumb.setOnClickListener { callbacks.onShortClick(item) }
            b.homeShortThumb.setOnLongClickListener {
                callbacks.onShortMenu(item, b.homeShortMore)
                true
            }
            b.homeShortMore.setOnClickListener { callbacks.onShortMenu(item, it) }
            callbacks.onItemShown(item)
        }
    }

    companion object {
        const val SPAN = 2
        private const val TYPE_VIDEO = 0
        private const val TYPE_HEADER = 1
        private const val TYPE_SHORT = 2
        private const val SHORT_ID_SALT = 0x5ADEL
    }
}
