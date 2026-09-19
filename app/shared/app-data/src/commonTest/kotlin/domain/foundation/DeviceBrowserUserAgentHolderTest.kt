/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.foundation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 本机 UA 只取一次: 一起开搜的数据源同时来要, 等同一次结果.
 */
class DeviceBrowserUserAgentHolderTest {
    @AfterTest
    fun tearDown() {
        DeviceBrowserUserAgentHolder.install { null }
    }

    @Test
    fun `concurrent callers share one lookup`() = runTest {
        var calls = 0
        val answer = CompletableDeferred<Unit>()
        DeviceBrowserUserAgentHolder.install {
            calls++
            answer.await()
            "Mozilla/5.0 (Linux; Android 11; Device)"
        }
        val callers = List(12) { async { DeviceBrowserUserAgentHolder.current() } }
        answer.complete(Unit)
        assertEquals(List(12) { "Mozilla/5.0 (Linux; Android 11; Device)" }, callers.awaitAll())
        assertEquals(1, calls)
        DeviceBrowserUserAgentHolder.current()
        assertEquals(1, calls)
    }

    @Test
    fun `a lookup cancelled halfway is tried again`() = runTest {
        var calls = 0
        val never = CompletableDeferred<String>()
        DeviceBrowserUserAgentHolder.install {
            calls++
            if (calls == 1) never.await() else "UA"
        }
        assertNull(withTimeoutOrNull(100) { DeviceBrowserUserAgentHolder.current() })
        assertEquals("UA", DeviceBrowserUserAgentHolder.current())
        assertEquals(2, calls)
    }
}
