/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.main

import kotlin.test.Test
import kotlin.test.assertEquals

class TvBackgroundTaskShownIndexTest {
    @Test
    fun `the shown task stays shown when others come and go`() {
        assertEquals(1, tvBackgroundTaskShownIndex(listOf("move", "import"), "import", 1))
        assertEquals(0, tvBackgroundTaskShownIndex(listOf("import", "migration"), "import", 1))
        assertEquals(2, tvBackgroundTaskShownIndex(listOf("move", "migration", "import"), "import", 1))
    }

    @Test
    fun `when the shown task ends the one now at its place is shown`() {
        assertEquals(1, tvBackgroundTaskShownIndex(listOf("move", "migration"), "import", 1))
        assertEquals(0, tvBackgroundTaskShownIndex(listOf("import", "migration"), "move", 0))
    }

    @Test
    fun `when the last task ends the new last one is shown`() {
        assertEquals(1, tvBackgroundTaskShownIndex(listOf("move", "import"), "migration", 2))
        assertEquals(0, tvBackgroundTaskShownIndex(listOf("move"), "import", 1))
    }

    @Test
    fun `nothing is shown without tasks and the first one is shown at the start`() {
        assertEquals(-1, tvBackgroundTaskShownIndex(emptyList(), "move", 0))
        assertEquals(0, tvBackgroundTaskShownIndex(listOf("move", "import"), null, 0))
    }
}
