/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.torrent.anitorrent

import kotlinx.coroutines.test.runTest
import me.him188.ani.app.torrent.anitorrent.session.AnitorrentDownloadSession
import me.him188.ani.app.torrent.anitorrent.test.TestAnitorrentTorrentDownloader
import me.him188.ani.app.torrent.anitorrent.test.TestTorrentDescriptor
import me.him188.ani.app.torrent.anitorrent.test.TestTorrentFileInfo
import me.him188.ani.app.torrent.api.HttpFileDownloader
import me.him188.ani.utils.io.inSystem
import me.him188.ani.utils.io.toKtPath
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * 种子删掉以后原生事件里句柄已经失效时按名字找被删的会话 ([AnitorrentTorrentDownloader.sessionRemovedByName]):
 * 在原生事件的线程上找, 不能等还没拿到种子信息的会话 (以前挨个等名字, 有一个没人做种的磁力链接就把事件循环永远卡住).
 */
class TorrentRemovedByNameTest {
    private object NoHttp : HttpFileDownloader {
        override suspend fun download(url: String): ByteArray = error("unexpected download $url")
        override fun close() {}
    }

    private fun magnet(name: String) =
        AnitorrentAddTorrentInfo.encode(AnitorrentAddTorrentInfo(AnitorrentTorrentData.MagnetUri("magnet:?xt=urn:btih:$name")))

    private fun descriptor(name: String) = TestTorrentDescriptor(name, 1000, 1024, 1024).apply {
        files.add(TestTorrentFileInfo("$name.mkv", "$name.mkv", 1024_000))
    }

    @Test
    fun `a removed torrent is found by name while another torrent still has no info`() = runTest {
        val downloader = TestAnitorrentTorrentDownloader(
            createTempDirectory("removed-by-name").toKtPath().inSystem, NoHttp, backgroundScope.coroutineContext,
        )
        // 先开的这个一直拿不到种子信息 (没人做种的磁力链接)
        downloader.native.descriptor = { descriptor("waiting") }
        downloader.startDownload(magnet("waiting"), backgroundScope.coroutineContext)
        downloader.native.descriptor = { descriptor("removed") }
        val removed = downloader.startDownload(magnet("removed"), backgroundScope.coroutineContext) as AnitorrentDownloadSession
        downloader.dispatchToSession1(removed.handleId) { it.onTorrentChecked() }

        assertSame(removed, downloader.sessionRemovedByName("removed"))
        assertNull(downloader.sessionRemovedByName("waiting"), "名字还不知道的不认, 也不等")
    }
}
