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
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import me.him188.ani.app.data.models.preference.CloudDriveAccount
import me.him188.ani.app.data.models.preference.CloudDrivePlaybackMode
import me.him188.ani.app.platform.PlaybackRequestHints
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 网盘能直接取分享里文件的直链时 ([DriveApiConfig.downloadFromShare]), 播放分享里的原文件不转存; 取不到才转存.
 */
class CloudDriveShareDownloadTest {
    private fun HttpRequestData.path() = url.encodedPath

    private val loggedIn = CloudDriveAccount(cookie = "sid=p1", shareSaveFolderId = "save1")

    private val ref = DriveShareFileRef("share1", "", "sf1", "tok1", "Show - 01.mkv", 500, folderId = "sd1")

    /** 分享 share1 的文件夹 sd1 里有第 1 集与它的简体字幕; 别的请求交给 [other]. */
    private fun service(
        account: CloudDriveAccount,
        requests: MutableList<HttpRequestData>,
        other: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ) = testDriveService(accountsOf(account), TestDrive.shareDownloadProtocol) { request ->
        requests += request
        when (request.path()) {
            "/api/share/open" -> reply("""{"code":0,"data":{"token":"st1","title":"t"}}""")
            "/api/share/list" -> reply(
                listJson(
                    video("sf1", "Show - 01.mkv", parent = "sd1", size = 500, token = "tok1"),
                    DriveFile("sf2", fileName = "Show - 01.sc.ass", parentFid = "sd1", size = 10, shareToken = "tok2"),
                ),
            )

            else -> other(request)
        }
    }

    /** 同 [service], 另外能转存: 转存文件夹 save1 转存前是空的, 转存后有视频 new1 与字幕 new2. */
    private fun savingService(
        account: CloudDriveAccount,
        requests: MutableList<HttpRequestData>,
        other: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): CloudDriveService {
        var saved = false
        return service(account, requests) { request ->
            when (request.path()) {
                "/api/files/list" -> reply(
                    if (!saved) {
                        listJson()
                    } else {
                        listJson(
                            video("new1", "Show - 01.mkv", parent = "save1", size = 500),
                            DriveFile("new2", fileName = "Show - 01.sc.ass", parentFid = "save1", size = 10),
                        )
                    },
                )

                "/api/share/save" -> {
                    saved = true
                    reply("""{"code":0,"data":{"task":"task1"}}""")
                }

                "/api/tasks/task1" -> reply("""{"code":0,"data":{"state":"done","result":{"ids":["new1","new2"]}}}""")
                "/api/files/download" -> reply(downloadJson(request.jsonBody().strings("ids")))
                else -> other(request)
            }
        }
    }

    @Test
    fun `share video plays from the share link without saving`() = realTimeTest {
        val requests = mutableListOf<HttpRequestData>()
        val service = service(loggedIn, requests) { request ->
            when (request.path()) {
                // 条目里没有 id: 按请求里文件的顺序对应
                "/api/share/download" -> reply(
                    """{"code":0,"data":[{"url":"https://share-dl.drive.test/sf1"},{"url":"https://share-dl.drive.test/sf2"}]}""",
                )

                else -> error("unexpected request ${request.path()}")
            }
        }

        val playback = service.resolveSharePlayback(ref)
        assertEquals("https://share-dl.drive.test/sf1", playback.url)
        assertEquals(listOf("https://share-dl.drive.test/sf2"), playback.subtitles.map { it.url })
        assertEquals(listOf("简体中文"), playback.subtitles.map { it.label })
        // 与转存后的直链一样: 带登录 Cookie, 按账号档位并发分块, 本机存下过的数据按分享里的文件认
        assertEquals("sid=p1", playback.headers[HttpHeaders.Cookie])
        assertEquals(TestDrive.DEFAULT_CONNECTIONS.toString(), playback.headers[PlaybackRequestHints.PARALLEL_RANGE_HEADER])
        assertEquals("${TestDrive.ID}-share:share1/sf1", playback.headers[PlaybackRequestHints.CACHE_KEY_HEADER])

        val download = requests.single { it.path() == "/api/share/download" }
        assertEquals("sid=p1", download.headers[HttpHeaders.Cookie])
        val body = download.jsonBody()
        assertEquals("st1", body.string("token"))
        assertEquals(listOf("sf1", "sf2"), body.strings("ids"))
        assertEquals(listOf("tok1", "tok2"), body.strings("fileTokens"))
        // 不转存, 也不碰自己网盘
        assertTrue(requests.none { it.path() == "/api/share/save" || it.path().startsWith("/api/files/") }, "${requests.map { it.path() }}")
    }

    @Test
    fun `share link refused falls back to saving`() = realTimeTest {
        val requests = mutableListOf<HttpRequestData>()
        val service = savingService(loggedIn, requests) { request ->
            when (request.path()) {
                "/api/share/download" -> reply("""{"code":23018,"message":"download file size limit"}""")
                else -> error("unexpected request ${request.path()}")
            }
        }

        assertEquals("https://dl.drive.test/new1", service.resolveSharePlayback(ref).url)
        val order = requests.map { it.path() }
        assertTrue(order.indexOf("/api/share/download") < order.indexOf("/api/share/save"), "$order")
    }

    @Test
    fun `share link rejected with an expired token gets a fresh token once`() = realTimeTest {
        val requests = mutableListOf<HttpRequestData>()
        var downloads = 0
        val service = service(loggedIn, requests) { request ->
            when (request.path()) {
                "/api/share/download" -> if (++downloads == 1) {
                    reply("""{"code":41004,"message":"token校验异常"}""", status = HttpStatusCode.Forbidden)
                } else {
                    reply(downloadJson(request.jsonBody().strings("ids")))
                }

                else -> error("unexpected request ${request.path()}")
            }
        }

        assertEquals("https://dl.drive.test/sf1", service.resolveSharePlayback(ref).url)
        assertEquals(2, downloads)
        assertEquals(2, requests.count { it.path() == "/api/share/open" })
        assertTrue(requests.none { it.path() == "/api/share/save" })
    }

    @Test
    fun `transcoded playback still saves the file`() = realTimeTest {
        val requests = mutableListOf<HttpRequestData>()
        val service = savingService(loggedIn.copy(playbackMode = CloudDrivePlaybackMode.TRANSCODED), requests) { request ->
            when (request.path()) {
                "/api/files/play" -> reply("""{"code":0,"data":{"streams":[{"url":"https://tc.drive.test/new1.m3u8","height":1080,"available":"true"}]}}""")
                else -> error("unexpected request ${request.path()}")
            }
        }

        assertEquals("https://tc.drive.test/new1.m3u8", service.resolveSharePlayback(ref).url)
        assertTrue(requests.none { it.path() == "/api/share/download" })
    }
}
