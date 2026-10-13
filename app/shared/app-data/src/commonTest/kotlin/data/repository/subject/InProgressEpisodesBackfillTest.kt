/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.repository.subject

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 「继续观看」刷新的两样东西: 补分集限并发、攒几部写一次库 ([backfillEpisodesConcurrently]);
 * 本地数据先出、刷新在后台跑 ([whileRefreshingInBackground]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InProgressEpisodesBackfillTest {
    @Test
    fun `saves every subject in batches`() = runTest {
        val saves = mutableListOf<List<Int>>()
        backfillEpisodesConcurrently(
            (1..10).toList(),
            fetch = { listOf(it) },
            save = { saves += it },
            concurrency = 3,
            flushEvery = 4,
        )
        assertEquals(listOf(4, 4, 2), saves.map { it.size })
        assertEquals((1..10).toList(), saves.flatten().sorted())
    }

    @Test
    fun `never runs more requests at once than allowed`() = runTest {
        var inFlight = 0
        var maxInFlight = 0
        backfillEpisodesConcurrently(
            (1..10).toList(),
            fetch = {
                inFlight++
                maxInFlight = maxOf(maxInFlight, inFlight)
                delay(100)
                inFlight--
                listOf(it)
            },
            save = {},
            concurrency = 3,
        )
        assertEquals(3, maxInFlight)
    }

    @Test
    fun `a slow subject does not hold back the others`() = runTest {
        val savedAt = mutableListOf<Pair<Long, List<Int>>>()
        backfillEpisodesConcurrently(
            (1..5).toList(),
            fetch = {
                delay(if (it == 1) 10_000 else 100)
                listOf(it)
            },
            save = { savedAt += currentTime to it },
            concurrency = 2,
            flushEvery = 2,
        )
        val (firstAt, firstBatch) = savedAt.first()
        assertTrue(firstAt < 10_000, "慢的那部还没回来时别的已经写库: $savedAt")
        assertEquals(listOf(2, 3), firstBatch.sorted())
        assertEquals((1..5).toList(), savedAt.flatMap { it.second }.sorted())
    }

    @Test
    fun `a failing subject is skipped and the rest are saved`() = runTest {
        val saved = mutableListOf<Int>()
        val failed = mutableListOf<Int>()
        backfillEpisodesConcurrently(
            (1..5).toList(),
            fetch = { if (it == 3) error("boom") else listOf(it) },
            save = { saved += it },
            onFailed = { subjectId, _ -> failed += subjectId },
        )
        assertEquals(listOf(3), failed)
        assertEquals(listOf(1, 2, 4, 5), saved.sorted())
    }

    @Test
    fun `local data comes out before the refresh finishes`() = runTest {
        val local = MutableStateFlow(listOf(1))
        val refreshGate = CompletableDeferred<Unit>()
        val flow = local.whileRefreshingInBackground {
            refreshGate.await()
            local.value = listOf(1, 2)
        }
        val collected = mutableListOf<List<Int>>()
        val job = launch { flow.toList(collected) }
        runCurrent()
        // 刷新还卡着: 本地已有的照样先出
        assertEquals(listOf(listOf(1)), collected)

        // 刷新写完库, 本地那条 flow 重发新数据
        refreshGate.complete(Unit)
        runCurrent()
        assertEquals(listOf(listOf(1), listOf(1, 2)), collected)
        job.cancel()
    }

    @Test
    fun `refresh stops with the collector`() = runTest {
        val refreshGate = CompletableDeferred<Unit>()
        var refreshCancelled = false
        val flow = MutableStateFlow(1).whileRefreshingInBackground {
            try {
                refreshGate.await()
            } catch (e: CancellationException) {
                refreshCancelled = true
                throw e
            }
        }
        val job = launch { flow.collect {} }
        runCurrent()
        job.cancel()
        job.join()
        assertTrue(refreshCancelled)
    }
}
