/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.cache.engine

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.torrent.api.peer.PeerInfo
import me.him188.ani.app.torrent.api.TorrentHandleState
import me.him188.ani.app.torrent.api.TorrentSession
import me.him188.ani.app.torrent.api.files.FilePriority
import me.him188.ani.app.torrent.api.files.TorrentFileEntry
import me.him188.ani.app.torrent.api.files.TorrentFileHandle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 种子信息晚到 (建缓存时等了一阵没等到, 后台接着等) 时的 [TorrentFileStartup]: 等到以后照最后一次继续 / 暂停办,
 * 等的时候关掉 (删除) 的不再打开文件. 安卓上读文件列表取消不掉, 关掉以后结果照样会来, 这里直接喂结果.
 */
class TorrentFileStartupTest {
    private val session = RecordingSession()

    @Test
    fun `a late file follows the resume asked while waiting`() = runTest {
        val startup = TorrentFileStartup()
        startup.resume()
        assertNull(startup.started)

        val handle = RecordingHandle()
        startup.settle(started(handle))
        assertEquals(listOf("resume NORMAL"), handle.calls)
        assertSame(handle, startup.started?.handle)
    }

    @Test
    fun `a late file stays paused when the last request was pause`() = runTest {
        val startup = TorrentFileStartup()
        startup.resume()
        startup.pause()

        val handle = RecordingHandle()
        startup.settle(started(handle))
        assertEquals(listOf("pause"), handle.calls)
    }

    @Test
    fun `a late file is left alone when nobody asked yet`() = runTest {
        val startup = TorrentFileStartup()
        val handle = RecordingHandle()
        startup.settle(started(handle))
        assertEquals(emptyList(), handle.calls)

        startup.resume()
        assertEquals(listOf("resume NORMAL"), handle.calls)
    }

    @Test
    fun `a file that arrives after close is closed and never resumed`() = runTest {
        val startup = TorrentFileStartup()
        startup.resume()
        startup.close()

        val handle = RecordingHandle()
        startup.settle(started(handle))
        assertEquals(listOf("close"), handle.calls)
        assertIs<TorrentFileStartup.Phase.AwaitingMetadata>(startup.phase.value)
        assertNull(startup.started)
    }

    @Test
    fun `a torrent without the episode that arrives after close releases its session`() = runTest {
        val startup = TorrentFileStartup()
        startup.close()

        startup.settle(TorrentFileStartup.Phase.Started(TorrentMediaCacheEngine.FileHandle.State(session, null, null)))
        assertTrue(session.closedIfNotInUse)
    }

    @Test
    fun `a failure after the wait marks the download failed`() = runTest {
        val startup = TorrentFileStartup()
        startup.settle(TorrentFileStartup.Phase.Failed(IllegalStateException("remote died")))
        assertIs<TorrentFileStartup.Phase.Failed>(startup.phase.value)
        assertFalse(session.closedIfNotInUse)
    }

    @Test
    fun `the wait ends once as abandoned when closed before the torrent info arrives`() = runTest {
        val startup = TorrentFileStartup()
        val ends = mutableListOf<Boolean>()
        startup.onWaitEnded = { ends += it }
        startup.close()
        startup.close()
        startup.settle(started(RecordingHandle()))
        assertEquals(listOf(true), ends)
    }

    @Test
    fun `the wait ends once as settled when the torrent info arrives first`() = runTest {
        val startup = TorrentFileStartup()
        val ends = mutableListOf<Boolean>()
        startup.onWaitEnded = { ends += it }
        startup.settle(started(RecordingHandle()))
        startup.close()
        assertEquals(listOf(false), ends)
    }

    @Test
    fun `a startup that never waited does not report the wait`() = runTest {
        val startup = TorrentFileStartup(TorrentFileStartup.Phase.Failed(null))
        val ends = mutableListOf<Boolean>()
        startup.onWaitEnded = { ends += it }
        startup.close()
        assertEquals(emptyList(), ends)
    }

    @Test
    fun `the last abandoned waiter of a torrent gets its session to close`() = runTest {
        val waiters = TorrentSessionWaiters()
        waiters.begin("t")
        waiters.begin("t")
        assertTrue(waiters.sessionStarted("t", session))
        assertNull(waiters.end("t", abandoned = true), "another cache still waits")
        assertSame(session, waiters.end("t", abandoned = true))
    }

    @Test
    fun `a torrent whose info arrived is not closed by the waiters`() = runTest {
        val waiters = TorrentSessionWaiters()
        waiters.begin("t")
        assertTrue(waiters.sessionStarted("t", session))
        assertNull(waiters.end("t", abandoned = false))
    }

    @Test
    fun `a session that starts after its only waiter gave up is closed by the caller`() = runTest {
        val waiters = TorrentSessionWaiters()
        waiters.begin("t")
        assertNull(waiters.end("t", abandoned = true))
        assertFalse(waiters.sessionStarted("t", session))
    }

    private fun started(handle: TorrentFileHandle) =
        TorrentFileStartup.Phase.Started(TorrentMediaCacheEngine.FileHandle.State(session, null, handle))

    private class RecordingHandle : TorrentFileHandle {
        val calls = mutableListOf<String>()
        override val entry: TorrentFileEntry get() = error("unexpected entry")
        override fun resume(priority: FilePriority) {
            calls += "resume $priority"
        }

        override fun pause() {
            calls += "pause"
        }

        override fun setPrefetchRange(byteRange: LongRange?) = error("unexpected setPrefetchRange()")
        override suspend fun close() {
            calls += "close"
        }

        override suspend fun closeAndDelete() = error("unexpected closeAndDelete()")
        override suspend fun closeAndDiscard(filesInUse: Set<String>) = error("unexpected closeAndDiscard()")
    }

    private class RecordingSession : TorrentSession {
        var closedIfNotInUse = false
        override val sessionStats: Flow<TorrentSession.Stats?> get() = error("unexpected sessionStats")
        override suspend fun getName(): String = error("unexpected getName()")
        override suspend fun getFiles(): List<TorrentFileEntry> = error("unexpected getFiles()")
        override fun getPeers(): List<PeerInfo> = error("unexpected getPeers()")
        override fun getState(): TorrentHandleState? = error("unexpected getState()")
        override suspend fun close() = error("unexpected close()")
        override suspend fun closeIfNotInUse() {
            closedIfNotInUse = true
        }
    }
}
