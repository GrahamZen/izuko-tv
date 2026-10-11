/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.selector

import kotlinx.coroutines.flow.first
import me.him188.ani.app.data.models.subject.SplitSeasonTestData
import me.him188.ani.app.data.models.subject.SplitSeasonTestData.ReZero
import me.him188.ani.app.domain.media.selector.testFramework.MediaSelectorTestSuite
import me.him188.ani.app.domain.media.selector.testFramework.SimpleMediaSelectorTestSuite
import me.him188.ani.app.domain.media.selector.testFramework.runSimpleMediaSelectorTestSuite
import me.him188.ani.app.domain.mediasource.MediaListFilters
import me.him188.ani.app.domain.mediasource.clouddrive.DriveMediaFolders
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.datasources.api.topic.EpisodeRange
import me.him188.ani.datasources.api.topic.SubtitleLanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * fork 在上游选源规则上加的两道: 页名季号与本季冲突的站点页面按其他季排除 ([filter.SeasonNumberConflict]),
 * 网盘资源按所在文件夹判断拆分季的页面 ([filter.SplitSeasonDrivePages]).
 */
@OptIn(UnsafeOriginalMediaAccess::class)
class MediaSelectorSeasonGuardsTest {
    // region 季号冲突

    private fun MediaSelectorTestSuite.initSpyFamilySeason2() {
        initSubject("间谍过家家 第二季") {
            aliases("SPY×FAMILY Season 2")
            episodeSort = EpisodeSort(26)
            episodeEp = EpisodeSort(1)
            seriesInfo(seasonSort = 2) {
                series("间谍过家家", "SPY×FAMILY", "间谍过家家 第2部分", "SPY×FAMILY 第2クール")
                sequel("间谍过家家 第三季", "SPY×FAMILY Season 3")
            }
        }
    }

    @Test
    fun `page labelled with another season number is excluded`() = runSimpleMediaSelectorTestSuite {
        initSpyFamilySeason2()
        val firstSeason = addPage("间谍过家家第一季", 1..25)
        val thirdSeason = addPage("间谍过家家第三季", 1..13, source = "web2")
        val own = addPage("间谍过家家 第二季", 1..12, source = "web3")

        assertPage(firstSeason, 1 to "excluded FromSeriesSeason")
        assertPage(thirdSeason, 1 to "excluded FromSeriesSeason")
        assertPage(own, 1 to "included EXACT EP")
    }

    @Test
    fun `page covering this season among others is kept`() = runSimpleMediaSelectorTestSuite {
        initSpyFamilySeason2()
        val merged = addPage("SPY×FAMILY 间谍过家家【第一季+第二季】【1-37】", 1..37)

        // 跨季合集从第一季连续编号, 页上的第 01 集是第一季的 (见 EpisodeNumberingPages)
        assertPage(merged, 26 to "included FUZZY SORT")
    }

    @Test
    fun `subject without a season number keeps season labelled pages`() = runSimpleMediaSelectorTestSuite {
        initSubject("间谍过家家") {
            seriesInfo(seasonSort = 1) { sequel("间谍过家家 第二季") }
        }
        val firstSeason = addPage("间谍过家家第一季", 1..25)

        assertPage(firstSeason, 1 to "included FUZZY SORT")
    }

    /** Bangumi 上前几季的名字不一定写了季号 (「一人之下3」), 当前季之前的季总是有的. */
    @Test
    fun `earlier seasons count even when their names carry no number`() = runSimpleMediaSelectorTestSuite {
        initSubject("一人之下 第六季") {
            seriesInfo(seasonSort = 6) { series("一人之下", "一人之下 2", "一人之下3", "一人之下4", "一人之下5") }
        }
        val fifth = addPage("一人之下第五季", 1..12)
        val sixth = addPage("一人之下第六季", 1..12, source = "web2")

        assertPage(fifth, 1 to "excluded FromSeriesSeason")
        assertPage(sixth, 1 to "included EXACT SORT")
    }

