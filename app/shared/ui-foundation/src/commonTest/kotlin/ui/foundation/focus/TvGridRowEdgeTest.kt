/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.focus

import kotlin.test.Test
import kotlin.test.assertEquals

/** [resolveTvGridRowEdge]: 追番页跨 tab 的"对应位置"落点. */
class TvGridRowEdgeTest {
    @Test
    fun `enters the same row`() {
        assertEquals(7, resolveTvGridRowEdge(row = 1, direction = 1, columns = 7, itemCount = 59))
        assertEquals(13, resolveTvGridRowEdge(row = 1, direction = -1, columns = 7, itemCount = 59))
    }

    @Test
    fun `target with fewer rows lands on its last row`() {
        // 源第 4 行行末按右, 目标 tab 只有 2 张: 落第一张, 不是第二张
        assertEquals(0, resolveTvGridRowEdge(row = 3, direction = 1, columns = 2, itemCount = 2))
        assertEquals(1, resolveTvGridRowEdge(row = 3, direction = -1, columns = 2, itemCount = 2))
        // 59 张 7 列: 最后一行是 56..58
        assertEquals(56, resolveTvGridRowEdge(row = 10, direction = 1, columns = 7, itemCount = 59))
    }

    @Test
    fun `row end of a partial last row is its last card`() {
        assertEquals(58, resolveTvGridRowEdge(row = 8, direction = -1, columns = 7, itemCount = 59))
    }

    @Test
    fun `empty grid resolves to zero`() {
        assertEquals(0, resolveTvGridRowEdge(row = 2, direction = 1, columns = 7, itemCount = 0))
    }
}
