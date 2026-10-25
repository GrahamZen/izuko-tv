/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.range

import io.ktor.client.HttpClient
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import me.him188.ani.app.domain.foundation.HttpClientProvider
import me.him188.ani.app.domain.foundation.ScopedHttpClientUserAgent
import me.him188.ani.app.domain.foundation.get
import me.him188.ani.app.domain.media.hls.HlsPlaybackPreparerResult
import me.him188.ani.app.domain.media.hls.HlsPlaybackProxySession
import me.him188.ani.app.domain.media.hls.HlsProxyRequest
import me.him188.ani.app.domain.media.hls.HlsProxyResponseSink
import me.him188.ani.app.domain.media.hls.HlsProxyServer
import me.him188.ani.app.domain.media.hls.HlsProxyServerFactory
import me.him188.ani.app.domain.media.hls.PlatformHlsProxyServerFactory
import me.him188.ani.app.platform.PlaybackRequestHints
import me.him188.ani.utils.coroutines.IO_
import me.him188.ani.utils.ktor.ScopedHttpClient
import me.him188.ani.utils.ktor.UnsafeScopedHttpClientApi
import me.him188.ani.utils.ktor.rawCookieHeader
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import me.him188.ani.utils.platform.currentPlatform
import me.him188.ani.utils.platform.isIos
import org.openani.mediamp.source.MediaData
import org.openani.mediamp.source.UriMediaData
import kotlin.time.TimeSource

/*
 * 网盘原文件在 iOS 上的多连接播放. 有的网盘直链每个连接限速, 一个连接跟不上视频码率; 解析器在请求头里放了「按几个连接并发分块」的提示
 * ([PlaybackRequestHints.PARALLEL_RANGE_HEADER]). 安卓的播放器自己读它 (ParallelRangeDataSource); iOS 的 AVPlayer 只会单连接去取,
 * 这里改成经本机代理: 播放器向代理要一段字节, 代理把这一段拆成块, 分给几条通道同时向网盘拉, 拉到的按顺序送出.
 *
 * 每条通道是一个独立的 HTTP 客户端. iOS 的网络库 (NSURLSession) 碰上 HTTP/2 会把同一个会话里的请求挤进一条连接, 而网盘按连接限速,
 * 又没有公开的开关能关掉 HTTP/2; 每个客户端一个独立的会话, 也就各自一条连接 (安卓那边直接只走 HTTP/1.1).
 */

/**
 * 把带并发提示的地址经本机代理交给播放器 (见上). 只在 [enabled] (iOS) 时做, 不带提示、不是 http(s)、网盘不认范围请求时返回 null,
 * 照旧直接交给播放器. 返回的会话在停播时关掉 (同 HLS 本地代理).
 */
class ParallelRangePlayback internal constructor(
    private val httpClientProvider: HttpClientProvider,
    private val serverFactory: HlsProxyServerFactory,
    private val enabled: Boolean,
) {
    constructor(httpClientProvider: HttpClientProvider) : this(httpClientProvider, PlatformHlsProxyServerFactory, currentPlatform().isIos())

    suspend fun prepare(data: MediaData): HlsPlaybackPreparerResult? {
        if (!enabled || data !is UriMediaData) return null
        val connections = data.headers[PlaybackRequestHints.PARALLEL_RANGE_HEADER]?.toIntOrNull()?.takeIf { it > 1 } ?: return null
        if (!data.uri.startsWith("https://") && !data.uri.startsWith("http://")) return null
        logger.info { "[RangeProxy] 带并发提示 ($connections 路), 经本机代理交给播放器" }
        val session = withContext(Dispatchers.IO_) {
            ParallelRangeProxySession.createOrNull(
                url = data.uri,
                headers = PlaybackRequestHints.strip(data.headers),
                connections = connections.coerceAtMost(MAX_CONNECTIONS),
                httpClientProvider = httpClientProvider,
                serverFactory = serverFactory,
            )
        } ?: return null
        // 请求头由代理带着去取, 播放器这边不带
        return HlsPlaybackPreparerResult(UriMediaData(session.uri, emptyMap(), data.extraFiles), session)
    }

    private companion object {
        private val logger = logger<ParallelRangePlayback>()

        /** 解析器给的路数再多也只开这么多条通道 (同安卓多开时的上限). */
        const val MAX_CONNECTIONS = 48
    }
}

