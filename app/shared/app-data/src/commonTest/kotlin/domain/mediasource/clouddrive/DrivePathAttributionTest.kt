/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.clouddrive

import kotlinx.coroutines.test.runTest
import me.him188.ani.app.domain.mediasource.clouddrive.DriveSeriesTestData.calamity
import me.him188.ani.app.domain.mediasource.clouddrive.DriveSeriesTestData.conflict
import me.him188.ani.app.domain.mediasource.clouddrive.DriveSeriesTestData.counterattack
import me.him188.ani.app.domain.mediasource.clouddrive.DriveSeriesTestData.finalSeason
import me.him188.ani.app.domain.mediasource.clouddrive.DriveSeriesTestData.kyoto
import me.him188.ani.app.domain.mediasource.clouddrive.DriveSeriesTestData.scienceFuture2
import me.him188.ani.app.domain.mediasource.clouddrive.DriveSeriesTestData.separation
import me.him188.ani.app.domain.mediasource.clouddrive.DriveSeriesTestData.soloLeveling2
import me.him188.ani.app.domain.mediasource.clouddrive.DriveSeriesTestData.table
import me.him188.ani.app.data.network.SeriesGraphTable
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.source.MediaFetchNameHints
import me.him188.ani.datasources.api.source.MediaFetchRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 网盘里的视频按路径认部 ([DrivePathAttribution]), 经 [DriveSubjectMatcher.matchEpisodes] 看认出的集. 路径取自分享站上的真实分享 (2026-10).
 */
class DrivePathAttributionTest {
    /** 分享 [siteTitle] 里 [folders] (从外到里) 下的文件 [fileName] 认成 [request] 的哪一集; 不是这一部的为 null. */
    private fun episodeOf(
        request: MediaFetchRequest,
        siteTitle: String,
        folders: List<String>,
        fileName: String,
        tmdbEpisodes: Map<Pair<Int, Int>, EpisodeSort>? = null,
    ): Int? {
        val candidate = DriveSubjectMatcher.Candidate(video("f", fileName), listOf(siteTitle) + folders)
        val identity = DriveSubjectIdentity.of(request, table)
        return DriveSubjectMatcher.matchEpisodes(request, listOf(candidate), tmdbEpisodes, identity = identity)
            .singleOrNull()?.episode?.number?.toInt()
    }

    /** 对应表: 本篇从 TMDB 第 [season] 季第 [firstEpisode] 集起一对一接续. */
    private fun tmdb(request: MediaFetchRequest, season: Int, firstEpisode: Int) =
        request.episodes.withIndex().associate { (index, episode) -> (season to firstEpisode + index) to episode.sort }

    @Test
    fun `the nearest name that names a part decides`() {
        val title = "死神 千年血战篇-相克谭-.更至28集【附全系列】"
        assertEquals(
            27,
            episodeOf(conflict, title, listOf("S 死神 千年血战篇-相克谭-[2024][附系列]"), "[ANi] BLEACH 死神 千年血戰篇-相剋譚- - 27 [1080P].mp4"),
        )
        // 分享标题是这一部, 文件夹是别的部
        assertNull(episodeOf(conflict, title, listOf("S 死神 千年血战篇-祸进谭-"), "01.mp4"))
    }

    @Test
    fun `a share named after this part with earlier parts attached keeps only what is this part`() {
        val title = "死神 千年血战篇 -祸进谭- BLEACH 千年血戦篇 -禍進譚- (2026) 4K/1080p 更新EP48【七月新番剧】"
        // 「1-40」里附的是前几部: 第 1 集是第一部分的
        assertNull(episodeOf(calamity, title, listOf("1-40"), "1080P H EP 01.mp4"))
        assertNull(episodeOf(calamity, "死神千年血战篇-祸进谭-动漫", listOf("S01"), "S01E12.mp4"))
        // 文件名或没写编号的文件夹之上是这一部的标题: 照收
        assertEquals(41, episodeOf(calamity, title, listOf("BLEACH 死神"), "[BeanSub&FZSD][BLEACH_Sennen_Kessen-hen][41][CHS][1080P].mp4"))
        assertEquals(41, episodeOf(calamity, title, listOf("1-40"), "[ANi] BLEACH 死神 千年血戰篇-禍進譚- - 41 [1080P].mp4"))
    }

