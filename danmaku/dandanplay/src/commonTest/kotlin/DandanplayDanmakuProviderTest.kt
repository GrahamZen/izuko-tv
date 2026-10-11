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
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import me.him188.ani.danmaku.api.provider.DanmakuFetchRequest
import me.him188.ani.danmaku.api.provider.DanmakuMatchMethod
import me.him188.ani.danmaku.api.provider.DanmakuSeasonNumbering
import me.him188.ani.danmaku.dandanplay.data.DandanplayMatchVideoResponse
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.PackedDate
import me.him188.ani.utils.ktor.asScopedHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes

class DandanplayDanmakuProviderTest {
    @Test
    fun `fetchAutomatic uses Bangumi subject id mapping before existing matching`() = runTest {
        val seenPaths = mutableListOf<String>()
        val provider = createProvider { path ->
            seenPaths += path
            when (path) {
                "/api/v2/bangumi/bgmtv/113906" -> respondJson(
                    """
                    {
                      "success": true,
                      "errorCode": 0,
                      "errorMessage": "",
                      "bangumi": {
                        "animeTitle": "网球优等生 第二期",
                        "type": "tvseries",
                        "episodes": [
                          {
                            "episodeId": 108470001,
                            "episodeTitle": "第1话 世界与鸿沟",
                            "episodeNumber": "1",
                            "lastWatched": null,
                            "airDate": "2015-04-05T00:00:00"
                          }
                        ]
                      }
                    }
                    """.trimIndent(),
                )

                "/api/v2/comment/108470001" -> respondJson("""{"count":0,"comments":[]}""")
                else -> error("Unexpected request: $path")
            }
        }

        val result = provider.fetchAutomatic(
            request(
                subjectId = 113906,
                subjectName = "ベイビーステップ 第2シリーズ",
                episodeSort = EpisodeSort(1),
                episodeName = "世界と壁",
            ),
        ).single()

        val method = assertIs<DanmakuMatchMethod.Exact>(result.matchInfo.method)
        assertEquals("网球优等生 第二期", method.subjectTitle)
        assertEquals("第1话 世界与鸿沟", method.episodeTitle)
        assertEquals(
            listOf(
                "/api/v2/bangumi/bgmtv/113906",
                "/api/v2/comment/108470001",
            ),
            seenPaths,
        )
    }

    @Test
    fun `fetchAutomatic falls back to existing matching when Bangumi subject id mapping is missing`() = runTest {
        val seenPaths = mutableListOf<String>()
        val provider = createProvider { path ->
            seenPaths += path
            when (path) {
                "/api/v2/bangumi/bgmtv/999999999" -> respondJson(
                    """
                    {
                      "success": false,
                      "errorCode": 7,
                      "errorMessage": "无法找到指定的资源",
                      "bangumi": null
                    }
                    """.trimIndent(),
                )

                "/api/v2/search/episodes" -> respondJson(
                    """
                    {
                      "success": true,
                      "errorCode": 0,
                      "errorMessage": "",
                      "hasMore": false,
                      "animes": [
                        {
                          "animeId": 1,
                          "animeTitle": "fallback subject",
                          "episodes": [
                            {
                              "episodeId": 123456,
                              "episodeTitle": "fallback episode",
                              "episodeNumber": "1"
                            }
                          ]
                        }
                      ]
                    }
                    """.trimIndent(),
                )

                "/api/v2/comment/123456" -> respondJson("""{"count":0,"comments":[]}""")
                else -> error("Unexpected request: $path")
            }
        }

        val result = provider.fetchAutomatic(
            request(
                subjectId = 999999999,
                subjectName = "fallback subject",
                episodeSort = EpisodeSort(1),
                episodeName = "fallback episode",
            ),
        ).single()

        val method = assertIs<DanmakuMatchMethod.Exact>(result.matchInfo.method)
        assertEquals("fallback subject", method.subjectTitle)
        assertEquals("fallback episode", method.episodeTitle)
        assertEquals(
            listOf(
                "/api/v2/bangumi/bgmtv/999999999",
                "/api/v2/search/episodes",
                "/api/v2/comment/123456",
            ),
            seenPaths,
        )
    }

