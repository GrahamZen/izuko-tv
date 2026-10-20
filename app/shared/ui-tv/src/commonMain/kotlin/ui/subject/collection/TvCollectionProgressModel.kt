/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.collection

import androidx.compose.runtime.Immutable
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.asTimeZone
import kotlinx.datetime.toLocalDateTime
import me.him188.ani.app.data.models.episode.EpisodeCollectionInfo
import me.him188.ani.app.data.models.subject.SubjectCollectionInfo
import me.him188.ani.app.domain.episode.EpisodeAirTime
import me.him188.ani.app.domain.episode.EpisodeCompletionContext.resolveEpisodeAirTime
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.EpisodeType
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/*
 * 追番页「追更」页的分组 (纯函数, 页面与测试共用). 只收在播的番: 在看里还在连载的 (含刚播完、还有没看的), 想看里已经开播或 30 天内开播的;
 * 补番 (早已完结) 留在「在看」标签里.
 */

/** 追更页的组, 按页面上从上到下的顺序. */
enum class TvProgressGroup {
    /** 在看, 已播的集里看到的那一集之后还有没看的. */
    NewEpisodes,

    /** 想看, 已经开播. */
    WishAired,

    /** 在看且追平了, 下一集今天播. */
    Today,

    /** 在看且追平了, 下一集 7 天内播. */
    ThisWeek,

    /** 在看且追平了, 下一集更远或日期未定. */
    CaughtUp,

    /** 在看 / 想看, 还没开播, 30 天内开播. */
    Upcoming,
}

/**
 * 追更页的一部.
 *
 * @property unwatched 从看到的最后一集往后数到已播最新一集, 还有几集没看 (跳着看漏标的中间集不算)
 * @property airedCount 已播的正片集数; -1 = 分集还没取到, 只知道已经开播
 * @property latestSort 已播最新一集的序号 (没播过为 null)
 * @property latestAirTime 已播最新一集的播出时刻 (想看的番分集还没到时是首播)
 * @property nextSort 下一集 (还没播的第一集) 的序号; 未开播时是第一集
 * @property nextAirTime 下一集的播出时刻 ([EpisodeAirTime.exact] = 知道几点); 未开播时是首播
 * @property nextIsFinal 下一集是最后一集 (再播一集就完结)
 */
@Immutable
data class TvProgressEntry(
    val info: SubjectCollectionInfo,
    val group: TvProgressGroup,
    val unwatched: Int,
    val airedCount: Int,
    val latestSort: EpisodeSort?,
    val latestAirTime: EpisodeAirTime?,
    val nextSort: EpisodeSort?,
    val nextAirTime: EpisodeAirTime?,
    val nextIsFinal: Boolean = false,
) {
    val subjectId: Int get() = info.subjectId
}

/** 追更页的一组 (没有条目的组不出现). */
@Immutable
data class TvProgressSection(
    val group: TvProgressGroup,
    val entries: List<TvProgressEntry>,
)

/**
 * 把在看 [doing] 与想看 [wish] 按 [now] 分组, 返回有条目的组 (按 [TvProgressGroup] 的顺序). 同一部两边都有时按在看算.
 * [timeZone] 决定「今天」是哪一天.
 */
fun tvProgressSections(
    doing: List<SubjectCollectionInfo>,
    wish: List<SubjectCollectionInfo>,
    now: Instant,
    timeZone: TimeZone,
): List<TvProgressSection> {
    val seen = HashSet<Int>()
    val entries = ArrayList<TvProgressEntry>()
    for (info in doing) {
        if (seen.add(info.subjectId)) tvProgressEntryOf(info, wished = false, now, timeZone)?.let(entries::add)
    }
    for (info in wish) {
        if (seen.add(info.subjectId)) tvProgressEntryOf(info, wished = true, now, timeZone)?.let(entries::add)
    }
    return TvProgressGroup.entries.mapNotNull { group ->
        val inGroup = entries.filter { it.group == group }
        if (inGroup.isEmpty()) null else TvProgressSection(group, inGroup.sortedWith(tvProgressOrder(group)))
    }
}

