/*
 * SPDX-FileCopyrightText: 2026 Abhiraj Aryan
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.local.shorts

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import org.schabi.newpipe.R
import org.schabi.newpipe.databinding.ListStreamItemBinding
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.util.Localization
import org.schabi.newpipe.util.image.CoilHelper

/**
 * Stream: portrait Shorts cards (list_stream_short_item) for the Shorts tab grid and the
 * Home Shorts shelf.
 *
 * @param fixedWidthPx card width for horizontal shelves, or 0 to fill the grid cell
 */
class ShortsAdapter(
    private val fixedWidthPx: Int = 0,
    private val onClick: (items: List<StreamInfoItem>, position: Int) -> Unit,
    private val onLongClick: (StreamInfoItem) -> Unit
) : RecyclerView.Adapter<ShortsAdapter.Holder>() {

    private var items: List<StreamInfoItem> = emptyList()

    fun submit(newItems: List<StreamInfoItem>) {
        items = newItems
        @Suppress("NotifyDataSetChanged")
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.list_stream_short_item, parent, false)
        if (fixedWidthPx > 0) {
            view.layoutParams = view.layoutParams.apply { width = fixedWidthPx }
        }
        return Holder(ListStreamItemBinding.bind(view))
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        val b = holder.b
        val context = b.root.context
        b.itemVideoTitleView.text = item.name
        b.itemUploaderView.text = item.uploaderName
        b.itemAdditionalDetails.text =
            if (item.viewCount >= 0) Localization.shortViewCount(context, item.viewCount) else ""
        b.itemDurationView.visibility = View.GONE
        b.itemProgressView.visibility = View.GONE
        CoilHelper.loadThumbnail(b.itemThumbnailView, item.thumbnails)
        b.root.setOnClickListener { onClick(items, holder.bindingAdapterPosition) }
        b.root.setOnLongClickListener {
            onLongClick(item)
            true
        }
    }

    class Holder(val b: ListStreamItemBinding) : RecyclerView.ViewHolder(b.root)
}
