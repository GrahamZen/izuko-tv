/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.mediafetch

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import me.him188.ani.app.domain.media.TestMediaList
import me.him188.ani.app.domain.media.fetch.MediaSourceFetchResult
import me.him188.ani.app.domain.media.fetch.MediaSourceFetchState
import me.him188.ani.app.domain.media.selector.MatchMetadata
import me.him188.ani.app.domain.media.selector.MaybeExcludedMedia
import me.him188.ani.app.domain.media.selector.MediaExclusionReason
import me.him188.ani.app.ui.mediaselect.selector.WebSource
import me.him188.ani.app.ui.mediaselect.selector.WebSourceInexact
import me.him188.ani.datasources.api.DefaultMedia
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.source.MediaSourceInfo
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.datasources.api.topic.FileSize
import me.him188.ani.datasources.api.topic.FileSize.Companion.bytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 自动匹配页 (触屏) 补列选择器不列的, 同电视的选源面板: 没完全对上的、全被排除的、暂停了的源, 网盘类每个文件一个芯片, 其余条数, 没有结果的源.
 */
class AutoMatchExtrasTest {
    private val giri = FakeSource("giri")
    private val drive = FakeSource("drive")
    private val succeed = MediaSourceFetchState.Succeed(1)

    private fun media(
        id: String,
        alliance: String,
        source: FakeSource = giri,
        resolution: String = "1080P",
        size: FileSize = FileSize.Unspecified,
        title: String = id,
    ): DefaultMedia {
        val base = TestMediaList.first()
        return base.copy(
            mediaId = id,
            mediaSourceId = source.mediaSourceId,
            originalTitle = title,
            properties = base.properties.copy(alliance = alliance, resolution = resolution, size = size),
        )
    }

    private fun included(
        media: Media,
        subject: MatchMetadata.SubjectMatchKind = MatchMetadata.SubjectMatchKind.EXACT,
        episode: MatchMetadata.EpisodeMatchKind = MatchMetadata.EpisodeMatchKind.EP,
    ) = MaybeExcludedMedia.Included(media, MatchMetadata(subject, episode, 66))

    private fun excluded(media: Media, reason: MediaExclusionReason) = MaybeExcludedMedia.Excluded(media, reason)

    private fun extrasOf(vararg sources: Pair<FakeSource, MediaSourceFetchState>, candidates: List<MaybeExcludedMedia>) =
        autoMatchExtrasOf(sources.toList(), candidates)

    @Test
    fun `fuzzy title results are listed as channels`() {
        val a = media("a", "线路1")
        val b = media("b", "线路2")
        val fuzzy = MatchMetadata.SubjectMatchKind.FUZZY
        val extras = extrasOf(giri to succeed, candidates = listOf(included(a, fuzzy), included(b, fuzzy)))
        val web = extras.sources.single()
        assertEquals(listOf("线路1" to a, "线路2" to b), web.channels.map { it.name to it.original })
        assertEquals(WebSourceInexact(fuzzyTitle = true, episodeUnknown = false), web.inexact)
    }

    @Test
    fun `a source with every result excluded is listed with the reasons`() {
        val a = media("a", "线路1")
        val extras = extrasOf(giri to succeed, candidates = listOf(excluded(a, MediaExclusionReason.SubjectNameMismatch)))
        val web = extras.sources.single()
        assertEquals(listOf(a), web.channels.map { it.original })
        assertEquals(listOf<MediaExclusionReason>(MediaExclusionReason.SubjectNameMismatch), web.excludedReasons)
    }

    @Test
    fun `other episodes and unplayable files are not listed`() {
        val extras = extrasOf(
            giri to succeed,
            candidates = listOf(
                excluded(media("a", "线路1"), MediaExclusionReason.UnsupportedByPlatformPlayer),
                excluded(media("b", "线路1"), MediaExclusionReason.EpisodeMismatch(null)),
            ),
        )
        assertTrue(extras.sources.isEmpty())
        assertEquals(listOf(AutoMatchExtras.EmptySource("giri", "giri")), extras.emptySources)
    }

    @Test
    fun `drive sources get one chip per file`() {
        val gb = 1_500_000_000L.bytes
        val a = media("a", "drive", drive, resolution = "1080P", size = gb)
        val b = media("b", "drive", drive, resolution = "2160P", size = gb)
        val c = media("c", "drive", drive, resolution = "2160P", size = gb, title = "Show / Show.S02E01.HDR.mkv.mp4")
        val extras = extrasOf(drive to succeed, candidates = listOf(included(a), included(b), included(c)))
        // 选择器已经列了这个源: 只换芯片
        assertTrue(extras.sources.isEmpty())
        val chips = extras.fileChannels.getValue("drive")
        assertEquals(listOf(a, b, c), chips.map { it.original })
        // 清晰度与大小撞了的用文件名
        assertEquals("1080P · $gb", chips[0].name)
        assertEquals(listOf("b", "Show.S02E01.HDR.mkv.mp4"), chips.drop(1).map { it.name })
    }

    @Test
    fun `listed sources count the results they do not show`() {
        val extras = extrasOf(
            giri to succeed,
            candidates = listOf(
                included(media("a", "线路1")),
                included(media("b", "线路2"), MatchMetadata.SubjectMatchKind.FUZZY),
                excluded(media("c", "线路3"), MediaExclusionReason.SubjectNameMismatch),
                excluded(media("d", "线路3"), MediaExclusionReason.UnsupportedByPlatformPlayer),
            ),
        )
        assertEquals(mapOf("giri" to 2), extras.hiddenCounts)
    }

    @Test
    fun `paused sources without results can be resumed and searching ones are left alone`() {
        val extras = extrasOf(giri to MediaSourceFetchState.Paused(1), drive to MediaSourceFetchState.Working, candidates = emptyList())
        assertEquals(listOf("giri"), extras.sources.filter { it.isPausedWithoutResults }.map { it.instanceId })
        assertTrue(extras.emptySources.isEmpty())
    }

    @Test
    fun `merging keeps the selector order and does not repeat a source`() {
        val normal = WebSource("giri", "giri", "", "giri", emptyList(), isLoading = true, isError = false, isPreferred = false)
        val extras = AutoMatchExtras(
            sources = listOf(normal.copy(isLoading = false), normal.copy(instanceId = "drive", mediaSourceId = "drive", name = "drive")),
            fileChannels = emptyMap(),
            hiddenCounts = mapOf("giri" to 3),
            emptySources = emptyList(),
        )
        val merged = mergeAutoMatchSources(listOf(normal), extras)
        assertEquals(listOf("giri", "drive"), merged.map { it.instanceId })
        assertEquals(3, merged.first().hiddenCount)
    }

    private class FakeSource(override val instanceId: String) : MediaSourceFetchResult {
        override val mediaSourceId: String = instanceId
        override val sourceInfo: MediaSourceInfo = MediaSourceInfo(displayName = instanceId)
        override val kind: MediaSourceKind = MediaSourceKind.WEB
        override val state = MutableStateFlow<MediaSourceFetchState>(MediaSourceFetchState.Succeed(1))
        override val results: Flow<List<Media>> = MutableStateFlow(emptyList())
        override fun restart() {}
        override fun pause() {}
        override fun enable() {}
    }
}
