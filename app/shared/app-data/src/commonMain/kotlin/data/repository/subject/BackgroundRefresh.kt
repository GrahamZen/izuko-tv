/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.repository.subject

import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration

/**
 * 对冲请求: 先发一份 [fetch], [hedgeAfter] 内没有结果就再发一份, 两份谁先成功用谁, 另一份取消.
 *
 * 第一份不掐断: 网慢、或者请求在客户端排队 (同一主机的并发有上限) 时它可能就快轮到了, 掐掉重来只会一直排在队尾.
 * 第二份兜的是「这一个请求挂住了, 另发一个几百毫秒就回来」.
 *
 * 第一份在 [hedgeAfter] 内失败就直接抛, 不补发 (再试是调用方的事); 补发之后一份失败就等另一份, 都失败抛后失败的那个.
 * 总共等多久由调用方在外面套超时.
 */
internal suspend fun <T> hedged(hedgeAfter: Duration, fetch: suspend () -> T): T = supervisorScope {
    val first = async { fetch() }
    if (withTimeoutOrNull(hedgeAfter) { first.join() } != null) return@supervisorScope first.await()
    val second = async { fetch() }
    val done = select {
        first.onJoin { first }
        second.onJoin { second }
    }
    val other = if (done === first) second else first
    try {
        done.await().also { other.cancel() }
    } catch (e: Exception) {
        // 外面取消了就照常取消; 只是这一份自己出错 (含它内部的超时取消) 就等另一份
        currentCoroutineContext().ensureActive()
        other.await()
    }
}

/**
 * 反复调 [attempt] 直到它不抛异常. 失败后等 [initialDelay], 之后每次 ×4, 最长隔 [maxDelay]; 每次失败交给 [onFailure] (第几次从 1 数).
 * 跑在调用方的协程里, 调用方取消就停.
 */
internal suspend fun retryUntilDone(
    initialDelay: Duration,
    maxDelay: Duration,
    onFailure: (cause: Exception, failures: Int) -> Unit,
    attempt: suspend () -> Unit,
) {
    var nextDelay = initialDelay
    var failures = 0
    while (true) {
        try {
            attempt()
            return
        } catch (e: Exception) {
            // 外面取消了就照常取消; 只是这一次自己抛了取消 (比如它内部的超时) 也算一次失败
            currentCoroutineContext().ensureActive()
            failures++
            onFailure(e, failures)
            delay(nextDelay)
            nextDelay = (nextDelay * 4).coerceAtMost(maxDelay)
        }
    }
}

/**
 * 本地数据过期时: 收集期间在后台跑 [refresh], 同时照常发本地的数据, 只有一处例外 ——
 * **本地头一份是空的**时先等 [refresh] 跑完, 最多等 [maxWait], 这段时间界面显示加载骨架: 空列表会被读成「确实没有」.
 * 在 [maxWait] 内跑完了, 这份旧的空列表就不发 (取到的数据落了库, 本地会再发一份), 免得「没有」闪一下; 等满了就照发, [refresh] 接着在后台跑.
 *
 * [refresh] 自己处理失败 (它抛出的异常会让整条流失败); 跑完之前收集方走了, 它跟着取消.
 */
internal fun <T> Flow<List<T>>.refreshingInBackground(
    maxWait: Duration,
    refresh: suspend () -> Unit,
): Flow<List<T>> {
    val local = this
    return channelFlow {
        val refreshing = launch { refresh() }
        var waited = false
        local.collect { list ->
            if (list.isEmpty() && !waited) {
                waited = true
                if (withTimeoutOrNull(maxWait) { refreshing.join() } != null) return@collect
            }
            send(list)
        }
    }
}
