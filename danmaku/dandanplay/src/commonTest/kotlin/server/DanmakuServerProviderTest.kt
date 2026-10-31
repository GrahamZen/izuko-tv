/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.danmaku.dandanplay.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import me.him188.ani.danmaku.api.DanmakuServiceId
import me.him188.ani.danmaku.api.provider.DanmakuEpisode
import me.him188.ani.danmaku.api.provider.DanmakuFetchRequest
import me.him188.ani.danmaku.api.provider.DanmakuMatchMethod
import me.him188.ani.danmaku.api.provider.DanmakuProviderId
import me.him188.ani.danmaku.api.provider.DanmakuSubject
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.PackedDate
import me.him188.ani.utils.ktor.ScopedHttpClient
import me.him188.ani.utils.ktor.asScopedHttpClient
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration

/** 响应形状照抄实测过的两种服务器. */
class DanmakuServerProviderTest {
    private val base = "https://example.com/secret-token"

    @Test
    fun `search picks the title that matches and returns one source named after the server`() = runTest {
        val provider = createProvider { path, _ ->
            when (path) {
                "/secret-token/api/v2/search/episodes" -> respondJson(
                    """
                    {"success":true,"animes":[
                      {"animeTitle":"孤独摇滚！ 中配版(2022)【TV动画】from bilibili","episodes":[{"episodeId":9,"episodeTitle":"第1话"}]},
                      {"animeTitle":"孤独摇滚！(2022)【TV动画】from dandan","episodes":[{"episodeId":8,"episodeTitle":"【dandan】 第1话"}]},
                      {"animeTitle":"孤独摇滚！(2022)【TV动画】from bilibili","episodes":[{"episodeId":7,"episodeTitle":"【bilibili1】 第1话"}]}
                    ]}
                    """.trimIndent(),
                )

                "/secret-token/api/v2/comment/7" -> respondJson(
                    """{"count":2,"comments":[{"cid":701,"p":"2.69,1,15106878,[bilibili1]","m":"a","t":0.06},{"cid":61,"p":"0.24,5,255,[bilibili1]","m":"b","like":2}]}""",
                )

                else -> error("Unexpected request: $path")
            }
        }

        val result = provider.fetchAutomatic(request("孤独摇滚！", EpisodeSort(1))).single()

        assertEquals(DanmakuServiceId("家里"), result.matchInfo.serviceId)
        assertEquals(DanmakuProviderId("server:家里"), result.providerId)
        assertEquals(2, result.matchInfo.count)
        assertNull(result.matchInfo.sourceEpisodeId, "must not be taken for a dandanplay library id")
        assertIs<DanmakuMatchMethod.Exact>(result.matchInfo.method)
        assertEquals(listOf("a", "b"), result.list.map { it.text })
        assertEquals(listOf(2690L, 240L), result.list.map { it.playTimeMillis })
        assertEquals(listOf("server:家里:0", "server:家里:1"), result.list.map { it.id })
        assertTrue(result.list.all { it.serviceId == DanmakuServiceId("家里") })
    }

    @Test
    fun `source with the most comments among the first three is used`() = runTest {
        val requested = mutableListOf<String>()
        val lock = Mutex()
        val provider = createProvider { path, _ ->
            lock.withLock { requested += path }
            when (path) {
                "/secret-token/api/v2/search/episodes" -> respondJson(
                    """
                    {"animes":[
                      {"animeTitle":"番(2019)【动漫】from qq","episodes":[{"episodeId":1,"episodeTitle":"第1话"}]},
                      {"animeTitle":"番(2019)【TV动画】from bilibili","episodes":[{"episodeId":2,"episodeTitle":"第1话"}]},
                      {"animeTitle":"番(2019)【动漫】from youku","episodes":[{"episodeId":3,"episodeTitle":"第1集"}]},
                      {"animeTitle":"番(2019)【TV动画】from bahamut","episodes":[{"episodeId":4,"episodeTitle":"第1集"}]},
                      {"animeTitle":"番(2019)【TV动画】from dandan","episodes":[{"episodeId":5,"episodeTitle":"第1话"}]}
                    ]}
                    """.trimIndent(),
                )

                "/secret-token/api/v2/comment/1" -> respondJson(commentsJson(2, "qq"))
                "/secret-token/api/v2/comment/2" -> respondJson(commentsJson(5, "bili"))
                "/secret-token/api/v2/comment/3" -> respondJson(commentsJson(3, "youku"))
                else -> error("Unexpected request: $path")
            }
        }

        val result = provider.fetchAutomatic(request("番", EpisodeSort(1), publishDate = PackedDate(2019, 4, 6))).single()

        assertEquals(5, result.matchInfo.count)
        assertTrue(result.list.all { it.text.startsWith("bili") })
        // 第 4 个站不比; 来自弹弹 play 的那份只在别的站都没有时才用, 也不比
        assertEquals(
            listOf("comment/1", "comment/2", "comment/3", "comment/2"),
            requested.filter { "comment/" in it }.map { it.substringAfter("v2/") }.let { it.take(3).sorted() + it.drop(3) },
        )
    }

