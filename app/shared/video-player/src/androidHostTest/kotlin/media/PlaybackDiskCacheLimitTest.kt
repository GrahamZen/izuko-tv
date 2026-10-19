/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.media

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheSpan
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(UnstableApi::class)
class PlaybackDiskCacheLimitTest {
    private val mib = 1024L * 1024
    private val gib = 1024 * mib

    @Test
    fun `limit is a quarter of free space plus what is stored`() {
        assertEquals(1 * gib, PlaybackDiskCache.budgetFor(freeBytes = 3 * gib, storedBytes = 1 * gib))
        assertEquals(512 * mib, PlaybackDiskCache.budgetFor(freeBytes = 2 * gib, storedBytes = 0))
    }

    @Test
    fun `limit is capped at 4 GiB`() {
        assertEquals(4 * gib, PlaybackDiskCache.budgetFor(freeBytes = 100 * gib, storedBytes = 3 * gib))
    }

    @Test
    fun `limit is zero when the share is under 256 MiB`() {
        assertEquals(0, PlaybackDiskCache.budgetFor(freeBytes = 900 * mib, storedBytes = 100 * mib - 1))
        assertEquals(256 * mib, PlaybackDiskCache.budgetFor(freeBytes = 900 * mib, storedBytes = 124 * mib))
    }

    @Test
    fun `lowering the limit evicts the least recently used spans`() {
        val evictor = AdjustableLruCacheEvictor(maxBytes = 1000)
        val cache = fakeCache(evictor)
        repeat(4) { evictor.onSpanAdded(cache, span(position = it * 100L, touched = it + 1L)) }
        assertEquals(emptyList(), removed)

        evictor.maxBytes = 250
        evictor.trim(cache)
        assertEquals(listOf(1L, 2L), removed.map { it.lastTouchTimestamp })

        // 新的一段开始写之前按新上限腾出位置
        evictor.onStartFile(cache, KEY, position = 400, length = 100)
        assertEquals(listOf(1L, 2L, 3L), removed.map { it.lastTouchTimestamp })
    }

    @Test
    fun `zero limit keeps nothing`() {
        val evictor = AdjustableLruCacheEvictor(maxBytes = 0)
        val cache = fakeCache(evictor)
        evictor.onSpanAdded(cache, span(position = 0, touched = 1))
        assertEquals(listOf(1L), removed.map { it.lastTouchTimestamp })
    }

    private val removed = mutableListOf<CacheSpan>()

    /** 只实现 removeSpan 的缓存: 删的时候照真缓存那样回调淘汰器. */
    private fun fakeCache(evictor: AdjustableLruCacheEvictor): Cache {
        lateinit var cache: Cache
        cache = Proxy.newProxyInstance(Cache::class.java.classLoader, arrayOf(Cache::class.java)) { _, method, args ->
            check(method.name == "removeSpan") { "unexpected call ${method.name}" }
            val span = args!![0] as CacheSpan
            removed += span
            evictor.onSpanRemoved(cache, span)
            null
        } as Cache
        return cache
    }

    private fun span(position: Long, touched: Long) = CacheSpan(KEY, position, 100, touched, null)

    private companion object {
        const val KEY = "key"
    }
}
