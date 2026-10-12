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
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
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
import kotlin.test.assertNull
import kotlin.time.Clock

/**
 * 建任务时探测 Range ([KtorHttpDownloader.probeRangeSupport]): 网盘直链靠 Cookie 鉴权, 探测也得原样带上;
 * 探测卡住时不能拖住建任务 (建缓存、删缓存都在等它)。
 */
class RangeProbeTest {
    private val dispatcher = StandardTestDispatcher()
    private val tempDir = SystemTemporaryDirectory.resolve("test-range-probe-${Clock.System.now().toEpochMilliseconds()}")
    private val clients = mutableListOf<HttpClient>()
    private val downloaders = mutableListOf<KtorHttpDownloader>()

    // 网盘登录 Cookie 常见的样子: 值里有 base64 的 + / =
    private val cookie = "sid=a1B+c2/D3e==; uid=AAQ1x/y+z"

    @AfterTest
    fun cleanup() {
        runBlocking {
            downloaders.forEach { it.close() }
            clients.forEach { it.close() }
            if (SystemFileSystem.exists(tempDir)) SystemFileSystem.deleteRecursively(tempDir)
        }
    }

    private fun downloader(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): KtorHttpDownloader {
        SystemFileSystem.createDirectories(tempDir)
        // 同应用的默认 client: 装了 Cookie 与超时插件
        val client = HttpClient(MockEngine(handler)) {
            expectSuccess = true
            install(HttpCookies)
            install(HttpTimeout)
        }.also { it.installRawCookieHeader() }
        clients += client
        return KtorHttpDownloader(
            client = client.asScopedHttpClient(),
            fileSystem = SystemFileSystem,
            baseSaveDir = tempDir,
            parentScope = CoroutineScope(SupervisorJob() + dispatcher),
            ioDispatcher = dispatcher,
        ).also { downloaders += it }
    }

    /** 带对了 Cookie 才给, 按 Range 回 */
    private fun MockRequestHandleScope.driveFile(request: HttpRequestData): HttpResponseData {
        if (request.headers[HttpHeaders.Cookie] != cookie) return respond("require login", HttpStatusCode.Forbidden)
        val range = request.headers[HttpHeaders.Range]?.removePrefix("bytes=")?.split("-")
            ?: return respond(ByteArray(FILE_SIZE.toInt()), HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, "$FILE_SIZE"))
        val start = range[0].toLong()
        val end = range[1].toLongOrNull() ?: (FILE_SIZE - 1)
        return respond(
            ByteArray((end - start + 1).toInt()),
            HttpStatusCode.PartialContent,
            headersOf(
                HttpHeaders.ContentRange to listOf("bytes $start-$end/$FILE_SIZE"),
                HttpHeaders.ContentLength to listOf("${end - start + 1}"),
            ),
        )
    }

    @Test
    fun `range probe sends the drive cookie as is`() = runTest(dispatcher) {
        val downloader = downloader { driveFile(it) }
        val id = DownloadId("drive")
        downloader.downloadWithId(
            url = "https://dl.drive.test/file.mkv?auth_key=1",
            downloadId = id,
            options = DownloadOptions(headers = mapOf(HttpHeaders.Cookie to cookie)),
        )
        val state = assertNotNull(downloader.getState(id))
        // 探测成功: 按 5 MB 分段, 每段有自己的范围
        assertEquals(2, state.segments.size)
        assertEquals(0L, state.segments[0].rangeStart)
    }

    @Test
    fun `a stalled range probe does not hold up the download`() = runTest(dispatcher) {
        val downloader = downloader { request ->
            if (request.headers[HttpHeaders.Range] == "bytes=0-0") awaitCancellation()
            driveFile(request)
        }
        val id = DownloadId("stalled")
        downloader.downloadWithId(
            url = "https://dl.drive.test/file.mkv?auth_key=2",
            downloadId = id,
            options = DownloadOptions(headers = mapOf(HttpHeaders.Cookie to cookie)),
        )
        // 等满探测超时后照样建出任务: 整条下 (单段, 不带范围)
        val state = assertNotNull(downloader.getState(id))
        assertEquals(1, state.segments.size)
        assertNull(state.segments[0].rangeStart)
    }

    private companion object {
        /** 比一段 (5 MB) 大一点 */
        const val FILE_SIZE = 5L * 1024 * 1024 + 1024
    }
}
