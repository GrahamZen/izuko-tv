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

/**
 * 参与匹配的集号: 在 [matchingEpisodeSort] 之上, 再补两条要看整个条目页才能判断的判据.
 *
 * 站点给不出集号、名称也不是只标了画质与语言时, 依次:
 * 1. 整个条目页只有这一条, 例如剧场版页面上唯一的 "剧场版" —— 它是整部作品, 记第 1 集;
 *    带作品名的 "铃芽之旅（普通话版）" 若是页面上唯一一条, 也由这条兜住;
 * 2. 名称与正在看的 [episodeName] 相符 —— 认它是正在看的那一集 [episodeSort].
 *
 * 解析结果本身不动 —— 那是页面上的事实, 还要写进搜索缓存.
 */
internal fun List<WebSearchEpisodeInfo>.matchingEpisodeSortOf(
    info: WebSearchEpisodeInfo,
    episodeSort: EpisodeSort,
    episodeEp: EpisodeSort?,
    episodeName: String?,
): EpisodeSort? {
    val matched = info.matchingEpisodeSort(episodeSort, episodeEp)
    // 站点给了集号、只标了画质与语言, 或解析结果已与请求相等: 单看这一条就有定论
    if (matched != null && matched !is EpisodeSort.Unknown) return matched
    if (matched != null && (matched == episodeSort || matched == episodeEp)) return matched
    if (size == 1) return EpisodeSort(1)
    if (episodeName.isNullOrBlank()) return matched
    return if (MediaListFilters.specialContains(info.name, episodeName)) episodeSort else matched
}