    @Test
    fun `numbers continued across parts count only under the series name`() {
        // 请求带着系列名: 「死神」也在里面, 它是 2004 年 TV 版, 集号与相克谭的连续编号重叠
        val conflict = conflict.copy(nameHints = MediaFetchNameHints(seriesNames = listOf("死神 千年血战篇", "BLEACH 千年血戦篇", "死神", "BLEACH")))
        val title = "死神 千年血战篇 -相克谭-"
        assertEquals(27, episodeOf(conflict, title, listOf("死神 千年血战篇", "1-30"), "027.mkv"))
        // 附着的 2004 年 TV 版也从 1 编, 第 27、33 集与相克谭的连续编号对得上
        assertNull(episodeOf(conflict, title, listOf(title, "S.死神系列动漫 TV版和剧场版 国语 【完结】", "1-30"), "027.mkv"))
        assertNull(episodeOf(conflict, title, listOf(title, "S.死神系列动漫 TV版和剧场版 国语 【完结】", "31-60"), "033.mkv"))
    }

    @Test
    fun `a folder of a continuously numbered collection keeps numbers that are also part-local`() {
        // 我推的孩子第二季 (连续集号 12~24, 本季 1~13) 与第一季放在同一个文件夹里从 1 编到 24: 有 14 以后的, 这个文件夹按连续编号排
        val title = "W 4k 我推的孩子"
        val folders = listOf("600-700", "W 4k 我推的孩子")
        val second = DriveSeriesTestData.request(443428, listOf("【我推的孩子】 第二季"), 12, 13)
            .copy(nameHints = MediaFetchNameHints(seriesNames = listOf("【我推的孩子】")))
        val candidates = listOf(5, 12, 20).map { DriveSubjectMatcher.Candidate(video("e$it", "[Ygm] Oshi no Ko [$it].mkv"), listOf(title) + folders) }
        val matched = DriveSubjectMatcher.matchEpisodes(second, candidates, identity = DriveSubjectIdentity.of(second, table))
        assertEquals(listOf(12, 20), matched.map { it.episode.number?.toInt() })
    }

    @Test
    fun `episodes numbered within the shared season of a split season map to this part`() {
        // Re:Zero 第二季前半 (连续集号 26~38): 对应表把整部算一季 (S1E26 起), 网盘按季写 `S02E05`; 第二季后半 (39~50) 也在同一季里
        val table = SeriesGraphTable.parse(
            listOf(
                "# bangumi-series-graph v1 max_id=714439 dump=dump-2026-10-20.210332Z.zip | 列: …",
                "140001\tRe:ゼロから始める異世界生活\tRe：从零开始的异世界生活\t2016-04-03\t25\tTV\t\t\t278826\t\t",
                "278826\tRe:ゼロから始める異世界生活 2nd season\tRe：从零开始的异世界生活 第二季\t2020-07-08\t13\tTV\t\t140001\t316247\t26\t",
                "316247\tRe:ゼロから始める異世界生活 2nd season 後半クール\tRe：从零开始的异世界生活 第二季 后半部分\t2021-01-06\t12\tTV\t\t278826\t\t39\t",
            ).joinToString("\n").encodeToByteArray(),
        )!!
        val firstHalf = DriveSeriesTestData.request(278826, listOf("Re：从零开始的异世界生活 第二季"), 26, 13)
        val identity = DriveSubjectIdentity.of(firstHalf, table)
        fun episodeOf(fileName: String) = DriveSubjectMatcher.matchEpisodes(
            firstHalf,
            listOf(DriveSubjectMatcher.Candidate(video("f", fileName), listOf("《Re：从零开始的异世界生活》(2020)[第2季][全25集]", "18826"))),
            tmdb(firstHalf, season = 1, firstEpisode = 26),
            identity = identity,
        ).singleOrNull()?.episode?.number?.toInt()
        assertEquals(30, episodeOf("Re.ZERO.Starting.Life.in.Another.World.S02E05.2020.1080p.mkv"))
        // 后半的
        assertNull(episodeOf("Re.ZERO.Starting.Life.in.Another.World.S02E20.2020.1080p.mkv"))
        // 特别篇不按连续编号认
        assertNull(episodeOf("Re.ZERO.Starting.Life.in.Another.World.2020.S00E31.mkv"))
    }

