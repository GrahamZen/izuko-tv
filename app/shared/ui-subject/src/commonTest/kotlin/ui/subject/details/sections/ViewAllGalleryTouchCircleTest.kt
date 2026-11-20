/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.details.sections

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

class ViewAllGalleryTouchCircleTest {
    @Test
    fun `touch circle follows the step ratio`() {
        val cell = 194.dp
        assertEquals((cell + VIEW_ALL_GALLERY_COLUMN_SPACING) * VIEW_ALL_GALLERY_TOUCH_CIRCLE_TO_STEP, viewAllGalleryTouchCircle(cell))
    }

    @Test
    fun `touch circle never exceeds the cell`() {
        assertEquals(0.dp, viewAllGalleryTouchCircle(0.dp))
    }
}
