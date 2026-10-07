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
import io.ktor.http.content.TextContent
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import me.him188.ani.app.data.models.preference.CloudDriveAccount
import me.him188.ani.app.data.models.preference.CloudDriveAccounts
import me.him188.ani.app.data.models.preference.CloudDrivePlaybackMode
import me.him188.ani.app.data.models.preference.DriveRememberedFolder
import me.him188.ani.app.platform.PlaybackRequestHints
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.source.MatchKind
import me.him188.ani.datasources.api.source.MediaFetchRequest
import me.him188.ani.datasources.api.topic.EpisodeRange
import me.him188.ani.utils.platform.currentTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * 网盘服务按协议访问网盘: 请求怎么发、Cookie 怎么轮换、登录、播放地址, 以及自己网盘的数据源.
 */
class CloudDriveServiceTest {
    private val protocol = TestDrive.protocol

    private fun service(
        account: CloudDriveAccount = TestDrive.loggedIn,
        protocol: CloudDriveProtocol = this.protocol,
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): Pair<CloudDriveService, MemorySettings<CloudDriveAccounts>> {
        val settings = accountsOf(account)
        return testDriveService(settings, protocol, handler) to settings
    }

    private val downloadF1 = downloadJson(listOf("f1"))

    @Test
    fun `search sends cookie and common parameters and stores rotated cookies`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val (service, settings) = service { request ->
            requests += request
            reply(
                """{"code":0,"data":{"items":[{"id":"f1","name":"01.mp4","parent":"d1","isDir":false,"size":123,"kind":"video"}],"total":1}}""",
                listOf("sig=u2; Path=/; Domain=.drive.test; HttpOnly", "tracker=x; Path=/"),
            )
        }
        val files = service.browser.search("葬送的芙莉莲")
        assertEquals(listOf("f1"), files.map { it.fid })
        assertTrue(files.single().isVideo)
        assertEquals("d1", files.single().parentFid)

        val request = requests.single()
        assertEquals("api-a.drive.test", request.url.host)
        assertEquals("/api/files/search", request.url.encodedPath)
        assertEquals("葬送的芙莉莲", request.url.parameters["q"])
        assertEquals("1", request.url.parameters["page"])
        assertEquals("50", request.url.parameters["size"])
        assertEquals("tv", request.url.parameters["client"])
        assertEquals("sid=p1; sig=u1; uid=42", request.headers[HttpHeaders.Cookie])
        assertEquals(TestDrive.USER_AGENT, request.headers[HttpHeaders.UserAgent])
        assertEquals(TestDrive.REFERER, request.headers[HttpHeaders.Referrer])

