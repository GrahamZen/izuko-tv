/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.resolver

import me.him188.ani.app.domain.episode.ipadDiag
import me.him188.ani.app.domain.episode.ipadDiagUrl
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.matcher.WebVideoDirectResolver
import me.him188.ani.datasources.api.matcher.WebVideoMatcher
import me.him188.ani.datasources.api.matcher.WebVideoMatcherContext
import platform.WebKit.WKUserScript
import platform.WebKit.WKUserScriptInjectionTime

/**
 * 直连取流 (规则源的 play 段、选择器源配的取流接口): 取到就不开 WKWebView, 同安卓与桌面的解析器.
 * 规则源的 play 走的是站点自己的接口与已过盾的会话, 比在 WKWebView 里嗅探可靠得多 —— WKWebView 拦不到子资源请求, 只能靠注入的脚本.
 */
internal suspend fun List<WebVideoMatcher>.resolveDirectlyOrNull(
    media: Media,
    context: WebVideoMatcherContext,
): HttpStreamingMediaDataProvider? {
    for (matcher in this) {
        if (matcher !is WebVideoDirectResolver) continue
        val direct = matcher.resolveDirectly(media.download.uri, context)
        ipadDiag("direct resolve ${media.mediaSourceId}: ${direct?.let { "${ipadDiagUrl(it.m3u8Url)} headers=${it.headers.keys}" } ?: "none"}")
        direct ?: continue
        return HttpStreamingMediaDataProvider(
            direct.m3u8Url,
            media.originalTitle,
            direct.headers,
            media.extraFiles.toMediampMediaExtraFiles(),
        )
    }
    ipadDiag("direct resolve ${media.mediaSourceId}: no direct result (${count { it is WebVideoDirectResolver }} direct resolvers), sniff in WKWebView")
    return null
}

/**
 * 补在 [IosWebViewVideoExtractor] 原有脚本 (fetch / XHR / setAttribute) 之后的嗅探脚本, 报给同一个 `AniIntercept`:
 * - `video.src = …` / `source.src = …` 这种属性赋值 (Safari 原生放 HLS, 播放器多是直接把 m3u8 赋给 video.src, 不走 XHR);
 * - 页面 HTML 里写死的、或之后插进来的 video / source / iframe 的 src;
 * - 每半秒扫一遍媒体元素的 currentSrc 与 Resource Timing 里新增的请求 (兜住别的路径发出的请求).
 * 报上去的地址由原生那边去重并交给匹配器, 不是视频的会被忽略.
 */
internal fun iosVideoSniffExtraScript(): WKUserScript = WKUserScript(
    source = IOS_VIDEO_SNIFF_EXTRA_SCRIPT,
    injectionTime = WKUserScriptInjectionTime.WKUserScriptInjectionTimeAtDocumentStart,
    forMainFrameOnly = false,
)

private val IOS_VIDEO_SNIFF_EXTRA_SCRIPT = """
(function() {
    if (window.__aniSniffExtra) return;
    window.__aniSniffExtra = true;
    var handlers = window.webkit && window.webkit.messageHandlers;
    if (!handlers || !handlers.AniIntercept) return;
    var reported = {};
    function report(value) {
        try {
            if (!value) return;
            var url = String(value);
            if (url.indexOf('blob:') === 0 || url.indexOf('data:') === 0 || url.indexOf('about:') === 0) return;
            if (reported[url]) return;
            reported[url] = true;
            handlers.AniIntercept.postMessage(url);
        } catch (e) {}
    }
    function hookSrc(proto) {
        if (!proto) return;
        var desc = Object.getOwnPropertyDescriptor(proto, 'src');
        if (!desc || !desc.get || !desc.set) return;
        Object.defineProperty(proto, 'src', {
            configurable: true,
            enumerable: desc.enumerable,
            get: function() { return desc.get.call(this); },
            set: function(value) { report(value); desc.set.call(this, value); }
        });
    }
    hookSrc(window.HTMLMediaElement && HTMLMediaElement.prototype);
    hookSrc(window.HTMLSourceElement && HTMLSourceElement.prototype);
    function scan(node) {
        if (!node || node.nodeType !== 1) return;
        var tag = node.tagName;
        if (tag === 'VIDEO' || tag === 'AUDIO' || tag === 'SOURCE' || tag === 'IFRAME' || tag === 'EMBED') {
            report(node.getAttribute('src'));
        }
        if (node.querySelectorAll) {
            var list = node.querySelectorAll('video[src],audio[src],source[src],iframe[src],embed[src]');
            for (var i = 0; i < list.length; i++) report(list[i].getAttribute('src'));
        }
    }
    try {
        new MutationObserver(function(mutations) {
            for (var i = 0; i < mutations.length; i++) {
                var m = mutations[i];
                if (m.type === 'attributes') {
                    scan(m.target);
                } else {
                    for (var j = 0; j < m.addedNodes.length; j++) scan(m.addedNodes[j]);
                }
            }
        }).observe(document, { childList: true, subtree: true, attributes: true, attributeFilter: ['src'] });
    } catch (e) {}
    setInterval(function() {
        try {
            var media = document.querySelectorAll('video,audio');
            for (var i = 0; i < media.length; i++) report(media[i].currentSrc);
            if (window.performance && performance.getEntriesByType) {
                var entries = performance.getEntriesByType('resource');
                for (var k = 0; k < entries.length; k++) report(entries[k].name);
                // 缓冲区默认只存 250 条, 满了新的就记不进来
                if (performance.clearResourceTimings) performance.clearResourceTimings();
            }
        } catch (e) {}
    }, 500);
})();
""".trimIndent()
