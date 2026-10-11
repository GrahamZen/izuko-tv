/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.danmaku.dandanplay

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import me.him188.ani.danmaku.api.provider.DanmakuFetchRequest
import me.him188.ani.danmaku.api.provider.DanmakuMatchMethod
import me.him188.ani.danmaku.api.provider.DanmakuSeasonNumbering
import me.him188.ani.danmaku.api.provider.DanmakuVideoFile
import me.him188.ani.danmaku.dandanplay.data.DandanplayGetBangumiResponse
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.PackedDate
import me.him188.ani.utils.ktor.asScopedHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** 按 AniDB 条目编号定位分集 ([DandanplayEpisodeLocator]) 与作品详情接口的记录 / 限额暂停 ([DandanplayBangumiCalls]). */
class DandanplayEpisodeLocatorTest {
    @Test
    fun `direct episode id is computed only when the number is unambiguous`() {
        assertEquals(160630001L, directDandanplayEpisodeId(16063, request(EpisodeSort(1), EpisodeSort(1))))
        assertEquals(160630005L, directDandanplayEpisodeId(16063, request(EpisodeSort(5), episodeEp = null)))
        assertNull(directDandanplayEpisodeId(16063, request(EpisodeSort(13), EpisodeSort(1))), "系列内与条目内序号不同: 那部作品可能从 1 编也可能接着编")
        assertNull(
            directDandanplayEpisodeId(
                16063,
                request(EpisodeSort(1), EpisodeSort(1), seasonNumbering = DanmakuSeasonNumbering(seasonEpisode = 1, partEpisodeCount = 12)),
            ),
            "拆分季那部作品可能把两段并成一部",
        )
        assertNull(directDandanplayEpisodeId(16063, request(EpisodeSort("SP1"), episodeEp = null)), "特别篇不按正片编号")
        assertNull(directDandanplayEpisodeId(16063, request(EpisodeSort("1.5"), episodeEp = null)))
    }

    @Test
    fun `fetches danmaku of the computed episode together with the episode list`() = runTest {
        val seen = mutableListOf<String>()
        val provider = createProvider { url ->
            seen += url.encodedPath
            when (url.encodedPath) {
                "/api/v2/bangumi/16063" -> respondJson(animeJson(16063, "孤独摇滚！", 1..12, mapOf(1 to "翻转孤独")))
                "/api/v2/comment/160630001" -> respondJson(ONE_COMMENT)
                else -> error("Unexpected request: $url")
            }
        }
        val result = provider.fetchAutomatic(request(EpisodeSort(1), EpisodeSort(1), anidbId = 16063)).single()

        assertEquals("第1话 翻转孤独", assertIs<DanmakuMatchMethod.Exact>(result.matchInfo.method).episodeTitle)
        assertEquals(160630001L, result.matchInfo.sourceEpisodeId)
        assertEquals(1, result.list.size)
        assertEquals(listOf("/api/v2/bangumi/16063", "/api/v2/comment/160630001"), seen.sorted(), "弹幕只取一次")
    }

    @Test
    fun `takes the episode the list matches when the computed one differs`() = runTest {
        val provider = createProvider { url ->
            when (url.encodedPath) {
                // 列表里第 1 话的编号不是 0001 (前面有一个被当作正片的总集篇)
                "/api/v2/bangumi/16063" -> respondJson(
                    """{"success": true, "errorCode": 0, "bangumi": {"animeTitle": "孤独摇滚！", "type": "tvseries", "episodes": [
                        {"episodeId": 160630001, "episodeTitle": "第0话 序章", "episodeNumber": "0", "lastWatched": null, "airDate": null},
                        {"episodeId": 160630002, "episodeTitle": "第1话 翻转孤独", "episodeNumber": "1", "lastWatched": null, "airDate": null}]}}""",
                )

                "/api/v2/comment/160630001" -> respondJson(ONE_COMMENT)
                "/api/v2/comment/160630002" -> respondJson(EMPTY_COMMENTS)
                else -> error("Unexpected request: $url")
            }
        }
        val result = provider.fetchAutomatic(request(EpisodeSort(1), EpisodeSort(1), anidbId = 16063)).single()

        assertEquals(160630002L, result.matchInfo.sourceEpisodeId)
        assertEquals(0, result.list.size)
    }

    @Test
    fun `uses the computed episode while the episode list is over quota and stops calling the list`() = runTest {
        val seen = mutableListOf<String>()
        val provider = createProvider { url ->
            seen += url.encodedPath
            when (url.encodedPath) {
                "/api/v2/bangumi/16063" -> respondJson(QUOTA_EXCEEDED)
                "/api/v2/comment/160630001", "/api/v2/comment/160630002" -> respondJson(ONE_COMMENT)
                else -> error("Unexpected request: $url")
            }
        }
        val first = provider.fetchAutomatic(request(EpisodeSort(1), EpisodeSort(1), anidbId = 16063)).single()
        val second = provider.fetchAutomatic(request(EpisodeSort(2), EpisodeSort(2), anidbId = 16063)).single()

        assertEquals(160630001L, first.matchInfo.sourceEpisodeId)
        assertEquals(160630002L, second.matchInfo.sourceEpisodeId)
        assertEquals(1, seen.count { it == "/api/v2/bangumi/16063" }, "被限额后暂停调用作品详情")
    }

