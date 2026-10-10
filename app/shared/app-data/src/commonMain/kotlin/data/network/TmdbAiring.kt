/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.network

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.minus
import kotlinx.datetime.todayIn
import kotlinx.serialization.Serializable
import me.him188.ani.app.data.models.subject.ContinueWatchingStatus
import me.him188.ani.app.data.models.subject.SubjectAiringInfo
import me.him188.ani.app.data.models.subject.SubjectAiringKind
import me.him188.ani.app.data.models.subject.SubjectProgressInfo
import kotlin.time.Clock

/**
 * TMDB 上本条目的播出情况, 随分集剧照一起取 (见 [TmdbEpisodeStills.airing]), 不单独发请求.
 *
 * 详情页的「未开播 / 连载中 / 已完结」按 Bangumi 的分集日期算, 而少有人维护的条目 (美漫居多) 分集常常没有日期、
 * 甚至一集都没录, 早就开播了还算成「未开播」. Bangumi 算出未开播时才看 TMDB, 见 [correctAiringByTmdb].
 */
@Serializable
data class TmdbAiring(
    /** 取数当天 (`YYYY-MM-DD`). 状态还会变时据此隔几天重取, 见 [needsRefresh]. */
    val fetchedOn: String? = null,
    /** 整部剧已完结 (TMDB `status` 为 `Ended` / `Canceled`). */
    val showEnded: Boolean = false,
    /**
     * 本条目对应的那一季, 从条目第一集对应的那集起按集号排的各集播出日 (`YYYY-MM-DD`, 未定为 null);
     * 电影为上映日一项. 认不出对应哪一季时为 null.
     */
    val seasonAirDates: List<String?>? = null,
    /** 下一季正片的首播日; 没有下一季或日期未定时为 null. */
    val nextSeasonAirDate: String? = null,
    /** 本条目对应一部电影: 上映即完结. */
    val movie: Boolean = false,
)

/**
 * 按 TMDB 判断本条目的播出状态; 给不出确定结论时返回 null, 由调用方沿用 Bangumi 的.
 *
 * - 整部剧完结 → 已完结.
 * - 其余只看本条目对应的那一季. Bangumi 有 n 集正片时只看这一季的前 n 集: Bangumi 把一季拆成上下两个条目、
 *   TMDB 合成一季时 (Re:ゼロ 第二季), 上半季的条目不该因为下半季在播而算连载中. n 比这一季的集数还多,
 *   说明条目横跨好几季 (海贼王 1100+ 集对 TMDB 第 1 季 61 集), 不按季判断.
 * - 一集没播 → 未开播; 下一季已经开播 → 已完结; 播了一部分 → 连载中.
 * - 全播完了而剧没标完结时, TMDB 可能只录了已播的几集. Bangumi 有集数 (那 n 集都播了)、整季同一天上线
 *   (Netflix 那种), 或最后一集已过 [SETTLE_DAYS] 天, 才算已完结; 否则算连载中.
 *
 * @param subjectEpisodeCount Bangumi 上本条目的正片集数, 0 = 没录分集.
 */
fun TmdbAiring.airingKind(today: LocalDate, subjectEpisodeCount: Int): SubjectAiringKind? {
    if (showEnded) return SubjectAiringKind.COMPLETED
    val season = seasonAirDates?.takeIf { it.isNotEmpty() } ?: return null
    val todayText = today.toString()
    fun aired(date: String?) = date != null && date <= todayText

    if (movie) {
        val date = season.first() ?: return null
        return if (aired(date)) SubjectAiringKind.COMPLETED else SubjectAiringKind.UPCOMING
    }
    if (subjectEpisodeCount > season.size) return null
    val window = if (subjectEpisodeCount > 0) season.take(subjectEpisodeCount) else season
    val airedCount = window.count(::aired)
    return when {
        airedCount == 0 -> if (window.any { it != null }) SubjectAiringKind.UPCOMING else null
        aired(nextSeasonAirDate) -> SubjectAiringKind.COMPLETED
        airedCount < window.size -> SubjectAiringKind.ON_AIR
        subjectEpisodeCount > 0 -> SubjectAiringKind.COMPLETED
        window.distinct().size == 1 -> SubjectAiringKind.COMPLETED
        window.filterNotNull().max() <= today.minus(SETTLE_DAYS, DateTimeUnit.DAY).toString() ->
            SubjectAiringKind.COMPLETED

        else -> SubjectAiringKind.ON_AIR
    }
}

/**
 * 这份播出情况该不该重取: 它还证明不了已经开播 (这一季没有一集播出日不晚于今天, 剧也没标完结),
 * 而且离取的那天已有 [REFRESH_DAYS] 天 —— TMDB 可能补了排期.
 *
 * 能证明开播就不再重取: 修正只是把 Bangumi 的「未开播」改成已开播, 这一步已经够了;
 * 之后连载中 → 已完结按缓存里的播出日随日期自己变 (见 [airingKind]), TMDB 事后补集、改标完结不再跟.
 * 认不出对应哪一季的也不重取: 重取多半还是认不出来.
 */
fun TmdbAiring.needsRefresh(today: LocalDate): Boolean {
    if (showEnded) return false
    val season = seasonAirDates ?: return false
    val todayText = today.toString()
    if (season.any { it != null && it <= todayText }) return false
    val fetched = fetchedOn?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return true
    return fetched.daysUntil(today) >= REFRESH_DAYS
}

