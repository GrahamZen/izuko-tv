/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.web

/**
 * 数据源没指定 UA 时 WKWebView 用的: iPad 上 Safari 默认报的桌面版 (与页面里 navigator.platform 的 MacIntel 对得上).
 *
 * 抓视频的 WebView 与过人机验证的浏览器用同一个: 验证拿到的 cf_clearance 认 UA, 换一个就失效;
 * 冒充别的浏览器 (UA 说是 Chrome, 页面里的表现是 WebKit) 会被 Cloudflare 当成机器人, 直接回拦截页.
 */
const val APPLE_WEBVIEW_USER_AGENT =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15"

/** 抓视频的 WebView 这次用的 UA: 数据源指定的 ([configured]) 优先, 没有时用 [APPLE_WEBVIEW_USER_AGENT]. */
internal fun appleWebViewUserAgentFor(configured: String?): String = configured ?: APPLE_WEBVIEW_USER_AGENT
