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
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import kotlin.math.min
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * [ResumingHttpDataSource]: 连接空久了、读到一半断了从断处重新请求; 一上来就坏的、被取消的、不知道该读到哪的照原样交出去.
 * 上游是按脚本断开的假连接 (在设备上跑: 宿主测试里建不出 [DataSpec] 要的 [Uri]).
 */
@AndroidxOptIn(UnstableApi::class)
class ResumingHttpDataSourceTest {
    private val file = Random(1).nextBytes(1000)
    private val uri = Uri.parse("https://example.com/video.mp4")
    private var now = 0L
    private var reconnects = 0

    /** 每次打开时这条连接怎么结束 */
    private sealed interface Ending {
        data object Complete : Ending

        /** 读到 [at] 就说读完了 (HTTP/2 断流时服务器把流正常收尾) */
        data class EndsAt(val at: Long) : Ending

        /** 读到 [at] 抛 [error] (连接被重置) */
        data class FailsAt(val at: Long, val error: IOException = IOException("Connection reset")) : Ending
    }

    /** 按 [endings] 一次次断开的连接, 记下每次打开的请求 */
    private class ScriptedConnection(
        private val file: ByteArray,
        endings: List<Ending>,
        private val reportsLength: Boolean = true,
        private val reportsContentRange: Boolean = false,
    ) : HttpDataSource {
        private val endings = ArrayDeque(endings)
        val opened = mutableListOf<DataSpec>()
        private var ending: Ending = Ending.Complete
        private var position = 0L
        private var limit = 0L

        override fun open(dataSpec: DataSpec): Long {
            opened += dataSpec
            ending = endings.removeFirstOrNull() ?: Ending.Complete
            position = dataSpec.position
            limit = if (dataSpec.length == C.LENGTH_UNSET.toLong()) file.size.toLong() else dataSpec.position + dataSpec.length
            return if (reportsLength) limit - position else C.LENGTH_UNSET.toLong()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val current = ending
            val stop = when (current) {
                Ending.Complete -> limit
                is Ending.EndsAt -> min(current.at, limit)
                is Ending.FailsAt -> min(current.at, limit)
            }
            if (position >= stop) {
                if (current is Ending.FailsAt && position < limit) throw current.error
                return C.RESULT_END_OF_INPUT
            }
            val n = min(length.toLong(), stop - position).toInt()
            file.copyInto(buffer, offset, position.toInt(), position.toInt() + n)
            position += n
            return n
        }

        override fun getResponseHeaders(): Map<String, List<String>> {
            val start = opened.lastOrNull()?.position ?: 0
            return if (reportsContentRange) mapOf("Content-Range" to listOf("bytes $start-${file.size - 1}/${file.size}")) else emptyMap()
        }

        override fun close() {}
        override fun getUri(): Uri? = opened.lastOrNull()?.uri
        override fun getResponseCode(): Int = 206
        override fun setRequestProperty(name: String, value: String) {}
        override fun clearRequestProperty(name: String) {}
        override fun clearAllRequestProperties() {}
        override fun addTransferListener(transferListener: TransferListener) {}
    }

    private fun resuming(connection: ScriptedConnection, enabled: Boolean = true) =
        ResumingHttpDataSource(connection, onReconnect = { reconnects++ }, enabled = { enabled }, clock = { now })

