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
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.utils.io.readAvailable
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.atomicfu.update
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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
    private val activeRequests = atomic(0)

    /** 每条连接最近拉块的速度 (字节/秒, 含首字节的等待), 0 = 还没测到. 定块多大用 (见 [chunkCap]). */
    private val laneSpeed = atomic(0L)

    /** 读前窗口在后台拉, 不跟着哪个请求停; 会话关掉时一起停. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO_)

    /** 读前窗口, 最近用过的在前 (见 [readAheadFor]). */
    private val readAheads = ArrayDeque<ReadAhead>()
    private val readAheadLock = SynchronizedObject()

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
        val others = activeRequests.value
        logger.info {
            "[RangeProxy] #$id 第 $n 个请求: Range ${rangeHeader ?: "(无)"} → 回 ${range.first}-${range.last} (${length.mib()} MiB)" +
                if (others > 0) ", 同时还有 $others 个请求在送" else ""
        }
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
        activeRequests.incrementAndGet()
        try {
            stream(n, range, sink)
        } finally {
            activeRequests.decrementAndGet()
        }
    }

    /**
     * 先从读前窗口 ([readAheadFor]) 送盖得住的开头, 剩下的拆成块 ([planChunks]) 分给各通道同时拉, 按顺序送给播放器.
     * 同时在拉与拉好没送的块最多 [connections] 个 (内存不超过路数 × 最大块).
     * 排在最前的那块拉到多少就送多少 (不等整块拉完): 画面不用一块一块地等, 播放器断开 (跳转、换源) 时下一次写入就发现,
     * 这个请求在拉的块随之取消, 通道马上空出来给新的请求 —— 不然要等手上那块拉完写不出去才知道, 期间通道全被播放器不要了的数据占着.
     */
    private suspend fun stream(n: Int, range: LongRange, sink: HlsProxyResponseSink) {
        val started = TimeSource.Monotonic.markNow()
        val progress = StreamProgress(n)
        var firstByteMillis = -1L
        var fromReadAhead = 0L
        suspend fun send(bytes: ByteArray) {
            val waitedPlayer = TimeSource.Monotonic.markNow()
            sink.write(bytes)
            progress.playerMillis += waitedPlayer.elapsedNow().inWholeMilliseconds
            if (firstByteMillis < 0) firstByteMillis = started.elapsedNow().inWholeMilliseconds
            progress.sent += bytes.size
        }
        try {
            var position = range.first
            // 跨过窗口边界时接着从下一个窗口答 (剩下的还小就再开一个); 窗口那段拉不下来时停下, 剩下的照常拉
            while (position <= range.last) {
                val ahead = readAheadFor(n, position..range.last) ?: break
                val waited = TimeSource.Monotonic.markNow()
                val playerBefore = progress.playerMillis
                val next = ahead.serve(position..range.last) { send(it) }
                progress.networkMillis += waited.elapsedNow().inWholeMilliseconds - (progress.playerMillis - playerBefore)
                fromReadAhead += next - position
                if (next == position) break
                position = next
            }
            if (position <= range.last) {
                coroutineScope {
                    val permits = Semaphore(connections)
                    val pending = Channel<Chunk>(Channel.UNLIMITED)
                    launch {
                        for (part in planChunks(position, range.last, connections, firstChunkSize, ::chunkCap)) {
                            permits.acquire()
                            val chunk = Chunk(part)
                            launch { fetch(chunk) }
                            pending.send(chunk)
                        }
                        pending.close()
                    }
                    for (chunk in pending) {
                        while (true) {
                            // 卡的时候分得清是网盘慢 (等块) 还是播放器缓冲够了暂时不读 (等写入)
                            val waitedNetwork = TimeSource.Monotonic.markNow()
                            val piece = chunk.pieces.receiveCatching()
                            progress.networkMillis += waitedNetwork.elapsedNow().inWholeMilliseconds
                            val bytes = piece.getOrNull() ?: (piece.exceptionOrNull()?.let { throw it } ?: break)
                            send(bytes)
                            progress.maybeLog(inFlight = connections - permits.availablePermits)
                        }
                        permits.release()
                    }
                }
            }
            val millis = started.elapsedNow().inWholeMilliseconds
            logger.info {
                "[RangeProxy] #$id 第 $n 个请求送完 ${range.first}-${range.last}: ${progress.sent.mib()} MiB" +
                    (if (fromReadAhead > 0) " (读前窗口里的 ${fromReadAhead / 1024} KiB)" else "") +
                    ", 首块 ${firstByteMillis}ms, 用时 ${millis}ms, 平均 ${progress.sent * 1000 / millis.coerceAtLeast(1) / 1024} KiB/s, " +
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
                    "等网盘 ${networkMillis - lastNetworkMillis}ms、等播放器 ${playerMillis - lastPlayerMillis}ms, 在拉 $inFlight 块, " +
                    "每条连接 ${laneSpeed.value / 1024} KiB/s、块上限 ${chunkCap() / 1024} KiB"
            }
            lastLog = TimeSource.Monotonic.markNow()
            lastSent = sent
            lastNetworkMillis = networkMillis
            lastPlayerMillis = playerMillis
        }
    }

    /** 长请求的一块: 拉到的按到达顺序放进 [Chunk.pieces], 拉完关掉; 拉不下来时带着原因关掉 (送到这块时抛出). */
    private suspend fun fetch(chunk: Chunk) {
        try {
            fetchPart(chunk.part, urgent = false) { chunk.pieces.send(it) }
            chunk.pieces.close()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            chunk.pieces.close(e)
        }
    }

    /**
     * 拉 [part], 拉到多少交给 [onBytes] 多少. 借一条空着的通道 ([urgent] 的排在前面), 网络出错从断开处接着重试 [MAX_RETRIES] 次
     * (网盘回 4xx 不重试, 直链多半过期了); 要它的已经停了 (播放器断开、窗口被挤掉) 就不重试. 拉不下来时抛出.
     */
    private suspend fun fetchPart(part: LongRange, urgent: Boolean, onBytes: suspend (ByteArray) -> Unit) {
        var from = part.first
        var attempt = 0
        while (true) {
            val client = lanes.take(urgent)
            val started = TimeSource.Monotonic.markNow()
            val before = from
            try {
                client.fetchRange(url, headers, from..part.last) { bytes ->
                    onBytes(bytes)
                    from += bytes.size
                    if (!urgent && from <= part.last && lanes.shouldYield()) throw LaneYield()
                }
                val elapsed = started.elapsedNow()
                recordSpeed(from - before, elapsed.inWholeMilliseconds)
                if (elapsed.inWholeMilliseconds >= SLOW_CHUNK_LOG_MILLIS) {
                    logger.info { "[RangeProxy] #$id 块 ${part.first}-${part.last} 拉了 ${elapsed.inWholeMilliseconds}ms" }
                }
                return
            } catch (e: CancellationException) {
                throw e
            } catch (_: LaneYield) {
                // 让给急用的: 不算重试, 还回通道 (finally) 后重新排队, 从断开处接着拉
                continue
            } catch (e: Throwable) {
                currentCoroutineContext().ensureActive()
                val status = (e as? ResponseException)?.response?.status?.value
                if (attempt >= MAX_RETRIES || status in 400..499) {
                    logger.warn { "[RangeProxy] #$id 块 ${part.first}-${part.last} 拉不下来 (试了 ${attempt + 1} 次): ${describe(e)}" }
                    throw e
                }
                attempt++
                logger.info { "[RangeProxy] #$id 块 ${part.first}-${part.last} 第 $attempt 次重试 (从 $from 接着拉): ${describe(e)}" }
            } finally {
                lanes.give(client)
            }
            delay(RETRY_DELAY_MILLIS * attempt)
        }
    }

    /** 不急的拉取把通道让给急用的 (见 [Lanes.shouldYield]). */
    private class LaneYield : Exception()

    /** 一块拉到的字节, 一段一段 (随网络到达). */
    private class Chunk(val part: LongRange) {
        val pieces = Channel<ByteArray>(Channel.UNLIMITED)
    }

    /**
     * 盖住 [range] 开头的读前窗口. 没有的话, 小请求 (不超过 [READ_AHEAD_TRIGGER_BYTES]) 开一个新的, 大请求不开.
     * 顺着读到窗口后半截时, 接着把紧挨着的下一截也开始拉: 跳转后播放器一段接一段地要好几 MiB 才开播, 下一截在它读这一截时就在路上.
     */
    private fun readAheadFor(n: Int, range: LongRange): ReadAhead? = synchronized(readAheadLock) {
        readAheads.firstOrNull { range.first in it.window }?.let { hit ->
            readAheads.remove(hit)
            readAheads.addFirst(hit)
            val next = hit.window.last + 1
            val inSecondHalf = range.first - hit.window.first >= (hit.window.last - hit.window.first + 1) / 2
            if (inSecondHalf && next < totalLength && readAheads.none { next in it.window }) {
                openReadAhead(n, next, minLength = 1, verb = "接着开")
            }
            return hit
        }
        val length = range.last - range.first + 1
        if (length > READ_AHEAD_TRIGGER_BYTES) return null
        openReadAhead(n, range.first, minLength = length, verb = "开")
    }

    /**
     * 从 [start] 开一个读前窗口, 至少 [minLength]. 不和已有的重叠 (碰到后面的窗口就截止在它前面, 那段数据已经有了).
     * 跳到片尾附近时播放器在几 MiB 里跳着读, 中间还会插一段别处的, 所以留最多 [MAX_READ_AHEADS] 个、合计不超过 [READ_AHEAD_TOTAL_BYTES],
     * 挤掉最久没用的 —— 只留一两个的话互相挤掉, 同一段一遍遍重拉. 持着 [readAheadLock] 调.
     */
    private fun openReadAhead(n: Int, start: Long, minLength: Long, verb: String): ReadAhead {
        val pieceSize = readAheadPieceSize()
        val bytes = (pieceSize * connections).coerceIn(READ_AHEAD_MIN_BYTES, READ_AHEAD_MAX_BYTES).coerceAtLeast(minLength)
        val nextWindowStart = readAheads.minOfOrNull { if (it.window.first > start) it.window.first else Long.MAX_VALUE } ?: Long.MAX_VALUE
        val window = start..minOf(totalLength - 1, start + bytes - 1, nextWindowStart - 1)
        val ahead = ReadAhead(window, pieceSize)
        readAheads.addFirst(ahead)
        while (readAheads.size > MAX_READ_AHEADS || readAheads.sumOf { it.window.last - it.window.first + 1 } > READ_AHEAD_TOTAL_BYTES) {
            readAheads.removeLast().cancel()
        }
        logger.info {
            "[RangeProxy] #$id 第 $n 个请求${verb}读前窗口 ${window.first}-${window.last} " +
                "(${(window.last - window.first + 1) / 1024} KiB, 每段 ${pieceSize / 1024} KiB)"
        }
        return ahead
    }

    /** 读前窗口每段多大: 按每条连接的速度约 [READ_AHEAD_PIECE_MILLIS] 拉完, 夹在 [MIN_READ_AHEAD_PIECE]..[MAX_READ_AHEAD_PIECE]; 还没测到速度时取最小. */
    private fun readAheadPieceSize(): Long {
        val speed = laneSpeed.value
        if (speed <= 0) return MIN_READ_AHEAD_PIECE
        return (speed * READ_AHEAD_PIECE_MILLIS / 1000).coerceIn(MIN_READ_AHEAD_PIECE, MAX_READ_AHEAD_PIECE)
    }

    /**
     * 读前窗口: 跳转后播放器先一段接一段地要几十 KiB, 读完才开播. 每段单连接拉, 都要等一次首字节、再按单连接的速度传, 串起来就是好几秒.
     * 第一段来时把从它开始的一截 ([window]) 切成小段, 借急用的通道 (排在长请求的块前面) 一起拉进内存, 后面落在这一截里的请求直接从这里答.
     */
    private inner class ReadAhead(val window: LongRange, pieceSize: Long) {
        private val job = SupervisorJob(scope.coroutineContext[Job])
        private val parts = planChunks(window.first, window.last, connections, pieceSize, pieceSize).toList()
        // 当场跑到借通道那一步 (UNDISPATCHED): 按段的顺序排队, 播放器先要的那几段先拿到通道
        private val data = parts.map { part ->
            scope.async(job, start = CoroutineStart.UNDISPATCHED) {
                val bytes = ByteArray((part.last - part.first + 1).toInt())
                var filled = 0
                fetchPart(part, urgent = true) { piece ->
                    piece.copyInto(bytes, filled)
                    filled += piece.size
                }
                bytes
            }
        }

        init {
            val started = TimeSource.Monotonic.markNow()
            scope.launch(job) {
                val failed = data.count { runCatching { it.await() }.isFailure }
                // 被挤掉的不记
                currentCoroutineContext().ensureActive()
                logger.info {
                    "[RangeProxy] #$id 读前窗口 ${window.first}-${window.last} 拉完: ${started.elapsedNow().inWholeMilliseconds}ms" +
                        if (failed > 0) ", ${failed} 段没拉下来" else ""
                }
            }
        }

        /**
         * 从 [range] 开头起送出落在窗口里的部分, 返回下一个没送的位置. 窗口那段拉不下来、或窗口已被挤掉时就停在那里, 剩下的由调用方照常拉.
         */
        suspend fun serve(range: LongRange, send: suspend (ByteArray) -> Unit): Long {
            val end = minOf(range.last, window.last)
            var position = range.first
            for ((index, part) in parts.withIndex()) {
                if (part.last < position) continue
                if (part.first > end) break
                val bytes = try {
                    data[index].await()
                } catch (e: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    return position
                } catch (e: Throwable) {
                    return position
                }
                val upTo = minOf(end, part.last)
                send(bytes.copyOfRange((position - part.first).toInt(), (upTo - part.first + 1).toInt()))
                position = upTo + 1
            }
            return position
        }

        fun cancel() = job.cancel()
    }

    /** 记一块的速度 (平滑: 新的占三成). 太小的块首字节的等待占大头, 不算. */
    private fun recordSpeed(bytes: Long, millis: Long) {
        if (bytes < MIN_SPEED_SAMPLE_BYTES) return
        val sample = bytes * 1000 / millis.coerceAtLeast(1)
        laneSpeed.update { old -> if (old == 0L) sample else (old * 7 + sample * 3) / 10 }
    }

    /**
     * 一块最大多大: 按每条连接的速度, 让一块约 [CHUNK_TARGET_MILLIS] 拉完, 夹在 [MIN_FIRST_CHUNK_SIZE]..[chunkSize].
     * 块越大请求越少, 但一条通道被一块占着越久: 慢的网盘 (每条连接几十 KB/s) 上 1 MiB 一块要 20 秒, 其间新来的请求 (播放器跳转时一起发的小请求) 都得等.
     */
    private fun chunkCap(): Long {
        val speed = laneSpeed.value
        if (speed <= 0) return chunkSize
        return (speed * CHUNK_TARGET_MILLIS / 1000).coerceIn(MIN_FIRST_CHUNK_SIZE, chunkSize)
    }

    override fun close() {
        logger.info { "[RangeProxy] #$id 关闭 (共答了 ${requestCount.value} 个请求)" }
        server.close()
        scope.cancel()
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

        /** 一块大约拉多久 (见 [chunkCap]); 测速不算比这还小的块 (见 [recordSpeed]). */
        private const val CHUNK_TARGET_MILLIS = 5_000L
        private const val MIN_SPEED_SAMPLE_BYTES = 128L * 1024

        /** 读前窗口 (见 [ReadAhead]): 多小的请求才开; 窗口多大 (每段 × 路数, 夹在这两个之间); 每段约拉多久、多大; 最多留几个、合计多大. */
        private const val READ_AHEAD_TRIGGER_BYTES = 1024L * 1024
        private const val READ_AHEAD_MIN_BYTES = 1024L * 1024
        private const val READ_AHEAD_MAX_BYTES = 4L * 1024 * 1024
        private const val READ_AHEAD_PIECE_MILLIS = 500L
        private const val MIN_READ_AHEAD_PIECE = 32L * 1024
        private const val MAX_READ_AHEAD_PIECE = 256L * 1024
        private const val MAX_READ_AHEADS = 8
        private const val READ_AHEAD_TOTAL_BYTES = 12L * 1024 * 1024

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
 * 空出来的通道先给急用的 (读前窗口), 再按先来后到给长请求的块.
 */
@OptIn(UnsafeScopedHttpClientApi::class)
private class Lanes(private val scoped: List<ScopedHttpClient>) : AutoCloseable {
    private val tickets = scoped.map { it.borrow() }
    private val lock = SynchronizedObject()
    private val free = ArrayDeque(tickets.map { it.client })

    /** 排队等通道的: 每个一格的信箱, 交到手上就放进去. */
    private val urgentWaiters = ArrayDeque<Channel<HttpClient>>()
    private val waiters = ArrayDeque<Channel<HttpClient>>()
    private val closed = atomic(false)

    /** 已经答应让出、还没交到急用的手上的通道数 (见 [shouldYield]). */
    private var yielding = 0

    /** 实际有几个不同的客户端 (日志里核对: 应当等于通道数, 不然几条通道共用一条连接). */
    val distinctClients: Int = tickets.map { it.client }.distinct().size

    /** 借一条通道, 没有空的就排队 ([urgent] 排在不急的前面). 会话关了抛出. */
    suspend fun take(urgent: Boolean = false): HttpClient {
        val mailbox = synchronized(lock) {
            check(!closed.value) { "lanes closed" }
            free.removeFirstOrNull()?.let { return it }
            Channel<HttpClient>(1).also { (if (urgent) urgentWaiters else waiters).addLast(it) }
        }
        try {
            return mailbox.receive()
        } catch (e: Throwable) {
            synchronized(lock) {
                urgentWaiters.remove(mailbox)
                waiters.remove(mailbox)
            }
            mailbox.close()
            // 不等了的那一刻刚好交到手上的, 还回去
            mailbox.tryReceive().getOrNull()?.let(::give)
            throw e
        }
    }

    fun give(client: HttpClient) {
        while (true) {
            val mailbox = synchronized(lock) {
                urgentWaiters.removeFirstOrNull()?.also { if (yielding > 0) yielding-- }
                    ?: waiters.removeFirstOrNull()
                    ?: run {
                        if (!closed.value) free.addLast(client)
                        return
                    }
            }
            // 已经不等了的信箱是关着的, 交给下一个
            if (mailbox.trySend(client).isSuccess) return
        }
    }

    /**
     * 不急的拉取每读一段问一次: 有急用的在等 (通道都占着才会等) 时让出手上这条, 每个在等的只换一条, 返回 true 的那个要马上还回通道.
     * 长请求的一块要拉好几秒 (慢的网盘每条连接几十 KB/s), 不让的话急用的得等到哪块拉完.
     */
    fun shouldYield(): Boolean = synchronized(lock) {
        if (yielding > urgentWaiters.size) yielding = urgentWaiters.size
        if (urgentWaiters.size > yielding) {
            yielding++
            true
        } else {
            false
        }
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
        val stranded = synchronized(lock) {
            (urgentWaiters + waiters).also {
                urgentWaiters.clear()
                waiters.clear()
            }
        }
        stranded.forEach { it.close() }
        scoped.zip(tickets).forEach { (client, ticket) -> client.returnClient(ticket) }
    }

    class Probe(val totalLength: Long, val upstreamType: String?, val head: ByteArray)

    private companion object {
        private val logger = logger<Lanes>()
        const val PROBE_BYTES = 16
    }
}

/**
 * 要 [part] 这一段, 拉到多少就交给 [onBytes] 多少: 必须回 206 且长度对得上. 网盘没按范围回 (整个文件) 时不读正文;
 * 中途断了抛出, 已经交出去的不收回 (调用方从断开处接着要).
 */
private suspend fun HttpClient.fetchRange(
    url: String,
    headers: Map<String, String>,
    part: LongRange,
    onBytes: suspend (ByteArray) -> Unit,
) {
    val expected = part.last - part.first + 1
    prepareGet(url) {
        resolverHeaders(headers)
        header(HttpHeaders.Range, "bytes=${part.first}-${part.last}")
        chunkTimeouts()
    }.execute { response ->
        check(response.status == HttpStatusCode.PartialContent) { "网盘没按范围回: ${response.status}" }
        val channel = response.bodyAsChannel()
        val buffer = ByteArray(PIECE_BYTES)
        var received = 0L
        while (received < expected) {
            val read = channel.readAvailable(buffer, 0, minOf(PIECE_BYTES.toLong(), expected - received).toInt())
            if (read < 0) break
            if (read == 0) continue
            onBytes(buffer.copyOf(read))
            received += read
        }
        check(received == expected) { "只回了 $received / $expected 字节" }
    }
}

/** 拉块时一次最多读多少 (边拉边送的粒度). */
private const val PIECE_BYTES = 64 * 1024

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
internal fun planChunks(start: Long, endInclusive: Long, connections: Int, firstChunkSize: Long, chunkSize: Long): Sequence<LongRange> =
    planChunks(start, endInclusive, connections, firstChunkSize) { chunkSize }

/** 同上, 块的上限每块现取 ([chunkCap], 随测到的速度变). 序列是惰性的, 取下一块时才算. */
internal fun planChunks(start: Long, endInclusive: Long, connections: Int, firstChunkSize: Long, chunkCap: () -> Long): Sequence<LongRange> = sequence {
    var position = start
    var index = 0
    while (position <= endInclusive) {
        val round = (index / connections.coerceAtLeast(1)).coerceAtMost(MAX_RAMP_ROUNDS)
        val size = minOf(chunkCap(), firstChunkSize shl round)
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
