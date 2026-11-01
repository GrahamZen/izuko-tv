/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.search

import androidx.paging.LoadState
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.compose.launchAsLazyPagingItemsIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.him188.ani.utils.coroutines.flows.FailedReadRetry
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** 分页列表收到 [FailedReadRetry] 时只重试失败了的加载. */
@OptIn(ExperimentalCoroutinesApi::class)
class LazyPagingItemsFailedReadRetryTest {
    private class FlakySource(var failing: Boolean) : PagingSource<Int, String>() {
        var loads = 0

        override fun getRefreshKey(state: PagingState<Int, String>): Int? = null

        override suspend fun load(params: LoadParams<Int>): LoadResult<Int, String> {
            loads++
            return if (failing) LoadResult.Error(RuntimeException("network")) else LoadResult.Page(listOf("a"), null, null)
        }
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a failed load is retried on request`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val source = FlakySource(failing = true)
        val items = Pager(PagingConfig(pageSize = 10)) { source }.flow.launchAsLazyPagingItemsIn(scope)
        advanceUntilIdle()
        assertEquals(1, source.loads)
        assertIs<LoadState.Error>(items.loadState.refresh)

        source.failing = false
        FailedReadRetry.request()
        advanceUntilIdle()

        assertEquals(2, source.loads)
        assertEquals(1, items.itemCount)
        assertIs<LoadState.NotLoading>(items.loadState.refresh)
        scope.cancel()
    }

    @Test
    fun `a loaded list is not loaded again on request`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val source = FlakySource(failing = false)
        val items = Pager(PagingConfig(pageSize = 10)) { source }.flow.launchAsLazyPagingItemsIn(scope)
        advanceUntilIdle()
        assertEquals(1, source.loads)

        FailedReadRetry.request()
        advanceUntilIdle()

        assertEquals(1, source.loads)
        assertEquals(1, items.itemCount)
        scope.cancel()
    }
}
