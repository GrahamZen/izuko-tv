/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.foundation

import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import kotlin.coroutines.cancellation.CancellationException

/**
 * 发往自家放在 Cloudflare 上的服务 (反馈中转、故障工单的 Worker) 的请求怎么连: 先直连, 不通再走加密直连 (见 [EchSites]);
 * bangumi 已经在走加密直连时 (官方连不上, 这些服务多半也直连不通) 反过来先试加密直连. 加密直连握手被拒 (配置过期) 时换一份配置再试一次.
 *
 * 用法: [request] 交一个「按给的地址发一次」的函数, 它依次换地址调用. 地址都要是 https (加密直连只有 TLS).
 */
class CloudflareServiceTransport(
    /** 现在能走加密直连: 平台支持且没设代理 (条件同 bangumi 的那条线路). */
    private val echUsable: suspend () -> Boolean,
    /** 先试加密直连. */
    private val preferEch: () -> Boolean,
    /** 加密直连握手被拒时换一份 ECH 配置, 换到了返回 `true` (见 [BangumiEchTransport.recover]). */
    private val recoverEch: suspend (Throwable) -> Boolean,
) {
    /**
     * 依次用直连与加密直连的地址调 [send], 返回第一个非 `null` 的结果; 都连不上为 `null`.
     *
     * [send] 返回 `null` (超时) 或抛异常 (连不上) 都换下一种; 抛的异常在这里记日志后吞掉, 取消照常往外抛.
     */
    suspend fun <T : Any> request(url: String, send: suspend (url: String) -> T?): T? {
        val viaEch = if (echUsable()) EchSites.echUrlOf(url) else null
        val urls = listOfNotNull(url, viaEch).let { if (viaEch != null && preferEch()) it.reversed() else it }
        for (candidate in urls) {
            attempt(candidate, isEch = candidate == viaEch, send)?.let { return it }
        }
        return null
    }

    private suspend fun <T : Any> attempt(url: String, isEch: Boolean, send: suspend (url: String) -> T?): T? {
        var retried = false
        while (true) {
            try {
                val result = send(url)
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

        /** 只直连 (平台不支持加密直连、测试). */
        val DirectOnly = CloudflareServiceTransport(echUsable = { false }, preferEch = { false }, recoverEch = { false })
    }
}
