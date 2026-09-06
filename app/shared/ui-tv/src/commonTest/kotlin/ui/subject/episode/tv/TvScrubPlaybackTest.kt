/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode.tv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [TvScrubPlayback] 的进出规则: 拖动时暂停 / 边播边选两档, 各自在进入、确认、取消时对播放器做什么.
 */
class TvScrubPlaybackTest {
    private class FakePlayer(var playing: Boolean) {
        val calls = mutableListOf<String>()

        fun pause() {
            calls += "pause"
            playing = false
        }

        fun play() {
            calls += "play"
            playing = true
        }
    }

    private fun scrubPlayback(player: FakePlayer, pauseOnScrub: () -> Boolean) = TvScrubPlayback(
        pauseOnScrub = pauseOnScrub,
        isPlaying = { player.playing },
        pause = player::pause,
        play = player::play,
    )

    @Test
    fun `pausing mode pauses a playing video and cancel resumes it`() {
        val player = FakePlayer(playing = true)
        val scrub = scrubPlayback(player) { true }

        scrub.onEnter()
        assertEquals(false, player.playing)
        scrub.onCancel()

        assertEquals(true, player.playing)
        assertEquals(listOf("pause", "play"), player.calls)
    }

    @Test
    fun `a resume the player has not reported yet still counts as playing`() {
        // 取消会跳回原处再恢复播放: 播放器在过渡期间把这条播放排队, 报出来的状态还是停着
        val player = FakePlayer(playing = true)
        var queued = false
        val scrub = TvScrubPlayback(
            pauseOnScrub = { true },
            isPlaying = { player.playing },
            pause = player::pause,
            play = {
                player.calls += "play"
                queued = true
            },
        )
        scrub.onEnter()
        scrub.onCancel()
        assertTrue(queued)

        // 马上再拖: 照样暂停, 取消时再恢复
        assertEquals(true, scrub.onEnter())
        scrub.onCancel()
        assertEquals(listOf("pause", "play", "pause", "play"), player.calls)
    }

    @Test
    fun `once the player reports playing a later pause by the user is respected`() {
        val player = FakePlayer(playing = true)
        val scrub = scrubPlayback(player) { true }
        scrub.onEnter()
        scrub.onCancel()
        scrub.onPlayingChanged(true)

        player.pause() // 用户自己按了暂停
        scrub.onPlayingChanged(false)
        assertEquals(false, scrub.onEnter())
        scrub.onCancel()

        assertEquals(listOf("pause", "play", "pause"), player.calls)
        assertEquals(false, player.playing)
    }

    @Test
    fun `pausing mode leaves a video paused before scrubbing paused after cancel`() {
        val player = FakePlayer(playing = false)
        val scrub = scrubPlayback(player) { true }

        scrub.onEnter()
        scrub.onCancel()

        assertEquals(false, player.playing)
        assertTrue(player.calls.isEmpty(), "calls: ${player.calls}")
    }

    @Test
    fun `play-while-scrubbing mode never touches playback on enter or cancel`() {
        for (playingBefore in listOf(true, false)) {
            val player = FakePlayer(playingBefore)
            val scrub = scrubPlayback(player) { false }

            scrub.onEnter()
            scrub.onCancel()

            assertEquals(playingBefore, player.playing)
            assertTrue(player.calls.isEmpty(), "playingBefore=$playingBefore calls: ${player.calls}")
        }
    }

    @Test
    fun `commit always plays`() {
        for (pauseOnScrub in listOf(true, false)) {
            for (playingBefore in listOf(true, false)) {
                val player = FakePlayer(playingBefore)
                val scrub = scrubPlayback(player) { pauseOnScrub }

                scrub.onEnter()
                scrub.onCommit()

                assertEquals(true, player.playing, "pauseOnScrub=$pauseOnScrub playingBefore=$playingBefore")
            }
        }
    }

    @Test
    fun `setting changed mid-scrub still undoes the pause made on entry`() {
        val player = FakePlayer(playing = true)
        var pauseOnScrub = true
        val scrub = scrubPlayback(player) { pauseOnScrub }

        scrub.onEnter()
        pauseOnScrub = false
        scrub.onCancel()

        assertEquals(true, player.playing)
    }

    @Test
    fun `cancel after commit does not play again`() {
        val player = FakePlayer(playing = true)
        val scrub = scrubPlayback(player) { true }

        scrub.onEnter()
        scrub.onCommit()
        player.calls.clear()
        scrub.onCancel()

        assertTrue(player.calls.isEmpty(), "calls: ${player.calls}")
    }
}
