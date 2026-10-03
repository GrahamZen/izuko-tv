/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.media

import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import java.io.Closeable
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 一个已经打开的范围请求.
 */
internal interface RangeConnection : Closeable {
    /** 读到 [buffer]; 读完返回 -1. */
    fun read(buffer: ByteArray, offset: Int, length: Int): Int
}

/**
 * @param totalLength 整个资源的长度, 服务端没说 (没有 `Content-Range`, 即没按范围回) 时为 -1
 */
internal class OpenedRange(val connection: RangeConnection, val totalLength: Long)

internal fun interface RangeOpener {
    /**
     * 打开 `[start, start + length)` 这一段. [length] 为 -1 表示到资源末尾.
     */
    fun open(start: Long, length: Long): OpenedRange
}

/**
 * 把一段范围切成块, 同时最多 [connections] 块在下, 按顺序交给读的一方.
 *
 * 每次 [open] 之后头一轮 ([connections] 块) 每块 [firstChunkSize], 之后每轮翻倍, 到 [chunkSize] 为止: 开播 / 跳转后马上要的那几 MB
 * 由所有连接一起下, 不用等一个连接把一整块下完 —— 连接被限速得很慢时 (非会员网盘每路几十 KB/s), 一整块要等上一两分钟.
 * 读的一方可以读一块里已经到了的部分, 不用等整块下完, 所以开播不比单连接慢.
 * 一块下到一半断了就从断的地方重新请求, 第一次打开连不上也当场再试 (连不上的节点会被跳过, 见 [RangeHttpClients]),
 * 连续失败 [MAX_RETRIES] 次才把错误交给读的一方. [retryable] 判为不值得重试的错误 (例如 HTTP 403) 直接交出去.
 *
 * [open] / [read] / [close] 只能在同一个线程上调用 (播放器的加载线程); 各块在 [executor] 上下载.
 *
 * 每块的连接只在下这一块的线程上读和关; [close] 只是告诉各块别再下了.
 *
 * 日志: 读的一方等数据每满 [SLOW_WAIT_LOG_MILLIS] 记一次 (带这一块的进度和下载线程的栈); 每次下载失败、
 * 下得特别慢的块 ([SLOW_CHUNK_LOG_MILLIS])、关得慢的 [close] 也记.
 */