    /**
     * 弹弹 play 把 Re:Zero 第四季的丧失篇和夺还篇合并为一个番剧 (1-19 话), 只映射到丧失篇的 Bangumi 条目.
     * 夺还篇的 Bangumi 条目没有映射, 只能走剧集搜索; 搜索结果没有集数, 且中文名与弹弹的日文标题不同.
     */
    @Test
    fun `fetchAutomatic matches merged split cour episode by original title from episode search`() = runTest {
        val provider = createProvider { path ->
            when (path) {
                "/api/v2/bangumi/bgmtv/633836" -> respondJson(
                    """{"success": false, "errorCode": 7, "errorMessage": "无法找到指定的资源", "bangumi": null}""",
                )

                "/api/v2/search/episodes" -> respondJson(
                    """
                    {
                      "success": true, "errorCode": 0, "errorMessage": "", "hasMore": false,
                      "animes": [
                        {
                          "animeId": 19242,
                          "animeTitle": "Re：从零开始的异世界生活 第四季",
                          "episodes": [
                            {"episodeId": 192420007, "episodeTitle": "第7话 コンビニを出ると, そこは不思議の世界でした"},
                            {"episodeId": 192420009, "episodeTitle": "第9话 残骸"},
                            {"episodeId": 192420018, "episodeTitle": "第18话 ラム"},
                            {"episodeId": 192420019, "episodeTitle": "第19话"}
                          ]
                        }
                      ]
                    }
                    """.trimIndent(),
                )

                "/api/v2/comment/192420018" -> respondJson("""{"count":0,"comments":[]}""")
                else -> error("Unexpected request: $path")
            }
        }

        val result = provider.fetchAutomatic(
            request(
                subjectId = 633836,
                subjectName = "Re：从零开始的异世界生活 第四季 夺还篇",
                episodeSort = EpisodeSort(84),
                episodeEp = EpisodeSort(7),
                episodeName = "拉姆",
                episodeNames = listOf("ラム", "拉姆"),
            ),
        ).single()

        val method = assertIs<DanmakuMatchMethod.Exact>(result.matchInfo.method)
        assertEquals("第18话 ラム", method.episodeTitle)
    }

    /** 条目有几个名字时剧集搜索按每个名字搜一次, 搜到的是同一部番, 同一集出现好几次. */
    @Test
    fun `fetchAutomatic matches merged split cour episode when several subject names find the same anime`() = runTest {
        val provider = createProvider { path ->
            when (path) {
                "/api/v2/bangumi/bgmtv/633836" -> respondJson(
                    """{"success": false, "errorCode": 7, "errorMessage": "无法找到指定的资源", "bangumi": null}""",
                )

                "/api/v2/search/episodes" -> respondJson(
                    """
                    {
                      "success": true, "errorCode": 0, "errorMessage": "", "hasMore": false,
                      "animes": [
                        {
                          "animeId": 19242,
                          "animeTitle": "Re：从零开始的异世界生活 第四季",
                          "episodes": [
                            {"episodeId": 192420007, "episodeTitle": "第7话 コンビニを出ると, そこは不思議の世界でした"},
                            {"episodeId": 192420009, "episodeTitle": "第9话 残骸"},
                            {"episodeId": 192420018, "episodeTitle": "第18话 ラム"},
                            {"episodeId": 192420019, "episodeTitle": "第19话"}
                          ]
                        }
                      ]
                    }
                    """.trimIndent(),
                )

                "/api/v2/comment/192420018" -> respondJson("""{"count":0,"comments":[]}""")
                else -> error("Unexpected request: $path")
            }
        }

        val result = provider.fetchAutomatic(
            request(
                subjectId = 633836,
                subjectName = "Re：从零开始的异世界生活 第四季 夺还篇",
                episodeSort = EpisodeSort(84),
                episodeEp = EpisodeSort(7),
                episodeName = "拉姆",
                episodeNames = listOf("ラム", "拉姆"),
                subjectNames = listOf(
                    "Re：从零开始的异世界生活 第四季 夺还篇",
                    "Re:ゼロから始める異世界生活 4th season 奪還編",
                    "re0 第四季 夺还篇",
                ),
            ),
        ).single()

        val method = assertIs<DanmakuMatchMethod.Exact>(result.matchInfo.method)
        assertEquals("第18话 ラム", method.episodeTitle)
    }

