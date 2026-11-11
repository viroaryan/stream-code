package org.schabi.newpipe.stream

import org.junit.Assume.assumeTrue
import org.junit.Test
import org.schabi.newpipe.DownloaderImpl
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/** Manual network probe: run with env STREAM_PROBE=1. Not part of the normal test run. */
class ShortsDiscoveryProbe {
    @Test
    fun probe() {
        assumeTrue(System.getenv("STREAM_PROBE") == "1")
        NewPipe.init(DownloaderImpl.init(null), Localization("en", "IN"), ContentCountry("IN"))
        val yt = ServiceList.YouTube
        val handler = yt.searchQHFactory.fromQuery("#shorts", listOf("all"), "")
        val info = SearchInfo.getInfo(yt, handler)
        println("PROBE search items=${info.relatedItems.size} errors=${info.errors.size}")
        info.errors.take(3).forEach { println("PROBE   err: ${it.javaClass.simpleName}: ${it.message?.take(200)}") }

        for (h in listOf("MrBeast", "CarryMinati", "BBKiVines", "TechnicalGuruji", "dhruvrathee", "tseries", "AshishChanchlani", "KhabyLame", "zachking", "FactTechz", "SandeepSeminars", "triggeredinsaan")) {
            val url = "https://www.youtube.com/@$h"
            try {
                val ch = ChannelInfo.getInfo(yt, url)
                val tab = ch.tabs.firstOrNull { it.contentFilters.contains(ChannelTabs.SHORTS) }
                if (tab == null) {
                    println("PROBE $url no shorts tab")
                    continue
                }
                val ti = ChannelTabInfo.getInfo(yt, tab)
                val s = ti.relatedItems.filterIsInstance<StreamInfoItem>()
                println("PROBE $url shorts=${s.size} short-flag=${s.count { it.isShortFormContent }} errors=${ti.errors.size}")
            } catch (e: Exception) {
                println("PROBE $url failed: ${e.javaClass.simpleName}: ${e.message?.take(200)}")
            }
        }
    }
}
