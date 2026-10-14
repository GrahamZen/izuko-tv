/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.clouddrive

import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.decodeURLPart
import io.ktor.http.encodeURLPathPart
import io.ktor.http.headersOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import me.him188.ani.app.data.models.preference.CloudDriveAccount
import me.him188.ani.app.data.models.preference.CloudDriveAccounts
import me.him188.ani.app.domain.media.probe.MkvSamples
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.source.MediaFetchRequest
import me.him188.ani.datasources.api.topic.EpisodeRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class WebDavDriveTest {
    // region 解析

    @Test
    fun `parses an apache style listing`() {
        val xml = """<?xml version="1.0" encoding="utf-8"?>
            <D:multistatus xmlns:D="DAV:">
            <D:response xmlns:lp1="DAV:">
            <D:href>/dav/%e5%8a%a8%e7%94%bb/</D:href>
            <D:propstat><D:prop><lp1:resourcetype><D:collection/></lp1:resourcetype></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>
            </D:response>
            <D:response xmlns:lp1="DAV:">
            <D:href>/dav/%e5%8a%a8%e7%94%bb/%E6%81%8B%E7%88%B1FLOPS/</D:href>
            <D:propstat><D:prop><lp1:resourcetype><D:collection/></lp1:resourcetype>
            <lp1:getlastmodified>Tue, 14 Oct 2026 08:00:00 GMT</lp1:getlastmodified></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>
            </D:response>
            <D:response xmlns:lp1="DAV:">
            <D:href>/dav/%e5%8a%a8%e7%94%bb/[Sub] Renai Flops - 01 [1080p].mkv</D:href>
            <D:propstat><D:prop><lp1:resourcetype/><lp1:getcontentlength>734003200</lp1:getcontentlength>
            <lp1:getlastmodified>Fri, 31 Dec 1999 23:59:59 GMT</lp1:getlastmodified></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>
            </D:response>
            </D:multistatus>
        """.trimIndent()
        val files = WebDav.parseMultistatus(xml, "/dav/%E5%8A%A8%E7%94%BB/")
        assertEquals(listOf("恋爱FLOPS", "[Sub] Renai Flops - 01 [1080p].mkv"), files.map { it.fileName })

        val folder = files[0]
        assertTrue(folder.dir)
        assertFalse(folder.isVideo)
        assertEquals("/dav/%E5%8A%A8%E7%94%BB/%E6%81%8B%E7%88%B1FLOPS/", WebDav.pathOf(folder.fid))
        assertEquals(1791964800000, folder.updatedAt)

        val video = files[1]
        assertFalse(video.dir)
        assertTrue(video.isVideo)
        assertEquals(734003200, video.size)
        assertEquals(946684799000, video.updatedAt)
        // 占位地址里的文件 id 不能带 `/`
        assertFalse('/' in video.fid)
        assertEquals(WebDav.idOf("/dav/%E5%8A%A8%E7%94%BB/"), video.parentFid)
        assertEquals("[Sub] Renai Flops - 01 [1080p].mkv", WebDav.nameOf(video.fid))
        assertEquals(folder.parentFid, WebDav.parentIdOf(video.fid))
    }

    @Test
    fun `parses lowercase prefixes - full url hrefs and missing properties`() {
        val xml = """<?xml version="1.0"?>
            <d:multistatus xmlns:d="DAV:">
            <d:response><d:href>http://nas.test:5005/%E5%8A%A8%E7%94%BB</d:href>
            <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
            <d:response><d:href>http://nas.test:5005/%E5%8A%A8%E7%94%BB/Show</d:href>
            <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
            <d:propstat><d:prop><d:getcontentlength/></d:prop><d:status>HTTP/1.1 404 Not Found</d:status></d:propstat></d:response>
            <d:response><d:href>http://nas.test:5005/%E5%8A%A8%E7%94%BB/a.chs.ass</d:href>
            <d:propstat><d:prop><d:resourcetype/><d:getcontentlength>100</d:getcontentlength><d:getlastmodified>2026-10-14T08:00:00Z</d:getlastmodified></d:prop>
            <d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
            </d:multistatus>
        """.trimIndent()
        val files = WebDav.parseMultistatus(xml, "/%E5%8A%A8%E7%94%BB")
        assertEquals(listOf("Show", "a.chs.ass"), files.map { it.fileName })
        // 不带结尾 `/` 的文件夹也认成文件夹, id 按文件夹补上 `/`
        assertTrue(files[0].dir)
        assertEquals("/%E5%8A%A8%E7%94%BB/Show/", WebDav.pathOf(files[0].fid))
        assertFalse(files[1].dir)
        assertFalse(files[1].isVideo)
        assertEquals(100, files[1].size)
        assertEquals(1791964800000, files[1].updatedAt)
    }

    @Test
    fun `ids - urls and display paths`() {
        val protocol = WebDavSources.newProtocol("NAS", " http://192.168.1.10:5005/dav ")
        assertEquals(WebDav.TYPE, protocol.type)
        assertEquals("/dav/", WebDav.pathOf(protocol.rootId))
        val id = WebDav.idOf("/dav/动画/恋爱 FLOPS/01.mkv")
        assertEquals("http://192.168.1.10:5005/dav/%E5%8A%A8%E7%94%BB/%E6%81%8B%E7%88%B1%20FLOPS/01.mkv", WebDav.urlOf(protocol, id))
        assertEquals("/动画/恋爱 FLOPS/01.mkv", WebDav.displayPath(protocol, id))
        assertEquals("/", WebDav.displayPath(protocol, protocol.rootId))

        val defaultPort = WebDavSources.newProtocol("NAS", "https://nas.example.com/")
        assertEquals("https://nas.example.com/a.mkv", WebDav.urlOf(defaultPort, WebDav.idOf("/a.mkv")))
        assertFailsWith<IllegalArgumentException> { WebDavSources.newProtocol("NAS", "smb://nas/share") }
    }

    @Test
    fun `http dates`() {
        assertEquals(1791964800000, WebDav.parseHttpDate("Tue, 14 Oct 2026 08:00:00 GMT"))
        assertEquals(1791964800000, WebDav.parseHttpDate("Tuesday, 14-Oct-26 08:00:00 GMT"))
        assertEquals(1791964800000, WebDav.parseHttpDate("2026-10-14T08:00:00Z"))
        assertEquals(null, WebDav.parseHttpDate("yesterday"))
    }

    // endregion

    // region 当网盘用

    private val protocol = WebDavSources.newProtocol("NAS", "http://nas.test:5005/dav")
        .let { it.copy(library = DriveLibraryConfig(folders = listOf(WebDav.idOf("/dav/动画/")))) }

    private fun account() = CloudDriveAccount(username = "anime", password = "密码")

    private fun server() = FakeWebDav(username = "anime", password = "密码").apply {
        folder("/dav/", dirs = listOf("动画", "照片"))
        folder("/dav/动画/", dirs = listOf("2026-10", "2026-07"))
        folder("/dav/动画/2026-10/", dirs = listOf("恋爱FLOPS", "别的番"))
        folder("/dav/动画/2026-07/", dirs = listOf("夏番"))
        folder(
            "/dav/动画/2026-10/恋爱FLOPS/",
            files = listOf("恋爱FLOPS - 01.mkv", "恋爱FLOPS - 02.mkv", "恋爱FLOPS - 02.chs.ass"),
        )
    }

    @Test
    fun `password login checks the server and rolls back on wrong passwords`() = runTest {
        val server = server()
        val accounts = MemorySettings(CloudDriveAccounts.Default)
        val service = testDriveService(accounts, protocol) { server.respond(this, it) }
        assertTrue(protocol.supportsPasswordLogin)

        assertFailsWith<CloudDriveAuthException> { service.loginWithPassword("anime", "错的") }
        assertFalse(accounts.state.value.of(protocol.id).isLoggedIn)

        service.loginWithPassword("anime", "密码")
        val account = accounts.state.value.of(protocol.id)
        assertTrue(account.isLoggedIn)
        assertEquals("anime", account.username)
        assertEquals("/dav/", server.listed.last())
    }

    @Test
    fun `finds the subject in the media folders and plays with basic auth`() = realTimeTest {
        val server = server()
        val accounts = MemorySettings(CloudDriveAccounts.Default.with(protocol.id, account()))
        val service = testDriveService(accounts, protocol) { server.respond(this, it) }
        val source = CloudDriveMediaSource(service, CloudDriveArguments(protocol = protocol))
        val request = MediaFetchRequest(
            subjectId = "375735",
            episodeId = "2",
            subjectNames = listOf("恋爱FLOPS"),
            episodeSort = EpisodeSort(2),
            episodeName = "",
        )
        val media = source.fetch(request).results.toList().map { it.media }
        assertEquals(listOf(EpisodeRange.single(EpisodeSort(1)), EpisodeRange.single(EpisodeSort(2))), media.map { it.episodeRange })
        // 媒体文件夹往下列两层建索引, 别的根文件夹 (照片) 不列
        assertFalse("/dav/照片/" in server.listed)

        val episode2 = media[1]
        val fileId = service.placeholders.fileIdOf(episode2.download.uri)!!
        val playback = service.resolvePlayback(fileId)
        assertEquals(
            "http://nas.test:5005/dav/%E5%8A%A8%E7%94%BB/2026-10/%E6%81%8B%E7%88%B1FLOPS/%E6%81%8B%E7%88%B1FLOPS%20-%2002.mkv",
            playback.url,
        )
        assertEquals(WebDav.basicAuthorization("anime", "密码"), playback.headers[HttpHeaders.Authorization])
        assertEquals(listOf("恋爱FLOPS - 02.chs.ass"), playback.subtitles.map { it.url.substringAfterLast('/').decodeURLPart() })
    }

    @Test
    fun `reads the video header of this episode once for the resolution`() = realTimeTest {
        val server = server().apply {
            contents["/dav/动画/2026-10/恋爱FLOPS/恋爱FLOPS - 02.mkv"] = MkvSamples.video(3840, 2160, durationSeconds = 1440)
        }
        val accounts = MemorySettings(CloudDriveAccounts.Default.with(protocol.id, account()))
        val service = testDriveService(accounts, protocol) { server.respond(this, it) }
        val source = CloudDriveMediaSource(service, CloudDriveArguments(protocol = protocol))
        val request = MediaFetchRequest(
            subjectId = "375735",
            episodeId = "2",
            subjectNames = listOf("恋爱FLOPS"),
            episodeSort = EpisodeSort(2),
            episodeName = "",
        )
        val media = source.fetch(request).results.toList().map { it.media }
        // 文件名里没写分辨率: 第 2 集按文件头标 4K 并有码率 (列表给的大小 ÷ 时长), 没读的第 1 集仍按默认
        val episode2 = media.single { it.episodeRange == EpisodeRange.single(EpisodeSort(2)) }
        assertEquals("4K", episode2.properties.resolution)
        assertEquals("4.1 Mbps", DriveVideoBitrates.label(episode2.mediaId))
        assertEquals("1080P", media.single { it.episodeRange == EpisodeRange.single(EpisodeSort(1)) }.properties.resolution)
        assertEquals(listOf("/dav/动画/2026-10/恋爱FLOPS/恋爱FLOPS - 02.mkv"), server.read)

        // 记在账号里, 再搜不再读
        val again = source.fetch(request).results.toList().map { it.media }
        assertEquals("4K", again.single { it.episodeRange == EpisodeRange.single(EpisodeSort(2)) }.properties.resolution)
        assertEquals(1, server.read.size)
        assertEquals(1, accounts.state.value.of(protocol.id).videoInfo.size)
    }

    @Test
    fun `a whole subject query reads the headers of every episode`() = realTimeTest {
        val folder = "/dav/动画/2026-10/恋爱FLOPS/"
        val server = server().apply {
            contents["${folder}恋爱FLOPS - 01.mkv"] = MkvSamples.video(1920, 1080, durationSeconds = 1440)
            contents["${folder}恋爱FLOPS - 02.mkv"] = MkvSamples.video(3840, 2160, durationSeconds = 1440)
        }
        val accounts = MemorySettings(CloudDriveAccounts.Default.with(protocol.id, account()))
        val service = testDriveService(accounts, protocol) { server.respond(this, it) }
        val source = CloudDriveMediaSource(service, CloudDriveArguments(protocol = protocol))
        // 带剧集列表的短番: 各集共用这一次查询的结果 (切集不再查), 别的集也要读 (在后台, 不等)
        val request = MediaFetchRequest(
            subjectId = "375735",
            episodeId = "1",
            subjectNames = listOf("恋爱FLOPS"),
            episodeSort = EpisodeSort(1),
            episodeName = "",
            episodes = listOf(MediaFetchRequest.Episode("1", EpisodeSort(1)), MediaFetchRequest.Episode("2", EpisodeSort(2))),
        )
        val media = source.fetch(request).results.toList().map { it.media }
        assertEquals("1080P", media.single { it.episodeRange == EpisodeRange.single(EpisodeSort(1)) }.properties.resolution)
        // 第 2 集读完后记下, 并让切集时重查这个源 (见 DriveVideoInfoRefresh)
        withTimeout(5.seconds) {
            while (accounts.state.value.of(protocol.id).videoInfo.size < 2) delay(20)
        }
        assertEquals(setOf("${folder}恋爱FLOPS - 01.mkv", "${folder}恋爱FLOPS - 02.mkv"), server.read.toSet())
        // 重查时直接用记下的, 不再读文件头
        val again = source.fetch(request.copy(episodeId = "2", episodeSort = EpisodeSort(2))).results.toList().map { it.media }
        assertEquals("4K", again.single { it.episodeRange == EpisodeRange.single(EpisodeSort(2)) }.properties.resolution)
        assertEquals(2, server.read.size)
    }

    @Test
    fun `the library index is reused until the media folders change`() = runTest {
        val server = server()
        val accounts = MemorySettings(CloudDriveAccounts.Default.with(protocol.id, account()))
        val service = testDriveService(accounts, protocol) { server.respond(this, it) }
        assertEquals(listOf("恋爱FLOPS"), service.browser.search("恋爱").map { it.fileName })
        val afterFirst = server.listed.size
        assertEquals(listOf("夏番"), service.browser.search("夏番").map { it.fileName })
        assertEquals(afterFirst, server.listed.size)

        // 只列一层时 (媒体文件夹/番名), 第二层的番不在索引里
        service.updateProtocol(protocol.copy(library = protocol.library.copy(depth = 1)))
        assertEquals(emptyList(), service.browser.search("恋爱"))
        assertEquals(listOf("2026-10"), service.browser.search("2026-10").map { it.fileName })
    }

    @Test
    fun `an unreachable library is an error - not an empty result`() = runTest {
        val server = server()
        val accounts = MemorySettings(CloudDriveAccounts.Default.with(protocol.id, CloudDriveAccount(username = "anime", password = "旧密码")))
        val service = testDriveService(accounts, protocol) { server.respond(this, it) }
        assertFailsWith<CloudDriveAuthException> { service.browser.search("恋爱") }
    }

    // endregion
}

