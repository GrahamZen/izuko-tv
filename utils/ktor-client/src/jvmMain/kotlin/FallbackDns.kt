/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.utils.ktor

import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.okhttp.OkHttpConfig
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap

/**
 * 系统 DNS 解析不出时的兜底. 有的路由器、运营商的 DNS 时好时坏: 几分钟前还解析得了的视频域名,
 * 播到一半忽然 `No address associated with hostname`, 这一段就断了. 所以系统解析失败时:
 * - 先用这个域名最近一次解析出的地址 (留 [lastGoodTtlMillis]);
 * - 没解析出过就问公共 DNS ([publicLookup]); 结果 (查不到也算) 留 [publicTtlMillis], 一个死域名不会每个请求都问一遍.
 * 都没有才照常抛 [UnknownHostException].
 */
class FallbackDns(
    private val system: Dns = Dns.SYSTEM,
    private val publicLookup: (String) -> List<InetAddress> = { PublicDns.lookup(it) },
    private val lastGoodTtlMillis: Long = 6 * 60 * 60_000L,
    private val publicTtlMillis: Long = 60_000L,
    private val now: () -> Long = System::currentTimeMillis,
) : Dns {
    private class Resolved(val at: Long, val addresses: List<InetAddress>)

    private val lastGood = ConcurrentHashMap<String, Resolved>()
    private val publicAnswers = ConcurrentHashMap<String, Resolved>()
    private val publicLocks = ConcurrentHashMap<String, Any>()

    /** 每个域名上次记兜底日志的时刻: 系统 DNS 坏着时每个新连接都会走一遍兜底, 一分钟只记一次. */
    private val loggedAt = ConcurrentHashMap<String, Long>()

    override fun lookup(hostname: String): List<InetAddress> {
        val failure = try {
            return system.lookup(hostname).also { if (it.isNotEmpty()) remember(hostname, it) }
        } catch (e: UnknownHostException) {
            e
        }
        lastGood[hostname]?.takeIf { now() - it.at < lastGoodTtlMillis }?.let {
            logOnce(hostname) { "System DNS failed for $hostname (${failure.message}), using the last resolved ${it.addresses.describe()}" }
            return it.addresses
        }
        val public = synchronized(publicLocks.computeIfAbsent(hostname) { Any() }) {
            publicAnswers[hostname]?.takeIf { now() - it.at < publicTtlMillis }?.addresses
                ?: publicLookup(hostname).also { publicAnswers[hostname] = Resolved(now(), it) }
        }
        if (public.isEmpty()) throw failure
        logOnce(hostname) { "System DNS failed for $hostname (${failure.message}), using public DNS ${public.describe()}" }
        return public
    }

    private fun remember(hostname: String, addresses: List<InetAddress>) {
        if (lastGood.size >= MAX_HOSTS) {
            lastGood.entries.removeAll { now() - it.value.at >= lastGoodTtlMillis }
            if (lastGood.size >= MAX_HOSTS) lastGood.clear()
        }
        lastGood[hostname] = Resolved(now(), addresses)
    }

    private inline fun logOnce(hostname: String, message: () -> String) {
        val now = now()
        val last = loggedAt[hostname]
        if (last != null && now - last < publicTtlMillis) return
        loggedAt[hostname] = now
        if (loggedAt.size > MAX_HOSTS) loggedAt.clear()
        logger.warn(message())
    }

    private fun List<InetAddress>.describe() = joinToString { it.hostAddress.orEmpty() }

    companion object {
        private val logger = logger<FallbackDns>()

        private const val MAX_HOSTS = 1024

        /** 全应用共用一份: 记下的「最近一次解析出的地址」各个客户端都用得上. */
        val Default: FallbackDns by lazy { FallbackDns() }
    }
}

actual fun HttpClientConfig<*>.engineFallbackDns() {
    @Suppress("UNCHECKED_CAST") // engine 块只会作用于实际的引擎配置, 类型在块内判断
    (this as HttpClientConfig<HttpClientEngineConfig>).engine {
        if (this !is OkHttpConfig) return@engine
        config { dns(FallbackDns.Default) }
    }
}
