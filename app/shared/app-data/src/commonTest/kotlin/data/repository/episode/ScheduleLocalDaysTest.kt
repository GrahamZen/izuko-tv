/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.repository.episode

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import me.him188.ani.app.data.network.schedule.BroadcastRule
import me.him188.ani.app.data.network.schedule.ScheduleEpisode
import me.him188.ani.app.data.network.schedule.ScheduleSubject
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 用 2026 年 10 月第三周的真实播出时刻 (bangumi-data) 与 Bangumi 播出日期:
 * - [MORNING]: 日本时间周六 09:30 播, Bangumi 记 10-17;
 * - [TV_DAY]: 日本时间周六 01:23 播, Bangumi 按电视日记成前一天 10-16;
 * - [CALENDAR_DAY]: 日本时间周日 01:00 播, Bangumi 记当天 10-18;
 * - [NO_RULE]: bangumi-data 里没有播出规则, Bangumi 记 10-17.
 */
class ScheduleLocalDaysTest {
    private val chicago = TimeZone.of("America/Chicago")
    private val shanghai = TimeZone.of("Asia/Shanghai")
    private val tokyo = TimeZone.of("Asia/Tokyo")

    private fun subject(id: Int, weekday: Int) = ScheduleSubject(id, "s$id", "", "https://lain.bgm.tv/pic/cover/l/$id.jpg", weekday)

    private val subjects = listOf(subject(MORNING, 6), subject(TV_DAY, 5), subject(CALENDAR_DAY, 7), subject(NO_RULE, 6))

    private val episodes = mapOf(
        MORNING to listOf(episode(MORNING, "2026-10-10"), episode(MORNING, "2026-10-17")),
        TV_DAY to listOf(episode(TV_DAY, "2026-10-09"), episode(TV_DAY, "2026-10-16")),
        CALENDAR_DAY to listOf(episode(CALENDAR_DAY, "2026-10-11"), episode(CALENDAR_DAY, "2026-10-18")),
        NO_RULE to listOf(episode(NO_RULE, "2026-10-17")),
    )

    private val rules = mapOf(
        MORNING to BroadcastRule("2026-10-03T00:30:00.000Z", 7),
        TV_DAY to BroadcastRule("2026-10-02T16:23:00.000Z", 7),
        CALENDAR_DAY to BroadcastRule("2026-10-03T16:00:00.000Z", 7),
        NO_RULE to null,
    )

    private fun episode(subjectId: Int, airDate: String) =
        ScheduleEpisode(id = subjectId * 1000 + airDate.takeLast(2).toInt(), name = "", nameCn = "", sort = "1", ep = "1", airDate = airDate)

    /** 本地 [date] 那天: `条目 本地时刻`, 时刻不明的写 `?`. */
    private fun on(date: LocalDate, timeZone: TimeZone): List<String> =
        scheduleItemsOn(date, subjects, episodes, rules, timeZone).map { item ->
            val time = if (item.timeKnown) item.airingTime.toLocalDateTime(timeZone).time.toString() else "?"
            "${item.subject.subjectId} $time"
        }

    @Test
    fun `chicago puts japanese daytime and late night episodes on the local day they air`() {
        assertEquals(listOf("$TV_DAY 11:23", "$MORNING 19:30"), on(LocalDate(2026, 10, 16), chicago))
        assertEquals(listOf("$NO_RULE ?", "$CALENDAR_DAY 11:00"), on(LocalDate(2026, 10, 17), chicago))
        assertEquals(emptyList(), on(LocalDate(2026, 10, 18), chicago))
    }

    @Test
    fun `shanghai moves late night episodes after midnight to the next day`() {
        assertEquals(emptyList(), on(LocalDate(2026, 10, 16), shanghai))
        assertEquals(listOf("$NO_RULE ?", "$TV_DAY 00:23", "$MORNING 08:30"), on(LocalDate(2026, 10, 17), shanghai))
        assertEquals(listOf("$CALENDAR_DAY 00:00"), on(LocalDate(2026, 10, 18), shanghai))
    }

    @Test
    fun `tokyo keeps the real broadcast date even when bangumi records the tv day`() {
        assertEquals(listOf("$NO_RULE ?", "$TV_DAY 01:23", "$MORNING 09:30"), on(LocalDate(2026, 10, 17), tokyo))
        assertEquals(listOf("$CALENDAR_DAY 01:00"), on(LocalDate(2026, 10, 18), tokyo))
    }

    @Test
    fun `candidates include the weekdays before and after`() {
        val calendar = mapOf(4 to listOf(subject(1, 4)), 5 to listOf(subject(2, 5)), 6 to listOf(subject(3, 6)), 7 to listOf(subject(4, 7)))
        assertEquals(listOf(2, 3, 4), scheduleCandidates(calendar, LocalDate(2026, 10, 17)).map { it.id })
    }

    private companion object {
        const val MORNING = 1
        const val TV_DAY = 2
        const val CALENDAR_DAY = 3
        const val NO_RULE = 4
    }
}
