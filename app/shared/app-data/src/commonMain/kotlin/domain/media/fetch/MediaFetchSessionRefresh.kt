/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.fetch

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
class MediaFetchSessionRefresh {
    private val _ticks = MutableStateFlow(0)

    /** 每 +1 一次, 播放页就用当前的数据源列表重建一次搜索会话. */
    val ticks: StateFlow<Int> = _ticks.asStateFlow()

    fun request() {
        _ticks.value++
    }
}