/** 一部算进哪一组; 不在播 (早已完结 / 开播还远 / 日期未知) 时 null. */
internal fun tvProgressEntryOf(
    info: SubjectCollectionInfo,
    wished: Boolean,
    now: Instant,
    timeZone: TimeZone,
): TvProgressEntry? {
    val recurrence = info.recurrence
    val mains = info.episodes
        .filter { it.isMainStory() }
        .sortedBy { it.episodeInfo.sort }
    if (mains.isEmpty()) return tvProgressEntryWithoutEpisodes(info, wished, now)

    val airTimes = mains.map { resolveEpisodeAirTime(it.episodeInfo.airDate, recurrence) }
    val airedIndex = airTimes.indexOfLast { it != null && now >= it.instant }
    val watchedIndex = mains.indexOfLast {
        it.collectionType == UnifiedCollectionType.DONE || it.collectionType == UnifiedCollectionType.DROPPED
    }
    val next = mains.getOrNull(airedIndex + 1)
    val nextAir = airTimes.getOrNull(airedIndex + 1)

    if (airedIndex < 0) {
        // 还没开播: 首播 30 天内的才放进来
        val premiere = nextAir ?: return null
        if (premiere.instant - now > TV_PROGRESS_UPCOMING_WINDOW) return null
        return TvProgressEntry(
            info, TvProgressGroup.Upcoming,
            unwatched = 0, airedCount = 0, latestSort = null, latestAirTime = null,
            nextSort = next?.episodeInfo?.sort, nextAirTime = premiere,
        )
    }

    val latestAir = airTimes[airedIndex]!!
    val unwatched = (airedIndex - watchedIndex).coerceAtLeast(0)
    val sinceLatest = now - latestAir.instant
    val airing = when {
        // 播完了: 最后一集刚播 (两周内) 还没看完的仍算 —— 这周播完的番照样是「有新集」
        next == null -> sinceLatest <= TV_PROGRESS_JUST_COMPLETED_WINDOW && unwatched > 0
        nextAir != null -> true
        // 下一集日期未定: 最近一个月还在播才算连载中 (bgm 列了没日期的集, 而番早就停了)
        else -> sinceLatest <= TV_PROGRESS_STALE_WINDOW
    }
    if (!airing) return null

    val group = when {
        wished -> TvProgressGroup.WishAired
        unwatched > 0 -> TvProgressGroup.NewEpisodes
        nextAir != null && nextAir.date(timeZone) == now.toLocalDateTime(timeZone).date -> TvProgressGroup.Today
        nextAir != null && nextAir.instant - now <= TV_PROGRESS_THIS_WEEK_WINDOW -> TvProgressGroup.ThisWeek
        else -> TvProgressGroup.CaughtUp
    }
    return TvProgressEntry(
        info, group,
        unwatched = unwatched,
        airedCount = airedIndex + 1,
        latestSort = mains[airedIndex].episodeInfo.sort,
        latestAirTime = latestAir,
        nextSort = next?.episodeInfo?.sort,
        nextAirTime = nextAir,
        nextIsFinal = next != null && airedIndex + 1 == mains.lastIndex,
    )
}

/**
 * 分集还没取到 (想看的番, 收藏列表只给条目): 按条目的开播日期估. 最近 [TV_PROGRESS_RECENT_PREMIERE_WINDOW] 内开播的算已开播
 * (想看 → 「想看的开播了」, 分集到了再细分); 30 天内开播的算即将开播. 在看的番分集由收藏同步补齐, 没有分集时先不放.
 */
private fun tvProgressEntryWithoutEpisodes(info: SubjectCollectionInfo, wished: Boolean, now: Instant): TvProgressEntry? {
    val subject = info.subjectInfo
    val premiere = resolveEpisodeAirTime(subject.airDate, info.recurrence) ?: return null
    val sincePremiere = now - premiere.instant
    return when {
        sincePremiere < Duration.ZERO -> {
            if (-sincePremiere > TV_PROGRESS_UPCOMING_WINDOW) return null
            TvProgressEntry(info, TvProgressGroup.Upcoming, 0, 0, null, null, null, premiere)
        }

        wished && sincePremiere <= TV_PROGRESS_RECENT_PREMIERE_WINDOW ->
            TvProgressEntry(info, TvProgressGroup.WishAired, 0, -1, null, premiere, null, null)

        else -> null
    }
}

/** 组内顺序: 有新集与开播了的按最近一集播出时刻新的在前, 待播的按下一集时刻, 即将开播按首播. */
private fun tvProgressOrder(group: TvProgressGroup): Comparator<TvProgressEntry> = when (group) {
    TvProgressGroup.NewEpisodes, TvProgressGroup.WishAired ->
        compareByDescending<TvProgressEntry> { it.latestAirTime?.instant }.thenByDescending { it.info.lastUpdated }

    TvProgressGroup.Today, TvProgressGroup.ThisWeek, TvProgressGroup.CaughtUp, TvProgressGroup.Upcoming ->
        compareBy<TvProgressEntry, Instant?>(nullsLast()) { it.nextAirTime?.instant }.thenByDescending { it.info.lastUpdated }
}

private fun EpisodeCollectionInfo.isMainStory(): Boolean {
    val type = episodeInfo.type
    return (type == null || type == EpisodeType.MainStory) && episodeInfo.sort is EpisodeSort.Normal
}

/**
 * 这一集播出的那一天: 知道几点的按 [timeZone] 换算; 只知道日期的就是它自己的日期 (那时刻是东京当天 0 点, 换算到西边的时区会落到前一天).
 */
internal fun EpisodeAirTime.date(timeZone: TimeZone): LocalDate =
    instant.toLocalDateTime(if (exact) timeZone else TV_PROGRESS_AIR_DATE_ZONE).date

/** 分集日期的时区 (bangumi 记的是条目所在地区的日期, 通常是日本). */
private val TV_PROGRESS_AIR_DATE_ZONE: TimeZone = UtcOffset(hours = 9).asTimeZone()

/** 「即将开播」收多远以内的. */
internal val TV_PROGRESS_UPCOMING_WINDOW: Duration = 30.days

/** 「本周更新」: 下一集多久以内播. */
internal val TV_PROGRESS_THIS_WEEK_WINDOW: Duration = 7.days

/** 最后一集播完多久以内、还没看完的仍算在播. */
internal val TV_PROGRESS_JUST_COMPLETED_WINDOW: Duration = 14.days

/** 下一集日期未定时, 最近一集多久以内播过才算还在连载. */
internal val TV_PROGRESS_STALE_WINDOW: Duration = 30.days

/** 想看的番分集还没到时, 开播多久以内的算「开播了」(差不多一季加半个月). */
internal val TV_PROGRESS_RECENT_PREMIERE_WINDOW: Duration = 105.days
