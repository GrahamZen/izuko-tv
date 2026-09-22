/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.episode

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import me.him188.ani.app.data.models.schedule.AnimeSeason
import me.him188.ani.app.data.models.schedule.AnimeSeasonId
import me.him188.ani.app.domain.usecase.UseCase
import kotlin.time.Clock

/**
 * 提供可浏览的季度列表 (按时间降序, 最新在前).
 *
 * 调用方可按需使用全部列表 (如搜索页的年份筛选), 或取 [List.first] 作为最新季度.
 */
fun interface GetAnimeSeasonIdsFlowUseCase : UseCase {
    operator fun invoke(): Flow<List<AnimeSeasonId>>

    companion object {
        /**
         * 将季度列表按时间降序排列 (最新在前).
         */
        fun sorted(seasons: List<AnimeSeasonId>): List<AnimeSeasonId> = seasons.sortedDescending()
    }
}

/**
 * 本地按当前日期推算, 不发请求.
 *
 * 季度是纯日历概念 ([AnimeSeasonId.fromDate]), 列出 [FIRST_YEAR] 冬季直到当前季度的全部季度即可.
 * 年份筛选最终只是换算成 bangumi 的播出日期区间 (见 `SubjectSearchQuery.toBangumiAirDates`),
 * 任意年份都能搜, 所以这里不需要任何服务端数据.
 */
class GetAnimeSeasonIdsFlowUseCaseImpl(
    private val clock: Clock = Clock.System,
    private val timeZone: TimeZone = TimeZone.currentSystemDefault(),
) : GetAnimeSeasonIdsFlowUseCase {
    override fun invoke(): Flow<List<AnimeSeasonId>> = flow {
        val today = clock.now().toLocalDateTime(timeZone).date
        val current = AnimeSeasonId.fromDate(today.year, today.monthNumber)
        val seasons = buildList {
            for (year in FIRST_YEAR..current.year) {
                for (season in AnimeSeason.entries) {
                    // 当前季度之后的不列: 今年的后几个季度还没开始
                    AnimeSeasonId(year, season).takeIf { it <= current }?.let { add(it) }
                }
            }
        }
        emit(GetAnimeSeasonIdsFlowUseCase.sorted(seasons))
    }

    private companion object {
        /** 列表起点. 取番剧索引能追溯到的最早年份, 再往前没有可筛的内容. */
        const val FIRST_YEAR = 1943
    }
}
