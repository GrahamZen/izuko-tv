/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.media

import java.io.Closeable
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.Executor

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
 * 把一段范围切成 [chunkSize] 大的块, 同时最多 [connections] 块在下, 按顺序交给读的一方.
 *
 * 读的一方可以读一块里已经到了的部分, 不用等整块下完, 所以开播不比单连接慢.
 * 一块下到一半断了就从断的地方重新请求, 连续失败 [MAX_RETRIES] 次才把错误交给读的一方.
 *
 * [open] / [read] / [close] 只能在同一个线程上调用 (播放器的加载线程); 各块在 [executor] 上下载.
 */
internal class ParallelRangeReader(
    private val opener: RangeOpener,
    private val executor: Executor,
    private val connections: Int,
    private val chunkSize: Int,
) {
    private class Chunk(val start: Long, val length: Int, val data: ByteArray) {
        private val lock = Object()
        private var filled = 0
        private var error: IOException? = null

        @Volatile
        var cancelled = false
            private set

        /** 正在用的连接, 取消时关掉它好让下载线程从阻塞的读里出来. */
        @Volatile
        var connection: RangeConnection? = null

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

        fun cancel() {
            synchronized(lock) {
                cancelled = true
                lock.notifyAll()
            }
            runCatching { connection?.close() }
        }

        /** 等到 [offset] 之后有数据可读, 返回可读的字节数. */
        fun awaitAvailable(offset: Int): Int = synchronized(lock) {
            while (filled <= offset && error == null && !cancelled) {
                try {
                    lock.wait()
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw InterruptedIOException("Interrupted while waiting for data")
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

    @Volatile
    private var closed = false

    /** [open] 之后: 是否在分块并发下载 (请求本身小、或服务端不支持范围请求时为 false). */
    val isParallel: Boolean get() = direct == null

    /**
     * 打开 `[position, position + length)` ([length] 为 -1 表示到资源末尾), 返回要读的字节数, 不知道时为 -1.
     */
    fun open(position: Long, length: Long): Long {
        if (length in 0..chunkSize.toLong()) {
            direct = opener.open(position, length).connection
            return length
        }
        val firstLength = if (length < 0) chunkSize.toLong() else minOf(length, chunkSize.toLong())
        val first = opener.open(position, firstLength)
        if (first.totalLength < 0) {
            // 服务端没按范围回 (整个文件都在这个连接里): 不能分块, 换成直接读
            runCatching { first.connection.close() }
            direct = opener.open(position, length).connection
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
        val available = chunk.awaitAvailable(readOffset)
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

    fun close() {
        closed = true
        direct?.let { runCatching { it.close() } }
        direct = null
        window.forEach { it.cancel() }
        window.clear()
        freeBuffers.clear()
    }

    private fun schedule(initial: RangeConnection?) {
        val length = minOf(chunkSize.toLong(), end - nextStart).toInt()
        val data = freeBuffers.removeFirstOrNull() ?: ByteArray(chunkSize)
        val chunk = Chunk(nextStart, length, data)
        nextStart += length
        window.addLast(chunk)
        chunk.connection = initial
        executor.execute { download(chunk, initial) }
    }

    private fun download(chunk: Chunk, initial: RangeConnection?) {
        var connection = initial
        var failures = 0
        try {
            while (!chunk.isComplete && !chunk.cancelled && !closed) {
                try {
                    val filled = chunk.filledCount()
                    val current = connection ?: opener.open(chunk.start + filled, (chunk.length - filled).toLong())
                        .connection.also {
                            connection = it
                            chunk.connection = it
                        }
                    val count = current.read(chunk.data, filled, chunk.length - filled)
                    if (count < 0) throw IOException("Connection ended early at ${chunk.start + filled}")
                    chunk.advance(count)
                } catch (e: IOException) {
                    connection?.let { runCatching { it.close() } }
                    connection = null
                    if (chunk.cancelled || closed) return
                    if (++failures > MAX_RETRIES) {
                        chunk.fail(e)
                        return
                    }
                }
            }
        } catch (e: Exception) {
            chunk.fail(e as? IOException ?: IOException(e))
        } finally {
            connection?.let { runCatching { it.close() } }
        }
    }

    companion object {
        const val MAX_RETRIES = 3
    }
}
