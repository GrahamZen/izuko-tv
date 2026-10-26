/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.player.extension

import me.him188.ani.app.domain.media.TestMediaList
import me.him188.ani.app.domain.media.selector.MaybeExcludedMedia
import me.him188.ani.app.domain.media.selector.MediaExclusionReason
import me.him188.ani.app.domain.media.selector.TestMatchMetadata
import me.him188.ani.datasources.api.DefaultMedia
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.topic.EpisodeRange
import me.him188.ani.utils.platform.annotations.TestOnly
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 下一集预热找下一集: 同一个源、同一条线路、集数加一 (这一轮里是「集数不对」被排除的). */
@OptIn(TestOnly::class)
class NextEpisodeWarmupTest {
    private fun media(id: String, sort: String, source: String = "giri", alliance: String = "简中"): DefaultMedia {
        val base = TestMediaList.first()
        return base.copy(
            mediaId = id,
            mediaSourceId = source,
            episodeRange = EpisodeRange.single(sort),
            properties = base.properties.copy(alliance = alliance),
        )
    }

    private fun otherEpisode(media: DefaultMedia) = MaybeExcludedMedia.Excluded(media, MediaExclusionReason.EpisodeMismatch(null))

    @Test
    fun `the next episode is the same source and line one episode later`() {
        val current = media("12", "12")
        val candidates = listOf(
            MaybeExcludedMedia.Included(current, TestMatchMetadata),
            otherEpisode(media("13-other-line", "13", alliance = "繁中")),
            otherEpisode(media("13-other-source", "13", source = "other")),
            otherEpisode(media("14", "14")),
            otherEpisode(media("13", "13")),
        )
        assertEquals("13", nextEpisodeMediaOf(current, EpisodeSort(13), candidates)?.mediaId)
    }

    @Test
    fun `results excluded for other reasons are not warmed`() {
        val current = media("12", "12")
        val candidates = listOf(MaybeExcludedMedia.Excluded(media("13", "13"), MediaExclusionReason.UnsupportedByPlatformPlayer))
        assertNull(nextEpisodeMediaOf(current, EpisodeSort(13), candidates))
    }

    @Test
    fun `only whole episodes have a next one`() {
        assertEquals(EpisodeSort(13), nextEpisodeSortOf(media("12", "12")))
        assertNull(nextEpisodeSortOf(media("12.5", "12.5")))
        assertNull(nextEpisodeSortOf(media("sp", "SP1")))
    }

    @Test
    fun `only whole files over http are warmed`() {
        assertTrue(isWholeFileUrl("https://cdn.example.com/a/13.mp4?t=1"))
        assertFalse(isWholeFileUrl("https://cdn.example.com/a/index.m3u8?t=1"))
        assertFalse(isWholeFileUrl("file:///a/13.mp4"))
    }
}