    @Test
    fun `fetchAutomatic prefers title over episode number when Bangumi mapping covers merged cours`() = runTest {
        val provider = createProvider { path ->
            when (path) {
                "/api/v2/bangumi/bgmtv/633836" -> respondJson(
                    """
                    {
                      "success": true, "errorCode": 0, "errorMessage": "",
                      "bangumi": {
                        "animeTitle": "Re：从零开始的异世界生活 第四季",
                        "type": "tvseries",
                        "episodes": [
                          {"episodeId": 192420007, "episodeTitle": "第7话 コンビニを出ると, そこは不思議の世界でした", "episodeNumber": "7", "lastWatched": null, "airDate": "2026-09-23T00:00:00"},
                          {"episodeId": 192420018, "episodeTitle": "第18话 ラム", "episodeNumber": "18", "lastWatched": null, "airDate": "2026-09-23T00:00:00"}
                        ]
                      }
                    }
                    """.trimIndent(),
                )

                "/api/v2/comment/192420018" -> respondJson("""{"count":0,"comments":[]}""")
                else -> error("Unexpected request: $path")
            }
        }

        val result = provider.fetchAutomatic(
            request(
                subjectId = 633836,
                subjectName = "Re：从零开始的异世界生活 第四季 夺还篇",
                episodeSort = EpisodeSort(84),
                episodeEp = EpisodeSort(7),
                episodeName = "拉姆",
                episodeNames = listOf("ラム", "拉姆"),
            ),
        ).single()

        val method = assertIs<DanmakuMatchMethod.Exact>(result.matchInfo.method)
        assertEquals("第18话 ラム", method.episodeTitle)
    }

    @Test
    fun `fetchAutomatic falls back to episode number when title is unknown`() = runTest {
        val provider = createProvider { path ->
            when (path) {
                "/api/v2/bangumi/bgmtv/633836" -> respondJson(
                    """
                    {
                      "success": true, "errorCode": 0, "errorMessage": "",
                      "bangumi": {
                        "animeTitle": "Re：从零开始的异世界生活 第四季",
                        "type": "tvseries",
                        "episodes": [
                          {"episodeId": 192420007, "episodeTitle": "第7话 コンビニを出ると, そこは不思議の世界でした", "episodeNumber": "7", "lastWatched": null, "airDate": "2026-09-23T00:00:00"},
                          {"episodeId": 192420019, "episodeTitle": "第19话", "episodeNumber": "19", "lastWatched": null, "airDate": "2026-09-23T00:00:00"}
                        ]
                      }
                    }
                    """.trimIndent(),
                )

                "/api/v2/comment/192420007" -> respondJson("""{"count":0,"comments":[]}""")
                else -> error("Unexpected request: $path")
            }
        }

        val result = provider.fetchAutomatic(
            request(
                subjectId = 633836,
                subjectName = "Re：从零开始的异世界生活 第四季 夺还篇",
                episodeSort = EpisodeSort(84),
                episodeEp = EpisodeSort(7),
                episodeName = "",
                episodeNames = emptyList(),
            ),
        ).single()

        val method = assertIs<DanmakuMatchMethod.Exact>(result.matchInfo.method)
        assertEquals("第7话 コンビニを出ると, そこは不思議の世界でした", method.episodeTitle)
    }

