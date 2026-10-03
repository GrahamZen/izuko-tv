/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.cache.engine

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.domain.media.cache.engine.PlaybackYieldingGate.Companion.nextMode
import me.him188.ani.app.domain.media.cache.engine.PlaybackYieldingGate.Mode
import me.him188.ani.app.domain.media.player.ActivePlayback
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 缓存按播放器的缓冲余量给播放让路: 攒得住就不管, 往下掉就限速, 快见底或卡住就停; 进退之间留余量.
 */
class PlaybackYieldingGateTest {
    private fun ahead(seconds: Int, stalled: Boolean = false) = ActivePlayback(1, 1, seconds * 1000L, stalled)

    @Test
    fun `not playing or unknown buffer leaves downloads alone`() {
        assertEquals(Mode.FREE, nextMode(Mode.PAUSED, null))
        assertEquals(Mode.FREE, nextMode(Mode.THROTTLED, ActivePlayback(1, 1, null, stalled = false)))
    }

    @Test
    fun `stalls pause downloads`() {
        assertEquals(Mode.PAUSED, nextMode(Mode.FREE, ahead(60, stalled = true)))
    }

    @Test
    fun `buffer thresholds with hysteresis`() {
        // 不受限时: 25 秒以下才限速, 10 秒以下停
        assertEquals(Mode.FREE, nextMode(Mode.FREE, ahead(27)))
        assertEquals(Mode.THROTTLED, nextMode(Mode.FREE, ahead(20)))
        assertEquals(Mode.PAUSED, nextMode(Mode.FREE, ahead(5)))
        // 限速时: 回到 30 秒才放开
        assertEquals(Mode.THROTTLED, nextMode(Mode.THROTTLED, ahead(27)))
        assertEquals(Mode.FREE, nextMode(Mode.THROTTLED, ahead(30)))
        // 停了之后: 15 秒仍停, 20 秒先限速, 30 秒放开
        assertEquals(Mode.PAUSED, nextMode(Mode.PAUSED, ahead(15)))
        assertEquals(Mode.THROTTLED, nextMode(Mode.PAUSED, ahead(20)))
        assertEquals(Mode.FREE, nextMode(Mode.PAUSED, ahead(30)))
    }

    @Test
    fun `paused downloads wait until the buffer recovers`() = runTest {
        val playback = MutableStateFlow<ActivePlayback?>(ahead(5))
        val gate = PlaybackYieldingGate(playback) { playback.value }
        val read = async { gate.acquire(1024) }
        runCurrent()
        assertFalse(read.isCompleted)

        playback.value = ahead(40)
        runCurrent()
        assertTrue(read.isCompleted)
    }
}
