/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.repository.episode

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import me.him188.ani.app.data.models.subject.LightEpisodeInfo
import me.him188.ani.app.data.models.subject.LightSubjectInfo
import me.him188.ani.app.data.models.subject.SubjectRecurrence
import me.him188.ani.app.data.network.schedule.BangumiScheduleSource
import me.him188.ani.app.data.network.schedule.ScheduleEpisode
import me.him188.ani.app.data.network.schedule.ScheduleSubject
import me.him188.ani.app.data.repository.Repository
import me.him188.ani.app.domain.episode.AiringScheduleForDate
import me.him188.ani.app.domain.episode.EpisodeWithAiringTime
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.EpisodeType
import me.him188.ani.datasources.api.PackedDate
import me.him188.ani.datasources.api.UTC9
import me.him188.ani.utils.serialization.BigNum
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * 时间表 (最近两周每天播出哪些番的哪一集).
 *
 * Ani 服务端有个算好的 `/v1/schedule/airing`, bangumi 没有等价物, 只能自己拼:
 * 名册来自 bangumi 的每日放送, 具体是哪一集来自分集的播出日期, 时刻来自 bangumi-data,
 * 三样都在 [BangumiScheduleSource] 里做了落盘缓存.
 *
 * **按天懒加载**: 一屏只看得到一天, 而一天只要那个星期几的十来个条目. flow 先按缓存把整屏发出去,
 * 再从今天开始往两边一天一天补, 每补完一天重发一次 —— 页面会一格格填上, 不会整屏空等.
 * 还没补到的那些天带 [AiringScheduleForDate.pending] 标记, 界面据此画骨架而不是"这一天没有新番".
 */
class AnimeScheduleRepository(
    private val source: BangumiScheduleSource,
    private val updatePeriod: Duration = 1.hours,
    defaultDispatcher: CoroutineContext = Dispatchers.Default,
) : Repository(defaultDispatcher) {
    suspend fun getSubjectRecurrence(subjectId: Int, firstAirDate: String? = null): SubjectRecurrence? {
        return source.recurrenceOf(subjectId, firstAirDate)
    }

    fun recentAiringSchedulesFlow(today: LocalDate, timeZone: TimeZone): Flow<List<AiringScheduleForDate>> = flow {
        val dates = OFFSET_DAYS_RANGE.map { today.plus(DatePeriod(days = it)) }
        val calendar = source.calendar()

        // 每天要用到的条目 = 这个星期几在播的那些
        val subjectsByDate = dates.associateWith { date ->
            calendar[date.dayOfWeek.isoDayNumber].orEmpty()
        }

        val episodes = mutableMapOf<Int, List<ScheduleEpisode>>()
        val rules = mutableMapOf<Int, String?>() // subjectId -> 播出时刻 (ISO), null = 没有

        suspend fun emitCurrent() {
            emit(
                dates.map { date ->
                    val roster = subjectsByDate.getValue(date)
                    AiringScheduleForDate(
                        date = date,
                        list = roster.mapNotNull { subject ->
                            val list = episodes[subject.id] ?: return@mapNotNull null
                            buildItem(subject, list, date, rules[subject.id], timeZone)
                        }.sortedBy { it.airingTime },
                        // 名册里还有没拿到分集的条目 = 这一天还没补完. 界面靠它区分"没有新番"与"还在加载"
                        pending = roster.any { it.id !in episodes },
                    )
                },
            )
        }

        // 先把缓存里已有的画出来, 一个请求都不发: 一天内来过第二次时整屏当场就是全的
        val cached = source.cachedEpisodesAndRules(subjectsByDate.values.flatten().map { it.id })
        episodes.putAll(cached.episodes)
        for ((id, rule) in cached.rules) rules[id] = rule.startTime
        emitCurrent()

        // 从今天往两边补, 先看到的先补
        for (date in dates.sortedBy { (it.toEpochDays() - today.toEpochDays()).let { d -> if (d < 0) -d * 2 else d * 2 - 1 } }) {
            val roster = subjectsByDate.getValue(date)
            val missing = roster.filter { it.id !in episodes }
            if (missing.isNotEmpty()) {
                // 整天一批并发取 (见 episodesOfMany): 逐个串行要十几个来回, 一天要等好几秒
                val fetched = source.episodesOfMany(missing.map { it.id })
                for (subject in missing) episodes[subject.id] = fetched[subject.id].orEmpty()
            }
            var changed = missing.isNotEmpty()
            for (subject in roster) {
                if (subject.id in rules) continue
                rules[subject.id] = source.broadcastRuleOf(
                    subject.id,
                    episodes[subject.id]?.firstOrNull()?.airDate,
                )?.startTime
                changed = true
            }
            if (changed) emitCurrent()
        }
    }.flowOn(defaultDispatcher)

    /**
     * 找出 [date] 当天播出的那一集. 找不到 (当天没有这部的更新) 返回 `null`.
     */
    private fun buildItem(
        subject: ScheduleSubject,
        episodes: List<ScheduleEpisode>,
        date: LocalDate,
        broadcastStartTime: String?,
        timeZone: TimeZone,
    ): EpisodeWithAiringTime? {
        val dateString = date.toString()
        val episode = episodes.firstOrNull { it.airDate == dateString } ?: return null
        val broadcastTime = broadcastTimeOf(broadcastStartTime, timeZone)
        return EpisodeWithAiringTime(
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
            airingTime = airingTimeOf(date, broadcastTime, timeZone),
            // 只有 bangumi-data 给出播出时刻才算"时刻已知"; 否则界面上不显示具体时刻
            timeKnown = broadcastTime != null,
        )
    }

    /**
     * 播出时刻: 有 bangumi-data 的规则就取它那个时刻 (只取时分, 日期用当天的),
     * 没有就退到日本时间当天 00:00 —— 与 `EpisodeCompletionContext` 判"已播出"的兜底一致.
     */
    private fun airingTimeOf(date: LocalDate, broadcastTime: LocalTime?, timeZone: TimeZone): Instant =
        LocalDateTime(date, broadcastTime ?: LocalTime(0, 0)).toInstant(timeZone)

    /**
     * bangumi-data 的播出规则里那个时刻 (只取时分); 没有规则或解析不了就是 `null`.
     */
    private fun broadcastTimeOf(broadcastStartTime: String?, timeZone: TimeZone): LocalTime? =
        broadcastStartTime?.let { runCatching { Instant.parse(it).toLocalDateTime(timeZone).time }.getOrNull() }

    companion object {
        val OFFSET_DAYS_RANGE = (-7..7)
    }
}