/** 按 TMDB 修正后的播出状态与观看进度, 见 [correctAiringByTmdb]. */
data class TmdbCorrectedAiring(
    val airingInfo: SubjectAiringInfo,
    val progressInfo: SubjectProgressInfo?,
)

/**
 * Bangumi 的播出状态靠不住时按 TMDB ([airing], 见 [airingKind]) 往已开播 (连载中 / 已完结) 改. 靠不住的两种:
 * - 算出「未开播」: 分集没日期常常早就开播了还算成未开播;
 * - 一集都没录 (`!bangumiHasEpisodes`): 只能按条目信息估 (有开播日、没完结日就算连载中), 老电影也显示「连载中」.
 *
 * 只往后改不往回改: TMDB 也说没开播的不动, Bangumi 估成已完结的也不改回连载中. Bangumi 有分集且已算成连载中 /
 * 已完结的、TMDB 没匹配到的, 原样返回.
 *
 * 只改展示用的字段, 要播哪一集 ([SubjectProgressInfo.nextEpisodeIdToPlay]) 不动:
 * - 播出状态 [SubjectAiringInfo.kind]. Bangumi 集数是 0 时「全 N 话」改用 TMDB 这一季的集数,
 *   再没有就用 Bangumi 条目信息里的话数 ([subjectTotalEpisodes]); 仍是 0 时不修正, 免得显示「全 0 话」.
 * - 播放按钮的「还未开播 / x 开播」改成「开始观看」.
 */
fun correctAiringByTmdb(
    airingInfo: SubjectAiringInfo,
    progressInfo: SubjectProgressInfo?,
    airing: TmdbAiring?,
    today: LocalDate,
    subjectTotalEpisodes: Int,
    bangumiHasEpisodes: Boolean,
): TmdbCorrectedAiring {
    val unchanged = TmdbCorrectedAiring(airingInfo, progressInfo)
    if (airingInfo.kind != SubjectAiringKind.UPCOMING && bangumiHasEpisodes) return unchanged
    val kind = airing?.airingKind(today, airingInfo.mainEpisodeCount)
        ?.takeIf { it != SubjectAiringKind.UPCOMING }
        ?.let { maxOf(it, airingInfo.kind) }
        ?: return unchanged
    val episodeCount = airingInfo.mainEpisodeCount.takeIf { it > 0 }
        ?: airing.seasonAirDates?.size?.takeIf { it > 0 }
        ?: subjectTotalEpisodes.takeIf { it > 0 }
        ?: return unchanged
    if (kind == airingInfo.kind && episodeCount == airingInfo.mainEpisodeCount) return unchanged
    return TmdbCorrectedAiring(
        airingInfo.copy(kind = kind, mainEpisodeCount = episodeCount),
        if (progressInfo?.continueWatchingStatus is ContinueWatchingStatus.NotOnAir) {
            progressInfo?.copy(continueWatchingStatus = ContinueWatchingStatus.Start)
        } else {
            progressInfo
        },
    )
}

/**
 * 由剧集详情与各季分集组出 [TmdbAiring].
 *
 * @param seasons (季号, 季首播日), 来自剧集详情, 含没取分集的季.
 * @param seasonAirDates 取过分集的正片季: 季号 → (集号, 播出日).
 * @param start 本条目第一集对应的 (季, 集); 认不出对应哪一季时为 null, 此时只记整部剧的状态.
 */
internal fun tmdbTvAiring(
    status: String?,
    seasons: List<Pair<Int, String?>>,
    seasonAirDates: Map<Int, List<Pair<Int, String?>>>,
    start: Pair<Int, Int>?,
    today: LocalDate,
): TmdbAiring {
    val (season, firstEpisode) = start ?: (null to 0)
    return TmdbAiring(
        fetchedOn = today.toString(),
        showEnded = status == "Ended" || status == "Canceled",
        seasonAirDates = season?.let { seasonAirDates[it] }
            ?.filter { (number, _) -> number >= firstEpisode }
            ?.sortedBy { (number, _) -> number }
            ?.map { (_, date) -> date }
            ?.takeIf { it.isNotEmpty() },
        nextSeasonAirDate = season?.let { current ->
            seasons.filter { (number, _) -> number > current }.minByOrNull { (number, _) -> number }?.second
        }?.takeIf { it.isNotBlank() },
    )
}

/**
 * 条目名末尾「Season 2」这种西文季号 (美漫在 Bangumi 上的惯用写法); 没有时为 null.
 * 只在条目没有开播日、按日期认不出季时用 (见 `buildEpisodeStills`).
 */
internal fun tmdbSeasonNumberFromLatinSuffix(subjectName: String): Int? =
    LATIN_SEASON_SUFFIX.find(subjectName.trim())?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }

private val LATIN_SEASON_SUFFIX = Regex("""\bSeason\s*(\d{1,2})$""", RegexOption.IGNORE_CASE)

/** 本机今天的日期, 记 [TmdbAiring.fetchedOn] 与判断播出状态用. */
internal fun tmdbToday(): LocalDate = Clock.System.todayIn(TimeZone.currentSystemDefault())

/** 全部播完多少天后不再等 TMDB 补集, 认定这一季完结. */
private const val SETTLE_DAYS = 30

/** 状态还会变的播出情况隔多少天重取. */
private const val REFRESH_DAYS = 7
