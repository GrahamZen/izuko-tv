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
import io.ktor.client.engine.mock.respondError
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
import me.him188.ani.app.domain.mediasource.web.BlockReason
import me.him188.ani.app.domain.mediasource.web.BlockedException
import me.him188.ani.app.domain.mediasource.web.NoopWebSearchSessionCacheDao
import me.him188.ani.app.domain.mediasource.web.PageEvaluator
import me.him188.ani.app.domain.mediasource.web.WebCaptchaKind
import me.him188.ani.app.domain.mediasource.web.captcha.UnsupportedCaptchaBrowserFactory
import me.him188.ani.app.domain.mediasource.web.captcha.WebSessionManager
import me.him188.ani.app.domain.mediasource.web.captcha.WebSourceCookieJar
import me.him188.ani.app.domain.mediasource.web.captcha.WebSourceIdentityRegistry
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.source.MediaFetchRequest
import me.him188.ani.datasources.api.source.MediaSourceConfig
import me.him188.ani.datasources.api.source.serializeArguments
import me.him188.ani.utils.ktor.asScopedHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration

/**
 * 搜索页被苹果 CMS 验证码挡住时的处理. 这类站点限「3 秒内只能搜一次」, 而且先记搜索时间再出验证页.
 */
class RuleMediaSourceCaptchaTest {
    /** 搜索页一律要验证码; 距上一次搜索不到 3 秒时给冷却页. */
    private class MacCmsSite(private val clock: () -> Long) {
        val searchTimes = mutableListOf<Long>()
        val engine = MockEngine { request ->
            if (!request.url.encodedPath.startsWith("/search")) return@MockEngine respondError(HttpStatusCode.NotFound)
            val now = clock()
            val last = searchTimes.lastOrNull()
            searchTimes += now
            val html = if (last != null && now - last < 3000) COOLDOWN_PAGE else VERIFY_PAGE
            respond(html, headers = headersOf(HttpHeaders.ContentType, ContentType.Text.Html.toString()))
        }
    }

    /**
     * 验证码会话的后台任务 (定时清理闲置会话) 不放进测试调度器: 那里的长延时会在等 HTTP 时把虚拟时钟一下推到几十小时后, 站点的冷却就测不出来了.
     */
    private fun createSource(engine: MockEngine, sessionScope: CoroutineScope): RuleMediaSource {
        val client = HttpClient(engine).asScopedHttpClient()
        val sessionManager = WebSessionManager(
            browserFactory = UnsupportedCaptchaBrowserFactory,
            evaluator = PageEvaluator(),
            cookieJar = WebSourceCookieJar(),
            identityRegistry = WebSourceIdentityRegistry(),
            client = client,
            backgroundScope = sessionScope,
        )
        val arguments = RuleMediaSourceArguments(
            name = "test",
            rule = RuleConfig(
                baseUrl = "https://example.com",
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
            sessionManager = sessionManager,
        )
    }

    @Test
    fun `captcha on the search page is reported as captcha not rate limit`() = runTest {
        val site = MacCmsSite { testScheduler.currentTime }
        val sessionScope = CoroutineScope(SupervisorJob())
        val source = createSource(site.engine, sessionScope)
        val request = MediaFetchRequest(
            subjectId = "8546",
            episodeId = "1",
            subjectNames = listOf("出包王女"),
            episodeSort = EpisodeSort(1),
            episodeName = "",
        )

        val e = try {
            assertFailsWith<BlockedException> { source.fetch(request).results.toList() }
        } finally {
            sessionScope.cancel()
        }

        // 再请求一次前等过了搜索间隔, 拿到的仍是验证页, 界面才会让用户去验证
        assertEquals(WebCaptchaKind.Image, assertIs<BlockReason.Captcha>(e.reason).kind)
        assertEquals(2, site.searchTimes.size)
        assertTrue(site.searchTimes[1] - site.searchTimes[0] >= 3000, site.searchTimes.toString())
    }

    private companion object {
        val VERIFY_PAGE = """
            <html><body><div class="verify-box">
            <input placeholder="请输入验证码" type="text" class="ds-verify" name="verify" value="">
            <img class="ds-verify-img" src="/verify/index.html">
            <button class="button verify-submit" data-type="search">提交验证</button>
            </div></body></html>
        """.trimIndent()

        val COOLDOWN_PAGE = """
            <html><body><div class="msg-jump">
            <p>请不要频繁操作，搜索时间间隔为3秒</p>
            <p>页面自动 <a href="javascript:history.back(-1);">跳转</a></p>
            </div></body></html>
        """.trimIndent()
    }
}
