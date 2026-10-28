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
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import me.him188.ani.app.domain.episode.EpisodeAirTime
import me.him188.ani.app.ui.foundation.getStringOffMain
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.collection_tv_progress_completed
import me.him188.ani.app.ui.lang.collection_tv_progress_finale_at
import me.him188.ani.app.ui.lang.collection_tv_progress_finale_day
import me.him188.ani.app.ui.lang.collection_tv_progress_group_caught_up
import me.him188.ani.app.ui.lang.collection_tv_progress_group_new
import me.him188.ani.app.ui.lang.collection_tv_progress_group_today
import me.him188.ani.app.ui.lang.collection_tv_progress_group_upcoming
import me.him188.ani.app.ui.lang.collection_tv_progress_group_week
import me.him188.ani.app.ui.lang.collection_tv_progress_group_wish
import me.him188.ani.app.ui.lang.collection_tv_progress_header
import me.him188.ani.app.ui.lang.collection_tv_progress_next
import me.him188.ani.app.ui.lang.collection_tv_progress_next_unknown
import me.him188.ani.app.ui.lang.collection_tv_progress_premiere
import me.him188.ani.app.ui.lang.collection_tv_progress_resume
import me.him188.ani.app.ui.lang.collection_tv_progress_updates_at
import me.him188.ani.app.ui.lang.collection_tv_progress_updates_day
import me.him188.ani.app.ui.lang.collection_tv_progress_wish_started
import me.him188.ani.datasources.api.EpisodeSort
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Instant

/**
 * 追更页的文案 (格式串, 占位符照 strings_izuko.xml). 没看几集画在封面右上角的数字角标上 (见 [TvProgressEntry.badgeCount]), 卡片小字只出一行,
 * 写下一集什么时候:
 * 有新集 / 想看的开播了「周五 23:00 更新」, 下一集是最后一集写「周五 23:00 完结」, 已经播完写「全 12 话已完结」(想看的分集还没到写「已开播」);
 * 今天「22:40 · 第 4 话」; 本周 / 已追平「周四 23:56 · 第 7 话」; 即将开播「10-24 21:00 开播」. 没有日期写「下一集日期未定」, 只知道日期的不写时刻.
 * [resume] 是继续观看卡片上补番的那一行「第 8 / 24 话」(见 [tvProgressCardLine]).
 */
@Immutable
internal data class TvProgressStrings(
    val header: String,
    val groupNew: String,
    val groupWish: String,
    val groupToday: String,
    val groupWeek: String,
    val groupCaughtUp: String,
    val groupUpcoming: String,
    val updatesDay: String,
    val updatesAt: String,
    val finaleDay: String,
    val finaleAt: String,
    val completed: String,
    val wishStarted: String,
    val next: String,
    val nextUnknown: String,
    val premiere: String,
    val resume: String,
) {
    fun groupTitle(group: TvProgressGroup): String = when (group) {
        TvProgressGroup.NewEpisodes -> groupNew
        TvProgressGroup.WishAired -> groupWish
        TvProgressGroup.Today -> groupToday
        TvProgressGroup.ThisWeek -> groupWeek
        TvProgressGroup.CaughtUp -> groupCaughtUp
        TvProgressGroup.Upcoming -> groupUpcoming
    }

    /** 组标题: 组名 + 几部. */
    fun headerOf(section: TvProgressSection): String = header.format(groupTitle(section.group), section.entries.size)
}

@Composable
internal fun rememberTvProgressStrings(): TvProgressStrings = tvProgressStringsOf { stringResource(it) }

/** 同 [rememberTvProgressStrings], 给不在 Compose 里的 (系统主屏频道) 用. */
internal suspend fun loadTvProgressStrings(): TvProgressStrings = tvProgressStringsOf { getStringOffMain(it) }