    /** 「PartⅡ」是分段: 看第三季时「叁之章 PartⅡ」不能当成第二季排除. */
    @Test
    fun `part numbers are not season numbers`() = runSimpleMediaSelectorTestSuite {
        initSubject("炎炎消防队 第三季") {
            seriesInfo(seasonSort = 3) { series("炎炎消防队", "炎炎消防队 第二季") }
        }
        addPage("炎炎消防队 叁之章 PartⅡ", 1..12)

        val candidate = selector.filteredCandidates.first().single { it.original.properties.subjectName == "炎炎消防队 叁之章 PartⅡ" && it.original.episodeRange == EpisodeRange.single(EpisodeSort(1)) }
        assertTrue(candidate.describe() != "excluded FromSeriesSeason", candidate.describe())
    }

    @Test
    fun `BT collections are not judged by their season label`() = runSimpleMediaSelectorTestSuite {
        initSpyFamilySeason2()
        // 按整部连续编号的合集常标成 S01
        val collection = mediaApi.addMedia(
            media(
                episodeRange = EpisodeRange.range(EpisodeSort(26), EpisodeSort(37)),
                subjectName = "SPY×FAMILY S01",
                originalTitle = "[Sub] SPY×FAMILY S01 [26-37]",
            ),
        )

        val candidate = selector.filteredCandidates.first().single { it.original === collection }
        assertEquals("included FUZZY SORT", candidate.describe())
    }

    // endregion

    // region 同一页上两种编号

    private fun MediaSelectorTestSuite.episodeCount(count: Int) {
        val context = preferenceApi.mediaSelectorContext.value
        preferenceApi.mediaSelectorContext.value = context.copy(subjectInfo = context.subjectInfo?.copy(totalEpisodes = count))
    }

    /** 第一季 12 集, 第二季 13 集: 第二季第 1 集的系列内序号 13 也是本季内部编号页面上的最后一集. */
    private fun MediaSelectorTestSuite.initSecondSeason(sort: Int, ep: Int) {
        initSubject("相反的你和我 第二季") {
            aliases("正相反的你与我 第二季")
            episodeSort = EpisodeSort(sort)
            episodeEp = EpisodeSort(ep)
            seriesInfo(seasonSort = 2) { series("相反的你和我") }
        }
    }

    @Test
    fun `page numbered within the season drops the series numbered episode`() = runSimpleMediaSelectorTestSuite {
        initSecondSeason(sort = 13, ep = 1)
        episodeCount(13)
        val own = addPage("相反的你和我 第二季", 1..13)

        assertPage(own, 1 to "included EXACT EP")
    }

    /** 只收本季却接着第一季编号 (13~25): 页上的第 13 集是本季第 1 集. 不知道本季集数也看得出. */
    @Test
    fun `page numbered on from earlier seasons drops the season numbered episode`() = runSimpleMediaSelectorTestSuite {
        initSecondSeason(sort = 25, ep = 13)
        val own = addPage("相反的你和我 第二季", 13..25)

        assertPage(own, 25 to "included EXACT SORT")
    }

    @Test
    fun `page numbered far beyond this season is numbered from the first season`() = runSimpleMediaSelectorTestSuite {
        initSubject("猫猫的呢喃 第三季") {
            episodeSort = EpisodeSort(49)
            episodeEp = EpisodeSort(1)
            seriesInfo(seasonSort = 3) { series("猫猫的呢喃", "猫猫的呢喃 第二季") }
        }
        episodeCount(24)
        val page = addPage("猫猫的呢喃 第三季", 1..52)

        assertPage(page, 49 to "included EXACT SORT")
    }

    /** 本季内部编号的页面里混进一两集按连续编号写的 (22、23): 不算从前面的季连续编号, 两条都留. */
    @Test
    fun `a couple of continuously numbered strays do not make the page continuous`() = runSimpleMediaSelectorTestSuite {
        initSecondSeason(sort = 13, ep = 1)
        episodeCount(13)
        val page = addPage("相反的你和我 第二季", (1..8) + (11..13) + listOf(22, 23))

        assertPage(page, 1 to "included EXACT EP", 13 to "included EXACT SORT")
    }

