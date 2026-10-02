/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.rule

import io.ktor.http.Url
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.io.IOException
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import me.him188.ani.utils.platform.currentTimeMillis
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * 站点把这个网络的 IP 拉黑了 (封禁页, 见 [isIpBlockPage]): 这一轮搜索就此停下, 剩下的名字不再试; [untilMillis] 前不再请求这个站点.
 * 没有验证可过, 不是验证码; 也不是规则写错.
 */
class SiteIpBlockedException(val host: String, val untilMillis: Long) :
    IOException("$host blocked this network's IP, not requesting it until $untilMillis")

/**
 * 拉黑了这个网络的站点 (按主机名) 与解封前不再请求的时刻. 只在内存: 应用重启后再请求一次, 还在封就再等一轮.
 *
 * 规则源搜一集要按几个名字各请求一次, 换集、完整搜索再来一轮; 站点的防火墙会把这种连续请求它资源接口的流量当采集, 拉黑整个网络出口
 * (实测 HTTP 456 「您的 IP 地址已被列入黑名单」, 连首页也打不开). 被拦之后还接着请求只会延长封禁.
 */
internal object RuleSiteBlocks {
    val COOLDOWN: Duration = 6.hours

    private val logger = logger<RuleSiteBlocks>()
    private val lock = SynchronizedObject()
    private val blockedUntil = HashMap<String, Long>()

    /** [url] 的站点还在冷却期: 不发请求, 直接抛 [SiteIpBlockedException]. */
    fun throwIfBlocked(url: String, now: Long = currentTimeMillis()) {
        val host = hostOf(url) ?: return
        val until = synchronized(lock) { blockedUntil[host]?.takeIf { it > now } } ?: return
        throw SiteIpBlockedException(host, until)
    }

    /** 这次响应是封禁页: 记下这个站点, 抛 [SiteIpBlockedException]. */
    fun checkResponse(url: String, status: Int, body: String, now: Long = currentTimeMillis()) {
        if (!isIpBlockPage(status, body)) return
        val host = hostOf(url) ?: return
        val until = now + COOLDOWN.inWholeMilliseconds
        synchronized(lock) { blockedUntil[host] = until }
        logger.warn { "$host blocked this network's IP (HTTP $status), not requesting it for $COOLDOWN" }
        throw SiteIpBlockedException(host, until)
    }

    /** 测试用. */
    fun clear() = synchronized(lock) { blockedUntil.clear() }

    private fun hostOf(url: String): String? = runCatching { Url(url).host }.getOrNull()?.takeIf { it.isNotBlank() }
}

/**
 * 站点防火墙拉黑这个网络出口的页面. 456 是一家 CDN 防火墙拉黑 IP 专用的状态码 (正文只有一个脚本, 写着「您的 IP 地址已被列入黑名单」);
 * 403 要正文明说拉黑才算 —— 不带说明的 403 多是验证码或个别页面不让看, 照旧按原来的规则处理.
 */
internal fun isIpBlockPage(status: Int, body: String): Boolean {
    if (status == 456) return true
    if (status != 403) return false
    val head = body.take(IP_BLOCK_SCAN_CHARS)
    return IP_BLOCK_MARKERS.any { head.contains(it, ignoreCase = true) }
}

private const val IP_BLOCK_SCAN_CHARS = 16 * 1024
private val IP_BLOCK_MARKERS = listOf("列入黑名单", "IP 地址已被", "IP address has been blacklisted", "your IP has been banned")
