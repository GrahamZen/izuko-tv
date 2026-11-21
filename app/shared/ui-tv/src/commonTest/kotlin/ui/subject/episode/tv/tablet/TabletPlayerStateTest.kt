/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode.tv.tablet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [TabletPlayerState]: 面板开关、全屏抽屉、返回顺序 (抽屉 → 全屏退回分栏 → 右栏面板退一层).
 */
class TabletPlayerStateTest {
    @Test
    fun `starts in split view with no panel`() {
        val state = TabletPlayerState()
        assertFalse(state.fullScreen)
        assertNull(state.shownPanel)
        assertFalse(state.canGoBack)
        assertFalse(state.back())
    }

    @Test
    fun `toggling a panel in split view replaces the current one and closes on second tap`() {
        val state = TabletPlayerState()
        state.toggle(TabletSidePanel.SOURCES)
        assertEquals(TabletSidePanel.SOURCES, state.shownPanel)
        state.toggle(TabletSidePanel.EPISODES)
        assertEquals(listOf(TabletSidePanel.EPISODES), state.panels)
        state.toggle(TabletSidePanel.EPISODES)
        assertNull(state.shownPanel)
    }

    @Test
    fun `leaving full screen unlocks gestures`() {
        val state = TabletPlayerState()
        state.enterFullScreen()
        state.gesturesLocked = true
        assertTrue(state.back())
        assertFalse(state.fullScreen)
        assertFalse(state.gesturesLocked)
    }

    @Test
    fun `full screen keeps the side panel and restores it on exit`() {
        val state = TabletPlayerState()
        state.toggle(TabletSidePanel.EPISODES)
        state.enterFullScreen()
        // 全屏时侧边看抽屉, 右栏那一页还记着
        assertNull(state.shownPanel)
        state.toggle(TabletSidePanel.SOURCES)
        assertEquals(TabletSidePanel.SOURCES, state.drawer)
        state.exitFullScreen()
        assertNull(state.drawer)
        assertEquals(TabletSidePanel.EPISODES, state.shownPanel)
    }

    @Test
    fun `back closes the drawer then leaves full screen then pops the panel`() {
        val state = TabletPlayerState()
        state.toggle(TabletSidePanel.EPISODES)
        state.enterFullScreen()
        state.toggle(TabletSidePanel.SOURCES)

        assertTrue(state.back())
        assertNull(state.drawer)
        assertTrue(state.fullScreen)

        assertTrue(state.back())
        assertFalse(state.fullScreen)
        assertEquals(TabletSidePanel.EPISODES, state.shownPanel)

        assertTrue(state.back())
        assertNull(state.shownPanel)

        assertFalse(state.canGoBack)
        assertFalse(state.back())
    }

    @Test
    fun `close shown only closes what is visible`() {
        val state = TabletPlayerState()
        state.toggle(TabletSidePanel.EPISODES)
        state.enterFullScreen()
        state.toggle(TabletSidePanel.SOURCES)
        state.closeShown()
        assertNull(state.drawer)
        assertEquals(listOf(TabletSidePanel.EPISODES), state.panels)
    }
}
