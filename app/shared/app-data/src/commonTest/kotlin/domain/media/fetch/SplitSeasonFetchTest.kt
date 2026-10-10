/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.fetch

import me.him188.ani.app.data.models.episode.EpisodeInfo
import me.him188.ani.app.data.models.subject.SplitSeason
import me.him188.ani.app.data.models.subject.SubjectInfo
import me.him188.ani.app.data.models.subject.SubjectSeriesInfo
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.EpisodeType
import me.him188.ani.datasources.api.source.MediaFetchRequest
import kotlin.test.Test
import kotlin.test.assertEquals

class SplitSeasonFetchTest {
    private fun part(id: Int, firstSort: Int, count: Int, inlineSpecials: Int = 0) =
        SplitSeason.Part(id, listOf("$id"), emptyList(), firstSort, count, inlineSpecials)

    /** Re:ZERO 第四季: 丧失篇 sort 67 起 11 集, 夺还篇 sort 78 起 8 集 */
    private val reZeroS4 = listOf(part(547888, 67, 11), part(633836, 78, 8))

    @Test
    fun `second half - the whole season up to its last episode`() {
        assertEquals(19, SplitSeason(reZeroS4, selfIndex = 1, baseNames = emptyList()).seasonEpisodeCount())
    }

    @Test
    fun `first part and non split seasons keep their own numbers only`() {
        assertEquals(0, SplitSeason(reZeroS4, selfIndex = 0, baseNames = emptyList()).seasonEpisodeCount())
        assertEquals(0, (null as SplitSeason?).seasonEpisodeCount())
    }

    @Test
    fun `inline specials and a prologue can take numbers on a merged page`() {
        // 86: 前半 11 集 + 11.5 集, 后半 12 集 + 3 个特别篇
        val eightySix = listOf(part(302189, 1, 11, inlineSpecials = 1), part(331887, 12, 12, inlineSpecials = 3))
        assertEquals(27, SplitSeason(eightySix, selfIndex = 1, baseNames = emptyList()).seasonEpisodeCount())
        // 无职转生 第二季: 前半从第 0 集 (序章) 编起
        val mushoku = listOf(part(373247, 0, 13), part(444557, 13, 12, inlineSpecials = 1))
        assertEquals(27, SplitSeason(mushoku, selfIndex = 1, baseNames = emptyList()).seasonEpisodeCount())
    }

    @Test
    fun `fetch request carries the season numbers of a second half`() {
        val subject = SubjectInfo.Empty.copy(subjectId = 633836, nameCn = "Re：从零开始的异世界生活 第四季 夺还篇")
        val episode = EpisodeInfo(episodeId = 1656858, type = EpisodeType.MainStory, sort = EpisodeSort(78), ep = EpisodeSort(1))
        val seriesInfo = SubjectSeriesInfo.Fallback.copy(splitSeason = SplitSeason(reZeroS4, selfIndex = 1, baseNames = emptyList()))

        val request = MediaFetchRequest.create(subject, episode, listOf(episode), seriesInfo)
        assertEquals(19, request.seasonEpisodeCount)
        assertEquals((1..19).map { EpisodeSort(it) }, request.seasonEpisodeSorts())
        assertEquals(0, MediaFetchRequest.create(subject, episode, listOf(episode)).seasonEpisodeCount)
    }
}
