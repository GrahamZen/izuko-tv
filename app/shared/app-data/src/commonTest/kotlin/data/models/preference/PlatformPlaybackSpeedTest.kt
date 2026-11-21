/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.models.preference

import kotlin.test.Test
import kotlin.test.assertEquals

class PlatformPlaybackSpeedTest {
    @Test
    fun `default config is limited to the cap`() {
        val config = VideoScaffoldConfig.Default.withinPlaybackSpeedCap(cap = 2f)

        assertEquals(0.5f, config.minPlaybackSpeed)
        assertEquals(2f, config.maxPlaybackSpeed)
        assertEquals(1f, config.playbackSpeed)
        assertEquals(2f, config.fastForwardSpeed)
    }

    @Test
    fun `a range above the cap keeps one step below it`() {
        val config = VideoScaffoldConfig.Default
            .withPlaybackSpeedRange(3f..4f)
            .copy(playbackSpeed = 3.5f, fastForwardSpeed = 4f)
            .withinPlaybackSpeedCap(cap = 2f)

        assertEquals(1.75f, config.minPlaybackSpeed)
        assertEquals(2f, config.maxPlaybackSpeed)
        assertEquals(2f, config.playbackSpeed)
        assertEquals(2f, config.fastForwardSpeed)
    }

    @Test
    fun `values within the cap are unchanged even when long press exceeds the range`() {
        val config = VideoScaffoldConfig.Default.copy(fastForwardSpeed = 3f)

        assertEquals(config, config.withinPlaybackSpeedCap(cap = VideoScaffoldConfig.MAX_SUPPORTED_PLAYBACK_SPEED))
    }
}
