/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.episode

import androidx.compose.ui.util.packInts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import me.him188.ani.app.data.models.subject.LightEpisodeInfo
import me.him188.ani.app.data.models.subject.LightSubjectInfo
import me.him188.ani.app.data.repository.episode.AnimeScheduleRepository
import me.him188.ani.app.domain.usecase.UseCase
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock
import kotlin.time.Instant

data class AiringScheduleForDate(
    val date: LocalDate,
    val list: List<EpisodeWithAiringTime>,
)

/**
 * @param airingTime 放送时间. [timeKnown] 为 `false` 时, 这是该剧集的 Bangumi 放送日期在客户端时区的 00:00.
 * @param timeKnown 放送时间是否精确已知. `false` 表示服务端只知道 Bangumi 的放送日期, 而没有可靠的放送时刻 (如没有 recurrence, 或与 Bangumi 日期不符).
 */
data class EpisodeWithAiringTime(
    val subject: LightSubjectInfo,
    val episode: LightEpisodeInfo,
    val airingTime: Instant,
    val timeKnown: Boolean,
) {
    val combinedId = packInts(subject.subjectId, episode.episodeId)
}

fun interface GetAnimeScheduleFlowUseCase : UseCase {
    operator fun invoke(today: LocalDate, timeZone: TimeZone): Flow<List<AiringScheduleForDate>>

    /**
     * 进程里还新鲜的那份时间表 (没有或已过期 = `null`): 页面首帧直接用它, 不先出骨架.
     * 见 [AnimeScheduleRepository.peekRecentAiringSchedules].
     */
    fun peekCached(today: LocalDate, timeZone: TimeZone): List<AiringScheduleForDate>? = null

    /** 丢掉缓存的时间表: 用户要求刷新时, 下一次必定走网络. */
    fun invalidateCache() {}

    /**
     * 预取今天的时间表 (如 TV 探索页焦点落到「新番时间表」上): 缓存还新鲜时是空操作; 请求在仓库自己的作用域里跑,
     * 调用方取消了也会跑完、落进缓存.
     */
    suspend fun prefetch(timeZone: TimeZone = TimeZone.currentSystemDefault(), clock: Clock = Clock.System) {
        invoke(clock.now().toLocalDateTime(timeZone).date, timeZone).first()
    }

    companion object {
        val OFFSET_DAYS_RANGE = (-7..7)
    }
}

class GetAnimeScheduleFlowUseCaseImpl(
    private val animeScheduleRepository: AnimeScheduleRepository,
    private val defaultDispatcher: CoroutineContext = Dispatchers.Default,
) : GetAnimeScheduleFlowUseCase {
    override fun invoke(today: LocalDate, timeZone: TimeZone): Flow<List<AiringScheduleForDate>> =
        animeScheduleRepository.recentAiringSchedulesFlow(today, timeZone)
            .flowOn(defaultDispatcher)

    override fun peekCached(today: LocalDate, timeZone: TimeZone): List<AiringScheduleForDate>? =
        animeScheduleRepository.peekRecentAiringSchedules(today, timeZone)

    override fun invalidateCache() = animeScheduleRepository.invalidateRecentAiringSchedules()
}
