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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.data.repository.media.SelectorMediaSourceEpisodeCacheRepository
import me.him188.ani.app.domain.mediasource.web.InMemoryWebSearchSessionCacheDao
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.source.MediaFetchNameHints
import me.him188.ani.datasources.api.source.MediaFetchRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.hours

/**
 * 规则源记下「这个名字最近什么都没搜到」只对那一部番算数 ([RuleMediaSource] 的 recentlyEmpty), 有条目页没取到的那一轮不记.
 */
class RuleMediaSourceRecentlyEmptyTest {
    private fun request(subjectId: String, names: List<String>, hints: MediaFetchNameHints = MediaFetchNameHints.Empty) = MediaFetchRequest(
        subjectId = subjectId,
        episodeId = "1",
        subjectNames = names,
        episodeSort = EpisodeSort(1),
        episodeName = "",
        nameHints = hints,
    )

    private fun page(html: String) = html

    @Test
    fun `nothing found for one season does not stop the other season from searching the same series name`() = runTest {
        // 站点上只有第一季
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            val html = when {
                path.startsWith("/search/") -> page("""<div class="result"><a href="/subject/1.html">铃芽之旅</a></div>""")
                path == "/subject/1.html" -> page("""<ul class="list"><li><a href="/play/1-1.html">第1集</a></li></ul>""")
                else -> return@MockEngine respondError(HttpStatusCode.NotFound)
            }
            respond(html, headers = headersOf(HttpHeaders.ContentType, ContentType.Text.Html.toString()))
        }
        val sessionScope = CoroutineScope(SupervisorJob())
        try {
            val source = createTestRuleMediaSource(
                engine, SelectorMediaSourceEpisodeCacheRepository(InMemoryWebSearchSessionCacheDao(), flowOf(1.hours)), sessionScope,
            )
            // 第二季拿系列名「铃芽之旅」搜, 站点上那一页是第一季, 被排除
            val secondSeason = request(
                "2", listOf("铃芽之旅 续"),
                MediaFetchNameHints(ownNames = listOf("铃芽之旅 续"), seriesNames = listOf("铃芽之旅"), otherWorkNames = listOf("铃芽之旅")),
            )
            assertEquals(emptyList(), source.fetch(secondSeason).results.toList())

            // 第一季也拿「铃芽之旅」搜, 照样要搜到
            val firstSeason = source.fetch(request("1", listOf("铃芽之旅"))).results.toList().map { it.media.originalUrl }
            assertEquals(listOf("https://example.com/play/1-1.html"), firstSeason)
        } finally {
            sessionScope.cancel()
        }
    }

    @Test
    fun `a round whose subject page failed to load is searched again next time`() = runTest {
        var pageRequests = 0
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            val html = when {
                path.startsWith("/search/") -> page("""<div class="result"><a href="/subject/1.html">铃芽之旅</a></div>""")
                // 条目页第一轮出错 (连同重试的那次), 之后正常
                path == "/subject/1.html" -> if (pageRequests++ < 2) return@MockEngine respondError(HttpStatusCode.InternalServerError)
                else page("""<ul class="list"><li><a href="/play/1-1.html">第1集</a></li></ul>""")

                else -> return@MockEngine respondError(HttpStatusCode.NotFound)
            }
            respond(html, headers = headersOf(HttpHeaders.ContentType, ContentType.Text.Html.toString()))
        }
        val sessionScope = CoroutineScope(SupervisorJob())
        try {
            val source = createTestRuleMediaSource(
                engine, SelectorMediaSourceEpisodeCacheRepository(InMemoryWebSearchSessionCacheDao(), flowOf(1.hours)), sessionScope,
            )
            val request = request("1", listOf("铃芽之旅"))
            assertEquals(emptyList(), source.fetch(request).results.toList())
            assertEquals(listOf("https://example.com/play/1-1.html"), source.fetch(request).results.toList().map { it.media.originalUrl })
        } finally {
            sessionScope.cancel()
        }
    }
}
