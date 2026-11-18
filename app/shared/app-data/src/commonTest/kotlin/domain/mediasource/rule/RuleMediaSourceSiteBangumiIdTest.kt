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
import me.him188.ani.datasources.api.source.MediaFetchRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.hours

/**
 * 规则源按站点在搜索结果里给的 Bangumi 编号认部 (见 SiteBangumiIds): 编号对上的开, 编号是别的条目的不开.
 */
class RuleMediaSourceSiteBangumiIdTest {
    private val kaguya2 = MediaFetchRequest(
        subjectId = "293049",
        episodeId = "1",
        subjectNames = listOf("辉夜大小姐想让我告白？～天才们的恋爱头脑战～", "かぐや様は告らせたい？～天才たちの恋愛頭脳戦～"),
        episodeSort = EpisodeSort(1),
        episodeName = "",
    )

    /** 搜索回 [searchBody] ([searchType] 类型); 条目页 `/anime/<n>` 列出一集 `/play/<n>-1.html`. */
    private fun site(searchPath: String, searchBody: String, searchType: ContentType) = MockEngine { request ->
        val path = request.url.encodedPath
        when {
            path == searchPath -> respond(searchBody, headers = headersOf(HttpHeaders.ContentType, searchType.toString()))
            path.startsWith("/anime/") -> respond(
                """<ul class="list"><li><a href="/play/${path.removePrefix("/anime/")}-1.html">01</a></li></ul>""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Text.Html.toString()),
            )

            else -> respondError(HttpStatusCode.NotFound)
        }
    }

    /** 自动匹配取到的资源: 剧集页地址与条目名. */
    private suspend fun fetch(engine: MockEngine, search: List<RuleStep>, request: MediaFetchRequest = kaguya2): List<Pair<String, String?>> {
        val sessionScope = CoroutineScope(SupervisorJob())
        try {
            val source = createTestRuleMediaSource(
                engine, SelectorMediaSourceEpisodeCacheRepository(InMemoryWebSearchSessionCacheDao(), flowOf(1.hours)), sessionScope,
                search = search,
            )
            return source.fetch(request).results.toList().map { it.media.originalUrl to it.media.properties.subjectName }
        } finally {
            sessionScope.cancel()
        }
    }

    @Test
    fun `json results open only the page whose bangumi id is this subject`() = runTest {
        // 两季的页名只差一个问号; 站点的字段分得清 (青空次元、TvTFun 的样子)
        val engine = site(
            "/api/video",
            """{"data":{"records":[
                {"id":11,"title":"辉夜大小姐想让我告白～天才们的恋爱头脑战～","bangumiId":248175},
                {"id":12,"title":"辉夜大小姐想让我告白？～天才们的恋爱头脑战～","bangumiId":293049},
                {"id":13,"title":"辉夜大小姐想让我告白？～天才们的恋爱头脑战～ 番外","bangumiId":"999999"}
            ]}}""",
            ContentType.Application.Json,
        )

        val media = fetch(
            engine,
            listOf(
                RuleStep.Fetch(url = "/api/video?keyword={keyword}"),
                RuleStep.JsonSubjects(listPath = "data.records", idPath = "id", namePath = "title", urlTemplate = "/anime/{id}"),
            ),
        )

        assertEquals(listOf("https://example.com/play/12-1.html" to "辉夜大小姐想让我告白？～天才们的恋爱头脑战～"), media)
    }

    @Test
    fun `an html result whose cover is this subject is opened under the name of this subject`() = runTest {
        // Moonci 的样子: 搜索页的封面用的是 Bangumi 式路径 (目录是编号的 MD5 前四位); 站点把第二季叫「自由之翼」, 名字认不出
        val engine = site(
            "/search",
            """
            <ul class="results">
              <li><a href="/anime/1" title="进击的巨人" data-original="https://img2.xfmanga.top/pic/cover/l/78/c9/55770_abcde.jpg"></a></li>
              <li><a href="/anime/2" title="自由之翼" data-original="https://img2.xfmanga.top/pic/cover/l/0e/ca/212293_fghij.jpg"></a></li>
            </ul>
            """.trimIndent(),
            ContentType.Text.Html,
        )
        val request = kaguya2.copy(subjectId = "212293", subjectNames = listOf("进击的巨人 第二季"))

        val media = fetch(engine, listOf(RuleStep.Fetch(url = "/search?wd={keyword}"), RuleStep.Subjects(list = listOf("ul.results li"))), request)

        assertEquals(listOf("https://example.com/play/2-1.html" to "进击的巨人 第二季"), media)
    }
}
