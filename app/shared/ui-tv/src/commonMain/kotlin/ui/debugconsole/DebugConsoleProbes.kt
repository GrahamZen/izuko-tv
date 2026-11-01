/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.debugconsole

import io.ktor.client.plugins.expectSuccess
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.request
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.Url
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.him188.ani.app.domain.foundation.HttpClientProvider
import me.him188.ani.app.domain.foundation.ScopedHttpClientUserAgent
import me.him188.ani.app.domain.foundation.get
import me.him188.ani.app.domain.media.probe.MediaStreamProbe
import me.him188.ani.app.domain.media.resolver.EpisodeMetadata
import me.him188.ani.app.domain.media.resolver.MediaResolver
import me.him188.ani.app.domain.media.selector.MaybeExcludedMedia
import me.him188.ani.app.domain.media.selector.UnsafeOriginalMediaAccess
import me.him188.ani.app.domain.mediasource.web.APPLE_WEBVIEW_USER_AGENT
import me.him188.ani.app.domain.mediasource.web.captcha.CaptchaBrowserFactory
import me.him188.ani.app.domain.mediasource.web.captcha.InterceptDecision
import me.him188.ani.app.domain.usecase.GlobalKoin
import me.him188.ani.app.platform.PlaybackRequestHints
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.utils.ktor.rawCookieHeader
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import org.openani.mediamp.source.UriMediaData
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.TimeSource

/**
 * 调试接口里「在这台设备上发请求」的几个 (debug 包): 播放之前先在这台设备的网络与浏览器环境里看清数据源那边会发生什么.
 *
 * - `api/debug/http` ([http]): 用应用自己的网络栈发一个请求, 回状态码、回应头、正文开头、耗时
 * - `api/debug/webview` ([webview]): 用与过人机验证同一套的 WebView 打开一个页面, 回最终地址、标题、HTML 开头、Cookie 名字、像不像 Cloudflare 的拦截 / 验证页
 * - `api/debug/resolve` ([resolve]): 照播放的路子解析当前选源列表里的候选 (不播), 再像播放器那样要开头一段字节, 回解析器、直链域名、状态码、格式与码率
 * - `api/debug/candidates` ([candidates]): 一个源的全部候选, 含属于别的集的 (选源列表里不列), 带认出的集数与排除原因 —— 查「搜到了却没有这一集」
 *
 * 回应里不放 Cookie 与令牌的值; 地址只写到路径 (直链的参数里有签名).
 */
@OptIn(UnsafeOriginalMediaAccess::class, ExperimentalEncodingApi::class)
internal object DebugConsoleProbes {
    private val logger = logger<DebugConsoleProbes>()

