/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.repository.subject

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [SubjectCollectionRepository.subjectCollectionFlow] 在仓库里有十几个调用点, 每个都是独立冷流、
 * 各跑一遍"要不要 fetch"的判定 —— 实测一次进详情页发 3 份重复请求, 而落库那段
 * ("读 oldIds → upsert → delete 差集") 没有事务, 多副本交错就可能删掉对方刚写进去的行.
 * 这些用例钉住的就是"只取一次"; 末尾两条钉住"调用者走开也要落库" (见 [StaleKeyedFetcher] 的 KDoc).
 */
class StaleKeyedFetcherTest {
    /**
     * 取数的归属作用域: **Job 与测试协程脱钩** (`minusKey(Job)`), 这样"调用者被取消"才不会
     * 顺带把它一起取消 —— 正是生产里 hero 流水线换焦点那个场景. 仍用测试调度器, 虚拟时间照走.
     */
    private fun TestScope.detachedScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + coroutineContext.minusKey(Job))

    @Test
    fun `同一个 key 并发 - 只取一次`() = runTest {
        val fetcher = StaleKeyedFetcher<Int>(detachedScope())
        var fetched = 0
        var fresh = false
        // 让首个取数挂住, 确保其余几个确实是在它进行中挤进来的
        val release = CompletableDeferred<Unit>()

        val jobs = (1..8).map {
            async {
                fetcher.fetchIfStale(
                    key = 1,
                    isFresh = { fresh },
                ) {
                    fetched++
                    release.await()
                    fresh = true
                }
            }
        }
        release.complete(Unit)
        jobs.awaitAll()

        assertEquals(1, fetched, "8 个并发调用应当只取一次")
    }

    @Test
    fun `已经新鲜 - 一次都不取`() = runTest {
        val fetcher = StaleKeyedFetcher<Int>(detachedScope())
        var fetched = 0
        repeat(3) {
            fetcher.fetchIfStale(key = 1, isFresh = { true }) { fetched++ }
        }
        assertEquals(0, fetched)
    }

    @Test
    fun `不同 key 互不阻塞 - 各取各的`() = runTest {
        val fetcher = StaleKeyedFetcher<Int>(detachedScope())
        val fetched = mutableSetOf<Int>()
        // 全部挂住再一起放行: 锁若是全局的或分桶的, 这里会死等
        val release = CompletableDeferred<Unit>()
        val jobs = (1..3).map { key ->
            async {
                fetcher.fetchIfStale(key = key, isFresh = { false }) {
                    release.await()
                    fetched += key
                }
            }
        }
        release.complete(Unit)
        jobs.awaitAll()
        assertEquals(setOf(1, 2, 3), fetched)
    }

    @Test
    fun `取数失败 - 异常传给本次调用者, 下次还能重试`() = runTest {
        val fetcher = StaleKeyedFetcher<Int>(detachedScope())
        var attempts = 0

        assertFailsWith<IllegalStateException> {
            fetcher.fetchIfStale(key = 1, isFresh = { false }) {
                attempts++
                error("boom")
            }
        }
        // 锁必须已经释放, 且不能因为失败就把这个 key 记成"取过了"
        fetcher.fetchIfStale(key = 1, isFresh = { false }) { attempts++ }
        assertEquals(2, attempts)
    }

    /**
     * **这条是 2026-09-06 那个 bug 的回归闸门**: TV 上 hero 流水线在 `collectLatest` 里解析
     * 聚焦卡片, 焦点一挪就取消上游. 取数原先挂在调用方协程上, 于是"条目已取到、分集在途、
     * 落库还没轮到"的活儿被整片作废 —— 关联条目行里划过去的条目进详情页连一张选集卡骨架都没有,
     * 而且每次进页都从零重取 (「详情页非常慢」同一个因).
     *
     * main 上不会遇到: Ani 的列表接口把分集内联一起下发, 进 Room 时就齐了, 根本没有"进详情页
     * 才取分集"这条会被取消的路径.
     */
    @Test
    fun `调用者被取消 - 取数照样跑完落库`() = runTest {
        val fetcher = StaleKeyedFetcher<Int>(detachedScope())
        var landed = false
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val caller = launch {
            fetcher.fetchIfStale(key = 1, isFresh = { false }) {
                started.complete(Unit)
                release.await() // 模拟在途的网络请求
                landed = true // 落库
            }
        }
        started.await()
        caller.cancelAndJoin() // = 焦点挪走, collectLatest 掐掉上一个目标

        release.complete(Unit)
        runCurrent()
        assertTrue(landed, "调用者走开不该把落库前的取数掐掉")
    }

    @Test
    fun `首个调用者被取消 - 后来者合流而不是重取一遍`() = runTest {
        val fetcher = StaleKeyedFetcher<Int>(detachedScope())
        var fetched = 0
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val first = launch {
            fetcher.fetchIfStale(key = 1, isFresh = { false }) {
                fetched++
                started.complete(Unit)
                release.await()
            }
        }
        started.await()
        first.cancelAndJoin()

        // 第二个调用者进来时任务仍在途: 它该合流等同一个任务, 而不是再发一遍请求
        val second = launch {
            fetcher.fetchIfStale(key = 1, isFresh = { false }) { fetched++ }
        }
        runCurrent()
        release.complete(Unit)
        second.join()

        assertEquals(1, fetched, "在途任务必须被合流, 不能因为首个调用者被取消就重取")
    }
}
