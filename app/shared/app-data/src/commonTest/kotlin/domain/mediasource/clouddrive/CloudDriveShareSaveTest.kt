/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.clouddrive

import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.data.models.preference.CloudDriveAccount
import me.him188.ani.app.data.models.preference.CloudDriveAccounts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 播放分享里的文件: 转存到自己网盘的转存文件夹, 放不下时先删旧副本, 同一集不重复转存.
 */
class CloudDriveShareSaveTest {
    private val ref = DriveShareFileRef("share1", "", "sf1", "tok1", "S01E01.mp4", 500)

    private fun HttpRequestData.path() = url.encodedPath

    /** 转存文件夹里已有 [existing] 个文件, 修改时间依次增大 (o1 最早). */
    private fun service(
        account: CloudDriveAccount,
        existing: Int,
        requests: MutableList<HttpRequestData>,
    ): Pair<CloudDriveService, MemorySettings<CloudDriveAccounts>> {
        val settings = accountsOf(account)
        val service = testDriveService(settings) { request ->
            requests += request
            val path = request.path()
            when {
                path == "/api/files/list" && request.url.parameters["parent"] == TestDrive.ROOT ->
                    reply(listJson(dir("other", "动漫", parent = TestDrive.ROOT)))

                path == "/api/files/folder" && request.method == HttpMethod.Post -> reply("""{"code":0,"data":{"id":"save1"}}""")
                path == "/api/files/list" ->
                    reply(listJson((1..existing).map { DriveFile("o$it", fileName = "o$it.mp4", size = 1, updatedAt = it * 1000L) }))

                path == "/api/share/open" -> reply("""{"code":0,"data":{"token":"st1","title":"t"}}""")
                path == "/api/share/save" -> reply("""{"code":0,"data":{"task":"task1"}}""")
                path == "/api/tasks/task1" -> reply("""{"code":0,"data":{"state":"done","result":{"ids":["new1"]}}}""")
                path == "/api/files/delete" -> reply("""{"code":0,"data":{"task":"task2"}}""")
                path == "/api/files/download" -> reply(downloadJson(request.jsonBody().strings("ids")))
                else -> error("unexpected request ${request.method.value} $path")
            }
        }
        return service to settings
    }

    @Test
    fun `creates the folder - saves and reuses the saved file`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val (service, settings) = service(CloudDriveAccount(cookie = "sid=p1"), existing = 20, requests)

        val playback = service.resolveSharePlayback(ref)
        assertEquals("https://dl.drive.test/new1", playback.url)
        assertEquals("save1", settings.account.shareSaveFolderId)

        val create = requests.single { it.path() == "/api/files/folder" }.jsonBody()
        assertEquals(TestDrive.ROOT, create.string("parent"))
        assertEquals(CloudDriveService.SAVE_FOLDER_NAME, create.string("name"))

        // 看分享不带登录 Cookie
        assertNull(requests.single { it.path() == "/api/share/open" }.headers[HttpHeaders.Cookie])

        val saveRequest = requests.single { it.path() == "/api/share/save" }
        assertEquals("sid=p1", saveRequest.headers[HttpHeaders.Cookie])
        val save = saveRequest.jsonBody()
        assertEquals("save1", save.string("target"))
        assertEquals(listOf("sf1"), save.strings("ids"))
        assertEquals(listOf("tok1"), save.strings("fileTokens"))
        assertEquals("st1", save.string("token"))
        assertEquals("share1", save.string("share"))
        assertEquals(TestDrive.SHARE_ROOT, save.string("from"))

        // 副本不按个数删: 已有 20 个也不删
        assertTrue(requests.none { it.path() == "/api/files/delete" })