    @Test
    fun `a folder named after this part does not vouch for a season folder under it`() {
        val title = "《浪客剑心 明治剑客浪漫谭 京都动乱第二季 更46》"
        val folder = "[内嵌简日][附第1季]（浪客剑心 明治剑客浪漫谭 京都动乱；第二季）るろうに剣心 －明治剣客浪漫譚－ 京都動乱(2024)[更至46／47集]"
        // 附着的第一季
        assertNull(episodeOf(kyoto, title, listOf(folder, "[内嵌简日]第一季[24集全]"), "[orion origin] Rurouni Kenshin：Meiji Kenkaku Romantan [01] [1080p].mp4"))
        // 这一部自己的, 按跨部连续编号是第 25 集
        assertEquals(25, episodeOf(kyoto, title, listOf(folder), "[orion origin] Rurouni Kenshin：Meiji Kenkaku Romantan - Kyoto Douran [25] [1080p].mp4"))
    }

    @Test
    fun `a later season named after the first season with the season written is this part`() {
        // 弦音第二季: 分享与文件夹拿第一季的副标题加季起名
        val table = SeriesGraphTable.parse(
            listOf(
                "# bangumi-series-graph v1 max_id=714439 dump=dump-2026-10-20.210332Z.zip | 列: …",
                "228378\tツルネ ―風舞高校弓道部―\t弦音 -风舞高中弓道部-\t2018-10-22\t13\tTV\t\t\t396988\t\t",
                "396988\tツルネ －つながりの一射－\t弦音 -连结的一射-\t2023-01-04\t13\tTV\t\t228378\t\t\t",
            ).joinToString("\n").encodeToByteArray(),
        )!!
        val second = DriveSeriesTestData.request(396988, listOf("弦音 -连结的一射-", "ツルネ －つながりの一射－"), 1, 13)
        val tmdb = tmdb(second, season = 2, firstEpisode = 1)
        fun episodeOf(folders: List<String>, fileName: String) = DriveSubjectMatcher.matchEpisodes(
            second, listOf(DriveSubjectMatcher.Candidate(video("f", fileName), folders)), tmdb, identity = DriveSubjectIdentity.of(second, table),
        ).singleOrNull()?.episode?.number?.toInt()
        val title = "弦音 -风舞高中弓道部- 第二季 ツルネ －つながりの一射－ (2023)"
        assertEquals(10, episodeOf(listOf(title), "弦音 -风舞高中弓道部-.Tsurune.S02E10.2023.1080p.WEB-DL.mkv"))
        assertEquals(12, episodeOf(listOf(title, "2023弦音-风舞高中弓道部-第二季"), "12.mp4"))
        // 第一季的
        assertNull(episodeOf(listOf("弦音 -风舞高中弓道部-"), "弦音 -风舞高中弓道部-.Tsurune.S01E10.2018.1080p.WEB-DL.mkv"))
    }

    @Test
    fun `a folder of the season named in this part names this part`() {
        val title = "我独自升级 第二季 -起于暗影- (2025) 1080P"
        assertEquals(1, episodeOf(soloLeveling2, title, listOf("2025奇幻动画剧《我独自升级 第2季》"), "01.mp4"))
        // 别的季照样隔开
        assertNull(episodeOf(soloLeveling2, title, listOf("我独自升级 第1季"), "01.mp4"))
        // 只写了系列名、不写季的从 01 编起, 分不清是哪一季
        assertNull(episodeOf(soloLeveling2, "我独自升级", emptyList(), "01.mp4"))
    }

    @Test
    fun `a season split into parts keeps its season folder separating numbering`() {
        val title = "Re：从零开始的异世界生活 第三季 反击篇"
        // 「第3季」里袭击篇与反击篇都在, 01 是袭击篇的
        assertNull(episodeOf(counterattack, title, listOf("第3季"), "01.mp4"))
        // 文件夹点了系列名、按跨部连续编号是反击篇的照收
        assertEquals(59, episodeOf(counterattack, title, listOf("Re：从零开始的异世界生活 第三季"), "59.mp4"))
    }

