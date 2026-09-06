/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import me.him188.ani.datasources.bangumi.next.apis.TrendingBangumiNextApi
import me.him188.ani.datasources.bangumi.next.infrastructure.ApiClient
import me.him188.ani.utils.io.SystemPath
import me.him188.ani.utils.io.SystemPaths
import me.him188.ani.utils.io.createTempDirectory
import me.him188.ani.utils.io.deleteRecursively
import me.him188.ani.utils.io.resolve
import me.him188.ani.utils.ktor.ApiInvoker
import me.him188.ani.utils.ktor.asScopedHttpClient
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class TrendsRepositoryTest {
    private val tempDirectory = SystemPaths.createTempDirectory("trends-repository-test")
    private val cacheFile = tempDirectory.resolve("trending.json")
    private val requests = mutableListOf<String>()
    private var responseBody = TRENDING
    private val client = HttpClient(
        MockEngine { request ->
            requests += request.url.toString()
            respond(responseBody, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        },
    ) {
        // 与生产同一份配置: 条目类型在接口里是数字, 生成的枚举按字符串声明, 要 isLenient 才读得进来
        install(ContentNegotiation) { json(ApiClient.JSON_DEFAULT) }
    }
    private var now = 1_000_000_000_000L

    @AfterTest
    fun cleanup() {
        client.close()
        tempDirectory.deleteRecursively()
    }

    private fun repository(
        cacheFile: SystemPath? = this.cacheFile,
        backgroundScope: CoroutineScope? = null,
    ) = TrendsRepository(
        ApiInvoker(client.asScopedHttpClient()) { TrendingBangumiNextApi("https://next.bgm.tv", it) },
        cacheFile = cacheFile,
        clock = { now },
        backgroundScope = backgroundScope,
    )

    @Test
    fun `头一页一小时内共用一份`() = runTest {
        val repository = repository()
        val first = repository.getTrendsInfo()
        now += 59.minutes.inWholeMilliseconds
        assertEquals(first, repository.getTrendsInfo())
        assertEquals(1, requests.size)
        assertEquals(listOf(633836, 622206), first.subjects.map { it.bangumiId })
        assertEquals("Re：从零开始的异世界生活 第四季 夺还篇", first.subjects.first().nameCn)
    }

    @Test
    fun `同时来问 - 只发一次请求`() = runTest {
        val repository = repository()
        List(4) { async { repository.getTrendsInfo() } }.awaitAll()
        assertEquals(1, requests.size)
    }

    @Test
    fun `过了一小时 - 重取`() = runTest {
        val repository = repository()
        repository.getTrendsInfo()
        now += 1.hours.inWholeMilliseconds
        repository.getTrendsInfo()
        assertEquals(2, requests.size)
    }

    @Test
    fun `落盘的那份 - 新进程一小时内直接用`() = runTest {
        repository().getTrendsInfo()
        now += 30.minutes.inWholeMilliseconds
        val restored = repository().getTrendsInfo()
        assertEquals(1, requests.size)
        assertEquals(listOf(633836, 622206), restored.subjects.map { it.bangumiId })
    }

    @Test
    fun `过期了先用手上那份 - 后台取到的新一页给之后来问的`() = runTest {
        val repository = repository(backgroundScope = backgroundScope)
        val first = repository.getTrendsInfo()
        now += 2.hours.inWholeMilliseconds
        responseBody = TRENDING_SECOND_ONLY

        assertEquals(first, repository.getTrendsInfo())
        val refreshed = repository.firstPageRefreshed.first()
        assertEquals(listOf(622206), refreshed.subjects.map { it.bangumiId })
        assertEquals(listOf(622206), repository.getTrendsInfo().subjects.map { it.bangumiId })
        assertEquals(2, requests.size)
    }

    @Test
    fun `后台取回的一页与手上的一样 - 不发通知`() = runTest {
        val repository = repository(backgroundScope = backgroundScope)
        repository.getTrendsInfo()
        now += 2.hours.inWholeMilliseconds
        repository.getTrendsInfo()
        val refreshed = async { repository.firstPageRefreshed.first() }
        // 等后台那次请求落定: 一小时内再问不会再发请求, 而且拿到的就是新存下的那份
        while (requests.size < 2) yield()
        now += 1.minutes.inWholeMilliseconds
        repository.getTrendsInfo()
        assertEquals(2, requests.size)
        assertFalse(refreshed.isCompleted)
        refreshed.cancel()
    }

    @Test
    fun `别的页 - 照常现取`() = runTest {
        val repository = repository(cacheFile = null)
        repository.getTrendsInfo()
        repository.getTrendsInfo(limit = 30, offset = 20)
        repository.getTrendsInfo(limit = 30, offset = 20)
        assertEquals(3, requests.size)
    }

    private companion object {
        /** `next.bgm.tv/p1/trending/subjects?type=2&limit=2` 的真实响应 (2026-09-26). */
        const val TRENDING = """{"data":[{"subject":{"id":633836,"name":"Re:ゼロから始める異世界生活 4th season 奪還編","nameCN":"Re：从零开始的异世界生活 第四季 夺还篇","type":2,"info":"8话 / 2026年8月12日 / 篠原正寛 / 長月達平（MF文庫J『Re:ゼロから始める異世界生活』/KADOKAWA刊） / 佐川遥","metaTags":["TV","日本","奇幻","战斗","冒险","穿越","小说改"],"rating":{"rank":690,"count":[15,4,6,7,38,111,440,690,155,112],"score":7.63,"total":1578},"locked":false,"nsfw":false,"images":{"large":"https://lain.bgm.tv/pic/cover/l/43/ca/633836_ql0f3.jpg","common":"https://lain.bgm.tv/r/400/pic/cover/l/43/ca/633836_ql0f3.jpg","medium":"https://lain.bgm.tv/r/200/pic/cover/l/43/ca/633836_ql0f3.jpg","small":"https://lain.bgm.tv/r/100/pic/cover/l/43/ca/633836_ql0f3.jpg","grid":"https://lain.bgm.tv/r/100x100/pic/cover/l/43/ca/633836_ql0f3.jpg"}},"count":2384},{"subject":{"id":622206,"name":"ヤニねこ","nameCN":"尼古喵喵","type":2,"info":"12话 / 2026年7月2日 / 木村拓 / にゃんにゃんファクトリー（講談社「ヤングマガジン」連載） / 松浦力","metaTags":["TV","喜剧","日常","日本","漫画改","青年向"],"rating":{"rank":1085,"count":[40,13,21,49,145,555,2100,2440,460,258],"score":7.45,"total":6081},"locked":false,"nsfw":false,"images":{"large":"https://lain.bgm.tv/pic/cover/l/6a/b3/622206_dpWcC.jpg","common":"https://lain.bgm.tv/r/400/pic/cover/l/6a/b3/622206_dpWcC.jpg","medium":"https://lain.bgm.tv/r/200/pic/cover/l/6a/b3/622206_dpWcC.jpg","small":"https://lain.bgm.tv/r/100/pic/cover/l/6a/b3/622206_dpWcC.jpg","grid":"https://lain.bgm.tv/r/100x100/pic/cover/l/6a/b3/622206_dpWcC.jpg"}},"count":2188}],"total":1000}"""

        /** 同一份响应只留第二条 (后台取到了不一样的新一页). */
        val TRENDING_SECOND_ONLY = TRENDING.replaceRange(
            TRENDING.indexOf("""{"subject":{"id":633836"""),
            TRENDING.indexOf("""{"subject":{"id":622206"""),
            "",
        )
    }
}
