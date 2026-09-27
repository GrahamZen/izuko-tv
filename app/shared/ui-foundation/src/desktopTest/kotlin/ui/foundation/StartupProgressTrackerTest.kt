/*
 * Copyright (C) 2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class StartupProgressTrackerTest {
    @Test
    fun `ready once every started cover finished and no new one starts`() = runTest {
        val tracker = StartupProgressTracker()
        val ready = async { tracker.awaitFirstScreenReady() }
        repeat(3) { tracker.coverStarted() }
        repeat(3) { tracker.coverFinished() }
        advanceTimeBy(200)
        assertFalse(ready.isCompleted)

        // 下一行晚一拍才有数据: 重新等它加载完
        tracker.coverStarted()
        advanceTimeBy(400)
        assertFalse(ready.isCompleted)
        tracker.coverFinished()
        advanceTimeBy(249)
        assertFalse(ready.isCompleted)
        advanceTimeBy(2)
        assertTrue(ready.isCompleted)
    }

    @Test
    fun `stops waiting for covers that hang on the network`() = runTest {
        val tracker = StartupProgressTracker()
        val ready = async { tracker.awaitFirstScreenReady() }
        repeat(4) { tracker.coverStarted() }
        repeat(3) { tracker.coverFinished() }
        advanceTimeBy(499)
        assertFalse(ready.isCompleted)
        advanceTimeBy(2)
        assertTrue(ready.isCompleted)
    }

    @Test
    fun `gives up early when no cover ever starts`() = runTest {
        val tracker = StartupProgressTracker()
        tracker.awaitFirstScreenReady()
        assertEquals(4_000, currentTime)
    }

    @Test
    fun `stops waiting shortly after the page says no covers are coming`() = runTest {
        val tracker = StartupProgressTracker()
        val ready = async { tracker.awaitFirstScreenReady() }
        advanceTimeBy(300)
        tracker.expectNoCovers()
        advanceTimeBy(499)
        assertFalse(ready.isCompleted)
        advanceTimeBy(2)
        assertTrue(ready.isCompleted)
        assertEquals(801, currentTime)
    }

    @Test
    fun `still waits for covers that start right after the page says none are coming`() = runTest {
        val tracker = StartupProgressTracker()
        val ready = async { tracker.awaitFirstScreenReady() }
        tracker.expectNoCovers()
        advanceTimeBy(200)
        tracker.coverStarted()
        advanceTimeBy(1_000)
        assertFalse(ready.isCompleted)
        tracker.coverFinished()
        advanceTimeBy(251)
        assertTrue(ready.isCompleted)
    }

    @Test
    fun `never waits longer than the timeout`() = runTest {
        val tracker = StartupProgressTracker()
        tracker.coverStarted() // 一张都加载不出来
        tracker.awaitFirstScreenReady()
        assertEquals(8_000, currentTime)
    }

    @Test
    fun `waits for the image cache to open`() = runTest {
        val tracker = StartupProgressTracker()
        val cacheOpen = MutableStateFlow(0f)
        tracker.bindImageCacheOpenProgress(cacheOpen)
        val ready = async { tracker.awaitFirstScreenReady() }
        tracker.coverStarted()
        tracker.coverFinished()
        advanceTimeBy(1_000)
        assertFalse(ready.isCompleted)

        cacheOpen.value = 1f
        advanceTimeBy(251)
        assertTrue(ready.isCompleted)
    }

    @Test
    fun `fraction covers cache opening then covers and never goes back`() = runTest {
        val tracker = StartupProgressTracker()
        val cacheOpen = MutableStateFlow(0f)
        tracker.bindImageCacheOpenProgress(cacheOpen)
        val fractions = mutableListOf<Float>()
        val collecting = launch { tracker.fraction.toList(fractions) }
        runCurrent()

        cacheOpen.value = 0.5f
        runCurrent()
        cacheOpen.value = 1f
        runCurrent()
        repeat(2) { tracker.coverStarted() }
        tracker.coverFinished()
        runCurrent()
        // 又开始了两张: 比例变小, 进度条不往回走
        repeat(2) { tracker.coverStarted() }
        runCurrent()
        repeat(3) { tracker.coverFinished() }
        runCurrent()
        collecting.cancel()

        assertEquals(listOf(0, 10, 20, 60, 100), fractions.map { (it * 100).roundToInt() }.distinctRuns())
    }

    @Test
    fun `current fraction is readable without collecting`() {
        val tracker = StartupProgressTracker()
        // 缓存进度没接上: 那一段算已完成
        assertEquals(20, (tracker.currentFraction * 100).roundToInt())
        val cacheOpen = MutableStateFlow(0.5f)
        tracker.bindImageCacheOpenProgress(cacheOpen)
        assertEquals(10, (tracker.currentFraction * 100).roundToInt())
        cacheOpen.value = 1f
        repeat(4) { tracker.coverStarted() }
        tracker.coverFinished()
        assertEquals(40, (tracker.currentFraction * 100).roundToInt())
    }

    @Test
    fun `counts nothing after stop`() = runTest {
        val tracker = StartupProgressTracker()
        tracker.stop()
        tracker.coverStarted()
        tracker.coverFinished()
        tracker.awaitFirstScreenReady()
        // 没有封面被记下: 等满「一张都没开始」那段
        assertEquals(4_000, currentTime)
    }

    @Test
    fun `cold start is claimed once`() {
        val tracker = StartupProgressTracker()
        assertTrue(tracker.claimColdStart())
        assertFalse(tracker.claimColdStart())
    }

    /** 去掉相邻的重复值. */
    private fun List<Int>.distinctRuns(): List<Int> =
        fold(mutableListOf()) { acc, value -> if (acc.lastOrNull() != value) acc += value; acc }
}
