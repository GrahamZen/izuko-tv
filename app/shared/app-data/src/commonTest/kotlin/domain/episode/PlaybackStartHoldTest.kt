/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.episode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [PlaybackStartHold]: 只停打开时的那一集, 每个会话只判断一次, 远程打开的不停。
 */
class PlaybackStartHoldTest {
    private var episode = 10
    private val hold = PlaybackStartHold { episode }

    @Test
    fun `holds the opened episode when enabled`() {
        hold.decide(subjectId = 1, enabled = true)

        assertEquals(10, hold.heldEpisodeId.value)
        assertFalse(hold.admit())
        // 换源、重载再问一次也还停着
        assertFalse(hold.admit())
    }

    @Test
    fun `does not hold when disabled`() {
        hold.decide(subjectId = 1, enabled = false)

        assertNull(hold.heldEpisodeId.value)
        assertTrue(hold.admit())
    }

    @Test
    fun `release lets the next load play`() {
        hold.decide(subjectId = 1, enabled = true)

        assertTrue(hold.release())
        assertFalse(hold.release())
        assertNull(hold.heldEpisodeId.value)
        assertTrue(hold.admit())
    }

    @Test
    fun `switching episode stops holding`() {
        hold.decide(subjectId = 1, enabled = true)

        episode = 11
        assertTrue(hold.admit())
        assertNull(hold.heldEpisodeId.value)
    }

    @Test
    fun `switching away and back without loading still plays`() {
        hold.decide(subjectId = 1, enabled = true)

        // 换到别的集, 那一集解析失败没装片子, 又换回来
        hold.onEpisodeChanged(11)
        hold.onEpisodeChanged(10)

        assertNull(hold.heldEpisodeId.value)
        assertTrue(hold.admit())
    }

    @Test
    fun `placeholder episode on the page keeps holding`() {
        hold.decide(subjectId = 1, enabled = true)

        // 集信息还没到时页面上是占位的集 (编号 -1)
        hold.onEpisodeChanged(-1)
        hold.onEpisodeChanged(10)

        assertEquals(10, hold.heldEpisodeId.value)
        assertFalse(hold.admit())
    }

    @Test
    fun `showing the held episode keeps holding`() {
        hold.decide(subjectId = 1, enabled = true)

        hold.onEpisodeChanged(10)

        assertEquals(10, hold.heldEpisodeId.value)
    }

    @Test
    fun `decides only once per session`() {
        hold.decide(subjectId = 1, enabled = false)
        hold.decide(subjectId = 1, enabled = true)

        assertNull(hold.heldEpisodeId.value)
    }

    @Test
    fun `loading before the page decides keeps playing`() {
        // 后台起的会话: 播放页出现之前就装片子
        assertTrue(hold.admit())
        hold.decide(subjectId = 1, enabled = true)

        assertNull(hold.heldEpisodeId.value)
        assertTrue(hold.admit())
    }

    @Test
    fun `loading while the setting is read waits and plays when not held`() {
        // 记住的源一选就中: 设置还没读到片子就装了
        assertTrue(hold.pageShown())
        assertFalse(hold.admit())

        assertTrue(hold.decide(subjectId = 1, enabled = false))
        assertNull(hold.heldEpisodeId.value)
        assertTrue(hold.admit())
    }

    @Test
    fun `loading while the setting is read stays held when enabled`() {
        hold.pageShown()
        assertFalse(hold.admit())

        assertFalse(hold.decide(subjectId = 1, enabled = true))
        assertEquals(10, hold.heldEpisodeId.value)
        assertFalse(hold.admit())
    }

    @Test
    fun `nothing to start when nothing was loaded while reading`() {
        hold.pageShown()

        assertFalse(hold.decide(subjectId = 1, enabled = false))
        assertTrue(hold.admit())
    }

    @Test
    fun `reading the setting starts once per session`() {
        assertTrue(hold.pageShown())
        assertFalse(hold.pageShown())
        hold.decide(subjectId = 1, enabled = false)

        assertFalse(hold.pageShown())
        assertTrue(hold.admit())
    }

    @Test
    fun `remotely opened episode is not held once`() {
        PlaybackStartHold.openedRemotely(subjectId = 2, episodeId = 10)
        hold.decide(subjectId = 2, enabled = true)
        assertNull(hold.heldEpisodeId.value)

        // 登记用一次就消掉: 之后自己打开同一集照常停
        val another = PlaybackStartHold { 10 }
        another.decide(subjectId = 2, enabled = true)
        assertEquals(10, another.heldEpisodeId.value)
    }

    @Test
    fun `remote mark for another episode does not apply`() {
        PlaybackStartHold.openedRemotely(subjectId = 3, episodeId = 99)
        hold.decide(subjectId = 3, enabled = true)

        assertEquals(10, hold.heldEpisodeId.value)
    }
}
