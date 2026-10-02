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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.him188.ani.app.data.models.preference.QuarkConfig
import me.him188.ani.app.data.models.preference.QuarkPickedSubtitle
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.app.data.repository.user.Settings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 播放时带上视频旁边的外挂字幕: 自己网盘里的到所在文件夹找, 分享里的随视频一起转存.
 */
class QuarkSubtitlePlaybackTest {
    private class MemorySettings<T>(initial: T) : Settings<T> {
        val state = MutableStateFlow(initial)
        override val flow: Flow<T> get() = state
        override suspend fun set(value: T) {
            state.value = value
        }
    }

    private fun MockRequestHandleScope.reply(body: String): HttpResponseData =
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun HttpRequestData.path() = url.encodedPath.removePrefix("/1/clouddrive/")

    private fun HttpRequestData.jsonBody() = Json.parseToJsonElement((body as TextContent).text).jsonObject

    private fun HttpRequestData.fids() = jsonBody()["fids"]!!.jsonArray.map { it.jsonPrimitive.content }

    private fun list(vararg files: String) = """{"code":0,"data":{"list":[${files.joinToString(",")}]},"metadata":{"_total":${files.size}}}"""

    private fun file(fid: String, name: String, parent: String, size: Long = 1, video: Boolean = false, token: String = "") =
        """{"fid":"$fid","file_name":"$name","pdir_fid":"$parent","size":$size,"obj_category":"${if (video) "video" else "doc"}","share_fid_token":"$token"}"""

    /** file/download 回给 [fids] 的直链; 视频 [videos] 带上名字与所在文件夹. */
    private fun downloads(fids: List<String>, videos: Map<String, Pair<String, String>>) =
        """{"code":0,"data":[${
            fids.joinToString(",") { fid ->
                val (name, parent) = videos[fid] ?: ("" to "")
                """{"fid":"$fid","file_name":"$name","pdir_fid":"$parent","download_url":"https://dl.test/$fid"}"""
            }
        }]}"""

    /**
     * 找字幕有超时上限 (withTimeoutOrNull), 而模拟的 HTTP 跑在真线程上: 留在 runTest 的虚拟时钟里, 一等请求就算超时了.
     */
    private fun realTimeTest(block: suspend CoroutineScope.() -> Unit) = runTest { withContext(Dispatchers.Default, block) }

    private fun service(
        config: QuarkConfig,
        requests: MutableList<HttpRequestData>,
        settings: MemorySettings<QuarkConfig> = MemorySettings(config),
        handler: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ) =
        QuarkDriveService(
            settings,
            HttpClient(
                MockEngine { request ->
                    requests += request
                    handler(request)
                },
            ) { expectSuccess = false },
        )

    @Test
    fun `own drive video gets subtitles from its folder`() = realTimeTest {
        val requests = mutableListOf<HttpRequestData>()
        val service = service(QuarkConfig(cookie = "__pus=p1"), requests) { request ->
            when (request.path()) {
                "file/download" -> reply(downloads(request.fids(), mapOf("v1" to ("Show - 01.mkv" to "d1"))))
                "file/sort" -> {
                    assertEquals("d1", request.url.parameters["pdir_fid"])
                    reply(
                        list(
                            file("v1", "Show - 01.mkv", "d1", video = true),
                            file("s2", "Show - 01.tc.ass", "d1"),
                            file("s1", "Show - 01.sc.ass", "d1"),
                            file("v2", "Show - 02.mkv", "d1", video = true),
                            file("s3", "Show - 02.sc.ass", "d1"),
                        ),
                    )
                }

                else -> error("unexpected request ${request.path()}")
            }
        }

        val playback = service.resolvePlayback("v1")
        assertEquals("https://dl.test/v1", playback.url)
        assertEquals(listOf("简体中文", "繁体中文"), playback.subtitles.map { it.label })
        assertEquals(listOf("https://dl.test/s1", "https://dl.test/s2"), playback.subtitles.map { it.url })
        assertEquals(listOf("text/x-ssa", "text/x-ssa"), playback.subtitles.map { it.mimeType })
        // 视频直链, 列文件夹, 两条字幕的直链一次取
        assertEquals(listOf("file/download", "file/sort", "file/download"), requests.map { it.path() })
        assertEquals(listOf("s1", "s2"), requests.last().fids())
    }

