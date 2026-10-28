/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.platform

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 存下的本机 UA: 版本没变直接用; 系统或 WebView 更新后先用旧的 (起播时不现算), 只有没存过才现算.
 */
class StoredBrowserUserAgentTest {
    private val old = "Mozilla/5.0 (Linux; Android 11; BRAVIA) AppleWebKit/537.36 Chrome/150.0.0.0 Safari/537.36"
    private val v150 = "sony/bravia:11|com.google.android.webview|807000000"
    private val v156 = "sony/bravia:11|com.google.android.webview|807804700"

    @Test
    fun `same version uses the stored user agent`() {
        assertEquals(StoredBrowserUserAgent.Current(old), storedBrowserUserAgent(v150, old, v150))
    }

    @Test
    fun `after a WebView update the old user agent is used and refreshed later`() {
        assertEquals(StoredBrowserUserAgent.Outdated(old), storedBrowserUserAgent(v150, old, v156))
    }

    @Test
    fun `after a system update the old user agent is used and refreshed later`() {
        assertEquals(
            StoredBrowserUserAgent.Outdated(old),
            storedBrowserUserAgent(v150, old, "sony/bravia:12|com.google.android.webview|807000000"),
        )
    }

    @Test
    fun `nothing stored is computed now`() {
        assertEquals(StoredBrowserUserAgent.Missing, storedBrowserUserAgent(null, null, v156))
        assertEquals(StoredBrowserUserAgent.Missing, storedBrowserUserAgent(v150, " ", v156))
    }

    @Test
    fun `unknown current version is computed now`() {
        assertEquals(StoredBrowserUserAgent.Missing, storedBrowserUserAgent(v150, old, null))
    }
}
