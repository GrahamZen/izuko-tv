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
import me.him188.ani.app.platform.PlaybackRequestHints
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.source.MatchKind
import me.him188.ani.datasources.api.source.MediaFetchRequest
import me.him188.ani.datasources.api.topic.EpisodeRange
import me.him188.ani.utils.platform.currentTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
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
        // 原文件直链让播放器多连接分块取; 没记下会员类型的按非会员
        assertEquals(
            QuarkDriveService.NON_MEMBER_PARALLEL_CONNECTIONS.toString(),
            playback.headers[PlaybackRequestHints.PARALLEL_RANGE_HEADER],
        )
    }

    @Test
    fun `members use fewer parallel connections than non-members`() = runTest {
        for ((memberType, expected) in listOf(
            "SUPER_VIP" to QuarkDriveService.PARALLEL_CONNECTIONS,
            "EXP_SVIP" to QuarkDriveService.PARALLEL_CONNECTIONS,
            "NORMAL" to QuarkDriveService.NON_MEMBER_PARALLEL_CONNECTIONS,
            // 没核实过下载速度的档位按非会员
            "MINI_VIP" to QuarkDriveService.NON_MEMBER_PARALLEL_CONNECTIONS,
            "VIP" to QuarkDriveService.NON_MEMBER_PARALLEL_CONNECTIONS,
        )) {
            val (service, _) = service(loggedIn.copy(memberType = memberType)) {
                reply("""{"status":200,"code":0,"data":[{"fid":"f1","download_url":"https://dl-pc-zb.drive.quark.cn/x?auth_key=1"}]}""")
            }
            val playback = service.resolvePlayback("f1")
            assertEquals(expected.toString(), playback.headers[PlaybackRequestHints.PARALLEL_RANGE_HEADER], memberType)
        }
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
        val playback = service.resolvePlayback("f1")
        assertEquals("https://v/low", playback.url)
        // 转码流是一段段小分片, 不用分块
        assertNull(playback.headers[PlaybackRequestHints.PARALLEL_RANGE_HEADER])
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

        val before = currentTimeMillis()
        val states = service.qrLogin(pollInterval = 1.seconds, timeout = 5.minutes).toList()
        assertIs<QuarkQrLoginState.Loading>(states[0])
        val waiting = assertIs<QuarkQrLoginState.WaitingForScan>(states[1])
        assertTrue(waiting.qrContent.startsWith("https://su.quark.cn/4_eMHBJ?token=tok&"))
        // 过期时刻 = 出码那一刻 + 等待上限, 界面按它倒数
        assertTrue(waiting.expiresAtMillis in before + 5.minutes.inWholeMilliseconds..currentTimeMillis() + 5.minutes.inWholeMilliseconds)
        // 手机上确认之后、换到 Cookie 之前先报「已确认」, 界面收起二维码
        assertEquals(QuarkQrLoginState.Confirmed, states[2])
        assertEquals(QuarkQrLoginState.Success("浅眠一梦"), states[3])

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
        // 没确认过就不报「已确认」
        assertTrue(QuarkQrLoginState.Confirmed !in states)
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
    fun `media source adds the folders and files picked for the subject`() = runTest {
        val (service, settings) = service { request ->
            when (request.url.encodedPath) {
                // 按名字什么都搜不到
                "/1/clouddrive/file/search" -> reply("""{"status":200,"code":0,"data":{"list":[]},"metadata":{"_total":0}}""")
                "/1/clouddrive/file/sort" -> reply(
                    """{"status":200,"code":0,"data":{"list":[
                        {"fid":"e1","file_name":"Heaven's Lost Property.2009.S02E01.mkv","pdir_fid":"pf","size":2569000000,"obj_category":"video"},
                        {"fid":"e2","file_name":"Heaven's Lost Property.2009.S02E02.mkv","pdir_fid":"pf","size":2641000000,"obj_category":"video"}
                    ]},"metadata":{"_total":2}}""",
                )

                else -> error("unexpected ${request.url}")
            }
        }
        // 条目名不写季, 文件写着 S02: 用户指定了文件夹就不再按季过滤
        service.pickFolder(7150, QuarkFile(fid = "pf", fileName = "天降之物 f", dir = true))
        service.pickFile(7150, QuarkFile(fid = "x9", fileName = "番外.mp4", parentFid = "p0", size = 300_000_000, category = "video"), EpisodeSort(3))
        assertEquals(listOf("pf"), settings.state.value.subjectPicks.getValue(7150).folders.map { it.fid })

        val request = MediaFetchRequest(
            subjectId = "7150",
            episodeId = "1",
            subjectNames = listOf("天降之物f"),
            episodeSort = EpisodeSort(1),
            episodeName = "",
        )
        val matches = QuarkMediaSource(service).fetch(request).results.toList()
        assertEquals(
            listOf("quark-drive.x9@03", "quark-drive.e1@01", "quark-drive.e2@02"),
            matches.map { "${it.media.mediaId}@${it.media.episodeRange?.knownSorts?.single()}" },
        )
        assertTrue(matches.all { it.kind == MatchKind.EXACT })

        // 忘掉文件夹之后只剩单独指定的文件; 两个都忘掉就整条去掉
        service.forgetPick(7150, "pf")
        assertEquals(listOf("quark-drive.x9"), QuarkMediaSource(service).fetch(request).results.toList().map { it.media.mediaId })
        service.forgetPick(7150, "x9")
        assertEquals(emptyMap(), settings.state.value.subjectPicks)
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
