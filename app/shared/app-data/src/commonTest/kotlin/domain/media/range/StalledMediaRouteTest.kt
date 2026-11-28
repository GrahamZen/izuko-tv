/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.range

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 卡死过的资源: 这个播放器再装它时经代理, 装了别的就忘掉; 各播放器互不相干; 会话关掉时忘掉.
 */
class StalledMediaRouteTest {
    private val player = Any()
    private val other = Any()

    @AfterTest
    fun cleanup() {
        StalledMediaRoute.forget(player)
        StalledMediaRoute.forget(other)
    }

    @Test
    fun `nothing stalled plays directly`() {
        assertFalse(StalledMediaRoute.throughProxy(player, "a"))
    }

    @Test
    fun `the stalled media goes through the proxy every time it is loaded again`() {
        StalledMediaRoute.markStalled(player, "a")
        assertTrue(StalledMediaRoute.throughProxy(player, "a"))
        // 之后地址过期、断网恢复等再装同一条
        assertTrue(StalledMediaRoute.throughProxy(player, "a"))
    }

    @Test
    fun `loading another media forgets the stalled one`() {
        StalledMediaRoute.markStalled(player, "a")
        assertFalse(StalledMediaRoute.throughProxy(player, "b"))
        // 换回来也是直连
        assertFalse(StalledMediaRoute.throughProxy(player, "a"))
    }

    @Test
    fun `players are tracked separately`() {
        StalledMediaRoute.markStalled(player, "a")
        assertFalse(StalledMediaRoute.throughProxy(other, "a"))
        assertTrue(StalledMediaRoute.throughProxy(player, "a"))
    }

    @Test
    fun `closing the session forgets it`() {
        StalledMediaRoute.markStalled(player, "a")
        StalledMediaRoute.forget(player)
        assertFalse(StalledMediaRoute.throughProxy(player, "a"))
    }
}
