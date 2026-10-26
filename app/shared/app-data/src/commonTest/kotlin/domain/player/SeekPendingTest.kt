/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.openani.mediamp.source.UriMediaData
import org.openani.mediamp.test.TestMediampPlayer
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SeekPendingTest {
    private suspend fun TestScope.playingPlayer(): TestMediampPlayer {
        val player = TestMediampPlayer(StandardTestDispatcher(testScheduler))
        player.holdSeeks = true
        player.setMediaData(UriMediaData("https://example.com/video.mp4"), playWhenReady = true)
        runCurrent()
        return player
    }

    /** 收集 [seekPendingFlow] 的最新值; 测试结束时取消. */
    private fun TestScope.pendingOf(player: TestMediampPlayer): MutableStateFlow<Boolean> {
        val latest = MutableStateFlow(false)
        backgroundScope.launch { player.seekPendingFlow().collect { latest.value = it } }
        runCurrent()
        return latest
    }

    private fun TestScope.playTo(player: TestMediampPlayer, vararg positions: Long) {
        for (position in positions) {
            player.injectPosition(position)
            runCurrent()
        }
    }

    @Test
    fun `normal playback is not pending`() = runTest {
        val player = playingPlayer()
        val pending = pendingOf(player)
        playTo(player, 200, 400, 600, 800)
        assertFalse(pending.value)
    }

    @Test
    fun `forward seek is pending until the native seek completes`() = runTest {
        val player = playingPlayer()
        val pending = pendingOf(player)
        playTo(player, 200, 400)

        player.seekTo(60_000)
        runCurrent()
        assertTrue(pending.value)

        // 落定之前播放器报上来的位置被状态机丢掉, 仍算在拖
        playTo(player, 600)
        assertTrue(pending.value)

        player.completeHeldSeek()
        runCurrent()
        assertFalse(pending.value)
    }

    @Test
    fun `backward seek is pending too`() = runTest {
        val player = playingPlayer()
        val pending = pendingOf(player)
        playTo(player, 30_000, 30_200)

        player.seekTo(5_000)
        runCurrent()
        assertTrue(pending.value)

        player.completeHeldSeek()
        runCurrent()
        assertFalse(pending.value)
    }

    @Test
    fun `small steps back and forth are not seeks`() = runTest {
        val player = playingPlayer()
        val pending = pendingOf(player)
        playTo(player, 10_000, 10_200)

        player.seekTo(9_500)
        runCurrent()
        assertFalse(pending.value)
        player.completeHeldSeek()
        runCurrent()

        player.seekTo(10_500)
        runCurrent()
        assertFalse(pending.value)
    }

    @Test
    fun `opening another media ends pending`() = runTest {
        val player = playingPlayer()
        val pending = pendingOf(player)
        playTo(player, 200)

        player.seekTo(60_000)
        runCurrent()
        assertTrue(pending.value)

        player.setMediaData(UriMediaData("https://example.com/other.mp4"), playWhenReady = true)
        runCurrent()
        assertFalse(pending.value)
    }
}
