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
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.data.models.preference.QuarkConfig
import me.him188.ani.app.data.models.preference.QuarkRememberedShare
import me.him188.ani.app.data.repository.user.Settings
import me.him188.ani.app.platform.PlaybackRequestHints
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.source.MediaFetchRequest
import me.him188.ani.datasources.api.source.MediaSourceConfig
import me.him188.ani.datasources.api.source.serializeArguments
import me.him188.ani.utils.ktor.asScopedHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 「夸克分享搜索」记住播过的那一集所在的分享文件夹: 之后先只列它, 有要的那一集就不再去站点搜索.
 */
class QuarkShareRememberTest {
    private class MemorySettings<T>(initial: T) : Settings<T> {
        val state = MutableStateFlow(initial)
        override val flow: Flow<T> get() = state
        override suspend fun set(value: T) {
            state.value = value
        }
    }

    private fun MockRequestHandleScope.reply(body: String): HttpResponseData =
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))

    private val episode1 = """{"fid":"f1","file_name":"Smoking.S01E01.mp4","pdir_fid":"d1","size":300000000,"obj_category":"video","share_fid_token":"t1"}"""
    private val episode3 = """{"fid":"f3","file_name":"Smoking.S01E03.mp4","pdir_fid":"d1","size":300000000,"obj_category":"video","share_fid_token":"t3"}"""

    private var siteSearches = 0
    private var shareGone = false
    private val folderListings = mutableListOf<String>()

    private val settings = MemorySettings(QuarkConfig(cookie = "__pus=p1", shareSaveFolderId = "save1"))

    private val client = HttpClient(
        MockEngine { request ->
            val path = request.url.encodedPath.removePrefix("/1/clouddrive/")
            when {
                request.url.host == "site.test" -> {
                    siteSearches++
                    reply("""{"list":[{"vod_name":"在超市后门吸烟的二人","vod_down_url":"https://pan.quark.cn/s/s1"}]}""")
                }

                path == "share/sharepage/token" -> if (shareGone) {
                    reply("""{"status":404,"code":41011,"message":"分享已取消"}""")
                } else {
                    reply("""{"code":0,"data":{"stoken":"st1","title":"超市后门"}}""")
                }

                path == "share/sharepage/detail" -> {
                    val folder = request.url.parameters["pdir_fid"].orEmpty()
                    folderListings += folder
                    when {
                        shareGone -> reply("""{"status":404,"code":41011,"message":"分享已取消"}""")
                        folder == "0" -> reply("""{"code":0,"data":{"list":[{"fid":"d1","file_name":"S01","dir":true}]},"metadata":{"_total":1}}""")
                        folder == "d1" -> reply("""{"code":0,"data":{"list":[$episode1,$episode3]},"metadata":{"_total":2}}""")
                        else -> reply("""{"code":0,"data":{"list":[]},"metadata":{"_total":0}}""")
                    }
                }

                // 转存文件夹里已经有这一集的副本 (同名同大小), 播放时不再转存
                path == "file/sort" && request.url.parameters["pdir_fid"] == "save1" ->
                    reply("""{"code":0,"data":{"list":[{"fid":"saved1","file_name":"Smoking.S01E01.mp4","size":300000000}]},"metadata":{"_total":1}}""")

                path == "file/download" -> reply("""{"code":0,"data":[{"download_url":"https://dl.test/saved1"}]}""")
                else -> error("unexpected request ${request.method.value} ${request.url}")
            }
        },
    ) { expectSuccess = false }

    private val service = QuarkDriveService(settings, client)

    private val source = QuarkShareSearchMediaSource(
        "share-site",
        MediaSourceConfig(
            serializedArguments = MediaSourceConfig.serializeArguments(
                QuarkShareSearchArguments.serializer(),
                QuarkShareSearchArguments(
                    name = "站点",
                    config = QuarkShareSearchConfig(
                        searchUrl = "https://site.test/search?wd={keyword}",
                        itemsPath = "list",
                        titlePath = "vod_name",
                        linkPaths = listOf("vod_down_url"),
                        maxKeywords = 1,
                        userAgent = "test",
                    ),
                ),
            ),
        ),
        client.asScopedHttpClient(),
        service,
    )

    private fun request(episode: Int) = MediaFetchRequest(
        subjectId = "571784",
        episodeId = "$episode",
        subjectNames = listOf("在超市后门吸烟的二人"),
        episodeSort = EpisodeSort(episode),
        episodeName = "",
    )

    private suspend fun fetch(episode: Int) = source.fetch(request(episode)).results.toList().map { it.media }

    @Test
    fun `remembers the share folder of a played episode and lists it instead of searching`() = runTest {
        // 第一集: 照常搜; 只是搜到还不记, 播了才记
        val first = fetch(1)
        assertEquals(listOf("share-site.s1.f1", "share-site.s1.f3"), first.map { it.mediaId })
        assertEquals(1, siteSearches)
        assertEquals(emptyMap(), settings.state.value.rememberedShares)
        val playback = service.resolveSharePlayback(QuarkShareFileRef.parse(first.first().download.uri)!!)
        // 本机数据按分享里的文件存: 转存的副本被删了重新转存, 内容还是同一个
        assertEquals("quark-share:s1/f1", playback.headers[PlaybackRequestHints.CACHE_KEY_HEADER])
        assertEquals(
            QuarkRememberedShare("s1", "", "在超市后门吸烟的二人", "d1", listOf("S01")),
            settings.state.value.rememberedShares["share-site:571784"],
        )

        // 第三集在记下的文件夹里: 不去站点搜索, 也只列那一个文件夹
        folderListings.clear()
        assertEquals(listOf("share-site.s1.f1", "share-site.s1.f3"), fetch(3).map { it.mediaId })
        assertEquals(1, siteSearches)
        assertEquals(listOf("d1"), folderListings)

        // 第二集文件夹里没有: 照常搜
        fetch(2)
        assertEquals(2, siteSearches)

        // 分享被取消: 忘掉, 照常搜
        shareGone = true
        assertEquals(emptyList(), fetch(3))
        assertEquals(3, siteSearches)
        assertEquals(emptyMap(), settings.state.value.rememberedShares)
    }
}