        // 只合并协议声明会轮换的 Cookie
        assertEquals("sid=p1; sig=u2; uid=42", settings.account.cookie)
    }

    @Test
    fun `switches host when one is unreachable and remembers the working one`() = runTest {
        val hosts = mutableListOf<String>()
        val (service, _) = service { request ->
            hosts += request.url.host
            if (request.url.host == "api-a.drive.test") throw IOException("connect timed out")
            reply(listJson())
        }
        service.browser.search("a")
        service.browser.search("b")
        assertEquals(listOf("api-a.drive.test", "api-b.drive.test", "api-b.drive.test"), hosts)
    }

    @Test
    fun `fails with the last connection error when every host is unreachable`() = runTest {
        val hosts = mutableListOf<String>()
        val (service, _) = service { request ->
            hosts += request.url.host
            throw IOException("connect timed out")
        }
        assertFailsWith<IOException> { service.browser.search("a") }
        assertEquals(protocol.http.hosts, hosts)
    }

    @Test
    fun `unauthorized responses mean login required`() = runTest {
        val (byHttp, _) = service {
            reply("""{"code":1,"message":"login required"}""", status = HttpStatusCode.Unauthorized)
        }
        assertFailsWith<CloudDriveAuthException> { byHttp.browser.search("a") }

        // 外壳里的状态说没登录, HTTP 照样是 200
        val (byEnvelope, _) = service { reply("""{"code":1,"status":"unauthenticated","message":"guest"}""") }
        assertFailsWith<CloudDriveAuthException> { byEnvelope.browser.search("a") }

        // 别的错误码只是出错, 不算登录失效
        val (byCode, _) = service { reply("""{"code":9,"status":"error","message":"busy"}""") }
        val error = assertFailsWith<CloudDriveApiException> { byCode.browser.search("a") }
        assertFalse(error is CloudDriveAuthException)
        assertEquals("9", error.code)
        assertFalse(error.isCapacityLimit)
    }

    @Test
    fun `not logged in fails before sending requests`() = runTest {
        var sent = 0
        val (service, _) = service(CloudDriveAccount.Default) {
            sent++
            reply("{}")
        }
        assertFailsWith<CloudDriveAuthException> { service.resolvePlayback("f1") }
        assertEquals(0, sent)
    }

    @Test
    fun `original playback uses download url with the playback headers`() = runTest {
        var body: String? = null
        val (service, _) = service { request ->
            assertEquals("/api/files/download", request.url.encodedPath)
            body = (request.body as TextContent).text
            reply(downloadF1)
        }
        val playback = service.resolvePlayback("f1")
        // 整个值是一个变量时换成 JSON 值: 文件 id 列表是数组
        assertEquals("""{"ids":["f1"]}""", body)
        assertEquals("https://dl.drive.test/f1", playback.url)
        assertEquals("sid=p1; sig=u1; uid=42", playback.headers[HttpHeaders.Cookie])
        assertEquals(TestDrive.REFERER, playback.headers[HttpHeaders.Referrer])
        assertEquals(TestDrive.USER_AGENT, playback.headers[HttpHeaders.UserAgent])
        // 原文件直链让播放器多连接分块取; 不知道档位时用协议的默认值
        assertEquals(TestDrive.DEFAULT_CONNECTIONS.toString(), playback.headers[PlaybackRequestHints.PARALLEL_RANGE_HEADER])
        // 直链每次都不一样, 播放器按文件 id 把下过的数据存在本机
        assertEquals("testdrive:f1", playback.headers[PlaybackRequestHints.CACHE_KEY_HEADER])
    }

    @Test
    fun `account tiers decide the parallel connections`() = runTest {
        for ((tier, expected) in listOf(
            "PRO" to TestDrive.PRO_CONNECTIONS,
            "PRO_TRIAL" to TestDrive.PRO_CONNECTIONS,
            // 对上了档位但档位没写连接数: 用协议的默认值
            "BASIC" to TestDrive.DEFAULT_CONNECTIONS,
            "SUPER_PRO" to TestDrive.DEFAULT_CONNECTIONS,
            "" to TestDrive.DEFAULT_CONNECTIONS,
        )) {
            val (service, _) = service(TestDrive.loggedIn.copy(tier = tier)) { reply(downloadF1) }
            val playback = service.resolvePlayback("f1")
            assertEquals(expected.toString(), playback.headers[PlaybackRequestHints.PARALLEL_RANGE_HEADER], tier)
        }

        // 单连接的网盘不带分块提示, 本机缓存照常
        val single = protocol.copy(playback = protocol.playback.copy(parallelConnections = 1), tiers = emptyList())
        val (service, _) = service(protocol = single) { reply(downloadF1) }
        val playback = service.resolvePlayback("f1")
        assertNull(playback.headers[PlaybackRequestHints.PARALLEL_RANGE_HEADER])
        assertEquals("testdrive:f1", playback.headers[PlaybackRequestHints.CACHE_KEY_HEADER])
    }

    @Test
    fun `transcoded playback picks the best accessible resolution`() = runTest {
        var body: String? = null
        val (service, _) = service(TestDrive.loggedIn.copy(playbackMode = CloudDrivePlaybackMode.TRANSCODED)) { request ->
            assertEquals("/api/files/play", request.url.encodedPath)
            body = (request.body as TextContent).text
            reply(
                """{"code":0,"data":{"streams":[
                    {"quality":"high","available":false,"url":"https://v.drive.test/high","height":1080},
                    {"quality":"medium","available":false,"url":"https://v.drive.test/medium","height":540},
                    {"quality":"low","available":true,"url":"https://v.drive.test/low","height":270},
                    {"quality":"tiny","available":true,"url":"https://v.drive.test/tiny","height":144}
                ]}}""",
            )
        }
        val playback = service.resolvePlayback("f1")
        assertEquals("""{"id":"f1"}""", body)
        assertEquals("https://v.drive.test/low", playback.url)
        // 转码流是一段段小分片, 不用分块, 也不存本机
        assertNull(playback.headers[PlaybackRequestHints.PARALLEL_RANGE_HEADER])
        assertNull(playback.headers[PlaybackRequestHints.CACHE_KEY_HEADER])
        assertEquals("sid=p1; sig=u1; uid=42", playback.headers[HttpHeaders.Cookie])
    }

    @Test
    fun `transcoded mode plays the original file when the protocol has no transcoding`() = runTest {
        val noTranscoding = protocol.copy(api = protocol.api.copy(transcoded = null))
        val (service, _) = service(TestDrive.loggedIn.copy(playbackMode = CloudDrivePlaybackMode.TRANSCODED), noTranscoding) { request ->
            assertEquals("/api/files/download", request.url.encodedPath)
            reply(downloadF1)
        }
        assertEquals("https://dl.drive.test/f1", service.resolvePlayback("f1").url)
    }

    @Test
    fun `qr login saves cookies from every step`() = runTest {
        var polls = 0
        val (service, settings) = service(CloudDriveAccount.Default) { request ->
            when (request.url.encodedPath) {
                "/qr/start" -> {
                    // 每次一个新的请求 id; 扫码接口不带公共参数
                    assertTrue(!request.url.parameters["request"].isNullOrBlank())
                    assertNull(request.url.parameters["client"])
                    reply("""{"state":"ok","data":{"token":"tok"}}""", listOf("device=d1; Path=/"))
                }

                "/qr/poll" -> {
                    assertEquals("tok", request.url.parameters["token"])
                    if (polls++ == 0) {
                        reply("""{"state":"waiting"}""")
                    } else {
                        reply("""{"state":"confirmed","data":{"ticket":"st1"}}""")
                    }
                }

                "/qr/exchange" -> {
                    assertEquals("st1", request.url.parameters["ticket"])
                    reply(
                        """{"data":{"nickname":"浅眠一梦"}}""",
                        listOf("sid=p9; Path=/; Domain=.drive.test", "uid=7; Path=/"),
                    )
                }

                "/api/account/tier" -> {
                    assertEquals("device=d1; sid=p9; uid=7", request.headers[HttpHeaders.Cookie])
                    reply("""{"code":0,"data":{"tier":"BASIC"}}""")
                }

                "/api/account/profile" -> reply("""{"code":0,"data":{"nickname":"浅眠一梦"}}""")
                else -> error("unexpected request ${request.url}")
            }
        }

        val before = currentTimeMillis()
        val states = service.qrLogin().toList()
        assertIs<CloudDriveQrLoginState.Loading>(states[0])
        val waiting = assertIs<CloudDriveQrLoginState.WaitingForScan>(states[1])
        assertEquals("https://login.drive.test/qr?token=tok", waiting.qrContent)
        // 过期时刻 = 出码那一刻 + 协议给的等待上限, 界面按它倒数
        assertTrue(waiting.expiresAtMillis in before + 5.minutes.inWholeMilliseconds..currentTimeMillis() + 5.minutes.inWholeMilliseconds)
        // 手机上确认之后、换到 Cookie 之前先报「已确认」, 界面收起二维码
        assertEquals(CloudDriveQrLoginState.Confirmed, states[2])
        assertEquals(CloudDriveQrLoginState.Success("浅眠一梦"), states[3])

        val account = settings.account
        assertEquals("device=d1; sid=p9; uid=7", account.cookie)
        assertEquals("浅眠一梦", account.nickname)
        assertEquals("BASIC", account.tier)
    }

    @Test
    fun `expired qr code ends the flow`() = runTest {
        val (service, settings) = service(CloudDriveAccount.Default) { request ->
            if (request.url.encodedPath == "/qr/start") {
                reply("""{"state":"ok","data":{"token":"tok"}}""")
            } else {
                reply("""{"state":"expired"}""")
            }
        }
        val states = service.qrLogin().toList()
        assertEquals(CloudDriveQrLoginState.Expired, states.last())
        // 没确认过就不报「已确认」
        assertTrue(CloudDriveQrLoginState.Confirmed !in states)
        assertEquals(CloudDriveAccounts.Default, settings.state.value)
    }

    @Test
    fun `qr login fails when the token request is rejected`() = runTest {
        val (service, settings) = service(CloudDriveAccount.Default) { reply("""{"state":"denied","message":"too many requests"}""") }
        val states = service.qrLogin().toList()
        assertIs<CloudDriveQrLoginState.Failed>(states.last())
        assertEquals(CloudDriveAccounts.Default, settings.state.value)
    }

    @Test
    fun `cookie login is checked against the server before it is kept`() = runTest {
        var tierStatus = HttpStatusCode.OK
        var sent = 0
        val previous = CloudDriveAccount(playbackMode = CloudDrivePlaybackMode.TRANSCODED)
        val (service, settings) = service(previous) { request ->
            sent++
            when (request.url.encodedPath) {
                "/api/account/tier" -> reply("""{"code":0,"data":{"tier":"PRO"}}""", status = tierStatus)
                "/api/account/profile" -> reply("""{"code":0,"data":{"nickname":"浅眠一梦"}}""")
                else -> error("unexpected request ${request.url}")
            }
        }

        // 没有协议要求的登录 Cookie: 不发请求
        assertFailsWith<IllegalArgumentException> { service.loginWithCookie("uid=42") }
        assertEquals(0, sent)

        // 服务端不认: 恢复原样
        tierStatus = HttpStatusCode.Unauthorized
        assertFailsWith<CloudDriveAuthException> { service.loginWithCookie("sid=bad") }
        assertEquals(previous, settings.account)

        tierStatus = HttpStatusCode.OK
        val account = service.loginWithCookie("Cookie: sid=n1; sig=s1 ")
        assertEquals("sid=n1; sig=s1", account.cookie)
        assertEquals("PRO", account.tier)
        assertEquals("浅眠一梦", account.nickname)
        // 播放方式不随登录变
        assertEquals(CloudDrivePlaybackMode.TRANSCODED, account.playbackMode)
        assertEquals(account, settings.account)
    }

    @Test
    fun `logout keeps playback mode`() = runTest {
        val (service, settings) = service(TestDrive.loggedIn.copy(playbackMode = CloudDrivePlaybackMode.TRANSCODED)) { reply("{}") }
        service.logout()
        assertEquals(CloudDriveAccount(playbackMode = CloudDrivePlaybackMode.TRANSCODED), settings.account)
    }

    @Test
    fun `folders are listed page by page`() = runTest {
        val paged = protocol.copy(api = protocol.api.copy(listFolder = protocol.api.listFolder!!.copy(pageSize = 2)))
        val pages = mutableListOf<String>()
        val files = (1..5).map { DriveFile("f$it", fileName = "$it.mkv") }
        val (service, _) = service(protocol = paged) { request ->
            val folder = request.url.parameters["parent"].orEmpty()
            val page = request.url.parameters["page"]!!.toInt()
            pages += "$folder#$page"
            assertEquals("2", request.url.parameters["size"])
            val items = files.drop((page - 1) * 2).take(2)
            when (folder) {
                // 带总数: 取够了就停
                "a" -> reply(listJson(items, total = 4))
                // 不带总数: 取到不满一页为止
                else -> reply("""{"code":0,"data":{"items":[${items.joinToString(",") { it.toJson() }}]}}""")
            }
        }
        assertEquals(listOf("f1", "f2", "f3", "f4"), service.listForPicking("a").map { it.fid })
        assertEquals(listOf("f1", "f2", "f3", "f4", "f5"), service.listForPicking("b").map { it.fid })
        assertEquals(listOf("a#1", "a#2", "b#1", "b#2", "b#3"), pages)
    }

    @Test
    fun `deleting waits for the background task when asked to`() = runTest {
        var taskState = "done"
        val requests = mutableListOf<HttpRequestData>()
        val (service, _) = service { request ->
            requests += request
            when (request.url.encodedPath) {
                "/api/files/delete" -> reply("""{"code":0,"data":{"task":"t1"}}""")
                "/api/tasks/t1" -> {
                    val retry = request.url.parameters["retry"]!!.toInt()
                    when (taskState) {
                        "done" -> reply(if (retry == 0) """{"code":0,"data":{"state":"running"}}""" else """{"code":0,"data":{"state":"done"}}""")
                        else -> reply("""{"code":0,"data":{"state":"$taskState","error":"locked"}}""")
                    }
                }

                else -> error("unexpected request ${request.url}")
            }
        }

        service.api.deleteFiles(emptyList(), wait = true)
        assertEquals(emptyList(), requests)

        // 不等: 提交就返回
        service.api.deleteFiles(listOf("a", "b"))
        assertEquals(listOf("POST /api/files/delete"), requests.map { it.route() })
        assertEquals(listOf("a", "b"), requests.single().jsonBody().strings("ids"))

        requests.clear()
        service.api.deleteFiles(listOf("a"), wait = true)
        assertEquals(listOf("POST /api/files/delete", "GET /api/tasks/t1", "GET /api/tasks/t1"), requests.map { it.route() })
        assertEquals(listOf("0", "1"), requests.drop(1).map { it.url.parameters["retry"] })

        taskState = "failed"
        val failed = assertFailsWith<CloudDriveApiException> { service.api.deleteFiles(listOf("a"), wait = true) }
        assertTrue(failed.message!!.contains("locked"), failed.message)

        // 一直没完成: 查够次数就算超时
        taskState = "running"
        requests.clear()
        assertFailsWith<CloudDriveApiException> { service.api.deleteFiles(listOf("a"), wait = true) }
        assertEquals(protocol.api.task!!.maxPolls, requests.count { it.url.encodedPath == "/api/tasks/t1" })
    }

    @Test
    fun `media source turns drive files into playable media`() = runTest {
        val (service, _) = service { request ->
            when (request.url.encodedPath) {
                "/api/files/search" -> reply(
                    if (request.url.parameters["q"] == "Smoking Behind the Supermarket with You") {
                        listJson(dir("d1", "Smoking.Behind.the.Supermarket.With.You.S01", parent = TestDrive.ROOT))
                    } else {
                        listJson()
                    },
                )

                "/api/files/list" -> {
                    assertEquals("d1", request.url.parameters["parent"])
                    reply(
                        listJson(
                            DriveFile(
                                "f3",
                                fileName = "Smoking.Behind.the.Supermarket.With.You.S01E03.mp4",
                                parentFid = "d1",
                                size = 247580374,
                                updatedAt = 1790437896202,
                                videoHeight = 1080,
                                isVideo = true,
                            ),
                        ),
                    )
                }

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
        val source = CloudDriveMediaSource(service, CloudDriveArguments(protocol = protocol))
        assertEquals("testdrive-drive", source.mediaSourceId)
        assertEquals("测试网盘", source.info.displayName)
        val media = source.fetch(request).results.toList().single().media
        assertEquals("testdrive-drive.f3", media.mediaId)
        assertEquals("testdrive-drive", media.mediaSourceId)
        assertEquals(EpisodeRange.single(EpisodeSort(3)), media.episodeRange)
        assertEquals("在超市后门吸烟的二人", media.properties.subjectName)
        assertEquals("1080P", media.properties.resolution)
        assertEquals("测试网盘", media.properties.alliance)
        assertEquals(1790437896202, media.publishedTime)
        assertEquals(
            "Smoking.Behind.the.Supermarket.With.You.S01 / Smoking.Behind.the.Supermarket.With.You.S01E03.mp4",
            media.originalTitle,
        )
        assertEquals("https://www.drive.test/folder/d1", media.originalUrl)
        assertEquals("https://www.drive.test/file/f3", media.download.uri)
        assertEquals("f3", service.placeholders.fileIdOf(media.download.uri))
    }

    @Test
    fun `media source gives nothing when not logged in`() = runTest {
        var sent = 0
        val (service, _) = service(CloudDriveAccount.Default) {
            sent++
            reply(listJson())
        }
        val request = MediaFetchRequest(
            subjectId = "1",
            episodeId = "1",
            subjectNames = listOf("葬送的芙莉莲"),
            episodeSort = EpisodeSort(1),
            episodeName = "",
        )
        assertEquals(emptyList(), CloudDriveMediaSource(service, CloudDriveArguments(protocol = protocol)).fetch(request).results.toList())
        assertEquals(0, sent)
    }

    @Test
    fun `media source adds the folders and files picked for the subject`() = runTest {
        val (service, settings) = service { request ->
            when (request.url.encodedPath) {
                // 按名字什么都搜不到
                "/api/files/search" -> reply(listJson())
                "/api/files/list" -> reply(
                    listJson(
                        DriveFile("e1", fileName = "Heaven's Lost Property.2009.S02E01.mkv", parentFid = "pf", size = 2569000000, isVideo = true),
                        DriveFile("e2", fileName = "Heaven's Lost Property.2009.S02E02.mkv", parentFid = "pf", size = 2641000000, isVideo = true),
                    ),
                )

                else -> error("unexpected ${request.url}")
            }
        }
        // 条目名不写季, 文件写着 S02: 用户指定了文件夹就不再按季过滤
        service.pickFolder(7150, dir("pf", "天降之物 f"))
        service.pickFile(7150, DriveFile("x9", fileName = "番外.mp4", parentFid = "p0", size = 300_000_000, isVideo = true), EpisodeSort(3))
        assertEquals(listOf("pf"), settings.account.subjectPicks.getValue(7150).folders.map { it.fid })

        val request = MediaFetchRequest(
            subjectId = "7150",
            episodeId = "1",
            subjectNames = listOf("天降之物f"),
            episodeSort = EpisodeSort(1),
            episodeName = "",
        )
        val source = CloudDriveMediaSource(service, CloudDriveArguments(protocol = protocol))
        val matches = source.fetch(request).results.toList()
        assertEquals(
            listOf("testdrive-drive.x9@03", "testdrive-drive.e1@01", "testdrive-drive.e2@02"),
            matches.map { "${it.media.mediaId}@${it.media.episodeRange?.knownSorts?.single()}" },
        )
        assertTrue(matches.all { it.kind == MatchKind.EXACT })

        // 忘掉文件夹之后只剩单独指定的文件; 两个都忘掉就整条去掉
        service.forgetPick(7150, "pf")
        assertEquals(listOf("testdrive-drive.x9"), source.fetch(request).results.toList().map { it.media.mediaId })
        service.forgetPick(7150, "x9")
        assertEquals(emptyMap(), settings.account.subjectPicks)
    }

    @Test
    fun `media source remembers the folder of a played episode and lists it instead of searching`() = runTest {
        var searches = 0
        var folderGone = false
        val (service, settings) = service { request ->
            when (request.url.encodedPath) {
                "/api/files/search" -> {
                    searches++
                    reply(
                        if (request.url.parameters["q"] == "Smoking Behind the Supermarket with You") {
                            listJson(dir("d1", "Smoking.Behind.the.Supermarket.With.You.S01"))
                        } else {
                            listJson()
                        },
                    )
                }

                "/api/files/list" -> if (folderGone) {
                    reply("""{"code":404,"message":"文件不存在"}""", status = HttpStatusCode.NotFound)
                } else {
                    reply(
                        listJson(
                            DriveFile("f1", fileName = "Smoking.Behind.the.Supermarket.With.You.S01E01.mp4", parentFid = "d1", size = 247580374, isVideo = true),
                            DriveFile("f2", fileName = "Smoking.Behind.the.Supermarket.With.You.S01E02.mp4", parentFid = "d1", size = 247580374, isVideo = true),
                        ),
                    )
                }

                "/api/files/download" -> reply(downloadF1)
                else -> error("unexpected ${request.url}")
            }
        }

        fun request(episode: Int) = MediaFetchRequest(
            subjectId = "571784",
            episodeId = "$episode",
            subjectNames = listOf("在超市后门吸烟的二人", "Smoking Behind the Supermarket with You"),
            episodeSort = EpisodeSort(episode),
            episodeName = "",
        )

        val source = CloudDriveMediaSource(service, CloudDriveArguments(protocol = protocol))
        suspend fun fetch(episode: Int) = source.fetch(request(episode)).results.toList().map { it.media.mediaId }

        // 第一集: 照常搜; 只是搜到还不记, 播了才记
        assertEquals(listOf("testdrive-drive.f1", "testdrive-drive.f2"), fetch(1))
        assertEquals(2, searches)
        assertEquals(emptyMap(), settings.account.rememberedFolders)
        service.resolvePlayback("f1")
        assertEquals(
            DriveRememberedFolder("d1", listOf("Smoking.Behind.the.Supermarket.With.You.S01")),
            settings.account.rememberedFolders[571784],
        )

        // 第二集在记下的文件夹里: 不再搜
        assertEquals(listOf("testdrive-drive.f1", "testdrive-drive.f2"), fetch(2))
        assertEquals(2, searches)

        // 第三集文件夹里没有: 照常搜
        fetch(3)
        assertEquals(4, searches)

        // 文件夹没了: 忘掉, 照常搜
        folderGone = true
        fetch(2)
        assertEquals(6, searches)
        assertEquals(emptyMap(), settings.account.rememberedFolders)
    }

    @Test
    fun `merge cookies keeps order and replaces values`() {
        assertEquals(
            "a=1; sig=new; b=2; c=3",
            CloudDriveService.mergeCookies("a=1; sig=old; b=2", mapOf("sig" to "new", "c" to "3")),
        )
        assertEquals(
            mapOf("sid" to "x=y", "b" to ""),
            CloudDriveApi.parseSetCookieHeaders(listOf("sid=x=y; Path=/", "b=; Max-Age=0", "=bad")),
        )
    }
}