    @Test
    fun `DandanplayMatchVideoResponse accepts null matches when match request is rejected`() {
        val response = json.decodeFromString<DandanplayMatchVideoResponse>(REJECTED_MATCH_RESPONSE)

        assertFalse(response.success)
        assertFalse(response.isMatched)
        assertEquals(2, response.errorCode)
        assertEquals("一个或多个参数不符合规则", response.errorMessage)
        assertNull(response.matches)
    }

    @Test
    fun `fetchAutomatic returns no match when file match request is rejected`() = runTest {
        val seenPaths = mutableListOf<String>()
        val provider = createProvider { path ->
            seenPaths += path
            when (path) {
                "/api/v2/bangumi/bgmtv/999999999" -> respondJson(
                    """{"success": false, "errorCode": 7, "errorMessage": "无法找到指定的资源", "bangumi": null}""",
                )

                "/api/v2/search/episodes" -> respondJson(EMPTY_EPISODE_SEARCH_RESPONSE)
                "/api/v2/match" -> respondJson(REJECTED_MATCH_RESPONSE)
                else -> error("Unexpected request: $path")
            }
        }

        val result = provider.fetchAutomatic(
            request(
                subjectId = 999999999,
                subjectName = "unknown subject",
                episodeSort = EpisodeSort(1),
                episodeName = "unknown episode",
                filename = "[Group] unknown subject - 01 [1080P]",
            ),
        ).single()

        assertEquals(DanmakuMatchMethod.NoMatch, result.matchInfo.method)
        assertEquals(
            listOf(
                "/api/v2/bangumi/bgmtv/999999999",
                "/api/v2/search/episodes",
                "/api/v2/match",
            ),
            seenPaths,
        )
    }

    @Test
    fun `fetchAutomatic uses file match when other matching fails`() = runTest {
        val provider = createProvider { path ->
            when (path) {
                "/api/v2/bangumi/bgmtv/999999999" -> respondJson(
                    """{"success": false, "errorCode": 7, "errorMessage": "无法找到指定的资源", "bangumi": null}""",
                )

                "/api/v2/search/episodes" -> respondJson(EMPTY_EPISODE_SEARCH_RESPONSE)
                "/api/v2/match" -> respondJson(
                    """
                    {
                      "isMatched": false,
                      "matches": [
                        {
                          "episodeId": 176170001, "animeId": 17617, "animeTitle": "葬送的芙莉莲",
                          "episodeTitle": "第1话 冒险的结束", "type": "tvseries", "typeDescription": "TV动画",
                          "shift": 0, "imageUrl": "https://example.com/17617.jpg"
                        }
                      ],
                      "errorCode": 0, "success": true, "errorMessage": ""
                    }
                    """.trimIndent(),
                )

                "/api/v2/comment/176170001" -> respondJson("""{"count":0,"comments":[]}""")
                else -> error("Unexpected request: $path")
            }
        }

        val result = provider.fetchAutomatic(
            request(
                subjectId = 999999999,
                subjectName = "葬送的芙莉莲",
                episodeSort = EpisodeSort(1),
                episodeName = "冒险的结束",
                filename = "[Group] Sousou no Frieren - 01 [1080P]",
            ),
        ).single()

        val method = assertIs<DanmakuMatchMethod.Fuzzy>(result.matchInfo.method)
        assertEquals("葬送的芙莉莲", method.subjectTitle)
        assertEquals("第1话 冒险的结束", method.episodeTitle)
    }

