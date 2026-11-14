/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.utils.httpdownloader

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import me.him188.ani.utils.io.deleteRecursively
import me.him188.ani.utils.io.resolve
import me.him188.ani.utils.ktor.asScopedHttpClient
import me.him188.ani.utils.ktor.installRawCookieHeader
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * 分段下载的地址被拒 (签名过期) 时换新地址接着下 ([ExpiredSourceRecovery]).
 *
 * 假网盘: 地址带 `auth_key=K`, 请求要带 Cookie `k=K`; 探大小 (`Range: bytes=0-0`) 总能成功,
 * 分段请求只有 [Server.valid] 里的地址才给 —— 建任务时地址还有效, 开始下分段时已经过期.
 */
class ExpiredSourceRefreshTest {
    private val dispatcher = StandardTestDispatcher()

    /** 跟着测试的虚拟时间走: 换地址的冷却按它算 */
    private val clock = object : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(dispatcher.scheduler.currentTime)
    }
    private val tempDir = SystemTemporaryDirectory.resolve("test-expired-source-${Clock.System.now().toEpochMilliseconds()}")
    private val clients = mutableListOf<HttpClient>()
    private val downloaders = mutableListOf<KtorHttpDownloader>()

    @AfterTest
    fun cleanup() {
        runBlocking {
            downloaders.forEach { it.close() }
            clients.forEach { it.close() }
            if (SystemFileSystem.exists(tempDir)) SystemFileSystem.deleteRecursively(tempDir)
        }
    }

    private class Server {
        /** 分段请求能用的 auth_key */
        val valid = mutableSetOf<String>()

        /** 各 auth_key 对应的文件多大 (没写的是 [FILE_SIZE]) */
        val sizes = mutableMapOf<String, Long>()

        /** 各 auth_key 收到的分段请求数 (不含探大小) */
        val segmentRequests = mutableMapOf<String, Int>()

        /** 某个 auth_key 下成这么多段之后就过期 */
        val expireAfter = mutableMapOf<String, Int>()

        /** 某个 auth_key 头几个分段请求先拒掉, 之后照常给 */
        val rejectFirst = mutableMapOf<String, Int>()

        /** 某个 auth_key 从这一刻 (虚拟时间, 毫秒) 起能用: 站点临时拒绝一阵后恢复 */
        val validFrom = mutableMapOf<String, Long>()
        var now: () -> Long = { 0L }

        private val lock = Mutex()

        // MockEngine 在别的线程上回包, 分段并发时几个请求同时进来
        suspend fun MockRequestHandleScope.handle(request: HttpRequestData): HttpResponseData = lock.withLock { respondTo(request) }

        private fun MockRequestHandleScope.respondTo(request: HttpRequestData): HttpResponseData {
            val key = request.url.parameters["auth_key"].orEmpty()
            if (request.headers[HttpHeaders.Cookie] != "k=$key") return respond("bad cookie", HttpStatusCode.Forbidden)
            val size = sizes[key] ?: FILE_SIZE
            val range = request.headers[HttpHeaders.Range]?.removePrefix("bytes=")?.split("-")
                ?: return respond("whole file not served", HttpStatusCode.BadRequest)
            val start = range[0].toLong()
            val end = range[1].toLongOrNull() ?: (size - 1)
            if (start != 0L || end != 0L) {
                val served = segmentRequests[key] ?: 0
                segmentRequests[key] = served + 1
                val usable = key in valid || validFrom[key]?.let { now() >= it } == true
                if (!usable || served < (rejectFirst[key] ?: 0)) return respond("expired", HttpStatusCode.Forbidden)
                expireAfter[key]?.let { if (served + 1 >= it) valid -= key }
            }
            return respond(
                ByteArray((end - start + 1).toInt()),
                HttpStatusCode.PartialContent,
                headersOf(
                    HttpHeaders.ContentRange to listOf("bytes $start-$end/$size"),
                    HttpHeaders.ContentLength to listOf("${end - start + 1}"),
                ),
            )
        }
    }

    private fun downloader(server: Server, refresher: DownloadSourceRefresher?): KtorHttpDownloader {
        SystemFileSystem.createDirectories(tempDir)
        val client = HttpClient(MockEngine { with(server) { handle(it) } }) {
            expectSuccess = true
            install(HttpCookies)
            install(HttpTimeout)
        }.also { it.installRawCookieHeader() }
        clients += client
        return KtorHttpDownloader(
            client = client.asScopedHttpClient(),
            fileSystem = SystemFileSystem,
            baseSaveDir = tempDir,
            clock = clock,
            parentScope = CoroutineScope(SupervisorJob() + dispatcher),
            ioDispatcher = dispatcher,
        ).also {
            it.sourceRefresher = refresher
            downloaders += it
        }
    }

    private fun source(key: String) = DownloadSource(urlOf(key), mapOf(HttpHeaders.Cookie to "k=$key"))

    private suspend fun KtorHttpDownloader.start(id: DownloadId, key: String, retries: Int = 100, concurrency: Int = SEGMENTS) {
        downloadWithId(
            downloadId = id,
            url = urlOf(key),
            options = DownloadOptions(
                headers = mapOf(HttpHeaders.Cookie to "k=$key"),
                maxRetriesPerSegment = retries,
                baseRetryDelayMillis = 1,
                maxConcurrentSegments = concurrency,
            ),
        )
        joinDownload(id)
    }

    @Test
    fun `an expired source is refreshed once and the download goes on`() = runTest(dispatcher) {
        val server = Server().apply { valid += "new" }
        var refreshes = 0
        val downloader = downloader(server) {
            refreshes++
            source("new")
        }
        val id = DownloadId("expired")
        downloader.start(id, "old")

        val state = assertNotNull(downloader.getState(id))
        assertEquals(DownloadStatus.COMPLETED, state.status)
        assertEquals(FILE_SIZE, state.downloadedBytes)
        // 三段同时被拒也只换一次; 换上的地址与请求头记进任务 (下次续传接着用)
        assertEquals(1, refreshes)
        assertEquals(urlOf("new"), state.url)
        assertEquals("k=new", state.requestHeaders[HttpHeaders.Cookie])
        assertTrue(state.segments.all { it.url == urlOf("new") })
    }

    @Test
    fun `a fresh source rejected at first is retried and the download goes on`() = runTest(dispatcher) {
        val server = Server().apply {
            valid += "new"
            // 刚换上的地址头几次也被拒 (偶尔一次), 之后照常
            rejectFirst["new"] = SEGMENTS
        }
        var refreshes = 0
        val downloader = downloader(server) {
            refreshes++
            source("new")
        }
        val id = DownloadId("rejected-at-first")
        downloader.start(id, "old")

        val state = assertNotNull(downloader.getState(id))
        // 不当场判失败 (失败的任务在界面上接不回去): 照旧退避重试, 冷却期内也不再去换
        assertEquals(DownloadStatus.COMPLETED, state.status)
        assertEquals(1, refreshes)
        assertEquals(urlOf("new"), state.url)
    }

    @Test
    fun `fresh sources rejected again and again stop being fetched and the download keeps retrying`() = runTest(dispatcher) {
        val server = Server()
        var refreshes = 0
        val downloader = downloader(server) {
            refreshes++
            source("new$refreshes")
        }
        val id = DownloadId("rejected-again")
        downloader.start(id, "old")

        val state = assertNotNull(downloader.getState(id))
        // 连着三个新地址都一段没下成: 换了也没用 (站点临时拒绝之类), 不再换. 但不替它提前判失败 ——
        // 失败的任务在界面上接不回去, 而照旧重试的话站点恢复了就能接着下. 这里一直不恢复, 重试到上限才失败
        assertEquals(DownloadStatus.FAILED, state.status)
        assertEquals(3, refreshes)
        assertEquals(urlOf("new3"), state.url)
        assertTrue(testScheduler.currentTime >= 40.minutes.inWholeMilliseconds, "took ${testScheduler.currentTime} ms")
    }

    @Test
    fun `a rejection that goes away by itself is survived even after fresh sources did not help`() = runTest(dispatcher) {
        val server = Server().apply {
            // 站点临时拒绝了十分钟 (限流之类), 之后最后换上的那个地址就能用
            now = { testScheduler.currentTime }
            validFrom["new3"] = 10.minutes.inWholeMilliseconds
        }
        var refreshes = 0
        val downloader = downloader(server) {
            refreshes++
            source("new$refreshes")
        }
        val id = DownloadId("temporary-rejection")
        downloader.start(id, "old")

        val state = assertNotNull(downloader.getState(id))
        // 换了三个都被拒之后不再换, 也没判失败: 照旧重试, 站点恢复后下完
        assertEquals(DownloadStatus.COMPLETED, state.status)
        assertEquals(FILE_SIZE, state.downloadedBytes)
        assertEquals(3, refreshes)
        assertTrue(testScheduler.currentTime >= 10.minutes.inWholeMilliseconds)
    }

    @Test
    fun `a fresh source of another size is not taken`() = runTest(dispatcher) {
        val server = Server().apply {
            valid += "other"
            sizes["other"] = FILE_SIZE + 1
        }
        var refreshes = 0
        val downloader = downloader(server) {
            refreshes++
            source("other")
        }
        val id = DownloadId("other-file")
        downloader.start(id, "old")

        val state = assertNotNull(downloader.getState(id))
        // 已经按原来的字节范围切好的分段对不上新文件: 当成没换成, 一段也不从新地址下; 原地址照旧重试到上限才失败,
        // 连着三次没换成之后也不再去换
        assertEquals(DownloadStatus.FAILED, state.status)
        assertEquals(urlOf("old"), state.url)
        assertEquals(null, server.segmentRequests["other"])
        assertEquals(3, refreshes)
        assertTrue(testScheduler.currentTime >= 40.minutes.inWholeMilliseconds, "took ${testScheduler.currentTime} ms")
    }

    @Test
    fun `without a fresh source the old one is retried as before`() = runTest(dispatcher) {
        val server = Server()
        var refreshes = 0
        val downloader = downloader(server) {
            refreshes++
            null
        }
        val id = DownloadId("no-refresh")
        downloader.start(id, "old", retries = 4)

        val state = assertNotNull(downloader.getState(id))
        assertEquals(DownloadStatus.FAILED, state.status)
        // 换不了时冷却期内不再去换, 照旧退避重试到上限
        assertEquals(1, refreshes)
        assertEquals(urlOf("old"), state.url)
    }

    @Test
    fun `a fresh source that expires after working is refreshed again`() = runTest(dispatcher) {
        val server = Server().apply {
            valid += listOf("first", "second")
            // 换来的第一个地址下成一段后也过期了 (下得久): 过了冷却期再换一个
            expireAfter["first"] = 1
        }
        val keys = ArrayDeque(listOf("first", "second"))
        val downloader = downloader(server) { source(keys.removeFirst()) }
        val id = DownloadId("expires-again")
        // 一段一段下: 第一个新地址先下成一段, 之后才过期
        downloader.start(id, "old", concurrency = 1)

        val state = assertNotNull(downloader.getState(id))
        assertEquals(DownloadStatus.COMPLETED, state.status)
        assertEquals(FILE_SIZE, state.downloadedBytes)
        assertEquals(urlOf("second"), state.url)
        assertTrue(keys.isEmpty())
    }

    private companion object {
        /** 三段 (每段 5 MB) */
        const val FILE_SIZE = 10L * 1024 * 1024 + 1024
        const val SEGMENTS = 3

        fun urlOf(key: String) = "https://dl.drive.test/file.mkv?auth_key=$key"
    }
}