        // 同一集再播: 不再转存
        requests.clear()
        service.resolveSharePlayback(ref)
        assertEquals(listOf("/api/files/download"), requests.map { it.path() })
    }

    @Test
    fun `uses the remembered folder and an already saved copy`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val (service, _) = service(CloudDriveAccount(cookie = "sid=p1", shareSaveFolderId = "save1"), existing = 2, requests)
        // 文件夹里已经有同名同大小的文件 (上次转存的)
        service.resolveSharePlayback(ref.copy(fileName = "o2.mp4", size = 1))
        assertEquals(listOf("/api/files/list", "/api/files/download"), requests.map { it.path() })
        assertEquals("save1", requests.first().url.parameters["parent"])
        assertEquals(listOf("o2"), requests.last().jsonBody().strings("ids"))
    }

    @Test
    fun `playing a share needs login`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val (service, _) = service(CloudDriveAccount.Default, existing = 0, requests)
        assertFailsWith<CloudDriveAuthException> { service.resolveSharePlayback(ref) }
        assertEquals(emptyList(), requests)
    }

    /**
     * 网盘空间不够的情形: 转存文件夹里已有 o1、o2 两个 2 GB 的副本 (o1 早) 与归 o1 的字幕 o1.sc.ass (o1s).
     * [free] 是 `GET /api/account/capacity` 报的剩余空间, 删除任务完成时加上删掉的大小; [lagReads] 大于 0 时删除完成后还要再查这么多次才加上
     * (网盘的已用量要过一会儿才降). 为 null 时协议没有这项. 转存的文件比报的剩余空间大时转存回空间不够;
     * 前 [fullSaves] 次转存一律回空间不够 (网盘报的数与转存的结果对不上).
     */
    private fun fullDriveService(
        requests: MutableList<HttpRequestData>,
        free: Long? = 0,
        lagReads: Int = 0,
        fullSaves: Int = 0,
        saving: Long = ref.size,
    ): CloudDriveService {
        val sizes = mapOf("o1" to 2_000_000_000L, "o1s" to 1_000L, "o2" to 2_000_000_000L)
        var reported = free ?: 0
        var freed = 0L
        var readsAfterDelete = 0
        var saveTasks = 0
        val protocol = if (free == null) TestDrive.protocol else TestDrive.protocol.copy(
            api = TestDrive.protocol.api.copy(
                capacity = DriveCapacityOp(DriveRequest("GET", "https://{host}/api/account/capacity"), total = "data.total", used = "data.used"),
            ),
        )
        val total = 10_000_000_000
        return testDriveService(accountsOf(CloudDriveAccount(cookie = "sid=p1", shareSaveFolderId = "save1")), protocol) { request ->
            requests += request
            when (request.path()) {
                "/api/account/capacity" -> {
                    if (freed > 0 && ++readsAfterDelete > lagReads) reported += freed.also { freed = 0 }
                    reply("""{"code":0,"data":{"total":$total,"used":${total - reported}}}""")
                }

                "/api/files/list" -> reply(
                    listJson(
                        DriveFile("o1", fileName = "o1.mkv", size = sizes.getValue("o1"), updatedAt = 1000),
                        DriveFile("o1s", fileName = "o1.sc.ass", size = sizes.getValue("o1s"), updatedAt = 1000),
                        DriveFile("o2", fileName = "o2.mkv", size = sizes.getValue("o2"), updatedAt = 2000),
                    ),
                )

                "/api/share/open" -> reply("""{"code":0,"data":{"token":"st1","title":"t"}}""")
                "/api/share/save" -> reply("""{"code":0,"data":{"task":"save-task"}}""")
                "/api/files/delete" -> {
                    freed += request.jsonBody().strings("ids").sumOf { sizes.getValue(it) }
                    reply("""{"code":0,"data":{"task":"delete-task"}}""")
                }

                "/api/tasks/delete-task" -> {
                    if (lagReads == 0) reported += freed.also { freed = 0 }
                    reply("""{"code":0,"data":{"state":"done"}}""")
                }

                "/api/tasks/save-task" -> if (++saveTasks <= fullSaves || free != null && reported < saving) {
                    reply("""{"code":4507,"message":"quota exceeded"}""")
                } else {
                    reply("""{"code":0,"data":{"state":"done","result":{"ids":["new1"]}}}""")
                }

                "/api/files/download" -> reply(downloadJson(request.jsonBody().strings("ids")))
                else -> error("unexpected request ${request.method.value} ${request.path()}")
            }
        }
    }

    @Test
    fun `no room - removes the oldest copy with its subtitles before saving`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val service = fullDriveService(requests)

        assertEquals("https://dl.drive.test/new1", service.resolveSharePlayback(ref).url)
        // 删掉最早的一份 (视频连同它的字幕) 就够放下, o2 不动
        val delete = requests.single { it.path() == "/api/files/delete" }.jsonBody()
        assertEquals(listOf("o1", "o1s"), delete.strings("ids"))
        // 先删、等删除完成, 再转存一次就成
        val order = requests.map { it.path() }
        assertTrue(order.indexOf("/api/tasks/delete-task") < order.indexOf("/api/share/save"), "$order")
        assertEquals(1, requests.count { it.path() == "/api/share/save" })
    }

    @Test
    fun `no room - waits until the freed space shows up before saving`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        // 删除完成后还要再查三次, 剩余空间才降下来; 马上转存的话照样回空间不够
        val service = fullDriveService(requests, lagReads = 3)

        assertEquals("https://dl.drive.test/new1", service.resolveSharePlayback(ref).url)
        assertEquals(1, requests.count { it.path() == "/api/share/save" })
        val order = requests.map { it.path() }
        val delete = order.indexOf("/api/tasks/delete-task")
        assertEquals(4, order.subList(delete, order.indexOf("/api/share/save")).count { it == "/api/account/capacity" })
        assertEquals(3_000L, testScheduler.currentTime)
    }

    @Test
    fun `no room - stops waiting for the freed space after a while and saves anyway`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val service = fullDriveService(requests, lagReads = Int.MAX_VALUE)

        val error = assertFailsWith<CloudDriveCapacityException> { service.resolveSharePlayback(ref) }
        assertTrue(error.isCapacityLimit)
        assertEquals(1, requests.count { it.path() == "/api/share/save" })
        assertEquals(30_000L, testScheduler.currentTime)
    }

    @Test
    fun `no room even after removing every copy - removes nothing and does not save`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val big = ref.copy(size = 5_000_000_000)
        val service = fullDriveService(requests, saving = big.size)

        val error = assertFailsWith<CloudDriveCapacityException> { service.resolveSharePlayback(big) }
        assertTrue(error.isCapacityLimit)
        assertTrue(requests.none { it.path() == "/api/files/delete" })
        assertTrue(requests.none { it.path() == "/api/share/save" })
    }

    @Test
    fun `enough room - saves without removing copies`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val service = fullDriveService(requests, free = 1_000_000)

        assertEquals("https://dl.drive.test/new1", service.resolveSharePlayback(ref).url)
        assertTrue(requests.none { it.path() == "/api/files/delete" })
        assertEquals(1, requests.count { it.path() == "/api/share/save" })
    }

    @Test
    fun `unknown free space keeps the copies and reports the full drive`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val service = fullDriveService(requests, free = null, fullSaves = 1)

        val error = assertFailsWith<CloudDriveCapacityException> { service.resolveSharePlayback(ref) }
        assertTrue(error.isCapacityLimit)
        assertTrue(requests.none { it.path() == "/api/files/delete" })
        assertEquals(1, requests.count { it.path() == "/api/share/save" })
    }

    @Test
    fun `drive full although the free space says it fits - removes nothing`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val service = fullDriveService(requests, free = 1_000_000, fullSaves = 1)

        val error = assertFailsWith<CloudDriveCapacityException> { service.resolveSharePlayback(ref) }
        assertTrue(error.isCapacityLimit)
        assertTrue(requests.none { it.path() == "/api/files/delete" })
        assertEquals(1, requests.count { it.path() == "/api/share/save" })
        // 不当成转存文件夹被删、去重找文件夹
        assertTrue(requests.none { it.path() == "/api/files/folder" })
    }

    /**
     * 文件凭证与分享令牌配对的网盘: 每次打开分享发一个新令牌 (st1, st2, ...), 只认最新的;
     * 列文件夹 sd1 时视频 sf1 的凭证是 `ft-<令牌>`. 转存要令牌与凭证都对得上, 前 [rejectSaves] 次转存一律拒绝.
     */
    private fun pairedTokenService(rejectSaves: Int, requests: MutableList<HttpRequestData>): Pair<CloudDriveService, MemorySettings<CloudDriveAccounts>> {
        val settings = accountsOf(CloudDriveAccount(cookie = "sid=p1", shareSaveFolderId = "save1"))
        var issued = 0
        var saves = 0
        val service = testDriveService(settings) { request ->
            requests += request
            val latest = "st$issued"
            when (request.path()) {
                "/api/share/open" -> reply("""{"code":0,"data":{"token":"st${++issued}","title":"t"}}""")
                "/api/share/list" -> if (request.url.parameters["token"] == latest && request.url.parameters["parent"] == "sd1") {
                    reply(listJson(video("sf1", "S01E01.mp4", parent = "sd1", size = 500, token = "ft-$latest")))
                } else {
                    reply("""{"code":404,"message":"分享不存在"}""")
                }

                "/api/share/save" -> {
                    val body = request.jsonBody()
                    val paired = body.string("token") == latest && body.strings("fileTokens") == listOf("ft-$latest")
                    if (++saves <= rejectSaves || !paired) {
                        reply("""{"code":41004,"message":"转存文件token校验异常"}""", status = HttpStatusCode.Forbidden)
                    } else {
                        reply("""{"code":0,"data":{"task":"task1"}}""")
                    }
                }

                "/api/tasks/task1" -> reply("""{"code":0,"data":{"state":"done","result":{"ids":["new1"]}}}""")
                "/api/files/list" -> reply(listJson())
                "/api/files/download" -> reply(downloadJson(request.jsonBody().strings("ids")))
                else -> error("unexpected request ${request.method.value} ${request.path()}")
            }
        }
        return service to settings
    }

    /** 搜索时记下的凭证配的是那时的令牌, 早过期了. */
    private val staleRef = DriveShareFileRef("share1", "", "sf1", "ft-old", "S01E01.mp4", 500, folderId = "sd1")

    @Test
    fun `save uses the file token listed with the current share token`() = realTimeTest {
        val requests = mutableListOf<HttpRequestData>()
        val (service, _) = pairedTokenService(rejectSaves = 0, requests)

        assertEquals("https://dl.drive.test/new1", service.resolveSharePlayback(staleRef).url)
        val save = requests.single { it.path() == "/api/share/save" }.jsonBody()
        assertEquals("st1", save.string("token"))
        assertEquals(listOf("ft-st1"), save.strings("fileTokens"))
    }

    @Test
    fun `rejected save refreshes the share token and lists the folder again`() = realTimeTest {
        val requests = mutableListOf<HttpRequestData>()
        val (service, _) = pairedTokenService(rejectSaves = 1, requests)

        assertEquals("https://dl.drive.test/new1", service.resolveSharePlayback(staleRef).url)
        val saves = requests.filter { it.path() == "/api/share/save" }.map { it.jsonBody() }
        assertEquals(2, saves.size)
        assertEquals("st2", saves.last().string("token"))
        assertEquals(listOf("ft-st2"), saves.last().strings("fileTokens"))
    }

    @Test
    fun `save still rejected after refreshing is not a login problem`() = realTimeTest {
        val requests = mutableListOf<HttpRequestData>()
        val (service, settings) = pairedTokenService(rejectSaves = 2, requests)

        val error = assertFailsWith<CloudDriveShareUnavailableException> { service.resolveSharePlayback(staleRef) }
        assertTrue(error.message!!.contains("转存文件token校验异常"), error.message)
        assertEquals(2, requests.count { it.path() == "/api/share/save" })
        // 不当成转存文件夹被删: 不重新找文件夹, 记下的文件夹也不清
        assertTrue(requests.none { it.path() == "/api/files/folder" })
        assertEquals("save1", settings.account.shareSaveFolderId)
        assertTrue(settings.account.isLoggedIn)
    }

    @Test
    fun `own drive search skips the save folder`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val settings = accountsOf(CloudDriveAccount(cookie = "sid=p1", shareSaveFolderId = "save1"))
        val service = testDriveService(settings) { request ->
            requests += request
            reply(
                listJson(
                    DriveFile("a", fileName = "芙莉莲 01.mp4", parentFid = "d1", isVideo = true),
                    DriveFile("b", fileName = "芙莉莲 01.mp4", parentFid = "save1", isVideo = true),
                    dir("save1", CloudDriveService.SAVE_FOLDER_NAME, parent = TestDrive.ROOT),
                ),
            )
        }
        val files = service.browser.search("芙莉莲")
        assertEquals(listOf("a"), files.map { it.fid })
        assertEquals(1, requests.size)
    }
}
