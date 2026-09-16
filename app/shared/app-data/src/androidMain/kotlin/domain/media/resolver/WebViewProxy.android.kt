/*
 * Copyright (C) 2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.resolver

import android.os.Handler
import android.os.Looper
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import me.him188.ani.app.data.models.preference.ProxyConfig
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import java.util.concurrent.Executor
import androidx.webkit.ProxyConfig as WebkitProxyConfig

/**
 * 把 Ani 的代理设置应用到 WebView.
 *
 * WebView 不走 Ktor, 也读不到我们的代理配置, 所以设置里开了代理时在线源仍然直连 —— 表现为
 * 搜索得到结果但一播放就「未知错误」. 这里用 [ProxyController] 补上.
 *
 * 代理是**整个进程级**的, 一次设置对所有 WebView 生效 (在线源解析 / 验证码 / 登录页), 因此用
 * 单例记住已生效的值, 只在变化时重设.
 */
internal object WebViewProxy {
    private val logger = logger<WebViewProxy>()
    private val directExecutor = Executor { it.run() }

    // null = 还没设过; "" = 已设为直连
    private var applied: String? = null

    @Synchronized
    fun apply(config: ProxyConfig?) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            // 取 ProxyController 会初始化 WebView, 不在主线程会抛 "Must be started before we block!"
            Handler(Looper.getMainLooper()).post { apply(config) }
            return
        }
        val url = config?.url?.takeIf { it.isNotBlank() }
        val key = url.orEmpty()
        if (key == applied) return

        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            // 系统 WebView 太旧 (< 73). 只能直连, 说一声就不再重复判断了
            logger.info { "System WebView does not support proxy override; web sources will connect directly." }
            applied = key
            return
        }
        if (url != null && url.startsWith("socks", ignoreCase = true)) {
            // WebView 只认 http/https 代理
            logger.info { "WebView does not support SOCKS proxy ($url); web sources will connect directly." }
            applied = key
            return
        }

        try {
            val controller = ProxyController.getInstance()
            if (url == null) {
                controller.clearProxyOverride(directExecutor) {}
            } else {
                controller.setProxyOverride(
                    WebkitProxyConfig.Builder()
                        .addProxyRule(url)
                        // 局域网别走代理 (自建源 / 本机服务); WebView 默认已绕过 localhost 与 link-local
                        .addBypassRule("10.0.0.0/8")
                        .addBypassRule("172.16.0.0/12")
                        .addBypassRule("192.168.0.0/16")
                        .build(),
                    directExecutor,
                ) {}
            }
            applied = key
            logger.info { "Applied WebView proxy: ${url ?: "direct"}" }
        } catch (e: Throwable) {
            // 取 ProxyController 会初始化 WebView, 没装 WebView 的设备会抛
            logger.info { "Failed to apply WebView proxy: $e" }
            applied = key
        }
    }
}