    @Test
    fun `in a share of several parts only numbers this part alone has are kept`() {
        val title = "死神 千年血战篇  诀别谭+相克谭+死神全集 4K"
        // 跨部连续编号的第 15 集只可能是诀别谭的
        assertEquals(15, episodeOf(separation, title, listOf("死神 千年血战篇"), "15.mp4"))
        // 「死神全集」是 2004 年 TV 版, 它的第 15 集不是
        assertNull(episodeOf(separation, title, listOf("死神全集"), "15.mp4"))
        // 从 01 编起的分不清是哪一部 (第一部分的第 2 集也叫 02)
        assertNull(episodeOf(separation, title, listOf("死神血战千年篇 第二季"), "02.mp4"))
        // TMDB 对应表对上的 S几E几 照用
        assertEquals(16, episodeOf(separation, title, listOf("Season 2"), "S02E03.mkv", mapOf((2 to 3) to EpisodeSort(16))))
    }

    @Test
    fun `the part named in a folder of a split season decides`() {
        val tmdb = tmdb(finalSeason, season = 4, firstEpisode = 1)
        val title = "《进击的巨人最终季完结篇》附.1-4季+OAD外传+剧场版+真人版"
        val folders = listOf("J 进击的巨人合集 剧场版＋OAD＋真人版+漫画+最终季前后篇", "进击的巨人 S04")
        assertEquals(61, episodeOf(finalSeason, title, folders + "进击的巨人 S04 最终季 part1", "02.mkv", tmdb))
        assertNull(episodeOf(finalSeason, title, folders + "进击的巨人 S04 最终季 part2", "26.mp4", tmdb))
        // 只写了几段共有的季: 按对应表认, 表里没有的是别的段
        assertEquals(60, episodeOf(finalSeason, "进击的巨人", listOf("进击的巨人 S04"), "01.mkv", tmdb))
        assertNull(episodeOf(finalSeason, "进击的巨人", listOf("进击的巨人 S04"), "20.mkv", tmdb))
    }

    @Test
    fun `episodes of another part in the same TMDB season are not this part`() {
        val tmdb = tmdb(scienceFuture2, season = 4, firstEpisode = 13)
        val title = "《石纪元科学与未来第2部分更至3》"
        assertNull(episodeOf(scienceFuture2, title, listOf("021.石纪元", "S04 Part1（2025）"), "Dr.STONE.S04E04.2025.1080p.CR.WEB-DL.x264.AAC.mkv", tmdb))
        assertEquals(
            15,
            episodeOf(scienceFuture2, title, listOf("021.石纪元", "S04 Part2（2025）"), "[ANi] Dr.STONE 新石紀 第四季 - 15 [1080P][Baha][WEB-DL][AAC AVC][CHT].mp4", tmdb),
        )
        // 合集里按季分的文件夹: 第四季的前 12 集是第一部分的
        val collection = listOf("301-400", "S 4k 石纪元", "第四季")
        assertNull(episodeOf(scienceFuture2, "S 4k 石纪元", collection, "[Ygm] Dr STONE S4 [07][Ma10p_2160p][x265_aac_srt].mkv", tmdb))
        assertEquals(13, episodeOf(scienceFuture2, "S 4k 石纪元", collection, "[Ygm] Dr STONE S4 [13][Ma10p_2160p][x265_aac_srt].mkv", tmdb))
    }

    @Test
    fun `a lineage folder is read by the same rules`() = runTest {
        val shares = FakeShares(
            mutableMapOf("mix" to mapOf("c" to listOf(video("c2", "02.mp4"), video("c15", "15.mp4")))),
            defaultTitle = { "打乱的标题" },
        )
        val engine = DriveShareSearchEngine(
            DriveShareSearchConfig(), shares, DriveShareLinks(TestDrive.protocol),
            identities = DriveSubjectIdentities { DriveSubjectIdentity.of(it, table) },
        ) { null }
        val folder = DriveShareLocation("mix", "", "死神 千年血战篇  诀别谭+相克谭+死神全集 4K", "c", listOf("死神 千年血战篇"))
        assertEquals(listOf("c15"), engine.matchFolder(separation, folder)?.map { it.file.fid })
        // 站点标题只点名了这一部: 系列名文件夹里的都当这一部的
        assertEquals(listOf("c15", "c2"), engine.matchFolder(separation, folder.copy(siteTitle = "死神 千年血战篇-诀别谭-"))?.map { it.file.fid }?.sorted())
    }
}
