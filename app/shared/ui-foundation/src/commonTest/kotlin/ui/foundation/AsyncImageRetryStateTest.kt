/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation

import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AsyncImageRetryStateTest {
    @Test
    fun `each failure retries after a longer wait until the limit`() = runTest {
        val state = AsyncImageRetryState(maxAttempts = 8)
        repeat(8) { i ->
            state.onError()
            assertTrue(state.suppressed, "failure ${i + 1} should trigger a retry")
            val before = currentTime
            state.runBackoff("cover")
            assertEquals(500L * (i + 1), currentTime - before)
            assertFalse(state.suppressed)
        }
        // past the limit the image stays failed
        state.onError()
        assertFalse(state.suppressed)
    }

    @Test
    fun `default limit gives up after three retries`() = runTest {
        val state = AsyncImageRetryState(maxAttempts = 3)
        repeat(3) {
            state.onError()
            state.runBackoff("still")
        }
        state.onError()
        assertFalse(state.suppressed)
    }
}
