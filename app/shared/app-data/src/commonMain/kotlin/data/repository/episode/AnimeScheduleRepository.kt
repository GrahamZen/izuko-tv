/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.repository.episode

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import me.him188.ani.app.data.models.schedule.AnimeSeasonId
import me.him188.ani.app.data.models.subject.LightEpisodeInfo
import me.him188.ani.app.data.models.subject.LightSubjectInfo
import me.him188.ani.app.data.models.subject.SubjectRecurrence
import me.him188.ani.app.data.network.AnimeScheduleService
import me.him188.ani.app.data.repository.Repository
import me.him188.ani.app.data.repository.RepositoryServiceUnavailableException
import me.him188.ani.app.domain.episode.AiringScheduleForDate
import me.him188.ani.app.domain.episode.EpisodeWithAiringTime
import me.him188.ani.client.models.AniAiringScheduleForDate
import me.him188.ani.client.models.AniScheduledAnimeEpisode
import me.him188.ani.client.models.AniScheduledAnimeEpisodeInfo
import me.him188.ani.client.models.AniScheduledAnimeSubject
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.EpisodeType
import me.him188.ani.datasources.api.PackedDate
import me.him188.ani.datasources.api.UTC9
import me.him188.ani.utils.logging.error
import me.him188.ani.utils.serialization.BigNum
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class AnimeScheduleRepository(
    private val animeScheduleService: AnimeScheduleService,
    private val updatePeriod: Duration = 1.hours,
    defaultDispatcher: CoroutineContext = Dispatchers.Default,
    clock: Clock = Clock.System,
) : Repository(defaultDispatcher) {
    private val recentSchedules = RecentAiringScheduleCache(
        scope = CoroutineScope(SupervisorJob() + defaultDispatcher),
        maxAge = updatePeriod,
        clock = clock,
    ) { today, timeZone ->
        animeScheduleService.getLatestAiringSchedule(today.toString(), timeZone.id)
            .list
            .map { it.toAiringScheduleForDate() }
    }

    /**
     * 获取可浏览的季度列表 (服务端不保证顺序).
     */
    suspend fun getSeasonIds(): List<AnimeSeasonId> = animeScheduleService.getSeasonIds()

    suspend fun getSubjectRecurrence(subjectId: Int): SubjectRecurrence? {
        try {
            return batchGetSubjectRecurrence(listOf(subjectId)).first()
        } catch (e: CancellationException) {
            throw e
        } catch (e: RepositoryServiceUnavailableException) {
            logger.error(e) { "Failed to get subject recurrence due to RepositoryServiceUnavailableException. Ignoring." }
            return null
        }
    }

    suspend fun batchGetSubjectRecurrence(subjectIds: List<Int>): List<SubjectRecurrence?> {
        return animeScheduleService.batchGetSubjectRecurrences(subjectIds)
    }

    /**
     * [today] 前后一周的时间表: 进程里有还新鲜的那份就先出它 (见 [RecentAiringScheduleCache]), 之后每 [updatePeriod] 拉一次.
     */
    fun recentAiringSchedulesFlow(today: LocalDate, timeZone: TimeZone): Flow<List<AiringScheduleForDate>> =
        recentSchedules.flow(today, timeZone).flowOn(defaultDispatcher)

    /** 进程里还新鲜的那份时间表; 没有或已过期 = `null`. */
    fun peekRecentAiringSchedules(today: LocalDate, timeZone: TimeZone): List<AiringScheduleForDate>? =
        recentSchedules.peek(today, timeZone)

    /** 丢掉缓存的时间表: 用户要求刷新时, 下一次必定走网络. */
    fun invalidateRecentAiringSchedules() = recentSchedules.invalidate()
}

private fun AniAiringScheduleForDate.toAiringScheduleForDate(): AiringScheduleForDate {
    return AiringScheduleForDate(
        date = LocalDate.parse(date),
        list = list.map { it.toEpisodeWithAiringTime() },
    )
}

private fun AniScheduledAnimeEpisode.toEpisodeWithAiringTime(): EpisodeWithAiringTime {
    return EpisodeWithAiringTime(
        subject = subject.toLightSubjectInfo(),
        episode = episode.toLightEpisodeInfo(),
        airingTime = Instant.parse(airingTime),
        timeKnown = timeKnown ?: true, // 旧服务端没有这个字段, 视为时间已知
    )
}

private fun AniScheduledAnimeSubject.toLightSubjectInfo(): LightSubjectInfo {
    return LightSubjectInfo(
        subjectId = subjectId.toInt(),
        name = name,
        nameCn = nameCn,
        imageLarge = imageLarge,
    )
}

private fun AniScheduledAnimeEpisodeInfo.toLightEpisodeInfo(): LightEpisodeInfo {
    return LightEpisodeInfo(
        episodeId = episodeId.toInt(),
        name = name,
        nameCn = nameCn,
        airDate = PackedDate.parseFromDate(airDate),
        timezone = UTC9,
        sort = EpisodeSort(BigNum(sort), parseEpisodeType(type)),
        ep = ep?.let { EpisodeSort(BigNum(it), parseEpisodeType(type)) },
    )
}

private fun parseEpisodeType(type: Int): EpisodeType? {
    return when (type) {
        0 -> EpisodeType.MainStory
        1 -> EpisodeType.SP
        2 -> EpisodeType.OP
        3 -> EpisodeType.ED
        4 -> EpisodeType.PV
        5 -> EpisodeType.MAD
        else -> null
    }
}
