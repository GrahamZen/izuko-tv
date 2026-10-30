/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.web

import me.him188.ani.app.domain.mediasource.MediaListFilters
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.EpisodeType
import me.him188.ani.datasources.api.source.MediaFetchRequest

/**
 * 参与匹配的集号: 在 [matchingEpisodeSortFor] 之上, 再补两条要看整个条目页才能判断的判据.
 *
 * 站点给不出集号、名称也不是只标了画质与语言时, 依次:
 * 1. 整个条目页只有这一条, 例如剧场版页面上唯一的 "剧场版" —— 它是整部作品, 记第 1 集;
 *    带作品名的 "铃芽之旅（普通话版）" 若是页面上唯一一条, 也由这条兜住. 只在条目不是多集的番时用 (见 [matchingEpisodeSortFor]);
 * 2. 名称与正在看的 [episodeName] 相符 —— 认它是正在看的那一集 [episodeSort].
 *
 * 站点给的集号是 OVA / OAD / SP (不带编号或编号为 1, 见 [isWholeWorkSpecial]) 时, 条目不是多集的番、页上各线路又只有这一个名字的,
 * 也是整部作品, 记第 1 集: 单集 OVA 与特别篇的条目页常把唯一一集叫「OVA」(「攻壳机动队SSS」「邪神与厨二病少女【世纪末篇】」).
 *
 * 解析结果本身不动 —— 那是页面上的事实, 还要写进搜索缓存.
 *
 * @param subjectEpisodeCount 条目的正片集数 ([mainEpisodeCountOrNull]); 不知道时为 null (判断缓存页是否陈旧时不看)
 */
internal fun List<WebSearchEpisodeInfo>.matchingEpisodeSortOf(
    info: WebSearchEpisodeInfo,
    episodeSort: EpisodeSort,
    episodeEp: EpisodeSort?,
    episodeName: String?,
    subjectEpisodeCount: Int? = null,
): EpisodeSort? {
    val matched = info.matchingEpisodeSortFor(episodeSort, episodeEp, subjectEpisodeCount)
    // 解析结果已与请求相等 (看的就是这一部的 SP 时也是): 单看这一条就有定论
    if (matched != null && (matched == episodeSort || matched == episodeEp)) return matched
    if (isWholeWorkSpecial(matched) && !isMultiEpisode(subjectEpisodeCount) && all { it.name == info.name }) return FIRST_EPISODE
    // 站点给了集号、只标了画质与语言: 单看这一条就有定论
    if (matched != null && matched !is EpisodeSort.Unknown) return matched
    if (size == 1 && !isMultiEpisode(subjectEpisodeCount)) return EpisodeSort(1)
    if (episodeName.isNullOrBlank()) return matched
    return if (MediaListFilters.specialContains(info.name, episodeName)) episodeSort else matched
}

/**
 * [matchingEpisodeSort], 再看条目有几集: 名字里没有集号、记成第 1 集的一条 (只标了画质与语言的「1080P」「HD中字」,
 * 解析时当成剧场版的「正片」) 只在条目只有一集 (剧场版、单集 OVA) 或集数不明时是整部作品. 多集的番遇到这种,
 * 是同名的剧场版、真人电影或整季合集 (「辉夜大小姐想让我告白：天才们的恋爱头脑战」页面上唯一一条「1080P」是 2019 年的真人电影),
 * 不认作哪一集.
 */
internal fun WebSearchEpisodeInfo.matchingEpisodeSortFor(
    episodeSort: EpisodeSort,
    episodeEp: EpisodeSort?,
    subjectEpisodeCount: Int?,
): EpisodeSort? {
    val matched = matchingEpisodeSort(episodeSort, episodeEp)
    if (isMultiEpisode(subjectEpisodeCount) && matched == FIRST_EPISODE && namesNoEpisode(name)) return null
    return matched
}

/** 条目的正片集数, 给 [matchingEpisodeSortOf] 判断条目是不是只有一集; 剧集列表未知时为 null. */
internal fun MediaFetchRequest.mainEpisodeCountOrNull(): Int? = episodes.count { it.sort is EpisodeSort.Normal }.takeIf { it > 0 }

private fun isMultiEpisode(subjectEpisodeCount: Int?) = subjectEpisodeCount != null && subjectEpisodeCount > 1

/**
 * 不带编号或编号为 1 的 OVA / OAD / SP: 单集条目的页上只有它时是整部作品 (见 [matchingEpisodeSortOf]).
 * PV / OP / ED / MAD 是预告、片头片尾与剪辑, 「OVA2」是另一部, 都不算.
 */
private fun isWholeWorkSpecial(sort: EpisodeSort?): Boolean =
    sort is EpisodeSort.Special && sort.type in WHOLE_WORK_SPECIAL_TYPES && (sort.number == null || sort.number == 1f)

private val WHOLE_WORK_SPECIAL_TYPES = setOf(EpisodeType.OVA, EpisodeType.OAD, EpisodeType.SP)

/** 名字里没有集号: 去掉画质、语言词与年份后没有数字, 也没有「第一集」这样的中文数字集号. */
private fun namesNoEpisode(name: String): Boolean {
    val rest = name.replace(WHOLE_WORK_LABEL_TOKENS, "").replace(YEAR, "")
    return rest.none { it.isDigit() } && !CHINESE_EPISODE.containsMatchIn(rest)
}

private val FIRST_EPISODE = EpisodeSort(1)
private val YEAR = Regex("""(?<![0-9])(?:19|20)\d\d(?![0-9])""")
private val CHINESE_EPISODE = Regex("""第\s*[一二三四五六七八九十百零〇两]+\s*[话集話]""")
