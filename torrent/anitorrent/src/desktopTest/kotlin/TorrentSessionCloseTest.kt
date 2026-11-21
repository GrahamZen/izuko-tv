/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.torrent.anitorrent

import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.torrent.anitorrent.session.AnitorrentDownloadSession
import me.him188.ani.app.torrent.anitorrent.session.TorrentSessionClosedException
import me.him188.ani.app.torrent.anitorrent.test.TestAnitorrentTorrentDownloader
import me.him188.ani.app.torrent.api.HttpFileDownloader
import me.him188.ani.utils.io.inSystem
import me.him188.ani.utils.io.toKtPath
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * 会话关掉时 (删除、没人再等它的种子信息) 不留下等不完的东西: 等种子信息的调用方收到出错; 同一个种子再开时等上一个关完, 开一个新的.
 */
class TorrentSessionCloseTest {
    private object NoHttp : HttpFileDownloader {
        override suspend fun download(url: String): ByteArray = error("unexpected download $url")
        override fun close() {}
    }

    private fun magnet(name: String) =
        AnitorrentAddTorrentInfo.encode(AnitorrentAddTorrentInfo(AnitorrentTorrentData.MagnetUri("magnet:?xt=urn:btih:$name")))

    private fun TestScope.downloader() = TestAnitorrentTorrentDownloader(
        createTempDirectory("session-close").toKtPath().inSystem, NoHttp, backgroundScope.coroutineContext,
    )

    @Test
    fun `waiting for the files of a session that closes before its info arrives fails`() = runTest {
        val downloader = downloader()
        val session = downloader.startDownload(magnet("no-seeders"), backgroundScope.coroutineContext)
        val files = async { runCatching { session.getFiles() } }
        runCurrent()
        assertFalse(files.isCompleted, "种子信息没来, 一直等")

        // 原生层没发移除事件: 关闭等满 7.5 秒后强制算关完
        launch { session.close() }
        advanceTimeBy(8.seconds)
        runCurrent()
        assertTrue(files.isCompleted)
        assertTrue(files.await().exceptionOrNull() is TorrentSessionClosedException)
    }

    @Test
    fun `reopening a torrent that is still closing waits and starts a new session`() = runTest {
        val downloader = downloader()
        val first = downloader.startDownload(magnet("same"), backgroundScope.coroutineContext) as AnitorrentDownloadSession
        launch { first.close() }
        runCurrent()
        assertTrue(first.isClosing)

        val reopened = async { downloader.startDownload(magnet("same"), backgroundScope.coroutineContext) }
        runCurrent()
        assertFalse(reopened.isCompleted, "上一个会话还在关")

        downloader.dispatchToSession1(first.handleId) { it.onTorrentRemoved() }
        runCurrent()
        val second = reopened.await()
        assertNotSame<Any>(first, second)
        assertFalse((second as AnitorrentDownloadSession).isClosing)
    }

    @Test
    fun `a removal without a name goes to the only closing session that has no info`() = runTest {
        val downloader = downloader()
        downloader.startDownload(magnet("still-waiting"), backgroundScope.coroutineContext)
        val closing = downloader.startDownload(magnet("closing"), backgroundScope.coroutineContext) as AnitorrentDownloadSession
        launch { closing.close() }
        runCurrent()

        // 没拿到种子信息的种子删掉时, 原生事件里的名字对不上任何会话
        assertSame(closing, downloader.sessionRemovedByName("unknown"))
    }
}
