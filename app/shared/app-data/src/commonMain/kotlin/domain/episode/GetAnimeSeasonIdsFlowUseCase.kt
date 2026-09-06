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
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
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
 * 季度是纯日历概念 ([AnimeSeasonId.fromDate]), 不需要问任何人 —— 原先走的是 Ani 服务器的
 * "可浏览季度"接口, 直连 bangumi 之后没有对应物, 而那个接口给的本来也就是最近这些季度.
 */
class GetAnimeSeasonIdsFlowUseCaseImpl(
    private val clock: Clock = Clock.System,
    private val timeZone: TimeZone = TimeZone.currentSystemDefault(),
) : GetAnimeSeasonIdsFlowUseCase {
    override fun invoke(): Flow<List<AnimeSeasonId>> = flow {
        val today = clock.now().toLocalDateTime(timeZone).date
        val current = AnimeSeasonId.fromDate(today.year, today.monthNumber)
        // 往回列 [SEASON_COUNT] 个季度: 一季三个月, 按月往回退再换算, 免得自己处理跨年
        val seasons = (0 until SEASON_COUNT).map { i ->
            val date = today.minus(DatePeriod(months = i * 3))
            AnimeSeasonId.fromDate(date.year, date.monthNumber)
        }
        emit(GetAnimeSeasonIdsFlowUseCase.sorted((seasons + current).distinct()))
    }

    private companion object {
        /** 往回列几个季度. 搜索页的年份筛选够用即可. */
        const val SEASON_COUNT = 24
    }
}
