/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.resolver

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.time.TimeSource

/**
 * 打开 BT 资源、在等种子信息 (元数据) 时的进展: 从什么时候开始等、此刻连上了几个节点.
 *
 * 等元数据没有超时, 节点数是唯一能告诉用户"还有没有希望"的东西: 加载提示据此写
 * 「正在获取种子信息 · 已连上 3 个节点 · 已等 40 秒」, 一直没有节点时建议换源.
 */
data class TorrentOpenProgress(
    val startedAt: TimeSource.Monotonic.ValueTimeMark,
    val peers: Int,
)

/**
 * 放进协程上下文, 让 [TorrentMediaDataProvider.open] 在等种子信息时报告节点数 (见 `PlayerSession.loadMedia`).
 * 与 [MediaResolveDeadlineReporter] 同一个思路: 只有这一处要报, 不为它改 `MediaDataProvider.open` 的签名.
 */
class TorrentOpenProgressReporter(
    private val onProgress: (TorrentOpenProgress) -> Unit,
) : AbstractCoroutineContextElement(Key) {
    fun report(progress: TorrentOpenProgress) = onProgress(progress)

    companion object Key : CoroutineContext.Key<TorrentOpenProgressReporter>
}
