/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [tmdbBrokenRenditionFallbackUrl]: TMDB 图床节点缓存的坏图换一档重取. 数字取自图床实测:
 * 坏图 5684 字节 (带 [TMDB_CDN_PROCESSING_ERROR_HEADER]); 带这个头的正常图 185649 字节; 不带头的浅色正常图 12235 字节.
 */
class TmdbBrokenRenditionTest {
    @Test
    fun `坏掉的 w1280 换 w780`() {
        assertEquals(
            "https://image.tmdb.org/t/p/w780/56APRYRS4NdgrezZNnV6csySWC0.jpg",
            tmdbBrokenRenditionFallbackUrl("https://image.tmdb.org/t/p/w1280/56APRYRS4NdgrezZNnV6csySWC0.jpg", "104", 5684),
        )
    }

    @Test
    fun `其余缩放档换 w1280`() {
        assertEquals(
            "https://image.tmdb.org/t/p/w1280/a.jpg",
            tmdbBrokenRenditionFallbackUrl("https://image.tmdb.org/t/p/w780/a.jpg", "104", 2500),
        )
    }

    @Test
    fun `只有响应头不算坏图`() {
        assertNull(tmdbBrokenRenditionFallbackUrl("https://image.tmdb.org/t/p/w1280/a.jpg", "104", 185649))
    }

    @Test
    fun `只是体积小不算坏图`() {
        assertNull(tmdbBrokenRenditionFallbackUrl("https://image.tmdb.org/t/p/w1280/a.jpg", null, 12235))
        assertNull(tmdbBrokenRenditionFallbackUrl("https://image.tmdb.org/t/p/w1280/a.jpg", null, 5684))
    }

    @Test
    fun `不知道体积时不换`() {
        assertNull(tmdbBrokenRenditionFallbackUrl("https://image.tmdb.org/t/p/w1280/a.jpg", "104", -1))
    }

    @Test
    fun `原图档与别的图床不换`() {
        assertNull(tmdbBrokenRenditionFallbackUrl("https://image.tmdb.org/t/p/original/a.jpg", "104", 5684))
        assertNull(tmdbBrokenRenditionFallbackUrl("https://lain.bgm.tv/pic/cover/l/a.jpg", "104", 5684))
    }
}
