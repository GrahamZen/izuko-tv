/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.foundation

import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.Url
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import kotlin.coroutines.cancellation.CancellationException

/**
 * 发往自家放在 Cloudflare 上的服务 (反馈中转、故障工单的 Worker) 的请求怎么连: 直连与加密直连 (见 [EchSites]) 两种, 一种不通换另一种.
 * 先试哪种: 这个服务上次连通的那种; 还没连过时, bangumi 在用官方直连就先直连, 否则先加密直连 —— 官方关着或连不上的人
 * (多半在大陆) 这些服务也直连不通, 先直连只会白等. 先试的那种连接阶段只等 [PROBE_CONNECT_MILLIS], 连不上的别把整个请求的时间耗光.
 * 加密直连握手被拒 (配置过期) 时换一份配置再试一次.
 *
 * 用法: [request] 交一个「按给的地址发一次」的函数, 它依次换地址调用. 地址都要是 https (加密直连只有 TLS).
 */
class CloudflareServiceTransport(
    /** 现在能走加密直连: 平台支持且没设代理 (条件同 bangumi 的那条线路). */
    private val echUsable: suspend () -> Boolean,
    /** 还没连过的服务先试加密直连 (bangumi 没在用官方直连). */
    private val preferEch: () -> Boolean,
    /** 加密直连握手被拒时换一份 ECH 配置, 换到了返回 `true` (见 [BangumiEchTransport.recover]). */
    private val recoverEch: suspend (Throwable) -> Boolean,
) {
    /** 各服务 (按域名) 上次连通的是不是加密直连 (进程内记): 下次先试它, 不再每次先吃一次连不通的那种. */
    private val lastWorked = MutableStateFlow<Map<String, Boolean>>(emptyMap())

    /**
     * 依次用直连与加密直连的地址调 [send], 返回第一个非 `null` 的结果; 都连不上为 `null`. 顺序见类说明.
     *
     * [send] 的 `probe` = 后面还有另一种可退: 调用方用 [probeConnectTimeout] 把连接超时缩短.
     * [send] 返回 `null` (超时) 或抛异常 (连不上) 都换下一种; 抛的异常在这里记日志后吞掉, 取消照常往外抛.
     */
    suspend fun <T : Any> request(url: String, send: suspend (url: String, probe: Boolean) -> T?): T? {
        val viaEch = if (echUsable()) EchSites.echUrlOf(url) else null
        val host = runCatching { Url(url).host }.getOrDefault(url)
        val echFirst = viaEch != null && (lastWorked.value[host] ?: preferEch())
        val urls = if (echFirst && viaEch != null) listOf(viaEch, url) else listOfNotNull(url, viaEch)
        for ((index, candidate) in urls.withIndex()) {
            val isEch = candidate == viaEch
            val result = attempt(candidate, isEch, probe = index < urls.lastIndex, send) ?: continue
            if (lastWorked.value[host] != isEch) lastWorked.update { it + (host to isEch) }
            return result
        }
        return null
    }

    private suspend fun <T : Any> attempt(url: String, isEch: Boolean, probe: Boolean, send: suspend (url: String, probe: Boolean) -> T?): T? {
        var retried = false
        while (true) {
            try {
                val result = send(url, probe)
                if (result == null) logger.info { "$url timed out" }
                return result
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.info { "$url unreachable (${e::class.simpleName})" }
                if (isEch && !retried && recoverEch(e)) {
                    retried = true
                    continue
                }
                return null
            }
        }
    }

    companion object {
        private val logger = logger<CloudflareServiceTransport>()

        /**
         * 先试的那种 (后面还有另一种可退) 的连接超时. 连得通的网络一两秒内就连上; 连不通的 (大陆直连 workers.dev 常常一直挂着)
         * 早点放弃, 留时间给另一种. 只缩连接阶段: 连上之后传大日志、等服务回应照常.
         */
        const val PROBE_CONNECT_MILLIS = 4_000L

        /** 只直连 (平台不支持加密直连、测试). */
        val DirectOnly = CloudflareServiceTransport(echUsable = { false }, preferEch = { false }, recoverEch = { false })
    }
}

/** [CloudflareServiceTransport.request] 的 `probe` 为真时把这个请求的连接超时缩到 [CloudflareServiceTransport.PROBE_CONNECT_MILLIS]. */
fun HttpRequestBuilder.probeConnectTimeout(probe: Boolean) {
    if (probe) timeout { connectTimeoutMillis = CloudflareServiceTransport.PROBE_CONNECT_MILLIS }
}
