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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TvPadStatsFitTest {
    @Test
    fun `stats show when the buttons and the stats fit side by side`() {
        assertTrue(tvPadStatsFit(available = 1200, actionsWidth = 600, statsWidth = 500, minGap = 48))
    }

    @Test
    fun `stats exactly filling the row still show`() {
        assertTrue(tvPadStatsFit(available = 1148, actionsWidth = 600, statsWidth = 500, minGap = 48))
    }

    @Test
    fun `stats hide when the buttons would have to wrap`() {
        assertFalse(tvPadStatsFit(available = 1147, actionsWidth = 600, statsWidth = 500, minGap = 48))
        assertFalse(tvPadStatsFit(available = 900, actionsWidth = 700, statsWidth = 500, minGap = 48))
    }
}