/**
 * 一次播放的本机代理: 只监听 127.0.0.1, 答播放器的范围请求 (见 [handle]). [totalLength] / [contentType] 由创建时的探测得到.
 */
internal class ParallelRangeProxySession private constructor(
    private val url: String,
    private val headers: Map<String, String>,
    private val connections: Int,
    private val lanes: Lanes,
    private val server: HlsProxyServer,
    val totalLength: Long,
    private val contentType: String,
    extension: String,
) : HlsPlaybackProxySession {
    private val id = nextId.incrementAndGet()
    private val host = runCatching { Url(url).host }.getOrDefault("?")
    private val chunkSize = chunkSizeFor(connections)
    private val firstChunkSize = firstChunkSizeFor(connections, chunkSize)
    private val requestCount = atomic(0)

    /** 各通道实际拿到几个不同的客户端 (应当等于 [connections]). */
    internal val laneClients: Int get() = lanes.distinctClients

    /** 交给播放器的地址: 后缀照文件类型, 播放器据此认格式. */
    val uri: String = "http://127.0.0.1:${server.port}/media.$extension"

    init {
        logger.info {
            "[RangeProxy] #$id 经本机代理多连接播放: $host, 共 ${totalLength.mib()} MiB, $contentType, " +
                "$connections 条通道 (实际 ${lanes.distinctClients} 个客户端), 每块 ${firstChunkSize / 1024} KiB 起、最大 ${chunkSize / 1024} KiB"
        }
        server.start { request, sink -> handle(request, sink) }
    }

    /** 播放器的一个请求: 照 Range 回 206 (没有 Range 回 200 整个文件), 正文边拉边送. */
    private suspend fun handle(request: HlsProxyRequest, sink: HlsProxyResponseSink) {
        val n = requestCount.incrementAndGet()
        val rangeHeader = request.headers["range"]
        val range = parseByteRange(rangeHeader, totalLength)
        if (range == null) {
            logger.warn { "[RangeProxy] #$id 第 $n 个请求的范围不对: $rangeHeader (共 $totalLength)" }
            sink.write(
                "HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$totalLength\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                    .encodeToByteArray(),
            )
            return
        }
        val length = range.last - range.first + 1
        logger.info { "[RangeProxy] #$id 第 $n 个请求: Range ${rangeHeader ?: "(无)"} → 回 ${range.first}-${range.last} (${length.mib()} MiB)" }
        val head = buildString {
            if (rangeHeader != null) {
                append("HTTP/1.1 206 Partial Content\r\n")
                append("Content-Range: bytes ${range.first}-${range.last}/$totalLength\r\n")
            } else {
                append("HTTP/1.1 200 OK\r\n")
            }
            append("Content-Type: $contentType\r\n")
            append("Content-Length: $length\r\n")
            append("Accept-Ranges: bytes\r\n")
            append("Connection: close\r\n\r\n")
        }
        sink.write(head.encodeToByteArray())
        stream(n, range, sink)
    }

    /**
     * 把 [range] 拆成块 ([planChunks]) 分给各通道同时拉, 按顺序送给播放器. 同时在拉与拉好没送的块最多 [connections] 个 (内存不超过路数 × 最大块).
     * 播放器断开 (跳转、换源) 时写入抛异常, 没送的块随之取消, 通道空出来给新的请求.
     */
    private suspend fun stream(n: Int, range: LongRange, sink: HlsProxyResponseSink) {
        val started = TimeSource.Monotonic.markNow()
        val progress = StreamProgress(n)
        var firstByteMillis = -1L
        try {
            coroutineScope {
                val permits = Semaphore(connections)
                val pending = Channel<Deferred<ByteArray>>(Channel.UNLIMITED)
                launch {
                    for (part in planChunks(range.first, range.last, connections, firstChunkSize, chunkSize)) {
                        permits.acquire()
                        pending.send(async { fetch(part) })
                    }
                    pending.close()
                }
                for (chunk in pending) {
                    // 卡的时候分得清是网盘慢 (等块) 还是播放器缓冲够了暂时不读 (等写入)
                    val waitedNetwork = TimeSource.Monotonic.markNow()
                    val bytes = chunk.await()
                    progress.networkMillis += waitedNetwork.elapsedNow().inWholeMilliseconds
                    val waitedPlayer = TimeSource.Monotonic.markNow()
                    sink.write(bytes)
                    progress.playerMillis += waitedPlayer.elapsedNow().inWholeMilliseconds
                    if (firstByteMillis < 0) firstByteMillis = started.elapsedNow().inWholeMilliseconds
                    progress.sent += bytes.size
                    permits.release()
                    progress.maybeLog(inFlight = connections - permits.availablePermits)
                }
            }
            val millis = started.elapsedNow().inWholeMilliseconds
            logger.info {
                "[RangeProxy] #$id 第 $n 个请求送完 ${range.first}-${range.last}: ${progress.sent.mib()} MiB, 首块 ${firstByteMillis}ms, " +
                    "用时 ${millis}ms, 平均 ${progress.sent * 1000 / millis.coerceAtLeast(1) / 1024} KiB/s, " +
                    "等网盘 ${progress.networkMillis}ms、等播放器 ${progress.playerMillis}ms"
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.info {
                "[RangeProxy] #$id 第 $n 个请求 ${range.first}-${range.last} 停在已送 ${progress.sent.mib()} MiB (首块 ${firstByteMillis}ms, " +
                    "${started.elapsedNow().inWholeMilliseconds}ms, 等网盘 ${progress.networkMillis}ms、等播放器 ${progress.playerMillis}ms): " +
                    describe(e)
            }
            throw e
        }
    }

    /** 一个请求送了多少、等网盘与等播放器各多久; 长请求 (边放边拉) 每 [PROGRESS_LOG_MILLIS] 记一次这一段的速度. */
    private inner class StreamProgress(private val n: Int) {
        var sent = 0L
        var networkMillis = 0L
        var playerMillis = 0L
        private var lastLog = TimeSource.Monotonic.markNow()
        private var lastSent = 0L
        private var lastNetworkMillis = 0L
        private var lastPlayerMillis = 0L

        fun maybeLog(inFlight: Int) {
            val interval = lastLog.elapsedNow().inWholeMilliseconds
            if (interval < PROGRESS_LOG_MILLIS) return
            val bytes = sent - lastSent
            logger.info {
                "[RangeProxy] #$id 第 $n 个请求进行中: 已送 ${sent.mib()} MiB, 这 ${interval / 1000} 秒 ${bytes * 1000 / interval / 1024} KiB/s, " +
                    "等网盘 ${networkMillis - lastNetworkMillis}ms、等播放器 ${playerMillis - lastPlayerMillis}ms, 在拉 $inFlight 块"
            }
            lastLog = TimeSource.Monotonic.markNow()
            lastSent = sent
            lastNetworkMillis = networkMillis
            lastPlayerMillis = playerMillis
        }
    }

    /** 拉一块: 借一条空着的通道, 网络出错重试 [MAX_RETRIES] 次 (网盘回 4xx 不重试, 直链多半过期了). */
    private suspend fun fetch(part: LongRange): ByteArray {
        var attempt = 0
        while (true) {
            val client = lanes.take()
            val started = TimeSource.Monotonic.markNow()
            try {
                val bytes = client.fetchRange(url, headers, part)
                val millis = started.elapsedNow().inWholeMilliseconds
                if (millis >= SLOW_CHUNK_LOG_MILLIS) logger.info { "[RangeProxy] #$id 块 ${part.first}-${part.last} 拉了 ${millis}ms" }
                return bytes
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val status = (e as? ResponseException)?.response?.status?.value
                if (attempt >= MAX_RETRIES || status in 400..499) {
                    logger.warn { "[RangeProxy] #$id 块 ${part.first}-${part.last} 拉不下来 (试了 ${attempt + 1} 次): ${describe(e)}" }
                    throw e
                }
                attempt++
                logger.info { "[RangeProxy] #$id 块 ${part.first}-${part.last} 第 $attempt 次重试: ${describe(e)}" }
            } finally {
                lanes.give(client)
            }
            delay(RETRY_DELAY_MILLIS * attempt)
        }
    }

    override fun close() {
        logger.info { "[RangeProxy] #$id 关闭 (共答了 ${requestCount.value} 个请求)" }
        server.close()
        lanes.close()
    }

    companion object {
        private val logger = logger<ParallelRangeProxySession>()
        private val nextId = atomic(0)

        /** 一块最大多大: 同安卓 (ParallelRangeDataSource.chunkSizeFor), 所有通道手上的块合计约 16 MiB, 非会员 16 路每块 1 MiB. */
        private const val WINDOW_BYTES = 16L * 1024 * 1024
        private const val MIN_CHUNK_SIZE = 1024L * 1024
        private const val MAX_CHUNK_SIZE = 4L * 1024 * 1024

        /** 开播 / 跳转后头一轮每块: 最大块按路数平分, 不超过最大块的 1/4, 不小于 32 KiB (同安卓 firstChunkSizeFor). */
        private const val FIRST_CHUNK_DIVISOR = 4
        private const val MIN_FIRST_CHUNK_SIZE = 32L * 1024

        /** 通道客户端的「每个主机的并发上限」从这里起编号 (只用来让各通道拿到不同的客户端, 见 [createOrNull]). */
        private const val LANE_CLIENT_KEY_BASE = 4096

        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MILLIS = 300L
        private const val SLOW_CHUNK_LOG_MILLIS = 10_000L
        private const val PROGRESS_LOG_MILLIS = 15_000L

        fun chunkSizeFor(connections: Int): Long = (WINDOW_BYTES / connections.coerceAtLeast(1)).coerceIn(MIN_CHUNK_SIZE, MAX_CHUNK_SIZE)

        fun firstChunkSizeFor(connections: Int, chunkSize: Long): Long =
            (chunkSize / connections.coerceAtLeast(1)).coerceAtLeast(MIN_FIRST_CHUNK_SIZE).coerceAtMost(chunkSize / FIRST_CHUNK_DIVISOR)

        /**
         * 建一个会话: 先借好各通道, 用第一条通道探一次 (要开头 16 个字节: 拿到总长、类型, 也认出文件格式). 网盘不认范围请求或探测失败时返回 null.
         */
        suspend fun createOrNull(
            url: String,
            headers: Map<String, String>,
            connections: Int,
            httpClientProvider: HttpClientProvider,
            serverFactory: HlsProxyServerFactory,
        ): ParallelRangeProxySession? {
            // 每条通道要一个独立的客户端: 客户端池按功能配置分格子, 配置一样就共用一个 (iOS 上就是共用一个会话、一条连接).
            // 「每个主机的并发上限」在 iOS 上不起作用、在别处远大于实际用量, 拿它给每条通道一个不同的值, 各落一格.
            // 用不带 UA 与内容协商的那一档 (NONE): 请求头只发解析器给的 (Cookie、网盘指定的 UA), 见 resolverHeaders
            val lanes = Lanes(
                List(connections) { lane ->
                    httpClientProvider.get(ScopedHttpClientUserAgent.NONE, maxRequestsPerHost = LANE_CLIENT_KEY_BASE + lane)
                },
            )
            var server: HlsProxyServer? = null
            var created = false
            try {
                val started = TimeSource.Monotonic.markNow()
                val probe = lanes.probe(url, headers) ?: return null
                logger.info { "[RangeProxy] 探测 ${probe.totalLength} 字节、${probe.upstreamType} (${started.elapsedNow().inWholeMilliseconds}ms)" }
                server = serverFactory.create()
                val format = MediaFormat.sniff(probe.head, url, probe.upstreamType)
                return ParallelRangeProxySession(url, headers, connections, lanes, server, probe.totalLength, format.mimeType, format.extension)
                    .also { created = true }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logger.warn { "[RangeProxy] 建不了本机代理, 照旧直接交给播放器: ${describe(e)}" }
                return null
            } finally {
                if (!created) {
                    server?.close()
                    lanes.close()
                }
            }
        }
    }
}

/**
 * 各条通道: 每条一个借来的独立客户端, 一次只拉一块. 整次播放一直借着 (还回去客户端就关了, 连接跟着断, 下一块又要重新握手).
 */
@OptIn(UnsafeScopedHttpClientApi::class)
private class Lanes(private val scoped: List<ScopedHttpClient>) : AutoCloseable {
    private val tickets = scoped.map { it.borrow() }
    private val idle = Channel<HttpClient>(tickets.size).apply { tickets.forEach { trySend(it.client) } }
    private val closed = atomic(false)

    /** 实际有几个不同的客户端 (日志里核对: 应当等于通道数, 不然几条通道共用一条连接). */
    val distinctClients: Int = tickets.map { it.client }.distinct().size

    suspend fun take(): HttpClient = idle.receive()

    fun give(client: HttpClient) {
        idle.trySend(client)
    }

    /** 要开头 [PROBE_BYTES] 个字节; 回的不是 206 (网盘不认范围请求) 时返回 null. */
    suspend fun probe(url: String, headers: Map<String, String>): Probe? {
        val client = take()
        try {
            // 流式取: 不认范围的网盘会回整个文件, 只看状态码不读正文
            return client.prepareGet(url) {
                resolverHeaders(headers)
                header(HttpHeaders.Range, "bytes=0-${PROBE_BYTES - 1}")
                chunkTimeouts()
            }.execute { response ->
                if (response.status != HttpStatusCode.PartialContent) {
                    logger.info { "[RangeProxy] 网盘对范围请求回了 ${response.status.value} (不认范围), 不走代理" }
                    return@execute null
                }
                val total = response.headers[HttpHeaders.ContentRange]?.substringAfterLast('/')?.toLongOrNull()
                if (total == null) {
                    logger.info { "[RangeProxy] 网盘回的 Content-Range 看不出总长 (${response.headers[HttpHeaders.ContentRange]}), 不走代理" }
                    return@execute null
                }
                Probe(total, response.headers[HttpHeaders.ContentType], response.readRawBytes())
            }
        } finally {
            give(client)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(expect = false, update = true)) return
        idle.close()
        scoped.zip(tickets).forEach { (client, ticket) -> client.returnClient(ticket) }
    }

    class Probe(val totalLength: Long, val upstreamType: String?, val head: ByteArray)

    private companion object {
        private val logger = logger<Lanes>()
        const val PROBE_BYTES = 16
    }
}

/** 要 [part] 这一段: 必须回 206 且长度对得上. 流式取, 网盘没按范围回 (整个文件) 时不读正文. */
private suspend fun HttpClient.fetchRange(url: String, headers: Map<String, String>, part: LongRange): ByteArray {
    val expected = part.last - part.first + 1
    return prepareGet(url) {
        resolverHeaders(headers)
        header(HttpHeaders.Range, "bytes=${part.first}-${part.last}")
        chunkTimeouts()
    }.execute { response ->
        check(response.status == HttpStatusCode.PartialContent) { "网盘没按范围回: ${response.status}" }
        val bytes = response.readRawBytes()
        check(bytes.size.toLong() == expected) { "只回了 ${bytes.size} / $expected 字节" }
        bytes
    }
}

/**
 * 带上解析器给的请求头. Cookie 原样发 ([rawCookieHeader]): 客户端的 Cookie 插件会把它重新编码一遍, 网盘登录 Cookie 里的 base64 与百分号编码的值
 * 就对不上账号, 下载节点回 412.
 */
private fun HttpRequestBuilder.resolverHeaders(headers: Map<String, String>) {
    headers.forEach { (name, value) ->
        if (name.equals(HttpHeaders.Cookie, ignoreCase = true)) rawCookieHeader(value) else header(name, value)
    }
}

/** 一块的超时: 建连与两次读之间都不该太久 (连不上的节点早点放弃, 换一次重试), 整块给足时间. */
private fun HttpRequestBuilder.chunkTimeouts() {
    timeout {
        connectTimeoutMillis = 5_000
        socketTimeoutMillis = 15_000
        requestTimeoutMillis = 120_000
    }
}

/**
 * 从 [start] 到 [endInclusive] 怎么拆块: 头一轮 ([connections] 块) 每块 [firstChunkSize], 之后每轮翻倍, 到 [chunkSize] 为止 (同安卓 ParallelRangeReader).
 * 跳到没下过的位置要等多久, 除了首字节几乎就看头一块多大; 后面的块大, 请求少.
 */
internal fun planChunks(start: Long, endInclusive: Long, connections: Int, firstChunkSize: Long, chunkSize: Long): Sequence<LongRange> = sequence {
    var position = start
    var index = 0
    while (position <= endInclusive) {
        val round = (index / connections.coerceAtLeast(1)).coerceAtMost(MAX_RAMP_ROUNDS)
        val size = minOf(chunkSize, firstChunkSize shl round)
        val last = minOf(endInclusive, position + size - 1)
        yield(position..last)
        position = last + 1
        index++
    }
}

private const val MAX_RAMP_ROUNDS = 16

/**
 * 解析 Range 请求头 (只认一段): `bytes=a-b`、`bytes=a-`、`bytes=-n` (最后 n 个字节). 没有 Range 时是整个文件; 范围不对 (超出文件) 时返回 null.
 */
internal fun parseByteRange(header: String?, totalLength: Long): LongRange? {
    if (totalLength <= 0) return null
    if (header == null) return 0L..<totalLength
    val spec = header.trim().removePrefix("bytes=").substringBefore(',').trim()
    val dash = spec.indexOf('-')
    if (!header.trim().startsWith("bytes=") || dash < 0) return null
    val from = spec.substring(0, dash).trim()
    val to = spec.substring(dash + 1).trim()
    return when {
        from.isEmpty() -> {
            val suffix = to.toLongOrNull()?.takeIf { it > 0 } ?: return null
            maxOf(0L, totalLength - suffix)..<totalLength
        }

        else -> {
            val first = from.toLongOrNull() ?: return null
            if (first >= totalLength) return null
            val last = if (to.isEmpty()) totalLength - 1 else (to.toLongOrNull() ?: return null).coerceAtMost(totalLength - 1)
            if (last < first) null else first..last
        }
    }
}

/** 交给播放器的类型与后缀: 先看文件开头 (认得出的几种容器), 再看网盘给的类型与地址的后缀, 都不认识按 mp4. */
internal class MediaFormat(val mimeType: String, val extension: String) {
    companion object {
        fun sniff(head: ByteArray, url: String, upstreamType: String?): MediaFormat {
            fun at(offset: Int, vararg bytes: Int) = head.size >= offset + bytes.size && bytes.indices.all { head[offset + it] == bytes[it].toByte() }
            when {
                // ISO BMFF: 第 4~7 字节是 ftyp; 品牌 qt 是 QuickTime
                at(4, 'f'.code, 't'.code, 'y'.code, 'p'.code) ->
                    return if (at(8, 'q'.code, 't'.code)) MediaFormat("video/quicktime", "mov") else MediaFormat("video/mp4", "mp4")

                at(0, 0x1A, 0x45, 0xDF, 0xA3) -> return MediaFormat("video/x-matroska", "mkv")
                at(0, 0x47) -> return MediaFormat("video/mp2t", "ts")
            }
            val type = upstreamType?.substringBefore(';')?.trim()?.lowercase()
            if (type != null && type.startsWith("video/")) {
                return MediaFormat(type, EXTENSION_BY_TYPE[type] ?: "mp4")
            }
            val extension = url.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "").lowercase()
            return TYPE_BY_EXTENSION[extension]?.let { MediaFormat(it, extension) } ?: MediaFormat("video/mp4", "mp4")
        }

        private val TYPE_BY_EXTENSION = mapOf(
            "mp4" to "video/mp4",
            "m4v" to "video/x-m4v",
            "mov" to "video/quicktime",
            "mkv" to "video/x-matroska",
            "webm" to "video/webm",
            "ts" to "video/mp2t",
        )
        private val EXTENSION_BY_TYPE = TYPE_BY_EXTENSION.entries.associate { (extension, type) -> type to extension }
    }
}

private fun Long.mib(): String = ((this * 10 / (1024 * 1024)) / 10.0).toString()

/**
 * 写进日志的异常: 网络错误只记状态码 (ktor 的异常信息带着完整的请求地址, 网盘直链的路径与参数里有签名), 其余的把信息里的地址缩成域名.
 */
internal fun describe(e: Throwable): String {
    if (e is ResponseException) return "HTTP ${e.response.status.value}"
    val message = e.message?.let { URL_IN_MESSAGE.replace(it) { match -> match.groupValues[1] + "/…" } }?.take(300)
    return "${e::class.simpleName}: $message"
}

private val URL_IN_MESSAGE = Regex("""(https?://[^/\s"'?#]+)[^\s"']*""")
