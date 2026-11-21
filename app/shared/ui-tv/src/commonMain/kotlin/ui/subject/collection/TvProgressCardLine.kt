/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.collection

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import me.him188.ani.app.data.models.subject.SubjectCollectionInfo
import me.him188.ani.app.domain.episode.EpisodeCompletionContext.resolveEpisodeAirTime
import me.him188.ani.app.tools.WeekFormatter
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import kotlin.time.Clock
import kotlin.time.Instant

/*
 * 「继续观看」的卡片 (探索页那一行、系统主屏的继续观看) 也写追更信息, 与追更页同一套判断 ([tvProgressEntryOf]) 与说法 ([cardSubtitle]):
 * 在播的照追更页写番名下面那一行, 有新集的再画没看几集的角标; 不在播的 (补番) 写接下来看第几话 / 最后一话是第几话, 都看完了写「全 12 话已完结」.
 * 继续观看照旧按最近看的先后排, 不按这里的状态重排 (按状态看在追更页).
 */

/** 一张继续观看卡片上的追更信息: [subtitle] = 番名下面那一行 (null = 不写), [count] = 封面右上角的数字角标 (null = 不画). */
@Immutable
internal data class TvProgressCardLine(val subtitle: String?, val count: Int?)

/**
 * 在看的 [info] 在继续观看卡片上写什么. 在播的 (追更页里有它): 第二行同追更页, 角标只给「有新集」的 (追平的不画); 不在播的: 补番写
 * 「第 8 / 24 话」(接下来看的那一话 / 最后一话的序号, 序号不从 1 起的分割季照写), 都看完了写「全 12 话已完结」, 还没开播 (追更页只收 30 天内的)
 * 写开播日期; 没有分集时不写.
 */
internal fun tvProgressCardLine(
    info: SubjectCollectionInfo,
    strings: TvProgressStrings,
    now: Instant,
    timeZone: TimeZone,
    dayLabel: (LocalDate) -> String = { WeekFormatter.System.format(it) },
): TvProgressCardLine {
    tvProgressEntryOf(info, wished = false, now, timeZone)?.let { entry ->
        val count = entry.badgeCount.takeIf { entry.group == TvProgressGroup.NewEpisodes }
        return TvProgressCardLine(entry.cardSubtitle(strings, now, timeZone, dayLabel), count)
    }
    val mains = info.episodes.filter { it.isMainStory() }.sortedBy { it.episodeInfo.sort }
    val first = mains.firstOrNull() ?: return TvProgressCardLine(null, null)
    val last = mains.last()
    val premiere = resolveEpisodeAirTime(first.episodeInfo.airDate, info.recurrence)
    if (premiere != null && now < premiere.instant) {
        return TvProgressCardLine(strings.premiere.fill(premiere.dayAndClock(timeZone, dayLabel)), null)
    }
    val watched = mains.indexOfLast {
        it.collectionType == UnifiedCollectionType.DONE || it.collectionType == UnifiedCollectionType.DROPPED
    }
    val next = mains.getOrNull(watched + 1)
        ?: return TvProgressCardLine(strings.completed.fill(last.episodeInfo.sort.compact()), null)
    return TvProgressCardLine(strings.resume.fill(next.episodeInfo.sort.compact(), last.episodeInfo.sort.compact()), null)
}

/** 每分钟一次的当前时刻: 卡片上的「今天 / 明天」、某一集到点播出 (变成「有新集」) 跟着时间变. */
@Composable
internal fun rememberTvProgressNow(): Instant {
    val now by produceState(Clock.System.now()) {
        while (true) {
            delay(TV_PROGRESS_TICK_MILLIS)
            value = Clock.System.now()
        }
    }
    return now
}

/** 示例页 (海报墙大小的假页面) 继续观看那几张卡轮着用的追更信息: 有新集 / 本周更新 / 补番 / 下一集完结. */
internal fun tvProgressPreviewLines(strings: TvProgressStrings, now: Instant, timeZone: TimeZone): List<TvProgressCardLine> {
    val today = now.toLocalDateTime(timeZone).date
    fun day(offset: Int) = WeekFormatter.System.format(today.plus(offset, DateTimeUnit.DAY))
    return listOf(
        TvProgressCardLine(strings.updatesAt.fill(day(1), "23:00"), 2),
        TvProgressCardLine(strings.next.fill("${day(3)} 23:30", "7"), null),
        TvProgressCardLine(strings.resume.fill("8", "24"), null),
        TvProgressCardLine(strings.finaleDay.fill(day(5)), 1),
    )
}

/**
 * 系统主屏「继续观看」卡片上的追更那一行 (同探索页继续观看卡片的第二行, 见 [tvProgressCardLine]). 不在 Compose 里: 文案 ([load]) 一次取齐,
 * 每张卡照它写.
 */
class TvContinueWatchingLines private constructor(private val strings: TvProgressStrings) {
    fun of(info: SubjectCollectionInfo, now: Instant, timeZone: TimeZone = TimeZone.currentSystemDefault()): String? =
        tvProgressCardLine(info, strings, now, timeZone).subtitle

    companion object {
        suspend fun load(): TvContinueWatchingLines = TvContinueWatchingLines(loadTvProgressStrings())
    }
}
