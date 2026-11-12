/*
 * SPDX-FileCopyrightText: 2026 Abhiraj Aryan
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.local.shorts

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.widget.TextViewCompat
import androidx.recyclerview.widget.RecyclerView
import org.schabi.newpipe.R
import org.schabi.newpipe.databinding.ItemShortPageBinding
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.util.image.CoilHelper

/** Stream: pages of the vertical Shorts player (thumbnail, overlay texts and actions). */
class ShortsPageAdapter(private val callbacks: Callbacks) :
    RecyclerView.Adapter<ShortsPageAdapter.Holder>() {

    interface Callbacks {
        fun onTogglePause(position: Int)
        fun onLike(item: StreamInfoItem): Boolean
        fun isLiked(item: StreamInfoItem): Boolean
        fun onComments(item: StreamInfoItem)
        fun onShare(item: StreamInfoItem)
        fun onSave(item: StreamInfoItem)
        fun onMore(item: StreamInfoItem)
        fun onChannel(item: StreamInfoItem)
    }

    private val items = ArrayList<StreamInfoItem>()

    fun submit(newItems: List<StreamInfoItem>) {
        items.clear()
        items.addAll(newItems)
        @Suppress("NotifyDataSetChanged")
        notifyDataSetChanged()
    }

    fun append(more: List<StreamInfoItem>) {
        if (more.isEmpty()) return
        val start = items.size
        items.addAll(more)
        notifyItemRangeInserted(start, more.size)
    }

    fun itemAt(position: Int): StreamInfoItem = items[position]

    fun contains(url: String): Boolean = items.any { it.url == url }

    /** Stream: puts Shorts like the one just loved right ahead of the user. */
    fun insertAt(position: Int, more: List<StreamInfoItem>) {
        val fresh = more.filter { m -> items.none { it.url == m.url } }
        if (fresh.isEmpty()) return
        val at = position.coerceIn(0, items.size)
        items.addAll(at, fresh)
        notifyItemRangeInserted(at, fresh.size)
    }

    /**
     * Stream: after a quick swipe-away, upcoming Shorts of the same channel (between
     * [from] and [from] + [window]) move to the end of the queue.
     */
    fun sinkChannel(from: Int, window: Int, uploaderUrl: String?) {
        if (uploaderUrl.isNullOrBlank() || from >= items.size) return
        val end = minOf(items.size, from + window)
        val sunk = (from until end).filter { items[it].uploaderUrl == uploaderUrl }
        if (sunk.isEmpty()) return
        val moved = sunk.map { items[it] }
        sunk.asReversed().forEach { i ->
            items.removeAt(i)
            notifyItemRemoved(i)
        }
        val start = items.size
        items.addAll(moved)
        notifyItemRangeInserted(start, moved.size)
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val inflater = LayoutInflater.from(parent.context)
        return Holder(ItemShortPageBinding.inflate(inflater, parent, false))
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        val b = holder.b
        b.shortTitle.text = item.name
        b.shortChannelName.text = item.uploaderName
        b.shortThumb.visibility = android.view.View.VISIBLE
        b.shortError.visibility = android.view.View.GONE
        b.shortPauseIcon.visibility = android.view.View.GONE
        b.shortProgress.progress = 0
        CoilHelper.loadHdThumbnail(b.shortThumb, item.url, true, item.thumbnails)
        CoilHelper.loadAvatar(b.shortAvatar, item.uploaderAvatars)
        showLiked(b, callbacks.isLiked(item))

        b.shortRoot.setOnClickListener { callbacks.onTogglePause(holder.bindingAdapterPosition) }
        b.shortActionLike.setOnClickListener { showLiked(b, callbacks.onLike(item)) }
        b.shortActionComments.setOnClickListener { callbacks.onComments(item) }
        b.shortActionShare.setOnClickListener { callbacks.onShare(item) }
        b.shortActionSave.setOnClickListener { callbacks.onSave(item) }
        b.shortActionMore.setOnClickListener { callbacks.onMore(item) }
        b.shortChannel.setOnClickListener { callbacks.onChannel(item) }
    }

    private fun showLiked(b: ItemShortPageBinding, liked: Boolean) {
        val context = b.root.context
        val color = if (liked) {
            ContextCompat.getColor(context, R.color.stream_rose)
        } else {
            ContextCompat.getColor(context, android.R.color.white)
        }
        TextViewCompat.setCompoundDrawableTintList(b.shortActionLike, ColorStateList.valueOf(color))
        b.shortActionLike.setText(
            if (liked) R.string.short_action_liked else R.string.short_action_like
        )
    }

    class Holder(val b: ItemShortPageBinding) : RecyclerView.ViewHolder(b.root)
}
