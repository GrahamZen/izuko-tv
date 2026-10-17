/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.selector

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class MediaSelectorInputRetryTest {
    @Test
    fun `failed input is retried instead of thrown`() = runTest {
        var attempts = 0
        val input = flow {
            attempts++
            if (attempts < 3) throw IllegalStateException("Server error 522")
            emit("episode")
        }

        assertEquals("episode", input.retryAsSelectorInput("episode 1").first())
        assertEquals(3, attempts)
    }
}
