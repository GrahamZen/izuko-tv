/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.foundation

import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import me.him188.ani.app.data.models.preference.EndpointUrls
import me.him188.ani.utils.ktor.ScopedHttpClient
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import kotlin.time.TimeSource

/** 一个地址连不连得上 (连通性检测的结论). */
sealed interface Reachability {
    data object Checking : Reachability
    data class Reachable(val millis: Long) : Reachability
    data object Unreachable : Reachability
}

/**
 * 连通性检测 (首次启动引导用): **直接**连给定的地址.
 *
 * [client] 不能带会改写地址的特性 ([BangumiMirrorFeature]、[AlternativeEndpointsFeature]): 带着的话地址会被换掉,
 * 测的就不是它了. 但要走用户的代理 —— 设好代理再测一次, 才看得出代理管不管用.
 *
 * 判据: 拿到 5xx 以下的任何回应都算连得上 (4xx 也是对方在回答); 连接失败、超时、5xx (反代连不上源站时回 502)
 * 算连不上.
 */
class ReachabilityProbe(
    private val client: () -> ScopedHttpClient,
    private val timeoutMillis: Long = PROBE_TIMEOUT_MILLIS,
) {
    suspend fun probe(url: String): Reachability {
        val start = TimeSource.Monotonic.markNow()
        val reachable = withTimeoutOrNull(timeoutMillis) {
            try {
                client().use {
                    get(url) {
                        timeout {
                            connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS
                            socketTimeoutMillis = SOCKET_TIMEOUT_MILLIS
                        }
                    }.status.value < 500
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ResponseException) {
                // 客户端开着 expectSuccess, 4xx 也会抛到这里 —— 那是对方在回答
                e.response.status.value < 500
            } catch (e: Exception) {
                logger.info { "Connectivity: $url unreachable: $e" }
                false
            }
        }
        val millis = start.elapsedNow().inWholeMilliseconds
        return when (reachable) {
            true -> {
                logger.info { "Connectivity: $url reachable in ${millis}ms" }
                Reachability.Reachable(millis)
            }

            false -> Reachability.Unreachable
            null -> {
                // 超时这条路径自己留一行: withTimeoutOrNull 取消里面的请求时, 上面的 catch 只会重抛
                logger.warn { "Connectivity: $url timed out after ${timeoutMillis}ms" }
                Reachability.Unreachable
            }
        }
    }

    /**
     * 按 [urls] 的顺序取第一个连得上的 (与请求回落的顺序一致): 并行试, 排在前面的还没出结论就等它.
     *
     * @return 下标与结论; 都连不上是 `null`
     */
    suspend fun firstReachable(urls: List<String>): IndexedValue<Reachability.Reachable>? = coroutineScope {
        val attempts = urls.map { async { probe(it) } }
        val hit = attempts.withIndex().firstNotNullOfOrNull { (index, attempt) ->
            (attempt.await() as? Reachability.Reachable)?.let { IndexedValue(index, it) }
        }
        attempts.forEach { it.cancel() }
        hit
    }

    /**
     * 可互换入口的服务 ([AlternativeEndpoints]) 清单里的每个入口各连一次 (并行, 连的是入口上的 [AlternativeEndpoints.probePath]).
     * 先发一次全是「检测中」的, 之后每出一个结论发一次; 最后一次的 [CandidatesCheck.completed] 为 true.
     */
    fun checkCandidates(endpoints: AlternativeEndpoints): Flow<CandidatesCheck> = channelFlow {
        val candidates = endpoints.candidates.first()
        var result = CandidatesCheck(candidates.map { it to Reachability.Checking }, loaded = true)
        val lock = Mutex()
        send(result)
        candidates.forEachIndexed { index, baseUrl ->
            launch {
                val reachability = probe(EndpointUrls.resolve(baseUrl, endpoints.probePath))
                lock.withLock {
                    result = result.copy(
                        candidates = result.candidates.mapIndexed { i, entry ->
                            if (i == index) baseUrl to reachability else entry
                        },
                    )
                    send(result)
                }
            }
        }
    }

    private companion object {
        private val logger = logger<ReachabilityProbe>()

        const val CONNECT_TIMEOUT_MILLIS = 5_000L
        const val SOCKET_TIMEOUT_MILLIS = 5_000L

        /** 单路封顶: 被墙的地址常常是连接超时, 客户端还会自己重试一次; 封顶让结论在十秒内出来. */
        const val PROBE_TIMEOUT_MILLIS = 8_000L
    }
}

/** 一项可互换入口的服务 ([AlternativeEndpoints]) 的检测结果, 见 [ReachabilityProbe.checkCandidates]. */
data class CandidatesCheck(
    /** 清单里的每个入口与各自的结论, 顺序同清单. */
    val candidates: List<Pair<String, Reachability>> = emptyList(),
    /** 清单已经读出来了 (在那之前 [candidates] 是空的, 不代表清单是空的). */
    val loaded: Boolean = false,
) {
    val completed: Boolean
        get() = loaded && candidates.none { it.second == Reachability.Checking }

    /**
     * 「自动选择」会落到的入口: 按清单顺序第一个连得上的. 排在它前面的还有没出结论的, 就先不下结论 (`null`);
     * 测完了都连不上也是 `null`.
     */
    val firstReachable: String?
        get() {
            for ((baseUrl, reachability) in candidates) {
                when (reachability) {
                    is Reachability.Reachable -> return baseUrl
                    Reachability.Checking -> return null
                    Reachability.Unreachable -> continue
                }
            }
            return null
        }
}
