/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.widgets

import kotlin.test.Test
import kotlin.test.assertEquals

class AniScrollFadeTest {
    @Test
    fun `nothing hidden keeps the whole fade outside`() {
        assertEquals(36f, edgeFadeOutside(hidden = 0f, length = 36f))
    }

    @Test
    fun `the fade slides in as far as the content has scrolled under the edge`() {
        assertEquals(26f, edgeFadeOutside(hidden = 10f, length = 36f))
        assertEquals(6f, edgeFadeOutside(hidden = 30f, length = 36f))
    }

    @Test
    fun `hidden content taller than the fade shows the whole fade`() {
        assertEquals(0f, edgeFadeOutside(hidden = 36f, length = 36f))
        assertEquals(0f, edgeFadeOutside(hidden = Float.POSITIVE_INFINITY, length = 36f))
    }

    @Test
    fun `a zero length fade has nothing to move`() {
        assertEquals(0f, edgeFadeOutside(hidden = 10f, length = 0f))
    }
}
