/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.media

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 多连接分块读取: 内容与顺序不能错, 断线要续上, 服务端不支持范围请求时退回单连接.
 */
class ParallelRangeReaderTest {
    private val executor = Executors.newCachedThreadPool()

    @AfterTest
    fun tearDown() {
        executor.shutdownNow()
    }

    private val content = ByteArray(10_500) { (it * 31 + it / 7).toByte() }

    /**
     * 假的服务端: 每次读最多给 [perRead] 个字节, 读之前稍等一下好让几个连接真的并发.
     *
     * @param failAt 读到这些绝对位置时断线一次 (每个位置只断一次)
     * @param alwaysFailFrom 从这个位置开始每次请求都失败
     */
    private inner class FakeServer(
        val supportsRange: Boolean = true,
        val failAt: MutableSet<Long> = mutableSetOf(),
        val alwaysFailFrom: Long = Long.MAX_VALUE,
        val perRead: Int = 300,
        /** 前这么多次打开连不上 (像整组节点都不通) */
        val failingOpens: Int = 0,
        /** 打开时回这个错误 (例如 HTTP 403), 每次都回 */
        val rejectWith: IOException? = null,
        /** 范围越过文件末尾时不按范围回, 回整个文件 (像夸克的 pds 节点); 数据源得先读掉前面的才到要的位置, 读掉的记进 [skipped] */
        val ignoresRangePastEnd: Boolean = false,
    ) : RangeOpener {
        val opened = mutableListOf<Pair<Long, Long>>()
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        val served = AtomicLong()
        val skipped = AtomicLong()

        /** 在别的线程正读着时被关的次数 (OkHttp 这时会抛错、连接关不掉) */
        val closedWhileReading = AtomicInteger()

        override fun open(start: Long, length: Long): OpenedRange {
            val count = synchronized(opened) {
                opened += start to length
                opened.size
            }
            rejectWith?.let { throw it }
            if (count <= failingOpens) throw IOException("connect timed out ($count)")
            if (start >= alwaysFailFrom) throw IOException("server error at $start")
            val pastEnd = ignoresRangePastEnd && length >= 0 && start + length > content.size
            // 同播放器的 HTTP 数据源: 从 0 开始又不封口时不带 Range 头, 服务端回整个文件、不给总长
            val noRangeHeader = start == 0L && length < 0
            val ranged = supportsRange && !pastEnd && !noRangeHeader
            if (pastEnd) skipped.addAndGet(start)
            val from = if (ranged || pastEnd) start else 0L
            val until = if (!ranged || length < 0) content.size.toLong() else minOf(content.size.toLong(), start + length)
            maxActive.accumulateAndGet(active.incrementAndGet(), ::maxOf)
            val connection = object : RangeConnection {
                var position = from

                @Volatile
                var reading = false

                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (position >= until) return -1
                    reading = true
                    try {
                        Thread.sleep(1)
                        synchronized(failAt) {
                            val hit = failAt.firstOrNull { it in position until position + perRead }
                            if (hit != null) {
                                failAt.remove(hit)
                                throw IOException("connection reset at $hit")
                            }
                        }
                        val count = minOf(length, perRead, (until - position).toInt())
                        System.arraycopy(content, position.toInt(), buffer, offset, count)
                        position += count
                        served.addAndGet(count.toLong())
                        return count
                    } finally {
                        reading = false
                    }
                }

                override fun close() {
                    if (reading) {
                        closedWhileReading.incrementAndGet()
                        throw IllegalStateException("Unbalanced enter/exit")
                    }
                    active.decrementAndGet()
                }
            }
            return OpenedRange(connection, if (ranged) content.size.toLong() else -1)
        }
    }

    private fun reader(server: FakeServer, connections: Int = 4, chunkSize: Int = 1000) =
        ParallelRangeReader(server, executor, connections, chunkSize)

    private fun ParallelRangeReader.readAll(): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(700)
        while (true) {
            val count = read(buffer, 0, buffer.size)
            if (count < 0) break
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }

    @Test
    fun `reads the whole resource in order over several connections`() {
        val server = FakeServer()
        val reader = reader(server)
        assertEquals(content.size.toLong(), reader.open(0, -1))
        assertContentEquals(content, reader.readAll())
        reader.close()

        assertTrue(server.maxActive.get() in 2..4, "expected parallel connections, got ${server.maxActive.get()}")
        // 每块一次请求, 第一块就是打开时那次 (从头读时封口, 不然不带 Range 头、整个文件回来就分不了块)
        assertEquals(11, server.opened.size)
        assertEquals(0L to 1000L, server.opened.first())
    }

    @Test
    fun `chunks start small after opening and double each round up to the chunk size`() {
        val server = FakeServer()
        val reader = ParallelRangeReader(server, executor, connections = 2, chunkSize = 1000, firstChunkSize = 250)
        assertEquals(content.size.toLong(), reader.open(0, -1))
        assertContentEquals(content, reader.readAll())
        // 关之前取速度小结 (分块下载时才有)
        assertTrue(reader.throughputSummary(1000)?.startsWith("downloaded ") == true)
        reader.close()
        // 每轮 2 块: 250, 250, 500, 500, 然后都是 1000 (最后一块是剩下的)
        // 各块在不同线程上打开, 先后不定: 按位置排
        val lengths = server.opened.sortedBy { it.first }.map { it.second }
        assertEquals(listOf(250L, 250L, 500L, 500L, 1000L, 1000L), lengths.take(6))
        assertTrue(lengths.drop(4).dropLast(1).all { it == 1000L }, "later chunks use the full size: $lengths")

        // 跳到中间再打开 (同播放器跳转): 又从小块开始
        val again = FakeServer()
        val seeking = ParallelRangeReader(again, executor, connections = 2, chunkSize = 1000, firstChunkSize = 250)
        assertEquals(content.size - 5000L, seeking.open(5000, -1))
        assertContentEquals(content.copyOfRange(5000, content.size), seeking.readAll())
        seeking.close()
        // 不知道读到哪又不是从头时第一次请求不封口, 这一块只取 250; 第二块也是 250
        assertEquals(listOf(5000L to -1L, 5250L to 250L, 5500L to 500L), again.opened.sortedBy { it.first }.take(3))
    }

    @Test
    fun `closing mid-download stops the chunks and closes their connections on the download threads`() {
        // 每块 1000 字节, 每次读 10 字节, 4 块同时在下
        val server = FakeServer(perRead = 10)
        val reader = reader(server)
        reader.open(0, -1)
        reader.read(ByteArray(100), 0, 100)
        reader.close()

        val deadline = System.currentTimeMillis() + 5_000
        while (server.active.get() > 0 && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertEquals(0, server.active.get(), "every connection is closed")
        assertEquals(0, server.closedWhileReading.get(), "no connection is closed while another thread reads it")
        assertTrue(server.served.get() < 4 * 1000, "the chunks stop instead of finishing: served ${server.served.get()}")
    }

    @Test
    fun `reading near the end never asks for a range past the end`() {
        // 播放器跳到文件尾读索引, 只剩 300 字节, 比一块小
        val server = FakeServer(ignoresRangePastEnd = true)
        val reader = reader(server)
        assertEquals(300L, reader.open(content.size - 300L, -1))
        assertContentEquals(content.copyOfRange(content.size - 300, content.size), reader.readAll())
        reader.close()
        assertEquals(0L, server.skipped.get(), "no bytes skipped before the position")
    }

    @Test
    fun `reads a bounded range`() {
        val server = FakeServer()
        val reader = reader(server)
        assertEquals(3123L, reader.open(1000, 3123))
        assertContentEquals(content.copyOfRange(1000, 4123), reader.readAll())
        reader.close()
    }

    @Test
    fun `small request uses a single connection`() {
        val server = FakeServer()
        val reader = reader(server)
        assertEquals(500L, reader.open(10_000, 500))
        assertContentEquals(content.copyOfRange(10_000, 10_500), reader.readAll())
        reader.close()
        assertEquals(listOf(10_000L to 500L), server.opened)
    }

    @Test
    fun `falls back to one connection when the server ignores ranges`() {
        val server = FakeServer(supportsRange = false)
        val reader = reader(server)
        assertEquals(-1L, reader.open(0, -1))
        assertContentEquals(content, reader.readAll())
        reader.close()
        // 第一次分块请求被整个回了, 关掉后不分块重开一次
        assertEquals(listOf(0L to 1000L, 0L to -1L), server.opened)
    }

    @Test
    fun `resumes a chunk after the connection drops`() {
        val server = FakeServer(failAt = mutableSetOf(1500, 6200))
        val reader = reader(server)
        reader.open(0, -1)
        assertContentEquals(content, reader.readAll())
        reader.close()
        // 11 块各一次, 断的那两块各多一次 (从断的地方接着请求)
        assertEquals(13, server.opened.size, "${server.opened}")
        // 第二块读到 1300 时断了 (下一次读会越过 1500), 从 1300 接着要剩下的 700 字节
        assertTrue(1300L to 700L in server.opened, "${server.opened}")
    }

    @Test
    fun `persistent failure reaches the reader`() {
        val server = FakeServer(alwaysFailFrom = 3000)
        val reader = reader(server)
        reader.open(0, -1)
        val buffer = ByteArray(700)
        var read = 0
        val error = assertFailsWith<IOException> {
            while (true) {
                val count = reader.read(buffer, 0, buffer.size)
                if (count < 0) break
                read += count
            }
        }
        reader.close()
        assertEquals(3000, read)
        assertTrue(error.message.orEmpty().contains("3000"), error.message)
    }

    @Test
    fun `a first open that cannot connect is tried again`() {
        val server = FakeServer(failingOpens = 2)
        val reader = reader(server)
        assertEquals(content.size.toLong(), reader.open(0, -1))
        assertContentEquals(content, reader.readAll())
        reader.close()
        // 两次连不上 + 11 块各一次
        assertEquals(13, server.opened.size)
        assertEquals(listOf(0L to 1000L, 0L to 1000L, 0L to 1000L), server.opened.take(3))
    }

    private class Rejected : IOException("HTTP 403")

    @Test
    fun `errors that are not worth retrying fail right away`() {
        val server = FakeServer(rejectWith = Rejected())
        val reader = ParallelRangeReader(server, executor, connections = 4, chunkSize = 1000) { it !is Rejected }
        assertFailsWith<Rejected> { reader.open(0, -1) }
        reader.close()
        assertEquals(1, server.opened.size)
    }

    @Test
    fun `parses total length from content range`() {
        assertEquals(247580374L, ParallelRangeDataSource.totalLengthOf(mapOf("Content-Range" to listOf("bytes 0-4194303/247580374"))))
        assertEquals(-1L, ParallelRangeDataSource.totalLengthOf(mapOf("content-range" to listOf("bytes 0-10/*"))))
        assertEquals(-1L, ParallelRangeDataSource.totalLengthOf(emptyMap()))
    }
}
