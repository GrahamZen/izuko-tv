/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.download

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.domain.media.cache.MediaCacheState
import me.him188.ani.app.domain.media.cache.engine.MediaCacheEngineKey
import me.him188.ani.app.domain.media.player.ActivePlayback
import me.him188.ani.app.domain.media.player.PlaybackActivity
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 正在播的那一集的 HTTP 缓存停着等播放结束, 不在播这一集了就接着下; 别的集与 BT 缓存不管.
 */
class MediaDownloadPlaybackYieldTest {
    private val player = Any()
    private val activity = PlaybackActivity()

    private fun TestScope.manager(storage: DownloadTestStorage): MediaDownloadManager =
        MediaDownloadManager(listOf(storage), backgroundScope, activity).also { runCurrent() }

    private fun httpStorage(vararg caches: DownloadTestCache) =
        DownloadTestStorage(testDownloadEngine(MediaCacheEngineKey.WebM3u)).apply { listFlow.value = caches.toList() }

    private fun play(episodeId: Int) = activity.update(player, ActivePlayback(1, episodeId, bufferedAheadMillis = 0, stalled = false))

    @Test
    fun `the playing episode waits and resumes after playback`() = runTest {
        val playing = testDownload(1)
        val other = testDownload(2)
        val manager = manager(httpStorage(playing, other))

        play(1)
        runCurrent()
        assertEquals(MediaCacheState.PAUSED, playing.state.value)
        assertEquals(MediaCacheState.IN_PROGRESS, other.state.value)
        assertEquals(setOf(playing.cacheId), manager.waitingForPlayback.value)

        activity.update(player, null)
        runCurrent()
        assertEquals(MediaCacheState.IN_PROGRESS, playing.state.value)
        assertEquals(emptySet(), manager.waitingForPlayback.value)
    }

    @Test
    fun `a download added while its episode plays waits too`() = runTest {
        val storage = httpStorage()
        val manager = manager(storage)
        play(1)
        runCurrent()

        val added = testDownload(1)
        storage.listFlow.value = listOf(added)
        runCurrent()
        assertEquals(MediaCacheState.PAUSED, added.state.value)
        assertEquals(setOf(added.cacheId), manager.waitingForPlayback.value)

        // 换到下一集: 这一集的接着下
        play(2)
        runCurrent()
        assertEquals(MediaCacheState.IN_PROGRESS, added.state.value)
    }

    @Test
    fun `downloads the user paused stay paused after playback`() = runTest {
        val paused = testDownload(1).apply { state.value = MediaCacheState.PAUSED }
        manager(httpStorage(paused))
        play(1)
        runCurrent()
        activity.update(player, null)
        runCurrent()
        assertEquals(MediaCacheState.PAUSED, paused.state.value)
        assertEquals(0, paused.resumeCalls)
    }

    @Test
    fun `torrent downloads are left alone`() = runTest {
        val torrent = testDownload(1)
        val storage = DownloadTestStorage(testDownloadEngine(MediaCacheEngineKey.Anitorrent)).apply { listFlow.value = listOf(torrent) }
        val manager = manager(storage)
        play(1)
        runCurrent()
        assertEquals(MediaCacheState.IN_PROGRESS, torrent.state.value)
        assertEquals(emptySet(), manager.waitingForPlayback.value)
    }

    @Test
    fun `an old player reporting it stopped does not clear the new one`() = runTest {
        val playing = testDownload(1)
        manager(httpStorage(playing))
        val oldPlayer = Any()
        activity.update(oldPlayer, ActivePlayback(1, 1, 0, stalled = false))
        play(1)
        activity.update(oldPlayer, null)
        runCurrent()
        assertEquals(MediaCacheState.PAUSED, playing.state.value)
    }
}
