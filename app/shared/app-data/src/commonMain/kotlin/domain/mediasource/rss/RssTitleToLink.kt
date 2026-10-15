/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.rss

import io.ktor.http.encodeURLParameter
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import me.him188.ani.app.domain.rss.RssEnclosure
import me.him188.ani.app.domain.rss.RssItem
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.source.MediaFetchRequest
import me.him188.ani.datasources.api.util.namedGroup
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * 把 RSS 条目的标题改写成视频直链 ([RssSearchConfig.titleToLink]).
 *
 * 用于「种子站上有发布标题、视频在另一个文件站上按标题存放」的情况: 文件站的地址哪里都不直接给, 但能从标题算出来.
 * 例如 ANi 的种子标题 `[ANi] Sousou no Frieren S02 /  葬送的芙莉蓮 第二季 - 38 [1080P][Baha][WEB-DL][AAC AVC][CHT][MP4]`,
 * 文件在 `resources.ani.rip/2026-1/[ANi] 葬送的芙莉蓮 第二季 - 38 [1080P][Baha][WEB-DL][AAC AVC][CHT]?d=mp4`.
 *
 * - [pattern] 整个匹配标题 (前后空白不算), 匹配不上的条目丢掉; 它的命名分组 `(?<名字>…)` 在 [url] 与 [title] 里用 `{名字}` 取.
 * - 季度变量: 文件站常按番开播的季度分目录. `{seasonYear}` / `{seasonMonth}` (1、4、7、10) / `{seasonMonth2}` (01、04、07、10)
 *   取自开播日期: 条目第一集在 Bangumi 上的播出日期 (查询请求的剧集表里有); 不知道时用同一部番 (命名分组 [groupBy] 的值相同)
 *   里最早一条的发布日期 —— 种子站的搜索结果常常只有最近的几十条, 跨季播出的长番最早一条已经是后一季的, 所以优先用条目的日期.
 *   跨季播出的番后面的集仍在开播那季. 离下一季开始不到 [seasonRoundUpDays] 天的算下一季 (提前几天首播的番归在正式开播那季).
 * - 知道条目开播日期时, 比它早一个月以上发布的条目是更早的季, 丢掉: 按名字搜会把前几季一起搜出来, 续作常常与第一季同名
 *   (「XX 第2部分」在种子站上就叫「XX」), 套上本季的目录必然是错的地址.
 * - [maxAgeDays]: 开播 (同上) 超过这么多天的丢掉, 文件站只留最近一段时间的文件.
 *
 * [url] 里的变量做 URL 编码 (空格成 `%20`), 写成 `{名字:raw}` 原样; [title] 里一律原样.
 * 改写后的条目的视频地址放在 enclosure 上 (类型 [mimeType]), 原来的链接 (种子页) 留着当来源页.
 */
@Serializable
data class RssTitleToLink(
    /** 匹配整个标题的正则, 带命名分组. */
    val pattern: String = "",
    /** 视频地址的模板. */
    val url: String = "",
    /** 改写后的标题模板; 为空则不改标题. 资源的条目名、集号、清晰度、字幕语言都从标题里认. */
    val title: String = "",
    /** 用哪个命名分组区分「同一部番」: 不知道条目开播日期时按组取最早一条当开播日期. 为空则全部条目算一部. */
    val groupBy: String = "",
    /** 离下一季不到这么多天的发布日期算下一季. 0 表示不提前. */
    val seasonRoundUpDays: Int = 0,
    /** 开播超过这么多天的番丢掉. 0 表示不限. */
    val maxAgeDays: Int = 0,
    /** 改写出的地址的视频类型. */
    val mimeType: String = "video/mp4",
)

/**
 * 按 [RssTitleToLink] 改写 [items]: 对得上的条目换成直链, 对不上的、太老的、更早一季的、要季度却不知道开播日期的丢掉.
 * [pattern][RssTitleToLink.pattern] 不是合法正则时什么都不留.
 *
 * @param subjectAirDate 条目开播日期 (见 [firstAirDate]), 不知道时为 null
 */
