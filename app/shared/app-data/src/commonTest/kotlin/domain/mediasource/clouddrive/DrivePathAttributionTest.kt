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
import me.him188.ani.app.domain.mediasource.search.AnimeNameIndex
import me.him188.ani.app.domain.mediasource.search.TitleIdentities
import me.him188.ani.app.domain.mediasource.search.TitleIdentity
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
        graph: SeriesGraphTable = table,
    ): Int? {
        val candidate = DriveSubjectMatcher.Candidate(video("f", fileName), listOf(siteTitle) + folders)
        val identity = TitleIdentity.of(request, graph)
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
        val matched = DriveSubjectMatcher.matchEpisodes(second, candidates, identity = TitleIdentity.of(second, table))
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
        val identity = TitleIdentity.of(firstHalf, table)
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
            second, listOf(DriveSubjectMatcher.Candidate(video("f", fileName), folders)), tmdb, identity = TitleIdentity.of(second, table),
        ).singleOrNull()?.episode?.number?.toInt()
        val title = "弦音 -风舞高中弓道部- 第二季 ツルネ －つながりの一射－ (2023)"
        assertEquals(10, episodeOf(listOf(title), "弦音 -风舞高中弓道部-.Tsurune.S02E10.2023.1080p.WEB-DL.mkv"))
        assertEquals(12, episodeOf(listOf(title, "2023弦音-风舞高中弓道部-第二季"), "12.mp4"))
        // 第一季的
        assertNull(episodeOf(listOf("弦音 -风舞高中弓道部-"), "弦音 -风舞高中弓道部-.Tsurune.S01E10.2018.1080p.WEB-DL.mkv"))
    }

    @Test
    fun `a season number does not vouch for a work outside the series`() {
        val table = SeriesGraphTable.parse(
            listOf(
                "# bangumi-anime v1 max_id=714439 dump=dump-2026-10-20.210332Z.zip | 列: …",
                "228378\tツルネ ―風舞高校弓道部―\t弦音 -风舞高中弓道部-\t2018-10-22\t13\tTV\t\t\t396988\t\t\t",
                "396988\tツルネ －つながりの一射－\t弦音 -连结的一射-\t2023-01-04\t13\tTV\t\t228378\t\t\t\t",
                "329906\tSPY×FAMILY\t间谍过家家\t2022-04-09\t25\tTV\t\t\t\t\t\t",
            ).joinToString("\n").encodeToByteArray(),
        )!!
        val second = DriveSeriesTestData.request(396988, listOf("弦音 -连结的一射-", "ツルネ －つながりの一射－"), 1, 13)
        val identity = TitleIdentity.of(second, table, AnimeNameIndex.of(table))
        val tmdb = tmdb(second, season = 2, firstEpisode = 1)
        fun episodeOf(folders: List<String>, fileName: String) = DriveSubjectMatcher.matchEpisodes(
            second, listOf(DriveSubjectMatcher.Candidate(video("f", fileName), folders)), tmdb, identity = identity,
        ).singleOrNull()?.episode?.number?.toInt()
        // 季号只在这个系列里算数: 外层点名的是系列以外的作品
        assertNull(episodeOf(listOf("动漫合集", "间谍过家家 第二季"), "S02E01.mkv"))
        assertNull(episodeOf(listOf("动漫合集", "间谍过家家 第二季"), "间谍过家家 第二季 S02E01.mkv"))
        // 系列的第一部的名字加本季照旧是这一部; 外层没点名哪一部的也照旧
        assertEquals(1, episodeOf(listOf("动漫合集", "弦音 -风舞高中弓道部- 第二季"), "S02E01.mkv"))
        assertEquals(1, episodeOf(listOf("动漫合集", "第二季"), "S02E01.mkv"))
    }

    @Test
    fun `a folder written with the whole name of a work outside the series is that work`() {
        // 按这一部起名的合集分享里附着同品牌别的作品: 名字本身带着季或几种文字, 拆开后对不上名字表
        val table = SeriesGraphTable.parse(
            listOf(
                "# bangumi-anime v1 max_id=714439 dump=dump-2026-10-20.210332Z.zip | 列: …",
                "246429\tBanG Dream! 2nd Season\tBanG Dream! 第二季\t2019-01-03\t13\tTV\t\t\t\t\t\t",
                "246431\tBanG Dream! ガルパ☆ピコ\tBanG Dream! 少女乐团派对！☆PICO\t2018-07-05\t26\tTV\t\t\t\t\t\t",
                "454684\tBanG Dream! Ave Mujica\t\t2025-01-03\t13\tTV\t\t\t\t\t\t颂乐人偶|BanG Dream! 颂乐人偶",
            ).joinToString("\n").encodeToByteArray(),
        )!!
        val request = DriveSeriesTestData.request(454684, listOf("BanG Dream! Ave Mujica", "颂乐人偶"), 1, 13)
        val identity = TitleIdentity.of(request, table, AnimeNameIndex.of(table))
        fun episodeOf(folders: List<String>, fileName: String) = DriveSubjectMatcher.matchEpisodes(
            request, listOf(DriveSubjectMatcher.Candidate(video("f", fileName), folders)), identity = identity,
        ).singleOrNull()?.episode?.number?.toInt()
        val share = "《炸梦！颂乐人偶 12集全》"
        assertNull(episodeOf(listOf(share, "[内嵌简中]（炸梦！少女乐团派对☆PICO）BanG Dream! ガルパ☆ピコ (2018)[26集全]"), "[Airota][BanG Dream!☆PICO][01][720P].mp4"))
        assertNull(episodeOf(listOf(share, "[内嵌简中]（炸梦！第二季）BanG Dream! 2nd Season (2019)"), "[Sakurato.sub] BanG Dream! [01][CHS][720P].mp4"))
        assertEquals(9, episodeOf(listOf(share, "（炸梦！S乐人｜偶）BanG Dream!Ave Mujica(2025)[12集全]"), "BanG.Dream.Ave.Mujica.S01E09.2025.1080p.WEB-DL.mkv"))
    }

    @Test
    fun `a season number vouches for this work named with the year of the first season`() {
        // 表里两季之间没有边; 按系列整理的文件名写着第一季那年
        val table = SeriesGraphTable.parse(
            listOf(
                "# bangumi-anime v1 max_id=714439 dump=dump-2026-10-20.210332Z.zip | 列: …",
                "309311\tCyberpunk: Edgerunners\t赛博朋克：边缘行者\t2022-09-13\t10\tWEB\t\t\t\t\t\t",
                "513878\tCyberpunk: Edgerunners 2\t赛博朋克：边缘行者 2\t2026-10-20\t10\tWEB\t\t\t\t\t\t",
            ).joinToString("\n").encodeToByteArray(),
        )!!
        val second = DriveSeriesTestData.request(513878, listOf("赛博朋克：边缘行者 2", "Cyberpunk: Edgerunners 2"), 1, 10)
        val tmdb = tmdb(second, season = 2, firstEpisode = 1)
        fun episodeOf(folders: List<String>, fileName: String) = DriveSubjectMatcher.matchEpisodes(
            second, listOf(DriveSubjectMatcher.Candidate(video("f", fileName), folders)), tmdb,
            identity = TitleIdentity.of(second, table, AnimeNameIndex.of(table)),
        ).singleOrNull()?.episode?.number?.toInt()
        assertEquals(
            9,
            episodeOf(
                listOf("赛博朋克：边缘行者 第二季（2026）4K", "Cyberpunk.Edgerunners.2022.S02.2160p.NF.WEB-DL"),
                "Cyberpunk.Edgerunners.2022.S02E09.2160p.NF.WEB-DL.mkv",
            ),
        )
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
            identities = TitleIdentities { TitleIdentity.of(it, table) },
        ) { null }
        val folder = DriveShareLocation("mix", "", "死神 千年血战篇  诀别谭+相克谭+死神全集 4K", "c", listOf("死神 千年血战篇"))
        assertEquals(listOf("c15"), engine.matchFolder(separation, folder)?.map { it.file.fid })
        // 站点标题只点名了这一部: 系列名文件夹里的都当这一部的
        assertEquals(listOf("c15", "c2"), engine.matchFolder(separation, folder.copy(siteTitle = "死神 千年血战篇-诀别谭-"))?.map { it.file.fid }?.sorted())
    }

    private fun graph(vararg rows: String) = SeriesGraphTable.parse((listOf("# bangumi-series-graph v1 max_id=714439 dump=dump-2026-10-20.210332Z.zip | 列: …") + rows).joinToString("\n").encodeToByteArray())!!

    @Test
    fun `a later work knows the subtitles of the other works even without its own`() {
        val graph = graph(
            "235128\tジョジョの奇妙な冒険 黄金の風\tJOJO的奇妙冒险 黄金之风\t2018-10-05\t39\tTV\t\t\t332261\t\t",
            "332261\tジョジョの奇妙な冒険 ストーンオーシャン\tJOJO的奇妙冒险 石之海\t2022-01-07\t12\tTV\t\t235128\t375913\t\t",
            "375913\tジョジョの奇妙な冒険 ストーンオーシャン 第2クール\tJOJO的奇妙冒险 石之海 第2部分\t2022-10-07\t12\tTV\t\t332261\t\t13\t",
        )
        val part2 = DriveSeriesTestData.request(375913, listOf("JOJO的奇妙冒险 石之海 第2部分", "ジョジョの奇妙な冒険 ストーンオーシャン 第2クール"), 13, 12)
        val tmdb = tmdb(part2, season = 5, firstEpisode = 13)
        // 上传者把黄金之风标成 S5, 对应表也对得上: 文件夹点名了黄金之风, 不是这一部
        val goldenWind = listOf("S5 黄金之风 (2018.10)", "[悠哈璃羽字幕社&诸神字幕组][01-39][BDRIP 1920x1080]")
        assertNull(episodeOf(part2, "JOJO的奇妙冒险", goldenWind, "[Kamigami][JoJo Bizarre Adventure - Golden Wind][24][BDRIP].mp4", tmdb, graph))
        assertEquals(23, episodeOf(part2, "JOJO的奇妙冒险", listOf("05.JOJO的奇妙冒险 石之海"), "[VCB-Studio] JoJo Bizarre Adventure - Stone Ocean [23][Ma10p_1080p].mkv", tmdb, graph))
    }

    @Test
    fun `a folder dated in the year of another work does not vouch for the files under it`() {
        val graph = graph(
            "8402\tPanty & Stocking with Garterbelt\t吊带袜天使\t2010-10-01\t13\tTV\t\t\t390555\t\t",
            "390555\tNew PANTY & STOCKING with GARTERBELT\t新 吊带袜天使\t2025-07-09\t13\tTV\t\t8402\t\t\t",
        )
        val sequel = DriveSeriesTestData.request(390555, listOf("新 吊带袜天使", "New PANTY & STOCKING with GARTERBELT"), 1, 13)
        // TMDB 把新作单列成一部: 2010 年那部的 `S01E05` 也对得上
        val tmdb = tmdb(sequel, season = 1, firstEpisode = 1)
        val title = "《新·吊带袜天使(2025)》"
        val folder = "New.PANTY.&.STOCKING.with.GARTERBELT.S01.2025.1080p.AMZN.WEB-DL"
        assertNull(episodeOf(sequel, title, listOf(folder, "吊带袜天使 (2010)"), "Panty.and.Stocking.with.Garterbelt.S01E05.2010.1080p.BluRay.mkv", tmdb, graph))
        assertEquals(5, episodeOf(sequel, title, listOf(folder), "New.PANTY.&.STOCKING.with.GARTERBELT.S01E05.2025.1080p.AMZN.WEB-DL.mkv", tmdb, graph))
    }

    @Test
    fun `numbers of the whole season under a folder naming this part map to this part`() {
        val part2 = DriveSeriesTestData.request(331752, listOf("进击的巨人 最终季 Part.2", "進撃の巨人 The Final Season Part.2"), 76, 12)
        val folder = listOf("进击的巨人最终季 Part.2 【Spade】4K 2160P 内嵌简日字幕 全12集")
        assertEquals(85, episodeOf(part2, "进击的巨人全季4K典藏版+OVA", folder, "[SRENIX] Attack on Titan The Final Season - 26 [BD HEVC 2160P FLAC].mkv"))
        // 本段自己的编号照旧
        assertEquals(3, episodeOf(part2, "进击的巨人全季4K典藏版+OVA", folder, "03.mkv"))
    }

    @Test
    fun `live action is never this subject`() {
        val finale = DriveSeriesTestData.request(415779, listOf("进击的巨人 最终季 完结篇 后篇"), 1, 1)
        val title = "进击的巨人 最终季 完结篇 后篇（2023）"
        assertNull(episodeOf(finale, title, listOf("进击的巨人【全系列】", "进击的巨人 真人版"), "巨人真人版：前篇.mp4"))
        assertEquals(1, episodeOf(finale, title, listOf("进击的巨人【全系列】", "进击的巨人 最终季 完结篇 后篇"), "进击的巨人.最终季 完结篇 后篇.官方中字.mp4"))
    }

    @Test
    fun `short dramas are never this subject even under a collection name`() {
        val spy = DriveSeriesTestData.request(329906, listOf("间谍过家家", "SPY×FAMILY"), 1, 12, ep = 6)
        // 列着几十部短剧的分享, 条目名是其中一部名字的一段
        val digest = "9月3日短剧更新汇总（17）179.系统要我死，我偏要活到大结局（66集）180.新婚夜和夫君间谍过家家（66集）181.骨哨声起（80集）"
        assertNull(episodeOf(spy, digest, listOf("01.潮起幸得同行（73集）（1080P）"), "第06集.mp4"))
        assertNull(episodeOf(spy, "动漫+短剧合集", listOf("新婚夜和夫君间谍过家家（66集）"), "第06集.mp4"))
        assertEquals(6, episodeOf(spy, "间谍过家家 TV+剧场版", listOf("间谍过家家"), "第06集.mp4"))
    }

    @Test
    fun `a name continued by another part of a title names another work of the series`() {
        val sao = DriveSeriesTestData.request(23686, listOf("刀剑神域", "ソードアート・オンライン", "SAO", "Sword Art Online"), 1, 25)
        val title = "刀剑神域全系列+剧场版"
        assertNull(episodeOf(sao, title, listOf("DJ.刀剑神域", "刀剑神域 Alicization"), "01.地底世界.mp4"))
        assertNull(episodeOf(sao, title, listOf("DJ.刀剑神域", "刀剑神域 Extra Edition"), "[P1]1.mp4"))
        assertEquals(1, episodeOf(sao, title, listOf("DJ.刀剑神域", "刀剑神域"), "01.剑的世界.mp4"))
        val collection = listOf("501-600", "D 4k 刀剑神域", "4.刀剑神域 爱丽丝篇")
        assertNull(episodeOf(sao, "D 4k 刀剑神域", collection, "[Ygm&Kgm] Sword Art Online Alicization [01][Ma10p_2160p][x265_flac_aac_ass].mkv"))
        assertEquals(
            1,
            episodeOf(sao, "D 4k 刀剑神域", listOf("501-600", "D 4k 刀剑神域", "1.刀剑神域 第一季"), "[Ygm&Kgm] Sword Art Online [01][Ma10p_2160p][x265_flac_ass].mkv"),
        )
        val titan = DriveSeriesTestData.request(55770, listOf("进击的巨人", "進撃の巨人", "Attack on Titan", "Shingeki no Kyojin"), 1, 25)
        val folders = listOf("进击的巨人 全季+OVA  4K 高码", "5.The Final Season -Part1")
        assertNull(episodeOf(titan, "【收藏版】进击的巨人 全季 4K 高码 + OVA 中日硬字幕", folders, "[SRENIX] Attack on Titan The Final Season - 01 [BD HEVC 2160P FLAC].mkv"))
        assertEquals(1, episodeOf(titan, "进击的巨人", listOf("Attack.on.Titan.S01.2013.1080p.DSNP.WEB-DL"), "Attack.on.Titan.S01E01.2013.1080p.DSNP.WEB-DL.H.264.AAC.mkv"))
    }
}
