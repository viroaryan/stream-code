/*
 * SPDX-FileCopyrightText: 2026 Abhiraj Aryan
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.fragments

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.schedulers.Schedulers
import org.schabi.newpipe.R
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.databinding.FragmentYouBinding
import org.schabi.newpipe.databinding.ItemYouHistoryBinding
import org.schabi.newpipe.databinding.ItemYouRowBinding
import org.schabi.newpipe.local.history.HistoryRecordManager
import org.schabi.newpipe.util.NavigationHelper
import org.schabi.newpipe.util.image.CoilHelper

/**
 * Stream's "You" tab: a local profile card, the recently watched videos and the
 * library (history, playlists, downloads, channels) plus settings and about.
 * Everything here lives on the phone; there is no account.
 */
class YouFragment : Fragment() {

    private var binding: FragmentYouBinding? = null
    private var historyDisposable: Disposable? = null
    private val historyAdapter = HistoryAdapter()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = FragmentYouBinding.inflate(inflater, container, false)
        .also { binding = it }
        .root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val b = binding!!
        b.youHistoryList.adapter = historyAdapter
        b.youHistoryHeader.setOnClickListener { openHistory() }

        addRow(b, R.drawable.ic_history, R.string.you_item_history) { openHistory() }
        addRow(b, R.drawable.ic_bookmark, R.string.you_item_playlists) {
            NavigationHelper.openBookmarksFragment(activityFm())
        }
        addRow(b, R.drawable.ic_file_download, R.string.you_item_downloads) {
            NavigationHelper.openDownloads(requireActivity())
        }
        addRow(b, R.drawable.ic_tv, R.string.you_item_channels) {
            NavigationHelper.openSubscriptionFragment(activityFm())
        }
        addDivider(b)
        addRow(b, R.drawable.ic_settings, R.string.you_item_settings) {
            NavigationHelper.openSettings(requireContext())
        }
        addRow(b, R.drawable.ic_info_outline, R.string.you_item_about) {
            NavigationHelper.openAbout(requireContext())
        }
    }

    override fun onStart() {
        super.onStart()
        loadHistory()
    }

    override fun onStop() {
        historyDisposable?.dispose()
        historyDisposable = null
        super.onStop()
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    private fun loadHistory() {
        historyDisposable?.dispose()
        historyDisposable = HistoryRecordManager(requireContext())
            .getStreamHistorySortedById()
            .map { entries ->
                // newest first, each video once
                entries.map { it.streamEntity }.distinctBy { it.uid }.take(HISTORY_ROW_SIZE)
            }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                { streams ->
                    historyAdapter.submit(streams)
                    binding?.let {
                        it.youHistoryList.isVisible = streams.isNotEmpty()
                        it.youHistoryEmpty.isVisible = streams.isEmpty()
                    }
                },
                { e -> Log.e(TAG, "Could not load history", e) }
            )
    }

    private fun openHistory() = NavigationHelper.openStatisticFragment(activityFm())

    private fun activityFm() = requireActivity().supportFragmentManager

    private fun addRow(
        b: FragmentYouBinding,
        @DrawableRes icon: Int,
        @StringRes text: Int,
        onClick: () -> Unit
    ) {
        val row = ItemYouRowBinding.inflate(layoutInflater, b.youRows, false)
        row.youRowIcon.setImageResource(icon)
        row.youRowText.setText(text)
        row.root.setOnClickListener { onClick() }
        b.youRows.addView(row.root)
    }

    private fun addDivider(b: FragmentYouBinding) {
        val divider = View(requireContext())
        val typed = android.util.TypedValue()
        requireContext().theme.resolveAttribute(R.attr.separator_color, typed, true)
        divider.setBackgroundColor(typed.data)
        val margin = resources.getDimensionPixelSize(R.dimen.you_divider_margin)
        b.youRows.addView(
            divider,
            ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
                setMargins(0, margin, 0, margin)
            }
        )
    }

    private inner class HistoryAdapter : RecyclerView.Adapter<HistoryAdapter.Holder>() {
        private var items: List<StreamEntity> = emptyList()

        fun submit(newItems: List<StreamEntity>) {
            items = newItems
            @Suppress("NotifyDataSetChanged")
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val inflater = LayoutInflater.from(parent.context)
            return Holder(ItemYouHistoryBinding.inflate(inflater, parent, false))
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val stream = items[position]
            holder.b.youHistoryTitle.text = stream.title
            holder.b.youHistoryChannel.text = stream.uploader
            CoilHelper.loadThumbnail(holder.b.youHistoryThumb, stream.thumbnailUrl)
            holder.b.root.setOnClickListener {
                NavigationHelper.openVideoDetailFragment(
                    requireContext(),
                    activityFm(),
                    stream.serviceId,
                    stream.url,
                    stream.title,
                    null,
                    false
                )
            }
        }

        inner class Holder(val b: ItemYouHistoryBinding) : RecyclerView.ViewHolder(b.root)
    }

    companion object {
        private const val TAG = "YouFragment"
        private const val HISTORY_ROW_SIZE = 15
    }
}
