/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.selector.filter

import me.him188.ani.app.domain.media.selector.MatchMetadata
import me.him188.ani.app.domain.media.selector.MaybeExcludedMedia
import me.him188.ani.datasources.api.Media

/**
 * 逐条筛选结论的记忆表, 交给 [MediaSelectorFilterSortAlgorithm.filterMediaList] (实现见 `MediaFilterMemo`).
 *
 * 拆分季时站点资源的结论还取决于它在页面上的判断 ([SplitSeasonEpisodeMatcher.match]): 页面随结果追加变大, 判断会变.
 * 这类结论连同当时的判断 ([page]) 一起记, 判断与记下时相同才沿用. 不依赖页面的结论 [page] 为 `null`.
 */
interface FilterResultMemo {
    fun get(media: Media, page: Any?): MaybeExcludedMedia?

    fun put(media: Media, page: Any?, result: MaybeExcludedMedia)
}

/**
 * [media] 在拆分季页面上的判断, 作为 [FilterResultMemo] 的 `page`: 筛选里用到的只有这几项,
 * 其余输入 (条目、剧集、本季的名字) 变了时记忆表整个作废. 不在站点页面上的资源为 `null`.
 */
internal fun SplitSeasonEpisodeMatcher.memoPageOf(media: Media): Any? = match(media)?.let {
    PageVerdict(it.pageKind, it.exact, it.matched, it.episodeMatchKind, it.subjectNameForMatching)
}

private data class PageVerdict(
    val kind: SplitSeasonPageMatcher.PageKind,
    val exact: Boolean,
    val matched: Boolean,
    val episodeMatchKind: MatchMetadata.EpisodeMatchKind,
    val subjectNameForMatching: String?,
)
