/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.download

import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.domain.media.player.ActivePlayback
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 播放跟不上时别的 BT 下载停下让路 ([TorrentPlaybackYield]): 缓冲攒得住不管, 正在播的那一集与还没选好文件的不停.
 */
class TorrentPlaybackYieldTest {
    private class FakeTarget(override val id: String, override val episodeId: String, var downloading: Boolean = true) : TorrentPlaybackYield.Target {
        override val label: String get() = "ep $episodeId"
        override val subjectId: String get() = "1"
        var yielding = false
        override suspend fun canYield(): Boolean = downloading
        override suspend fun setYielding(yielding: Boolean) {
            this.yielding = yielding
        }
    }

    private fun playing(episodeId: Int, ahead: Long?, stalled: Boolean = false) = ActivePlayback(1, episodeId, ahead, stalled)

    private val yield = TorrentPlaybackYield(emptyFlow(), emptyFlow())

    @Test
    fun `yields when the buffer runs low or playback stalls and lets go once it has refilled`() {
        assertFalse(TorrentPlaybackYield.shouldYield(false, null))
        assertFalse(TorrentPlaybackYield.shouldYield(false, playing(1, 20_000)))
        assertTrue(TorrentPlaybackYield.shouldYield(false, playing(1, 9_000)))
        assertTrue(TorrentPlaybackYield.shouldYield(false, playing(1, 40_000, stalled = true)))
        // 让路以后回到 30 秒才放开
        assertTrue(TorrentPlaybackYield.shouldYield(true, playing(1, 20_000)))
        assertFalse(TorrentPlaybackYield.shouldYield(true, playing(1, 30_000)))
        // 暂停、加载中 (报不出缓冲) 不让路
        assertFalse(TorrentPlaybackYield.shouldYield(true, playing(1, null)))
    }

    @Test
    fun `a buffer that keeps up leaves BT downloads alone`() = runTest {
        val other = FakeTarget("b", "11")
        yield.step(playing(10, 25_000), listOf(other))
        assertFalse(other.yielding)
    }

    @Test
    fun `others pause while the playing episode and those without a file keep going`() = runTest {
        val playingOne = FakeTarget("a", "10")
        val other = FakeTarget("b", "11")
        val awaitingInfo = FakeTarget("c", "12", downloading = false)
        yield.step(playing(10, 5_000), listOf(playingOne, other, awaitingInfo))
        assertEquals(listOf(false, true, false), listOf(playingOne, other, awaitingInfo).map { it.yielding })

        // 缓冲回来一些还不放开
        yield.step(playing(10, 20_000), listOf(playingOne, other, awaitingInfo))
        assertTrue(other.yielding)
        // 等种子信息的那份后来选好了文件: 还在让路就一起停
        awaitingInfo.downloading = true
        yield.step(playing(10, 20_000), listOf(playingOne, other, awaitingInfo))
        assertTrue(awaitingInfo.yielding)

        yield.step(playing(10, 31_000), listOf(playingOne, other, awaitingInfo))
        assertEquals(listOf(false, false, false), listOf(playingOne, other, awaitingInfo).map { it.yielding })
    }

    @Test
    fun `leaving playback lets the downloads go`() = runTest {
        val other = FakeTarget("b", "11")
        yield.step(playing(10, 5_000), listOf(other))
        assertTrue(other.yielding)
        yield.step(null, listOf(other))
        assertFalse(other.yielding)
    }

    @Test
    fun `playing an episode that was paused lets it go at once`() = runTest {
        val next = FakeTarget("b", "11")
        val later = FakeTarget("c", "12")
        yield.step(playing(10, 5_000, stalled = true), listOf(next, later))
        assertTrue(next.yielding)
        // 换到第 11 集, 还在卡: 它是正在播的, 当场放开; 别的接着停
        yield.step(playing(11, 0, stalled = true), listOf(next, later))
        assertFalse(next.yielding)
        assertTrue(later.yielding)
    }
}
