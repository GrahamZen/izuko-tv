/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.platform

import io.ktor.http.Url
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.URI

internal actual fun createSystemProxyDetector(): SystemProxyDetector = AndroidSystemProxyDetector()

/**
 * Android 上的系统代理探测.
 *
 * **原先这里是 [NoOpSystemProxyDetector]** —— 于是设置里的「系统代理」在 Android/TV 上是个空开关:
 * `SystemProxyProvider` 永远拿到 `null`, 界面上永远显示"未检测到", 用户开了以为生效了其实什么都没发生
 * (2026-09-07 用户回忆的正是这件事).
 *
 * **拿不到 `Context`**: [SystemProxyDetector.instance] 是个进程级 lazy 单例, 没有注入点. 所以用两条
 * 不需要 Context 的路, 覆盖面已经够:
 *
 * 1. **系统属性** `http.proxyHost` / `http.proxyPort` —— Android 给"全局/Wi-Fi HTTP 代理"设的就是
 *    这两个 (`android.net.Proxy.setHttpProxySystemProperty`), 这也是绝大多数人手动填代理的入口.
 * 2. **[ProxySelector]** —— PAC 脚本与经 `ConnectivityManager` 下发的 `ProxyInfo` 走这条.
 *
 * **探测不到不等于用不了代理**: OkHttp (本应用 JVM 侧的引擎) 在没有显式代理时会用默认的
 * [ProxySelector], 所以系统代理本来就可能在悄悄生效 —— 探测出来的意义在于**让开关名副其实**,
 * 并且能在界面上告诉用户探到了什么.
 *
 * **VPN/TUN 类工具 (Clash 的 TUN 模式等) 不需要这里的任何东西**: 那是网络层接管, 与应用无关.
 */
private class AndroidSystemProxyDetector : SystemProxyDetector {
    private val logger = logger<AndroidSystemProxyDetector>()

    override fun detect(): SystemProxyInfo? {
        fromSystemProperties()?.let {
            logger.info { "Detected system proxy from system properties: ${it.url}" }
            return it
        }
        fromProxySelector()?.let {
            logger.info { "Detected system proxy from ProxySelector: ${it.url}" }
            return it
        }
        return null
    }

    private fun fromSystemProperties(): SystemProxyInfo? {
        val host = System.getProperty("http.proxyHost")?.takeIf { it.isNotBlank() } ?: return null
        // 端口缺省按 http 的 80 算: Android 填代理时端口是必填项, 缺了说明这份配置本来就不完整,
        // 但 host 有值仍比整个丢掉强
        val port = System.getProperty("http.proxyPort")?.trim()?.toIntOrNull() ?: 80
        return runCatching { SystemProxyInfo(Url("http://$host:$port")) }.getOrNull()
    }

    /**
     * 用一个**通用的** https 地址去问 [ProxySelector].
     *
     * 不拿应用真正要访问的域名去问, 是因为这个探测结果要展示给用户、也要喂给整个 HttpClient ——
     * 按某一个域名问出来的答案 (`http.nonProxyHosts`、PAC 里的分流规则) 不能代表全局.
     */
    private fun fromProxySelector(): SystemProxyInfo? = runCatching {
        val selected = ProxySelector.getDefault()?.select(URI("https://example.com")).orEmpty()
        selected.asSequence()
            .filter { it.type() == Proxy.Type.HTTP || it.type() == Proxy.Type.SOCKS }
            .mapNotNull { proxy ->
                val address = proxy.address() as? InetSocketAddress ?: return@mapNotNull null
                val host = address.hostString?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val scheme = if (proxy.type() == Proxy.Type.SOCKS) "socks5" else "http"
                runCatching { SystemProxyInfo(Url("$scheme://$host:${address.port}")) }.getOrNull()
            }
            .firstOrNull()
    }.getOrNull()
}
