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
import kotlin.test.assertNull

/**
 * 底部字幕挪到哪儿: 控制层在场时挪到胶囊行 (选集条展开时是选集条) 上面, 不在场时留在原位.
 */
class TvSubtitleObstructionTest {
    @Test
    fun `subtitles move above whatever the controls show at the bottom`() {
        val overlay = TvPlayerOverlayState()
        overlay.pillsRowTopPx.floatValue = 800f
        overlay.episodeStripTopPx.floatValue = 600f
        // 纯视频态: 不挪
        assertNull(overlay.subtitleObstructionTopPx())

        overlay.showControls()
        assertEquals(800f, overlay.subtitleObstructionTopPx())

        overlay.expandEpisodeStrip()
        assertEquals(600f, overlay.subtitleObstructionTopPx())
    }

    @Test
    fun `nothing moves before the controls are measured`() {
        val overlay = TvPlayerOverlayState()
        overlay.showControls()
        assertNull(overlay.subtitleObstructionTopPx())
    }
}
