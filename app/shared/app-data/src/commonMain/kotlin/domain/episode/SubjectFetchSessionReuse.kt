/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.episode

import me.him188.ani.app.domain.mediasource.web.SelectorMediaSource
import me.him188.ani.datasources.api.source.MediaFetchRequest

/**
 * 为这个请求建的条目级查询会话 ([SubjectMediaFetchSessions]) 能不能给 [next] 那一集用.
 *
 * 在线源按集号裁剪产出: 剧集列表未知或条目超过 [SelectorMediaSource.MAX_WHOLE_SUBJECT_EPISODES] 集时只产出查询时的那一集
 * (长番一页上千集, 全部产出太占内存). 这种条目换了集, 会话里的在线源结果一条都对不上, 要换一个会话重新查;
 * 在线源从搜索缓存里读这一集, BT 源重新搜. 其余条目的各集共用一个会话.
 */
internal fun MediaFetchRequest.servesEpisodeOf(next: MediaFetchRequest): Boolean =
    next.episodeId == episodeId || (episodes.isNotEmpty() && episodes.size <= SelectorMediaSource.MAX_WHOLE_SUBJECT_EPISODES)