    @Test
    fun `does not trust a computed episode without danmaku when the list is unavailable`() = runTest {
        val seen = mutableListOf<String>()
        val provider = createProvider { url ->
            seen += url.encodedPath
            when (url.encodedPath) {
                "/api/v2/bangumi/16063" -> respondJson(QUOTA_EXCEEDED)
                "/api/v2/comment/160630001" -> respondJson(EMPTY_COMMENTS)
                "/api/v2/search/episodes" -> respondJson(EMPTY_EPISODE_SEARCH)
                else -> error("Unexpected request: $url")
            }
        }
        val result = provider.fetchAutomatic(request(EpisodeSort(1), EpisodeSort(1), anidbId = 16063)).single()

        assertIs<DanmakuMatchMethod.NoMatch>(result.matchInfo.method)
        assertFalse("/api/v2/bangumi/bgmtv/1" in seen, "按 Bangumi 映射也是作品详情接口, 暂停中不调")
        assertTrue("/api/v2/search/episodes" in seen, "照旧按名字搜")
    }

    @Test
    fun `episode list is reused for the next episode and fetched again when it lacks the episode`() = runTest {
        var listCalls = 0
        val provider = createProvider { url ->
            when (url.encodedPath) {
                "/api/v2/bangumi/16063" -> {
                    listCalls++
                    respondJson(animeJson(16063, "孤独摇滚！", if (listCalls == 1) 1..2 else 1..3))
                }

                "/api/v2/comment/160630001", "/api/v2/comment/160630002", "/api/v2/comment/160630003" -> respondJson(EMPTY_COMMENTS)
                else -> error("Unexpected request: $url")
            }
        }
        provider.fetchAutomatic(request(EpisodeSort(1), EpisodeSort(1), anidbId = 16063))
        provider.fetchAutomatic(request(EpisodeSort(2), EpisodeSort(2), anidbId = 16063))
        assertEquals(1, listCalls, "同一部番看下一集不再取列表")

        val third = provider.fetchAutomatic(request(EpisodeSort(3), EpisodeSort(3), anidbId = 16063)).single()
        assertEquals(2, listCalls, "记着的列表里没有第 3 集: 重取一次")
        assertEquals(160630003L, third.matchInfo.sourceEpisodeId)
    }

    @Test
    fun `waits for the video file only when matching by subject and episode fails`() = runTest {
        var awaited = 0
        val awaitVideoFile: suspend () -> DanmakuVideoFile? = {
            awaited++
            DanmakuVideoFile("[Group] Bocchi the Rock! - 01 [1080P].mkv", null, null, 24.minutes)
        }
        val provider = createProvider { url ->
            when (url.encodedPath) {
                "/api/v2/bangumi/16063" -> respondJson(animeJson(16063, "孤独摇滚！", 1..12))
                "/api/v2/comment/160630001" -> respondJson(EMPTY_COMMENTS)
                "/api/v2/bangumi/bgmtv/1" -> respondJson(NOT_FOUND)
                "/api/v2/search/episodes" -> respondJson(EMPTY_EPISODE_SEARCH)
                "/api/v2/match" -> respondJson(
                    """{"isMatched": true, "matches": [{"episodeId": 160630001, "animeId": 16063, "animeTitle": "孤独摇滚！", "episodeTitle": "第1话",
                       "shift": 0, "type": "tvseries"}], "errorCode": 0, "success": true, "errorMessage": ""}""",
                )

                else -> error("Unexpected request: $url")
            }
        }
        provider.fetchAutomatic(request(EpisodeSort(1), EpisodeSort(1), anidbId = 16063, awaitVideoFile = awaitVideoFile))
        assertEquals(0, awaited, "按条目对上了就不等视频文件")

        val byFile = provider.fetchAutomatic(request(EpisodeSort(1), EpisodeSort(1), awaitVideoFile = awaitVideoFile)).single()
        assertEquals(1, awaited)
        assertIs<DanmakuMatchMethod.Fuzzy>(byFile.matchInfo.method)
        assertEquals(160630001L, byFile.matchInfo.sourceEpisodeId)
    }

