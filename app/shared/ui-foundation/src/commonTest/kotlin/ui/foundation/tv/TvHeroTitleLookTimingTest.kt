/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 转场标题的先后 (见 [TV_HERO_TITLE_LOOK_SPAN]): 列表页标题是黑字时, 放大先原地变成白字再平移, 缩回先平移回去最后原地变回黑字;
 * 列表页标题已经是详情页的样子 (整屏背景点开过 / 深色主题) 时一开始就平移, 不变样子.
 */
class TvHeroTitleLookTimingTest {
    private val span = TV_HERO_TITLE_LOOK_SPAN

    @Test
    fun `zoom from a black title turns white in place before moving`() {
        // 变样子那一段: 不动
        assertEquals(0f, tvHeroZoomTitleLook(0f, 0f))
        val mid = tvHeroZoomTitleLook(0f, span / 2)
        assertTrue(mid > 0f && mid < 1f, "变到一半: $mid")
        assertEquals(0f, tvHeroZoomTitleMove(0f, span / 2, t = 0.6f))
        // 变完才开始平移
        assertEquals(1f, tvHeroZoomTitleLook(0f, span))
        assertEquals(0f, tvHeroZoomTitleMove(0f, span, t = 0.8f))
        val moving = tvHeroZoomTitleMove(0f, (span + 1f) / 2, t = 0.95f)
        assertTrue(moving > 0f && moving < 1f, "平移途中: $moving")
        assertEquals(1f, tvHeroZoomTitleMove(0f, 1f, t = 1f))
    }

    @Test
    fun `zoom from a title already white moves with the zoom from the start`() {
        assertEquals(1f, tvHeroZoomTitleLook(1f, 0f))
        assertEquals(1f, tvHeroZoomTitleLook(1f, 0.1f))
        assertEquals(0.3f, tvHeroZoomTitleMove(1f, 0.1f, t = 0.3f))
    }

    @Test
    fun `shrink to a black title moves back first and turns black in place at the end`() {
        // 前一段: 还是白字, 在平移
        assertEquals(1f, tvHeroShrinkTitleLook(0f, 0f))
        assertEquals(1f, tvHeroShrinkTitleLook(0f, 1f - span))
        val moving = tvHeroShrinkTitleMove(0f, (1f - span) / 2, geometric = 0.2f)
        assertTrue(moving > 0f && moving < 1f, "平移途中: $moving")
        // 平移完才变回黑字
        assertEquals(1f, tvHeroShrinkTitleMove(0f, 1f - span, geometric = 0.6f))
        val mid = tvHeroShrinkTitleLook(0f, 1f - span / 2)
        assertTrue(mid > 0f && mid < 1f, "变到一半: $mid")
        assertEquals(0f, tvHeroShrinkTitleLook(0f, 1f))
    }

    @Test
    fun `shrink to a title still white keeps it white and follows the shrinking image`() {
        assertEquals(1f, tvHeroShrinkTitleLook(1f, 0.9f))
        assertEquals(0.4f, tvHeroShrinkTitleMove(1f, 0.9f, geometric = 0.4f))
    }
}
