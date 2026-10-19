/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.collection

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import me.him188.ani.app.data.models.episode.EpisodeCollectionInfo
import me.him188.ani.app.data.models.episode.EpisodeInfo
import me.him188.ani.app.data.models.subject.SubjectCollectionInfo
import me.him188.ani.app.data.models.subject.SubjectRecurrence
import me.him188.ani.app.data.models.subject.createTestSubjectCollection
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.EpisodeType
import me.him188.ani.datasources.api.PackedDate
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

class TvCollectionProgressModelTest {
    /** 2026-10-19 周一 20:15 (东京), 上海 19:15. */
    private val now = Instant.parse("2026-10-19T11:15:00Z")
    private val timeZone = TimeZone.of("Asia/Shanghai")

    /** 每周一 23:00 (东京) 播, 第一集 2026-09-07. */
    private val mondayNight = SubjectRecurrence(Instant.parse("2026-09-07T14:00:00Z"), 7.days)

    private fun episode(id: Int, sort: Int, airDate: PackedDate, watched: Boolean, type: EpisodeType = EpisodeType.MainStory) =
        EpisodeCollectionInfo(
            EpisodeInfo(episodeId = id, type = type, airDate = airDate, sort = EpisodeSort(sort)),
            if (watched) UnifiedCollectionType.DONE else UnifiedCollectionType.NOT_COLLECTED,
        )

    /** [count] 集, 第一集 [first], 每周一集; 前 [watched] 集看过. */
    private fun weekly(
        id: Int,
        type: UnifiedCollectionType,
        first: LocalDate,
        count: Int,
        watched: Int,
        recurrence: SubjectRecurrence? = null,
        lastUpdated: Long = 0,
    ): SubjectCollectionInfo {
        val episodes = (1..count).map { n ->
            val date = LocalDate.fromEpochDays(first.toEpochDays() + (n - 1) * 7)
            episode(id * 100 + n, n, PackedDate(date.year, date.month.ordinal + 1, date.day), watched = n <= watched)
        }
        return createTestSubjectCollection(id, episodes, type).copy(recurrence = recurrence, lastUpdated = lastUpdated)
    }

    private fun sections(doing: List<SubjectCollectionInfo> = emptyList(), wish: List<SubjectCollectionInfo> = emptyList()) =
        tvProgressSections(doing, wish, now, timeZone)

    private fun List<TvProgressSection>.groupOf(id: Int): TvProgressGroup? =
        firstOrNull { s -> s.entries.any { it.subjectId == id } }?.group

    private fun List<TvProgressSection>.entry(id: Int): TvProgressEntry =
        flatMap { it.entries }.first { it.subjectId == id }

    @Test
    fun `behind on an airing show is new episodes`() {
        // 8 月 31 日起每周一集, 到 10-19 播了 8 集 (10-19 那集按日期算 00:00 已播), 看了 5 集
        val s = sections(doing = listOf(weekly(1, UnifiedCollectionType.DOING, LocalDate(2026, 8, 31), 12, watched = 5)))
        val e = s.entry(1)
        assertEquals(TvProgressGroup.NewEpisodes, e.group)
        assertEquals(3, e.unwatched)
        assertEquals(8, e.airedCount)
        assertEquals(EpisodeSort(8), e.latestSort)
        assertEquals(EpisodeSort(9), e.nextSort)
    }

    @Test
    fun `caught up and next episode airs tonight is today`() {
        val s = sections(doing = listOf(weekly(2, UnifiedCollectionType.DOING, LocalDate(2026, 9, 7), 12, watched = 6, recurrence = mondayNight)))
        val e = s.entry(2)
        assertEquals(TvProgressGroup.Today, e.group)
        assertEquals(0, e.unwatched)
        assertEquals(EpisodeSort(7), e.nextSort)
        assertEquals(Instant.parse("2026-10-19T14:00:00Z"), e.nextAirTime?.instant)
        assertTrue(e.nextAirTime!!.exact)
    }

