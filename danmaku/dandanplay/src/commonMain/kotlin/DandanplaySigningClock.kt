/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.danmaku.dandanplay

import io.ktor.client.plugins.ClientRequestException
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.fromHttpToGmtDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import me.him188.ani.utils.platform.currentTimeMillis
import kotlin.concurrent.Volatile
import kotlin.coroutines.CoroutineContext
import kotlin.math.absoluteValue

/**
 * 弹弹 play 签名 (`X-Timestamp`) 用的时间.
 *
 * 弹弹 play 只认与它的服务器相差十分钟上下以内的时间戳, 超出时所有请求都回 403, 正文为空, 原因只在响应头 `X-Error-Message`
 * (`Invalid Timestamp`). 系统时钟偏了的电视因此一条弹幕也拿不到. 遇到这种 403 时按同一个响应的 `Date` 头算出本机与服务器的偏差,
 * 之后签名都加上它, 并把被拒的请求重发一次.
 *
 * 偏差相对本机时钟记, 只在内存里: 系统时间在运行中被改对后偏差反而错了, 下一次 403 时再按新的 `Date` 头算.
 * Android 的单调时钟待机时不走, 按它记的话待机醒来后会差出待机的时长.
 */
internal class DandanplaySigningClock(
    private val localMillis: () -> Long = { currentTimeMillis() },
) {
    @Volatile
    private var offsetMillis = 0L

    /** 签名用的 Unix 秒. */
    fun nowSeconds(): Long = (localMillis() + offsetMillis) / 1000

    /**
     * 在 [context] 里执行一次请求 [block]. 时间戳被拒时校正后再执行一次; 弹弹 play 拒绝的请求, 抛出的异常信息里带上 `X-Error-Message`.
     */
    suspend fun <R> signed(context: CoroutineContext, block: suspend CoroutineScope.() -> R): R {
        // 同时发出的几个请求会一起被拒, 先回来的那个已经把偏差换掉了: 后到的按自己签名时用的偏差判断要不要重发
        val signedWith = offsetMillis
        try {
            return withContext(context, block)
        } catch (e: ClientRequestException) {
            if (!correctFrom(e, signedWith)) throw e.withRejectReason()
        }
        try {
            return withContext(context, block)
        } catch (e: ClientRequestException) {
            throw e.withRejectReason()
        }
    }

    /**
     * [e] 是因时间戳被拒、且按 `Date` 头算出的偏差与请求签名时用的 [signedWith] 不同时返回 true (该重发);
     * 偏差与现在用的也不同时换成它.
     */
    private fun correctFrom(e: ClientRequestException, signedWith: Long): Boolean {
        val response = e.response
        if (response.status != HttpStatusCode.Forbidden) return false
        if (response.headers[ERROR_MESSAGE_HEADER] != INVALID_TIMESTAMP) return false
        val serverMillis = response.headers[HttpHeaders.Date]
            ?.let { runCatching { it.fromHttpToGmtDate().timestamp }.getOrNull() }
            ?: return false
        val offset = serverMillis - localMillis()
        // 签名时用的就是这个偏差还被拒: 不是时钟的问题, 重发也一样
        if ((offset - signedWith).absoluteValue < MIN_CORRECTION_MILLIS) return false
        if ((offset - offsetMillis).absoluteValue >= MIN_CORRECTION_MILLIS) {
            offsetMillis = offset
            logger.warn { "Dandanplay rejected the request timestamp: local clock is ${offset / 1000}s off the server, signing with server time from now on" }
        }
        return true
    }

    private fun ClientRequestException.withRejectReason(): ClientRequestException {
        val reason = response.headers[ERROR_MESSAGE_HEADER] ?: return this
        return ClientRequestException(response, "$ERROR_MESSAGE_HEADER: $reason")
    }

    private companion object {
        private val logger = logger<DandanplaySigningClock>()

        const val ERROR_MESSAGE_HEADER = "X-Error-Message"
        const val INVALID_TIMESTAMP = "Invalid Timestamp"

        /** 弹弹 play 放行的偏差在十分钟上下, 比这小得多的差别不是被拒的原因. */
        const val MIN_CORRECTION_MILLIS = 60_000L
    }
}
