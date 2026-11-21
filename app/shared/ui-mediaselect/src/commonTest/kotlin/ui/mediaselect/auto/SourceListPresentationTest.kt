/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.mediaselect.auto

import me.him188.ani.app.domain.media.createTestDefaultMedia
import me.him188.ani.app.domain.media.createTestMediaProperties
import me.him188.ani.app.domain.media.selector.MaybeExcludedMedia
import me.him188.ani.app.domain.media.selector.MediaExclusionReason
import me.him188.ani.app.domain.media.selector.TestMatchMetadata
import me.him188.ani.app.ui.mediaselect.bt.BtRowExclusion
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.datasources.api.source.MediaSourceLocation
import me.him188.ani.datasources.api.topic.EpisodeRange
import me.him188.ani.datasources.api.topic.FileSize.Companion.megaBytes
import me.him188.ani.datasources.api.topic.ResourceLocation
import me.him188.ani.utils.platform.annotations.TestOnly
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(TestOnly::class)
class SourceListPresentationTest {
    private fun media(
        id: String,
        sourceId: String = "drive",
        resolution: String = "1080P",
        alliance: String = "drive",
        subtitleLanguageIds: List<String> = listOf("CHS"),
    ): Media = createTestDefaultMedia(
        mediaId = "$sourceId.$id",
        mediaSourceId = sourceId,
        originalUrl = "https://example.com/$id",
        download = ResourceLocation.HttpStreamingFile("https://example.com/$id.mp4"),
        originalTitle = "title $id",
        publishedTime = 0,
        properties = createTestMediaProperties(
            subtitleLanguageIds = subtitleLanguageIds,
            resolution = resolution,
            alliance = alliance,
            size = 100.megaBytes,
            subtitleKind = null,
        ),
        episodeRange = EpisodeRange.single(EpisodeSort(1)),
        location = MediaSourceLocation.Online,
        kind = MediaSourceKind.WEB,
    )

    private fun included(media: Media) = MaybeExcludedMedia.Included(media, TestMatchMetadata)
    private fun excluded(media: Media, reason: MediaExclusionReason) = MaybeExcludedMedia.Excluded(media, reason)

    private val uhd = media("uhd", resolution = "2160P", subtitleLanguageIds = listOf("CHS", "CHT"))
    private val fhd = media("fhd", resolution = "1080P")
    private val mkv = media("mkv", resolution = "2160P")
    private val other = media("other", sourceId = "site", alliance = "line 1")

    @Test
    fun `lists every entry of the source in selector order with exclusions at the end`() {
        val list = projectSourceList(
            listOf(included(uhd), included(other), excluded(mkv, MediaExclusionReason.UnsupportedByPlatformPlayer), included(fhd)),
            mediaSourceId = "drive", selected = fhd, resolution = null, subtitleLanguageId = null, alliance = null,
        )
        assertEquals(listOf(uhd, fhd), list.included.map { it.media })
        assertEquals(listOf(mkv), list.excluded.map { it.media })
        assertEquals(BtRowExclusion.Reason(MediaExclusionReason.UnsupportedByPlatformPlayer), list.excluded.single().exclusion)
        assertTrue(list.included.single { it.media == fhd }.isSelected)
        assertEquals(3, list.total)
    }

    @Test
    fun `filters narrow the rows but available values come from every entry`() {
        val candidates = listOf(included(uhd), included(fhd), excluded(mkv, MediaExclusionReason.UnsupportedByPlatformPlayer))
        val list = projectSourceList(candidates, "drive", null, resolution = "2160P", subtitleLanguageId = null, alliance = null)
        assertEquals(listOf(uhd), list.included.map { it.media })
        assertEquals(listOf(mkv), list.excluded.map { it.media })
        assertEquals(listOf("2160P", "1080P"), list.availableResolutions)
        assertEquals(listOf("CHS", "CHT"), list.availableSubtitleLanguageIds)
        assertEquals(listOf("drive"), list.availableAlliances)

        val cht = projectSourceList(candidates, "drive", null, resolution = null, subtitleLanguageId = "CHT", alliance = null)
        assertEquals(listOf(uhd), cht.included.map { it.media })
        assertTrue(cht.excluded.isEmpty())
    }
}
