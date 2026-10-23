/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.datasources.api.paging

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KeywordSearchesTest {
    private class ListSource(override val results: Flow<Int>) : SizedSource<Int> {
        override val finished: Flow<Boolean> = flowOf(true)
        override val totalSize: Flow<Int?> = flowOf(null)
    }

    private fun ok(vararg items: Int) = ListSource(flowOf(*items.toTypedArray()))
    private fun failing(vararg before: Int) = ListSource(
        flow {
            before.forEach { emit(it) }
            throw IllegalStateException("HTTP 500")
        },
    )

    @Test
    fun `a failed keyword keeps the results of the other keywords`() = runTest {
        val merged = mergeKeywordSearches(
            "test",
            listOf("first" to ok(1, 2), "broken" to failing(9), "third" to ok(3)),
        )
        assertEquals(setOf(1, 2, 3, 9), merged.results.toList().toSet())
        assertTrue(merged.finished.first())
    }

    @Test
    fun `the source fails when every keyword fails`() = runTest {
        val merged = mergeKeywordSearches("test", listOf("a" to failing(), "b" to failing()))
        assertFailsWith<IllegalStateException> { merged.results.toList() }
    }

    @Test
    fun `a single keyword is searched as before`() = runTest {
        assertEquals(listOf(1, 2), mergeKeywordSearches("test", listOf("only" to ok(1, 2))).results.toList())
        assertFailsWith<IllegalStateException> {
            mergeKeywordSearches("test", listOf("only" to failing())).results.toList()
        }
        assertEquals(emptyList(), mergeKeywordSearches<Int>("test", emptyList()).results.toList())
    }
}
