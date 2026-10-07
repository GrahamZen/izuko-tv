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
import io.ktor.http.HttpStatusCode
import me.him188.ani.app.data.models.preference.CloudDriveAccount
import me.him188.ani.app.data.models.preference.CloudDriveAccounts
import me.him188.ani.app.data.models.preference.DrivePickedSubtitle
import me.him188.ani.datasources.api.EpisodeSort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 播放时带上视频旁边的外挂字幕: 自己网盘里的到所在文件夹找, 分享里的随视频一起转存.
 */
class CloudDriveSubtitlePlaybackTest {
    private fun HttpRequestData.path() = url.encodedPath

    private fun HttpRequestData.ids() = jsonBody().strings("ids")

    private fun subtitle(id: String, name: String, parent: String, size: Long = 1, token: String = "") =
        DriveFile(id, fileName = name, parentFid = parent, size = size, shareToken = token)

    private fun movie(id: String, name: String, parent: String, size: Long = 1, token: String = "") =
        DriveFile(id, fileName = name, parentFid = parent, size = size, isVideo = true, shareToken = token)

    private fun service(
        account: CloudDriveAccount,
        requests: MutableList<HttpRequestData>,
        settings: MemorySettings<CloudDriveAccounts> = accountsOf(account),
        handler: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ) = testDriveService(settings) { request ->
        requests += request
        handler(request)
    }

    private val loggedIn = CloudDriveAccount(cookie = "sid=p1")

    @Test
    fun `own drive video gets subtitles from its folder`() = realTimeTest {
        val requests = mutableListOf<HttpRequestData>()
        val v1 = movie("v1", "Show - 01.mkv", "d1")
        val service = service(loggedIn, requests) { request ->
            when (request.path()) {
                "/api/files/download" -> reply(downloadJson(request.ids(), mapOf("v1" to v1)))
                "/api/files/list" -> {
                    assertEquals("d1", request.url.parameters["parent"])
                    reply(
                        listJson(
                            v1,
                            subtitle("s2", "Show - 01.tc.ass", "d1"),
                            subtitle("s1", "Show - 01.sc.ass", "d1"),
                            movie("v2", "Show - 02.mkv", "d1"),
                            subtitle("s3", "Show - 02.sc.ass", "d1"),
                        ),
                    )
                }

                else -> error("unexpected request ${request.path()}")
            }
        }

        val playback = service.resolvePlayback("v1")
        assertEquals("https://dl.drive.test/v1", playback.url)
        assertEquals(listOf("简体中文", "繁体中文"), playback.subtitles.map { it.label })
        assertEquals(listOf("https://dl.drive.test/s1", "https://dl.drive.test/s2"), playback.subtitles.map { it.url })
        assertEquals(listOf("text/x-ssa", "text/x-ssa"), playback.subtitles.map { it.mimeType })
        // 视频直链, 列文件夹, 两条字幕的直链一次取
        assertEquals(listOf("/api/files/download", "/api/files/list", "/api/files/download"), requests.map { it.path() })
        assertEquals(listOf("s1", "s2"), requests.last().ids())
    }

    @Test
    fun `picked subtitles come first and are not repeated`() = realTimeTest {
        val requests = mutableListOf<HttpRequestData>()
        val account = loggedIn.copy(
            pickedSubtitles = mapOf(
                "file:v1" to listOf(DrivePickedSubtitle("p1", "别的字幕组 01.chs.ass"), DrivePickedSubtitle("s2", "Show - 01.tc.ass")),
            ),
        )
        val v1 = movie("v1", "Show - 01.mkv", "d1")
        val service = service(account, requests) { request ->
            when (request.path()) {
                "/api/files/download" -> reply(downloadJson(request.ids(), mapOf("v1" to v1)))
                "/api/files/list" -> reply(
                    listJson(
                        v1,
                        subtitle("s1", "Show - 01.sc.ass", "d1"),
                        subtitle("s2", "Show - 01.tc.ass", "d1"),
                    ),
                )

                else -> error("unexpected request ${request.path()}")
            }
        }
        val playback = service.resolvePlayback("v1")
        // 手动挂的两条在前 (其中 s2 也是自动找到的, 只出现一次), 重名的加序号
        assertEquals(listOf("https://dl.drive.test/p1", "https://dl.drive.test/s2", "https://dl.drive.test/s1"), playback.subtitles.map { it.url })
        assertEquals(listOf("简体中文", "繁体中文", "简体中文 2"), playback.subtitles.map { it.label })
    }

    @Test
    fun `picking and forgetting subtitles`() = realTimeTest {
        val settings = accountsOf(loggedIn)
        val service = service(loggedIn, mutableListOf(), settings) { error("no requests expected") }
        val key = assertNotNull(
            service.subtitleKeyOf(CloudDriveMediaSource.mediaFor(service, DriveFile("v1", fileName = "Show - 01.mkv"), EpisodeSort(1), null)),
        )
        val shareMedia = DriveShareMatch(
            FoundShare("share1", "", "Show"),
            DriveFile("v1", fileName = "Show - 01.mkv", shareToken = "t"),
            emptyList(),
            EpisodeSort(1),
        ).toShareMedia(service.placeholders, "share-search", "Show", null)
        // 分享里的视频按分享记, 不与自己网盘里 id 相同的文件混在一起
        val shareKey = service.subtitleKeyOf(shareMedia)
        assertTrue(shareKey != null && shareKey != key)

        service.pickSubtitle(key, DriveFile("a", fileName = "a.ass"))
        service.pickSubtitle(key, DriveFile("b", fileName = "b.srt"))
        service.pickSubtitle(key, DriveFile("a", fileName = "a.ass"))
        // 后挂的排前面, 同一个文件只记一次
        assertEquals(listOf("a", "b"), service.pickedSubtitlesOf(key).map { it.fid })
        service.forgetSubtitle(key, "a")
        service.forgetSubtitle(key, "b")
        assertTrue(settings.account.pickedSubtitles.isEmpty())
    }

