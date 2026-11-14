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
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readByteArray
import me.him188.ani.utils.io.deleteRecursively
import me.him188.ani.utils.io.resolve
import me.him188.ani.utils.ktor.asScopedHttpClient
import org.openani.mediamp.ffmpeg.FFmpegResult
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * m3u8 下载的分片地址被拒 (签名过期) 时重新取播放列表接着下 ([ExpiredSourceRecovery]).
 *
 * 假站点: 播放列表 `index.m3u8?t=K` 里的分片与密钥地址都带同一个 `t=K`; 分片与密钥只有 [Site.valid] 里的 K 才给.
 */
class HlsSourceRefreshTest {
    private val dispatcher = StandardTestDispatcher()
    private val clock = object : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(dispatcher.scheduler.currentTime)
    }
    private val tempDir = SystemTemporaryDirectory.resolve("test-hls-refresh-${Clock.System.now().toEpochMilliseconds()}")
    private val clients = mutableListOf<HttpClient>()
    private val downloaders = mutableListOf<KtorHttpDownloader>()

    /** 合并时交给 ffmpeg 的本机播放列表 */
    private var mergedPlaylist: String? = null

    @AfterTest
    fun cleanup() {
        runBlocking {
            downloaders.forEach { it.close() }
            clients.forEach { it.close() }
            if (SystemFileSystem.exists(tempDir)) SystemFileSystem.deleteRecursively(tempDir)
        }
    }

    private class Site {
        val valid = mutableSetOf<String>()

        /** 各 K 的播放列表有几个分片 (没写的是 3 个) */
        val segmentCounts = mutableMapOf<String, Int>()

        /** 播放列表地址不带 `t` 时列表里用的 K (站点每次取列表现发签名) */
        var issuing = ""

        /** 某个 K 下成这么多个分片之后过期 */
        val expireAfter = mutableMapOf<String, Int>()

        /** 某个 K 过期之后, 不带 `t` 的播放列表改发哪个 K */
        val issuingAfter = mutableMapOf<String, String>()

        /** 永远 404 的分片 (不管哪个 K) */
        val missing = mutableSetOf<Int>()

        /** 某个分片头几次请求回 403 (站点临时限流), 之后照常给 */
        val throttled = mutableMapOf<Int, Int>()

        /** 分片请求一律回 403 (站点临时拒绝), 直到虚拟时间过了这一刻 */
        var rejectAllUntil = 0L
        var now: () -> Long = { 0L }

        /** 每取一次播放列表, 列表里多一个分片 (插播片段的位置每次不同的站点: 每次取到的都对不上前一次的) */
        var growsOnEachFetch = false
        private var fetches = 0

        /** 收到的请求: "K/seg2.ts"、"K/key.bin"、"K/index.m3u8" */
        val requests = mutableListOf<String>()
        private val served = mutableMapOf<String, Int>()
        private val lock = Mutex()

        fun count(what: String) = requests.count { it == what }

        suspend fun MockRequestHandleScope.handle(request: HttpRequestData): HttpResponseData = lock.withLock { respondTo(request) }

        private fun MockRequestHandleScope.respondTo(request: HttpRequestData): HttpResponseData {
            val file = request.url.encodedPath.substringAfterLast('/')
            val key = request.url.parameters["t"] ?: issuing
            requests += "$key/$file"
            if (file == "index.m3u8") return respond(playlist(key, if (growsOnEachFetch) fetches++ else 0))
            if (key !in valid) return respond("expired", HttpStatusCode.Forbidden)
            if (file == "key.bin") return respond(ByteArray(16))
            if (now() < rejectAllUntil) return respond("slow down", HttpStatusCode.Forbidden)
            val index = file.removePrefix("seg").removeSuffix(".ts").toInt()
            if (index in missing) return respond("gone", HttpStatusCode.NotFound)
            throttled[index]?.let { left ->
                if (left > 0) {
                    throttled[index] = left - 1
                    return respond("slow down", HttpStatusCode.Forbidden)
                }
            }
            val count = (served[key] ?: 0) + 1
            served[key] = count
            expireAfter[key]?.let {
                if (count >= it) {
                    valid -= key
                    issuingAfter[key]?.let { next -> issuing = next }
                }
            }
            return respond(ByteArray(1024) { index.toByte() })
        }

        private fun playlist(key: String, extraSegments: Int) = buildString {
            appendLine("#EXTM3U")
            appendLine("#EXT-X-VERSION:3")
            appendLine("#EXT-X-TARGETDURATION:5")
            appendLine("#EXT-X-MEDIA-SEQUENCE:0")
            appendLine("#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin?t=$key\",IV=0x0102030405060708090A0B0C0D0E0F10")
            repeat((segmentCounts[key] ?: 3) + extraSegments) {
                appendLine("#EXTINF:4.0,")
                appendLine("seg$it.ts?t=$key")
            }
            appendLine("#EXT-X-ENDLIST")
        }
    }

    private fun downloader(site: Site, refresher: DownloadSourceRefresher?): KtorHttpDownloader {
        SystemFileSystem.createDirectories(tempDir)
        val client = HttpClient(MockEngine { with(site) { handle(it) } }) {
            expectSuccess = true
            install(HttpTimeout)
        }
        clients += client
        return object : KtorHttpDownloader(
            client = client.asScopedHttpClient(),
            fileSystem = SystemFileSystem,
            baseSaveDir = tempDir,
            clock = clock,
            parentScope = CoroutineScope(SupervisorJob() + dispatcher),
            ioDispatcher = dispatcher,
        ) {
            // 不真跑 ffmpeg: 记下交给它的本机播放列表, 写一个输出文件
            override suspend fun executeFfmpeg(args: List<String>): FFmpegResult {
                val input = Path(args[args.indexOf("-i") + 1])
                mergedPlaylist = SystemFileSystem.source(input).buffered().use { it.readByteArray().decodeToString() }
                SystemFileSystem.sink(Path(args.last())).buffered().use { it.write(ByteArray(8)) }
                return FFmpegResult(exitCode = 0)
            }
        }.also {
            it.sourceRefresher = refresher
            downloaders += it
        }
    }

    private suspend fun KtorHttpDownloader.start(id: DownloadId, url: String, retries: Int = 100): DownloadState {
        // 一片一片下: 头一片用原来的地址下成, 之后才过期
        downloadWithId(id, url, DownloadOptions(maxRetriesPerSegment = retries, baseRetryDelayMillis = 1, maxConcurrentSegments = 1))
        joinDownload(id)
        return assertNotNull(getState(id))
    }

    @Test
    fun `expired segment links are replaced from a fresh playlist and downloaded segments are kept`() = runTest(dispatcher) {
        val site = Site().apply {
            valid += listOf("old", "new")
            expireAfter["old"] = 1
        }
        var refreshes = 0
        val downloader = downloader(site) {
            refreshes++
            DownloadSource(playlistOf("new"), emptyMap())
        }

        val state = downloader.start(DownloadId("expired"), playlistOf("old"))

        assertEquals(DownloadStatus.COMPLETED, state.status)
        assertEquals(1, refreshes)
        assertEquals(playlistOf("new"), state.url)
        // 头一片是用原来的地址下的, 换了列表不重下
        assertEquals(1, site.count("old/seg0.ts"))
        assertEquals(0, site.count("new/seg0.ts"))
        assertEquals(1, site.count("new/seg1.ts"))
        assertEquals(1, site.count("new/seg2.ts"))
        // 密钥地址跟着换了, 合并用的本机列表指得到密钥
        assertEquals(1, site.count("new/key.bin"))
        assertTrue(assertNotNull(mergedPlaylist).contains("URI=\"keys/"), mergedPlaylist)
    }

    @Test
    fun `a playlist that signs its segments on every fetch is fetched again at the same address`() = runTest(dispatcher) {
        val site = Site().apply {
            valid += listOf("a", "b")
            issuing = "a"
            expireAfter["a"] = 1
            issuingAfter["a"] = "b"
        }
        // 解析不出新的列表地址 (没有换地址的办法): 原来的列表地址重新取一遍, 分片的签名是新的
        val downloader = downloader(site, refresher = null)

        val state = downloader.start(DownloadId("same-playlist"), "https://hls.test/v/index.m3u8")

        assertEquals(DownloadStatus.COMPLETED, state.status)
        assertEquals("https://hls.test/v/index.m3u8", state.url)
        assertEquals(1, site.count("a/seg0.ts"))
        assertEquals(1, site.count("b/seg1.ts"))
        assertEquals(1, site.count("b/seg2.ts"))
    }

    @Test
    fun `a fresh playlist with different content is downloaded again from the start`() = runTest(dispatcher) {
        val site = Site().apply {
            valid += listOf("old", "new")
            expireAfter["old"] = 1
            segmentCounts["new"] = 4
        }
        val downloader = downloader(site) { DownloadSource(playlistOf("new"), emptyMap()) }

        val state = downloader.start(DownloadId("different"), playlistOf("old"))

        // 分片数对不上: 已经下好的那一片不能接着用, 按新列表四片都下
        assertEquals(DownloadStatus.COMPLETED, state.status)
        assertEquals(4, state.totalSegments)
        assertEquals((0..3).map { 1 }, (0..3).map { site.count("new/seg$it.ts") })
    }

    @Test
    fun `a site that rejects everything for a while costs at most one restart and the download survives`() = runTest(dispatcher) {
        val site = Site().apply {
            valid += "k"
            issuing = "k"
            growsOnEachFetch = true
            now = { testScheduler.currentTime }
        }
        val downloader = downloader(site, refresher = null)
        // 建任务时取了一次列表 (3 片); 之后站点把分片请求全拒十分钟
        site.rejectAllUntil = 10.minutes.inWholeMilliseconds

        val state = downloader.start(DownloadId("rejects-for-a-while"), "https://hls.test/v/index.m3u8")

        // 被拒了一阵去换列表, 这个站每次取到的列表都对不上前一次: 从头下了一回. 从头下之后还是一片没下成 ——
        // 被拒不是过期, 不再从头下 (不白丢进度), 也不判失败; 站点恢复后接着下完
        assertEquals(DownloadStatus.COMPLETED, state.status)
        assertEquals(4, state.totalSegments)
        assertTrue(testScheduler.currentTime >= 10.minutes.inWholeMilliseconds)
    }

    @Test
    fun `a segment rejected only briefly is retried without fetching a fresh playlist`() = runTest(dispatcher) {
        val site = Site().apply {
            valid += listOf("old", "new")
            throttled[1] = 3
        }
        var refreshes = 0
        val downloader = downloader(site) {
            refreshes++
            DownloadSource(playlistOf("new"), emptyMap())
        }

        val state = downloader.start(DownloadId("throttled"), playlistOf("old"))

        // 偶尔被拒几次不是过期: 照旧退避重试. 换列表在有的站点上等于整集从头下, 不能一被拒就换
        assertEquals(DownloadStatus.COMPLETED, state.status)
        assertEquals(0, refreshes)
        assertEquals(1, site.count("old/index.m3u8"))
        assertEquals(playlistOf("old"), state.url)
    }

    @Test
    fun `a segment that stays missing does not keep fetching fresh playlists`() = runTest(dispatcher) {
        val site = Site().apply {
            valid += listOf("old", "new1", "new2", "new3")
            missing += 1
        }
        var refreshes = 0
        val downloader = downloader(site) {
            refreshes++
            DownloadSource(playlistOf("new$refreshes"), emptyMap())
        }

        val state = downloader.start(DownloadId("missing-segment"), playlistOf("old"))

        // 坏掉的那一片换了列表照样 404: 为它换过两回就不再换, 重试到上限后失败 (同没有换地址时)
        assertEquals(DownloadStatus.FAILED, state.status)
        assertEquals(2, refreshes)
    }

    private companion object {
        fun playlistOf(key: String) = "https://hls.test/v/index.m3u8?t=$key"
    }
}