    @Test
    fun `picked subtitles come first and are not repeated`() = realTimeTest {
        val requests = mutableListOf<HttpRequestData>()
        val config = QuarkConfig(
            cookie = "__pus=p1",
            pickedSubtitles = mapOf(
                "file:v1" to listOf(QuarkPickedSubtitle("p1", "别的字幕组 01.chs.ass"), QuarkPickedSubtitle("s2", "Show - 01.tc.ass")),
            ),
        )
        val service = service(config, requests) { request ->
            when (request.path()) {
                "file/download" -> reply(downloads(request.fids(), mapOf("v1" to ("Show - 01.mkv" to "d1"))))
                "file/sort" -> reply(
                    list(
                        file("v1", "Show - 01.mkv", "d1", video = true),
                        file("s1", "Show - 01.sc.ass", "d1"),
                        file("s2", "Show - 01.tc.ass", "d1"),
                    ),
                )

                else -> error("unexpected request ${request.path()}")
            }
        }
        val playback = service.resolvePlayback("v1")
        // 手动挂的两条在前 (其中 s2 也是自动找到的, 只出现一次), 重名的加序号
        assertEquals(listOf("https://dl.test/p1", "https://dl.test/s2", "https://dl.test/s1"), playback.subtitles.map { it.url })
        assertEquals(listOf("简体中文", "繁体中文", "简体中文 2"), playback.subtitles.map { it.label })
    }

    @Test
    fun `picking and forgetting subtitles`() = realTimeTest {
        val settings = MemorySettings(QuarkConfig(cookie = "__pus=p1"))
        val service = service(settings.state.value, mutableListOf(), settings) { error("no requests expected") }
        val key = assertNotNull(service.subtitleKeyOf(QuarkMediaSource.mediaFor(QuarkFile(fid = "v1", fileName = "Show - 01.mkv"), EpisodeSort(1), null)))
        val shareMedia = QuarkShareMatch(FoundShare("share1", "", "Show"), QuarkShareFile(fid = "v1", fileName = "Show - 01.mkv", shareFidToken = "t"), emptyList(), EpisodeSort(1))
            .toShareMedia("quark-share-search", "Show", null)
        // 分享里的视频按分享记, 不与自己网盘里 id 相同的文件混在一起
        val shareKey = service.subtitleKeyOf(shareMedia)
        assertTrue(shareKey != null && shareKey != key)

        service.pickSubtitle(key, QuarkFile(fid = "a", fileName = "a.ass"))
        service.pickSubtitle(key, QuarkFile(fid = "b", fileName = "b.srt"))
        service.pickSubtitle(key, QuarkFile(fid = "a", fileName = "a.ass"))
        // 后挂的排前面, 同一个文件只记一次
        assertEquals(listOf("a", "b"), service.pickedSubtitlesOf(key).map { it.fid })
        service.forgetSubtitle(key, "a")
        service.forgetSubtitle(key, "b")
        assertTrue(settings.state.value.pickedSubtitles.isEmpty())
    }

    @Test
    fun `subtitles look up failure does not stop playback`() = realTimeTest {
        val requests = mutableListOf<HttpRequestData>()
        val service = service(QuarkConfig(cookie = "__pus=p1"), requests) { request ->
            when (request.path()) {
                "file/download" -> reply(downloads(request.fids(), mapOf("v1" to ("Show - 01.mkv" to "d1"))))
                "file/sort" -> respond("oops", HttpStatusCode.InternalServerError)
                else -> error("unexpected request ${request.path()}")
            }
        }
        val playback = service.resolvePlayback("v1")
        assertEquals("https://dl.test/v1", playback.url)
        assertTrue(playback.subtitles.isEmpty())
    }

