/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.foundation

import io.ktor.client.HttpClient
import io.ktor.client.call.HttpClientCall
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.client.request.HttpRequestBuilder
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.io.IOException
import me.him188.ani.utils.platform.currentTimeMillis
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * 测试用的网络钩子 (Web 控制台调试操作 `api/debug/net/…` 与 `api/debug/requests` 读写这里): 按网址拦截请求、记录发出的请求.
 * 默认两样都关, 关着时每个请求只多读两个字段.
 *
 * 挂在 [HttpSend] 拦截链的最里层 (Bangumi 线路改写之后), 看到的是每条线路实际要发的地址: 只拦 `api.bgm.tv` 就是只有官方连不上,
 * 应用会照常退到别的线路; 要整个 bangumi 接口都连不上, 把各线路的域名都写上. 播放器与 WebView 自己发的请求不经这里.
 */
object DebugNetworkHooks {
    /**
     * 网址里含 [pattern] 的请求直接失败 (抛 [IOException], 与连不上同一种): [hang] 为 0 时立刻失败,
     * 否则先等这么久再失败 (模拟超时).
     */
    data class BlockRule(val pattern: String, val hang: Duration = Duration.ZERO)

    /** 记下的一个请求. [status] 是状态码, 或 `BLOCKED` / `CANCELLED` / 异常类名. */
    data class RequestRecord(
        val atMillis: Long,
        val method: String,
        val url: String,
        val status: String,
        val duration: Duration,
    )

    @Volatile
    var blockRules: List<BlockRule> = emptyList()

    private val lock = SynchronizedObject()
    private val records = ArrayDeque<RequestRecord>()
    private var recordedTotal = 0
    private var recordingSince: Long? = null

    /** 从现在起记录请求 (清掉之前记的); 只留最近 [MAX_RECORDS] 条, 总数照数. */
    fun startRecording() = synchronized(lock) {
        records.clear()
        recordedTotal = 0
        recordingSince = currentTimeMillis()
    }

    fun stopRecording() = synchronized(lock) { recordingSince = null }

    /** (开始记录的时刻, 一共记了几条, 最近的那些); 没在记录时开始时刻为 `null`. */
    fun snapshot(): Triple<Long?, Int, List<RequestRecord>> = synchronized(lock) {
        Triple(recordingSince, recordedTotal, records.toList())
    }

    private fun record(record: RequestRecord) = synchronized(lock) {
        if (recordingSince == null) return@synchronized
        recordedTotal++
        records.addLast(record)
        if (records.size > MAX_RECORDS) records.removeFirst()
    }

    internal suspend fun intercept(
        request: HttpRequestBuilder,
        proceed: suspend (HttpRequestBuilder) -> HttpClientCall,
    ): HttpClientCall {
        val rules = blockRules
        val recording = synchronized(lock) { recordingSince != null }
        if (rules.isEmpty() && !recording) return proceed(request)

        val url = request.url.toString()
        val method = request.method.value
        val start = TimeSource.Monotonic.markNow()
        val at = currentTimeMillis()
        rules.firstOrNull { url.contains(it.pattern) }?.let { rule ->
            if (rule.hang > Duration.ZERO) delay(rule.hang)
            record(RequestRecord(at, method, url, "BLOCKED", start.elapsedNow()))
            throw IOException("Blocked by debug rule '${rule.pattern}'")
        }
        if (!recording) return proceed(request)

        val result = runCatching { proceed(request) }
        val status = result.fold(
            onSuccess = { it.response.status.value.toString() },
            onFailure = { e -> if (e is CancellationException) "CANCELLED" else e::class.simpleName ?: "FAILED" },
        )
        record(RequestRecord(at, method, url, status, start.elapsedNow()))
        return result.getOrThrow()
    }

    private const val MAX_RECORDS = 2000
}

/** 见 [DebugNetworkHooks]; 要在别的 [HttpSend] 拦截器 (日志、线路改写) 之后装, 才能看到实际要发的地址. */
fun HttpClient.installDebugNetworkHooks() {
    plugin(HttpSend).intercept { request -> DebugNetworkHooks.intercept(request) { execute(it) } }
}
