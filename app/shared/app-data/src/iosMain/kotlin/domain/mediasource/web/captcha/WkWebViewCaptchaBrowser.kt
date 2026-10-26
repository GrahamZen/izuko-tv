/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package me.him188.ani.app.domain.mediasource.web.captcha

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import me.him188.ani.app.domain.mediasource.web.APPLE_WEBVIEW_USER_AGENT
import me.him188.ani.app.domain.mediasource.web.LoadedPage
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSError
import platform.Foundation.NSHTTPCookie
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationAction
import platform.WebKit.WKNavigationActionPolicy
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.WebKit.WKWebpagePreferences
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.coroutines.resume

/**
 * iOS 的平台浏览器 (WKWebView). Bangumi 应用内登录靠它: 授权页在应用里打开, 跳回回调地址的那一下由导航拦截器接住
 * (见 [CaptchaBrowser.setNavigationInterceptor]) —— 外部浏览器那条要靠回环监听收回调, 而应用切到后台后 iOS 不让它一直听着.
 * 网页源要过人机验证时也用它弹出页面.
 *
 * 资源拦截 ([CaptchaBrowser.setResourceInterceptor]) 只认主 frame 的导航: WKWebView 拦不到页面里的子资源请求.
 */
object WkWebViewCaptchaBrowserFactory : CaptchaBrowserFactory {
    override val isSupported: Boolean get() = true

    override val recommendedMaxSessions: Int get() = 2

    override suspend fun create(userAgent: String?): CaptchaBrowser =
        withContext(Dispatchers.Main) { WkWebViewCaptchaBrowser(userAgent) }
}

private class WkWebViewCaptchaBrowser(userAgent: String?) : CaptchaBrowser {
    // 数据源大多不指定 UA: 给 WKWebView 定一个已知的, 过盾拿到的 cf_clearance 才能配着同一个 UA 给 HTTP 请求用
    // (WKWebView 的默认 UA 读不出来, 留空的话 HTTP 侧照旧用自己的 UA, 带着这个 cookie 也一直被挡)
    override val userAgent: String = userAgent ?: APPLE_WEBVIEW_USER_AGENT

    private val webView = WKWebView(
        frame = CGRectMake(0.0, 0.0, 1000.0, 1000.0),
        configuration = WKWebViewConfiguration(),
    ).apply {
        customUserAgent = this@WkWebViewCaptchaBrowser.userAgent
    }

    private val _pageLoads = MutableSharedFlow<LoadedPage>(extraBufferCapacity = 16)
    override val pageLoads: SharedFlow<LoadedPage> = _pageLoads.asSharedFlow()

    private val _isLoading = MutableStateFlow(false)
    override val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private var navigationInterceptor: ((String) -> Boolean)? = null
    private var resourceInterceptor: ((String) -> InterceptDecision)? = null

    private val navigationDelegate = object : NSObject(), WKNavigationDelegateProtocol {
        override fun webView(
            webView: WKWebView,
            decidePolicyForNavigationAction: WKNavigationAction,
            decisionHandler: (WKNavigationActionPolicy) -> Unit,
        ) {
            decisionHandler(policyFor(decidePolicyForNavigationAction))
        }

        override fun webView(
            webView: WKWebView,
            decidePolicyForNavigationAction: WKNavigationAction,
            preferences: WKWebpagePreferences,
            decisionHandler: (WKNavigationActionPolicy, WKWebpagePreferences?) -> Unit,
        ) {
            decisionHandler(policyFor(decidePolicyForNavigationAction), preferences)
        }

        @ObjCSignatureOverride
        override fun webView(webView: WKWebView, didStartProvisionalNavigation: WKNavigation?) {
            _isLoading.value = true
        }

        @ObjCSignatureOverride
        override fun webView(webView: WKWebView, didFinishNavigation: WKNavigation?) {
            _isLoading.value = false
            emitCurrentPage()
        }

        @ObjCSignatureOverride
        override fun webView(webView: WKWebView, didFailNavigation: WKNavigation?, withError: NSError) {
            _isLoading.value = false
        }

        @ObjCSignatureOverride
        override fun webView(webView: WKWebView, didFailProvisionalNavigation: WKNavigation?, withError: NSError) {
            _isLoading.value = false
        }
    }

    init {
        webView.navigationDelegate = navigationDelegate
    }

    /** 主 frame 的一跳放不放行: 调用方接管 (OAuth 回调) 或资源拦截要拦的, 浏览器都不加载. */
    private fun policyFor(action: WKNavigationAction): WKNavigationActionPolicy {
        val url = action.request.URL?.absoluteString ?: return WKNavigationActionPolicy.WKNavigationActionPolicyAllow
        val handled = navigationInterceptor?.invoke(url) == true ||
                resourceInterceptor?.invoke(url) == InterceptDecision.Block
        return if (handled) WKNavigationActionPolicy.WKNavigationActionPolicyCancel else WKNavigationActionPolicy.WKNavigationActionPolicyAllow
    }

    private fun emitCurrentPage() {
        val url = webView.URL?.absoluteString ?: return
        webView.evaluateJavaScript(PAGE_HTML_SCRIPT) { result, _ ->
            _pageLoads.tryEmit(LoadedPage(url, result as? String ?: ""))
        }
    }

    override suspend fun navigate(url: String) {
        withContext(Dispatchers.Main) {
            webView.loadRequest(NSURLRequest.requestWithURL(NSURL(string = url)))
        }
    }

    override suspend fun currentPage(): LoadedPage? = withContext(Dispatchers.Main) {
        val url = webView.URL?.absoluteString ?: return@withContext null
        val html = evaluate(PAGE_HTML_SCRIPT) as? String ?: return@withContext null
        LoadedPage(url, html)
    }

    override suspend fun executeJavaScript(script: String) {
        withContext(Dispatchers.Main) { evaluate(script) }
    }

    private suspend fun evaluate(script: String): Any? = suspendCancellableCoroutine { cont ->
        webView.evaluateJavaScript(script) { result, _ ->
            if (cont.isActive) cont.resume(result)
        }
    }

    override suspend fun collectCookies(urls: List<String>): List<BrowserCookie> = withContext(Dispatchers.Main) {
        val cookies: List<NSHTTPCookie> = suspendCancellableCoroutine { cont ->
            webView.configuration.websiteDataStore.httpCookieStore.getAllCookies { all ->
                if (cont.isActive) cont.resume(all.orEmpty().filterIsInstance<NSHTTPCookie>())
            }
        }
        val hosts = urls.mapNotNull { NSURL(string = it).host }
        cookies
            .filter { cookie -> hosts.any { host -> host.endsWith(cookie.domain.removePrefix(".")) } }
            .map { cookie -> BrowserCookie(name = cookie.name, value = cookie.value, domain = cookie.domain, path = cookie.path) }
    }

    override fun setResourceInterceptor(handler: ((url: String) -> InterceptDecision)?) {
        resourceInterceptor = handler
    }

    override fun setNavigationInterceptor(handler: ((url: String) -> Boolean)?) {
        navigationInterceptor = handler
    }

    @Composable
    override fun View(
        modifier: Modifier,
        onExitRequest: (() -> Unit)?,
        onConfirmRequest: (() -> Unit)?,
        tvInputMode: TvWebInputMode,
    ) {
        UIKitView(factory = { webView }, modifier = modifier)
    }

    override fun close() {
        // 可能在任意线程关; WKWebView 只能在主线程碰
        dispatch_async(dispatch_get_main_queue()) {
            webView.stopLoading()
            webView.navigationDelegate = null
        }
    }
}

private const val PAGE_HTML_SCRIPT = "document.documentElement.outerHTML"
