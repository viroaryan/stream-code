/*
 * SPDX-FileCopyrightText: 2026 Abhiraj Aryan
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.local.shorts

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/**
 * Stream: standalone full-screen Shorts player, opened when a Shorts card is tapped
 * (on Home, in search results, on a channel). Swipe up/down moves through the given Shorts.
 */
class ShortsPlayerActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            val items = launchItems
            if (items.isNullOrEmpty()) {
                finish()
                return
            }
            supportFragmentManager.beginTransaction()
                .replace(android.R.id.content, ShortsPagerFragment.newListInstance(items, launchIndex))
                .commit()
            launchItems = null
        }
    }

    companion object {
        private var launchItems: List<StreamInfoItem>? = null
        private var launchIndex = 0

        /** Opens the Shorts player on [items], starting with the one at [index]. */
        @JvmStatic
        fun start(context: Context, items: List<StreamInfoItem>, index: Int) {
            if (items.isEmpty()) return
            launchItems = ArrayList(items)
            launchIndex = index.coerceIn(0, items.lastIndex)
            context.startActivity(Intent(context, ShortsPlayerActivity::class.java))
        }
    }
}
