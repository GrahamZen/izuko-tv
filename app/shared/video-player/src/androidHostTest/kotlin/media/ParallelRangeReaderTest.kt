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
    ) : RangeOpener {
        val opened = mutableListOf<Pair<Long, Long>>()
        private val active = AtomicInteger()
        val maxActive = AtomicInteger()

        override fun open(start: Long, length: Long): OpenedRange {
            synchronized(opened) { opened += start to length }
            if (start >= alwaysFailFrom) throw IOException("server error at $start")
            val from = if (supportsRange) start else 0L
            val until = if (!supportsRange || length < 0) content.size.toLong() else minOf(content.size.toLong(), start + length)
            maxActive.accumulateAndGet(active.incrementAndGet(), ::maxOf)
            val connection = object : RangeConnection {
                var position = from

                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (position >= until) return -1
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
                    return count
                }

                override fun close() {
                    active.decrementAndGet()
                }
            }
            return OpenedRange(connection, if (supportsRange) content.size.toLong() else -1)
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
        // 每块一次请求, 第一块就是打开时那次
        assertEquals(11, server.opened.size)
        assertEquals(0L to 1000L, server.opened.first())
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
    fun `parses total length from content range`() {
        assertEquals(247580374L, ParallelRangeDataSource.totalLengthOf(mapOf("Content-Range" to listOf("bytes 0-4194303/247580374"))))
        assertEquals(-1L, ParallelRangeDataSource.totalLengthOf(mapOf("content-range" to listOf("bytes 0-10/*"))))
        assertEquals(-1L, ParallelRangeDataSource.totalLengthOf(emptyMap()))
    }
}
