/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.session.auth

/**
 * **本机回环监听**, 接住外部浏览器里的 OAuth 回调 (RFC 8252 的原生应用做法).
 *
 * 为什么需要它: 回调原先是自定义 scheme (`ani://…`), 而**电视浏览器普遍不把它交给系统** ——
 * 实测 NVIDIA Shield 自带的 BrowseHere 直接 `shouldOverrideUrlLoading` 后塞进自己的 WebView
 * 加载, 报 "unknown protocol: ani", **从头到尾没发过 Intent**, app 永远等不到 code
 * (2026-09-06)。换成 `http://127.0.0.1:<port>/callback` 就是一个普通 http 地址, 浏览器老老实实
 * 请求它, 而请求直接落进我们自己的监听里 —— **不需要任何外部服务器**。
 *
 * bangumi 那边**用的是注册死的回调地址**, 传什么 `redirect_uri` 参数都没用
 * (见 [BangumiOAuthConstants] 的说明), 所以这个地址必须与 bgm 应用设置里填的逐字一致。
 *
 * 只接一次: 拿到 code 就关掉, 不常驻监听。
 */
expect class OAuthLoopbackServer(port: Int) {
    /**
     * 开始监听. 收到回调请求时用完整地址 (`http://127.0.0.1:port/callback?code=…&state=…`)
     * 调用 [onCallback], 给浏览器回一个"可以回到 App 了"的页面, 然后自动停止.
     *
     * @return 起得来返回 true; 端口被占或平台不支持返回 false (调用方据此退回自定义 scheme).
     */
    suspend fun start(onCallback: suspend (url: String) -> Unit): Boolean

    fun stop()
}
