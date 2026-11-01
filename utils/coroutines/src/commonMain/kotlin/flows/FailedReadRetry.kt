/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.utils.coroutines.flows

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * 让失败了的只读加载再试一次的全局信号. 网络恢复时发一次, 分页列表、详情页、时间表这些各自重试自己失败了的部分.
 *
 * 只给只读的加载用: 这里的重试是自动的, 写操作 (改收藏、发评论) 接上来可能重复提交.
 * 接的地方挂在自己的协程作用域里, 页面还开着才收得到, 关掉的页面不会跟着重取.
 */
object FailedReadRetry {
    private val signal = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** 让现在挂着的只读加载各自重试. */
    fun request() {
        signal.tryEmit(Unit)
    }

    /**
     * 一直挂着, 每次 [request] 时调 [retryFailed].
     *
     * [retryFailed] 要自己判断失败了没有, 已经取到的不动: 重启一个好好的列表会丢掉已经加载的内容和滚动位置.
     */
    suspend fun onRequest(retryFailed: suspend () -> Unit): Nothing = signal.collect { retryFailed() }
}
