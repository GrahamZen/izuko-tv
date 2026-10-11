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
import kotlinx.coroutines.flow.filter
import me.him188.ani.datasources.api.Media
import me.him188.ani.utils.coroutines.throttleLatest

/**
 * 搜索结果往下游传的频率上限 (见 [throttleLatest]).
 *
 * 数据源一页结果是逐条发出的, 每一条都会让下游把全部结果合并、过滤、排序一遍, 再刷新选源面板和控制台:
 * Shield 实测一次搜索里这套整表计算每秒 7~25 次, 占去一半以上的 CPU 和几个 GB 的内存分配.
 * 每个值都是累积的完整列表, 合并掉中间值不丢结果; 上游结束时立刻补发, 不推迟数据源的「查完」.
 */
internal object SearchResultThrottle {
    /** 单个数据源的累积结果: 一页里逐条到达的合成一次. */
    const val SOURCE_MILLIS = 200L

    /** 选择器的资源列表: 各数据源的结果陆续到达时, 候选最多这么久重算一次. */
    const val SELECTOR_MILLIS = 300L
}

/**
 * 累积列表只增不减, 空的只有 runningFold 起头那一个 (下游 onStart 已经发过空列表): 不让它占掉「当场发出」,
 * 第一条结果到了就发.
 */
internal fun Flow<List<Media>>.throttleSourceResults(): Flow<List<Media>> =
    filter { it.isNotEmpty() }.throttleLatest(SearchResultThrottle.SOURCE_MILLIS)

/** [enabled] 为 `false` (测试里关掉选择器缓存时) 不限频, 结果原样逐个传下去. */
internal fun Flow<List<Media>>.throttleSelectorMediaList(enabled: Boolean): Flow<List<Media>> =
    if (enabled) throttleLatest(SearchResultThrottle.SELECTOR_MILLIS) else this
