/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.link

import me.him188.ani.utils.coroutines.RejectionRefresh
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 播放地址被拒时换新的接着读 ([RefreshablePlaybackLink]).
 */
class RefreshablePlaybackLinkTest {
    private class Rejected(val status: Int) : Exception("HTTP $status")

    private var now = 0L
    private var refreshes = 0

    /** 假服务器: 只认 [valid] 里的地址, 别的回 403 */
    private val valid = mutableSetOf("https://dl.test/v?key=1")
    private val requested = mutableListOf<String>()

    private fun link(refresher: suspend () -> PlaybackLink?) =
        RefreshablePlaybackLink(PlaybackLink("https://dl.test/v?key=1", mapOf("Cookie" to "k=1")), nowMillis = { now }) {
            refreshes++
            refresher()
        }

    private fun RefreshablePlaybackLink.read(): String = requestBlocking({ (it as? Rejected)?.status }) { lease ->
        requested += lease.link.uri
        if (lease.link.uri !in valid) throw Rejected(403)
        lease.link.headers.getValue("Cookie")
    }

    private fun fresh(key: Int) = PlaybackLink("https://dl.test/v?key=$key", mapOf("Cookie" to "k=$key"))

    @Test
    fun `a link rejected after it worked is refreshed and the request goes on with the new one`() {
        val link = link { fresh(2) }
        assertEquals("k=1", link.read())
        // 暂停了很久: 原来的地址过期, 新地址能用
        valid.clear()
        valid += "https://dl.test/v?key=2"

        assertEquals("k=2", link.read())
        assertEquals(1, refreshes)
        assertEquals(listOf("https://dl.test/v?key=1", "https://dl.test/v?key=1", "https://dl.test/v?key=2"), requested)
        // 之后的请求直接用新地址
        assertEquals("k=2", link.read())
        assertEquals(1, refreshes)
    }

    @Test
    fun `a link rejected before it ever worked is reported as is`() {
        valid.clear()
        val link = link { fresh(2) }
        // 一上来就被拒不是过期: 不去换, 照旧报打开失败
        assertEquals(403, assertFailsWith<Rejected> { link.read() }.status)
        assertEquals(0, refreshes)
    }

    @Test
    fun `other failures are not taken as a rejected link`() {
        val link = link { fresh(2) }
        link.read()
        assertFailsWith<IllegalStateException> {
            link.requestBlocking({ (it as? Rejected)?.status }) { error("connection reset") }
        }
        assertEquals(0, refreshes)
    }

    @Test
    fun `without a fresh link the rejection is reported and it asks again only after the cooldown`() {
        val link = link { null }
        link.read()
        valid.clear()
        assertFailsWith<Rejected> { link.read() }
        assertFailsWith<Rejected> { link.read() }
        assertEquals(1, refreshes)
        now += RejectionRefresh.DEFAULT_COOLDOWN_MILLIS
        assertFailsWith<Rejected> { link.read() }
        assertEquals(2, refreshes)
    }

    @Test
    fun `a fresh link that serves another file is given up and later rejections are reported`() {
        val link = link { fresh(2) }
        link.read()
        // 原地址的响应说这个文件一共 1000 字节
        assertTrue(link.sameContent(1000))
        assertTrue(link.sameContent(1000))
        valid.clear()
        valid += "https://dl.test/v?key=2"
        link.read()

        // 换来的地址说文件是 2000 字节: 另一条线路的另一个文件, 接着按原位置读会读出对不上的数据
        assertFalse(link.sameContent(2000))
        assertTrue(link.mismatched)

        // 认出之后不再换, 被拒的错原样报出去 (播放器照它原来的办法整个重新加载)
        now += RejectionRefresh.DEFAULT_COOLDOWN_MILLIS
        valid.clear()
        assertFailsWith<Rejected> { link.read() }
        assertEquals(1, refreshes)
    }

    @Test
    fun `the registry gives the current address of a playing media`() {
        val original = "https://dl.test/v?key=1"
        val link = link { fresh(2) }
        assertNull(PlaybackLinks.of(original))
        assertEquals(original, PlaybackLinks.currentUriOf(original))

        PlaybackLinks.register(original, link)
        try {
            assertSame(link, PlaybackLinks.of(original))
            link.read()
            valid.clear()
            valid += "https://dl.test/v?key=2"
            link.read()
            // 分享、同步播放按原地址来查, 拿到的是换过的
            assertEquals("https://dl.test/v?key=2", PlaybackLinks.currentUriOf(original))
        } finally {
            PlaybackLinks.unregister(original, link)
        }
        assertNull(PlaybackLinks.of(original))
    }
}
