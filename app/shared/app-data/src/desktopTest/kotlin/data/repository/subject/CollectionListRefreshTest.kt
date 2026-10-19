/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.repository.subject

import kotlinx.coroutines.test.runTest
import me.him188.ani.app.data.repository.testSubjectCollectionEntity
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 收藏页刷新 ([syncCollectionList]): 条数对得上只取第一页; 对不上整类翻一遍, 再把本地多出来的交出去.
 */
class CollectionListRefreshTest {
    /** 服务端这一类的条目按顺序是 [serverIds]; 取到的写进 [local]. */
    private class Fixture(
        val serverIds: List<Int>,
        initialLocal: Collection<Int>,
        val reportedTotal: Int = serverIds.size,
        val failAtCall: Int? = null,
    ) {
        val local = initialLocal.toMutableSet()
        val requests = mutableListOf<Pair<Int, Int>>()
        val removed = mutableListOf<Int>()
        var reconciledFirstPage = false

        suspend fun run(): CollectionListSync = syncCollectionList(
            firstPageSize = 30,
            fullSyncPageSize = 100,
            fetchAndSave = { offset, limit ->
                requests += offset to limit
                if (requests.size == failAtCall) throw IOException("Connection reset")
                val ids = serverIds.drop(offset).take(limit)
                val changed = ids.any { it !in local }
                local += ids
                CollectionListPage(ids.map { testSubjectCollectionEntity(it) }, reportedTotal, changed)
            },
            reconcileFirstPage = { reconciledFirstPage = true },
            localIds = { local },
            removeLeftType = { removed += it },
        )
    }

    @Test
    fun `first page is enough when local count matches the server total`() = runTest {
        val f = Fixture(serverIds = (1..250).toList(), initialLocal = (1..250).toList())

        val sync = f.run()
        assertTrue(sync.inSync)
        assertFalse(sync.changed)
        assertEquals(listOf(0 to 30), f.requests)
        assertTrue(f.reconciledFirstPage)
        assertEquals(emptyList(), f.removed)
    }

    @Test
    fun `a new subject on the first page counts as a change`() = runTest {
        // 别处新加了一部想看: 排在第一页最前面, 本地条数也对上了
        val f = Fixture(serverIds = listOf(999) + (1..9).toList(), initialLocal = (1..9).toList())

        val sync = f.run()
        assertTrue(sync.inSync)
        assertTrue(sync.changed)
        assertEquals(listOf(0 to 30), f.requests)
    }

    @Test
    fun `count mismatch walks the whole list and hands over local extras`() = runTest {
        // 本地只取过前 60 条, 另有两条在别处移出了这一类
        val f = Fixture(serverIds = (1..250).toList(), initialLocal = (1..60).toList() + listOf(901, 902))

        val sync = f.run()
        assertTrue(sync.inSync)
        assertTrue(sync.changed)
        assertEquals(listOf(0 to 30, 30 to 100, 130 to 100, 230 to 100), f.requests)
        assertEquals((1..250).toSet() + setOf(901, 902), f.local)
        assertEquals(listOf(901, 902), f.removed)
    }

    @Test
    fun `walk stops at an empty page when the server has fewer than it reported`() = runTest {
        val f = Fixture(serverIds = (1..40).toList(), initialLocal = emptyList(), reportedTotal = 300)

        assertTrue(f.run().inSync)
        assertEquals(listOf(0 to 30, 30 to 100, 40 to 100), f.requests)
        assertEquals(emptyList(), f.removed)
    }

    @Test
    fun `failure during the walk keeps local rows and reports not in sync`() = runTest {
        val f = Fixture(serverIds = (1..250).toList(), initialLocal = (1..60).toList() + 901, failAtCall = 3)

        assertFalse(f.run().inSync)
        assertEquals(emptyList(), f.removed)
        assertTrue(901 in f.local)
    }
}
