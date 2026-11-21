/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.foundation

import kotlin.test.Test
import kotlin.test.assertEquals

class ForegroundListenerRefreshTest {
    @Test
    fun `closes every registered listener until it is unregistered`() {
        var first = 0
        var second = 0
        val firstHandle = ForegroundListenerRefresh.register { first++ }
        val secondHandle = ForegroundListenerRefresh.register { second++ }
        try {
            ForegroundListenerRefresh.refreshAll()
            assertEquals(1, first)
            assertEquals(1, second)

            firstHandle.close()
            ForegroundListenerRefresh.refreshAll()
            assertEquals(1, first)
            assertEquals(2, second)
        } finally {
            firstHandle.close()
            secondHandle.close()
        }
    }

    @Test
    fun `a failing listener does not stop the others`() {
        var called = 0
        val failing = ForegroundListenerRefresh.register { error("already closed") }
        val other = ForegroundListenerRefresh.register { called++ }
        try {
            ForegroundListenerRefresh.refreshAll()
            assertEquals(1, called)
        } finally {
            failing.close()
            other.close()
        }
    }
}
