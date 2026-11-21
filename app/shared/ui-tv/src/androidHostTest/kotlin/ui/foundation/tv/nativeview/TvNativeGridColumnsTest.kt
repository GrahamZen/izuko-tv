/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv.nativeview

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 全部选集网格按宽排几列 ([tvNativeGridColumns]): 格子不窄于最小宽, 算上列距.
 */
class TvNativeGridColumnsTest {
    @Test
    fun `columns fit with the gaps between them`() {
        // 4 x 180 + 3 x 16 = 768
        assertEquals(4, tvNativeGridColumns(768, 180, 16))
        assertEquals(3, tvNativeGridColumns(767, 180, 16))
    }

    @Test
    fun `at least one column`() {
        assertEquals(1, tvNativeGridColumns(100, 180, 16))
        assertEquals(1, tvNativeGridColumns(0, 180, 16))
    }
}