    @Test
    fun `subtitles look up failure does not stop playback`() = realTimeTest {
        val requests = mutableListOf<HttpRequestData>()
        val service = service(loggedIn, requests) { request ->
            when (request.path()) {
                "/api/files/download" -> reply(downloadJson(request.ids(), mapOf("v1" to movie("v1", "Show - 01.mkv", "d1"))))
                "/api/files/list" -> respond("oops", HttpStatusCode.InternalServerError)
                else -> error("unexpected request ${request.path()}")
            }
        }
        val playback = service.resolvePlayback("v1")
        assertEquals("https://dl.drive.test/v1", playback.url)
        assertTrue(playback.subtitles.isEmpty())
    }

    @Test
    fun `share video is saved together with its subtitles`() = realTimeTest {
        val requests = mutableListOf<HttpRequestData>()
        var saved = false
        val service = service(loggedIn.copy(shareSaveFolderId = "save1"), requests) { request ->
            when (request.path()) {
                "/api/share/open" -> reply("""{"code":0,"data":{"token":"st1","title":"t"}}""")
                "/api/share/list" -> {
                    assertEquals("sd1", request.url.parameters["parent"])
                    reply(
                        listJson(
                            movie("sf1", "Show - 01.mkv", "sd1", size = 500, token = "tok1"),
                            subtitle("sf2", "Show - 01.sc.ass", "sd1", size = 10, token = "tok2"),
                            movie("sf3", "Show - 02.mkv", "sd1", size = 500, token = "tok3"),
                        ),
                    )
                }

                "/api/files/list" -> reply(
                    if (!saved) {
                        listJson()
                    } else {
                        listJson(movie("new1", "Show - 01.mkv", "save1", size = 500), subtitle("new2", "Show - 01.sc.ass", "save1", size = 10))
                    },
                )

                "/api/share/save" -> {
                    saved = true
                    reply("""{"code":0,"data":{"task":"task1"}}""")
                }

                "/api/tasks/task1" -> reply("""{"code":0,"data":{"state":"done","result":{"ids":["new1","new2"]}}}""")
                "/api/files/download" -> reply(downloadJson(request.ids(), mapOf("new1" to movie("new1", "Show - 01.mkv", "save1"))))
                else -> error("unexpected request ${request.path()}")
            }
        }
        val ref = DriveShareFileRef("share1", "", "sf1", "tok1", "Show - 01.mkv", 500, folderId = "sd1")

        val playback = service.resolveSharePlayback(ref)
        assertEquals("https://dl.drive.test/new1", playback.url)
        assertEquals(listOf("简体中文"), playback.subtitles.map { it.label })
        assertEquals(listOf("https://dl.drive.test/new2"), playback.subtitles.map { it.url })
        val save = requests.single { it.path() == "/api/share/save" }.jsonBody()
        assertEquals(listOf("sf1", "sf2"), save.strings("ids"))
        assertEquals(listOf("tok1", "tok2"), save.strings("fileTokens"))

        // 再播一次: 不再转存, 也不用到转存文件夹里找
        requests.clear()
        val again = service.resolveSharePlayback(ref)
        assertEquals(listOf("https://dl.drive.test/new2"), again.subtitles.map { it.url })
        assertEquals(listOf("/api/share/list", "/api/files/download", "/api/files/download"), requests.map { it.path() })
    }

    @Test
    fun `share without a known folder uses subtitles already in the save folder`() = realTimeTest {
        val requests = mutableListOf<HttpRequestData>()
        val service = service(loggedIn.copy(shareSaveFolderId = "save1"), requests) { request ->
            when (request.path()) {
                "/api/files/list" -> reply(
                    listJson(
                        movie("c1", "Show - 01.mkv", "save1", size = 500),
                        subtitle("c2", "Show - 01.sc.ass", "save1", size = 10),
                        movie("c3", "Other - 01.mkv", "save1", size = 700),
                        subtitle("c4", "Other - 01.sc.ass", "save1", size = 10),
                    ),
                )

                "/api/files/download" -> reply(downloadJson(request.ids(), mapOf("c1" to movie("c1", "Show - 01.mkv", "save1"))))
                else -> error("unexpected request ${request.path()}")
            }
        }
        val playback = service.resolveSharePlayback(DriveShareFileRef("share1", "", "sf1", "tok1", "Show - 01.mkv", 500))
        assertEquals("https://dl.drive.test/c1", playback.url)
        assertEquals(listOf("https://dl.drive.test/c2"), playback.subtitles.map { it.url })
    }
}
