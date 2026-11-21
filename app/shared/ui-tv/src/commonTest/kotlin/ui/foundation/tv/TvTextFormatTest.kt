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

class TvTextFormatTest {
    @Test
    fun `positional arguments`() {
        assertEquals("正在查询 3/20 个数据源", "正在查询 %1\$d/%2\$d 个数据源".tvFormat(3, 20))
        assertEquals("b a", "%2\$s %1\$s".tvFormat("a", "b"))
    }

    @Test
    fun `sequential arguments`() {
        assertEquals("线路 2 · x", "线路 %d · %s".tvFormat(2, "x"))
    }

    @Test
    fun `zero padded width`() {
        assertEquals("03-07", "%02d-%02d".tvFormat(3, 7))
        assertEquals("12:05", "%02d:%02d".tvFormat(12, 5))
        assertEquals(" 7", "%2d".tvFormat(7))
    }

    @Test
    fun `percent sign and unknown specifiers stay`() {
        assertEquals("50%", "%1\$d%%".tvFormat(50))
        assertEquals("100% v", "100% v".tvFormat())
        assertEquals("a %f", "a %f".tvFormat(1.5))
    }

    @Test
    fun `missing argument becomes null like java`() {
        assertEquals("x null", "%1\$s %2\$s".tvFormat("x"))
    }
}
