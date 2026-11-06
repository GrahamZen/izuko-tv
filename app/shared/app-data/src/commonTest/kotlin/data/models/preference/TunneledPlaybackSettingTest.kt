/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.models.preference

import me.him188.ani.app.data.persistent.DataStoreJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TunneledPlaybackSettingTest {
    @Test
    fun `tunneled playback is off when never set`() {
        val config = DataStoreJson.decodeFromString(VideoScaffoldConfig.serializer(), "{}")
        assertFalse(config.tunneledPlayback)
    }

    @Test
    fun `tunneled playback survives serialization`() {
        val decoded = DataStoreJson.decodeFromString(
            VideoScaffoldConfig.serializer(),
            DataStoreJson.encodeToString(VideoScaffoldConfig.serializer(), VideoScaffoldConfig.Default.copy(tunneledPlayback = true)),
        )
        assertTrue(decoded.tunneledPlayback)
    }

    @Test
    fun `seek preview follows the display setting without tunneled playback`() {
        val config = VideoScaffoldConfig.Default.copy(tunneledPlayback = false)
        assertFalse(config.copy(seekPreviewDisplay = SeekPreviewDisplay.WINDOW).seekPreviewOnFullScreen)
        assertTrue(config.copy(seekPreviewDisplay = SeekPreviewDisplay.FULL_SCREEN).seekPreviewOnFullScreen)
    }

    @Test
    fun `seek preview goes full screen with tunneled playback and keeps the display setting`() {
        val config = VideoScaffoldConfig.Default.copy(tunneledPlayback = true, seekPreviewDisplay = SeekPreviewDisplay.WINDOW)
        assertTrue(config.seekPreviewOnFullScreen)
        assertEquals(SeekPreviewDisplay.WINDOW, config.seekPreviewDisplay)
    }
}
