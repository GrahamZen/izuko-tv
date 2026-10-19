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
import me.him188.ani.app.ui.lang.Lang
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
import me.him188.ani.app.ui.lang.collection_tv_progress_unwatched
import me.him188.ani.app.ui.lang.collection_tv_progress_unwatched_today
import me.him188.ani.app.ui.lang.collection_tv_progress_wish_aired
import me.him188.ani.app.ui.lang.collection_tv_progress_wish_aired_next
import me.him188.ani.app.ui.lang.collection_tv_progress_wish_started
import me.him188.ani.datasources.api.EpisodeSort
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Instant

/**
 * 追更页的文案 (格式串, 占位符照 strings_izuko.xml). 卡片小字按组写这一组最关心的那一项, 只出一行:
 * 有新集「未看 2 / 已更新 8」(今天刚更新的写「未看 1 · 今天更新」)、想看的开播了「已播 3 集 · 周四更新」、今天「22:40 · 第 4 话」、
 * 本周 / 已追平「周四 23:56 · 第 7 话」(没有日期写「下一集日期未定」)、即将开播「10-24 21:00 开播」. 只知道日期、不知道几点的不写时刻.
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
    val unwatchedToday: String,
    val unwatched: String,
    val wishAired: String,
    val wishAiredNext: String,
    val wishStarted: String,
    val next: String,
    val nextUnknown: String,
    val premiere: String,
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
internal fun rememberTvProgressStrings(): TvProgressStrings = TvProgressStrings(
    header = stringResource(Lang.collection_tv_progress_header),
    groupNew = stringResource(Lang.collection_tv_progress_group_new),
    groupWish = stringResource(Lang.collection_tv_progress_group_wish),
    groupToday = stringResource(Lang.collection_tv_progress_group_today),
    groupWeek = stringResource(Lang.collection_tv_progress_group_week),
    groupCaughtUp = stringResource(Lang.collection_tv_progress_group_caught_up),
    groupUpcoming = stringResource(Lang.collection_tv_progress_group_upcoming),
    unwatchedToday = stringResource(Lang.collection_tv_progress_unwatched_today),
    unwatched = stringResource(Lang.collection_tv_progress_unwatched),
    wishAired = stringResource(Lang.collection_tv_progress_wish_aired),
    wishAiredNext = stringResource(Lang.collection_tv_progress_wish_aired_next),
    wishStarted = stringResource(Lang.collection_tv_progress_wish_started),
    next = stringResource(Lang.collection_tv_progress_next),
    nextUnknown = stringResource(Lang.collection_tv_progress_next_unknown),
    premiere = stringResource(Lang.collection_tv_progress_premiere),
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
        TvProgressGroup.NewEpisodes -> {
            val latestDay = latestAirTime?.date(timeZone)
            if (latestDay == today) {
                strings.unwatchedToday.format(unwatched)
            } else {
                strings.unwatched.format(unwatched, latestSort?.compact().orEmpty())
            }
        }

        TvProgressGroup.WishAired -> when {
            airedCount < 0 -> strings.wishStarted
            next != null -> strings.wishAiredNext.format(airedCount, dayLabel(next.date(timeZone)))
            else -> strings.wishAired.format(airedCount)
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

/** 「周四 23:56」; 不知道几点的只写「周四」. */
private fun EpisodeAirTime.dayAndClock(timeZone: TimeZone, dayLabel: (LocalDate) -> String): String {
    val day = dayLabel(date(timeZone))
    return if (exact) "$day ${clock(timeZone)}" else day
}

/** 「23:56」. */
private fun EpisodeAirTime.clock(timeZone: TimeZone): String {
    val time = instant.toLocalDateTime(timeZone)
    return "${time.hour.toString().padStart(2, '0')}:${time.minute.toString().padStart(2, '0')}"
}

/** 集号不补零 (「8」「12.5」); 小字一行放不下几个字, 不用「08」. */
private fun EpisodeSort.compact(): String {
    val n = number ?: return toString()
    return if (n == n.toInt().toFloat()) n.toInt().toString() else n.toString()
}
