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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * [SubjectCollectionRepository.subjectCollectionFlow] 在仓库里有十几个调用点, 每个都是独立冷流、
 * 各跑一遍"要不要 fetch"的判定 —— 实测一次进详情页发 3 份重复请求, 而落库那段
 * ("读 oldIds → upsert → delete 差集") 没有事务, 多副本交错就可能删掉对方刚写进去的行.
 * 这些用例钉住的就是"只取一次".
 */
class StaleKeyedFetcherTest {
    @Test
    fun `同一个 key 并发 - 只取一次`() = runTest {
        val fetcher = StaleKeyedFetcher<Int>()
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
        val fetcher = StaleKeyedFetcher<Int>()
        var fetched = 0
        repeat(3) {
            fetcher.fetchIfStale(key = 1, isFresh = { true }) { fetched++ }
        }
        assertEquals(0, fetched)
    }

    @Test
    fun `不同 key 互不阻塞 - 各取各的`() = runTest {
        val fetcher = StaleKeyedFetcher<Int>()
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
        val fetcher = StaleKeyedFetcher<Int>()
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
}
