package me.him188.ani.utils.coroutines

import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 限频: 闲下来之后到的第一个值当场发出; 发出后的 [periodMillis] 内到的值只留最新的一个, 冷却期满再发.
 * 上游结束时立刻补发还没发的值, 不等冷却期满. 闲着时不定时唤醒.
 *
 * 用于每个值都是完整状态的流 (如逐条累积的结果列表): 丢掉中间值不丢信息, 下游按发出的次数付的代价降下来.
 */
fun <T> Flow<T>.throttleLatest(periodMillis: Long): Flow<T> {
    require(periodMillis > 0) { "Throttle period should be positive" }
    return scopedFlow { downstream ->
        val values = conflate().produceIn(this)
        var next = values.receiveOrNull() ?: return@scopedFlow
        while (true) {
            downstream.emit(next.value)
            var latest: Holder<T>? = null
            var closed = false
            withTimeoutOrNull(periodMillis) {
                while (true) {
                    latest = values.receiveOrNull() ?: break
                }
                closed = true
            }
            val pending = latest
            next = when {
                pending != null -> pending
                closed -> return@scopedFlow
                else -> values.receiveOrNull() ?: return@scopedFlow
            }
            if (closed) {
                downstream.emit(next.value)
                return@scopedFlow
            }
        }
    }
}

/** 包一层以区分「值是 null」与「通道已关闭」. */
private class Holder<T>(val value: T)

private suspend fun <T> ReceiveChannel<T>.receiveOrNull(): Holder<T>? {
    val result = receiveCatching()
    result.exceptionOrNull()?.let { throw it }
    return if (result.isClosed) null else Holder(result.getOrThrow())
}
