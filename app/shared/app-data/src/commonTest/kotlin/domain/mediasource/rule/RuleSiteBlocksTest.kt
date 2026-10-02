/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.rule

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.data.repository.media.SelectorMediaSourceEpisodeCacheRepository
import me.him188.ani.app.domain.mediasource.web.NoopWebSearchSessionCacheDao
import me.him188.ani.app.domain.mediasource.web.PageEvaluator
import me.him188.ani.app.domain.mediasource.web.captcha.UnsupportedCaptchaBrowserFactory
import me.him188.ani.app.domain.mediasource.web.captcha.WebSessionManager
import me.him188.ani.app.domain.mediasource.web.captcha.WebSourceCookieJar
import me.him188.ani.app.domain.mediasource.web.captcha.WebSourceIdentityRegistry
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.source.MediaFetchRequest
import me.him188.ani.datasources.api.source.MediaSourceConfig
import me.him188.ani.datasources.api.source.serializeArguments
import me.him188.ani.utils.ktor.asScopedHttpClient
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration

/**
 * 站点拉黑了这个网络时规则源停手: 收到封禁页就不再接着搜, 冷却期内一个请求都不发; 没搜到的名字换集不重搜.
 */
class RuleSiteBlocksTest {
    @AfterTest
    fun reset() = RuleSiteBlocks.clear()

    private fun createSource(engine: MockEngine, sessionScope: CoroutineScope): RuleMediaSource {
        val client = HttpClient(engine).asScopedHttpClient()
        val arguments = RuleMediaSourceArguments(
            name = "test",
            rule = RuleConfig(
                baseUrl = "https://blocked.example.com",
                requestIntervalMillis = 0,
                search = listOf(
                    RuleStep.Fetch(url = "/search/{keyword}/"),
                    RuleStep.Subjects(list = listOf(".result")),
                ),
            ),
        )
        return RuleMediaSource(
            mediaSourceId = "test-source",
            config = MediaSourceConfig(
                serializedArguments = MediaSourceConfig.serializeArguments(RuleMediaSourceArguments.serializer(), arguments),
            ),
            client = client,
            repository = SelectorMediaSourceEpisodeCacheRepository(NoopWebSearchSessionCacheDao, flowOf(Duration.ZERO)),
            sessionManager = WebSessionManager(
                browserFactory = UnsupportedCaptchaBrowserFactory,
                evaluator = PageEvaluator(),
                cookieJar = WebSourceCookieJar(),
                identityRegistry = WebSourceIdentityRegistry(),
                client = client,
                backgroundScope = sessionScope,
            ),
        )
    }

    private fun request(episode: Int) = MediaFetchRequest(
        subjectId = "554779",
        episodeId = episode.toString(),
        subjectNames = listOf("学生会也有洞！"),
        episodeSort = EpisodeSort(episode),
        episodeName = "",
        fallbackSearchKeywords = listOf("生徒会にも穴はある！"),
    )

    @Test
    fun `a 456 block page stops the search and nothing is sent while the site cools down`() = runTest {
        var requests = 0
        val engine = MockEngine {
            requests++
            respond(
                """<script src="/_guard/html.js?js=p456"></script>""",
                HttpStatusCode(456, "Blocked"),
                headersOf(HttpHeaders.ContentType, ContentType.Text.Html.toString()),
            )
        }
        val sessionScope = CoroutineScope(SupervisorJob())
        val source = createSource(engine, sessionScope)
        try {
            val first = assertFailsWith<SiteIpBlockedException> { source.fetch(request(1)).results.toList() }
            assertEquals("blocked.example.com", first.host)
            // 剩下的名字不再试
            assertEquals(1, requests)

            // 换下一集: 冷却期内直接失败, 不发请求
            assertFailsWith<SiteIpBlockedException> { source.fetch(request(2)).results.toList() }
            assertEquals(1, requests)
        } finally {
            sessionScope.cancel()
        }
    }

    @Test
    fun `names that found nothing are not searched again for the next episode`() = runTest {
        var requests = 0
        val engine = MockEngine {
            requests++
            respond(
                "<html><body><p>没有找到相关内容</p></body></html>",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, ContentType.Text.Html.toString()),
            )
        }
        val sessionScope = CoroutineScope(SupervisorJob())
        val source = createSource(engine, sessionScope)
        try {
            assertEquals(emptyList(), source.fetch(request(1)).results.toList())
            // 条目名与回退关键词各搜一次
            assertEquals(2, requests)
            assertEquals(emptyList(), source.fetch(request(2)).results.toList())
            assertEquals(2, requests)
        } finally {
            sessionScope.cancel()
        }
    }

    @Test
    fun `only a block status or a clear block notice counts as a block page`() {
        assertTrue(isIpBlockPage(456, ""))
        assertTrue(isIpBlockPage(403, "<p>您的 IP 地址已被列入黑名单</p>"))
        assertFalse(isIpBlockPage(403, "<html><title>Just a moment...</title></html>"))
        assertFalse(isIpBlockPage(200, "<p>列入黑名单</p>"))
    }
}
