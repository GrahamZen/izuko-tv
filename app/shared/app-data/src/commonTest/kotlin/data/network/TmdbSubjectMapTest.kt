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
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.him188.ani.app.data.persistent.MemoryDataStore
import me.him188.ani.app.domain.foundation.DefaultHttpClientProvider
import me.him188.ani.app.domain.foundation.ScopedHttpClientFeatureHandler
import me.him188.ani.app.domain.foundation.ScopedHttpClientUserAgent
import me.him188.ani.app.domain.foundation.ServerListFeatureHandler
import me.him188.ani.app.domain.foundation.UserAgentFeature
import me.him188.ani.app.domain.foundation.UserAgentFeatureHandler
import me.him188.ani.app.domain.settings.NoProxyProvider
import me.him188.ani.utils.io.SystemPath
import me.him188.ani.utils.io.SystemPaths
import me.him188.ani.utils.io.createTempDirectory
import me.him188.ani.utils.io.deleteRecursively
import me.him188.ani.utils.io.exists
import me.him188.ani.utils.io.readText
import me.him188.ani.utils.io.resolve
import me.him188.ani.utils.io.writeText
import me.him188.ani.utils.ktor.asScopedHttpClient
import me.him188.ani.utils.platform.currentTimeMillis
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class TmdbSubjectMapTest {
    private val tsv = listOf(
        "# bangumi-tmdb-map v1. 列: bgm_id, backdrop, backdrop_path, stills, source",
        "237\tmovie/9323\t/jIpagj2Dud9g3ZVJFvaWikEWsYz.jpg\tmovie/9323\tauto",
        "140001\ttv/65942\t/7ZruEnSnHD6Jx5mF0hBt1E306Vt.jpg\ttv/65942\tauto",
        "516311\ttv/65942\t/7ZruEnSnHD6Jx5mF0hBt1E306Vt.jpg\ttv/65942/season/0\tauto",
        "296195\ttv/65942\t\t\tmanual",
        "311\t\t\t\tmanual",
        // 以下都认不出, 要跳过
        "100\ttv/1/season/1\t/a.jpg\t\tauto",
        "101\tbook/1\t/a.jpg\t\tauto",
        "102\ttv/2\t/a.jpg\ttv/2/season/x\tauto",
        "abc\ttv/3\t/a.jpg\t\tauto",
    ).joinToString("\n")

    private val index = TmdbSubjectMapIndex.parse(tsv.encodeToByteArray())

    private val tempDirectory = SystemPaths.createTempDirectory("tmdb-subject-map-test")

    @AfterTest
    fun cleanUp() {
        tempDirectory.deleteRecursively()
    }

    @Test
    fun `解析 - 自动结果`() {
        val entry = assertNotNull(index[140001])
        assertEquals(TmdbSubjectMapRef("tv", 65942), entry.backdrop)
        assertEquals("/7ZruEnSnHD6Jx5mF0hBt1E306Vt.jpg", entry.backdropPath)
        assertEquals(listOf(TmdbSubjectMapRef("tv", 65942)), entry.stills)
        assertEquals(false, entry.manual)
        assertEquals("tv/65942", entry.stillsKey)
    }

    @Test
    fun `解析 - 剧照只取 S0`() {
        val entry = assertNotNull(index[516311])
        assertEquals(listOf(TmdbSubjectMapRef("tv", 65942, 0)), entry.stills)
        assertEquals("tv/65942/season/0", entry.stillsKey)
    }

    @Test
    fun `解析 - 人工修正只给条目不给图`() {
        val entry = assertNotNull(index[296195])
        assertEquals(TmdbSubjectMapRef("tv", 65942), entry.backdrop)
        assertNull(entry.backdropPath)
        assertTrue(entry.manual)
    }

    @Test
    fun `分集缓存标记 - 没给剧照出处时跟着背景图条目`() {
        assertEquals("tv/65942", index[140001]?.stillsBuildKey)
        assertEquals("follow:tv/65942", index[296195]?.stillsBuildKey)
        assertEquals("follow:none", index[311]?.stillsBuildKey)
    }

    @Test
    fun `解析 - 人工确认没有对应`() {
        val entry = assertNotNull(index[311])
        assertNull(entry.backdrop)
        assertNull(entry.backdropPath)
        assertEquals(emptyList(), entry.stills)
        assertTrue(entry.manual)
    }

    @Test
    fun `解析 - 认不出的行查不到`() {
        for (id in listOf(237, 140001, 516311, 296195, 311)) assertNotNull(index[id], "$id")
        for (id in listOf(100, 101, 102, 103, 0)) assertNull(index[id], "$id")
    }

    @Test
    fun `索引 - 乱序与 CRLF 都认，同一个 id 认最后一行`() {
        val map = TmdbSubjectMapIndex.parse(
            listOf(
                "30\ttv/3\t/c.jpg\ttv/3\tauto",
                "10\ttv/1\t/a.jpg\ttv/1\tauto",
                "20\ttv/2\t/old.jpg\ttv/2\tauto",
                "20\ttv/2\t/new.jpg\ttv/2\tmanual",
            ).joinToString("\r\n").encodeToByteArray(),
        )
        assertEquals(3, map.size)
        assertEquals("/a.jpg", map[10]?.backdropPath)
        assertEquals("/new.jpg", map[20]?.backdropPath)
        assertEquals(true, map[20]?.manual)
        assertEquals("/c.jpg", map[30]?.backdropPath)
        assertNull(map[15])
    }

    @Test
    fun `读盘 - 表存在单独的文件里`() = runTest {
        val file = tempDirectory.resolve("map.tsv").apply { writeText(tsv) }
        val repository = repository(MemoryDataStore(TmdbSubjectMapCache(checkedAt = Long.MAX_VALUE)), file)
        assertEquals("/jIpagj2Dud9g3ZVJFvaWikEWsYz.jpg", repository.lookup(237)?.backdropPath)
        assertNull(repository.lookup(100))
    }

    /**
     * 刚安装, 或从把原文存在 DataStore 里的旧版升上来: 下载元数据可能是新的, 但本地没有表文件 ——
     * 要重新下整份, 也不能带着旧 ETag 去问 (对方回 304 就什么都拿不到).
     *
     * 真实时间跑: 查表等首轮下载的那 3 秒超时在虚拟时间里会立刻到点.
     */
    @Test
    fun `本地没有表文件 - 不管下载元数据多新都重新下整份`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val client = HttpClient(
            MockEngine { request ->
                requests += request
                respond(tsv, HttpStatusCode.OK)
            },
        )
        val cache = MemoryDataStore(TmdbSubjectMapCache(etag = "\"old\"", source = "x", checkedAt = currentTimeMillis()))
        val file = tempDirectory.resolve("map.tsv")
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val repository = TmdbSubjectMapRepository(cache, file, { client.asScopedHttpClient() }, flowOf(true), scope)
            val entry = withContext(Dispatchers.Default) { withTimeout(10.seconds) { repository.lookup(237) } }
            assertEquals("/jIpagj2Dud9g3ZVJFvaWikEWsYz.jpg", entry?.backdropPath)
            assertTrue(file.exists())
            assertEquals(tsv, file.readText())
            assertNull(requests.first().headers[HttpHeaders.IfNoneMatch])
        } finally {
            scope.cancel()
            client.close()
        }
    }

    @Test
    fun `背景图 - 表里有路径就直接用，不发请求也不要 token`() = runTest {
        val service = serviceWithMap()
        assertEquals(
            "https://image.tmdb.org/t/p/w1280/jIpagj2Dud9g3ZVJFvaWikEWsYz.jpg",
            service.getBackdropUrl(237, "GHOST IN THE SHELL / 攻殻機動隊"),
        )
        // 进程内热表也记上了, 页面首帧能同步读到
        assertEquals(
            "https://image.tmdb.org/t/p/w1280/jIpagj2Dud9g3ZVJFvaWikEWsYz.jpg",
            service.peekBackdropUrl(237),
        )
    }

    @Test
    fun `背景图 - 人工确认没有对应就不出图，也不去搜`() = runTest {
        val service = serviceWithMap()
        assertNull(service.getBackdropUrl(311, "千と千尋の神隠し"))
        assertTrue(service.peekBackdropResolved(311))
    }

    /** 查表上线之前本机自己搜过的结果 (正的负的都有, 老番的负结果还是永久的) 不能挡在表前面. */
    @Test
    fun `屏保剧照 - 表里确认没有对应的，本机存着以前自己搜的也不用`() = runTest {
        val old = TmdbImageCache(allBackdrops = mapOf(311 to listOf("https://old.example/a.jpg")), version = TmdbImageCache.CURRENT_VERSION)
        val service = serviceWithMap(old)
        assertEquals(emptyList(), service.getAllBackdropUrls(311, "千と千尋の神隠し"))
    }

    /** 不下载 (设置里关着, 检查时间也是新的); 下载一旦发生就是测试写错了. */
    private fun TestScope.repository(cache: MemoryDataStore<TmdbSubjectMapCache>, file: SystemPath) =
        TmdbSubjectMapRepository(
            cache = cache,
            mapFile = file,
            client = { error("不应下载对应表") },
            enabled = flowOf(false),
            scope = backgroundScope,
        )

    private fun TestScope.serviceWithMap(cache: TmdbImageCache = TmdbImageCache()): TmdbImageService {
        val file = tempDirectory.resolve("map.tsv").apply { writeText(tsv) }
        val map = repository(MemoryDataStore(TmdbSubjectMapCache(checkedAt = Long.MAX_VALUE)), file)
        return TmdbImageService(
            httpClientProvider = DefaultHttpClientProvider(
                NoProxyProvider, backgroundScope,
                featureHandlers = listOf(
                    FailOnRequestHandler,
                    ServerListFeatureHandler(flowOf(listOf(Url("https://auth.myani.org/")))),
                ),
            ),
            dataStore = MemoryDataStore(cache),
            ioDispatcher = Dispatchers.Default,
            subjectMap = map,
        )
    }

    /** 顶替一定会被请求的 UA 处理器; 任何请求都让测试失败 (AssertionError 不是 Exception, 不会被业务代码吞掉). */
    private object FailOnRequestHandler : ScopedHttpClientFeatureHandler<ScopedHttpClientUserAgent>(UserAgentFeature) {
        override fun applyToConfig(config: HttpClientConfig<*>, value: ScopedHttpClientUserAgent) =
            UserAgentFeatureHandler.applyToConfig(config, value)

        override fun applyToClient(client: HttpClient, value: ScopedHttpClientUserAgent) {
            client.plugin(HttpSend).intercept { request ->
                throw AssertionError("不应发请求: ${request.url.buildString()}")
            }
        }
    }
}
