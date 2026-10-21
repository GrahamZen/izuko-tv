/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.torrent.anitorrent.session

import kotlinx.coroutines.runBlocking
import me.him188.ani.app.torrent.anitorrent.test.TestTorrentHandle
import me.him188.ani.utils.io.inSystem
import me.him188.ani.utils.io.toKtPath
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TorrentFileDiscardTest {
    private val fastResume = File(createTempDirectory("discard").toFile(), "fastresume").apply { writeBytes(byteArrayOf(1)) }
    private val fastResumePath = fastResume.toKtPath().inSystem

    @Test
    fun `the resume data the native side deleted is written again after discarding`() = runBlocking {
        val events = mutableListOf<String>()
        val handle = object : TestTorrentHandle({ _, _ -> }) {
            override fun discardFile(index: Int, fastResumePath: String): Boolean {
                events += "discard $index ${File(fastResumePath) == fastResume}"
                return super.discardFile(index, fastResumePath)
            }
        }
        val discard = TorrentFileDiscard(handle, fastResumePath) {
            events += "save fastresume=${fastResume.exists()}"
        }

        assertTrue(discard.discard(1))
        assertEquals(listOf("discard 1 true", "save fastresume=false"), events)
    }

    @Test
    fun `nothing to discard leaves the resume data alone`() = runBlocking {
        var saves = 0
        val handle = TestTorrentHandle({ _, _ -> }).apply { discardable = { false } }
        val discard = TorrentFileDiscard(handle, fastResumePath) { saves++ }

        assertFalse(discard.discard(1))
        assertTrue(fastResume.exists())
        assertEquals(0, saves)
        assertEquals(emptyList(), handle.discardedFiles)
    }

    @Test
    fun `a refused discard does not wait for resume data`() = runBlocking {
        var saves = 0
        val handle = object : TestTorrentHandle({ _, _ -> }) {
            override fun discardFile(index: Int, fastResumePath: String): Boolean = false
        }
        val discard = TorrentFileDiscard(handle, fastResumePath) { saves++ }

        assertFalse(discard.discard(1))
        assertEquals(0, saves)
    }
}