    /** 夺还篇第 7 集 (sort 84): AniDB 把丧失篇与夺还篇合成 19242 一部, 整季第 18 集. 不搜名字, 也不查 Bangumi 映射. */
    @Test
    fun `fetchAutomatic takes the season ordinal in the merged AniDB anime`() = runTest {
        val seen = mutableListOf<String>()
        val provider = createProvider { path ->
            seen += path
            when (path) {
                "/api/v2/bangumi/19242" -> respondJson(animeJson(19242, "Re：从零开始的异世界生活 第四季", 1..19))
                "/api/v2/comment/192420018" -> respondJson("""{"count":0,"comments":[]}""")
                else -> error("Unexpected request: $path")
            }
        }
        val result = provider.fetchAutomatic(
            request(
                subjectId = 633836, subjectName = "Re：从零开始的异世界生活 第四季 夺还篇",
                episodeSort = EpisodeSort(84), episodeEp = EpisodeSort(7), episodeName = "",
                anidbId = 19242, seasonNumbering = DanmakuSeasonNumbering(seasonEpisode = 18, partEpisodeCount = 8),
            ),
        ).single()

        assertEquals("第18话 ", assertIs<DanmakuMatchMethod.Exact>(result.matchInfo.method).episodeTitle)
        assertEquals(listOf("/api/v2/bangumi/19242", "/api/v2/comment/192420018"), seen)
    }

    /** 无职转生第二季第 2 部分: AniDB 18104 只收这一段、从 1 编号, 第 1 集就是第 1 话 (整季第 13 集不在里面). */
    @Test
    fun `fetchAutomatic takes the part ordinal when the AniDB anime holds only this part`() = runTest {
        val provider = createProvider { path ->
            when (path) {
                "/api/v2/bangumi/18104" -> respondJson(animeJson(18104, "无职转生Ⅱ 第二部分", 1..12))
                "/api/v2/comment/181040001" -> respondJson("""{"count":0,"comments":[]}""")
                else -> error("Unexpected request: $path")
            }
        }
        val result = provider.fetchAutomatic(
            request(
                subjectId = 444557, subjectName = "无职转生 第二季 第2部分",
                episodeSort = EpisodeSort(13), episodeEp = EpisodeSort(1), episodeName = "",
                anidbId = 18104, seasonNumbering = DanmakuSeasonNumbering(seasonEpisode = 13, partEpisodeCount = 12),
            ),
        ).single()

        assertEquals("第1话 ", assertIs<DanmakuMatchMethod.Exact>(result.matchInfo.method).episodeTitle)
    }

    /** 标题对得上时先按标题, 不看集号. */
    @Test
    fun `fetchAutomatic prefers the title in the AniDB anime`() = runTest {
        val provider = createProvider { path ->
            when (path) {
                "/api/v2/bangumi/19242" -> respondJson(animeJson(19242, "Re：从零开始的异世界生活 第四季", 1..19, mapOf(18 to "ラム")))
                "/api/v2/comment/192420018" -> respondJson("""{"count":0,"comments":[]}""")
                else -> error("Unexpected request: $path")
            }
        }
        val result = provider.fetchAutomatic(
            request(
                subjectId = 633836, subjectName = "Re：从零开始的异世界生活 第四季 夺还篇",
                episodeSort = EpisodeSort(84), episodeEp = EpisodeSort(7), episodeName = "拉姆",
                episodeNames = listOf("ラム", "拉姆"), anidbId = 19242,
            ),
        ).single()

        assertEquals("第18话 ラム", assertIs<DanmakuMatchMethod.Exact>(result.matchInfo.method).episodeTitle)
    }

