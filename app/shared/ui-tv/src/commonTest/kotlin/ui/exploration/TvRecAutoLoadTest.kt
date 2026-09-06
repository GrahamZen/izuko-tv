/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.exploration

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TvRecAutoLoadTest {
    @Test
    fun `a fresh row of one batch extends as soon as it is focused`() {
        assertTrue(tvRecAutoLoadDue(cardIndex = 0, rowSize = 12, columns = 6))
    }

    @Test
    fun `a longer row extends three screens before its end`() {
        assertFalse(tvRecAutoLoadDue(cardIndex = 5, rowSize = 24, columns = 6))
        assertTrue(tvRecAutoLoadDue(cardIndex = 6, rowSize = 24, columns = 6))
    }

    @Test
    fun `new cards go in one step before the trailing placeholder can show`() {
        // 屏上最右到焦点右边 columns 张: 焦点在第 6 张时下标 12 的占位露出来, 第 5 张时就该放了
        assertFalse(tvRecTailNear(cardIndex = 4, rowSize = 12, columns = 6))
        assertTrue(tvRecTailNear(cardIndex = 5, rowSize = 12, columns = 6))
        assertTrue(tvRecTailNear(cardIndex = 12, rowSize = 12, columns = 6))
    }

    @Test
    fun `the next batch is due before the current tail gets near`() {
        // 接一批之前先到「该接」, 中间隔着一整批的路
        for (rowSize in listOf(12, 24, 36, 48)) {
            val firstNear = (0..rowSize).first { tvRecTailNear(it, rowSize, columns = 6) }
            val firstDue = (0..rowSize).first { tvRecAutoLoadDue(it, rowSize, columns = 6) }
            assertTrue(firstNear - firstDue >= 5, "rowSize=$rowSize due=$firstDue near=$firstNear")
        }
    }
}
