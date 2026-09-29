/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.foundation

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 回落判据: **什么样的失败才算"这一家不通"**.
 *
 * 分档搞错的代价是不对称的 —— 判松了, 一个能直连的用户会因为一次 401 被推到第三方反代上, 而那条路
 * 按设计不带凭证 (见 [BangumiMirrorFeature] 的说明), 于是他之后的登录与收藏同步一直失败, 并且不知道
 * 自己在走反代. 2026-09-22 真机上就是这么炸的: 未登录时探索页并发要收藏计数, 5 个 401 连着把会话
 * 推到了镜像上并粘住半小时.
 */
class BangumiMirrorFallbackTest {
    private val routing = BangumiRouting(
        mirrors = listOf("bangumi.pro"),
        trusted = false,
        preferDirect = true,
    )

    private class Outcome(val hosts: List<String>, val status: HttpStatusCode)

    /**
     * 往 `api.bgm.tv` 发 [times] 次请求.
     *
     * @param originStatus 原站的响应; `null` = 连不上 (抛 [IOException])
     * @return 依次打到的 host, 以及最后一次的响应码
     */
    private suspend fun request(
        originStatus: HttpStatusCode?,
        times: Int = 1,
    ): Outcome {
        val hosts = mutableListOf<String>()
        val engine = MockEngine { req ->
            hosts += req.url.host
            if (req.url.host.endsWith("bgm.tv")) {
                if (originStatus == null) throw IOException("blocked")
                respond("", originStatus)
            } else {
                respond("", HttpStatusCode.OK)
            }
        }
        val client = HttpClient(engine) { expectSuccess = false }
        // 每个用例一个新 handler: 粘性是实例状态
        BangumiMirrorFeatureHandler(flowOf(routing)).applyToClient(client, true)
        var status = HttpStatusCode.OK
        repeat(times) { status = client.get("https://api.bgm.tv/v0/me").status }
        return Outcome(hosts, status)
    }

    @Test
    fun `原站回 401 是它在回答，不换镜像`() = runTest {
        val outcome = request(HttpStatusCode.Unauthorized)
        assertEquals(listOf("api.bgm.tv"), outcome.hosts)
        assertEquals(HttpStatusCode.Unauthorized, outcome.status)
    }

    @Test
    fun `原站回 404 也不换镜像 — 换一家还是同一个答案`() = runTest {
        val outcome = request(HttpStatusCode.NotFound)
        assertEquals(listOf("api.bgm.tv"), outcome.hosts)
        assertEquals(HttpStatusCode.NotFound, outcome.status)
    }

    @Test
    fun `原站限流 429 同样不算不通`() = runTest {
        val outcome = request(HttpStatusCode.TooManyRequests)
        assertEquals(listOf("api.bgm.tv"), outcome.hosts)
    }

    @Test
    fun `401 不改粘性 — 下一次照样先试原站`() = runTest {
        // 判松了的真实后果就是这一条: 第二次请求直接从镜像开始
        val outcome = request(HttpStatusCode.Unauthorized, times = 2)
        assertEquals(listOf("api.bgm.tv", "api.bgm.tv"), outcome.hosts)
    }

    @Test
    fun `原站 5xx 才回落到镜像`() = runTest {
        val outcome = request(HttpStatusCode.BadGateway)
        assertEquals(listOf("api.bgm.tv", "api.bangumi.pro"), outcome.hosts)
        assertEquals(HttpStatusCode.OK, outcome.status)
    }

    @Test
    fun `原站连不上时回落到镜像，并粘住`() = runTest {
        val outcome = request(originStatus = null, times = 2)
        // 第一次: 原站 -> 镜像; 第二次: 粘性直接从镜像开始, 不再吃一次直连超时
        assertEquals(listOf("api.bgm.tv", "api.bangumi.pro", "api.bangumi.pro"), outcome.hosts)
        assertEquals(HttpStatusCode.OK, outcome.status)
    }

    /**
     * 镜像前面的反机器人验证页: 200 + 网页. 拿它当成功的话, 调用方解析 JSON 全失败, 而粘性把之后的请求全送到这家.
     *
     * @param originUp 每次请求时原站通不通
     */
    private suspend fun requestWithWebPageMirror(
        vararg originUp: Boolean,
        mirrorServesWebPage: (requestIndex: Int) -> Boolean = { true },
        onOriginUnreachable: () -> Unit = {},
    ): Outcome {
        val hosts = mutableListOf<String>()
        var index = 0
        val engine = MockEngine { req ->
            hosts += req.url.host
            if (req.url.host.endsWith("bgm.tv")) {
                if (!originUp[index]) throw IOException("blocked")
                respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            } else if (mirrorServesWebPage(index)) {
                respond("<!doctype html>", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html; charset=utf-8"))
            } else {
                respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        val client = HttpClient(engine) { expectSuccess = false }
        BangumiMirrorFeatureHandler(flowOf(routing), onOriginUnreachable = { onOriginUnreachable() })
            .applyToClient(client, true)
        var status = HttpStatusCode.OK
        for (i in originUp.indices) {
            index = i
            status = client.get("https://api.bgm.tv/v0/me").status
        }
        return Outcome(hosts, status)
    }

    @Test
    fun `镜像回网页不算回答 - 不粘住也不算原站连不上`() = runTest {
        var reported = false
        val outcome = requestWithWebPageMirror(false, false, onOriginUnreachable = { reported = true })
        // 第二次照样先试原站: 只回网页的镜像没有被粘住
        assertEquals(listOf("api.bgm.tv", "api.bangumi.pro", "api.bgm.tv", "api.bangumi.pro"), outcome.hosts)
        assertEquals(false, reported)
    }

    @Test
    fun `粘住的镜像开始回网页 - 换回原站`() = runTest {
        // 第一次原站不通、镜像正常 -> 粘在镜像; 第二次镜像只回网页, 原站已经通了
        val outcome = requestWithWebPageMirror(false, true, mirrorServesWebPage = { it == 1 })
        assertEquals(listOf("api.bgm.tv", "api.bangumi.pro", "api.bangumi.pro", "api.bgm.tv"), outcome.hosts)
        assertEquals(HttpStatusCode.OK, outcome.status)
    }
}
