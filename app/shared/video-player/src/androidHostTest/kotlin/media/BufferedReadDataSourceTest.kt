/*
 * Copyright (C) 2026 OpenAni and contributors.
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
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.ByteArrayOutputStream
import kotlin.math.min
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@AndroidxOptIn(UnstableApi::class)
class BufferedReadDataSourceTest {
    private val content = Random(1).nextBytes(10_000)

    /** 依次交出 [content], 记下每次被要的长度 */
    private class FakeUpstream(private val content: ByteArray) : DataSource {
        var position = 0
        val requested = mutableListOf<Int>()

        override fun addTransferListener(transferListener: TransferListener) {}
        override fun open(dataSpec: DataSpec): Long = (content.size - position).toLong()
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            requested += length
            if (position == content.size) return C.RESULT_END_OF_INPUT
            val n = min(length, content.size - position)
            content.copyInto(buffer, offset, position, position + n)
            position += n
            return n
        }

        override fun getUri(): Uri? = null
        override fun close() {}
    }

    /** 按 [sizes] 轮流的长度一直读到末尾 */
    private fun readAll(source: DataSource, sizes: List<Int>): ByteArray {
        val out = ByteArrayOutputStream()
        val target = ByteArray(sizes.max())
        var i = 0
        while (true) {
            val n = source.read(target, 0, sizes[i++ % sizes.size])
            if (n == C.RESULT_END_OF_INPUT) return out.toByteArray()
            out.write(target, 0, n)
        }
    }

    @Test
    fun `single-byte reads go to upstream once per buffer`() {
        val upstream = FakeUpstream(content)
        val source = BufferedReadDataSource(upstream, bufferSize = 1024)
        assertContentEquals(content, readAll(source, listOf(1)))
        // 10 次填满缓冲 (最后一次不满) + 1 次读到末尾
        assertEquals(List(10) { 1024 } + 1024, upstream.requested)
    }

    @Test
    fun `mixed reads return the same bytes and large reads bypass the buffer`() {
        val upstream = FakeUpstream(content)
        val source = BufferedReadDataSource(upstream, bufferSize = 1024)
        assertContentEquals(content, readAll(source, listOf(1, 3, 2000, 7, 1024, 500)))
        assertTrue(upstream.requested.all { it >= 1024 }, "${upstream.requested}")
    }

    @Test
    fun `close drops buffered bytes`() {
        val upstream = FakeUpstream(content)
        val source = BufferedReadDataSource(upstream, bufferSize = 1024)
        val target = ByteArray(10)
        source.read(target, 0, 10)
        source.close()
        // 重新打开到别处
        upstream.position = 5000
        assertEquals(10, source.read(target, 0, 10))
        assertContentEquals(content.copyOfRange(5000, 5010), target)
    }
}
