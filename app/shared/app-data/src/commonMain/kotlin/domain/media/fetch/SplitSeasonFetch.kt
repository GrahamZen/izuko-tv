/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.fetch

import me.him188.ani.app.data.models.subject.SplitSeason
import me.him188.ani.app.domain.mediasource.web.SelectorMediaSource
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.source.MediaFetchRequest

/**
 * [MediaFetchRequest.seasonEpisodeCount]: 当前条目是拆分季的后一段时, 整季合成一页从 1 编号最多编到第几个; 第一段为 0,
 * 选择器不对第一段按季内序号对集 (见 [me.him188.ani.app.domain.media.selector.filter.SplitSeasonPageMatcher]).
 */
internal fun SplitSeason?.seasonEpisodeCount(): Int {
    if (this == null || selfIndex == 0) return 0
    return parts.take(selfIndex + 1).sumOf { it.episodeCount + it.inlineSpecialCount + if (it.firstSort == 0) 1 else 0 }
}

/**
 * 按集号裁剪产出的数据源除了本条目的集号还要留下的整季序号 1 到 [MediaFetchRequest.seasonEpisodeCount]. 不是拆分季的后一段时为空.
 */
internal fun MediaFetchRequest.seasonEpisodeSorts(): List<EpisodeSort> = (1..seasonEpisodeCount).map { EpisodeSort(it) }

/**
 * 本条目的全部集号, 在线源 (网页抓取、规则源) 据此决定一次产出哪些集. 拆分季的后一段再加上整季序号 ([seasonEpisodeSorts]).
 *
 * 剧集列表未知, 或条目集数超过 [SelectorMediaSource.MAX_WHOLE_SUBJECT_EPISODES] 时为 `null` —— 那时退回"只产出当前这一集".
 */
internal fun MediaFetchRequest.wholeSubjectEpisodeSortsOrNull(): Set<EpisodeSort>? {
    if (episodes.isEmpty() || episodes.size > SelectorMediaSource.MAX_WHOLE_SUBJECT_EPISODES) return null
    return episodes.flatMapTo(mutableSetOf()) { listOfNotNull(it.sort, it.ep) } + seasonEpisodeSorts()
}
