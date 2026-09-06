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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
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
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
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
 * 再从今天开始往两边补 (缺的条目按远近排队并发取), 取到一部重发一次 —— 页面会一格格填上, 不会整屏空等.
 * 还没补到的那些天带 [AiringScheduleForDate.pending] 标记, 界面据此画骨架而不是"这一天没有新番".
 */
class AnimeScheduleRepository(
    private val source: BangumiScheduleSource,
    @Suppress("unused")
    private val updatePeriod: Duration = 1.hours,
    defaultDispatcher: CoroutineContext = Dispatchers.Default,
) : Repository(defaultDispatcher) {
    suspend fun getSubjectRecurrence(subjectId: Int, firstAirDate: String? = null): SubjectRecurrence? {
        return source.recurrenceOf(subjectId, firstAirDate)
    }

    fun recentAiringSchedulesFlow(today: LocalDate, timeZone: TimeZone): Flow<List<AiringScheduleForDate>> = channelFlow {
        val dates = OFFSET_DAYS_RANGE.map { today.plus(DatePeriod(days = it)) }
        val calendar = source.calendar()
        logger.info { "bgm-direct: schedule 名册 ${calendar.values.sumOf { it.size }} 部 (7 天), 窗口 ${dates.first()}..${dates.last()}" }

        // 每天要用到的条目 = 这个星期几在播的那些
        val subjectsByDate = dates.associateWith { date ->
            calendar[date.dayOfWeek.isoDayNumber].orEmpty()
        }

        // 下面两份只在这个协程里读写; 取分集的那些协程只经 arrivals 交结果
        val episodes = mutableMapOf<Int, List<ScheduleEpisode>>()
        val rules = mutableMapOf<Int, String?>() // subjectId -> 播出时刻 (ISO), null = 没有

        suspend fun sendCurrent() {
            send(
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

        suspend fun resolveRule(subjectId: Int) {
            if (subjectId in rules) return
            rules[subjectId] = source.broadcastRuleOf(subjectId, episodes[subjectId]?.firstOrNull()?.airDate)?.startTime
        }

        // 先把缓存里已有的画出来, 一个请求都不发: 一天内来过第二次时整屏当场就是全的
        val cached = source.cachedEpisodesAndRules(subjectsByDate.values.flatten().map { it.id })
        episodes.putAll(cached.episodes)
        for ((id, rule) in cached.rules) rules[id] = rule.startTime
        sendCurrent()

        // 从今天往两边补, 先看到的先补: 各天缺的条目按这个先后一起排队并发取 (见 episodesOfMany), 取到一部放进来一部.
        // bgm 偶尔有请求挂到超时 —— 一部卡住只晚它自己, 不挡同一天的其余几部, 也不挡后面几天
        val order = dates.sortedBy { (it.toEpochDays() - today.toEpochDays()).let { d -> if (d < 0) -d * 2 else d * 2 - 1 } }
        val orderedIds = order.flatMap { subjectsByDate.getValue(it) }.map { it.id }.distinct()
        val missing = orderedIds.filterTo(LinkedHashSet()) { it !in episodes }
        val arrivals = Channel<Pair<Int, List<ScheduleEpisode>>>(Channel.UNLIMITED)
        if (missing.isNotEmpty()) {
            launch {
                try {
                    source.episodesOfMany(missing) { id, list -> arrivals.send(id to list) }
                } finally {
                    arrivals.close()
                }
            }
        } else {
            arrivals.close()
        }

        // 分集已在缓存里、播出时刻还没查过的, 趁等第一批回来时补上 (多半也在缓存里)
        var rulesChanged = false
        for (id in orderedIds) {
            if (id in episodes && id !in rules) {
                resolveRule(id)
                rulesChanged = true
            }
        }
        if (rulesChanged) sendCurrent()

        val completedDates = order.filterTo(HashSet()) { date -> subjectsByDate.getValue(date).none { it.id in missing } }
        for (first in arrivals) {
            // 前后脚回来的合成一次发出去
            var next: Pair<Int, List<ScheduleEpisode>>? = first
            while (next != null) {
                val (id, list) = next
                episodes[id] = list
                resolveRule(id)
                next = arrivals.tryReceive().getOrNull()
            }
            sendCurrent()
            for (date in order) {
                val roster = subjectsByDate.getValue(date)
                if (date in completedDates || roster.any { it.id !in episodes }) continue
                completedDates += date
                logger.info {
                    val items = roster.count { episodes[it.id]?.any { e -> e.airDate == date.toString() } == true }
                    "bgm-direct: schedule $date 补完: 名册 ${roster.size} 部 (回源 ${roster.count { it.id in missing }}), 当天有更新 $items 条"
                }
            }
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