    @Test
    fun `count that is not at the start is read from the whole response`() = runTest {
        val provider = createProvider { path, _ ->
            when (path) {
                "/secret-token/api/v2/search/episodes" -> respondJson(
                    """
                    {"animes":[
                      {"animeTitle":"番(2019)【动漫】from a","episodes":[{"episodeId":1,"episodeTitle":"第1话"}]},
                      {"animeTitle":"番(2019)【动漫】from b","episodes":[{"episodeId":2,"episodeTitle":"第1话"}]},
                      {"animeTitle":"番(2019)【动漫】from c","episodes":[{"episodeId":3,"episodeTitle":"第1话"}]}
                    ]}
                    """.trimIndent(),
                )

                // 条数在列表后面、没有条数、问不到: 前两种整份读完再数, 问不到的不选
                "/secret-token/api/v2/comment/1" -> respondJson(
                    """{"comments":[{"p":"1,1,255,a","m":"a1"},{"p":"2,1,255,a","m":"a2"}],"count":2}""",
                )

                "/secret-token/api/v2/comment/2" -> respondJson(
                    """{"comments":[{"p":"1,1,255,b","m":"b1"},{"p":"2,1,255,b","m":"b2"},{"p":"3,1,255,b","m":"b3"}]}""",
                )

                "/secret-token/api/v2/comment/3" -> respond("busy", HttpStatusCode.ServiceUnavailable)
                else -> error("Unexpected request: $path")
            }
        }

        val result = provider.fetchAutomatic(request("番", EpisodeSort(1))).single()

        assertEquals(listOf("b1", "b2", "b3"), result.list.map { it.text })
    }

    @Test
    fun `leading comment count`() {
        assertEquals(24292, leadingCommentCount("""{"count":24292,"comments":[{"cid":25,"p":"13.66,1,1"""))
        assertEquals(5, leadingCommentCount("""{"success":true,"errorCode":0,"count" : 5 ,"comments":[]}"""))
        assertEquals(12, leadingCommentCount("""{"count":12}"""))
        assertNull(leadingCommentCount("""{"count":2429"""), "cut in the middle of the number")
        assertNull(leadingCommentCount("""{"comments":[{"p":"1,1,255,a","m":"\"count\":9,"}],"count":1}"""))
        assertNull(leadingCommentCount("""{"success":true,"comments":["""))
    }

    @Test
    fun `candidate from another year is skipped`() = runTest {
        val provider = createProvider { path, _ ->
            when (path) {
                "/secret-token/api/v2/search/episodes" -> respondJson(
                    """
                    {"animes":[
                      {"animeTitle":"全职猎人(1999)【TV动画】from a","episodes":[{"episodeId":1,"episodeTitle":"第1话"}]},
                      {"animeTitle":"全职猎人(2011)【TV动画】from b","episodes":[{"episodeId":2,"episodeTitle":"第1话"}]}
                    ]}
                    """.trimIndent(),
                )

                "/secret-token/api/v2/comment/2" -> respondJson("""{"comments":[{"p":"1,1,255,u","m":"new"}]}""")
                "/secret-token/api/v2/match" -> respondJson("""{"isMatched":false,"matches":[]}""")
                else -> error("Unexpected request: $path")
            }
        }

        val result = provider.fetchAutomatic(
            request("全职猎人", EpisodeSort(1), publishDate = PackedDate(2011, 10, 2)),
        ).single()
        assertEquals(listOf("new"), result.list.map { it.text })

        // 只剩差了十几年的那部时宁可不要
        val old = provider.fetchAutomatic(
            request("全职猎人", EpisodeSort(1), publishDate = PackedDate(2020, 1, 1)),
        ).single()
        assertEquals(DanmakuMatchMethod.NoMatch, old.matchInfo.method)
    }

