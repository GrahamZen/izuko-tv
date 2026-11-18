/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.jsonapi

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.source.MediaFetchRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration

/**
 * JSON API 数据源按站点在搜索结果里给的 Bangumi 编号认部 (见 SiteBangumiIds), 不用配置.
 */
class JsonApiMediaSourceSiteBangumiIdTest {
    private val episodeRequests = mutableListOf<String>()

    /** 稀饭的样子: 封面路径是 Bangumi 式的 (目录是编号的 MD5 前四位). */
    private fun site(searchResponse: String) = MockEngine { request ->
        val url = request.url.toString()
        when {
            url == "https://api.example.com/search" && request.method == HttpMethod.Post -> respondJson(searchResponse)
            url.startsWith("https://api.example.com/episodes?anime_id=") -> {
                episodeRequests += url.substringAfter("anime_id=").substringBefore('&')
                respondJson(SampleSite.EPISODES_RESPONSE)
            }

            else -> respondError(HttpStatusCode.NotFound)
        }
    }

    private fun request(subjectId: String) = MediaFetchRequest(
        subjectId = subjectId,
        episodeId = "1",
        subjectNames = listOf(SampleSite.SUBJECT_NAME),
        episodeSort = EpisodeSort(1),
        episodeName = "",
    )

    @Test
    fun `of two pages with the same name only the one with the cover of this subject is opened`() = runTest {
        val engine = site(
            """
            [{"id": 3253, "title": "葬送的芙莉莲 第二季", "cover_url": "https://img2.xfmanga.top/r/800/pic/cover/l/13/c5/400602_ZI8Y9.jpg"},
             {"id": 3252, "title": "葬送的芙莉莲 第二季", "cover_url": "https://img2.xfmanga.top/r/800/pic/cover/l/0b/24/515759_qA1Zc.jpg"}]
            """.trimIndent(),
        )
        val source = createTestJsonApiMediaSource(SampleSite.config.copy(requestInterval = Duration.ZERO), engine, getTimeMillis = { testScheduler.currentTime })

        val media = source.fetch(request("515759")).results.toList().map { it.media }

        assertEquals(listOf("3252"), episodeRequests)
        assertTrue(media.isNotEmpty() && media.all { it.mediaId.startsWith("json-api-test.3252-") }, media.map { it.mediaId }.toString())
    }

    @Test
    fun `a page whose name is not recognized is opened by its bangumi id under the name of this subject`() = runTest {
        val engine = site("""[{"id": 3252, "title": "芙莉莲 续篇", "bgm_id": "515759"}]""")
        val source = createTestJsonApiMediaSource(SampleSite.config.copy(requestInterval = Duration.ZERO), engine, getTimeMillis = { testScheduler.currentTime })

        val media = source.fetch(request("515759")).results.toList().map { it.media }

        assertEquals(listOf("3252"), episodeRequests)
        assertTrue(media.isNotEmpty() && media.all { it.properties.subjectName == SampleSite.SUBJECT_NAME }, media.map { it.properties.subjectName }.toString())
    }
}