private inline fun tvProgressStringsOf(get: (StringResource) -> String): TvProgressStrings = TvProgressStrings(
    header = get(Lang.collection_tv_progress_header),
    groupNew = get(Lang.collection_tv_progress_group_new),
    groupWish = get(Lang.collection_tv_progress_group_wish),
    groupToday = get(Lang.collection_tv_progress_group_today),
    groupWeek = get(Lang.collection_tv_progress_group_week),
    groupCaughtUp = get(Lang.collection_tv_progress_group_caught_up),
    groupUpcoming = get(Lang.collection_tv_progress_group_upcoming),
    updatesDay = get(Lang.collection_tv_progress_updates_day),
    updatesAt = get(Lang.collection_tv_progress_updates_at),
    finaleDay = get(Lang.collection_tv_progress_finale_day),
    finaleAt = get(Lang.collection_tv_progress_finale_at),
    completed = get(Lang.collection_tv_progress_completed),
    wishStarted = get(Lang.collection_tv_progress_wish_started),
    next = get(Lang.collection_tv_progress_next),
    nextUnknown = get(Lang.collection_tv_progress_next_unknown),
    premiere = get(Lang.collection_tv_progress_premiere),
    resume = get(Lang.collection_tv_progress_resume),
)

/**
 * 卡片番名下面那一行小字. [dayLabel] 把日期写成「今天 / 明天 / 周四 / 下周一 / 10-24」(WeekFormatter), [now] 与 [timeZone] 定「今天」.
 */
internal fun TvProgressEntry.cardSubtitle(
    strings: TvProgressStrings,
    now: Instant,
    timeZone: TimeZone,
    dayLabel: (LocalDate) -> String,
): String {
    val today = now.toLocalDateTime(timeZone).date
    val next = nextAirTime
    return when (group) {
        TvProgressGroup.NewEpisodes, TvProgressGroup.WishAired -> when {
            airedCount < 0 -> strings.wishStarted
            nextSort == null -> strings.completed.format(latestSort?.compact().orEmpty())
            next == null -> strings.nextUnknown
            else -> {
                val day = dayLabel(next.date(timeZone))
                when {
                    nextIsFinal && next.exact -> strings.finaleAt.format(day, next.clock(timeZone))
                    nextIsFinal -> strings.finaleDay.format(day)
                    next.exact -> strings.updatesAt.format(day, next.clock(timeZone))
                    else -> strings.updatesDay.format(day)
                }
            }
        }

        TvProgressGroup.Today -> if (next == null) {
            strings.nextUnknown
        } else {
            val time = if (next.exact) next.clock(timeZone) else dayLabel(today)
            strings.next.format(time, nextSort?.compact().orEmpty())
        }

        TvProgressGroup.ThisWeek, TvProgressGroup.CaughtUp -> if (next == null) {
            strings.nextUnknown
        } else {
            strings.next.format(next.dayAndClock(timeZone, dayLabel), nextSort?.compact().orEmpty())
        }

        TvProgressGroup.Upcoming -> if (next == null) {
            strings.nextUnknown
        } else {
            strings.premiere.format(next.dayAndClock(timeZone, dayLabel))
        }
    }
}

/** 封面右上角数字角标上的数: 没看几集 (有新集 / 想看的开播了); 没有没看的不画. */
internal val TvProgressEntry.badgeCount: Int? get() = unwatched.takeIf { it > 0 }

/** 「周四 23:56」; 不知道几点的只写「周四」. */
internal fun EpisodeAirTime.dayAndClock(timeZone: TimeZone, dayLabel: (LocalDate) -> String): String {
    val day = dayLabel(date(timeZone))
    return if (exact) "$day ${clock(timeZone)}" else day
}

/** 「23:56」. */
private fun EpisodeAirTime.clock(timeZone: TimeZone): String {
    val time = instant.toLocalDateTime(timeZone)
    return "${time.hour.toString().padStart(2, '0')}:${time.minute.toString().padStart(2, '0')}"
}

/** 集号不补零 (「8」「12.5」); 小字一行放不下几个字, 不用「08」. */
internal fun EpisodeSort.compact(): String {
    val n = number ?: return toString()
    return if (n == n.toInt().toFloat()) n.toInt().toString() else n.toString()
}
