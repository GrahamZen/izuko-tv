/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.network

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import me.him188.ani.app.data.models.subject.ContinueWatchingStatus
import me.him188.ani.app.data.models.subject.SubjectAiringInfo
import me.him188.ani.app.data.models.subject.SubjectAiringKind
import me.him188.ani.app.data.models.subject.SubjectProgressInfo
import me.him188.ani.datasources.api.PackedDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TmdbAiringTest {
    private val today = LocalDate(2026, 10, 10)

    private fun season(vararg dates: String?, next: String? = null, ended: Boolean = false) =
        TmdbAiring(fetchedOn = "2026-10-10", showEnded = ended, seasonAirDates = dates.toList(), nextSeasonAirDate = next)

    private fun weekly(first: LocalDate, count: Int) =
        List(count) { first.plus(it * 7, DateTimeUnit.DAY).toString() }

    // region airingKind

    @Test
    fun `整部剧完结 - 认不出季也算已完结`() {
        assertEquals(SubjectAiringKind.COMPLETED, TmdbAiring(showEnded = true).airingKind(today, 0))
        assertEquals(SubjectAiringKind.COMPLETED, TmdbAiring(showEnded = true).airingKind(today, 26))
    }

    @Test
    fun `认不出季且剧没完结 - 不下结论`() {
        assertNull(TmdbAiring().airingKind(today, 12))
        assertNull(season().airingKind(today, 12))
    }

    @Test
    fun `一集没播 - 未开播`() {
        assertEquals(SubjectAiringKind.UPCOMING, season("2026-10-16", "2026-10-23", null).airingKind(today, 0))
    }

    @Test
    fun `一集没播且全无日期 - 不下结论`() {
        assertNull(season(null, null).airingKind(today, 0))
    }

    @Test
    fun `播了一部分 - 连载中`() {
        val dates = weekly(LocalDate(2026, 9, 19), 12).toTypedArray()
        assertEquals(SubjectAiringKind.ON_AIR, season(*dates).airingKind(today, 12))
        assertEquals(SubjectAiringKind.ON_AIR, season(*dates).airingKind(today, 0))
    }

    @Test
    fun `后面的集日期未定 - 连载中`() {
        assertEquals(SubjectAiringKind.ON_AIR, season("2026-10-02", "2026-10-09", null).airingKind(today, 0))
    }

    @Test
    fun `下一季已开播 - 已完结`() {
        assertEquals(
            SubjectAiringKind.COMPLETED,
            season("2025-02-06", null, next = "2026-03-18").airingKind(today, 0),
        )
    }

    @Test
    fun `下一季还没开播 - 不算`() {
        assertEquals(
            SubjectAiringKind.ON_AIR,
            season("2026-10-02", null, next = "2027-01-01").airingKind(today, 0),
        )
    }

    @Test
    fun `全播完且 Bangumi 那几集都在 - 已完结`() {
        val dates = weekly(LocalDate(2026, 8, 1), 10).toTypedArray()
        assertEquals(SubjectAiringKind.COMPLETED, season(*dates).airingKind(today, 10))
    }

    @Test
    fun `整季同一天上线 - 已完结 (Haunted Hotel 第二季)`() {
        val dates = Array(8) { "2026-10-09" }
        assertEquals(SubjectAiringKind.COMPLETED, season(*dates).airingKind(today, 0))
        assertEquals(SubjectAiringKind.COMPLETED, season(*dates).airingKind(today, 8))
    }

    @Test
    fun `Bangumi 没录分集 - TMDB 录的都播了但最后一集刚播 - 连载中`() {
        // TMDB 可能只录了已播的几集
        val dates = weekly(LocalDate(2026, 9, 12), 5).toTypedArray()
        assertEquals(SubjectAiringKind.ON_AIR, season(*dates).airingKind(today, 0))
    }

    @Test
    fun `Bangumi 没录分集 - 最后一集已过 30 天 - 已完结`() {
        val dates = weekly(LocalDate(2026, 6, 1), 8).toTypedArray()
        assertEquals(SubjectAiringKind.COMPLETED, season(*dates).airingKind(today, 0))
    }

    @Test
    fun `条目横跨好几季 - 不按季判断 (海贼王)`() {
        val dates = weekly(LocalDate(1999, 10, 20), 61).toTypedArray()
        assertNull(season(*dates, next = "2001-01-01").airingKind(today, 1150))
    }

    @Test
    fun `条目横跨好几季但剧已完结 - 已完结`() {
        val dates = weekly(LocalDate(1999, 10, 20), 61).toTypedArray()
        assertEquals(SubjectAiringKind.COMPLETED, season(*dates, ended = true).airingKind(today, 120))
    }

    @Test
    fun `一季拆成上下两个条目 - 上半季条目只看前 n 集`() {
        // TMDB 一季 24 集, 下半季在播; Bangumi 上半季条目 12 集
        val dates = (weekly(LocalDate(2026, 1, 3), 12) + weekly(LocalDate(2026, 10, 3), 12)).toTypedArray()
        assertEquals(SubjectAiringKind.COMPLETED, season(*dates).airingKind(today, 12))
    }

    @Test
    fun `电影 - 上映即完结`() {
        val movie = TmdbAiring(seasonAirDates = listOf("2026-10-01"), movie = true)
        assertEquals(SubjectAiringKind.COMPLETED, movie.airingKind(today, 1))
        val upcoming = TmdbAiring(seasonAirDates = listOf("2026-12-01"), movie = true)
        assertEquals(SubjectAiringKind.UPCOMING, upcoming.airingKind(today, 1))
    }

    // endregion

    // region needsRefresh

    @Test
    fun `状态定了的不重取`() {
        assertFalse(TmdbAiring(fetchedOn = "2020-01-01", showEnded = true).needsRefresh(today))
        assertFalse(season("2026-01-01", next = "2026-05-01").copy(fetchedOn = "2026-01-01").needsRefresh(today))
        assertFalse(season("2026-01-01", "2026-01-08").copy(fetchedOn = "2026-01-01").needsRefresh(today))
        assertFalse(TmdbAiring(fetchedOn = "2020-01-01").needsRefresh(today))
    }

    @Test
    fun `还证明不了开播 - 隔 7 天重取`() {
        val airing = season("2026-10-16", "2026-10-23", null)
        assertFalse(airing.copy(fetchedOn = "2026-10-05").needsRefresh(today))
        assertTrue(airing.copy(fetchedOn = "2026-10-03").needsRefresh(today))
        assertTrue(season(null, null).copy(fetchedOn = "2026-09-01").needsRefresh(today))
    }

    @Test
    fun `已能证明开播 - 不再重取 (连载中也不追)`() {
        assertFalse(season("2026-09-26", "2026-10-03", null).copy(fetchedOn = "2026-09-01").needsRefresh(today))
        assertFalse(season("2026-10-10", "2026-10-17").copy(fetchedOn = "2026-09-01").needsRefresh(today))
    }

    @Test
    fun `没有播出情况的旧缓存要重取`() {
        val stills = TmdbEpisodeStills(byAirDate = mapOf("2026-10-09" to listOf(TmdbEpisodeMedia())))
        assertTrue(stills.airingNeedsRefresh(today))
        assertFalse(stills.copy(airing = TmdbAiring(fetchedOn = "2026-10-01", showEnded = true)).airingNeedsRefresh(today))
        assertFalse(TmdbEpisodeStills().airingNeedsRefresh(today))
    }

    // endregion

    // region tmdbTvAiring

    @Test
    fun `组播出情况 - 从起点那集起按集号排`() {
        val airing = tmdbTvAiring(
            status = "Returning Series",
            seasons = listOf(0 to "2019-10-28", 1 to "2024-01-18", 2 to "2025-10-29", 3 to null),
            seasonAirDates = mapOf(
                1 to listOf(2 to "2024-01-18", 1 to "2024-01-18"),
                2 to listOf(1 to "2025-10-29", 2 to "", 3 to null),
            ),
            start = 1 to 1,
            today = today,
        )
        assertFalse(airing.showEnded)
        assertEquals(listOf("2024-01-18", "2024-01-18"), airing.seasonAirDates)
        assertEquals("2025-10-29", airing.nextSeasonAirDate)
        assertEquals("2026-10-10", airing.fetchedOn)
    }

    @Test
    fun `组播出情况 - 对应表从季中间起算`() {
        val airing = tmdbTvAiring(
            status = "Ended",
            seasons = listOf(1 to "2026-01-03"),
            seasonAirDates = mapOf(1 to (1..24).map { it to "2026-01-" + it.toString().padStart(2, '0') }),
            start = 1 to 13,
            today = today,
        )
        assertTrue(airing.showEnded)
        assertEquals(12, airing.seasonAirDates?.size)
        assertEquals("2026-01-13", airing.seasonAirDates?.first())
        assertNull(airing.nextSeasonAirDate)
    }

    @Test
    fun `组播出情况 - 认不出季只记整部剧状态`() {
        val airing = tmdbTvAiring("Canceled", listOf(1 to "2020-01-01"), mapOf(1 to listOf(1 to "2020-01-01")), null, today)
        assertTrue(airing.showEnded)
        assertNull(airing.seasonAirDates)
    }

    @Test
    fun `西文季号`() {
        assertEquals(2, tmdbSeasonNumberFromLatinSuffix("Haunted Hotel Season 2"))
        assertEquals(9, tmdbSeasonNumberFromLatinSuffix("Rick and Morty season 9 "))
        assertNull(tmdbSeasonNumberFromLatinSuffix("Haunted Hotel"))
        assertNull(tmdbSeasonNumberFromLatinSuffix("進撃の巨人 The Final Season"))
    }

    // endregion

    // region correctAiringByTmdb

    private val bangumiUpcoming = SubjectAiringInfo(
        SubjectAiringKind.UPCOMING,
        mainEpisodeCount = 0,
        airDate = PackedDate.Invalid,
        firstSort = null,
        latestEp = null,
        latestSort = null,
        upcomingSort = null,
    )
    private val notOnAir = SubjectProgressInfo(ContinueWatchingStatus.NotOnAir(PackedDate.Invalid), null)
    private val start = SubjectProgressInfo(ContinueWatchingStatus.Start, null)

    private fun correct(
        airingInfo: SubjectAiringInfo,
        progressInfo: SubjectProgressInfo?,
        airing: TmdbAiring?,
        totalEpisodes: Int = 0,
        hasEpisodes: Boolean = true,
    ) = correctAiringByTmdb(airingInfo, progressInfo, airing, today, totalEpisodes, hasEpisodes)

    @Test
    fun `未开播且没录分集 - 状态与集数用 TMDB 的, 按钮改开始观看`() {
        val corrected = correct(bangumiUpcoming, notOnAir, season(*Array(8) { "2026-10-09" }), hasEpisodes = false)
        assertEquals(SubjectAiringKind.COMPLETED, corrected.airingInfo.kind)
        assertEquals(8, corrected.airingInfo.mainEpisodeCount)
        assertEquals(ContinueWatchingStatus.Start, corrected.progressInfo?.continueWatchingStatus)
    }

    @Test
    fun `未开播 - 分集都没日期 (丹佛最后的恐龙)`() {
        val bangumi = bangumiUpcoming.copy(mainEpisodeCount = 52)
        val corrected = correct(bangumi, notOnAir, TmdbAiring(showEnded = true))
        assertEquals(SubjectAiringKind.COMPLETED, corrected.airingInfo.kind)
        assertEquals(52, corrected.airingInfo.mainEpisodeCount)
        assertEquals(ContinueWatchingStatus.Start, corrected.progressInfo?.continueWatchingStatus)
    }

    @Test
    fun `没录分集按条目信息估成连载中 - 按 TMDB 改成已完结 (鼠国流浪记)`() {
        val estimated = bangumiUpcoming.copy(kind = SubjectAiringKind.ON_AIR)
        val movie = TmdbAiring(seasonAirDates = listOf("2006-10-22"), movie = true)
        val corrected = correct(estimated, start, movie, hasEpisodes = false)
        assertEquals(SubjectAiringKind.COMPLETED, corrected.airingInfo.kind)
        assertEquals(1, corrected.airingInfo.mainEpisodeCount)
        assertSame(start, corrected.progressInfo)
    }

    @Test
    fun `没录分集 - 只补集数`() {
        val estimated = bangumiUpcoming.copy(kind = SubjectAiringKind.ON_AIR)
        val corrected = correct(estimated, start, season("2026-10-02", "2026-10-09", null), hasEpisodes = false)
        assertEquals(SubjectAiringKind.ON_AIR, corrected.airingInfo.kind)
        assertEquals(3, corrected.airingInfo.mainEpisodeCount)
    }

    @Test
    fun `没录分集 - 估成已完结的不改回连载中, TMDB 说没开播的不改`() {
        val completed = bangumiUpcoming.copy(kind = SubjectAiringKind.COMPLETED, mainEpisodeCount = 3)
        assertSame(completed, correct(completed, start, season("2026-10-02", null, null), hasEpisodes = false).airingInfo)
        val onAir = bangumiUpcoming.copy(kind = SubjectAiringKind.ON_AIR, mainEpisodeCount = 3)
        assertSame(onAir, correct(onAir, start, season("2026-10-16", "2026-10-23"), hasEpisodes = false).airingInfo)
    }

    @Test
    fun `集数哪里都没有 - 不修正`() {
        val corrected = correct(bangumiUpcoming, notOnAir, TmdbAiring(showEnded = true), hasEpisodes = false)
        assertSame(bangumiUpcoming, corrected.airingInfo)
        assertSame(notOnAir, corrected.progressInfo)
    }

    @Test
    fun `集数用条目信息里的话数`() {
        val corrected = correct(bangumiUpcoming, notOnAir, TmdbAiring(showEnded = true), totalEpisodes = 8, hasEpisodes = false)
        assertEquals(SubjectAiringKind.COMPLETED, corrected.airingInfo.kind)
        assertEquals(8, corrected.airingInfo.mainEpisodeCount)
    }

    @Test
    fun `要播的集不变`() {
        val withEpisode = SubjectProgressInfo(ContinueWatchingStatus.NotOnAir(PackedDate.Invalid), 7)
        val bangumi = bangumiUpcoming.copy(mainEpisodeCount = 2)
        val corrected = correct(bangumi, withEpisode, season("2026-10-02", "2026-10-09"))
        assertEquals(SubjectAiringKind.COMPLETED, corrected.airingInfo.kind)
        assertEquals(ContinueWatchingStatus.Start, corrected.progressInfo?.continueWatchingStatus)
        assertEquals(7, corrected.progressInfo?.nextEpisodeIdToPlay)
    }

    @Test
    fun `有分集且已开播 - 不看 TMDB`() {
        // TMDB 说还没播 (Bangumi 日期比 TMDB 早), 也说已完结: 都不改
        val bangumiOnAir = bangumiUpcoming.copy(kind = SubjectAiringKind.ON_AIR, mainEpisodeCount = 2)
        for (airing in listOf(season("2026-10-16", "2026-10-23"), season("2026-01-01", "2026-01-08"))) {
            val corrected = correct(bangumiOnAir, start, airing)
            assertSame(bangumiOnAir, corrected.airingInfo)
            assertSame(start, corrected.progressInfo)
        }
        val bangumiCompleted = bangumiOnAir.copy(kind = SubjectAiringKind.COMPLETED)
        assertSame(bangumiCompleted, correct(bangumiCompleted, start, season("2026-09-26", "2026-10-03", null)).airingInfo)
    }

    @Test
    fun `TMDB 也说没开播 - 原样`() {
        val corrected = correct(bangumiUpcoming, notOnAir, season("2026-10-16", "2026-10-23"), hasEpisodes = false)
        assertSame(bangumiUpcoming, corrected.airingInfo)
        assertSame(notOnAir, corrected.progressInfo)
    }

    @Test
    fun `看过的进度不动`() {
        val watched = SubjectProgressInfo(ContinueWatchingStatus.Watched(null, null, PackedDate.Invalid), 2)
        val bangumi = bangumiUpcoming.copy(mainEpisodeCount = 2)
        val corrected = correct(bangumi, watched, season("2026-01-01", "2026-01-08"))
        assertEquals(SubjectAiringKind.COMPLETED, corrected.airingInfo.kind)
        assertSame(watched, corrected.progressInfo)
    }

    @Test
    fun `TMDB 没匹配到 - 原样`() {
        val corrected = correct(bangumiUpcoming, notOnAir, null, totalEpisodes = 8, hasEpisodes = false)
        assertSame(bangumiUpcoming, corrected.airingInfo)
        assertSame(notOnAir, corrected.progressInfo)
    }

    // endregion
}
