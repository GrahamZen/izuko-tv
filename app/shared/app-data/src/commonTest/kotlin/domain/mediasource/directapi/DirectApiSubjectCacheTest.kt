/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.directapi

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.source.MediaFetchRequest
import me.him188.ani.utils.ktor.ScopedHttpClient
import me.him188.ani.utils.ktor.asScopedHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TestTimeSource

/**
 * 站内条目 id 的缓存只记搜成了的结果: 搜索被取消、请求失败时下次照常再搜; 搜成了、本站确实没有的, 过一阵也再搜一次.
 */
class DirectApiSubjectCacheTest {
    private val config = DirectApiConfig(
        baseUrl = "https://api.example.com",
        subject = DirectApiConfig.SubjectConfig(
            request = DirectApiConfig.RequestConfig(url = "{baseUrl}/search?keyword={subjectName}", itemsPath = "list"),
            idPath = "id",
        ),
        episode = DirectApiConfig.EpisodeConfig(
            request = DirectApiConfig.RequestConfig(url = "{baseUrl}/episodes/{subjectId}", itemsPath = "list"),
            sortPath = "sort",
            valuePath = "id",
        ),
        lines = DirectApiConfig.LinesConfig(
            request = DirectApiConfig.RequestConfig(url = "{baseUrl}/lines/{subjectId}/{episodeId}", itemsPath = "list"),
            urlPath = "url",
        ),
    )

    private val request = MediaFetchRequest(
        subjectId = "100",
        episodeId = "1001",
        subjectNames = listOf("示例动画"),
        episodeSort = EpisodeSort(1),
        episodeName = "",
        episodes = listOf(MediaFetchRequest.Episode(episodeId = "1001", sort = EpisodeSort(1))),
    )

    private var searches = 0

    /** 第几次搜索 (从 1 数) 由 [search] 应答; 剧集与地址照常给. 与应用里一样, 非 2xx 当作失败抛出. */
    private fun client(search: suspend MockRequestHandleScope.(call: Int) -> HttpResponseData): ScopedHttpClient =
        HttpClient(
            MockEngine { request ->
                val path = request.url.encodedPath
                when {
                    path.startsWith("/search") -> search(++searches)
                    path == "/episodes/7" -> json("""{"list":[{"id":"1","sort":"1"}]}""")
                    path == "/lines/7/1" -> json("""{"list":[{"url":"$LINE"}]}""")
                    else -> respondError(HttpStatusCode.NotFound)
                }
            },
        ) { expectSuccess = true }.asScopedHttpClient()

    private fun MockRequestHandleScope.json(body: String) = respond(ByteReadChannel(body), HttpStatusCode.OK, headersOf())

    @Test
    fun `a search cancelled midway is searched again next time`() = runTest {
        val searching = CompletableDeferred<Unit>()
        val engine = DirectApiEngine(
            config,
            client { call ->
                if (call == 1) {
                    searching.complete(Unit)
                    awaitCancellation()
                }
                json(FOUND)
            },
        )
        // 自动选源选定了别的源, 暂停还在查的源: 这一次搜索被取消
        val first = launch { engine.queryLinks(request) }
        searching.await()
        first.cancelAndJoin()

        assertEquals(listOf(LINE), engine.queryLinks(request).map { it.url })
        assertEquals(2, searches)
    }

    @Test
    fun `a failed search request is searched again next time`() = runTest {
        val engine = DirectApiEngine(
            config,
            client { call -> if (call == 1) respondError(HttpStatusCode.ServiceUnavailable) else json(FOUND) },
        )
        assertEquals(emptyList(), engine.queryLinks(request))

        assertEquals(listOf(LINE), engine.queryLinks(request).map { it.url })
        assertEquals(2, searches)
    }

    @Test
    fun `a subject the site does not have is searched again after a while`() = runTest {
        val time = TestTimeSource()
        val engine = DirectApiEngine(config, client { json("""{"list":[]}""") }, time)
        engine.queryLinks(request)
        engine.queryLinks(request)
        // 搜成了、确实没有: 一阵子内不再搜
        assertEquals(1, searches)

        time += 31.minutes
        engine.queryLinks(request)
        assertEquals(2, searches)
    }

    @Test
    fun `a found subject is not searched again`() = runTest {
        val time = TestTimeSource()
        val engine = DirectApiEngine(config, client { json(FOUND) }, time)
        engine.queryLinks(request)
        time += 31.minutes
        assertEquals(listOf(LINE), engine.queryLinks(request).map { it.url })
        assertEquals(1, searches)
    }

    private companion object {
        const val FOUND = """{"list":[{"id":"7"}]}"""
        const val LINE = "https://v.example.com/7-1.mp4"
    }
}
