/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.ui

import org.openani.mediamp.MediaStatus
import org.openani.mediamp.PlayerState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerDisplayStateTest {
    private val buffering = PlayerState(MediaStatus.Ready, playWhenReady = true, isBuffering = true)

    @Test
    fun `reported buffering while the player advances counts as playing`() {
        val corrected = correctBuffering(buffering, advancing = true)
        assertFalse(corrected.isBuffering)
        assertTrue(corrected.isPlaying)
    }

    @Test
    fun `reported buffering while the player stands still stays buffering`() {
        assertEquals(buffering, correctBuffering(buffering, advancing = false))
    }

    @Test
    fun `reported buffering while paused with data at the position counts as paused`() {
        val pausedBuffering = PlayerState(MediaStatus.Ready, playWhenReady = false, isBuffering = true)
        val corrected = correctBuffering(pausedBuffering, advancing = false, pausedWithData = true)
        assertFalse(corrected.isBuffering)
        assertFalse(corrected.isPlaying)
    }

    @Test
    fun `reported buffering while paused without data stays buffering`() {
        val pausedBuffering = PlayerState(MediaStatus.Ready, playWhenReady = false, isBuffering = true)
        assertEquals(pausedBuffering, correctBuffering(pausedBuffering, advancing = false, pausedWithData = false))
    }

    @Test
    fun `data at the position does not clear buffering while playback is wanted`() {
        assertEquals(buffering, correctBuffering(buffering, advancing = false, pausedWithData = true))
    }

    @Test
    fun `states that are not buffering pass through`() {
        val playing = PlayerState(MediaStatus.Ready, playWhenReady = true, isBuffering = false)
        val paused = PlayerState(MediaStatus.Ready, playWhenReady = false, isBuffering = false)
        assertEquals(playing, correctBuffering(playing, advancing = true))
        assertEquals(paused, correctBuffering(paused, advancing = false))
    }
}
