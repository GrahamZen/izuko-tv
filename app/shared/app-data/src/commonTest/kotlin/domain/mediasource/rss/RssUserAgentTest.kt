/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.rss

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.domain.foundation.DeviceBrowserUserAgentHolder
import me.him188.ani.app.domain.foundation.ScopedHttpClientUserAgent
import me.him188.ani.app.domain.foundation.UserAgentFeatureHandler
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.utils.ktor.asScopedHttpClient
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * RSS 数据源发请求用本机浏览器的 UA: client 自带的老版本 Chromium UA 有站点照它拒绝 (Cloudflare 防护回 403).
 */
class RssUserAgentTest {
    @AfterTest
    fun reset() {
        DeviceBrowserUserAgentHolder.install { null }
    }

    private suspend fun userAgentSent(): String? {
        var seen: String? = null
        val engine = MockEngine { request ->
            seen = request.headers[HttpHeaders.UserAgent]
            respond("<rss><channel></channel></rss>", headers = headersOf(HttpHeaders.ContentType, "application/rss+xml"))
        }
        val client = HttpClient(engine)
        // 覆盖 UA 的拦截装在 client 上 (见 UserAgentFeatureHandler.applyToClient), 测试要按同样的方式接线
        UserAgentFeatureHandler.applyToClient(client, ScopedHttpClientUserAgent.BROWSER)
        DefaultRssMediaSourceEngine(flowOf(client.asScopedHttpClient())).search(
            RssSearchConfig(searchUrl = "https://rss.example.com/feed.xml?q={keyword}"),
            RssSearchQuery(subjectName = "name", allSubjectNames = listOf("name"), episodeSort = EpisodeSort(1), episodeEp = null, episodeName = null),
            page = null,
            mediaSourceId = "rss",
        )
        return seen
    }

    @Test
    fun `uses the device browser user agent`() = runTest {
        DeviceBrowserUserAgentHolder.install { "Mozilla/5.0 (Linux; Android 11; Device; wv) AppleWebKit/537.36 Chrome/157.0.0.0" }

        assertEquals("Mozilla/5.0 (Linux; Android 11; Device; wv) AppleWebKit/537.36 Chrome/157.0.0.0", userAgentSent())
    }

    @Test
    fun `keeps the client user agent when the device one is unavailable`() = runTest {
        DeviceBrowserUserAgentHolder.install { null }

        assertNull(userAgentSent())
    }
}