    @Test
    fun `caught up and next episode within a week is this week`() {
        // 每周四播, 看到最新
        val s = sections(doing = listOf(weekly(3, UnifiedCollectionType.DOING, LocalDate(2026, 9, 17), 12, watched = 5)))
        assertEquals(TvProgressGroup.ThisWeek, s.groupOf(3))
    }

    @Test
    fun `caught up with unknown next date is caught up while recent`() {
        val episodes = listOf(
            episode(401, 1, PackedDate(2026, 10, 7), watched = true),
            episode(402, 2, PackedDate(2026, 10, 14), watched = true),
            episode(403, 3, PackedDate.Invalid, watched = false),
        )
        val s = sections(doing = listOf(createTestSubjectCollection(4, episodes, UnifiedCollectionType.DOING)))
        val e = s.entry(4)
        assertEquals(TvProgressGroup.CaughtUp, e.group)
        assertNull(e.nextAirTime)
    }

    @Test
    fun `undated next episode after a long pause is not airing`() {
        val episodes = listOf(
            episode(501, 1, PackedDate(2026, 6, 1), watched = true),
            episode(502, 2, PackedDate.Invalid, watched = false),
        )
        assertEquals(emptyList(), sections(doing = listOf(createTestSubjectCollection(5, episodes, UnifiedCollectionType.DOING))))
    }

    @Test
    fun `completed long ago is left to the watching tab`() {
        val s = sections(doing = listOf(weekly(6, UnifiedCollectionType.DOING, LocalDate(2026, 1, 5), 12, watched = 3)))
        assertEquals(emptyList(), s)
    }

    @Test
    fun `just completed with episodes left still counts as new episodes`() {
        // 最后一集 10-14 播完, 还差两集没看
        val s = sections(doing = listOf(weekly(7, UnifiedCollectionType.DOING, LocalDate(2026, 7, 29), 12, watched = 10)))
        val e = s.entry(7)
        assertEquals(TvProgressGroup.NewEpisodes, e.group)
        assertEquals(2, e.unwatched)
        assertNull(e.nextSort)
    }

    @Test
    fun `wishlist show that started airing`() {
        val s = sections(wish = listOf(weekly(8, UnifiedCollectionType.WISH, LocalDate(2026, 10, 1), 12, watched = 0)))
        val e = s.entry(8)
        assertEquals(TvProgressGroup.WishAired, e.group)
        assertEquals(3, e.airedCount)
    }

    @Test
    fun `wishlist show without episodes uses the subject air date`() {
        val base = createTestSubjectCollection(9, emptyList(), UnifiedCollectionType.WISH)
        val recent = base.copy(subjectInfo = base.subjectInfo.copy(airDate = PackedDate(2026, 10, 2)))
        val e = sections(wish = listOf(recent)).entry(9)
        assertEquals(TvProgressGroup.WishAired, e.group)
        assertEquals(-1, e.airedCount)

        val old = base.copy(subjectInfo = base.subjectInfo.copy(airDate = PackedDate(2024, 4, 2)))
        assertEquals(emptyList(), sections(wish = listOf(old)))
    }

    @Test
    fun `premiere within a month is upcoming and further ones are left out`() {
        val soon = weekly(10, UnifiedCollectionType.WISH, LocalDate(2026, 10, 29), 12, watched = 0)
        val later = weekly(11, UnifiedCollectionType.WISH, LocalDate(2027, 1, 7), 12, watched = 0)
        val s = sections(wish = listOf(soon, later))
        assertEquals(TvProgressGroup.Upcoming, s.groupOf(10))
        assertNull(s.groupOf(11))
        assertEquals(EpisodeSort(1), s.entry(10).nextSort)
    }

    @Test
    fun `a show both watching and wished counts once as watching`() {
        val doing = weekly(12, UnifiedCollectionType.DOING, LocalDate(2026, 8, 31), 12, watched = 2)
        val wish = weekly(12, UnifiedCollectionType.WISH, LocalDate(2026, 8, 31), 12, watched = 0)
        val s = sections(doing = listOf(doing), wish = listOf(wish))
        assertEquals(1, s.sumOf { it.entries.size })
        assertEquals(TvProgressGroup.NewEpisodes, s.groupOf(12))
    }