    @Test
    fun `year one apart still counts`() = runTest {
        val provider = createProvider { path, _ ->
            when (path) {
                "/secret-token/api/v2/search/episodes" -> respondJson(
                    """{"animes":[{"animeTitle":"番(2023)【TV动画】from a","episodes":[{"episodeId":5,"episodeTitle":"第1话"}]}]}""",
                )

                "/secret-token/api/v2/comment/5" -> respondJson("""{"comments":[{"p":"1,1,255,u","m":"x"}]}""")
                else -> error("Unexpected request: $path")
            }
        }

        val result = provider.fetchAutomatic(request("番", EpisodeSort(1), publishDate = PackedDate(2022, 12, 30))).single()

        assertEquals(listOf("x"), result.list.map { it.text })
    }

    @Test
    fun `title that has brackets of its own matches`() = runTest {
        val provider = createProvider { path, _ ->
            when (path) {
                "/secret-token/api/v2/search/episodes" -> respondJson(
                    """
                    {"animes":[
                      {"animeTitle":"【我推的孩子】 第二季(2024)【TV动画】from dandan","episodes":[{"episodeId":1,"episodeTitle":"第1话"}]},
                      {"animeTitle":"【我推的孩子】(2023)【TV动画】from dandan","episodes":[{"episodeId":2,"episodeTitle":"第1话"}]},
                      {"animeTitle":"【我推的孩子】(2023)【TV动画】from bahamut","episodes":[{"episodeId":3,"episodeTitle":"第1集"}]}
                    ]}
                    """.trimIndent(),
                )

                "/secret-token/api/v2/comment/3" -> respondJson("""{"comments":[{"p":"1,1,255,u","m":"baha"}]}""")
                else -> error("Unexpected request: $path")
            }
        }

        val result = provider.fetchAutomatic(request("【我推的孩子】", EpisodeSort(1), publishDate = PackedDate(2023, 4, 12))).single()

        assertEquals(listOf("baha"), result.list.map { it.text })
    }

    @Test
    fun `live action with the same title is skipped`() = runTest {
        val provider = createProvider { path, _ ->
            when (path) {
                "/secret-token/api/v2/search/episodes" -> respondJson(
                    """
                    {"animes":[
                      {"animeTitle":"凡人修仙传(2025)【电视剧】from 360","episodes":[{"episodeId":1,"episodeTitle":"第1集"}]},
                      {"animeTitle":"凡人修仙传(2020)【动漫】from bilibili","episodes":[{"episodeId":2,"episodeTitle":"第1话"}]}
                    ]}
                    """.trimIndent(),
                )

                "/secret-token/api/v2/comment/2" -> respondJson("""{"comments":[{"p":"1,1,255,u","m":"anime"}]}""")
                "/secret-token/api/v2/match" -> respondJson(
                    """{"isMatched":true,"matches":[{"episodeId":1,"animeTitle":"凡人修仙传(2025)【电视剧】from 360","episodeTitle":"第1集"}]}""",
                )

                else -> error("Unexpected request: $path")
            }
        }

        // 不知道播出年份时, 只能靠类型认出真人剧
        val result = provider.fetchAutomatic(request("凡人修仙传", EpisodeSort(1))).single()
        assertEquals(listOf("anime"), result.list.map { it.text })
    }

