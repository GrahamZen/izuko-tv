/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.mediafetch

import kotlinx.coroutines.flow.first
import me.him188.ani.app.domain.media.fetch.MediaSourceFetchResult
import me.him188.ani.app.domain.media.fetch.MediaSourceFetchState
import me.him188.ani.app.domain.media.selector.MaybeExcludedMedia
import me.him188.ani.app.domain.media.selector.UnsafeOriginalMediaAccess

/** 选择器此刻的候选 (含被排除的) 里有哪些资源, 见 [candidatesBehind]. 只比 id, 不当资源用. */
@OptIn(UnsafeOriginalMediaAccess::class)
internal fun candidateMediaIds(candidates: List<MaybeExcludedMedia>): Set<String> =
    candidates.mapTo(HashSet()) { it.original.mediaId }

/**
 * [source] 查完了, 它的结果却还没全进选择器的候选 ([candidateIds]): 候选是各源结果限频后再过滤出来的, 比源的状态晚一拍 (弱机上一秒上下),
 * 这时它的线路为空不是真没有结果, 照旧当成在查; 候选一更新, 上游整个重算, 就有了. 真查完 0 条的源没有结果, 不受影响.
 * 候选是全部结果一一过滤出来的 (被排除的也在), 迟早会跟上.
 */
internal suspend fun MediaSourceFetchResult.candidatesBehind(
    state: MediaSourceFetchState,
    channelsEmpty: Boolean,
    candidateIds: Set<String>,
): Boolean = channelsEmpty && state is MediaSourceFetchState.Succeed && results.first().any { it.mediaId !in candidateIds }