    @Test
    fun `specials are not counted as aired episodes`() {
        val episodes = listOf(
            episode(1301, 1, PackedDate(2026, 10, 5), watched = true),
            episode(1302, 2, PackedDate(2026, 10, 26), watched = false),
            episode(1303, 1, PackedDate(2026, 10, 12), watched = false, type = EpisodeType.SP),
        )
        val e = sections(doing = listOf(createTestSubjectCollection(13, episodes, UnifiedCollectionType.DOING))).entry(13)
        assertEquals(0, e.unwatched)
        assertEquals(1, e.airedCount)
    }

    @Test
    fun `groups come in page order and new episodes put the latest first`() {
        val older = weekly(14, UnifiedCollectionType.DOING, LocalDate(2026, 8, 27), 12, watched = 3)
        val newer = weekly(15, UnifiedCollectionType.DOING, LocalDate(2026, 8, 31), 12, watched = 3)
        val tonight = weekly(16, UnifiedCollectionType.DOING, LocalDate(2026, 9, 7), 12, watched = 6, recurrence = mondayNight)
        val wished = weekly(17, UnifiedCollectionType.WISH, LocalDate(2026, 10, 1), 12, watched = 0)
        val s = sections(doing = listOf(older, tonight, newer), wish = listOf(wished))
        assertEquals(listOf(TvProgressGroup.NewEpisodes, TvProgressGroup.WishAired, TvProgressGroup.Today), s.map { it.group })
        assertEquals(listOf(15, 14), s.first().entries.map { it.subjectId })
    }

    @Test
    fun `card subtitles`() {
        val strings = TvProgressStrings(
            header = "%1\$s · %2\$d 部",
            groupNew = "有新集", groupWish = "想看的番开播了", groupToday = "今天更新", groupWeek = "本周更新",
            groupCaughtUp = "已追平", groupUpcoming = "即将开播",
            unwatchedToday = "未看 %1\$d · 今天更新",
            unwatched = "未看 %1\$d / 已更新 %2\$s",
            wishAired = "已播 %1\$d 集",
            wishAiredNext = "已播 %1\$d 集 · %2\$s更新",
            wishStarted = "已开播",
            next = "%1\$s · 第 %2\$s 话",
            nextUnknown = "下一集日期未定",
            premiere = "%1\$s 开播",
        )
        val dayLabel: (LocalDate) -> String = { "${it.month.ordinal + 1}-${it.day}" }
        fun subtitle(info: SubjectCollectionInfo, wish: Boolean = false): String {
            val s = if (wish) sections(wish = listOf(info)) else sections(doing = listOf(info))
            return s.entry(info.subjectId).cardSubtitle(strings, now, timeZone, dayLabel)
        }

        assertEquals("未看 3 / 已更新 8", subtitle(weekly(20, UnifiedCollectionType.DOING, LocalDate(2026, 8, 25), 12, watched = 5)))
        assertEquals("22:00 · 第 7 话", subtitle(weekly(21, UnifiedCollectionType.DOING, LocalDate(2026, 9, 7), 12, watched = 6, recurrence = mondayNight)))
        assertEquals("10-22 · 第 6 话", subtitle(weekly(22, UnifiedCollectionType.DOING, LocalDate(2026, 9, 17), 12, watched = 5)))
        assertEquals("已播 3 集 · 10-22更新", subtitle(weekly(23, UnifiedCollectionType.WISH, LocalDate(2026, 10, 1), 12, watched = 0), wish = true))
        assertEquals("10-29 开播", subtitle(weekly(24, UnifiedCollectionType.WISH, LocalDate(2026, 10, 29), 12, watched = 0), wish = true))
        assertEquals("有新集 · 1 部", strings.headerOf(sections(doing = listOf(weekly(25, UnifiedCollectionType.DOING, LocalDate(2026, 8, 24), 12, watched = 5))).first()))
    }
}
