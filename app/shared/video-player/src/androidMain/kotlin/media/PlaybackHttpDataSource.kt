/*
 * Copyright (C) 2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.media

import androidx.annotation.OptIn as AndroidxOptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.Authenticator
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.TimeUnit
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger

/**
 * 播放要用的代理. 由 app 层从设置里的代理配置转换而来.
 */
data class PlaybackProxyConfig(
    val proxy: Proxy,
    /**
     * `Proxy-Authorization` 头的值 (如 `Basic xxx`), 代理不需要认证时为 `null`.
     */
    val authorization: String? = null,
) {
    companion object {
        /**
         * @param url `http://host:port` 或 `socks://host:port`
         * @return 解析不出主机时返回 `null` (当作不走代理)
         */
        fun parse(url: String, authorization: String? = null): PlaybackProxyConfig? {
            // 设置里允许不写协议 (Ktor 侧会自己补), 这里跟着补一个, 否则 URI 解不出主机
            val normalized = if (url.contains("://")) url else "http://" + url
            val uri = try {
                URI(normalized)
            } catch (e: Exception) {
                return null
            }
            val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
            val socks = uri.scheme?.startsWith("socks", ignoreCase = true) == true
            val port = uri.port.takeIf { it > 0 } ?: if (socks) 1080 else 80
            return PlaybackProxyConfig(
                Proxy(
                    if (socks) Proxy.Type.SOCKS else Proxy.Type.HTTP,
                    InetSocketAddress.createUnresolved(host, port),
                ),
                authorization,
            )
        }
    }
}

/**
 * 建播放用的 HTTP 数据源工厂.
 *
 * 没配代理时用 media3 自带的 [DefaultHttpDataSource] (底层 `HttpURLConnection`), 行为与以前一致.
 * 配了代理就换成 OkHttp —— `HttpURLConnection` 只认 JVM 全局的 `ProxySelector`, 没法只给播放加代理,
 * 而在线源的视频地址跟数据源接口一样常常需要代理才连得上 (设置里开了代理却放不了在线源就是这个原因).
 */
private val logger = logger("PlaybackHttpDataSource")

@AndroidxOptIn(UnstableApi::class)
internal fun createPlaybackHttpDataSourceFactory(
    proxyConfig: PlaybackProxyConfig?,
    userAgent: String,
    headers: Map<String, String>,
    connectTimeoutMillis: Int,
): HttpDataSource.Factory {
    logger.info { "Creating playback data source, proxy=" + (proxyConfig?.proxy?.toString() ?: "direct") }
    if (proxyConfig == null) {
        return DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setDefaultRequestProperties(headers)
            .setConnectTimeoutMs(connectTimeoutMillis)
    }
    val client = OkHttpClient.Builder()
        .connectTimeout(connectTimeoutMillis.toLong(), TimeUnit.MILLISECONDS)
        // 局域网地址 (Jellyfin 等本地源) 不能走代理, 所以按地址分流而不是 .proxy()
        .proxySelector(LanBypassProxySelector(proxyConfig.proxy))
        .apply {
            proxyConfig.authorization?.let { auth ->
                proxyAuthenticator(
                    Authenticator { _, response ->
                        // 已经带过还被拒就不再重试, 否则会死循环
                        if (response.request.header("Proxy-Authorization") != null) return@Authenticator null
                        response.request.newBuilder().header("Proxy-Authorization", auth).build()
                    },
                )
            }
        }
        .build()
    return OkHttpDataSource.Factory(client)
        .setUserAgent(userAgent)
        .setDefaultRequestProperties(headers)
}

private class LanBypassProxySelector(proxy: Proxy) : ProxySelector() {
    private val proxyList = listOf(proxy)

    override fun select(uri: URI?): List<Proxy> {
        val host = uri?.host ?: return proxyList
        return if (isLocalOrPrivate(host)) DIRECT else proxyList
    }

    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: java.io.IOException?) {
        // 交给上层报错即可, 不需要切换到别的代理
    }

    private fun isLocalOrPrivate(rawHost: String): Boolean {
        val host = rawHost.removePrefix("[").removeSuffix("]").lowercase()
        if (host == "localhost" || host.endsWith(".local") || host.endsWith(".lan")) return true
        if (host == "::1" || host.startsWith("fc") || host.startsWith("fd")) return true
        if (!host[0].isDigit()) return false
        val parts = host.split('.')
        if (parts.size != 4) return false
        val a = parts[0].toIntOrNull() ?: return false
        val b = parts[1].toIntOrNull() ?: return false
        return when (a) {
            10, 127 -> true
            192 -> b == 168
            172 -> b in 16..31
            169 -> b == 254
            else -> false
        }
    }

    private companion object {
        private val DIRECT = listOf(Proxy.NO_PROXY)
    }
}
