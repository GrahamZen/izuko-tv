/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.details.layout

import kotlin.test.Test
import kotlin.test.assertEquals

/** 触屏详情页标签的排行 ([tvPadFlowLines]): 一行放到放不下为止, 格间距算在里面. */
class TvPadFlowLinesTest {
    @Test
    fun `items wrap when the next one plus the gap does not fit`() {
        // 宽 100, 间距 10: 40 + 10 + 40 = 90 放得下, 再来一个 40 就要换行
        assertEquals(listOf(listOf(0, 1), listOf(2)), tvPadFlowLines(listOf(40, 40, 40), maxWidth = 100, gap = 10))
    }

    @Test
    fun `an item that fills the width exactly stays on the line`() {
        assertEquals(listOf(listOf(0, 1)), tvPadFlowLines(listOf(45, 45), maxWidth = 100, gap = 10))
    }

    @Test
    fun `an item wider than the line takes a line of its own`() {
        assertEquals(listOf(listOf(0), listOf(1), listOf(2)), tvPadFlowLines(listOf(30, 150, 30), maxWidth = 100, gap = 10))
    }

    @Test
    fun `no items make no lines`() {
        assertEquals(emptyList(), tvPadFlowLines(emptyList(), maxWidth = 100, gap = 10))
    }
}