    /** 那部作品里没有这一集 (还没上传、或者其实不是这部): 不猜, 照旧走 Bangumi 映射. */
    @Test
    fun `fetchAutomatic falls back when the AniDB anime lacks the episode`() = runTest {
        val provider = createProvider { path ->
            when (path) {
                "/api/v2/bangumi/19242" -> respondJson(animeJson(19242, "Re：从零开始的异世界生活 第四季", 1..13))
                "/api/v2/bangumi/bgmtv/633836" -> respondJson(animeJson(55555, "别的映射", 1..19))
                "/api/v2/comment/555550018" -> respondJson("""{"count":0,"comments":[]}""")
                else -> error("Unexpected request: $path")
            }
        }
        val result = provider.fetchAutomatic(
            request(
                subjectId = 633836, subjectName = "Re：从零开始的异世界生活 第四季 夺还篇",
                episodeSort = EpisodeSort(18), episodeEp = EpisodeSort(7), episodeName = "",
                anidbId = 19242, seasonNumbering = DanmakuSeasonNumbering(seasonEpisode = 18, partEpisodeCount = 8),
            ),
        ).single()

        assertEquals("别的映射", assertIs<DanmakuMatchMethod.Exact>(result.matchInfo.method).subjectTitle)
    }

    @Test
    fun `normalizeEpisodeTitle strips number prefix and unifies width and spaces`() {
        assertEquals("ラム", normalizeEpisodeTitle("第18话 ラム"))
        assertEquals("ラム", normalizeEpisodeTitle("ラム"))
        assertEquals("", normalizeEpisodeTitle("第19话"))
        assertEquals(
            normalizeEpisodeTitle("君を連れ出す理由／ゴージャス・タイガー・リローデッド"),
            normalizeEpisodeTitle("第1话 君を連れ出す理由 / ゴージャス・タイガー・リローデッド"),
        )
        assertEquals("straightbet", normalizeEpisodeTitle("第14话 STRAIGHT BET"))
    }

    private fun createProvider(handler: MockRequestHandleScope.(path: String) -> HttpResponseData): DandanplayDanmakuProvider {
        val engine = MockEngine { request ->
            assertEquals("app-id", request.headers["X-AppId"])
            handler(request.url.encodedPath)
        }
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
        subjectId: Int,
        subjectName: String,
        episodeSort: EpisodeSort,
        episodeName: String,
        episodeEp: EpisodeSort? = null,
        episodeNames: List<String> = listOf(episodeName),
        filename: String? = null,
        subjectNames: List<String> = listOf(subjectName),
        anidbId: Int? = null,
        seasonNumbering: DanmakuSeasonNumbering? = null,
    ) = DanmakuFetchRequest(
        subjectId = subjectId,
        subjectPrimaryName = subjectName,
        subjectNames = subjectNames,
        subjectPublishDate = PackedDate.Invalid,
        episodeId = 1,
        episodeSort = episodeSort,
        episodeEp = episodeEp,
        episodeName = episodeName,
        episodeNames = episodeNames,
        filename = filename,
        fileHash = null,
        fileSize = null,
        videoDuration = 24.minutes,
        anidbId = anidbId,
        seasonNumbering = seasonNumbering,
    )

    /** 弹弹 play 作品详情 (`/api/v2/bangumi/{animeId}`): 正片 [numbers], 标题「第N话 [titles] 里的那个」. */
    private fun animeJson(animeId: Int, title: String, numbers: IntRange, titles: Map<Int, String> = emptyMap()): String {
        val episodes = numbers.withIndex().joinToString(",") { (index, n) ->
            """{"episodeId": ${animeId * 10000L + index + 1}, "episodeTitle": "第${n}话 ${titles[n].orEmpty()}", "episodeNumber": "$n", "lastWatched": null, "airDate": null}"""
        }
        return """{"success": true, "errorCode": 0, "errorMessage": "", "bangumi": {"animeId": $animeId, "animeTitle": "$title", "type": "tvseries", "episodes": [$episodes]}}"""
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }

        /**
         * 弹弹 play 拒绝匹配请求时的真实响应, `matches` 为 `null`.
         */
        const val REJECTED_MATCH_RESPONSE =
            """{"isMatched":false,"matches":null,"errorCode":2,"success":false,"errorMessage":"一个或多个参数不符合规则"}"""

        const val EMPTY_EPISODE_SEARCH_RESPONSE =
            """{"success": true, "errorCode": 0, "errorMessage": "", "hasMore": false, "animes": []}"""
    }
}