    /** 页名没写季号, 集号也没超出本季: 分不清, 两条都留. */
    @Test
    fun `numbering is not guessed without a season label`() = runSimpleMediaSelectorTestSuite {
        initSubject("光阴之外 第二季") {
            aliases("光阴之外 末世的微光")
            episodeSort = EpisodeSort(27)
            episodeEp = EpisodeSort(1)
            seriesInfo(seasonSort = 2) { series("光阴之外") }
        }
        episodeCount(52)
        val page = addPage("光阴之外 末世的微光", 1..31)

        assertPage(page, 1 to "included EXACT EP", 27 to "included EXACT SORT")
    }

    /** 跨季合集先只到第一季: 第 01 集先放行, 第二季的集到了以后改成排除 (结论不能被记住). */
    @Test
    fun `numbering decisions follow the page as results arrive`() = runSimpleMediaSelectorTestSuite {
        initSpyFamilySeason2()
        val title = "SPY×FAMILY 间谍过家家【第一季+第二季】"
        addPage(title, 1..25)
        assertPage(Page("web1", title, "简中", (1..25).toList()), 1 to "included FUZZY EP")

        addPage(title, 26..37)
        assertPage(Page("web1", title, "简中", (1..37).toList()), 26 to "included FUZZY SORT")
    }

    // endregion

    // region 网盘按文件夹分页

    /** 网盘资源: 条目名标成当前条目, 线路是数据源名, 另记所在文件夹. */
    private fun SimpleMediaSelectorTestSuite.addDriveFolder(folderKey: String, folderName: String, numbers: Iterable<Int>): Page {
        val title = ReZero.names(633836).first()
        for (number in numbers) {
            val media = media(
                sourceId = "drive-share",
                alliance = "网盘分享",
                episodeRange = EpisodeRange.single(EpisodeSort(number)),
                kind = MediaSourceKind.WEB,
                subjectName = title,
                originalTitle = "R 4k Re：从零开始的异世界生活 / $folderName / Re Zero kara Hajimeru Isekai Seikatsu 4nd Season [$number].mkv",
                subtitleLanguages = listOf(SubtitleLanguage.ChineseSimplified.id),
                mediaId = "drive-share.$folderKey.$number",
            )
            DriveMediaFolders.record(media.mediaId, DriveMediaFolders.Folder(folderKey, folderName))
            mediaApi.addMedia(media)
        }
        return Page("drive-share", title, "网盘分享", numbers.toList(), idPrefix = "drive-share.$folderKey.")
    }

    /** Re:ZERO 第四季 夺还篇 第 6 集 (sort 83), 整季序号第 17 集. */
    private fun MediaSelectorTestSuite.initReZeroS4Part2Episode6() = initSplitSeason(ReZero, 633836, sort = 83, ep = 6)

    @Test
    fun `drive whole season folder - takes the season number`() = runSimpleMediaSelectorTestSuite {
        initReZeroS4Part2Episode6()
        val season = addDriveFolder("share1/fid1", "Re：从零开始的异世界生活 第四季", 1..19)

        assertPage(season, 17 to "included EXACT SEASON")
    }

    @Test
    fun `drive folders are separate pages`() = runSimpleMediaSelectorTestSuite {
        initReZeroS4Part2Episode6()
        val season = addDriveFolder("share1/fid1", "Re：从零开始的异世界生活 第四季", 1..19)
        val own = addDriveFolder("share2/fid2", "Re：从零开始的异世界生活 第四季 夺还篇", 1..8)
        val partOneOnly = addDriveFolder("share3/fid3", "Re：从零开始的异世界生活 第四季", 1..10)

        assertPage(season, 17 to "included EXACT SEASON")
        assertPage(own, 6 to "included EXACT EP")
        assertPage(partOneOnly)
    }

