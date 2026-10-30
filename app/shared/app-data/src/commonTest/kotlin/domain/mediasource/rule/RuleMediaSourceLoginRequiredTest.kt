/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.rule

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
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.data.repository.media.SelectorMediaSourceEpisodeCacheRepository
import me.him188.ani.app.domain.media.TestMediaList
import me.him188.ani.app.domain.media.resolver.MediaResolutionException
import me.him188.ani.app.domain.media.resolver.ResolutionFailures
import me.him188.ani.app.domain.mediasource.web.InMemoryWebSearchSessionCacheDao
import me.him188.ani.datasources.api.matcher.WebVideoDirectResolver
import me.him188.ani.datasources.api.matcher.WebVideoMatcherContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.hours

class RuleMediaSourceLoginRequiredTest {
    @Test
    fun `a play api that asks for login fails the resolution without falling back to webview`() = runTest {
        val engine = MockEngine { request ->
            val json = when (request.url.encodedPath) {
                "/api/episode/1/play" -> """{"code":200,"message":"操作成功","data":{"canPlay":false,"message":"请先登录","playUrl":null}}"""
                "/api/episode/2/play" -> """{"code":200,"message":"操作成功","data":{"canPlay":false,"message":"请求来源不受信任","playUrl":null}}"""
                "/api/episode/3/play" -> """{"code":200,"message":"操作成功","data":{"canPlay":true,"playUrl":"https://v.example.com/3.m3u8"}}"""
                else -> return@MockEngine respondError(HttpStatusCode.NotFound)
            }
            respond(json, headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }
        val sessionScope = CoroutineScope(SupervisorJob())
        try {
            val source = createTestRuleMediaSource(
                engine, SelectorMediaSourceEpisodeCacheRepository(InMemoryWebSearchSessionCacheDao(), flowOf(1.hours)), sessionScope,
                play = listOf(RuleStep.Fetch(), RuleStep.Regex(pattern = """"playUrl"\s*:\s*"([^"]+)"""")),
            )
            val resolver = source.matcher as WebVideoDirectResolver
            val context = WebVideoMatcherContext(TestMediaList.first())

            // 要登录: 这次开播直接算失败, 换下一个资源
            val error = assertFailsWith<MediaResolutionException> {
                resolver.resolveDirectly("https://example.com/api/episode/1/play", context)
            }
            assertEquals(ResolutionFailures.NO_MATCHING_RESOURCE, error.reason)
            // 别的原因没取到: 照旧退回 WebView
            assertNull(resolver.resolveDirectly("https://example.com/api/episode/2/play", context))
            assertEquals("https://v.example.com/3.m3u8", resolver.resolveDirectly("https://example.com/api/episode/3/play", context)?.m3u8Url)
        } finally {
            sessionScope.cancel()
        }
    }
}