/**
 * 内存里的 WebDAV 服务器: 按解码后的文件夹路径给 `PROPFIND` (Depth 1) 的响应, 核对 Basic 认证.
 */
private class FakeWebDav(private val username: String, private val password: String) {
    private class Folder(val dirs: List<String>, val files: List<String>)

    private val folders = HashMap<String, Folder>()

    /** 列过的文件夹 (解码后的路径), 按顺序. */
    val listed = mutableListOf<String>()

    fun folder(path: String, dirs: List<String> = emptyList(), files: List<String> = emptyList()) {
        folders[path] = Folder(dirs, files)
    }

    /** 文件内容 (解码后的路径 → 字节), `GET` 时按 Range 给; 没放内容的文件回 404. */
    val contents = HashMap<String, ByteArray>()

    /** `GET` 过的文件 (解码后的路径), 按顺序. */
    val read = mutableListOf<String>()

    fun respond(scope: MockRequestHandleScope, request: HttpRequestData): HttpResponseData {
        if (request.headers[HttpHeaders.Authorization] != WebDav.basicAuthorization(username, password)) {
            return scope.respond("", HttpStatusCode.Unauthorized)
        }
        val path = request.url.encodedPath.split('/').joinToString("/") { it.decodeURLPart() }
        if (request.method.value == "GET") return get(scope, request, path)
        check(request.method.value == "PROPFIND") { "unexpected ${request.method.value} ${request.url}" }
        check(request.headers["Depth"] == "1")
        val folder = folders[path] ?: return scope.respond("", HttpStatusCode.NotFound)
        listed += path
        val body = buildString {
            append("""<?xml version="1.0" encoding="utf-8"?><D:multistatus xmlns:D="DAV:">""")
            append(response(path, dir = true))
            folder.dirs.forEach { append(response("$path$it/", dir = true)) }
            folder.files.forEach { append(response("$path$it", dir = false)) }
            append("</D:multistatus>")
        }
        return scope.respond(body, HttpStatusCode.MultiStatus, headersOf(HttpHeaders.ContentType, "application/xml; charset=utf-8"))
    }

    private fun get(scope: MockRequestHandleScope, request: HttpRequestData, path: String): HttpResponseData {
        read += path
        val body = contents[path] ?: return scope.respond("", HttpStatusCode.NotFound)
        val range = request.headers[HttpHeaders.Range]?.removePrefix("bytes=")?.split('-')
            ?: return scope.respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, body.size.toString()))
        val from = range[0].toInt()
        val to = minOf(range[1].toInt(), body.size - 1)
        return scope.respond(
            body.copyOfRange(from, to + 1), HttpStatusCode.PartialContent,
            headersOf(HttpHeaders.ContentRange, "bytes $from-$to/${body.size}"),
        )
    }

    private fun response(path: String, dir: Boolean): String {
        val href = path.split('/').joinToString("/") { it.encodeURLPathPart() }
        val type = if (dir) "<D:resourcetype><D:collection/></D:resourcetype>" else "<D:resourcetype/><D:getcontentlength>734003200</D:getcontentlength>"
        return "<D:response><D:href>$href</D:href><D:propstat><D:prop>$type</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>"
    }
}