internal class ParallelRangeReader(
    private val opener: RangeOpener,
    private val executor: Executor,
    private val connections: Int,
    private val chunkSize: Int,
    private val firstChunkSize: Int = chunkSize,
    private val retryable: (IOException) -> Boolean = { true },
) {
    private class Chunk(val start: Long, val length: Int, val data: ByteArray) {
        private val lock = Object()
        private var filled = 0
        private var error: IOException? = null

        val scheduledAt = System.nanoTime()

        /** 正在下这一块的线程, 等得久时记它的栈. */
        @Volatile
        var downloader: Thread? = null

        @Volatile
        var failures = 0

        @Volatile
        var cancelled = false
            private set

        val isComplete: Boolean get() = synchronized(lock) { filled >= length }

        fun filledCount(): Int = synchronized(lock) { filled }

        fun advance(count: Int) = synchronized(lock) {
            filled += count
            lock.notifyAll()
        }

        fun fail(e: IOException) = synchronized(lock) {
            error = e
            lock.notifyAll()
        }

        /**
         * 只做标记, 连接由下载线程读完手上这一次后自己关 (卡在读里的最多等到读超时).
         * 不能从这边关: OkHttp 的响应在别的线程正读着时关不掉 (抛 `Unbalanced enter/exit`), 之后下载线程再关也不管用了,
         * 连接一直占着, 到被回收时才关 (日志里的 `A connection … was leaked`).
         */
        fun cancel() {
            synchronized(lock) {
                cancelled = true
                lock.notifyAll()
            }
        }

        /**
         * 等到 [offset] 之后有数据可读, 返回可读的字节数. 每等满 [SLOW_WAIT_LOG_MILLIS] 调一次 [onSlow] (参数是已等的毫秒数).
         */
        fun awaitAvailable(offset: Int, onSlow: (Long) -> Unit): Int = synchronized(lock) {
            val startedAt = System.nanoTime()
            var nextReport = SLOW_WAIT_LOG_MILLIS
            while (filled <= offset && error == null && !cancelled) {
                try {
                    lock.wait(WAIT_SLICE_MILLIS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw InterruptedIOException("Interrupted while waiting for data")
                }
                val waited = millisSince(startedAt)
                if (waited >= nextReport && filled <= offset && error == null && !cancelled) {
                    onSlow(waited)
                    nextReport += SLOW_WAIT_LOG_MILLIS
                }
            }
            if (filled > offset) return filled - offset
            error?.let { throw IOException("Failed to download bytes ${start + offset}..${start + length}", it) }
            throw InterruptedIOException("Reader closed")
        }
    }

    /** 不分块, 直接读一个连接 (请求本身就小, 或者服务端不支持范围请求). */
    private var direct: RangeConnection? = null

    private val window = ArrayDeque<Chunk>()
    private val freeBuffers = ArrayDeque<ByteArray>()
    private var nextStart = 0L
    private var end = 0L
    private var readOffset = 0
    private var remaining = 0L

    /** 本次 [open] 以来排了几块, 定下一块多大 (见 [nextChunkLength]). */
    private var scheduledCount = 0

    // 速度小结 (见 [throughputSummary]), 各下载线程累加
    private val downloadedBytes = AtomicLong()
    private val completedChunks = AtomicInteger()
    private val completedChunkBytes = AtomicLong()
    private val completedChunkNanos = AtomicLong()

    @Volatile
    private var closed = false

    /** [open] 之后: 是否在分块并发下载 (请求本身小、或服务端不支持范围请求时为 false). */
    val isParallel: Boolean get() = direct == null

    /**
     * 打开 `[position, position + length)` ([length] 为 -1 表示到资源末尾), 返回要读的字节数, 不知道时为 -1.
     */
    fun open(position: Long, length: Long): Long {
        scheduledCount = 0
        if (length in 0..chunkSize.toLong()) {
            direct = openWithRetry(position, length).connection
            return length
        }
        // 不知道要读到哪、又不是从头读时 (播放器跳到文件尾读索引), 第一次请求不封口 (`bytes=p-`), 第一块只从它读一块的量:
        // 封口的范围可能越过文件末尾, 有的节点 (夸克 `pds`) 对这种请求回整个文件 (200), 播放器的 HTTP 数据源会把前面的全读掉再给,
        // 位置靠后时要等一两分钟. 从头读时照旧封口: 从 0 开始又不封口的请求, HTTP 数据源根本不带 Range 头, 服务端回整个文件, 就分不了块了
        val firstChunk = nextChunkLength()
        val firstLength = when {
            length >= 0 -> minOf(length, firstChunk)
            position > 0 -> -1
            else -> firstChunk
        }
        val first = openWithRetry(position, firstLength)
        if (first.totalLength < 0) {
            // 服务端没按范围回 (整个文件都在这个连接里, 数据源已经跳到 position): 不能分块, 直接读.
            // 不封口的那次就是要的范围, 接着用; 封口的那次只给了一块, 重开一次要的长度
            direct = if (firstLength < 0) {
                first.connection
            } else {
                runCatching { first.connection.close() }
                openWithRetry(position, length).connection
            }
            return length
        }
        end = if (length < 0) first.totalLength else minOf(position + length, first.totalLength)
        remaining = (end - position).coerceAtLeast(0)
        nextStart = position
        if (remaining == 0L) {
            runCatching { first.connection.close() }
            return 0
        }
        schedule(initial = first.connection)
        while (window.size < connections && nextStart < end) schedule(initial = null)
        return remaining
    }

    fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        direct?.let { return it.read(buffer, offset, length) }
        if (length == 0) return 0
        if (remaining == 0L) return -1
        val chunk = window.first()
        val waitStartedAt = System.nanoTime()
        val available = chunk.awaitAvailable(readOffset) { waited -> logSlowWait(chunk, waited) }
        val waited = millisSince(waitStartedAt)
        if (waited >= SLOW_WAIT_LOG_MILLIS) {
            logger.info { "Got bytes at ${chunk.start + readOffset} after waiting ${waited}ms (chunk at ${chunk.start}, ${chunk.failures} failures)" }
        }
        val count = minOf(length, available)
        System.arraycopy(chunk.data, readOffset, buffer, offset, count)
        readOffset += count
        remaining -= count
        if (readOffset >= chunk.length) {
            window.removeFirst()
            freeBuffers.addLast(chunk.data)
            readOffset = 0
            if (nextStart < end) schedule(initial = null)
        }
        return count
    }

    /**
     * 一行速度小结, 关之前取 (分块下载时才有, 否则 null): 一共下了多少、平均多快, 以及下完的块平均每块多快 ——
     * 那就是一个连接的速度, 网盘按连接限速时就是那个上限 (用户发来的日志里看得出是不是被限速).
     */
    fun throughputSummary(elapsedMillis: Long): String? {
        if (!isParallel) return null
        val bytes = downloadedBytes.get()
        val chunkNanos = completedChunkNanos.get()
        val perConnection = if (chunkNanos > 0) "${completedChunkBytes.get() * 1_000_000_000L / chunkNanos / 1024} KiB/s" else "unknown"
        return "downloaded ${bytes / 1024} KiB at ${if (elapsedMillis > 0) bytes * 1000 / elapsedMillis / 1024 else 0} KiB/s, " +
                "$perConnection per connection over ${completedChunks.get()} chunks"
    }

    fun close() {
        val startedAt = System.nanoTime()
        closed = true
        direct?.let { connection ->
            runCatching { connection.close() }.onFailure { logger.warn { "Failed to close the direct connection: $it" } }
        }
        direct = null
        window.forEach { it.cancel() }
        window.clear()
        freeBuffers.clear()
        val took = millisSince(startedAt)
        if (took >= SLOW_CLOSE_LOG_MILLIS) logger.warn { "Closing took ${took}ms" }
    }

    private fun logSlowWait(chunk: Chunk, waited: Long) {
        logger.warn {
            val downloader = chunk.downloader
            val thread = if (downloader == null) {
                "no download thread"
            } else {
                "download thread ${downloader.name}: " + downloader.stackTrace.take(STACK_DEPTH).joinToString(" <- ")
            }
            "Waiting ${waited}ms for bytes at ${chunk.start + readOffset} " +
                    "(chunk at ${chunk.start}: ${chunk.filledCount()}/${chunk.length} downloaded, ${chunk.failures} failures, " +
                    "scheduled ${millisSince(chunk.scheduledAt)}ms ago); $thread"
        }
    }

    /** 在调用线程上打开, 连不上就当场再试, 规则同各块下载. */
    private fun openWithRetry(start: Long, length: Long): OpenedRange {
        var failures = 0
        while (true) {
            try {
                return opener.open(start, length)
            } catch (e: InterruptedIOException) {
                throw e
            } catch (e: IOException) {
                if (closed || Thread.currentThread().isInterrupted || !retryable(e) || ++failures > MAX_RETRIES) throw e
            }
        }
    }

    /** 下一块多大: 头一轮 [firstChunkSize], 之后每轮 ([connections] 块) 翻倍, 到 [chunkSize] 为止. */
    private fun nextChunkLength(): Long {
        val round = (scheduledCount / connections).coerceAtMost(MAX_RAMP_ROUNDS)
        return minOf(chunkSize.toLong(), firstChunkSize.toLong() shl round)
    }

    private fun schedule(initial: RangeConnection?) {
        val length = minOf(nextChunkLength(), end - nextStart).toInt()
        scheduledCount++
        // 缓冲一律按最大的块分配, 换下来的可以给后面任何一块用
        val data = freeBuffers.removeFirstOrNull() ?: ByteArray(chunkSize)
        val chunk = Chunk(nextStart, length, data)
        nextStart += length
        window.addLast(chunk)
        executor.execute { download(chunk, initial) }
    }

    private fun download(chunk: Chunk, initial: RangeConnection?) {
        var connection = initial
        var failures = 0
        chunk.downloader = Thread.currentThread()
        try {
            while (!chunk.isComplete && !chunk.cancelled && !closed) {
                try {
                    val filled = chunk.filledCount()
                    val current = connection ?: opener.open(chunk.start + filled, (chunk.length - filled).toLong())
                        .connection.also { connection = it }
                    val count = current.read(chunk.data, filled, chunk.length - filled)
                    if (count < 0) throw IOException("Connection ended early at ${chunk.start + filled}")
                    chunk.advance(count)
                    downloadedBytes.addAndGet(count.toLong())
                } catch (e: IOException) {
                    connection?.let { runCatching { it.close() } }
                    connection = null
                    if (chunk.cancelled || closed) return
                    chunk.failures = ++failures
                    logger.warn { "Chunk at ${chunk.start} failed at ${chunk.filledCount()}/${chunk.length} (failure $failures): $e" }
                    if (!retryable(e) || failures > MAX_RETRIES) {
                        chunk.fail(e)
                        return
                    }
                }
            }
            val took = millisSince(chunk.scheduledAt)
            if (chunk.isComplete) {
                completedChunks.incrementAndGet()
                completedChunkBytes.addAndGet(chunk.length.toLong())
                completedChunkNanos.addAndGet(System.nanoTime() - chunk.scheduledAt)
            }
            if (chunk.isComplete && took >= SLOW_CHUNK_LOG_MILLIS) {
                logger.info { "Chunk at ${chunk.start} (${chunk.length} bytes) took ${took}ms, $failures failures" }
            }
        } catch (e: Exception) {
            chunk.fail(e as? IOException ?: IOException(e))
        } finally {
            chunk.downloader = null
            connection?.let { runCatching { it.close() } }
        }
    }

    companion object {
        const val MAX_RETRIES = 3

        /** 块大小最多翻几轮 (防移位溢出; 实际几轮就到 chunkSize 了). */
        private const val MAX_RAMP_ROUNDS = 16

        private val logger = logger<ParallelRangeReader>()

        private const val WAIT_SLICE_MILLIS = 1_000L
        private const val SLOW_WAIT_LOG_MILLIS = 5_000L
        private const val SLOW_CHUNK_LOG_MILLIS = 15_000L
        private const val SLOW_CLOSE_LOG_MILLIS = 1_000L
        private const val STACK_DEPTH = 12

        private fun millisSince(nanos: Long) = (System.nanoTime() - nanos) / 1_000_000
    }
}
