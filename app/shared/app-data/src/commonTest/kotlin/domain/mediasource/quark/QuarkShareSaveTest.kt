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
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.him188.ani.app.data.models.preference.QuarkConfig
import me.him188.ani.app.data.repository.user.Settings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 播放分享里的文件: 转存到自己网盘的转存文件夹, 清理旧文件, 同一集不重复转存.
 */
class QuarkShareSaveTest {
    private class MemorySettings<T>(initial: T) : Settings<T> {
        val state = MutableStateFlow(initial)
        override val flow: Flow<T> get() = state
        override suspend fun set(value: T) {
            state.value = value
        }
    }

    private fun MockRequestHandleScope.reply(body: String): HttpResponseData =
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))

    private val ref = QuarkShareFileRef("share1", "", "sf1", "tok1", "S01E01.mp4", 500)

    private fun HttpRequestData.path() = url.encodedPath.removePrefix("/1/clouddrive/")

    private fun HttpRequestData.jsonBody() = Json.parseToJsonElement((body as TextContent).text).jsonObject

    /** 转存文件夹里已有 [existing] 个文件, updated_at 依次增大 (o1 最早). */
    private fun service(
        config: QuarkConfig,
        existing: Int,
        requests: MutableList<HttpRequestData>,
    ): Pair<QuarkDriveService, MemorySettings<QuarkConfig>> {
        val settings = MemorySettings(config)
        val client = HttpClient(
            MockEngine { request ->
                requests += request
                val path = request.path()
                when {
                    path == "file/sort" && request.url.parameters["pdir_fid"] == "0" ->
                        reply("""{"code":0,"data":{"list":[{"fid":"other","file_name":"动漫","dir":true}]},"metadata":{"_total":1}}""")

                    path == "file" && request.method == HttpMethod.Post -> reply("""{"code":0,"data":{"fid":"save1"}}""")
                    path == "file/sort" -> {
                        val list = (1..existing).joinToString(",") { """{"fid":"o$it","file_name":"o$it.mp4","size":1,"updated_at":${it * 1000}}""" }
                        reply("""{"code":0,"data":{"list":[$list]},"metadata":{"_total":$existing}}""")
                    }

                    path == "share/sharepage/token" -> reply("""{"code":0,"data":{"stoken":"st1","title":"t"}}""")
                    path == "share/sharepage/save" -> reply("""{"code":0,"data":{"task_id":"task1"}}""")
                    path == "task" -> reply("""{"code":0,"data":{"status":2,"save_as":{"save_as_top_fids":["new1"]}}}""")
                    path == "file/delete" -> reply("""{"code":0,"data":{"task_id":"task2"}}""")
                    path == "file/download" -> reply("""{"code":0,"data":[{"download_url":"https://dl.test/new1"}]}""")
                    else -> error("unexpected request ${request.method.value} $path")
                }
            },
        ) { expectSuccess = false }
        return QuarkDriveService(settings, client) to settings
    }

    @Test
    fun `creates the folder - saves - prunes the oldest and reuses the saved file`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val (service, settings) = service(QuarkConfig(cookie = "__pus=p1"), existing = QuarkDriveService.MAX_SAVED_FILES, requests)

        val playback = service.resolveSharePlayback(ref)
        assertEquals("https://dl.test/new1", playback.url)
        assertEquals("save1", settings.state.value.shareSaveFolderId)

        val create = requests.single { it.path() == "file" }.jsonBody()
        assertEquals("0", create["pdir_fid"]!!.jsonPrimitive.content)
        assertEquals(QuarkDriveService.SAVE_FOLDER_NAME, create["file_name"]!!.jsonPrimitive.content)

        // 看分享不带登录 Cookie
        assertNull(requests.single { it.path() == "share/sharepage/token" }.headers[HttpHeaders.Cookie])

        val save = requests.single { it.path() == "share/sharepage/save" }.jsonBody()
        assertEquals("save1", save["to_pdir_fid"]!!.jsonPrimitive.content)
        assertEquals(listOf("sf1"), save["fid_list"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("tok1"), save["fid_token_list"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("st1", save["stoken"]!!.jsonPrimitive.content)

        // 已有 20 个, 加上新的一个要删掉最早的那一个, 而且只删它
        val delete = requests.single { it.path() == "file/delete" }.jsonBody()
        assertEquals(listOf("o1"), delete["filelist"]!!.jsonArray.map { it.jsonPrimitive.content })

        // 同一集再播: 不再转存
        requests.clear()
        service.resolveSharePlayback(ref)
        assertEquals(listOf("file/download"), requests.map { it.path() })
    }

    @Test
    fun `uses the remembered folder and an already saved copy`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val (service, _) = service(
            QuarkConfig(cookie = "__pus=p1", shareSaveFolderId = "save1"),
            existing = 2,
            requests,
        )
        // 文件夹里已经有同名同大小的文件 (上次转存的)
        service.resolveSharePlayback(ref.copy(fileName = "o2.mp4", size = 1))
        assertEquals(listOf("file/sort", "file/download"), requests.map { it.path() })
        assertEquals("save1", requests.first().url.parameters["pdir_fid"])
    }

    @Test
    fun `own drive search skips the save folder`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val settings = MemorySettings(QuarkConfig(cookie = "__pus=p1", shareSaveFolderId = "save1"))
        val client = HttpClient(
            MockEngine { request ->
                requests += request
                respond(
                    """{"code":0,"data":{"list":[
                        {"fid":"a","file_name":"芙莉莲 01.mp4","pdir_fid":"d1","obj_category":"video"},
                        {"fid":"b","file_name":"芙莉莲 01.mp4","pdir_fid":"save1","obj_category":"video"},
                        {"fid":"save1","file_name":"Izuko 转存","pdir_fid":"0","dir":true}
                    ]},"metadata":{"_total":3}}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        ) { expectSuccess = false }
        val files = QuarkDriveService(settings, client).browser.search("芙莉莲")
        assertEquals(listOf("a"), files.map { it.fid })
        assertTrue(requests.size == 1)
    }
}
