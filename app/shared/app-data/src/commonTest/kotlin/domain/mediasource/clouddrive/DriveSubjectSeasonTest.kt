/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.clouddrive

import me.him188.ani.app.data.network.SeriesGraphTable
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.source.MediaFetchRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DriveSubjectSeasonTest {
    private fun request(vararg names: String) = MediaFetchRequest(
        subjectId = "1",
        episodeId = "1",
        subjectNames = names.toList(),
        episodeSort = EpisodeSort(1),
        episodeName = "",
    )

    private fun tmdb(season: Int, vararg more: Pair<Int, Int>) =
        (listOf(season to 1) + more).associateWith { EpisodeSort(it.second) }

    @Test
    fun `a season written in the name wins`() {
        val season = assertNotNull(DriveSubjectSeason.of(request("小书痴的下克上 第四季"), tmdb(2), seriesSeason = 3))
        assertEquals(4, season.number)
        assertEquals(true, season.declared)
    }

    @Test
    fun `a TMDB season after the first is inferred`() {
        val season = assertNotNull(DriveSubjectSeason.of(request("赛博朋克：边缘行者 2"), tmdb(2, 0 to 3), seriesSeason = null))
        assertEquals(2, season.number)
        assertEquals(false, season.declared)
    }

    @Test
    fun `sequels TMDB numbers on from season 1 fall back to the series graph`() {
        // 浪客剑心 京都动乱: TMDB 记成 S1E25 起
        assertEquals(2, DriveSubjectSeason.of(request("浪客剑心 京都动乱"), tmdb(1), seriesSeason = 2)?.number)
        assertNull(DriveSubjectSeason.of(request("间谍过家家 第2部分"), tmdb(1), seriesSeason = null))
        // 跨了两季的说明不了是哪一季
        assertNull(DriveSubjectSeason.of(request("某番"), tmdb(1, 2 to 1), seriesSeason = null))
    }

    @Test
    fun `the series graph alone is not used without a TMDB episode map`() {
        assertNull(DriveSubjectSeason.of(request("鬼灭之刃 柱合会议 蝶屋敷篇"), tmdbEpisodes = null, seriesSeason = 4))
    }

    /**
     * 浪客剑心 (2023) → 京都动乱 (接着编号, 名字没有分段标记); 间谍过家家 → 第2部分 (拆出来的后半);
     * 鬼灭之刃 浅草篇 → 鼓屋敷篇 → 那田蜘蛛山篇 (篇章没写季); 小书痴 第三季 → 领主的养女; TV 前传之间夹着剧场版.
     */
    private fun table() = assertNotNull(
        SeriesGraphTable.parse(
            listOf(
                "# bangumi-series-graph v1 max_id=500000 dump=dump-2026-10-20.210332Z.zip",
                "100\tるろうに剣心 －明治剣客浪漫譚－\t浪客剑心 －明治剑客浪漫谭－\t2023-07-07\t24\tTV\t\t\t101\t",
                "101\tるろうに剣心 －明治剣客浪漫譚－ 京都動乱\t浪客剑心 －明治剑客浪漫谭－ 京都动乱\t2024-10-03\t23\tTV\t\t100\t\t25",
                "200\tSPY×FAMILY\t间谍过家家\t2022-04-09\t12\tTV\t\t\t201\t",
                "201\tSPY×FAMILY 第2クール\t间谍过家家 第2部分\t2022-10-01\t13\tTV\t\t200\t\t13",
                "300\t鬼滅の刃 浅草編\t鬼灭之刃 浅草篇\t2019-04-06\t7\tTV\t\t\t301\t",
                "301\t鬼滅の刃 鼓屋敷編\t鬼灭之刃 鼓屋敷篇\t2019-05-18\t4\tTV\t\t300\t302\t",
                "302\t鬼滅の刃 那田蜘蛛山編\t鬼灭之刃 那田蜘蛛山篇\t2019-06-15\t6\tTV\t\t301\t\t",
                "400\t本好きの下剋上 第三期\t小书痴的下克上 第三季\t2022-04-11\t10\tTV\t\t\t401\t",
                "401\t本好きの下剋上 領主の養女\t小书痴的下克上 领主的养女\t2026-04-04\t12\tTV\t\t400,402\t\t",
                "402\t本好きの下剋上 劇場版\t\t2025-01-01\t1\t剧场版\t\t400\t401\t",
                "500\tWORK\t独立作品\t2020-01-01\t12\tTV\t\t\t\t",
            ).joinToString("\n").encodeToByteArray(),
        ),
    )

    @Test
    fun `series season follows the nearest TV prequel`() {
        val table = table()
        assertEquals(2, seriesSeasonOf(101, table), "第一部的下一季")
        assertEquals(4, seriesSeasonOf(401, table), "前传写明第三季, 剧场版不算")
        assertNull(seriesSeasonOf(201, table), "拆出来的后半与前传同一季")
        assertNull(seriesSeasonOf(302, table), "前传的季也是推出来的")
        assertNull(seriesSeasonOf(100, table), "系列第一部")
        assertNull(seriesSeasonOf(500, table), "没有前传")
        assertNull(seriesSeasonOf(402, table), "不是 TV")
    }
}
