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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf

/**
 * 「重新搜索（含新数据源）」的信号.
 *
 * 播放页的搜索会话**在创建时对数据源列表取快照** (见 `createFetchFetchSession`), 之后改数据源 / 更新订阅都
 * 不会让新源参与这一次的搜索 —— 这是有意的: 选中的资源与正在播的内容都挂在会话上, 悄悄重建就等于把人家
 * 正在看的打断。所以改成由用户按一下再刷。
 *
 * 计数变化会让 [me.him188.ani.app.domain.episode.CreateMediaFetchSelectBundleFlowUseCase] 用最新的数据源
 * 列表重建会话 (等同于退出播放页再进来, 但不用真的退出)。
 */
class MediaFetchSessionRefresh(
    /** 当前数据源列表的指纹: 每个实例的 id 与启用与否. */
    private val sourcesFingerprint: Flow<List<Pair<String, Boolean>>> = flowOf(emptyList()),
) {
    private val _ticks = MutableStateFlow(0)

    /** 每 +1 一次, 播放页就用当前的数据源列表重建一次搜索会话. */
    val ticks: StateFlow<Int> = _ticks.asStateFlow()

    /** 上次记下的数据源列表; `null` = 还没记过. */
    private var lastSources: List<Pair<String, Boolean>>? = null

    fun request() {
        _ticks.value++
    }

    /**
     * 记下当前的数据源列表, 不重建会话.
     *
     * 新建的会话本来就用最新的列表, 记一笔是为了让下一次 [requestIfSourcesChanged] 有得比。
     */
    suspend fun markSources() {
        lastSources = sourcesFingerprint.first()
    }

    /**
     * 重新进播放页时调用: 数据源列表与上次记下的不同就重建会话.
     *
     * 为什么不在改动数据源的当场重建: 那时人可能正在看, 会话一换选中的资源与正在播的内容都要重来。
     * 而重新进播放页是个自然的边界 —— 保留下来的播放会话 (`RetainedPlaybackSessionHolder`) 会被原样
     * 接着用, 不在这里放行的话, 停用掉的数据源会一直留在列表里, 直到用户手动按一次「重新搜索」。
     *
     * 换掉的只是搜索会话: 播放器、已选中的资源与播放进度都不受影响。
     */
    suspend fun requestIfSourcesChanged() {
        val now = sourcesFingerprint.first()
        val previous = lastSources
        lastSources = now
        if (previous != null && previous != now) request()
    }
}