    @Test
    fun `match that is sure is still refused for live action`() = runTest {
        val provider = createProvider { path, _ ->
            when (path) {
                "/secret-token/api/v2/search/episodes" -> respondJson("""{"animes":[]}""")
                "/secret-token/api/v2/match" -> respondJson(
                    """{"isMatched":true,"matches":[{"episodeId":1,"animeTitle":"星际牛仔(2021)【电视剧】from renren","episodeTitle":"第1集"}]}""",
                )

                else -> error("Unexpected request: $path")
            }
        }

        val result = provider.fetchAutomatic(request("星际牛仔", EpisodeSort(1))).single()

        assertEquals(DanmakuMatchMethod.NoMatch, result.matchInfo.method)
    }

    @Test
    fun `falls back to match with a season episode file name`() = runTest {
        var matchBody: String? = null
        val provider = createProvider { path, data ->
            when (path) {
                "/secret-token/api/v2/search/episodes" -> respondJson("""{"animes":[]}""")
                "/secret-token/api/v2/match" -> {
                    matchBody = (data.body as TextContent).text
                    respondJson(
                        """{"isMatched":true,"matches":[{"episodeId":41,"animeId":4,"animeTitle":"另一个名字(2023)【动漫】from x","episodeTitle":"第3话","type":"动漫","shift":0}]}""",
                    )
                }

                "/secret-token/api/v2/comment/41" -> respondJson("""{"count":1,"comments":[{"p":"1,1,255,u","m":"x"}]}""")
                else -> error("Unexpected request: $path")
            }
        }

        val result = provider.fetchAutomatic(request("间谍过家家 第二季", EpisodeSort(3))).single()

        assertTrue(matchBody!!.contains("间谍过家家 S02E03"), matchBody)
        assertIs<DanmakuMatchMethod.Fuzzy>(result.matchInfo.method)
    }

    @Test
    fun `match that is not sure only counts when the title matches`() = runTest {
        val provider = createProvider { path, _ ->
            when (path) {
                "/secret-token/api/v2/search/episodes" -> respondJson("""{"animes":[]}""")
                "/secret-token/api/v2/match" -> respondJson(
                    """{"isMatched":false,"matches":[{"episodeId":41,"animeTitle":"别的番","episodeTitle":"第1话"}]}""",
                )

                else -> error("Unexpected request: $path")
            }
        }

        val result = provider.fetchAutomatic(request("这部番", EpisodeSort(1))).single()

        assertEquals(DanmakuMatchMethod.NoMatch, result.matchInfo.method)
    }

    @Test
    fun `server without the routes drops out and is not asked again`() = runTest {
        val seen = mutableListOf<String>()
        val lock = Mutex()
        val provider = createProvider { path, _ ->
            lock.withLock { seen += path }
            respond("""{"message":"Not found"}""", HttpStatusCode.NotFound)
        }

        assertEquals(emptyList(), provider.fetchAutomatic(request("番", EpisodeSort(1))))
        assertFalse(provider.supportsAutoMatch)
        val requests = seen.size

        assertEquals(emptyList(), provider.fetchAutomatic(request("番", EpisodeSort(1))))
        assertEquals(requests, seen.size)
    }

    @Test
    fun `features turned off send no requests`() = runTest {
        val provider = createProvider(autoMatch = false) { path, _ -> error("Unexpected request: $path") }
        assertEquals(emptyList(), provider.fetchAutomatic(request("番", EpisodeSort(1))))
    }

    @Test
    fun `failure is reported without the address`() = runTest {
        val provider = createProvider { _, _ ->
            respond("""{"errorCode":401,"success":false,"errorMessage":"Unauthorized"}""", HttpStatusCode.Unauthorized)
        }

        val e = assertFailsWith<DanmakuServerException> {
            provider.fetchAutomatic(request("番", EpisodeSort(1)))
        }

        assertEquals("Unauthorized", e.serverMessage)
        assertFalse(e.message!!.contains("secret-token"), e.message)
    }

    @Test
    fun `network error message does not leak the token`() = runTest {
        val provider = createProvider { _, _ -> throw IllegalStateException("cannot connect to $base/api/v2/x") }

        val e = assertFailsWith<DanmakuServerException> {
            provider.fetchAutomatic(request("番", EpisodeSort(1)))
        }

        assertFalse(e.message!!.contains("secret-token"), e.message)
    }