    /**
     * `url` (http/https), `method` (默认 GET), `headers` (一行一个 `名字: 值`), `body` (文本), `max` (正文最多回多少字节, 默认 4096),
     * `client` (`browser` 默认 / `ani` / `none` = 不带 UA 与内容协商的那档), `bgmToken=1` 带上 Bangumi 令牌 (只发往 bgm 的域名).
     */
    suspend fun http(request: DebugConsoleRequest): JsonObject {
        val url = request.params["url"]?.trim().orEmpty()
        if (!url.startsWith("https://") && !url.startsWith("http://")) return failure("url must be http(s)")
        val method = HttpMethod.parse(request.params["method"]?.trim()?.uppercase()?.ifEmpty { null } ?: "GET")
        val headers = parseHeaders(request.params["headers"].orEmpty())
        val body = request.params["body"]
        val max = (request.int("max") ?: DEFAULT_BODY_BYTES).coerceIn(0, MAX_BODY_BYTES)
        val agent = when (request.params["client"]) {
            "ani" -> ScopedHttpClientUserAgent.ANI
            "none" -> ScopedHttpClientUserAgent.NONE
            else -> ScopedHttpClientUserAgent.BROWSER
        }
        val started = TimeSource.Monotonic.markNow()
        return try {
            GlobalKoin.get<HttpClientProvider>().get(agent, useBangumiToken = request.params["bgmToken"] == "1").use {
                prepareRequest(url) {
                    this.method = method
                    expectSuccess = false
                    applyHeaders(headers)
                    if (body != null) setBody(body)
                    timeout { requestTimeoutMillis = REQUEST_TIMEOUT_MILLIS }
                }.execute { response ->
                    val ttfb = started.elapsedNow().inWholeMilliseconds
                    val bytes = response.readUpTo(max)
                    logger.info { "[DebugProbe] ${method.value} ${linkForReport(url)} → ${response.status.value} in ${ttfb}ms" }
                    JsonObject(
                        mapOf(
                            "ok" to JsonPrimitive(true),
                            "status" to JsonPrimitive(response.status.value),
                            "finalUrl" to JsonPrimitive(linkForReport(response.request.url.toString())),
                            "ttfbMillis" to JsonPrimitive(ttfb),
                            "totalMillis" to JsonPrimitive(started.elapsedNow().inWholeMilliseconds),
                            "headers" to responseHeaders(response),
                        ) + bodyFields(bytes, response.headers[HttpHeaders.ContentType]),
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            failure(describe(e), "millis" to JsonPrimitive(started.elapsedNow().inWholeMilliseconds))
        }
    }

    /**
     * `url`, `ua` (`safari` 默认 / `native` = WKWebView 自带 / `mobile` / `chrome58` / 其他 = 原样当 UA), `wait` (等多久再读页面, 毫秒, 默认 6000),
     * `max` (HTML 最多回多少字符, 默认 4000), `clearCookies=1` 先清掉这个地址可见的 cookie 再打开 (浏览器的 cookie 整个 app 共用, 清的是全局的,
     * 之后抓视频与过人机验证都从头来).
     */
    suspend fun webview(request: DebugConsoleRequest): JsonObject {
        val url = request.params["url"]?.trim().orEmpty()
        if (!url.startsWith("https://") && !url.startsWith("http://")) return failure("url must be http(s)")
        val userAgent = userAgentOf(request.params["ua"]?.trim()?.ifEmpty { null } ?: "safari")
        val waitMillis = (request.int("wait") ?: DEFAULT_WEBVIEW_WAIT_MILLIS).coerceIn(500, MAX_WEBVIEW_WAIT_MILLIS).toLong()
        val maxChars = (request.int("max") ?: DEFAULT_HTML_CHARS).coerceIn(0, MAX_BODY_BYTES)
        val clearCookies = request.params["clearCookies"] == "1"
        val factory = GlobalKoin.get<CaptchaBrowserFactory>()
        if (!factory.isSupported) return failure("no browser on this platform")
        val browser = factory.create(userAgent)
        return try {
            val navigations = MutableStateFlow<List<String>>(emptyList())
            val loads = MutableStateFlow<List<String>>(emptyList())
            browser.setResourceInterceptor { resource ->
                navigations.update { it + linkForReport(resource) }
                InterceptDecision.Continue
            }
            if (clearCookies) browser.clearCookies(listOf(url))
            coroutineScope {
                val collector = launch { browser.pageLoads.collect { page -> loads.update { it + linkForReport(page.finalUrl) } } }
                browser.navigate(url)
                delay(waitMillis)
                collector.cancel()
            }
            val page = browser.currentPage()
            val html = page?.html.orEmpty()
            val cookies = browser.collectCookies(listOfNotNull(url, page?.finalUrl).distinct()).map { it.name }.distinct()
            logger.info { "[DebugProbe] webview ${linkForReport(url)}${if (clearCookies) " (cookies cleared)" else ""} → ${page?.finalUrl?.let(::linkForReport)}, ${cloudflareVerdict(html)}" }
            buildJsonObject {
                put("ok", true)
                put("userAgent", userAgent.ifEmpty { "(WKWebView 自带)" })
                put("clearedCookies", clearCookies)
                put("finalUrl", page?.finalUrl?.let(::linkForReport))
                put("title", TITLE.find(html)?.groupValues?.get(1)?.trim())
                put("cloudflare", cloudflareVerdict(html))
                put("htmlLength", html.length)
                put("html", html.take(maxChars))
                put("cookies", JsonArray(cookies.map(::JsonPrimitive)))
                put("navigations", JsonArray(navigations.value.map(::JsonPrimitive)))
                put("pageLoads", JsonArray(loads.value.map(::JsonPrimitive)))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            failure(describe(e))
        } finally {
            browser.close()
        }
    }

    /**
     * `source` (数据源名字或 id) 的全部候选 (最多 `limit` 条, 默认 60): 标题、线路、认出的集数、排除原因、条目名与集数怎么对上的.
     */
    suspend fun candidates(request: DebugConsoleRequest): JsonObject {
        val vm = DebugConsolePlayer.vm ?: return failure("no playback session")
        val page = DebugConsolePlayer.page(vm) ?: return failure("player page state not ready")
        val source = request.params["source"]?.trim().orEmpty()
        if (source.isEmpty()) return failure("give source")
        val sourceIds = page.mediaSourceResultListPresentation.list
            .filter { it.mediaSourceId == source || it.info.displayName == source }
            .map { it.mediaSourceId }.toSet() + source
        val limit = (request.int("limit") ?: 60).coerceIn(1, 500)
        val all = page.mediaSelectorState.allCandidates.first().filter { it.original.mediaSourceId in sourceIds }
        val ep = page.episodePresentation
        return buildJsonObject {
            put("ok", true)
            put("episode", listOf(ep.sort, ep.title).filter { it.isNotBlank() }.joinToString("  "))
            put("total", all.size)
            putJsonArray("items") {
                for (entry in all.take(limit)) addJsonObject {
                    val media = entry.original
                    put("title", media.originalTitle)
                    put("subjectName", media.properties.subjectName)
                    put("episodeName", media.properties.episodeName)
                    put("alliance", media.properties.alliance)
                    put("episodes", media.episodeRange?.knownSorts?.joinToString(",") { it.toString() })
                    entry.exclusionReason?.let { put("excluded", it.toString()) }
                    (entry as? MaybeExcludedMedia.Included)?.metadata?.let {
                        put("subjectMatch", it.subjectMatchKind.name)
                        put("episodeMatch", it.episodeMatchKind.name)
                        put("similarity", it.similarity)
                    }
                }
            }
        }
    }

    /**
     * 当前播放会话里的候选: `id` (候选的 id, 见 `api/player`) 或 `source` (数据源名字或 id) + `limit` (默认 3, 放行的在前).
     * `drive=1` 才试网盘分享里的文件 (解析时会先转存进自己的网盘); BT 不试. `timeout` 每个候选最多多久 (毫秒, 默认 40000).
     */
    suspend fun resolve(request: DebugConsoleRequest): JsonObject {
        val vm = DebugConsolePlayer.vm ?: return failure("no playback session")
        val page = DebugConsolePlayer.page(vm) ?: return failure("player page state not ready")
        val candidates = DebugConsolePlayer.presentation(page).filteredCandidates
        val id = request.params["id"]
        val source = request.params["source"]
        val limit = (request.int("limit") ?: 3).coerceIn(1, MAX_RESOLVE)
        val picked = when {
            id != null -> candidates.filter { it.original.mediaId == id }
            source != null -> {
                val sourceIds = page.mediaSourceResultListPresentation.list
                    .filter { it.mediaSourceId == source || it.info.displayName == source }
                    .map { it.mediaSourceId }.toSet()
                candidates.filter { it.original.mediaSourceId in sourceIds || it.original.mediaSourceId == source }
                    .sortedBy { if (it is MaybeExcludedMedia.Included) 0 else 1 }
                    .take(limit)
            }

            else -> return failure("give id or source")
        }
        if (picked.isEmpty()) return failure("no candidate matched")
        val ep = page.episodePresentation
        val episode = EpisodeMetadata(ep.title, null, EpisodeSort(ep.sort))
        val timeoutMillis = (request.int("timeout") ?: DEFAULT_RESOLVE_TIMEOUT_MILLIS).coerceIn(1_000, MAX_RESOLVE_TIMEOUT_MILLIS).toLong()
        val includeDrive = request.params["drive"] == "1"
        val results = picked.map { resolveOne(it, episode, includeDrive, timeoutMillis) }
        return JsonObject(
            mapOf(
                "ok" to JsonPrimitive(true),
                "episode" to JsonPrimitive(listOf(ep.sort, ep.title).filter { it.isNotBlank() }.joinToString("  ")),
                "results" to JsonArray(results),
            ),
        )
    }

    private suspend fun resolveOne(entry: MaybeExcludedMedia, episode: EpisodeMetadata, includeDrive: Boolean, timeoutMillis: Long): JsonObject {
        val media = entry.original
        val out = linkedMapOf<String, JsonElement>(
            "id" to JsonPrimitive(media.mediaId),
            "source" to JsonPrimitive(media.mediaSourceId),
            "title" to JsonPrimitive(media.originalTitle),
            "kind" to JsonPrimitive(media.kind.name),
        )
        entry.exclusionReason?.let { out["excluded"] = JsonPrimitive(it.toString()) }
        skipReason(media, includeDrive)?.let {
            out["skipped"] = JsonPrimitive(it)
            return JsonObject(out)
        }
        val started = TimeSource.Monotonic.markNow()
        try {
            withTimeout(timeoutMillis) {
                coroutineScope {
                    val provider = GlobalKoin.get<MediaResolver>().resolve(media, episode)
                    val data = provider.open(this)
                    out["resolveMillis"] = JsonPrimitive(started.elapsedNow().inWholeMilliseconds)
                    out["data"] = JsonPrimitive(data::class.simpleName)
                    if (data is UriMediaData) {
                        out["uri"] = JsonPrimitive(linkForReport(data.uri))
                        out["headers"] = JsonArray(data.headers.keys.map(::JsonPrimitive))
                        data.headers[PlaybackRequestHints.PARALLEL_RANGE_HEADER]?.let { out["parallelConnections"] = JsonPrimitive(it) }
                        out["probe"] = probe(data)
                    }
                    coroutineContext.cancelChildren()
                }
            }
        } catch (e: TimeoutCancellationException) {
            out["error"] = JsonPrimitive("timeout after ${timeoutMillis}ms")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            out["error"] = JsonPrimitive(describe(e))
        }
        out["totalMillis"] = JsonPrimitive(started.elapsedNow().inWholeMilliseconds)
        logger.info {
            "[DebugProbe] resolve ${media.mediaSourceId} ${media.originalTitle.take(40)}: ${out["uri"] ?: out["skipped"] ?: ""} " +
                "${(out["probe"] as? JsonObject)?.get("status") ?: ""} ${out["error"] ?: ""} in ${out["totalMillis"]}ms"
        }
        return JsonObject(out)
    }

    private fun skipReason(media: Media, includeDrive: Boolean): String? = when {
        media.kind == MediaSourceKind.BitTorrent -> "BT 不试 (打开会开始下载)"
        media.kind == MediaSourceKind.LocalCache -> "本地缓存不试"
        DRIVE_SHARE_MARKER in media.download.uri && !includeDrive -> "网盘分享里的文件: 解析会先转存进自己的网盘, 加 drive=1 才试"
        else -> null
    }

    /** 像播放器那样要开头一段, 再读出格式、分辨率、编码与码率 ([MediaStreamProbe]). Cookie 原样发 (同播放). */
    private suspend fun probe(data: UriMediaData): JsonObject {
        val headers = PlaybackRequestHints.strip(data.headers)
        val out = linkedMapOf<String, JsonElement>()
        val started = TimeSource.Monotonic.markNow()
        try {
            GlobalKoin.get<HttpClientProvider>().get(ScopedHttpClientUserAgent.NONE).use {
                prepareGet(data.uri) {
                    expectSuccess = false
                    applyHeaders(headers.toList())
                    header(HttpHeaders.Range, "bytes=0-${PROBE_HEAD_BYTES - 1}")
                    timeout { requestTimeoutMillis = REQUEST_TIMEOUT_MILLIS }
                }.execute { response ->
                    out["status"] = JsonPrimitive(response.status.value)
                    out["ttfbMillis"] = JsonPrimitive(started.elapsedNow().inWholeMilliseconds)
                    response.headers[HttpHeaders.ContentType]?.let { out["contentType"] = JsonPrimitive(it) }
                    response.headers[HttpHeaders.ContentRange]?.let { out["contentRange"] = JsonPrimitive(it) }
                    response.headers[HttpHeaders.ContentLength]?.let { out["contentLength"] = JsonPrimitive(it) }
                    out["head"] = JsonPrimitive(response.readUpTo(PROBE_HEAD_BYTES).toHex())
                }
                MediaStreamProbe.probe(this, data.uri, headers)?.let { result ->
                    out["container"] = JsonPrimitive(result.container)
                    result.width?.let { out["width"] = JsonPrimitive(it) }
                    result.height?.let { out["height"] = JsonPrimitive(it) }
                    result.codec?.let { out["codec"] = JsonPrimitive(it) }
                    result.kbps?.let { out["kbps"] = JsonPrimitive(it) }
                    result.durationSeconds?.let { out["durationSeconds"] = JsonPrimitive(it) }
                    result.dynamicRange?.let { out["dynamicRange"] = JsonPrimitive(it.name) }
                    result.audio?.let { out["audio"] = JsonPrimitive(it.name) }
                    // AVPlayer 只认 MP4 / MOV 与 HLS
                    out["avPlayerPlayable"] = JsonPrimitive(result.container == "mp4" || result.container == "hls")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            out["error"] = JsonPrimitive(describe(e))
        }
        return JsonObject(out)
    }

    private fun HttpRequestBuilder.applyHeaders(headers: List<Pair<String, String>>) {
        for ((name, value) in headers) {
            // 客户端的 Cookie 插件会把手写的 Cookie 重新编码, 原样发 (同播放与缓存下载)
            if (name.equals(HttpHeaders.Cookie, ignoreCase = true)) rawCookieHeader(value) else header(name, value)
        }
    }

    private fun parseHeaders(text: String): List<Pair<String, String>> =
        text.lines().mapNotNull { line ->
            val colon = line.indexOf(':')
            if (colon <= 0) null else line.substring(0, colon).trim() to line.substring(colon + 1).trim()
        }

    /** 回应头; Set-Cookie 只回名字. */
    private fun responseHeaders(response: HttpResponse): JsonObject = JsonObject(
        response.headers.entries().associate { (name, values) ->
            name to JsonPrimitive(
                if (name.equals(HttpHeaders.SetCookie, ignoreCase = true)) {
                    values.joinToString(", ") { it.substringBefore('=') + "=…" }
                } else {
                    values.joinToString(", ")
                },
            )
        },
    )

    /** 正文开头: 像文本就回文本, 否则 base64. */
    private fun bodyFields(bytes: ByteArray, contentType: String?): Map<String, JsonElement> {
        val type = contentType?.lowercase().orEmpty()
        val textual = type.startsWith("text/") || "json" in type || "xml" in type || "javascript" in type || "mpegurl" in type ||
            (type.isEmpty() && bytes.take(64).all { it >= 9 })
        return mapOf(
            "bytes" to JsonPrimitive(bytes.size),
            if (textual) "text" to JsonPrimitive(bytes.decodeToString()) else "base64" to JsonPrimitive(Base64.encode(bytes)),
        )
    }

    private suspend fun HttpResponse.readUpTo(max: Int): ByteArray {
        if (max <= 0) return ByteArray(0)
        val channel = bodyAsChannel()
        val buffer = ByteArray(max)
        var length = 0
        while (length < max) {
            val read = channel.readAvailable(buffer, length, max - length)
            if (read < 0) break
            length += read
        }
        return buffer.copyOf(length)
    }

    private fun ByteArray.toHex(): String = joinToString(" ") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    /** 网页探测 `ua` 参数的选项对应的 UA; 不是选项名的原样当 UA, 空字符串 = 不设 (WKWebView 自带的). */
    private fun userAgentOf(choice: String): String = when (choice) {
        "safari" -> APPLE_WEBVIEW_USER_AGENT
        "native" -> ""
        "mobile" ->
            "Mozilla/5.0 (iPad; CPU OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1"

        "chrome58" ->
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/58.0.3029.110 Safari/537.3"

        else -> choice
    }

    /** Cloudflare 的拦截页 (防火墙直接拒) 与验证页 (要跑脚本 / 点一下) 长得不一样, 分开报. */
    private fun cloudflareVerdict(html: String): String = when {
        "cf-error-details" in html || "cf.errors.css" in html || "Sorry, you have been blocked" in html -> "拦截页"
        "challenge-platform" in html && ("Just a moment" in html || "cf-chl" in html || "turnstile" in html) -> "验证页"
        else -> "无"
    }

    /** 回给开发机的地址: 只到路径, 参数换成 `?…` (直链的参数里有签名). */
    private fun linkForReport(url: String): String {
        val parsed = runCatching { Url(url) }.getOrNull() ?: return url.substringBefore('?')
        val query = if (parsed.encodedQuery.isNotEmpty()) "?…" else ""
        return "${parsed.protocol.name}://${parsed.host}${parsed.encodedPath}$query"
    }

    /** 异常的说明, 里面的地址只留域名 (ktor 的异常信息带着完整的请求地址). */
    private fun describe(e: Throwable): String {
        val message = e.message?.let { URL_IN_MESSAGE.replace(it) { match -> match.groupValues[1] + "/…" } }?.take(500)
        return "${e::class.simpleName}: $message"
    }

    private fun failure(message: String, vararg extra: Pair<String, JsonElement>): JsonObject =
        JsonObject(mapOf("ok" to JsonPrimitive(false), "message" to JsonPrimitive(message)) + extra)

    private val TITLE = Regex("""<title[^>]*>([\s\S]*?)</title>""", RegexOption.IGNORE_CASE)
    private val URL_IN_MESSAGE = Regex("""(https?://[^/\s"'?#]+)[^\s"']*""")

    /** 网盘分享里的文件的占位地址带的片段 (见 DrivePlaceholders.shareFileUri). */
    private const val DRIVE_SHARE_MARKER = "#izuko-share"

    private const val DEFAULT_BODY_BYTES = 4096
    private const val MAX_BODY_BYTES = 256 * 1024
    private const val DEFAULT_HTML_CHARS = 4000
    private const val DEFAULT_WEBVIEW_WAIT_MILLIS = 6000
    private const val MAX_WEBVIEW_WAIT_MILLIS = 30_000
    private const val DEFAULT_RESOLVE_TIMEOUT_MILLIS = 40_000
    private const val MAX_RESOLVE_TIMEOUT_MILLIS = 120_000
    private const val MAX_RESOLVE = 10
    private const val PROBE_HEAD_BYTES = 64 * 1024
    private const val REQUEST_TIMEOUT_MILLIS = 30_000L
}
