/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.player.extension

import me.him188.ani.app.domain.media.TestMediaList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 一个资源这一路给数据源画像记什么 (见 [SourceAttempt]).
 */
class SourceAttemptTest {
    private fun attempt() = SourceAttempt(TestMediaList.first(), selectedAt = 0)

    @Test
    fun `an error after loading but before playing counts as one failed start`() {
        val a = attempt()
        a.onLoaded()
        assertTrue(a.onPlayerError())
        assertEquals(false, a.outcome)
        // 换源之前又报一次、或之后才进播放, 都不再记
        assertFalse(a.onPlayerError())
        assertFalse(a.onPlaying())
    }

    @Test
    fun `an error after playing counts once as a playback failure`() {
        val a = attempt()
        a.onLoaded()
        assertTrue(a.onPlaying())
        assertTrue(a.onPlayerError())
        assertFalse(a.onPlayerError())
        assertEquals(true, a.outcome)
    }

    @Test
    fun `a failed open is recorded once and a later player error adds nothing`() {
        val a = attempt()
        assertTrue(a.onLoadFailed())
        assertFalse(a.onPlayerError())
        assertFalse(a.onLoadFailed())
    }

    @Test
    fun `a player error before the open finishes is left to the load failure`() {
        val a = attempt()
        assertFalse(a.onPlayerError())
        assertTrue(a.onLoadFailed())
    }

    @Test
    fun `playing is recorded only after loading and only once`() {
        val a = attempt()
        assertFalse(a.onPlaying())
        a.onLoaded()
        assertTrue(a.onPlaying())
        assertFalse(a.onPlaying())
    }
}
