/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.danmaku

import me.him188.ani.app.data.models.subject.SplitSeasonTestData.ReZero
import me.him188.ani.danmaku.api.provider.DanmakuFetchRequest
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.PackedDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.minutes

class DanmakuSeriesHintsTest {
    /** Re：从零开始的异世界生活 第四季: 丧失篇 sort 67~77 (11 集), 夺还篇 78~85 (8 集). */
    @Test
    fun `season numbering of split season parts`() {
        val takeBack = assertNotNull(ReZero.splitSeason(633836).danmakuSeasonNumbering(EpisodeSort(84)))
        assertEquals(18, takeBack.seasonEpisode)
        assertEquals(8, takeBack.partEpisodeCount)
        val loss = assertNotNull(ReZero.splitSeason(547888).danmakuSeasonNumbering(EpisodeSort(70)))
        assertEquals(4, loss.seasonEpisode)
        assertEquals(11, loss.partEpisodeCount)
    }

    @Test
    fun `no season numbering outside split seasons or for specials`() {
        assertNull(null.danmakuSeasonNumbering(EpisodeSort(1)))
        assertNull(ReZero.splitSeason(633836).danmakuSeasonNumbering(EpisodeSort("SP1")))
        assertNull(ReZero.splitSeason(633836).danmakuSeasonNumbering(EpisodeSort("78.5")))
    }

    @Test
    fun `with anidb id keeps the other fields`() {
        val request = DanmakuFetchRequest(
            subjectId = 633836, subjectPrimaryName = "夺还篇", subjectNames = listOf("夺还篇"),
            subjectPublishDate = PackedDate.Invalid, episodeId = 1656864, episodeSort = EpisodeSort(84),
            episodeEp = EpisodeSort(7), episodeName = "拉姆", episodeNames = listOf("ラム", "拉姆"),
            filename = "a.mkv", fileHash = null, fileSize = 3, videoDuration = 24.minutes,
            seasonNumbering = ReZero.splitSeason(633836).danmakuSeasonNumbering(EpisodeSort(84)),
        )
        assertSame(request, request.withAnidbId(null))
        val filled = request.withAnidbId(19242)
        assertEquals(19242, filled.anidbId)
        assertEquals(listOf("ラム", "拉姆"), filled.episodeNames)
        assertEquals("a.mkv", filled.filename)
        assertEquals(18, filled.seasonNumbering?.seasonEpisode)
        assertEquals(EpisodeSort(7), filled.episodeEp)
    }
}