    @Test
    fun `success false in a 200 response is a failure`() = runTest {
        val provider = createProvider { _, _ ->
            respondJson("""{"success":false,"errorCode":1003,"errorMessage":"请求的资源不可用"}""")
        }

        val e = assertFailsWith<DanmakuServerException> {
            provider.fetchByLink("https://example.org/video")
        }

        assertEquals("请求的资源不可用", e.serverMessage)
    }

    @Test
    fun `special episode number is not searched`() = runTest {
        val provider = createProvider { path, _ -> error("Unexpected request: $path") }
        val result = provider.fetchAutomatic(request("番", EpisodeSort("SP1"))).single()
        assertEquals(DanmakuMatchMethod.NoMatch, result.matchInfo.method)
    }

    @Test
    fun `link import sends the url and reads comments`() = runTest {
        var url: String? = null
        val provider = createProvider { path, data ->
            assertEquals("/secret-token/api/v2/extcomment", path)
            url = data.url.parameters["url"]
            respondJson("""{"errorCode":0,"success":true,"errorMessage":"","count":1,"comments":[{"cid":8,"p":"0.00,1,13938529,[qq]","m":"x","t":0,"like":0}]}""")
        }

        assertEquals(listOf("x"), provider.fetchByLink("https://example.org/video?a=1").map { it.text })
        assertEquals("https://example.org/video?a=1", url)
    }

    @Test
    fun `missing link route is remembered`() = runTest {
        val provider = createProvider { _, _ -> respond("", HttpStatusCode.NotFound) }
        assertTrue(provider.supportsLinkImport)
        assertFailsWith<DanmakuServerRouteMissingException> { provider.fetchByLink("https://example.org/video") }
        assertFalse(provider.supportsLinkImport)
    }

    @Test
    fun `subjects use bangumi id and hide the searching placeholder`() = runTest {
        var calls = 0
        val provider = createProvider(dispatcher = UnconfinedTestDispatcher(testScheduler)) { _, _ ->
            calls++
            if (calls == 1) {
                respondJson("""{"animes":[{"animeId":999999999,"bangumiId":"999999998","animeTitle":"番 搜索中"}]}""")
            } else {
                respondJson(
                    """{"animes":[{"animeId":4780191,"bangumiId":"ss43164","animeTitle":"A"},{"animeId":5,"bangumiId":"","animeTitle":"B"}]}""",
                )
            }
        }

        assertEquals(
            listOf(DanmakuSubject("ss43164", "A"), DanmakuSubject("5", "B")),
            provider.fetchSubjectList("番"),
        )
        assertEquals(2, calls)
    }

    @Test
    fun `episodes and manual match use the server ids`() = runTest {
        val provider = createProvider { path, _ ->
            when (path) {
                "/secret-token/api/v2/bangumi/ss43164" -> respondJson(
                    """{"success":true,"bangumi":{"animeTitle":"A","episodes":[{"seasonId":"s","episodeId":41751694,"episodeTitle":"第1话","episodeNumber":"1"}]}}""",
                )

                "/secret-token/api/v2/comment/41751694" -> respondJson("""{"comments":[{"p":"1,1,255,u","m":"x"}]}""")
                else -> error("Unexpected request: $path")
            }
        }
        val subject = DanmakuSubject("ss43164", "A")

        val episodes = provider.fetchEpisodeList(subject)
        assertEquals(listOf(DanmakuEpisode("41751694", "第1话")), episodes)

        val result = provider.fetchDanmakuList(subject, episodes.single()).single()
        assertEquals(listOf("x"), result.list.map { it.text })
        assertIs<DanmakuMatchMethod.Exact>(result.matchInfo.method)
    }

    @Test
    fun `file names`() {
        assertEquals("番 S01E03", DanmakuServerProvider.seasonEpisodeFileName("番", 3))
        assertEquals("间谍过家家 S02E12", DanmakuServerProvider.seasonEpisodeFileName("间谍过家家 第二季", 12))
        assertEquals("SPY FAMILY S03E01", DanmakuServerProvider.seasonEpisodeFileName("SPY FAMILY Season 3", 1))
        assertEquals("Foo S02E01", DanmakuServerProvider.seasonEpisodeFileName("Foo 2nd Season", 1))
        assertEquals("番 S11E01", DanmakuServerProvider.seasonEpisodeFileName("番 第十一期", 1))
        assertEquals("番 S20E01", DanmakuServerProvider.seasonEpisodeFileName("番 第二十季", 1))
    }

