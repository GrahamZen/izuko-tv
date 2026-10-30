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
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

class RuleMediaSourceSiteAliasesTest {
    @Test
    fun `a page named by the site is opened as this subject by its alias`() = runTest {
        // 苹果 CMS 接口: 第二季的页只叫「赛马娘 第二季」, vod_sub 里才有条目服务上的名字
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/api.php/Vod/get_list" -> respond(
                    """{"code":1,"info":{"rows":[
                        {"vod_id":100,"vod_name":"赛马娘 第二季","vod_sub":"ウマ娘 プリティーダービー Season 2 / Pretty Derby / 马娘"},
                        {"vod_id":6261,"vod_name":"赛马娘","vod_sub":"ウマ娘 プリティーダービー"}
                    ]}}""",
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )

                "/detail/100" -> respond(
                    """<ul class="list"><li><a href="/play/100-1.html">01</a></li><li><a href="/play/100-2.html">02</a></li></ul>""",
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Text.Html.toString()),
                )

                else -> respondError(HttpStatusCode.NotFound)
            }
        }
        val sessionScope = CoroutineScope(SupervisorJob())
        try {
            val source = createTestRuleMediaSource(
                engine, SelectorMediaSourceEpisodeCacheRepository(InMemoryWebSearchSessionCacheDao(), flowOf(1.hours)), sessionScope,
                search = listOf(
                    RuleStep.Fetch(url = "/api.php/Vod/get_list?vod_name={keyword}"),
                    RuleStep.JsonSubjects(listPath = "info.rows", idPath = "vod_id", namePath = "vod_name", urlTemplate = "/detail/{id}"),
                ),
            )
            val names = listOf("赛马娘 Pretty Derby 第二季", "ウマ娘 プリティーダービー Season 2")
            val request = MediaFetchRequest(
                subjectId = "315574",
                episodeId = "1",
                subjectNames = names,
                episodeSort = EpisodeSort(1),
                episodeName = "",
                nameHints = MediaFetchNameHints(ownNames = names, otherWorkNames = listOf("赛马娘 Pretty Derby", "赛马娘")),
            )
            val media = source.fetch(request).results.toList().map { it.media }
            // 开的是第二季那页, 资源的条目名用别名 (选择器按它认部), 第一季那页不开
            assertEquals(listOf("https://example.com/play/100-1.html"), media.map { it.originalUrl })
            assertEquals(listOf("ウマ娘 プリティーダービー Season 2"), media.map { it.properties.subjectName })
        } finally {
            sessionScope.cancel()
        }
    }

    @Test
    fun `aliases are looked up only when no page name is this subject`() = runTest {
        // girigiri 的样子: 搜索接口不给 vod_sub, 每个条目的详情接口里才有
        val aliasRequests = mutableListOf<String>()
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            val json = when {
                path == "/api.php/Vod/get_list" -> """{"code":1,"info":{"rows":[
                    {"vod_id":100,"vod_name":"赛马娘 第二季"},
                    {"vod_id":6261,"vod_name":"赛马娘"},
                    {"vod_id":7,"vod_name":"赛马娘 Pretty Derby 第三季"}
                ]}}"""

                path == "/api.php/Vod/get_detail" -> {
                    val id = request.url.parameters["vod_id"]
                    aliasRequests += id.orEmpty()
                    when (id) {
                        "100" -> """{"code":1,"info":{"vod_id":100,"vod_name":"赛马娘 第二季","vod_sub":"ウマ娘 プリティーダービー Season 2 / Pretty Derby"}}"""
                        "6261" -> """{"code":1,"info":{"vod_id":6261,"vod_name":"赛马娘","vod_sub":"ウマ娘 プリティーダービー"}}"""
                        else -> """{"code":1,"info":{"vod_id":7,"vod_name":"赛马娘 Pretty Derby 第三季","vod_sub":""}}"""
                    }
                }

                path.startsWith("/detail/") -> return@MockEngine respond(
                    """<ul class="list"><li><a href="/play${path.removePrefix("/detail")}-1.html">01</a></li></ul>""",
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Text.Html.toString()),
                )

                else -> return@MockEngine respondError(HttpStatusCode.NotFound)
            }
            respond(json, headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }
        val sessionScope = CoroutineScope(SupervisorJob())
        try {
            val source = createTestRuleMediaSource(
                engine, SelectorMediaSourceEpisodeCacheRepository(InMemoryWebSearchSessionCacheDao(), flowOf(1.hours)), sessionScope,
                search = listOf(
                    RuleStep.Fetch(url = "/api.php/Vod/get_list?vod_name={keyword}"),
                    RuleStep.JsonSubjects(
                        listPath = "info.rows", idPath = "vod_id", namePath = "vod_name", urlTemplate = "/detail/{id}",
                        aliasUrl = "/api.php/Vod/get_detail?vod_id={id}", aliasListPath = "info",
                    ),
                ),
            )
            fun request(subjectId: String, names: List<String>, others: List<String>) = MediaFetchRequest(
                subjectId = subjectId,
                episodeId = "1",
                subjectNames = names,
                episodeSort = EpisodeSort(1),
                episodeName = "",
                nameHints = MediaFetchNameHints(ownNames = names, otherWorkNames = others),
            )

            // 第三季: 有一页的名字就是它, 不另取别名
            val third = source.fetch(request("3", listOf("赛马娘 Pretty Derby 第三季"), listOf("赛马娘 Pretty Derby", "赛马娘"))).results.toList()
            assertEquals(listOf("赛马娘 Pretty Derby 第三季"), third.map { it.media.properties.subjectName })
            assertEquals(emptyList(), aliasRequests)

            // 第二季: 页名都对不上, 取回别名后按别名认出那一页
            val names = listOf("赛马娘 Pretty Derby 第二季", "ウマ娘 プリティーダービー Season 2")
            // (写明别的季的页照样排在最后打开, 由选择器排除; 第一季那页不开)
            val second = source.fetch(request("2", names, listOf("赛马娘 Pretty Derby", "赛马娘"))).results.toList().map { it.media }
            assertEquals("ウマ娘 プリティーダービー Season 2", second.single { it.originalUrl == "https://example.com/play/100-1.html" }.properties.subjectName)
            assertTrue(second.none { it.originalUrl == "https://example.com/play/6261-1.html" })
            assertEquals(setOf("100", "6261", "7"), aliasRequests.toSet())
        } finally {
            sessionScope.cancel()
        }
    }
}
