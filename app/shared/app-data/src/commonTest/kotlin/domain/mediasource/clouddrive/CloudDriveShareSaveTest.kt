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
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.data.models.preference.CloudDriveAccount
import me.him188.ani.app.data.models.preference.CloudDriveAccounts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 播放分享里的文件: 转存到自己网盘的转存文件夹, 清理旧文件, 同一集不重复转存.
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
    fun `creates the folder - saves - prunes the oldest and reuses the saved file`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val (service, settings) = service(CloudDriveAccount(cookie = "sid=p1"), existing = CloudDriveService.MAX_SAVED_FILES, requests)

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

        // 已有 20 个, 加上新的一个要删掉最早的那一个, 而且只删它
        val delete = requests.single { it.path() == "/api/files/delete" }.jsonBody()
        assertEquals(listOf("o1"), delete.strings("ids"))
        // 清理旧文件不等删除完成
        assertTrue(requests.none { it.path() == "/api/tasks/task2" })

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
     * 网盘满了的情形: 转存文件夹里已有 o1、o2 两个副本; 前 [fullSaves] 次转存任务回空间不够的错误码.
     * 删除任务的进度查询回「完成」.
     */
    private fun fullDriveService(fullSaves: Int, requests: MutableList<HttpRequestData>): CloudDriveService {
        var saveTasks = 0
        return testDriveService(accountsOf(CloudDriveAccount(cookie = "sid=p1", shareSaveFolderId = "save1"))) { request ->
            requests += request
            when (request.path()) {
                "/api/files/list" -> reply(
                    listJson(
                        DriveFile("o1", fileName = "o1.mkv", size = 2_000_000_000, updatedAt = 1000),
                        DriveFile("o2", fileName = "o2.mkv", size = 2_000_000_000, updatedAt = 2000),
                    ),
                )

                "/api/share/open" -> reply("""{"code":0,"data":{"token":"st1","title":"t"}}""")
                "/api/share/save" -> reply("""{"code":0,"data":{"task":"save-task"}}""")
                "/api/files/delete" -> reply("""{"code":0,"data":{"task":"delete-task"}}""")
                "/api/tasks/delete-task" -> reply("""{"code":0,"data":{"state":"done"}}""")
                "/api/tasks/save-task" -> if (++saveTasks <= fullSaves) {
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
    fun `drive full - clears the save folder - waits for the deletion and saves again`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val service = fullDriveService(fullSaves = 1, requests)

        assertEquals("https://dl.drive.test/new1", service.resolveSharePlayback(ref).url)
        val delete = requests.single { it.path() == "/api/files/delete" }.jsonBody()
        assertEquals(listOf("o1", "o2"), delete.strings("ids"))
        // 等删除完成之后才再转存
        val order = requests.map { it.path() }
        assertTrue(order.indexOf("/api/tasks/delete-task") < order.lastIndexOf("/api/share/save"), "$order")
        assertEquals(2, requests.count { it.path() == "/api/share/save" })
    }

    @Test
    fun `drive still full after clearing the save folder`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val service = fullDriveService(fullSaves = 2, requests)

        val error = assertFailsWith<CloudDriveCapacityException> { service.resolveSharePlayback(ref) }
        assertTrue(error.isCapacityLimit)
        // 不再当成转存文件夹被删、去重找文件夹
        assertEquals(2, requests.count { it.path() == "/api/share/save" })
        assertTrue(requests.none { it.path() == "/api/files/folder" })
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

    @Test
    fun `prune keeps the newest files`() {
        val files = (1..5).map { DriveFile("f$it", fileName = "$it.mp4", updatedAt = it * 1000L) }.shuffled()
        assertEquals(listOf("f1", "f2"), CloudDriveService.filesToPrune(files, keep = 3, keepSubtitles = 0).map { it.fid })
        assertTrue(CloudDriveService.filesToPrune(files, keep = 5, keepSubtitles = 0).isEmpty())
    }

    @Test
    fun `prune counts videos and subtitles separately`() {
        val videos = (1..3).map { DriveFile("v$it", fileName = "$it.mkv", updatedAt = it * 1000L) }
        val subtitles = (1..3).map { DriveFile("s$it", fileName = "$it.sc.ass", updatedAt = it * 1000L + 500) }
        val stale = CloudDriveService.filesToPrune((videos + subtitles).shuffled(), keep = 2, keepSubtitles = 1)
        assertEquals(setOf("v1", "s1", "s2"), stale.map { it.fid }.toSet())
    }
}
