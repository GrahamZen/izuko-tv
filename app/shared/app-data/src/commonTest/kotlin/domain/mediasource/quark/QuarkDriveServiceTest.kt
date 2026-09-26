/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.quark

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import me.him188.ani.app.data.models.preference.QuarkConfig
import me.him188.ani.app.data.models.preference.QuarkPlaybackMode
import me.him188.ani.app.data.repository.user.Settings
import me.him188.ani.app.domain.media.resolver.QuarkMediaResolver
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.source.MediaFetchRequest
import me.him188.ani.datasources.api.topic.EpisodeRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class QuarkDriveServiceTest {
    private class MemorySettings<T>(initial: T) : Settings<T> {
        val state = MutableStateFlow(initial)
        override val flow: Flow<T> get() = state
        override suspend fun set(value: T) {
            state.value = value
        }
    }

    private val loggedIn = QuarkConfig(cookie = "__pus=p1; __puus=u1; __uid=42")

    private fun MockRequestHandleScope.reply(
        body: String,
        setCookies: List<String> = emptyList(),
        status: HttpStatusCode = HttpStatusCode.OK,
    ): HttpResponseData = respond(
        body,
        status,
        headersOf(
            HttpHeaders.ContentType to listOf("application/json"),
            HttpHeaders.SetCookie to setCookies,
        ),
    )

    private fun service(
        config: QuarkConfig = loggedIn,
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): Pair<QuarkDriveService, MemorySettings<QuarkConfig>> {
        val settings = MemorySettings(config)
        val client = HttpClient(MockEngine { request -> handler(request) }) { expectSuccess = false }
        return QuarkDriveService(settings, client) to settings
    }

    @Test
    fun `search sends cookie and stores rotated cookies`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val (service, settings) = service { request ->
            requests += request
            reply(
                """{"status":200,"code":0,"data":{"list":[{"fid":"f1","file_name":"01.mp4","pdir_fid":"d1","dir":false,"size":123,"obj_category":"video"}]},"metadata":{"_total":1}}""",
                listOf("__puus=u2; Path=/; Domain=.quark.cn; HttpOnly"),
            )
        }
        val files = service.browser.search("葬送的芙莉莲")
        assertEquals(listOf("f1"), files.map { it.fid })
        assertTrue(files.single().isVideo)

        val request = requests.single()
        assertEquals("drive-pc.quark.cn", request.url.host)
        assertEquals("/1/clouddrive/file/search", request.url.encodedPath)
        assertEquals("葬送的芙莉莲", request.url.parameters["q"])
        assertEquals("__pus=p1; __puus=u1; __uid=42", request.headers[HttpHeaders.Cookie])
        assertEquals(QuarkApi.USER_AGENT, request.headers[HttpHeaders.UserAgent])

        assertEquals("__pus=p1; __puus=u2; __uid=42", settings.state.value.cookie)
    }

    @Test
    fun `switches host when one is unreachable and remembers the working one`() = runTest {
        val hosts = mutableListOf<String>()
        val (service, _) = service { request ->
            hosts += request.url.host
            if (request.url.host == "drive-pc.quark.cn") throw IOException("connect timed out")
            reply("""{"status":200,"code":0,"data":{"list":[]},"metadata":{"_total":0}}""")
        }
        service.browser.search("a")
        service.browser.search("b")
        assertEquals(listOf("drive-pc.quark.cn", "drive-m.quark.cn", "drive-m.quark.cn"), hosts)
    }

    @Test
    fun `unauthorized response means login required`() = runTest {
        val (service, _) = service {
            reply("""{"status":401,"code":31001,"message":"require login [guest]"}""", status = HttpStatusCode.Unauthorized)
        }
        assertFailsWith<QuarkAuthException> { service.browser.search("a") }
    }

    @Test
    fun `not logged in fails before sending requests`() = runTest {
        var sent = 0
        val (service, _) = service(QuarkConfig.Default) {
            sent++
            reply("{}")
        }
        assertFailsWith<QuarkAuthException> { service.resolvePlayback("f1") }
        assertEquals(0, sent)
    }

    @Test
    fun `original playback uses download url with cookie headers`() = runTest {
        var body: String? = null
        val (service, _) = service { request ->
            assertEquals("/1/clouddrive/file/download", request.url.encodedPath)
            body = (request.body as TextContent).text
            reply("""{"status":200,"code":0,"data":[{"fid":"f1","download_url":"https://dl-pc-zb.drive.quark.cn/x?auth_key=1"}]}""")
        }
        val playback = service.resolvePlayback("f1")
        assertEquals("""{"fids":["f1"]}""", body)
        assertEquals("https://dl-pc-zb.drive.quark.cn/x?auth_key=1", playback.url)
        assertEquals("__pus=p1; __puus=u1; __uid=42", playback.headers[HttpHeaders.Cookie])
        assertEquals(QuarkApi.REFERER, playback.headers[HttpHeaders.Referrer])
    }

    @Test
    fun `transcoded playback picks the best accessible resolution`() = runTest {
        val (service, _) = service(loggedIn.copy(playbackMode = QuarkPlaybackMode.TRANSCODED)) { request ->
            assertEquals("/1/clouddrive/file/v2/play", request.url.encodedPath)
            reply(
                """{"status":200,"code":0,"data":{"video_list":[
                    {"resolution":"super","accessable":false,"video_info":{"url":"https://v/super","height":1080}},
                    {"resolution":"high","accessable":false,"video_info":{"url":"https://v/high","height":540}},
                    {"resolution":"low","accessable":true,"video_info":{"url":"https://v/low","height":270}}
                ]}}""",
            )
        }
        assertEquals("https://v/low", service.resolvePlayback("f1").url)
    }

    @Test
    fun `qr login saves cookies from every step`() = runTest {
        var polls = 0
        val (service, settings) = service(QuarkConfig.Default) { request ->
            val path = request.url.encodedPath
            when {
                path.endsWith("getTokenForQrcodeLogin") ->
                    reply(
                        """{"status":2000000,"message":"ok","data":{"members":{"token":"tok"}}}""",
                        listOf("_UP_D_=d1; Path=/"),
                    )

                path.endsWith("getServiceTicketByQrcodeToken") -> {
                    assertEquals("tok", request.url.parameters["token"])
                    if (polls++ == 0) {
                        reply("""{"status":50004001,"message":"Query result is empty"}""")
                    } else {
                        reply("""{"status":2000000,"message":"ok","data":{"members":{"service_ticket":"st1"}}}""")
                    }
                }

                path == "/account/info" && request.url.parameters["st"] == "st1" ->
                    reply(
                        """{"success":true,"code":"OK","data":{"nickname":"浅眠一梦"}}""",
                        listOf("__pus=p9; Path=/; Domain=.quark.cn", "__uid=7; Path=/"),
                    )

                path == "/account/info" -> reply("""{"success":true,"code":"OK","data":{"nickname":"浅眠一梦"}}""")
                path.endsWith("/member") -> reply("""{"status":200,"code":0,"data":{"member_type":"NORMAL"}}""")
                else -> error("unexpected request $path")
            }
        }

        val states = service.qrLogin(pollInterval = 1.seconds).toList()
        assertIs<QuarkQrLoginState.Loading>(states[0])
        val waiting = assertIs<QuarkQrLoginState.WaitingForScan>(states[1])
        assertTrue(waiting.qrContent.startsWith("https://su.quark.cn/4_eMHBJ?token=tok&"))
        assertEquals(QuarkQrLoginState.Success("浅眠一梦"), states[2])

        val config = settings.state.value
        assertEquals("_UP_D_=d1; __pus=p9; __uid=7", config.cookie)
        assertEquals("浅眠一梦", config.nickname)
        assertEquals("NORMAL", config.memberType)
    }

    @Test
    fun `expired qr code ends the flow`() = runTest {
        val (service, settings) = service(QuarkConfig.Default) { request ->
            if (request.url.encodedPath.endsWith("getTokenForQrcodeLogin")) {
                reply("""{"status":2000000,"message":"ok","data":{"members":{"token":"tok"}}}""")
            } else {
                reply("""{"status":50004002,"message":"expired"}""")
            }
        }
        val states = service.qrLogin(pollInterval = 1.seconds).toList()
        assertEquals(QuarkQrLoginState.Expired, states.last())
        assertEquals(QuarkConfig.Default, settings.state.value)
    }

    @Test
    fun `logout keeps playback mode`() = runTest {
        val (service, settings) = service(loggedIn.copy(playbackMode = QuarkPlaybackMode.TRANSCODED)) { reply("{}") }
        service.logout()
        assertEquals(QuarkConfig(playbackMode = QuarkPlaybackMode.TRANSCODED), settings.state.value)
    }

    @Test
    fun `media source turns drive files into playable media`() = runTest {
        val (service, _) = service { request ->
            when (request.url.encodedPath) {
                "/1/clouddrive/file/search" -> reply(
                    if (request.url.parameters["q"] == "Smoking Behind the Supermarket with You") {
                        """{"status":200,"code":0,"data":{"list":[{"fid":"d1","file_name":"Smoking.Behind.the.Supermarket.With.You.S01","dir":true}]},"metadata":{"_total":1}}"""
                    } else {
                        """{"status":200,"code":0,"data":{"list":[]},"metadata":{"_total":0}}"""
                    },
                )

                "/1/clouddrive/file/sort" -> reply(
                    """{"status":200,"code":0,"data":{"list":[
                        {"fid":"f3","file_name":"Smoking.Behind.the.Supermarket.With.You.S01E03.mp4","pdir_fid":"d1","size":247580374,"obj_category":"video","updated_at":1790437896202,"video_height":1080}
                    ]},"metadata":{"_total":1}}""",
                )

                else -> error("unexpected ${request.url}")
            }
        }
        val request = MediaFetchRequest(
            subjectId = "571784",
            episodeId = "1",
            subjectNames = listOf("在超市后门吸烟的二人", "Smoking Behind the Supermarket with You"),
            episodeSort = EpisodeSort(3),
            episodeName = "",
        )
        val media = QuarkMediaSource(service).fetch(request).results.toList().single().media
        assertEquals("quark-drive.f3", media.mediaId)
        assertEquals(EpisodeRange.single(EpisodeSort(3)), media.episodeRange)
        assertEquals("在超市后门吸烟的二人", media.properties.subjectName)
        assertEquals("1080P", media.properties.resolution)
        assertEquals(
            "Smoking.Behind.the.Supermarket.With.You.S01 / Smoking.Behind.the.Supermarket.With.You.S01E03.mp4",
            media.originalTitle,
        )
        assertEquals("f3", QuarkMediaSource.fileIdOf(media.download.uri))
        assertTrue(QuarkMediaResolver(service).supports(media))
    }

    @Test
    fun `merge cookies keeps order and replaces values`() {
        assertEquals(
            "a=1; __puus=new; b=2; c=3",
            QuarkDriveService.mergeCookies("a=1; __puus=old; b=2", mapOf("__puus" to "new", "c" to "3")),
        )
        assertEquals(
            mapOf("__pus" to "x=y", "b" to ""),
            QuarkApi.parseSetCookieHeaders(listOf("__pus=x=y; Path=/", "b=; Max-Age=0", "=bad")),
        )
    }
}
