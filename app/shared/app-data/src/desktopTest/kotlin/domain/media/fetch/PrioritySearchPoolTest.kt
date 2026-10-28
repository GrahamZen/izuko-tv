/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.fetch

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PrioritySearchPoolTest {
    @Test
    fun `shared lane runs before every source lane and each lane keeps its order`() {
        val pool = PrioritySearchPool(1) { Thread(it).apply { isDaemon = true } }
        val busy = CountDownLatch(1)
        val started = CountDownLatch(1)
        pool.lane(0).dispatch(EmptyCoroutineContext) {
            started.countDown()
            busy.await()
        }
        assertTrue(started.await(5, TimeUnit.SECONDS))

        val order = Collections.synchronizedList(mutableListOf<String>())
        val done = CountDownLatch(5)
        fun run(name: String) = Runnable {
            order += name
            done.countDown()
        }
        pool.lane(5).dispatch(EmptyCoroutineContext, run("source 5"))
        pool.lane(1).dispatch(EmptyCoroutineContext, run("source 1 first"))
        pool.shared.dispatch(EmptyCoroutineContext, run("shared first"))
        pool.lane(1).dispatch(EmptyCoroutineContext, run("source 1 second"))
        pool.shared.dispatch(EmptyCoroutineContext, run("shared second"))
        busy.countDown()

        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("shared first", "shared second", "source 1 first", "source 1 second", "source 5"), order)
    }
}
