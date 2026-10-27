/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.foundation

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.seconds

class LingeringReferencesTest {
    private class Instance(val id: Int)

    private class Setup(scope: TestScope) {
        var created = 0
        val closed = mutableListOf<Int>()
        val pool = ReuseObjectPool<String, Instance>(
            newInstance = { Instance(++created) },
            onRelease = { closed += it.id },
        )
        val lingering = LingeringReferences(pool, scope.backgroundScope, 60.seconds, scope.testTimeSource)

        /** 像 [DefaultHttpClientProvider] 那样借、用、还 */
        fun use(key: String): Instance = pool.borrow(key).also {
            lingering.retain(key, it)
            pool.release(key, it)
        }
    }

    @Test
    fun `returned instance stays open until it has been idle for the linger time`() = runTest {
        val setup = Setup(this)
        val first = setup.use("a")
        advanceTimeBy(59.seconds)
        runCurrent()
        assertEquals(emptyList(), setup.closed)

        // 期间再用到: 还是同一个, 重新计时
        assertSame(first, setup.use("a"))
        advanceTimeBy(59.seconds)
        runCurrent()
        assertEquals(emptyList(), setup.closed)

        advanceTimeBy(2.seconds)
        runCurrent()
        assertEquals(listOf(1), setup.closed)

        // 放掉之后再用是新的
        assertEquals(2, setup.use("a").id)
    }

    @Test
    fun `an instance still borrowed elsewhere is not closed when the linger ends`() = runTest {
        val setup = Setup(this)
        val held = setup.pool.borrow("a")
        assertSame(held, setup.use("a"))
        advanceTimeBy(61.seconds)
        runCurrent()
        assertEquals(emptyList(), setup.closed)

        setup.pool.release("a", held)
        assertEquals(listOf(1), setup.closed)
    }

    @Test
    fun `keys linger independently`() = runTest {
        val setup = Setup(this)
        setup.use("a")
        advanceTimeBy(30.seconds)
        setup.use("b")
        advanceTimeBy(31.seconds)
        runCurrent()
        assertEquals(listOf(1), setup.closed)
        advanceTimeBy(30.seconds)
        runCurrent()
        assertEquals(listOf(1, 2), setup.closed)
    }
}
