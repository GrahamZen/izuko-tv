/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.cache.engine

import me.him188.ani.app.platform.PlaybackRequestHints
import me.him188.ani.utils.httpdownloader.DownloadOptions
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 缓存下载照解析器的并发提示开连接 (夸克非会员每个连接限速), 提示本身不发给服务器.
 */
class ParallelRangeHintTest {
    @Test
    fun `hint sets the segment concurrency and all hints are removed from request headers`() {
        val options = DownloadOptions(
            headers = mapOf(
                "Cookie" to "c",
                PlaybackRequestHints.PARALLEL_RANGE_HEADER to "16",
                PlaybackRequestHints.CACHE_KEY_HEADER to "quark:f1",
            ),
        )
        val adjusted = options.withParallelRangeHint()
        assertEquals(16, adjusted.maxConcurrentSegments)
        assertEquals(mapOf("Cookie" to "c"), adjusted.headers)
    }

    @Test
    fun `without a hint the options are unchanged`() {
        val options = DownloadOptions(headers = mapOf("Cookie" to "c"), maxConcurrentSegments = 5)
        assertEquals(options, options.withParallelRangeHint())
    }

    @Test
    fun `an unusable hint keeps the default concurrency but is still removed`() {
        val options = DownloadOptions(headers = mapOf(PlaybackRequestHints.PARALLEL_RANGE_HEADER to "x"))
        val adjusted = options.withParallelRangeHint()
        assertEquals(DownloadOptions().maxConcurrentSegments, adjusted.maxConcurrentSegments)
        assertEquals(emptyMap(), adjusted.headers)
    }
}
