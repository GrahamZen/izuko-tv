/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.media

import android.net.Uri
import androidx.annotation.OptIn as AndroidxOptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import me.him188.ani.app.platform.PlaybackRequestHints
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 多连接分块下载的 HTTP 数据源 (见 [ParallelRangeReader]), 每个范围请求交给 [upstreamFactory] 建的数据源去发.
 *
 * 用在每个连接有速度上限的网盘直链上 (由解析器在请求头里放 [PlaybackRequestHints.PARALLEL_RANGE_HEADER] 打开).
 * 服务端不按范围回时退回成单连接.
 *
 * 每次打开、关闭各记一行 (位置、用时、读了多少), 打开后很久没来读时记一次加载线程的栈 (见 [StallWatchdog]).
 */
@AndroidxOptIn(UnstableApi::class)
internal class ParallelRangeDataSource(
    private val upstreamFactory: HttpDataSource.Factory,
    private val connections: Int,
    private val chunkSize: Int,
    /** 此刻要不要多开连接 (到 [boostedConnectionsFor] 路), 每读一次问一次. */
    private val boosted: () -> Boolean = { false },
    /** 资源总长, 同一个资源的各数据源共用: 有了它打开时不必先单独问长度 (见 [ParallelRangeReader.open]). 不知道时为 -1. */
    private val knownLength: AtomicLong = AtomicLong(-1),
    /** [knownLength] 还不知道时在加载线程上问一次的来源 (例如本机缓存里记着的总长); 也不知道时返回 -1. */
    private val lengthHint: () -> Long = { -1 },
) : BaseDataSource(/* isNetwork = */ true) {
    /**
     * @param knownLength 见 [ParallelRangeDataSource] 的同名参数; 同一个资源的几个工厂 (播放、补字体) 传同一个
     * @param lengthHint 见 [ParallelRangeDataSource] 的同名参数
     */
    class Factory(
        private val upstreamFactory: HttpDataSource.Factory,
        private val connections: Int,
        private val chunkSize: Int = chunkSizeFor(connections),
        private val boosted: () -> Boolean = { false },
        private val knownLength: AtomicLong = AtomicLong(-1),
        private val lengthHint: () -> Long = { -1 },
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            ParallelRangeDataSource(upstreamFactory, connections, chunkSize, boosted, knownLength, lengthHint)
    }

    private val boostedConnections = boostedConnectionsFor(connections)

    private var dataSpec: DataSpec? = null
    private var reader: ParallelRangeReader? = null
    private var opened = false

    @Volatile
    private var responseHeaders: Map<String, List<String>> = emptyMap()

    // 诊断开播卡住 (见 [StallWatchdog]): 由加载线程写, 看门狗线程读
    @Volatile
    private var loadingThread: Thread? = null

    @Volatile
    private var openedAt = 0L

    @Volatile
    private var lastActivityAt = 0L

    @Volatile
    private var inRead = false

    @Volatile
    private var bytesRead = 0L

    @Volatile
    private var stallReported = false

    override fun open(dataSpec: DataSpec): Long {
        val startedAt = System.nanoTime()
        this.dataSpec = dataSpec
        transferInitializing(dataSpec)
        val opener = RangeOpener { start, length ->
            val upstream = upstreamFactory.createDataSource()
            val startedAt = System.nanoTime()
            try {
                upstream.open(
                    dataSpec.buildUpon()
                        .setPosition(start)
                        .setLength(if (length < 0) C.LENGTH_UNSET.toLong() else length)
                        .build(),
                )
            } catch (e: IOException) {
                runCatching { upstream.close() }
                // 被打断的是读的一方不要了的分块 (见 ParallelRangeReader 的 Chunk.cancel), 不是出错; HTTP 数据源会把它包一层
                if (e !is InterruptedIOException && e.cause !is InterruptedIOException) {
                    logger.warn { "Open ${dataSpec.uri.host} at $start failed after ${(System.nanoTime() - startedAt) / 1_000_000}ms: $e" }
                }
                throw e
            }
            val headers = upstream.responseHeaders
            if (responseHeaders.isEmpty()) responseHeaders = headers
            OpenedRange(UpstreamConnection(upstream), totalLengthOf(headers))
        }
        // 服务端明确拒绝 (403 地址过期之类) 再试也一样, 直接交给播放器
        val reader = ParallelRangeReader(
            opener, EXECUTOR, connections, chunkSize,
            retryable = { it !is HttpDataSource.InvalidResponseCodeException },
            firstChunkSize = chunkSize / FIRST_CHUNK_DIVISOR,
            maxConnections = { if (boosted()) boostedConnections else connections },
            maxWindowBytes = BOOSTED_WINDOW_BYTES,
        )
        this.reader = reader
        val lengthKnown = knownLength.get().takeIf { it >= 0 }
            ?: lengthHint().also { if (it >= 0) knownLength.compareAndSet(-1, it) }
        val length = reader.open(dataSpec.position, dataSpec.length, lengthKnown)
        if (lengthKnown < 0 && reader.totalLength >= 0) knownLength.compareAndSet(-1, reader.totalLength)
        logger.info {
            val mode = when {
                !reader.isParallel -> "a single connection"
                boosted() -> "$boostedConnections connections (boosted from $connections)"
                else -> "$connections connections"
            }
            val wait = if (lengthKnown >= 0) "all chunks requested at once" else "in ${millisSince(startedAt)}ms"
            "Opened ${dataSpec.uri.host} at ${dataSpec.position} with $mode, length $length, $wait"
        }
        opened = true
        bytesRead = 0
        stallReported = false
        loadingThread = Thread.currentThread()
        openedAt = System.nanoTime()
        lastActivityAt = openedAt
        StallWatchdog.watch(this)
        transferStarted(dataSpec)
        return if (length < 0) C.LENGTH_UNSET.toLong() else length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val reader = checkNotNull(reader) { "Data source is not open" }
        inRead = true
        val count = try {
            reader.read(buffer, offset, length)
        } finally {
            inRead = false
            lastActivityAt = System.nanoTime()
        }
        if (count < 0) return C.RESULT_END_OF_INPUT
        bytesRead += count
        bytesTransferred(count)
        return count
    }

    override fun getUri(): Uri? = dataSpec?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = responseHeaders

    override fun close() {
        StallWatchdog.unwatch(this)
        val current = reader
        val spec = dataSpec
        reader = null
        dataSpec = null
        val elapsed = millisSince(openedAt)
        val throughput = current?.throughputSummary(elapsed)
        current?.close()
        if (opened) {
            opened = false
            logger.info {
                "Closed ${spec?.uri?.host} after reading $bytesRead bytes from ${spec?.position} in ${elapsed}ms" +
                        (throughput?.let { "; $it" } ?: "")
            }
            transferEnded()
        }
    }

    /** 看门狗线程上调用: 打开后只读了一点就很久没再来读时, 记一次加载线程在干什么. */
    private fun reportIfStalled() {
        if (stallReported || inRead || bytesRead >= STALL_MAX_BYTES) return
        val idle = millisSince(lastActivityAt)
        if (idle < STALL_IDLE_MILLIS) return
        stallReported = true
        val thread = loadingThread
        logger.warn {
            "No reads for ${idle}ms after reading $bytesRead bytes (opened ${millisSince(openedAt)}ms ago); " +
                    "loading thread ${thread?.name}: " + thread?.stackTrace?.take(STACK_DEPTH)?.joinToString(" <- ")
        }
    }

    /**
     * 开播卡住时, 等数据与关连接慢由 [ParallelRangeReader] 自己记; 这里管它管不到的: 数据源打开后只读了不到 [STALL_MAX_BYTES]
     * 就 [STALL_IDLE_MILLIS] 没再来读 (加载线程卡在播放器自己那边), 记一次加载线程的栈.
     * 正常播放时缓冲满了也会停读, 但那时一个连接早已读了很多, 不会记.
     */
    private object StallWatchdog {
        private val watched = ConcurrentHashMap.newKeySet<ParallelRangeDataSource>()

        private val timer: ScheduledExecutorService by lazy {
            Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "ParallelRange-watchdog").apply { isDaemon = true }
            }.apply {
                scheduleWithFixedDelay({ watched.forEach { runCatching { it.reportIfStalled() } } }, 2, 2, TimeUnit.SECONDS)
            }
        }

        fun watch(source: ParallelRangeDataSource) {
            watched.add(source)
            timer
        }

        fun unwatch(source: ParallelRangeDataSource) {
            watched.remove(source)
        }
    }

    private class UpstreamConnection(private val upstream: DataSource) : RangeConnection {
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val count = upstream.read(buffer, offset, length)
            return if (count == C.RESULT_END_OF_INPUT) -1 else count
        }

        override fun close() = upstream.close()
    }

    companion object {
        /** 一块最大多大. 太小请求太多, 太大开头那块要等得久; 4 MiB 在 1 MB/s 的连接上约 4 秒. */
        const val DEFAULT_CHUNK_SIZE = 4 * 1024 * 1024

        /** 所有连接手上的块加起来多大 (每块的缓冲常驻): 连接多时块就小, 非会员 16 路每块 1 MiB. */
        private const val WINDOW_BYTES = 16 * 1024 * 1024
        private const val MIN_CHUNK_SIZE = 1024 * 1024

        /** 开播 / 跳转后头一轮的块是最大块的几分之一 (见 [ParallelRangeReader]). */
        private const val FIRST_CHUNK_DIVISOR = 4

        fun chunkSizeFor(connections: Int): Int =
            (WINDOW_BYTES / connections.coerceAtLeast(1)).coerceIn(MIN_CHUNK_SIZE, DEFAULT_CHUNK_SIZE)

        /**
         * 多开连接时开到几路. 非会员 16 路 → 48 路: 每路的限速不变, 总速度随路数涨 (实测到 48 路仍是线性的, 全部 206);
         * 64 路那次整轮连不上 (多半是下载节点整组失联, 但没排除跟路数有关), 所以封顶 48.
         */
        fun boostedConnectionsFor(connections: Int): Int =
            (connections * BOOST_FACTOR).coerceAtMost(MAX_BOOSTED_CONNECTIONS).coerceAtLeast(connections)

        private const val BOOST_FACTOR = 3
        private const val MAX_BOOSTED_CONNECTIONS = 48

        /** 多开连接时所有块加起来最多多大 (见 [ParallelRangeReader] 的 maxWindowBytes): 48 路每块 512 KiB. */
        private const val BOOSTED_WINDOW_BYTES = 24 * 1024 * 1024

        private val logger = logger<ParallelRangeDataSource>()

        private const val STALL_IDLE_MILLIS = 10_000L
        private const val STALL_MAX_BYTES = 8L * 1024 * 1024
        private const val STACK_DEPTH = 16

        private fun millisSince(nanos: Long) = (System.nanoTime() - nanos) / 1_000_000

        private val threadId = AtomicInteger()

        private val EXECUTOR: ExecutorService = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "ParallelRange-${threadId.incrementAndGet()}").apply { isDaemon = true }
        }

        /** `Content-Range: bytes 0-4194303/247580374` 里的总长; 没有或是 `*` 时为 -1. */
        internal fun totalLengthOf(headers: Map<String, List<String>>): Long {
            val value = headers.entries.firstOrNull { it.key.equals("Content-Range", ignoreCase = true) }
                ?.value?.firstOrNull() ?: return -1
            return value.substringAfterLast('/', "").trim().toLongOrNull() ?: -1
        }
    }
}
