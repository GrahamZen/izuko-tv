/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.network

import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlin.math.absoluteValue

/**
 * TMDB 按某种语言给的集名. 那种语言没有翻译时 TMDB 拿「Episode 3」「第 3 話」这类占位名顶上 —— 它不是集名,
 * 返回 null, 由 Bangumi 的集名兜底.
 */
internal fun tmdbLocalizedEpisodeName(name: String?): String? {
    val trimmed = name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return trimmed.takeUnless { TMDB_PLACEHOLDER_EPISODE_NAME.matches(it) }
}

/** 「词 + 集号」或「第 N 話」式的占位集名 (各语言里 TMDB 常见的写法). */
private val TMDB_PLACEHOLDER_EPISODE_NAME = Regex(
    """(episode|ep\.?|épisode|episodio|episódio|folge|odcinek|aflevering|avsnitt|jakso|эпизод|серия|епізод|에피소드|エピソード|tập|ตอนที่)\s*\d+""" +
        """|第\s*\d+\s*[話话集回]|\d+\s*[화회]""",
    // 不区分大小写要连非 ASCII 字母一起 (Épisode): 内联的 (?i) 只管 ASCII, IGNORE_CASE 会顺带打开 Unicode 大小写
    RegexOption.IGNORE_CASE,
)

/**
 * 讲本条目自己的简介 (见 [TmdbEpisodeStills.subjectOverview]): 本条目对应那一季 ([ownSeason]) 自己的简介;
 * 没有时整部的简介只在本条目就是第一季时才算讲它 —— 续作季拿整部的简介讲的是前面的事, 不如 Bangumi 那份.
 */
internal fun tmdbSubjectOverview(ownSeasonOverview: String?, showOverview: String?, ownSeason: Int?): String? =
    ownSeasonOverview ?: showOverview?.takeIf { ownSeason == 1 }

/** 讲本条目的 TMDB 文字 ([language] 语言, 同 [TmdbEpisodeStills] 的 subjectTitle / subjectOverview): 详情状态与电视各页的共享表用它. */
data class TmdbSubjectText(
    val language: String,
    val title: String?,
    val overview: String?,
)

fun TmdbEpisodeStills.subjectText() = TmdbSubjectText(language, subjectTitle, subjectOverview)

/**
 * 本条目在 TMDB 上的本地化标题 (见 [TmdbEpisodeStills.subjectTitle]). [ownStart] 是本条目从哪一季第几集开始:
 *  - 第一季从头开始: 剧名;
 *  - 续作季从头开始: 剧名拼季名 —— 「Season 3」这类序号季名用空格接 (SPY x FAMILY Season 3), 有名字的用冒号接
 *    (Bleach: Thousand-Year Blood War); 季名里已经带着剧名就只用季名;
 *  - 特别篇、从一季中间开始 (同一季拆成几部时几部会拼成同一个名字)、认不出季: null.
 */
internal fun tmdbSubjectTitle(showName: String?, ownStart: Pair<Int, Int>?, seasonName: String?): String? {
    val show = tmdbLocalizedName(showName) ?: return null
    val (season, firstEpisode) = ownStart ?: return null
    if (season <= 0 || firstEpisode != 1) return null
    if (season == 1) return show
    val name = tmdbLocalizedName(seasonName) ?: return null
    return when {
        name.contains(show, ignoreCase = true) -> name
        TMDB_NUMBERED_SEASON_NAME.matches(name) -> "$show $name"
        else -> "$show: $name"
    }
}

/**
 * TMDB 按某种 (非中日韩) 语言给的名字: 那种语言没有译名时 TMDB 给原名 (日文、中文、韩文), 它不是译名, 返回 null.
 */
internal fun tmdbLocalizedName(name: String?): String? {
    val trimmed = name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return trimmed.takeUnless { CJK_CHAR.containsMatchIn(it) }
}

/** 「Season 2」「Part 3」式的序号季名. */
private val TMDB_NUMBERED_SEASON_NAME = Regex("""(season|series|part|cour)\s*\d+""", RegexOption.IGNORE_CASE)

/** 汉字、假名、谚文. 用码位范围: Android 的正则引擎不支持 `\p{IsHan}` 这类脚本属性. */
private val CJK_CHAR = Regex("""[\u3400-\u4DBF\u4E00-\u9FFF\uF900-\uFAFF\u3005\u3007\u3040-\u30FF\u31F0-\u31FF\uAC00-\uD7AF\uFF66-\uFF9F]""")

/**
 * 按分集认本条目从哪一季第几集开始: 条目开播那天 (容差同 [tmdbOwnSeasonNumber]) 播出的是哪一季的哪一集.
 * 只认正片季, 且只能落在一季上; 同一季那几天有几集 (连播) 取集号最小的.
 *
 * @param seasonAirDates 正片各季的 (集号, 播出日)
 */
internal fun tmdbSeasonStartByEpisodeAirDate(
    seasonAirDates: Map<Int, List<Pair<Int, String?>>>,
    subjectAirDate: String?,
): Pair<Int, Int>? {
    val target = subjectAirDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
    val hits = seasonAirDates.mapNotNull { (season, episodes) ->
        if (season <= 0) return@mapNotNull null
        episodes.filter { (_, airDate) ->
            val date = airDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return@filter false
            date.daysUntil(target).absoluteValue <= SEASON_MATCH_TOLERANCE_DAYS
        }.minOfOrNull { it.first }?.let { season to it }
    }
    return hits.singleOrNull()
}