    @Test
    fun `titles are compared without decoration`() {
        assertEquals("孤独摇滚", DanmakuServerProvider.comparableTitle("孤独摇滚！(2022)【TV动画】from bilibili"))
        assertEquals("孤独摇滚", DanmakuServerProvider.comparableTitle("孤独摇滚！"))
        assertEquals("spyfamilyseason2", DanmakuServerProvider.comparableTitle("SPY×FAMILY Season 2(2023)【TV动画】from bilibili"))
        assertEquals("葬送的芙莉莲中配版", DanmakuServerProvider.comparableTitle("葬送的芙莉莲 中配版(2023)【TV动画】from bilibili"))
        assertEquals("我推的孩子", DanmakuServerProvider.comparableTitle("【我推的孩子】(2023)【TV动画】from dandan"))
        assertEquals("我推的孩子第三季", DanmakuServerProvider.comparableTitle("【我推的孩子】 第三季(2026)【TV动画】from dandan"))
        assertEquals("葬送的芙莉莲第三季黄金乡篇", DanmakuServerProvider.comparableTitle("葬送的芙莉莲 第三季 黄金乡篇(未知)【TV动画】from dandan"))
    }

    @Test
    fun `server title is split into name year type and site`() {
        val title = DanmakuServerProvider.parseServerTitle("凡人修仙传(2025)【电视剧】from 360")
        assertEquals("凡人修仙传", title.name)
        assertEquals(2025, title.year)
        assertEquals("电视剧", title.type)
        assertEquals("360", title.site)
        assertTrue(title.isLiveAction)
        assertFalse(DanmakuServerProvider.parseServerTitle("凡人修仙传(2020)【动漫】from bilibili").isLiveAction)

        val unknown = DanmakuServerProvider.parseServerTitle("葬送的芙莉莲 第三季 黄金乡篇(未知)【TV动画】from dandan")
        assertNull(unknown.year)
        assertEquals("TV动画", unknown.type)

        // 别家服务器不加这一段: 整个标题都是名字
        val plain = DanmakuServerProvider.parseServerTitle("【我推的孩子】 第1话")
        assertEquals("【我推的孩子】 第1话", plain.name)
        assertNull(plain.site)
    }

    private fun createProvider(
        autoMatch: Boolean = true,
        dispatcher: CoroutineContext = EmptyCoroutineContext,
        handler: suspend MockRequestHandleScope.(path: String, data: HttpRequestData) -> HttpResponseData,
    ): DanmakuServerProvider {
        val engine = MockEngine { data -> handler(data.url.encodedPath, data) }
        val client: ScopedHttpClient = HttpClient(engine).asScopedHttpClient()
        return if (dispatcher == EmptyCoroutineContext) {
            DanmakuServerProvider(DanmakuServerEndpoint("家里", base, autoMatch = autoMatch), client)
        } else {
            DanmakuServerProvider(DanmakuServerEndpoint("家里", base, autoMatch = autoMatch), client, dispatcher)
        }
    }

    private fun commentsJson(count: Int, text: String) = buildString {
        append("""{"count":$count,"comments":[""")
        repeat(count) { i ->
            if (i > 0) append(',')
            append("""{"p":"$i,1,255,u","m":"$text$i"}""")
        }
        append("]}")
    }

    private fun MockRequestHandleScope.respondJson(content: String) = respond(
        content = content,
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
    )

    private fun request(
        name: String,
        sort: EpisodeSort,
        publishDate: PackedDate = PackedDate.Invalid,
    ) = DanmakuFetchRequest(
        subjectId = 1,
        subjectPrimaryName = name,
        subjectNames = listOf(name),
        subjectPublishDate = publishDate,
        episodeId = 1,
        episodeSort = sort,
        episodeEp = null,
        episodeName = "",
        filename = null,
        fileHash = null,
        fileSize = null,
        videoDuration = Duration.ZERO,
    )
}
