/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.fetch

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import me.him188.ani.app.domain.mediasource.profile.SourceProfileSummary
import me.him188.ani.app.domain.mediasource.profile.SourceProfiles
import me.him188.ani.datasources.api.source.MediaSourceKind

/**
 * 一次查询里各数据源开查的先后. 各源的结果流 ([MediaSourceFetchResult.results]) 有人订才开查, 汇总时按订阅的先后启动;
 * 几十个源几乎同时起步, 谁先拿到限流名额 ([MediaSourceSearchLimiter]: 最高层级的在线源来一个给一个) 看的是谁先排上,
 * 按列表顺序订的话, 播过、播得起来的源可能排在一串新导入的同层级源后面, 等它们让出名额.
 *
 * 先后: 本地缓存最先; 再按限流排队的先后 ([MediaSourceSearchLimiter.priorityOf]: 层级, 同层在线源在 BT 前);
 * 同一档里真实播放成功过、且成功不少于失败的在前, 没播过的其次, 失败多于成功的最后; 再按画像估的「搜索 + 开播」用时 ([SourceProfileSummary], 没测过的按 [DEFAULT_SEARCH_MILLIS] / [DEFAULT_STARTUP_MILLIS]);
 * 都一样时保持列表的顺序.
 */
internal object SourceStartOrder {
    private const val DEFAULT_SEARCH_MILLIS = 3_000L
    private const val DEFAULT_STARTUP_MILLIS = 5_000L

    /** [results] 的下标, 按开查的先后排. */
    fun of(
        results: List<MediaSourceFetchResult>,
        summaryOf: (mediaSourceId: String) -> SourceProfileSummary? = SourceProfiles::summary,
    ): List<Int> {
        val keys = results.map { result ->
            val summary = summaryOf(result.mediaSourceId)
            val record = when {
                summary == null || summary.plays + summary.failures == 0 -> 1
                summary.plays > 0 && summary.failures <= summary.plays -> 0
                else -> 2
            }
            val expected = (summary?.searchMillis ?: DEFAULT_SEARCH_MILLIS) + (summary?.startupMillis ?: DEFAULT_STARTUP_MILLIS)
            Triple(
                if (result.kind == MediaSourceKind.LocalCache) -1L else MediaSourceSearchLimiter.priorityOf(result.kind, result.sourceInfo.tier),
                record,
                expected,
            )
        }
        return results.indices.sortedWith(
            compareBy<Int>({ keys[it].first }, { keys[it].second }, { keys[it].third }),
        )
    }

    /**
     * 与 `combine(flows, transform)` 相同 (交给 [transform] 的仍是 [flows] 的顺序), 只是按 [order] 的先后订阅各个流.
     */
    inline fun <reified T, R> combineInOrder(
        flows: List<Flow<T>>,
        order: List<Int>,
        crossinline transform: suspend (List<T>) -> R,
    ): Flow<R> = combine(order.map { flows[it] }) { values ->
        val restored = arrayOfNulls<T>(flows.size)
        order.forEachIndexed { position, index -> restored[index] = values[position] }
        @Suppress("UNCHECKED_CAST")
        transform(restored.asList() as List<T>)
    }
}
