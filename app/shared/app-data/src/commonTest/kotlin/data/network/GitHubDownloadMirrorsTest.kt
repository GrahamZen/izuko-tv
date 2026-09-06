/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.network

import me.him188.ani.app.data.network.GitHubDownloadMirrors.Companion.normalize
import me.him188.ani.app.data.network.GitHubDownloadMirrors.Companion.resolve
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GitHubDownloadMirrorsTest {
    private val origin = "https://github.com/GrahamZen/izuko-tv/releases/download/v1.0.2/izuko-1.0.2-armeabi-v7a.apk"

    @Test
    fun `prefix entries get the whole original url appended`() {
        val entry = normalize("gh-proxy.com/")!!
        assertEquals("https://gh-proxy.com", entry)
        assertEquals("https://gh-proxy.com/$origin", resolve(entry, origin))
    }

    @Test
    fun `template entries get the original url substituted`() {
        val entry = normalize("https://Example.com/dl?url={url}")!!
        assertEquals("https://example.com/dl?url={url}", entry)
        assertEquals("https://example.com/dl?url=$origin", resolve(entry, origin))
    }

    @Test
    fun `unusable entries are dropped`() {
        assertNull(normalize("ftp://gh-proxy.com"))
        assertNull(normalize("https://example.com/?a={url}&b={url}"))
        assertNull(normalize("https://example.com/?url=x"))
        assertNull(normalize("not a url"))
    }
}
