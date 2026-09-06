/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.resolver

import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.time.TimeSource

/**
 * 解析这一轮最多等到什么时候: 加载提示据此写「最多再等 6 秒」.
 *
 * 只有要等超时的解析 (网页嗅探) 才有; 直连取流、BT 不报.
 */
data class MediaResolveDeadline(
    val startedAt: TimeSource.Monotonic.ValueTimeMark,
    val timeoutMillis: Long,
) {
    /** 离超时还剩多少毫秒, 不小于 0. */
    fun remainingMillis(): Long =
        (timeoutMillis - startedAt.elapsedNow().inWholeMilliseconds).coerceAtLeast(0L)
}

/**
 * 放进协程上下文, 让解析器报告"这一轮要等多久" (见 `PlayerSession.loadMedia`).
 *
 * 走协程上下文而不是给 [MediaResolver.resolve] 加参数: 真正按超时等的只有网页嗅探那一两处,
 * 其余实现 (直连 / BT / 组合解析器) 都不用跟着改签名.
 */
class MediaResolveDeadlineReporter(
    private val onAttempt: (MediaResolveDeadline) -> Unit,
) : AbstractCoroutineContextElement(Key) {
    fun reportAttempt(timeoutMillis: Long) {
        onAttempt(MediaResolveDeadline(TimeSource.Monotonic.markNow(), timeoutMillis))
    }

    companion object Key : CoroutineContext.Key<MediaResolveDeadlineReporter>
}

/**
 * 解析器在开始一轮最多等 [timeoutMillis] 的尝试前调用. 调用方没放 [MediaResolveDeadlineReporter] 时什么都不做.
 * 一次解析里有多轮 (先在暖会话里试, 不成再开新的 WebView) 就报多次, 每次重新计时.
 */
suspend fun reportResolveAttempt(timeoutMillis: Long) {
    currentCoroutineContext()[MediaResolveDeadlineReporter]?.reportAttempt(timeoutMillis)
}
