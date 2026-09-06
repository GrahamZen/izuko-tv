/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.network.mapper

import me.him188.ani.app.data.persistent.database.dao.EpisodeCollectionEntity
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.EpisodeType
import me.him188.ani.datasources.api.PackedDate
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import me.him188.ani.datasources.bangumi.models.BangumiEpisode
import me.him188.ani.datasources.bangumi.models.BangumiEpisodeCollectionType

/**
 * 分集走 v0 的 `/v0/users/-/collections/{subjectId}/episodes` 而不是 p1 的
 * `/p1/subjects/{id}/episodes`, 唯一的原因是 **`ep`**:
 *
 * p1 的分集只有 `sort` 没有 `ep`. 两者绝大多数时候相等, 但不总是 —— 条目 132734 的正片是
 * `sort` 0,1,2 而 `ep` 1,2,3 (从第 0 话开头的那种). `ep` 是资源匹配 (集号)、弹幕、缓存请求
 * 在用的东西, 拿 `sort` 顶替会让这类条目整体错一集.
 *
 * v0 那个端点对**未收藏**的条目也返回全部分集 (状态给 0), 与 Ani 的形状一致.
 */
internal fun BangumiEpisode.toEntity(
    subjectId: Int,
    collectionType: UnifiedCollectionType,
    lastFetched: Long,
): EpisodeCollectionEntity {
    val episodeType = type.toEpisodeTypeOrNull()
    return EpisodeCollectionEntity(
        subjectId = subjectId,
        episodeId = id,
        episodeType = episodeType,
        name = name,
        nameCn = nameCn,
        airDate = PackedDate.parseFromDate(airdate),
        comment = 0,
        desc = desc,
        sort = EpisodeSort(sort, episodeType),
        // v0 对没有话数的分集 (特别篇之类) 给 0, Ani 那边是 null. 保持 null.
        ep = ep?.takeIf { it.toString() != "0" }?.let { EpisodeSort(it, episodeType) },
        sortNumber = sort.toString().toFloatOrNull() ?: 0f,
        selfCollectionType = collectionType,
        lastFetched = lastFetched,
    )
}

/** 本篇 0 / 特别篇 1 / OP 2 / ED 3 / 预告 4 / MAD 5 / 其他 6. */
internal fun Int.toEpisodeTypeOrNull(): EpisodeType? = when (this) {
    0 -> EpisodeType.MainStory
    1 -> EpisodeType.SP
    2 -> EpisodeType.OP
    3 -> EpisodeType.ED
    4 -> EpisodeType.PV
    5 -> EpisodeType.MAD
    else -> null
}

/**
 * Ani 那边分集只有"看过"一种状态 (`AniEpisodeCollectionType.DONE`), bangumi 有四种, 全保留.
 */
internal fun BangumiEpisodeCollectionType?.toUnifiedCollectionType(): UnifiedCollectionType = when (this) {
    BangumiEpisodeCollectionType.WATCHLIST -> UnifiedCollectionType.WISH
    BangumiEpisodeCollectionType.WATCHED -> UnifiedCollectionType.DONE
    BangumiEpisodeCollectionType.DISCARDED -> UnifiedCollectionType.DROPPED
    BangumiEpisodeCollectionType.NOT_COLLECTED, null -> UnifiedCollectionType.NOT_COLLECTED
}