    @Test
    fun `name search stops at the first name that finds the anime`() = runTest {
        val keywords = mutableListOf<String>()
        val provider = createProvider { url ->
            when (url.encodedPath) {
                "/api/v2/bangumi/bgmtv/1" -> respondJson(NOT_FOUND)
                "/api/v2/bangumi/season/anime/2022/10" -> respondJson("""{"success": true, "errorCode": 0, "bangumiList": []}""")
                "/api/v2/search/anime" -> {
                    keywords += url.parameters["keyword"].orEmpty()
                    respondJson("""{"success": true, "errorCode": 0, "animes": [{"animeId": 16063, "animeTitle": "孤独摇滚！"}]}""")
                }

                "/api/v2/bangumi/16063" -> respondJson(animeJson(16063, "孤独摇滚！", 1..12))
                "/api/v2/comment/160630001" -> respondJson(EMPTY_COMMENTS)
                else -> error("Unexpected request: $url")
            }
        }
        provider.fetchAutomatic(
            request(
                EpisodeSort(1), EpisodeSort(1),
                subjectNames = listOf("孤独摇滚！", "ぼっち・ざ・ろっく！", "Bocchi the Rock!"),
                publishDate = PackedDate(2022, 10, 9),
            ),
        )

        assertEquals(listOf("孤独摇滚！"), keywords)
    }

    @Test
    fun `quota pause ends after a while`() = runTest {
        var now = 0L
        val calls = DandanplayBangumiCalls(pause = 5.minutes, clock = { now })
        var fetched = 0
        val quota = DandanplayGetBangumiResponse(errorCode = 429, success = false)
        calls.anime(1) { fetched++; quota }
        calls.anime(1) { fetched++; quota }
        assertEquals(1, fetched)

        now += 5.minutes.inWholeMilliseconds + 1
        calls.anime(1) { fetched++; DandanplayGetBangumiResponse() }
        assertEquals(2, fetched)
    }

    private fun createProvider(handler: MockRequestHandleScope.(url: Url) -> HttpResponseData): DandanplayDanmakuProvider {
        // 弹幕与分集列表并发请求: 处理逐个进行, 测试里记请求的列表不用另外加锁
        val lock = Mutex()
        val engine = MockEngine { request -> lock.withLock { handler(request.url) } }
        val client = HttpClient(engine) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
        return DandanplayDanmakuProvider(
            dandanplayAppId = "app-id",
            dandanplayAppSecret = "app-secret",
            client = client.asScopedHttpClient(),
        )
    }

    private fun MockRequestHandleScope.respondJson(content: String) = respond(
        content = content,
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
    )

    private fun request(
        episodeSort: EpisodeSort,
        episodeEp: EpisodeSort?,
        anidbId: Int? = null,
        seasonNumbering: DanmakuSeasonNumbering? = null,
        subjectNames: List<String> = listOf("孤独摇滚！"),
        publishDate: PackedDate = PackedDate.Invalid,
        awaitVideoFile: (suspend () -> DanmakuVideoFile?)? = null,
    ) = DanmakuFetchRequest(
        subjectId = 1,
        subjectPrimaryName = subjectNames.first(),
        subjectNames = subjectNames,
        subjectPublishDate = publishDate,
        episodeId = 1,
        episodeSort = episodeSort,
        episodeEp = episodeEp,
        episodeName = "",
        filename = null,
        fileHash = null,
        fileSize = null,
        videoDuration = 24.minutes,
        anidbId = anidbId,
        seasonNumbering = seasonNumbering,
        awaitVideoFile = awaitVideoFile,
    )

    /** 弹弹 play 作品详情: 正片 [numbers], 编号 = 作品编号 × 10000 + 集号. */
    private fun animeJson(animeId: Int, title: String, numbers: IntRange, titles: Map<Int, String> = emptyMap()): String {
        val episodes = numbers.joinToString(",") { n ->
            """{"episodeId": ${animeId * 10000L + n}, "episodeTitle": "第${n}话 ${titles[n].orEmpty()}", "episodeNumber": "$n", "lastWatched": null, "airDate": null}"""
        }
        return """{"success": true, "errorCode": 0, "errorMessage": "", "bangumi": {"animeId": $animeId, "animeTitle": "$title", "type": "tvseries", "episodes": [$episodes]}}"""
    }

    private companion object {
        const val ONE_COMMENT = """{"count":1,"comments":[{"cid":1,"p":"1.00,1,16777215,abc","m":"好"}]}"""
        const val EMPTY_COMMENTS = """{"count":0,"comments":[]}"""
        const val QUOTA_EXCEEDED = """{"success": false, "errorCode": 429, "errorMessage": "已达到接口调用配额上限", "bangumi": null}"""
        const val NOT_FOUND = """{"success": false, "errorCode": 7, "errorMessage": "无法找到指定的资源", "bangumi": null}"""
        const val EMPTY_EPISODE_SEARCH = """{"success": true, "errorCode": 0, "errorMessage": "", "hasMore": false, "animes": []}"""
    }
}
