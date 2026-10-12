/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package io.github.grahamzen.izuko.installer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DownloadMirrorsTest {
    private val url = "https://github.com/GrahamZen/izuko-tv/releases/download/v1.0.5/izuko-1.0.5-arm64-v8a.apk"

    @Test
    fun `前缀写法 原地址整个接在后面`() {
        assertEquals("https://gh-proxy.com/$url", DownloadMirrors.resolve("https://gh-proxy.com", url))
    }

    @Test
    fun `模板写法 原地址换进占位符`() {
        assertEquals("https://example.org/get?u=$url", DownloadMirrors.resolve("https://example.org/get?u={url}", url))
    }

    @Test
    fun `归一化 补协议 去末尾斜杠`() {
        assertEquals("https://gh-proxy.com", DownloadMirrors.normalize("gh-proxy.com/"))
        assertEquals("https://ghfast.top", DownloadMirrors.normalize("  https://ghfast.top  "))
        assertEquals("https://example.org/get?u={url}", DownloadMirrors.normalize("https://example.org/get?u={url}"))
    }

    @Test
    fun `不像地址的丢掉`() {
        assertNull(DownloadMirrors.normalize(""))
        assertNull(DownloadMirrors.normalize("not a url"))
        assertNull(DownloadMirrors.normalize("ftp://example.org"))
        assertNull(DownloadMirrors.normalize("localhost"))
    }
}
