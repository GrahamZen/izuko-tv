/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode.tv.tablet

import androidx.compose.ui.unit.IntRect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [tabletPlayerGeometry]: 横屏分栏、全屏 (抽屉开 / 关)、竖屏分栏三种摆法.
 */
class TabletPlayerGeometryTest {
    private fun geometry(width: Int, height: Int, fullScreen: Boolean = false, sideOpen: Boolean = false) =
        tabletPlayerGeometry(width, height, sideMinPx = 300, sideMaxPx = 460, fullScreen = fullScreen, sideOpen = sideOpen)

    @Test
    fun `landscape split puts video top left and the side column on the right`() {
        val g = geometry(1280, 800)
        // 右栏 = 1280 * 0.35 = 448, 夹在 300..460 之间
        assertEquals(TabletSideMode.COLUMN, g.sideMode)
        assertEquals(448, g.panelWidth)
        assertEquals(IntRect(0, 0, 832, 468), g.player) // 832 * 9 / 16 = 468
        assertEquals(IntRect(0, 468, 832, 800), g.below)
        assertEquals(IntRect(832, 0, 1280, 800), g.side)
    }

    @Test
    fun `side column width is clamped to the configured range`() {
        assertEquals(460, geometry(2000, 1200).panelWidth)
        assertEquals(300, geometry(800, 500).panelWidth)
    }

    @Test
    fun `side column never takes more than half the screen`() {
        assertEquals(250, geometry(500, 400).panelWidth)
    }

    @Test
    fun `video height is capped on very wide screens`() {
        // 2400 宽 (右栏 460) 的左边 1940 按 16:9 要 1091 高, 超过 900 * 0.7 = 630
        val g = geometry(2400, 900)
        assertEquals(630, g.player.height)
        assertEquals(IntRect(0, 630, 1940, 900), g.below)
    }

    @Test
    fun `full screen without drawer shows only the video`() {
        val g = geometry(1280, 800, fullScreen = true, sideOpen = false)
        assertEquals(IntRect(0, 0, 1280, 800), g.player)
        assertNull(g.below)
        assertNull(g.side)
    }

    @Test
    fun `full screen drawer covers the whole screen with the panel on the right`() {
        val g = geometry(1280, 800, fullScreen = true, sideOpen = true)
        assertEquals(TabletSideMode.DRAWER, g.sideMode)
        assertEquals(IntRect(0, 0, 1280, 800), g.player)
        assertEquals(IntRect(0, 0, 1280, 800), g.side)
        assertEquals(448, g.panelWidth)
    }

    @Test
    fun `portrait split stacks video above the info and opens panels over the info`() {
        val closed = geometry(800, 1280)
        assertEquals(TabletSideMode.SHEET, closed.sideMode)
        assertEquals(IntRect(0, 0, 800, 450), closed.player)
        assertEquals(IntRect(0, 450, 800, 1280), closed.below)
        assertNull(closed.side)

        val open = geometry(800, 1280, sideOpen = true)
        assertEquals(IntRect(0, 450, 800, 1280), open.side)
    }

    @Test
    fun `portrait full screen drawer takes most of the width`() {
        val g = geometry(800, 1280, fullScreen = true, sideOpen = true)
        assertEquals(560, g.panelWidth) // 800 * 0.7
    }
}
