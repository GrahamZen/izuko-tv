/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.rule

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.data.repository.media.SelectorMediaSourceEpisodeCacheRepository
import me.him188.ani.app.domain.mediasource.web.InMemoryWebSearchSessionCacheDao
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.source.MediaFetchRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.hours

/** 规则源每开完一个条目页就写进搜索缓存: 先交出的那页被选中、搜索随即被暂停 (取消) 时, 缓存里仍有这一页. */
class RuleMediaSourcePageCacheTest {
    private val engine = MockEngine { request ->
        val path = request.url.encodedPath
        val html = when {
            path.startsWith("/search/") -> """
                <div class="result"><a href="/subject/1.html">铃芽之旅</a></div>
                <div class="result"><a href="/subject/2.html">铃芽之旅</a></div>
            """.trimIndent()

            path == "/subject/1.html" -> """<ul class="list"><li><a href="/play/1-1.html">第1集</a></li></ul>"""
            // 第二页一直取不完: 第一页的资源交出去被选中时它还在取
            path == "/subject/2.html" -> awaitCancellation()
            else -> return@MockEngine respondError(HttpStatusCode.NotFound)
        }
        respond(html, headers = headersOf(HttpHeaders.ContentType, ContentType.Text.Html.toString()))
    }

    @Test
    fun `a page delivered early is cached even when the search is stopped before the other pages finish`() = runTest {
        val sessionScope = CoroutineScope(SupervisorJob())
        try {
            val dao = InMemoryWebSearchSessionCacheDao()
            val repository = SelectorMediaSourceEpisodeCacheRepository(dao, flowOf(1.hours))
            val source = createTestRuleMediaSource(engine, repository, sessionScope)
            val request = MediaFetchRequest(
                subjectId = "8546",
                episodeId = "1",
                subjectNames = listOf("铃芽之旅"),
                episodeSort = EpisodeSort(1),
                episodeName = "",
            )

            // 拿到第一条就不再收: 同自动选源选中后暂停其余的搜索
            val first = source.fetch(request).results.first().media

            assertEquals("https://example.com/subject/1.html", dao.rows.single { it.playUrl == first.originalUrl }.subjectUrl)
        } finally {
            sessionScope.cancel()
        }
    }
}