    /** 每次读 7 字节一直读到末尾; 读到 [pauseAfter] 字节时停 [pauseMillis] */
    private fun readAll(source: HttpDataSource, pauseAfter: Int = -1, pauseMillis: Long = 0): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(7)
        while (true) {
            if (out.size() == pauseAfter) now += pauseMillis
            val n = source.read(buffer, 0, buffer.size)
            if (n == C.RESULT_END_OF_INPUT) return out.toByteArray()
            out.write(buffer, 0, n)
        }
    }

    private fun ScriptedConnection.openedPositions() = opened.map { it.position }

    @Test
    fun `a stream that ends early is requested again from where it stopped`() {
        val connection = ScriptedConnection(file, listOf(Ending.EndsAt(400)))
        val source = resuming(connection)
        assertEquals(1000L, source.open(DataSpec(uri)))
        assertContentEquals(file, readAll(source))
        assertEquals(listOf(0L, 400L), connection.openedPositions())
        assertEquals(1, reconnects)
    }

    @Test
    fun `a read error after some data is requested again from where it stopped`() {
        val connection = ScriptedConnection(file, listOf(Ending.FailsAt(400)))
        val source = resuming(connection)
        source.open(DataSpec(uri))
        assertContentEquals(file, readAll(source))
        assertEquals(listOf(0L, 400L), connection.openedPositions())
    }

    @Test
    fun `an idle connection is replaced before reading on`() {
        val connection = ScriptedConnection(file, emptyList())
        val source = resuming(connection)
        source.open(DataSpec(uri))
        assertContentEquals(file, readAll(source, pauseAfter = 105, pauseMillis = ResumingHttpDataSource.IDLE_RECONNECT_MILLIS + 1000))
        assertEquals(listOf(0L, 105L), connection.openedPositions())
        assertEquals(1, reconnects)
    }

    @Test
    fun `a short pause keeps the connection`() {
        val connection = ScriptedConnection(file, emptyList())
        val source = resuming(connection)
        source.open(DataSpec(uri))
        assertContentEquals(file, readAll(source, pauseAfter = 105, pauseMillis = 10_000))
        assertEquals(listOf(0L), connection.openedPositions())
    }

    @Test
    fun `a failure before any data is passed on`() {
        val connection = ScriptedConnection(file, listOf(Ending.FailsAt(0)))
        val source = resuming(connection)
        source.open(DataSpec(uri))
        assertFailsWith<IOException> { readAll(source) }
        assertEquals(listOf(0L), connection.openedPositions())
    }

    @Test
    fun `cuts without progress give up after a few attempts`() {
        val connection = ScriptedConnection(file, List(10) { Ending.EndsAt(400) })
        val source = resuming(connection)
        source.open(DataSpec(uri))
        assertFailsWith<EOFException> { readAll(source) }
        assertEquals(listOf(0L) + List(ResumingHttpDataSource.MAX_RESUMES) { 400L }, connection.openedPositions())
    }

    @Test
    fun `progress between cuts keeps resuming`() {
        val endings = (1..9).map { Ending.EndsAt(it * 100L) }
        val connection = ScriptedConnection(file, endings)
        val source = resuming(connection)
        source.open(DataSpec(uri))
        assertContentEquals(file, readAll(source))
        assertEquals(List(10) { it * 100L }, connection.openedPositions())
    }

    @Test
    fun `an interrupted read is passed on`() {
        val connection = ScriptedConnection(file, listOf(Ending.FailsAt(400, InterruptedIOException())))
        val source = resuming(connection)
        source.open(DataSpec(uri))
        assertFailsWith<InterruptedIOException> { readAll(source) }
        assertEquals(listOf(0L), connection.openedPositions())
    }

    @Test
    fun `a stream of unknown length ends where the server stops`() {
        val connection = ScriptedConnection(file, listOf(Ending.EndsAt(400)), reportsLength = false)
        val source = resuming(connection)
        source.open(DataSpec(uri))
        assertEquals(400, readAll(source).size)
        assertEquals(listOf(0L), connection.openedPositions())
    }

    @Test
    fun `content range tells where a stream of unknown length should end`() {
        val connection = ScriptedConnection(file, listOf(Ending.EndsAt(400)), reportsLength = false, reportsContentRange = true)
        val source = resuming(connection)
        source.open(DataSpec(uri))
        assertContentEquals(file, readAll(source))
        assertEquals(listOf(0L, 400L), connection.openedPositions())
    }

    @Test
    fun `a bounded request resumes with the rest of its range`() {
        val connection = ScriptedConnection(file, listOf(Ending.EndsAt(300)))
        val source = resuming(connection)
        assertEquals(500L, source.open(DataSpec.Builder().setUri(uri).setPosition(100).setLength(500).build()))
        assertContentEquals(file.copyOfRange(100, 600), readAll(source))
        val resumed = connection.opened[1]
        assertEquals(300L, resumed.position)
        assertEquals(300L, resumed.length)
    }

    @Test
    fun `switched off it reads like the connection it wraps`() {
        val connection = ScriptedConnection(file, listOf(Ending.EndsAt(400)))
        val source = resuming(connection, enabled = false)
        source.open(DataSpec(uri))
        assertEquals(400, readAll(source, pauseAfter = 105, pauseMillis = ResumingHttpDataSource.IDLE_RECONNECT_MILLIS + 1000).size)
        assertEquals(listOf(0L), connection.openedPositions())
    }
}
