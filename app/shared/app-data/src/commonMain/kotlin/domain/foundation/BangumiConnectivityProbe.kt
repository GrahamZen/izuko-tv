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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import me.him188.ani.app.data.models.preference.BangumiEndpointMode
import me.him188.ani.app.data.models.preference.BangumiMirrorHosts
import me.him188.ani.utils.ktor.ScopedHttpClient
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import kotlin.time.TimeSource

/**
 * 「这台设备连不连得上 Bangumi」的一次检测 (首次启动引导用): 官方与镜像清单里的每个镜像**直接**各连一次, 并行.
 *
 * [client] 必须**不带** [BangumiMirrorFeature]: 带着的话镜像地址会被换回原站、再按当前设置决定打哪儿,
 * 测的就不是镜像了. 但要走用户的代理 —— 设好代理再测一次, 才看得出代理管不管用.
 *
 * 判据: 拿到 5xx 以下的任何回应都算连得上 (4xx 也是对方在回答); 连接失败、超时、5xx
 * (反代连不上源站时回 502) 算连不上.
 */
class BangumiConnectivityProbe(
    private val client: () -> ScopedHttpClient,
    private val mirrors: Flow<List<String>>,
    private val timeoutMillis: Long = PROBE_TIMEOUT_MILLIS,
) {
    sealed interface Reachability {
        data object Checking : Reachability
        data class Reachable(val millis: Long) : Reachability
        data object Unreachable : Reachability
    }

    data class Result(
        val origin: Reachability = Reachability.Checking,
        /** 展示用的镜像: 连得上的第一个, 都连不上时是清单第一个; 清单为空时为 `null`. */
        val mirror: String? = null,
        val mirrorReachability: Reachability = Reachability.Checking,
    ) {
        val completed: Boolean
            get() = origin != Reachability.Checking && mirrorReachability != Reachability.Checking

        /** 建议的连接方式; 还没测完, 或两边都连不上 (多半是本机没网) 时为 `null`. */
        val recommendedMode: BangumiEndpointMode?
            get() = when {
                !completed -> null
                origin is Reachability.Reachable -> BangumiEndpointMode.AUTO
                mirrorReachability is Reachability.Reachable -> BangumiEndpointMode.MIRROR
                else -> null
            }
    }

    /** 测一轮: 先发一次「检测中」, 之后每出一个结论发一次; 最后一次的 [Result.completed] 为 true. */
    fun run(): Flow<Result> = channelFlow {
        val mirrorList = mirrors.first()
        var result = Result(
            mirror = mirrorList.firstOrNull(),
            mirrorReachability = if (mirrorList.isEmpty()) Reachability.Unreachable else Reachability.Checking,
        )
        val lock = Mutex()
        suspend fun update(transform: (Result) -> Result) = lock.withLock {
            result = transform(result)
            send(result)
        }
        send(result)
        launch {
            val origin = probe("https://$ORIGIN_PROBE_HOST/")
            update { it.copy(origin = origin) }
        }
        if (mirrorList.isNotEmpty()) launch {
            val attempts = mirrorList.map { mirror ->
                mirror to async {
                    BangumiMirrorHosts.mirrorHostOf(ORIGIN_PROBE_HOST, mirror)
                        ?.let { probe("https://$it/") }
                        ?: Reachability.Unreachable
                }
            }
            // 按清单顺序取第一个连得上的 (与请求回落的顺序一致): 排在前面的还没出结论就等它
            val hit = attempts.firstNotNullOfOrNull { (mirror, attempt) ->
                (attempt.await() as? Reachability.Reachable)?.let { mirror to it }
            }
            attempts.forEach { (_, attempt) -> attempt.cancel() }
            update {
                it.copy(
                    mirror = hit?.first ?: mirrorList.first(),
                    mirrorReachability = hit?.second ?: Reachability.Unreachable,
                )
            }
        }
    }

    private suspend fun probe(url: String): Reachability {
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
                logger.info { "Bangumi connectivity: $url unreachable: $e" }
                false
            }
        }
        val millis = start.elapsedNow().inWholeMilliseconds
        return when (reachable) {
            true -> {
                logger.info { "Bangumi connectivity: $url reachable in ${millis}ms" }
                Reachability.Reachable(millis)
            }

            false -> Reachability.Unreachable
            null -> {
                // 超时这条路径自己留一行: withTimeoutOrNull 取消里面的请求时, 上面的 catch 只会重抛
                logger.warn { "Bangumi connectivity: $url timed out after ${timeoutMillis}ms" }
                Reachability.Unreachable
            }
        }
    }

    private companion object {
        private val logger = logger<BangumiConnectivityProbe>()

        /** 官方那一路测的域名: 根路径回几十字节的欢迎语, 不跳转 (2026-09-24 实测, 镜像上同一路径也一样). */
        const val ORIGIN_PROBE_HOST = "api.bgm.tv"

        const val CONNECT_TIMEOUT_MILLIS = 5_000L
        const val SOCKET_TIMEOUT_MILLIS = 5_000L

        /** 单路封顶: 被墙的官方常常是连接超时, 客户端还会自己重试一次; 封顶让结论在十秒内出来. */
        const val PROBE_TIMEOUT_MILLIS = 8_000L
    }
}
