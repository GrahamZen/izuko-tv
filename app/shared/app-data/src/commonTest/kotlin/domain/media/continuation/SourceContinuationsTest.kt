/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.continuation

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.domain.media.createTestDefaultMedia
import me.him188.ani.app.domain.media.createTestMediaProperties
import me.him188.ani.app.domain.media.selector.testFramework.TestMediaSource
import me.him188.ani.app.domain.mediasource.instance.createTestMediaSourceInstance
import me.him188.ani.datasources.api.DefaultMedia
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.paging.SinglePagePagedSource
import me.him188.ani.datasources.api.source.MatchKind
import me.him188.ani.datasources.api.source.MediaFetchRequest
import me.him188.ani.datasources.api.source.MediaMatch
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.datasources.api.source.MediaSourceLocation
import me.him188.ani.datasources.api.topic.EpisodeRange
import me.him188.ani.datasources.api.topic.ResourceLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourceContinuationsTest {
    private fun web(
        id: String,
        episode: Int,
        source: String = "site",
        subjectName: String = "赛博朋克：边缘行者 第二季",
        channel: String = "线路2",
    ): DefaultMedia = createTestDefaultMedia(
        mediaId = "$source.$id",
        mediaSourceId = source,
        originalUrl = "https://site.test/play/$id",
        download = ResourceLocation.WebVideo("https://site.test/play/$id"),
        originalTitle = "$subjectName $episode",
        publishedTime = 0,
        properties = createTestMediaProperties(subjectName = subjectName, alliance = channel),
        episodeRange = EpisodeRange.single(EpisodeSort(episode)),
        location = MediaSourceLocation.Online,
        kind = MediaSourceKind.WEB,
    )

    private fun torrent(
        id: String,
        episodes: EpisodeRange,
        alliance: String = "字幕组A",
        resolution: String = "1080P",
        languages: List<String> = listOf("CHS", "CHT"),
    ): DefaultMedia = createTestDefaultMedia(
        mediaId = "bt.$id",
        mediaSourceId = "bt",
        originalUrl = "https://bt.test/$id",
        download = ResourceLocation.MagnetLink("magnet:?xt=urn:btih:$id"),
        originalTitle = "[$alliance] 番 $id",
        publishedTime = 0,
        properties = createTestMediaProperties(alliance = alliance, resolution = resolution, subtitleLanguageIds = languages),
        episodeRange = episodes,
        location = MediaSourceLocation.Online,
        kind = MediaSourceKind.BitTorrent,
    )

    private fun request(episode: Int) = MediaFetchRequest(
        subjectId = "1", episodeId = "$episode", subjectNames = listOf("番"), episodeSort = EpisodeSort(episode), episodeName = "",
    )

    @Test
    fun `web resources continue on the same source - subject page and channel`() = runTest {
        val continuation = WebSourceContinuation(flowOf(emptyList()))
        val lineage = assertIs<WebSourceLineage>(continuation.lineageOf(web("e5", 5)))
        assertEquals(WebSourceLineage("site", "赛博朋克：边缘行者 第二季", "线路2"), lineage)

        assertTrue(continuation.continues(lineage, web("e6", 6)))
        assertFalse(continuation.continues(lineage, web("e6b", 6, channel = "线路1")))
        assertFalse(continuation.continues(lineage, web("e6c", 6, subjectName = "赛博朋克：边缘行者")))
        assertFalse(continuation.continues(lineage, web("e6d", 6, source = "other")))
    }

    @Test
    fun `web lineage not among the candidates - the source searches again`() = runTest {
        var fetches = 0
        val source = TestMediaSource("site", mediaSourceId = "site", kind = MediaSourceKind.WEB) {
            fetches++
            SinglePagePagedSource { listOf(web("e6b", 6, channel = "线路1"), web("e6", 6)).map { MediaMatch(it, MatchKind.FUZZY) }.asFlow() }
        }
        val continuation = WebSourceContinuation(flowOf(listOf(createTestMediaSourceInstance(source))))
        val lineage = WebSourceLineage("site", "赛博朋克：边缘行者 第二季", "线路2")

        assertEquals("site.e6", assertIs<LineageResolution.Found>(continuation.resolve(lineage, request(6))).media.mediaId)
        assertEquals(LineageResolution.NotFound, continuation.resolve(lineage, request(7)))
        assertEquals(2, fetches)
        // 源停用了: 不查, 线索留着
        val disabled = WebSourceContinuation(flowOf(listOf(createTestMediaSourceInstance(source, isEnabled = false))))
        assertEquals(LineageResolution.NotFound, disabled.resolve(lineage, request(6)))
        assertEquals(2, fetches)
    }

    @Test
    fun `single torrents continue with the same group - resolution and subtitles`() = runTest {
        val continuation = TorrentContinuation(flowOf(emptyList()))
        val lineage = assertIs<TorrentLineage>(continuation.lineageOf(torrent("e5", EpisodeRange.single(EpisodeSort(5)))))
        assertNull(lineage.packMediaId)

        assertTrue(continuation.continues(lineage, torrent("e6", EpisodeRange.single(EpisodeSort(6)), languages = listOf("CHT", "CHS"))))
        assertFalse(continuation.continues(lineage, torrent("e6b", EpisodeRange.single(EpisodeSort(6)), resolution = "720P")))
        assertFalse(continuation.continues(lineage, torrent("e6c", EpisodeRange.single(EpisodeSort(6)), alliance = "字幕组B")))
        assertFalse(continuation.continues(lineage, torrent("e6d", EpisodeRange.single(EpisodeSort(6)), languages = listOf("CHS"))))
        // 认不出字幕组: 记不了
        assertNull(continuation.lineageOf(torrent("x", EpisodeRange.single(EpisodeSort(5)), alliance = "")))
    }

    @Test
    fun `a season pack continues in the same torrent`() = runTest {
        val continuation = TorrentContinuation(flowOf(emptyList()))
        val pack = torrent("pack", EpisodeRange.range(EpisodeSort(1), EpisodeSort(12)))
        val lineage = assertIs<TorrentLineage>(continuation.lineageOf(pack))
        assertEquals("bt.pack", lineage.packMediaId)
        // 同一个种子里的另一集 (候选是同一条资源)
        assertTrue(continuation.continues(lineage, pack))
        // 只写了季的整季包也算
        assertEquals("bt.season", (continuation.lineageOf(torrent("season", EpisodeRange.season(2))) as TorrentLineage).packMediaId)
    }
}
