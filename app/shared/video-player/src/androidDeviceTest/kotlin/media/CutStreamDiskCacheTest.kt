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
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.SimpleCache
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.min
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * 断流与「边下边播」: 上游没到长度就说读完了时 `CacheDataSource` 把那个位置记成文件总长 ([PlaybackDiskCache.forgetContentLength] 清掉它);
 * 下面垫着 [ResumingHttpDataSource] 时断流到不了缓存那层.
 */
@AndroidxOptIn(UnstableApi::class)
class CutStreamDiskCacheTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val databaseProvider = StandaloneDatabaseProvider(context)
    private val directory = File(context.cacheDir, "cut-stream-cache-test-${System.nanoTime()}")
    private val cache: SimpleCache = PlaybackDiskCache.create(directory, 64L * 1024 * 1024, databaseProvider)
    private val uri = Uri.parse("https://example.test/video.mp4")
    private val key = uri.toString()
    private val content = Random(2).nextBytes(1000)

    @AfterTest
    fun tearDown() {
        cache.release()
        SimpleCache.delete(directory, databaseProvider)
    }

    /** 第一次打开读到 [cutAt] 就说读完了 (HTTP/2 断流), 之后照常; 打开时都说清剩多少 */
    private class CutOnceConnection(private val content: ByteArray, private val cutAt: Int) : HttpDataSource {
        private var opens = 0
        private var position = 0
        private var stop = 0

        override fun open(dataSpec: DataSpec): Long {
            position = dataSpec.position.toInt()
            val limit = if (dataSpec.length == C.LENGTH_UNSET.toLong()) content.size else position + dataSpec.length.toInt()
            stop = if (opens++ == 0) min(cutAt, limit) else limit
            return (limit - position).toLong()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (position >= stop) return C.RESULT_END_OF_INPUT
            val n = min(length, stop - position)
            content.copyInto(buffer, offset, position, position + n)
            position += n
            return n
        }

        override fun close() {}
        override fun getUri(): Uri? = null
        override fun getResponseHeaders(): Map<String, List<String>> = emptyMap()
        override fun getResponseCode(): Int = 206
        override fun setRequestProperty(name: String, value: String) {}
        override fun clearRequestProperty(name: String) {}
        override fun clearAllRequestProperties() {}
        override fun addTransferListener(transferListener: TransferListener) {}
    }

    private fun readAll(source: DataSource): ByteArray {
        source.open(DataSpec.Builder().setUri(uri).setFlags(DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION).build())
        try {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64)
            while (true) {
                val n = source.read(buffer, 0, buffer.size)
                if (n == C.RESULT_END_OF_INPUT) return out.toByteArray()
                out.write(buffer, 0, n)
            }
        } finally {
            source.close()
        }
    }

    @Test
    fun `an early end is stored as the length until it is forgotten`() {
        val connection = CutOnceConnection(content, cutAt = 400)
        val source = PlaybackDiskCache.cacheDataSourceFactory(cache) { connection }.createDataSource()

        assertEquals(400, readAll(source).size)
        assertEquals(400L, PlaybackDiskCache.contentLength(cache, key))
        // 再读也到那里就完
        assertEquals(400, readAll(source).size)

        PlaybackDiskCache.forgetContentLength(cache, key)
        assertContentEquals(content, readAll(source))
        assertEquals(1000L, PlaybackDiskCache.contentLength(cache, key))
    }

    @Test
    fun `with resuming underneath an early end never reaches the cache`() {
        val connection = CutOnceConnection(content, cutAt = 400)
        val source = PlaybackDiskCache.cacheDataSourceFactory(cache) {
            ResumingHttpDataSource(connection, enabled = { true })
        }.createDataSource()

        assertContentEquals(content, readAll(source))
        assertEquals(1000L, PlaybackDiskCache.contentLength(cache, key))
    }
}
