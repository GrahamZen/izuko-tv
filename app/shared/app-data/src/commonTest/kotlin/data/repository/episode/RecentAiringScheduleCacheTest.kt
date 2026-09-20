/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.repository.episode

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import me.him188.ani.app.data.models.subject.LightEpisodeInfo
import me.him188.ani.app.data.models.subject.LightSubjectInfo
import me.him188.ani.app.domain.episode.AiringScheduleForDate
import me.him188.ani.app.domain.episode.EpisodeWithAiringTime
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.PackedDate
import me.him188.ani.datasources.api.UTC9
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * 覆盖 [RecentAiringScheduleCache]: 新鲜的缓存先出且不再请求; 过期 / 别的日子 / 别的时区不命中; 在途请求合流, 调用方取消
 * 不打断请求; 失败不缓存; invalidate 之后必走网络; 按 maxAge 刷新且内容没变不重复发.
 * 时间全走 runTest 的虚拟时间 ([virtualClock] 跟着调度器走); 缓存的作用域与仓库一样用 SupervisorJob.
 */
class RecentAiringScheduleCacheTest {
    private val timeZone = TimeZone.of("Asia/Shanghai")
    private val today = LocalDate(2026, 9, 13)

    private fun schedule(vararg subjectIds: Int): List<AiringScheduleForDate> = listOf(
        AiringScheduleForDate(
            date = today,
            list = subjectIds.map { id ->
                EpisodeWithAiringTime(
                    subject = LightSubjectInfo(subjectId = id, name = "Subject $id", nameCn = "", imageLarge = ""),
                    episode = LightEpisodeInfo(
                        episodeId = id * 10,
                        name = "Ep",
                        nameCn = "",
                        airDate = PackedDate(2026, 9, 13),
                        timezone = UTC9,
                        sort = EpisodeSort(1),
                        ep = EpisodeSort(1),
                    ),
                    airingTime = Instant.parse("2026-09-13T12:00:00Z"),
                    timeKnown = true,
                )
            },
        ),
    )

    private fun TestScope.virtualClock(): Clock {
        val start = Instant.parse("2026-09-13T04:00:00Z")
        return object : Clock {
            override fun now(): Instant = start + testScheduler.currentTime.milliseconds
        }
    }

    private fun runCacheTest(block: suspend TestScope.(scope: CoroutineScope) -> Unit) = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            block(scope)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `peek has nothing until a fetch completes`() = runCacheTest { scope ->
        var calls = 0
        val cache = RecentAiringScheduleCache(scope, 1.hours, virtualClock()) { _, _ -> calls++; schedule(1) }
        assertNull(cache.peek(today, timeZone))
        assertEquals(schedule(1), cache.flow(today, timeZone).first())
        assertEquals(schedule(1), cache.peek(today, timeZone))
        assertEquals(1, calls)
    }

    @Test
    fun `a fresh cache is emitted first without fetching again`() = runCacheTest { scope ->
        var calls = 0
        val cache = RecentAiringScheduleCache(scope, 1.hours, virtualClock()) { _, _ -> calls++; schedule(calls) }
        cache.flow(today, timeZone).first()
        advanceTimeBy(59.minutes)
        assertEquals(schedule(1), cache.flow(today, timeZone).first())
        assertEquals(1, calls)
    }

    @Test
    fun `an expired cache or another day or time zone does not hit`() = runCacheTest { scope ->
        var calls = 0
        val cache = RecentAiringScheduleCache(scope, 1.hours, virtualClock()) { _, _ -> calls++; schedule(calls) }
        cache.flow(today, timeZone).first()
        assertNull(cache.peek(LocalDate(2026, 9, 14), timeZone))
        assertNull(cache.peek(today, TimeZone.of("America/Chicago")))
        advanceTimeBy(1.hours)
        assertNull(cache.peek(today, timeZone))
        assertEquals(schedule(2), cache.flow(today, timeZone).first())
        assertEquals(2, calls)
    }

    @Test
    fun `concurrent callers share one request and a cancelled caller does not cancel it`() = runCacheTest { scope ->
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val cache = RecentAiringScheduleCache(scope, 1.hours, virtualClock()) { _, _ ->
            calls++
            gate.await()
            schedule(1)
        }
        // 预取发出后调用方就走了 (探索页离开组合)
        val prefetch = launch { cache.flow(today, timeZone).first() }
        runCurrent()
        prefetch.cancel()
        // 进页接着等同一个请求
        val entry = async { cache.flow(today, timeZone).first() }
        runCurrent()
        gate.complete(Unit)
        assertEquals(schedule(1), entry.await())
        assertEquals(1, calls)
        assertEquals(schedule(1), cache.peek(today, timeZone))
    }

    @Test
    fun `a failure is not cached`() = runCacheTest { scope ->
        var calls = 0
        val cache = RecentAiringScheduleCache(scope, 1.hours, virtualClock()) { _, _ ->
            if (calls++ == 0) throw IllegalStateException("network down")
            schedule(1)
        }
        assertFailsWith<IllegalStateException> { cache.flow(today, timeZone).first() }
        assertNull(cache.peek(today, timeZone))
        assertEquals(schedule(1), cache.flow(today, timeZone).first())
        assertEquals(2, calls)
    }

    @Test
    fun `invalidate forces the next fetch`() = runCacheTest { scope ->
        var calls = 0
        val cache = RecentAiringScheduleCache(scope, 1.hours, virtualClock()) { _, _ -> calls++; schedule(calls) }
        cache.flow(today, timeZone).first()
        cache.invalidate()
        assertNull(cache.peek(today, timeZone))
        assertEquals(schedule(2), cache.flow(today, timeZone).first())
        assertEquals(2, calls)
    }

    @Test
    fun `refreshes every maxAge and emits only when the content changed`() = runCacheTest { scope ->
        val responses = listOf(schedule(1), schedule(1), schedule(2))
        var calls = 0
        val cache = RecentAiringScheduleCache(scope, 1.hours, virtualClock()) { _, _ -> responses[calls++] }
        val emitted = mutableListOf<List<AiringScheduleForDate>>()
        val collector = launch { cache.flow(today, timeZone).collect { emitted += it } }
        runCurrent()
        assertEquals(listOf(schedule(1)), emitted)
        advanceTimeBy(1.hours + 1.milliseconds)
        assertEquals(2, calls)
        assertEquals(listOf(schedule(1)), emitted) // 内容没变, 不重复发
        advanceTimeBy(1.hours)
        assertEquals(3, calls)
        assertEquals(listOf(schedule(1), schedule(2)), emitted)
        collector.cancel()
    }
}
