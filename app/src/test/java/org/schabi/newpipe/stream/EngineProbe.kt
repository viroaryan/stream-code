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
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/** Manual network probe of the video engine: run with env STREAM_PROBE=1. */
class EngineProbe {
    @Test
    fun probe() {
        assumeTrue(System.getenv("STREAM_PROBE") == "1")
        NewPipe.init(DownloaderImpl.init(null), Localization("en", "IN"), ContentCountry("IN"))
        val yt = ServiceList.YouTube

        for (url in listOf(
            "https://www.youtube.com/watch?v=aqz-KE-bpKQ",
            "https://www.youtube.com/watch?v=jNQXAC9IVRw"
        )) {
            try {
                val t0 = System.currentTimeMillis()
                val info = StreamInfo.getInfo(yt, url)
                val ms = System.currentTimeMillis() - t0
                println("PROBE video ${info.name} in ${ms}ms")
                println("PROBE   muxed: " + info.videoStreams.map { it.resolution })
                println("PROBE   video-only: " + info.videoOnlyStreams.map { it.resolution }.distinct())
                println("PROBE   audio: ${info.audioStreams.size} dash=${info.dashMpdUrl.isNotEmpty()} hls=${info.hlsUrl.isNotEmpty()}")
                println("PROBE   related=${info.relatedItems.size} thumbs=" + info.thumbnails.map { "${it.width}x${it.height}" })
            } catch (e: Exception) {
                println("PROBE video $url failed: ${e.javaClass.simpleName}: ${e.message?.take(200)}")
            }
        }

        for (h in listOf("MrBeast", "TechnicalGuruji")) {
            try {
                val ch = ChannelInfo.getInfo(yt, "https://www.youtube.com/@$h")
                println("PROBE channel $h tabs=" + ch.tabs.map { it.contentFilters.firstOrNull() })
                for (name in listOf(ChannelTabs.VIDEOS, ChannelTabs.SHORTS)) {
                    val tab = ch.tabs.firstOrNull { it.contentFilters.contains(name) } ?: continue
                    val ti = ChannelTabInfo.getInfo(yt, tab)
                    val items = ti.relatedItems.filterIsInstance<StreamInfoItem>()
                    println(
                        "PROBE   $name items=${items.size} errors=${ti.errors.size} " +
                            "thumb=" + items.firstOrNull()?.thumbnails?.map { "${it.width}x${it.height}" }
                    )
                }
            } catch (e: Exception) {
                println("PROBE channel $h failed: ${e.javaClass.simpleName}: ${e.message?.take(200)}")
            }
        }
    }
}