    @Test
    fun `share video is saved together with its subtitles`() = realTimeTest {
        val requests = mutableListOf<HttpRequestData>()
        var saved = false
        val service = service(QuarkConfig(cookie = "__pus=p1", shareSaveFolderId = "save1"), requests) { request ->
            when (request.path()) {
                "share/sharepage/token" -> reply("""{"code":0,"data":{"stoken":"st1","title":"t"}}""")
                "share/sharepage/detail" -> {
                    assertEquals("sd1", request.url.parameters["pdir_fid"])
                    reply(
                        list(
                            file("sf1", "Show - 01.mkv", "sd1", size = 500, video = true, token = "tok1"),
                            file("sf2", "Show - 01.sc.ass", "sd1", size = 10, token = "tok2"),
                            file("sf3", "Show - 02.mkv", "sd1", size = 500, video = true, token = "tok3"),
                        ),
                    )
                }

                "file/sort" -> reply(
                    if (!saved) {
                        list()
                    } else {
                        list(file("new1", "Show - 01.mkv", "save1", size = 500, video = true), file("new2", "Show - 01.sc.ass", "save1", size = 10))
                    },
                )

                "share/sharepage/save" -> {
                    saved = true
                    reply("""{"code":0,"data":{"task_id":"task1"}}""")
                }

                "task" -> reply("""{"code":0,"data":{"status":2,"save_as":{"save_as_top_fids":["new1","new2"]}}}""")
                "file/download" -> reply(downloads(request.fids(), mapOf("new1" to ("Show - 01.mkv" to "save1"))))
                else -> error("unexpected request ${request.path()}")
            }
        }
        val ref = QuarkShareFileRef("share1", "", "sf1", "tok1", "Show - 01.mkv", 500, folderId = "sd1")

        val playback = service.resolveSharePlayback(ref)
        assertEquals("https://dl.test/new1", playback.url)
        assertEquals(listOf("简体中文"), playback.subtitles.map { it.label })
        assertEquals(listOf("https://dl.test/new2"), playback.subtitles.map { it.url })
        val save = requests.single { it.path() == "share/sharepage/save" }.jsonBody()
        assertEquals(listOf("sf1", "sf2"), save["fid_list"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("tok1", "tok2"), save["fid_token_list"]!!.jsonArray.map { it.jsonPrimitive.content })

        // 再播一次: 不再转存, 也不用到转存文件夹里找
        requests.clear()
        val again = service.resolveSharePlayback(ref)
        assertEquals(listOf("https://dl.test/new2"), again.subtitles.map { it.url })
        assertEquals(listOf("share/sharepage/detail", "file/download", "file/download"), requests.map { it.path() })
    }

    @Test
    fun `share without a known folder uses subtitles already in the save folder`() = realTimeTest {
        val requests = mutableListOf<HttpRequestData>()
        val service = service(QuarkConfig(cookie = "__pus=p1", shareSaveFolderId = "save1"), requests) { request ->
            when (request.path()) {
                "file/sort" -> reply(
                    list(
                        file("c1", "Show - 01.mkv", "save1", size = 500, video = true),
                        file("c2", "Show - 01.sc.ass", "save1", size = 10),
                        file("c3", "Other - 01.mkv", "save1", size = 700, video = true),
                        file("c4", "Other - 01.sc.ass", "save1", size = 10),
                    ),
                )

                "file/download" -> reply(downloads(request.fids(), mapOf("c1" to ("Show - 01.mkv" to "save1"))))
                else -> error("unexpected request ${request.path()}")
            }
        }
        val playback = service.resolveSharePlayback(QuarkShareFileRef("share1", "", "sf1", "tok1", "Show - 01.mkv", 500))
        assertEquals("https://dl.test/c1", playback.url)
        assertEquals(listOf("https://dl.test/c2"), playback.subtitles.map { it.url })
    }
}
