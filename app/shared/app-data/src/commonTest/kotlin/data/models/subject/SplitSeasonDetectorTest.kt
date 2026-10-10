/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.models.subject

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * 对 [SplitSeasonTestData] 里每个主线条目跑 [SplitSeasonDetector], 结果要与上游记录的服务端输出相同; 末尾几个是本地更保守的情形.
 * 分集数据 (第一集 sort / 正片集数 / 正片中间的特别篇数) 取自 Bangumi 2026-09-22 的数据导出; 测试数据里给了的以它为准.
 */
class SplitSeasonDetectorTest {
    private class Episodes(val firstSort: Int?, val count: Int, val inlineSpecials: Int = 0)

    private val episodes = mapOf(
        // Re:ZERO
        140001 to Episodes(1, 25), 278826 to Episodes(26, 13), 316247 to Episodes(39, 12),
        425998 to Episodes(51, 8), 510728 to Episodes(59, 8), 547888 to Episodes(67, 11), 633836 to Episodes(78, 8),
        // 无职转生: 第三季第 2 部分还没有分集
        277554 to Episodes(1, 11), 325585 to Episodes(12, 12), 373247 to Episodes(0, 13), 444557 to Episodes(13, 12, 1),
        501963 to Episodes(1, 14), 708197 to Episodes(null, 0),
        // 进击的巨人: 完结篇前后篇各一集, sort 都是 1
        217300 to Episodes(38, 12), 263750 to Episodes(50, 10), 285666 to Episodes(60, 16), 331752 to Episodes(76, 12),
        376739 to Episodes(1, 1), 415779 to Episodes(1, 1),
        // 间谍过家家: 第二、三季的 sort 接着第一季后半往下编
        329906 to Episodes(1, 12), 373267 to Episodes(13, 13, 1), 411427 to Episodes(26, 12), 498378 to Episodes(38, 13),
        // 魔法使的新娘
        210864 to Episodes(1, 24, 1), 399820 to Episodes(1, 12), 442523 to Episodes(13, 12),
        // 86
        302189 to Episodes(1, 11, 1), 331887 to Episodes(12, 12, 3),
        // 葬送的芙莉莲: 第二季的 sort 接着第一季
        400602 to Episodes(1, 28), 515759 to Episodes(29, 10),
        // 咒术回战: 三季的 sort 首尾相接
        294993 to Episodes(1, 24), 369304 to Episodes(25, 23, 2), 472741 to Episodes(48, 12),
        // 药屋少女的呢喃
        420628 to Episodes(1, 24), 486347 to Episodes(25, 24), 568244 to Episodes(49, 12), 599893 to Episodes(61, 12),
    )

    private fun SplitSeasonTestData.Series.mainLine() = subjects.map { (id, names) ->
        val e = episodes.getValue(id)
        SplitSeasonDetector.Entry(id, names, e.firstSort, e.count, e.inlineSpecials)
    }

    private fun assertMatchesServer(series: SplitSeasonTestData.Series) {
        val mainLine = series.mainLine()
        for ((id, _) in series.subjects) {
            assertEquals(series.splitSeason(id), SplitSeasonDetector.detect(id, mainLine), "subject $id")
        }
    }

    @Test
    fun `re zero - second half and arc names`() = assertMatchesServer(SplitSeasonTestData.ReZero)

    @Test
    fun `mushoku tensei - part 2 without episodes is not joined`() = assertMatchesServer(SplitSeasonTestData.MushokuTensei)

    @Test
    fun `attack on titan - final chapters are not a split season`() = assertMatchesServer(SplitSeasonTestData.AttackOnTitan)

    @Test
    fun `spy family - next season continuing the sorts is not a part`() = assertMatchesServer(SplitSeasonTestData.SpyFamily)

    @Test
    fun `ancient magus bride`() = assertMatchesServer(SplitSeasonTestData.AncientMagusBride)

    @Test
    fun `eighty six - inline specials`() = assertMatchesServer(SplitSeasonTestData.EightySix)

    @Test
    fun `frieren - second season is not a part`() = assertMatchesServer(SplitSeasonTestData.Frieren)

    @Test
    fun `jujutsu kaisen - season subtitles are not markers`() = assertMatchesServer(SplitSeasonTestData.JujutsuKaisen)

    @Test
    fun `apothecary diaries`() = assertMatchesServer(SplitSeasonTestData.Apothecary)

    @Test
    fun `self missing from the main line`() {
        assertNull(SplitSeasonDetector.detect(1, SplitSeasonTestData.Apothecary.mainLine()))
    }

    @Test
    fun `long running series - arcs continuing the sorts are not a split season`() {
        val onePiece = listOf(
            SplitSeasonDetector.Entry(975, listOf("航海王", "ONE PIECE"), firstSort = 1, episodeCount = 1155, inlineSpecialCount = 22),
            SplitSeasonDetector.Entry(602059, listOf("航海王 埃鲁巴夫篇", "ONE PIECE エルバフ編"), firstSort = 1156, episodeCount = 25),
        )
        assertNull(SplitSeasonDetector.detect(602059, onePiece))
        assertNull(SplitSeasonDetector.detect(975, onePiece))

        // 长的那段不能单独去掉: 后面几段自成一串的话季内序号从 273 数起
        val wushen = listOf(
            SplitSeasonDetector.Entry(286354, listOf("武神主宰"), firstSort = 1, episodeCount = 272),
            SplitSeasonDetector.Entry(466778, listOf("武神主宰 大威篇"), firstSort = 273, episodeCount = 64),
            SplitSeasonDetector.Entry(532407, listOf("武神主宰 秘境篇"), firstSort = 337, episodeCount = 48),
        )
        assertNull(SplitSeasonDetector.detect(532407, wushen))
        assertNull(SplitSeasonDetector.detect(466778, wushen))
    }

    @Test
    fun `season named like another subject of the series is not a split season`() {
        // "银魂." 去掉标点就是 2006 年的 "银魂": 站点上那一页会被当成本季的合并页
        val gintama = listOf(
            SplitSeasonDetector.Entry(1001, listOf("银魂", "銀魂"), firstSort = 1, episodeCount = 201),
            SplitSeasonDetector.Entry(193298, listOf("银魂.", "銀魂."), firstSort = 317, episodeCount = 12),
            SplitSeasonDetector.Entry(218740, listOf("银魂. 走光篇", "銀魂. ポロリ篇"), firstSort = 329, episodeCount = 13),
        )
        assertNull(SplitSeasonDetector.detect(218740, gintama))
        assertNotNull(SplitSeasonDetector.detect(218740, gintama.drop(1)))
    }
}
