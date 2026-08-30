/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.utils.httpdownloader

import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ByteRateLimiterTest {
    /** 桶满时可以立刻突发取走半秒的量, 不该等 */
    @Test
    fun `burst up to capacity does not wait`() = runTest {
        val limiter = ByteRateLimiter(1000) { currentTime }
        limiter.acquire(500) // capacity = max(1000/2, 64K) = 64K, 所以 500 直接过
        assertEquals(0L, currentTime)
    }

    /** 取空之后, 后续请求必须按额定速率等待 */
    @Test
    fun `throttles to the configured rate once drained`() = runTest {
        val bytesPerSecond = 128L * 1024
        val limiter = ByteRateLimiter(bytesPerSecond) { currentTime }
        // 先把桶抽干 (capacity = 64K)
        limiter.acquire(64 * 1024)
        val start = currentTime
        // 再要 128K, 按 128K/s 应当等约 1 秒
        limiter.acquire(64 * 1024)
        limiter.acquire(64 * 1024)
        val elapsed = currentTime - start
        assertTrue(elapsed in 900..1100, "expected ~1000ms, but was ${elapsed}ms")
    }

    /** 长时间取用后的平均吞吐应当收敛到额定速率 */
    @Test
    fun `average throughput converges to the rate`() = runTest {
        val bytesPerSecond = 4L * 1024 * 1024
        val chunk = 8 * 1024
        val limiter = ByteRateLimiter(bytesPerSecond) { currentTime }
        val start = currentTime
        val total = bytesPerSecond * 3 // 三秒的量
        var sent = 0L
        while (sent < total) {
            limiter.acquire(chunk)
            sent += chunk
        }
        val elapsed = currentTime - start
        // 桶里预存了半秒的量, 所以实际耗时会比 3 秒少半秒左右
        assertTrue(elapsed in 2000..3200, "expected ~2500..3000ms, but was ${elapsed}ms")
    }

    /** 单块大于桶容量时不能死等 (退化成"能拿多少算多少") */
    @Test
    fun `chunk larger than capacity still completes`() = runTest {
        val limiter = ByteRateLimiter(1024) { currentTime }
        limiter.acquire(Int.MAX_VALUE)
        assertTrue(currentTime >= 0)
    }
}
