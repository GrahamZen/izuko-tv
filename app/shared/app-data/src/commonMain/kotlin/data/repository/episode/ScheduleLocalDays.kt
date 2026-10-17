/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.repository.episode

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import me.him188.ani.app.data.models.subject.LightEpisodeInfo
import me.him188.ani.app.data.models.subject.LightSubjectInfo
import me.him188.ani.app.data.models.subject.SubjectRecurrence
import me.him188.ani.app.data.network.schedule.BroadcastRule
import me.him188.ani.app.data.network.schedule.ScheduleEpisode
import me.him188.ani.app.data.network.schedule.ScheduleSubject
import me.him188.ani.app.domain.episode.EpisodeCompletionContext
import me.him188.ani.app.domain.episode.EpisodeWithAiringTime
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.EpisodeType
import me.him188.ani.datasources.api.PackedDate
import me.him188.ani.datasources.api.UTC9
import me.him188.ani.utils.serialization.BigNum
import kotlin.math.abs
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/*
 * 时间表按用户所在时区排: 每一集用它的真实播出时刻决定落在本地的哪一天、显示几点.
 *
 * Bangumi 分集的播出日期是日本的日子, 深夜档 (日本时间 24 点到凌晨 5 点) 常按「电视日」记在前一天; bangumi-data 给的是
 * 精确的首播时刻与周期. 两者合起来由 EpisodeCompletionContext.resolveEpisodeAirTime 推出真实播出时刻 (与「播没播出」
 * 的判断同一套), 再换到用户时区. 换算后可能跨到前一天或后一天 (北京时间的深夜档落到第二天, 美洲的日本白天档落到前一天),
 * 所以本地某一天在播的番要从名册里前后各一天找 (见 [scheduleCandidates]).
 */

/** 本地日期 [date] 可能有番在播的条目: 名册里 [date] 前一天、当天、后一天这三个星期几的, 按 id 去重. */
internal fun scheduleCandidates(calendar: Map<Int, List<ScheduleSubject>>, date: LocalDate): List<ScheduleSubject> =
    (-1..1).flatMap { offset -> calendar[date.plus(offset, DateTimeUnit.DAY).dayOfWeek.isoDayNumber].orEmpty() }
        .distinctBy { it.id }

/**
 * 本地日期 [date] 播出的各集, 按播出时刻排. 推不出精确时刻的 (没有播出规则, 或规则对不上这一集) 照旧按 Bangumi 的日期放,
 * 时刻记作当天 00:00 且 [EpisodeWithAiringTime.timeKnown] 为 `false` (界面不显示钟点).
 *
 * @param rules 条目的 bangumi-data 播出规则; 没有这个键 = 还没查, 值为 `null` = 查过但没有.
 */
internal fun scheduleItemsOn(
    date: LocalDate,
    candidates: List<ScheduleSubject>,
    episodes: Map<Int, List<ScheduleEpisode>>,
    rules: Map<Int, BroadcastRule?>,
    timeZone: TimeZone,
): List<EpisodeWithAiringTime> = candidates.flatMap { subject ->
    val recurrence = rules[subject.id]?.toRecurrenceOrNull()
    episodes[subject.id].orEmpty().mapNotNull { episode ->
        val airDate = runCatching { LocalDate.parse(episode.airDate) }.getOrNull() ?: return@mapNotNull null
        // 换到任何时区都跨不出前后一天; 远的集不必算
        if (abs(airDate.toEpochDays() - date.toEpochDays()) > 2) return@mapNotNull null
        val airTime = EpisodeCompletionContext.resolveEpisodeAirTime(PackedDate.parseFromDate(episode.airDate), recurrence)
        if (airTime != null && airTime.exact) {
            if (airTime.instant.toLocalDateTime(timeZone).date != date) return@mapNotNull null
            scheduleItem(subject, episode, airTime.instant, timeKnown = true)
        } else {
            if (airDate != date) return@mapNotNull null
            scheduleItem(subject, episode, date.atStartOfDayIn(timeZone), timeKnown = false)
        }
    }
}.sortedBy { it.airingTime }

private fun BroadcastRule.toRecurrenceOrNull(): SubjectRecurrence? {
    val start = runCatching { Instant.parse(startTime) }.getOrNull() ?: return null
    return SubjectRecurrence(startTime = start, interval = intervalDays.days)
}

private fun scheduleItem(subject: ScheduleSubject, episode: ScheduleEpisode, airingTime: Instant, timeKnown: Boolean) =
    EpisodeWithAiringTime(
        subject = LightSubjectInfo(
            subjectId = subject.id,
            name = subject.name,
            nameCn = subject.nameCn,
            imageLarge = subject.imageLarge,
        ),
        episode = LightEpisodeInfo(
            episodeId = episode.id,
            name = episode.name,
            nameCn = episode.nameCn,
            airDate = PackedDate.parseFromDate(episode.airDate),
            timezone = UTC9,
            sort = EpisodeSort(BigNum(episode.sort.ifBlank { "0" }), EpisodeType.MainStory),
            ep = episode.ep?.takeIf { it.isNotBlank() && it != "0" }
                ?.let { EpisodeSort(BigNum(it), EpisodeType.MainStory) },
        ),
        airingTime = airingTime,
        timeKnown = timeKnown,
    )