fun RssTitleToLink.rewrite(
    items: List<RssItem>,
    subjectAirDate: LocalDate? = null,
    now: Instant = Clock.System.now(),
): List<RssItem> {
    val regex = runCatching { Regex(pattern) }.getOrNull() ?: return emptyList()
    val groupNames = GROUP_NAME.findAll(pattern).map { it.groupValues[1] }.toList()
    val usesSeason = SEASON_VARIABLES.any { "{$it" in url || "{$it" in title }

    val matched = items.mapNotNull { item ->
        val match = regex.matchEntire(item.title.trim()) ?: return@mapNotNull null
        val groups = groupNames.associateWith { name ->
            runCatching { match.namedGroup(regex, name)?.value }.getOrNull().orEmpty()
        }
        item to groups
    }
    val earliestOfGroup: Map<String, LocalDate> = matched
        .groupBy { (_, groups) -> groups[groupBy].orEmpty() }
        .mapNotNull { (key, members) ->
            members.mapNotNull { (item, _) -> item.pubDate?.date }.minOrNull()?.let { key to it }
        }
        .toMap()
    val today = now.toLocalDateTime(TimeZone.UTC).date

    return matched.mapNotNull { (item, groups) ->
        val published = item.pubDate?.date
        if (subjectAirDate != null && published != null && published.daysUntil(subjectAirDate) > EARLIER_SEASON_DAYS) {
            return@mapNotNull null
        }
        val start = subjectAirDate ?: earliestOfGroup[groups[groupBy].orEmpty()]
        if (maxAgeDays > 0 && (start == null || start.daysUntil(today) > maxAgeDays)) return@mapNotNull null
        val season = start?.let { seasonOf(it, seasonRoundUpDays) }
        if (usesSeason && season == null) return@mapNotNull null
        val variables = buildMap {
            putAll(groups)
            if (season != null) {
                put("seasonYear", season.year.toString())
                put("seasonMonth", season.month.toString())
                put("seasonMonth2", season.month.toString().padStart(2, '0'))
            }
        }
        item.copy(
            title = if (title.isBlank()) item.title else expand(title, variables, encode = false),
            enclosure = RssEnclosure(
                url = expand(url, variables, encode = true),
                length = item.enclosure?.length ?: 0,
                type = mimeType,
            ),
        )
    }
}

/** 条目开播日期: 正片里最早的 Bangumi 播出日期. 剧集表为空或都没有日期时为 null. */
fun MediaFetchRequest.firstAirDate(): LocalDate? = episodes
    .filter { it.sort is EpisodeSort.Normal && it.airDate.isValid }
    .mapNotNull { runCatching { LocalDate(it.airDate.year, it.airDate.month, it.airDate.day) }.getOrNull() }
    .minOrNull()

/** 番开播的季度: 年份与季度第一个月 (1、4、7、10). */
internal data class AnimeSeason(val year: Int, val month: Int)

/** [date] 所在的季度; 离下一季开始不到 [roundUpDays] 天的算下一季. */
internal fun seasonOf(date: LocalDate, roundUpDays: Int): AnimeSeason {
    val quarterStart = LocalDate(date.year, (date.month.ordinal / 3) * 3 + 1, 1)
    val nextQuarterStart = quarterStart.plus(DatePeriod(months = 3))
    val start = if (roundUpDays > 0 && date.daysUntil(nextQuarterStart) <= roundUpDays) nextQuarterStart else quarterStart
    return AnimeSeason(start.year, start.month.ordinal + 1)
}

/** 展开 `{名字}` / `{名字:raw}`; 没有这个变量的原样留着. */
private fun expand(template: String, variables: Map<String, String>, encode: Boolean): String =
    VARIABLE.replace(template) { match ->
        val value = variables[match.groupValues[1]] ?: return@replace match.value
        if (encode && match.groupValues[2] != "raw") value.encodeURLParameter() else value
    }

private val GROUP_NAME = Regex("""\(\?<([A-Za-z][A-Za-z0-9]*)>""")
// 安卓的正则 (ICU) 不认没转义的 `}`
private val VARIABLE = Regex("""\{([A-Za-z][A-Za-z0-9]*)(?::(raw|url))?\}""")
private val SEASON_VARIABLES = listOf("seasonYear", "seasonMonth")

/** 比条目开播早这么多天以上发布的条目, 算更早的一季. */
private const val EARLIER_SEASON_DAYS = 30
