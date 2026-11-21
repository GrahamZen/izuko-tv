/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.resolver

import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import platform.Foundation.NSHTTPCookie
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.WebKit.WKWebView

/**
 * 嗅探页被 Cloudflare 拦截 (拦截页 "Sorry, you have been blocked", 不是验证页) 时, 清掉这个站的 cookie 重开一次.
 *
 * WKWebView 的 cookie 存在整个 app 共用的默认存储里. 存下的 cf_clearance 与现在的 UA 对不上时 (旧版用别的 UA 拿到的),
 * Cloudflare 每次都回拦截页, 换 UA、换网络都没用; 清掉重开就和第一次访问一样重新过一遍 (iPad 上实测: 同一个播放页清掉后正常打开).
 * 拦截页会拉 `/cdn-cgi/styles/cf.errors.css`, 嗅探脚本报上来的地址里有它就是被拦了.
 */
internal class CloudflareBlockRetry(private val webView: WKWebView, private val pageUrl: String) {
    private var retried = false

    /** [url] 是拦截页的资源时清 cookie 并重开 (每个嗅探只一次), 返回 true. 主线程上调. */
    fun onResource(url: String): Boolean {
        if (retried || BLOCK_PAGE_STYLESHEET !in url) return false
        retried = true
        val host = NSURL(string = pageUrl).host ?: return false
        val store = webView.configuration.websiteDataStore.httpCookieStore
        store.getAllCookies { all ->
            val cookies = all.orEmpty().filterIsInstance<NSHTTPCookie>().filter { host.endsWith(it.domain.removePrefix(".")) }
            logger.warn { "[Sniff] $host 回了 Cloudflare 拦截页, 清掉这个站的 ${cookies.size} 个 cookie 重开一次" }
            var remaining = cookies.size
            val reload = { webView.loadRequest(NSURLRequest.requestWithURL(NSURL(string = pageUrl))) }
            if (remaining == 0) reload()
            for (cookie in cookies) {
                store.deleteCookie(cookie) {
                    remaining--
                    if (remaining == 0) reload()
                }
            }
        }
        return true
    }

    private companion object {
        private val logger = logger<CloudflareBlockRetry>()
        const val BLOCK_PAGE_STYLESHEET = "/cdn-cgi/styles/cf.errors.css"
    }
}
