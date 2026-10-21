/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.torrent.anitorrent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import me.him188.ani.utils.io.SystemPath
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * BT 写盘与在线源同一套节奏: 每个文件写够一段就落盘, 落完才放开接收 (见 [TorrentWriteBack]).
 */
class TorrentWriteBackTest {
    private val synced = CopyOnWriteArrayList<String>()
    private val released = CopyOnWriteArrayList<String>()

    private fun writeBack(syncFile: (SystemPath) -> Boolean = { synced += it.toString(); true }) = TorrentWriteBack(
        CoroutineScope(Dispatchers.Unconfined),
        writeBackDone = { released += it },
        syncEveryBytes = 10,
        syncFile = syncFile,
        // Unconfined: 落盘在调用的线程上当场做完
        ioDispatcher = Dispatchers.Unconfined,
    )

    @Test
    fun `holds the intake once a file has another stretch written and releases it after the sync`() {
        val writeBack = writeBack()
        assertFalse(writeBack.onWritten("ep.mkv", 4))
        assertFalse(writeBack.onWritten("ep.mkv", 4))
        assertTrue(writeBack.onWritten("ep.mkv", 4))
        assertEquals(listOf("ep.mkv"), synced)
        assertEquals(listOf("ep.mkv"), released)

        // 每个文件各算各的
        assertFalse(writeBack.onWritten("other.mkv", 9))
        assertFalse(writeBack.onWritten("ep.mkv", 9))
        assertTrue(writeBack.onWritten("ep.mkv", 1))
        assertEquals(listOf("ep.mkv", "ep.mkv"), released)
    }

    @Test
    fun `a file still being synced is not synced again`() {
        val entered = CountDownLatch(1)
        val finish = CompletableDeferred<Unit>()
        val writeBack = TorrentWriteBack(
            CoroutineScope(Dispatchers.IO),
            writeBackDone = { released += it },
            syncEveryBytes = 10,
            syncFile = {
                entered.countDown()
                runBlocking { finish.await() }
                true
            },
        )
        assertTrue(writeBack.onWritten("ep.mkv", 10))
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        assertFalse(writeBack.onWritten("ep.mkv", 10), "already syncing, the intake is held anyway")
        assertTrue(writeBack.onWritten("other.mkv", 10))
        finish.complete(Unit)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (released.size < 2 && System.nanoTime() < deadline) Thread.sleep(10)
        assertEquals(setOf("ep.mkv", "other.mkv"), released.toSet())
    }

    @Test
    fun `the intake is released even when the sync fails or throws`() {
        assertTrue(writeBack(syncFile = { false }).onWritten("missing.mkv", 10))
        assertTrue(writeBack(syncFile = { error("disk gone") }).onWritten("broken.mkv", 10))
        assertEquals(listOf("missing.mkv", "broken.mkv"), released)
    }
}
