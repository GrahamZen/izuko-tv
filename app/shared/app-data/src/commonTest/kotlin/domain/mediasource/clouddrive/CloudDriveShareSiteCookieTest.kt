/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.clouddrive

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import me.him188.ani.app.data.models.preference.CloudDriveAccount
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.source.ConnectionStatus
import me.him188.ani.datasources.api.source.MediaFetchRequest
import me.him188.ani.utils.ktor.asScopedHttpClient
import me.him188.ani.utils.ktor.installRawCookieHeader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 分享站按会话 Cookie 限制两次搜索的间隔 (苹果 CMS: 带着上次的会话再搜, 回「系统提示」页). 找分享的请求不带 Cookie,
 * 连通测试之后紧接着搜索也搜得到.
 */
class CloudDriveShareSiteCookieTest {
    /** 发到分享站的每个请求带的 Cookie 头 */
    private val siteCookies = mutableListOf<String?>()

    /** 像应用里的网页源 client: 装了 Cookie 插件, 共用一份存储 */
    private val siteClient = HttpClient(
        MockEngine { request ->
            val cookie = request.headers[HttpHeaders.Cookie]
            siteCookies += cookie
            val session = listOf("PHPSESSID=s${siteCookies.size}; path=/")
            if (cookie != null) {
                reply("""{"msg":"请不要频繁操作，搜索时间间隔为6秒","list":[]}""", setCookies = session)
            } else {
                reply("""{"list":[{"vod_name":"在超市后门吸烟的二人","vod_down_url":"https://share.drive.test/s/s1"}]}""", setCookies = session)
            }
        },
    ) {
        expectSuccess = false
        install(HttpCookies)
    }.also { it.installRawCookieHeader() }

    private val driveClient = mockClient { request ->
        when (request.url.encodedPath) {
            "/api/share/open" -> reply("""{"code":0,"data":{"token":"st","title":"超市后门"}}""")
            "/api/share/list" -> reply(listJson(video("a1", "Smoking.S01E01.mp4", size = 300_000_000, token = "t")))
            else -> error("unexpected request ${request.method.value} ${request.url}")
        }
    }

    @Test
    fun `search right after a connection test is not blocked by the site session`() = runTest {
        val test = testRegistry(accounts = accountsOf(CloudDriveAccount(cookie = "sid=p1")), client = { driveClient })
        val source = CloudDriveShareSearchMediaSource(
            "share-site",
            CloudDriveShareSearchArguments(
                name = "站点",
                drive = TestDrive.ID,
                config = DriveShareSearchConfig(
                    searchUrl = "https://site.test/search?wd={keyword}",
                    itemsPath = "list",
                    titlePath = "vod_name",
                    linkPaths = listOf("vod_down_url"),
                    maxKeywords = 1,
                    userAgent = "test",
                ),
            ),
            siteClient.asScopedHttpClient(),
            test.registry,
        )
        val request = MediaFetchRequest(
            subjectId = "571784", episodeId = "1", subjectNames = listOf("在超市后门吸烟的二人"), episodeSort = EpisodeSort(1), episodeName = "",
        )

        // 模拟的 HTTP 跑在真线程上: 这一段用真实时间
        val found = withContext(Dispatchers.Default) {
            assertEquals(ConnectionStatus.SUCCESS, source.checkConnection())
            source.fetch(request).results.toList()
        }

        assertEquals(listOf("share-site.s1.a1"), found.map { it.media.mediaId })
        assertTrue(siteCookies.size >= 2)
        assertTrue(siteCookies.all { it == null }, "发到分享站的 Cookie 头: $siteCookies")
    }
}
