/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation

import com.github.panpf.sketch.fetch.Fetcher
import com.github.panpf.sketch.fetch.HttpUriFetcher
import com.github.panpf.sketch.fetch.isHttpUri
import com.github.panpf.sketch.http.HttpHeaders
import com.github.panpf.sketch.http.HttpStack
import com.github.panpf.sketch.request.Extras
import com.github.panpf.sketch.request.RequestContext
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.contentnegotiation.exclude
import io.ktor.client.plugins.retry
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.isSuccess
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.cancel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException
import me.him188.ani.utils.ktor.ScopedHttpClient
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import io.ktor.http.HttpHeaders as KtorHttpHeaders

private val imageLoadLogger = logger("ImageLoad")

/**
 * 慢请求的判定阈值. 只记超过它的 —— 见 [ScopedHttpClientHttpStack.request] 里的说明.
 */
private val SLOW_IMAGE_REQUEST_THRESHOLD = 3.seconds

/**
 * 跟 `og:image` 之前愿意读多大的 HTML. 图床的图片页都在几十 KB 量级 (postimg 实测 51 KB),
 * 超过就不是图片页, 不值得为它把整份文档读进内存.
 */
private const val MAX_HTML_PAGE_SIZE = 512L * 1024

private val OPEN_GRAPH_IMAGE_REGEX = Regex(
    """<meta[^>]+?(?:property|name)\s*=\s*["'](?:og:image(?::secure_url|:url)?|twitter:image(?::src)?)["'][^>]*?>""",
    RegexOption.IGNORE_CASE,
)
private val META_CONTENT_REGEX = Regex("""content\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

/**
 * 从图床网页里取出 `og:image` (或 `twitter:image`) 指向的图片直链. 取不到返回 `null`.
 *
 * 只认 http(s) 绝对地址: 相对地址要拼 base, 而图床都给绝对地址, 不值得为此引入 URL 解析.
 */
internal fun extractOpenGraphImageUrl(html: String): String? {
    for (match in OPEN_GRAPH_IMAGE_REGEX.findAll(html)) {
        val content = META_CONTENT_REGEX.find(match.value)?.groupValues?.get(1) ?: continue
        val url = content.trim()
            .replace("&amp;", "&")
            .replace("&#039;", "'")
        if (url.startsWith("http://") || url.startsWith("https://")) return url
    }
    return null
}

/**
 * Sketch HTTP stack backed by Ani's dynamically replaceable [ScopedHttpClient].
 *
 * The response block deliberately stays inside [ScopedHttpClient.use]. A borrowed Ktor client may
 * be closed as soon as the block returns, so neither the response nor its body can escape it.
 */
internal class ScopedHttpClientHttpStack(
    private val scopedClient: ScopedHttpClient,
) : HttpStack {
    override suspend fun <T> request(
        url: String,
        httpHeaders: HttpHeaders?,
        extras: Extras?,
        block: suspend (HttpStack.Response) -> T,
    ): T = request(url, httpHeaders, extras, followedPage = false, block)

    private suspend fun <T> request(
        url: String,
        httpHeaders: HttpHeaders?,
        extras: Extras?,
        followedPage: Boolean,
        block: suspend (HttpStack.Response) -> T,
    ): T {
        val outcome = scopedClient.use {
            val request = HttpRequestBuilder().apply {
                url(url)
                httpHeaders?.addList?.forEach { (name, value) -> headers.append(name, value) }
                httpHeaders?.setList?.forEach { (name, value) -> headers[name] = value }
                configureAniImageRequest()
            }
            val startedAt = TimeSource.Monotonic.markNow()
            try {
                prepareRequest(request).execute { response ->
                    val elapsed = startedAt.elapsedNow()
                    // 只记异常: 失败, 以及慢于阈值的. 正常加载 (磁盘命中几十毫秒, 网络几百毫秒) 一律不记,
                    // 否则滚动一次列表就是几百行日志. 图加载不出来时这两行是唯一的线索来源.
                    if (!response.status.isSuccess()) {
                        imageLoadLogger.warn { "Image request failed: ${response.status} after $elapsed: $url" }
                    } else if (elapsed > SLOW_IMAGE_REQUEST_THRESHOLD) {
                        imageLoadLogger.warn { "Slow image request: $elapsed: $url" }
                    }

                    val pageImageUrl =
                        if (!followedPage) response.resolveImageUrlFromHtmlPage(url) else null
                    if (pageImageUrl != null) {
                        // 交给外层重新请求: 这里还在 execute {} 里, 借来的 client 不能带着响应逃出去
                        return@execute Outcome.FollowPage(pageImageUrl)
                    }
                    Outcome.Done(block(KtorResponse(response)))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                imageLoadLogger.warn(e) { "Image request threw after ${startedAt.elapsedNow()}: $url" }
                throw e
            }
        }

        return when (outcome) {
            is Outcome.Done -> outcome.value
            is Outcome.FollowPage -> {
                imageLoadLogger.warn {
                    "Image URL is a web page, following og:image: $url -> ${outcome.url}"
                }
                request(outcome.url, httpHeaders, extras, followedPage = true, block)
            }
        }
    }

    private sealed interface Outcome<out T> {
        class Done<T>(val value: T) : Outcome<T>
        class FollowPage(val url: String) : Outcome<Nothing>
    }

    /**
     * 评论里贴的常常是图床的**网页**地址 (postimg.cc/xxxx 之类) 而不是图片直链: 请求回来是一份
     * HTML, 解码器当然解不出图, 界面上是"加载很久然后显示图片已失效".
     *
     * 图床的网页都带 `og:image` 指向原图, 所以这里不按图床逐个写正则 (那种表永远补不完, 且
     * 直链 id 与网页 id 往往不是一回事 —— postimg 的网页 id 只能换到 180px 缩略图), 而是发现
     * 响应是 HTML 时顺着 `og:image` 再取一次. 正常图片请求不会进这条路, 零额外开销.
     *
     * 只跟一跳, 且只在成功响应上跟.
     */
    private suspend fun HttpResponse.resolveImageUrlFromHtmlPage(requestUrl: String): String? {
        if (!status.isSuccess()) return null
        val contentType = headers[KtorHttpHeaders.ContentType] ?: return null
        if (!contentType.startsWith(ContentType.Text.Html.contentType + "/" + ContentType.Text.Html.contentSubtype)) {
            return null
        }
        val declaredLength = headers[KtorHttpHeaders.ContentLength]?.toLongOrNull() ?: -1L
        if (declaredLength > MAX_HTML_PAGE_SIZE) {
            imageLoadLogger.warn { "Image URL returned a $declaredLength byte HTML page, not following: $requestUrl" }
            return null
        }
        val html = try {
            bodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            imageLoadLogger.warn(e) { "Failed to read HTML page for $requestUrl" }
            return null
        }
        val resolved = extractOpenGraphImageUrl(html) ?: return null
        return resolved.takeIf { it != requestUrl }
    }


    override fun toString(): String = "ScopedHttpClientHttpStack"

    private class KtorResponse(
        private val response: HttpResponse,
    ) : HttpStack.Response {
        override val code: Int = response.status.value
        override val message: String = response.status.description
        override val contentLength: Long =
            response.headers[KtorHttpHeaders.ContentLength]?.toLongOrNull() ?: -1L
        override val contentType: String? = response.headers[KtorHttpHeaders.ContentType]

        override fun getHeaderField(name: String): String? = response.headers[name]

        override suspend fun content(): HttpStack.Content = KtorContent(response.bodyAsChannel())
    }

    private class KtorContent(
        private val channel: ByteReadChannel,
    ) : HttpStack.Content {
        override suspend fun read(buffer: ByteArray): Int =
            channel.readAvailable(buffer, 0, buffer.size)

        override fun close() {
            channel.cancel()
        }
    }
}

/**
 * 图片请求在 ktor 层的四项修正. 与图片库无关 (coil 时代在 `ScopedHttpClientNetworkFetcher` 里),
 * 都是真机实测出来的:
 *
 * 1. **明说"我要图"**. 共享的 HttpClient 给 ContentNegotiation 注册了 text/html 与 text/xml
 *    (见 `DefaultClient.kt`, 影视源要拿它解析网页), 而这个插件会把**每个注册过的类型**都塞进
 *    出站 Accept 头, 于是图片请求发出去的是 `Accept: application/json, text/html, text/xml`.
 *    imgur 按 Accept 做内容协商: 见到 text/html 就把 `i.imgur.com/xxx.jpeg` 当成"要网页",
 *    回一个 200 + text/html 的 7KB 落地页 —— 图当然解不出来, 表现是占位撑开后又塌掉.
 *    `exclude` 是 ktor 的 per-request 开关, 挡住插件往 Accept 里追加那几个类型;
 *    再加一条通配兜底 (q=0.8): 有些图床把图发成 application/octet-stream, 只声明图片类型会被 406.
 * 2. **短连接超时 (2.5s)**. 图床 (image.tmdb.org 等) 解析出的部分 IP 直连不通, 全局默认 30s 会
 *    让单张图挂满半分钟才轮到重试; 连接超时设短一点快速失败, 交给下面的重试换 IP.
 * 3. **读超时 (3s)**. 管的是另一半: "连上了但流假死". 只设连接超时的话这一类会等到全局默认的
 *    30 秒 —— 而本机上假死是常态. 具体数字与取证见下面 `timeout {}` 里的注释.
 * 4. **重试 3 次 + 200ms 固定延迟**. 全局重试只认 IOException 且仅 1 次. 三类故障都靠"换一条
 *    连接"恢复: HTTP/2 复用把请求发到同 IP 的错误主机 (421 Misdirected Request, 实测
 *    lain.bangumi.one 封面偶发)、墙内 image.tmdb.org 挨 RST、以及 DNS 给出的部分 IP 直接连不通.
 *    所以延迟要短 —— 干等不会让死 IP 变通; 通的网络本来就走不到重试, 不受影响.
 */
private fun HttpRequestBuilder.configureAniImageRequest() {
    if (headers[KtorHttpHeaders.Accept] == null) {
        exclude(ContentType.Application.Json, ContentType.Text.Html, ContentType.Text.Xml)
        headers[KtorHttpHeaders.Accept] = "${ContentType.Image.Any},*/*;q=0.8"
    }
    timeout {
        // 2.5s 而不是更长: 这是**连接**超时, 不是下载超时. 真机实测同一台电视上 image.tmdb.org
        // 连得通的 IP 是 47~662ms 完成整个请求, 所以 2.5 秒还没连上基本就是一个连不通的 IP.
        // 曾经设 8s, 代价是每撞上一个死 IP 就白等 8 秒 (2026-08-21 日志: DNS 给出
        // 185.93.1.245/.249/.250/.251 全部 TCP 不通, 重试换 IP 后 300ms 就拿到图, 但用户
        // 看到的是 9.5 秒 —— hero 背景"有几个特别慢"就是这么来的).
        connectTimeoutMillis = 2_500
        // **连上之后流假死**这一类原先完全没人管: 只设了连接超时, 读超时落到全局默认的 30 秒.
        // 这台机器上假死是常态 —— 实测背景图卡住 10.7s/10.2s 而同期其它请求 200~300ms 正常完成;
        // 2026-08-21 又量到剧集图 w1280 卡了 5.18s, 端到端 ≈ 网络耗时 (排队和解码都没问题).
        // 3 秒后放弃, 交给下面的重试换一条新连接 —— 按实测"重发基本必然秒成".
        //
        // 注意这是**空闲**超时 (两次读之间的间隔), 不是总耗时: 真正的慢链路会持续吐字节,
        // 间隔很小, 不会被误杀; 图片最大也就几百 KB, 连上后 3 秒一个字节都不来就是病态.
        // (`TvBackdropImage` 那个"卡死重发"是 coil 时代为同一个病写的局部止痛药, 只保 backdrop;
        //  这里是通用的一剂, 剧集图/封面/头像都受益.)
        socketTimeoutMillis = 3_000
    }
    retry {
        maxRetries = 3
        retryIf { _, response -> response.status.value == 421 }
        retryOnExceptionIf { _, cause ->
            cause is IOException || (cause as? ClientRequestException)?.response?.status?.value == 421
        }
        // 短固定延迟, 不用指数退避: 这里的重试是为了**换一条连接/换一个 IP**, 等待本身不解决
        // 任何问题 (退避对"服务端过载"才有意义, 对"这个 IP 不通"只是干等).
        constantDelay(millis = 200, randomizationMs = 100)
    }
}

/** Registers Sketch's standard HTTP fetch pipeline with Ani's scoped Ktor stack. */
internal class ScopedHttpClientHttpUriFetcherFactory(
    private val httpStack: ScopedHttpClientHttpStack,
) : Fetcher.Factory {
    override val sortWeight: Int = HttpUriFetcher.SORT_WEIGHT

    override fun create(requestContext: RequestContext): HttpUriFetcher? {
        if (!isHttpUri(requestContext.request.uri)) return null
        return HttpUriFetcher(
            sketch = requestContext.sketch,
            httpStack = httpStack,
            request = requestContext.request,
            downloadCacheKey = requestContext.downloadCacheKey,
        )
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is ScopedHttpClientHttpUriFetcherFactory && httpStack == other.httpStack

    override fun hashCode(): Int = httpStack.hashCode()

    override fun toString(): String = "ScopedHttpClientHttpUriFetcherFactory"
}