    /**
     * 网盘文件一批批到: 用本篇名字命名、装着整季的文件夹只到一半时按本篇认了第 6 集, 到齐后要改按季内序号认第 17 集
     * (筛选结果不能记住).
     */
    @Test
    fun `split season decisions follow the page as results arrive`() = runSimpleMediaSelectorTestSuite {
        initReZeroS4Part2Episode6()
        addDriveFolder("share1/fid1", "Re：从零开始的异世界生活 第四季 夺还篇", 1..6)
        selector.filteredCandidates.first()
        addDriveFolder("share1/fid1", "Re：从零开始的异世界生活 第四季 夺还篇", 7..19)

        val title = ReZero.names(633836).first()
        assertPage(Page("drive-share", title, "网盘分享", (1..19).toList(), idPrefix = "drive-share.share1/fid1."), 17 to "included EXACT SEASON")
    }

    // endregion

    private class Page(val source: String, val title: String, val channel: String, val numbers: List<Int>, val idPrefix: String? = null)

    private fun SimpleMediaSelectorTestSuite.addPage(title: String, numbers: Iterable<Int>, source: String = "web1", channel: String = "简中"): Page {
        for (number in numbers) {
            mediaApi.addMedia(
                media(
                    sourceId = source,
                    alliance = channel,
                    episodeRange = EpisodeRange.single(EpisodeSort(number)),
                    kind = MediaSourceKind.WEB,
                    subjectName = title,
                    originalTitle = "$title 第${number}集",
                    subtitleLanguages = listOf(SubtitleLanguage.ChineseSimplified.id),
                ),
            )
        }
        return Page(source, title, channel, numbers.toList())
    }

    /** [page] 上每一集的结果; [outcomes] 没列出的集应为 [default]. */
    private suspend fun SimpleMediaSelectorTestSuite.assertPage(
        page: Page,
        vararg outcomes: Pair<Int, String>,
        default: String = "excluded EpisodeMismatch",
    ) {
        val actual = selector.filteredCandidates.first()
            .filter {
                it.original.mediaSourceId == page.source && it.original.properties.subjectName == page.title &&
                        it.original.properties.alliance == page.channel &&
                        (page.idPrefix == null || it.original.mediaId.startsWith(page.idPrefix))
            }
            .associate { (it.original.episodeRange!!.knownSorts.single() as EpisodeSort.Normal).number.toInt() to it.describe() }
        val expected = page.numbers.associateWith { number -> outcomes.firstOrNull { it.first == number }?.second ?: default }
        assertEquals(expected, actual)
    }

    private fun MaybeExcludedMedia.describe(): String = when (this) {
        is MaybeExcludedMedia.Included -> "included ${metadata.subjectMatchKind} ${metadata.episodeMatchKind}"
        is MaybeExcludedMedia.Excluded -> "excluded ${exclusionReason::class.simpleName}"
    }

    /** 同 MediaSelectorSplitSeasonTest: 按 Bangumi 数据初始化 [series] 中的条目 [selfId], 拆分季用服务端对它下发的结果. */
    private fun MediaSelectorTestSuite.initSplitSeason(series: SplitSeasonTestData.Series, selfId: Int, sort: Int, ep: Int) {
        val selfIndex = series.subjects.indexOfFirst { it.first == selfId }
        val own = series.names(selfId)
        initSubject(own.first()) {
            aliases(*own.drop(1).toTypedArray())
            episodeSort = EpisodeSort(sort)
            episodeEp = EpisodeSort(ep)
            seriesInfo(seasonSort = selfIndex + 1) {
                fun isOwnName(name: String) = own.any { MediaListFilters.specialEquals(it, name) }
                series(*series.subjects.flatMap { it.second }.filterNot(::isOwnName).toTypedArray())
                sequel(
                    *series.subjects.drop(selfIndex + 1).flatMap { it.second }
                        .filterNot { name -> own.any { MediaListFilters.specialContains(it, name) } }
                        .toTypedArray(),
                )
                splitSeason = series.splitSeason(selfId)
            }
        }
    }
}
