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
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.SimpleCache
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 在线播放时存在本机的数据 ([PlaybackDiskCache]): 读过的部分再读不走网络, 落盘不会把已删的文件建回来.
 * 要 SQLite, 所以是设备测试.
 */
@AndroidxOptIn(UnstableApi::class)
class PlaybackDiskCacheTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val databaseProvider = StandaloneDatabaseProvider(context)
    private val directory = File(context.cacheDir, "playback-cache-test-${System.nanoTime()}")
    private val cache: SimpleCache = PlaybackDiskCache.create(directory, 64L * 1024 * 1024, databaseProvider)

    private val uri = Uri.parse("https://example.test/video")

    @AfterTest
    fun tearDown() {
        cache.release()
        SimpleCache.delete(directory, databaseProvider)
    }

    /** 读 [content] 的上游, 记下被打开了几次. */
    private class CountingUpstream(private val content: ByteArray) : DataSource.Factory {
        var opens = 0

        override fun createDataSource(): DataSource {
            val delegate = ByteArrayDataSource(content)
            return object : DataSource by delegate {
                override fun open(dataSpec: DataSpec): Long {
                    opens++
                    return delegate.open(dataSpec)
                }
            }
        }
    }

    private fun read(source: DataSource, position: Long, length: Long = C.LENGTH_UNSET.toLong(), readBytes: Int? = null): ByteArray {
        source.open(
            DataSpec.Builder()
                .setUri(uri)
                .setKey(KEY)
                .setPosition(position)
                .setLength(length)
                .setFlags(DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION)
                .build(),
        )
        try {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (readBytes == null || out.size() < readBytes) {
                val wanted = if (readBytes == null) buffer.size else minOf(buffer.size, readBytes - out.size())
                val count = source.read(buffer, 0, wanted)
                if (count == C.RESULT_END_OF_INPUT) break
                out.write(buffer, 0, count)
            }
            return out.toByteArray()
        } finally {
            source.close()
        }
    }

    @Test
    fun `data read once is read from the cache the next time`() {
        val content = Random(1).nextBytes(10 * 1024 * 1024)
        val upstream = CountingUpstream(content)
        val factory = PlaybackDiskCache.cacheDataSourceFactory(cache, upstream)

        assertContentEquals(content, read(factory.createDataSource(), 0))
        assertEquals(1, upstream.opens)
        assertEquals(content.size.toLong(), ContentMetadata.getContentLength(cache.getContentMetadata(KEY)))

        // 往回拖: 整段都在盘上, 不再打开上游
        assertContentEquals(content.copyOfRange(3_000_000, content.size), read(factory.createDataSource(), 3_000_000))
        assertEquals(1, upstream.opens)
    }

    @Test
    fun `sync works on existing files only`() {
        val file = File(context.cacheDir, "playback-cache-sync-${System.nanoTime()}")
        file.writeBytes(ByteArray(1024))
        try {
            assertTrue(PlaybackDiskCache.syncFile(file))
        } finally {
            file.delete()
        }
        assertFalse(PlaybackDiskCache.syncFile(file))
        assertFalse(file.exists(), "syncing a deleted file must not create it again")
    }
}
