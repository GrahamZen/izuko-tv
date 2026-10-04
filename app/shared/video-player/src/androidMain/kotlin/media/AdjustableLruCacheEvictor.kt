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
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import java.util.TreeSet

/**
 * 最久没读过的先删, 同 media3 的 LeastRecentlyUsedCacheEvictor, 只是上限 ([maxBytes]) 可以随时改: 改小之后调 [trim] 删到新上限以下,
 * 之后每存一段也按新上限删.
 *
 * 除 [maxBytes] 外的状态只在缓存的锁里改 (缓存回调本来就在锁里, [trim] 自己拿锁).
 */
@OptIn(UnstableApi::class)
internal class AdjustableLruCacheEvictor(maxBytes: Long) : CacheEvictor {
    @Volatile
    var maxBytes: Long = maxBytes

    private val leastRecentlyUsed = TreeSet<CacheSpan> { lhs, rhs ->
        if (lhs.lastTouchTimestamp == rhs.lastTouchTimestamp) lhs.compareTo(rhs) else lhs.lastTouchTimestamp.compareTo(rhs.lastTouchTimestamp)
    }
    private var currentSize = 0L

    override fun requiresCacheSpanTouches(): Boolean = true

    override fun onCacheInitialized() {}

    override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
        if (length != C.LENGTH_UNSET.toLong()) evictCache(cache, length)
    }

    override fun onSpanAdded(cache: Cache, span: CacheSpan) {
        leastRecentlyUsed.add(span)
        currentSize += span.length
        evictCache(cache, 0)
    }

    override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
        leastRecentlyUsed.remove(span)
        currentSize -= span.length
    }

    override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
        onSpanRemoved(cache, oldSpan)
        onSpanAdded(cache, newSpan)
    }

    /** 按此刻的 [maxBytes] 删到上限以下. 要等缓存初始化完、会删文件, 别在主线程上调. */
    fun trim(cache: Cache) {
        synchronized(cache) { evictCache(cache, 0) }
    }

    private fun evictCache(cache: Cache, requiredSpace: Long) {
        while (currentSize + requiredSpace > maxBytes && leastRecentlyUsed.isNotEmpty()) {
            cache.removeSpan(leastRecentlyUsed.first())
        }
    }
}
