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
import io.ktor.client.request.head
import io.ktor.util.AttributeKey
import me.him188.ani.app.data.models.preference.BangumiMirrorHosts
import me.him188.ani.utils.ktor.ScopedHttpClient
import kotlin.coroutines.cancellation.CancellationException

/** 请求带着它时 [BangumiRoutingFeatureHandler] 不按线路改写, 原样发到官方. */
val BangumiDirectToOrigin = AttributeKey<Unit>("BangumiDirectToOrigin")

/**
 * 问一下 Bangumi 官方此刻回不回应. 在弹「连不上官方, 要不要跳过」之前用: 官方那一跳失败与后面的线路通了之间可能隔着一段整机断网
 * (例如电视休眠时后台起的进程被断网, 网一通后面那条就成了), 那不是官方被阻断.
 *
 * 直接发到官方 (不经线路改写) 的 HEAD, 超时同试线路时缩过的; 收到任何 HTTP 响应 (4xx / 5xx 也算) 就是回应了.
 */
class BangumiOriginProbe(private val client: () -> ScopedHttpClient) {
    suspend fun answers(): Boolean = try {
        client().use {
            head("https://next.${BangumiMirrorHosts.ORIGIN_ROOT}/") {
                attributes.put(BangumiDirectToOrigin, Unit)
                timeout {
                    connectTimeoutMillis = BangumiRoutingFeatureHandler.PROBE_CONNECT_MILLIS
                    socketTimeoutMillis = BangumiRoutingFeatureHandler.PROBE_SOCKET_MILLIS
                    requestTimeoutMillis = BangumiRoutingFeatureHandler.PROBE_CONNECT_MILLIS + BangumiRoutingFeatureHandler.PROBE_SOCKET_MILLIS
                }
            }
        }
        true
    } catch (e: CancellationException) {
        throw e
    } catch (_: ResponseException) {
        true
    } catch (_: Exception) {
        false
    }
}
