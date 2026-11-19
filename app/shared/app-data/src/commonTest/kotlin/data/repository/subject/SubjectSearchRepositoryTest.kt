/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.repository.subject

import androidx.paging.PagingSource
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.data.network.AniSubjectSearchService
import me.him188.ani.app.data.network.BatchSubjectDetails
import me.him188.ani.app.data.repository.RepositoryServiceUnavailableException
import me.him188.ani.app.domain.search.SubjectSearchQuery
import me.him188.ani.datasources.bangumi.apis.DefaultApi
import me.him188.ani.utils.ktor.ApiInvoker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SubjectSearchRepositoryTest {
    private val requestedOffsets = mutableListOf<String?>()

    private fun pagingSource(vararg statuses: HttpStatusCode): PagingSource<Int, BatchSubjectDetails> {
        val remaining = statuses.toMutableList()
        val client = DefaultApi(
            baseUrl = "http://test",
            httpClientEngine = MockEngine { request ->
                assertEquals("/v0/search/subjects", request.url.encodedPath)
                requestedOffsets.add(request.url.parameters["offset"])
                respond(
                    """{"total":0,"limit":20,"offset":0,"data":[]}""",
                    remaining.removeFirstOrNull() ?: HttpStatusCode.OK,
                    headersOf("Content-Type", "application/json"),
                )
            },
            httpClientConfig = { it.expectSuccess = true },
        )
        val service = AniSubjectSearchService(object : ApiInvoker<DefaultApi> {
            override suspend fun <R> invoke(action: suspend DefaultApi.() -> R): R = action(client)
        })
        return SubjectSearchRepository(service)
            .SubjectSearchPagingSource({ false }, SubjectSearchQuery(keywords = "Ave Mujica"))
    }

    private fun refresh(key: Int?) = PagingSource.LoadParams.Refresh(key, loadSize = 20, placeholdersEnabled = false)

    @Test
    fun `server error on first page is reported as service unavailable`() = runTest {
        val result = pagingSource(HttpStatusCode.BadGateway).load(refresh(0))

        assertIs<RepositoryServiceUnavailableException>(assertIs<PagingSource.LoadResult.Error<*, *>>(result).throwable)
    }

    @Test
    fun `refresh after an error has no key and searches from the first page`() = runTest {
        val result = pagingSource().load(refresh(null))

        assertIs<PagingSource.LoadResult.Page<Int, BatchSubjectDetails>>(result)
        assertEquals(listOf<String?>("0"), requestedOffsets)
    }
}
