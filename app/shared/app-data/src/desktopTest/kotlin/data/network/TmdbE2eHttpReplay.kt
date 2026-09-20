/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.network

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.call.HttpClientCall
import io.ktor.client.call.save
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.RedirectResponseException
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.ServerResponseException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.plugin
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.request
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.him188.ani.app.domain.foundation.ScopedHttpClientFeatureHandler
import me.him188.ani.app.domain.foundation.ScopedHttpClientUserAgent
import me.him188.ani.app.domain.foundation.UserAgentFeature
import me.him188.ani.app.domain.foundation.UserAgentFeatureHandler
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * E2E 锚点测试的 **HTTP 录制重放层**: 真实响应落盘, 之后重放 —— 跑的仍是真实
 * [TmdbImageService] 判定逻辑, 只是网络换成录制的响应. 这是"日常快筛"与"commit 前终验"
 * 共用同一份实现的关键 (离线模拟器是第二份实现, 历史上两个真 bug 都是"模拟器绿、实现红").
 *
 * - `ANI_TMDB_E2E=1`: 读缓存, miss 才发真实请求并补录 (改规则新增的查询词自动补录).
 *   全命中时零网络零睡眠, 全量锚点从 ~8 分钟降到十几秒.
 * - `ANI_TMDB_E2E=fresh`: 每个 URL 本轮第一次遇到时忽略旧缓存、真实请求并重新录制
 *   (commit 前终验, 捕捉 TMDB 服务端数据漂移); 同一 URL 本轮再次遇到直接用刚录的.
 *
 * **对 api.bgm.tv 的节流下沉到这里**: 只有真实请求才需要让路 (间隔 >= 1.2s), 重放不用.
 * 测试主体里不再 sleep.
 *
 * 挂载方式: DefaultHttpClientProvider 只对"被请求的 feature"调 handler, 测试塞不进私有
 * feature, 所以顶替一定会被请求的 [UserAgentFeatureHandler] —— 委托原实现, 只在
 * [applyToClient] 里往 [HttpSend] 最外层加拦截.
 *
 * 语义对齐的两个点 (不对齐重放就测出另一套行为):
 * - 生产 client `expectSuccess = true`, 非 2xx 在内层 validator 抛 [ResponseException];
 *   录制与重放统一从 [replay] 按 status 分段抛同类型异常.
 * - 录制的是 redirect 已 follow 的最终响应.
 */
internal object TmdbE2eReplayUserAgentFeatureHandler :
    ScopedHttpClientFeatureHandler<ScopedHttpClientUserAgent>(UserAgentFeature) {

    private val cacheDir = File(System.getProperty("user.home"), ".ani-dev/tmdb-e2e-cache")
    private val fresh: Boolean get() = System.getenv("ANI_TMDB_E2E") == "fresh"

    /** fresh 模式下本轮已重新录制过的 URL: 再遇到直接用新录的, 别重复打真实请求. */
    private val refreshedThisRun = ConcurrentHashMap.newKeySet<String>()

    @Serializable
    private data class Entry(
        val url: String,
        val status: Int,
        val contentType: String? = null,
        val bodyBase64: String = "",
    )

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 旁路渲染录制响应用的 client. 返回的 call 属于它, 后续 body 反序列化走它的管道,
     * 所以 JSON 配置必须与 createDefaultHttpClient 一致 (这条链三个 host 全是 JSON).
     */
    private val replayClient by lazy {
        HttpClient(
            MockEngine {
                val entry = pendingReplay ?: error("重放槽是空的 (只能经 replay() 调用)")
                respond(
                    content = Base64.getDecoder().decode(entry.bodyBase64),
                    status = HttpStatusCode.fromValue(entry.status),
                    headers = entry.contentType?.let { headersOf(HttpHeaders.ContentType, it) }
                        ?: headersOf(),
                )
            },
        ) {
            expectSuccess = false // 非 2xx 由 replay() 按生产语义抛, 不能在这里先炸
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; isLenient = true })
            }
        }
    }

    /** 渲染是"放槽 -> 请求"两步, 锁住防并发串味. */
    private val replayLock = Mutex()
    private var pendingReplay: Entry? = null

    /** 给 api.bgm.tv 让路: 真实请求彼此间隔 >= 1.2s, 防 429 熔断 (它按 IP 限流). */
    private val bgmThrottle = Mutex()
    private var lastBgmRequestAt = 0L

    override fun applyToConfig(config: HttpClientConfig<*>, value: ScopedHttpClientUserAgent) =
        UserAgentFeatureHandler.applyToConfig(config, value)

    override fun applyToClient(client: HttpClient, value: ScopedHttpClientUserAgent) {
        client.plugin(HttpSend).intercept { request ->
            if (request.method != HttpMethod.Get) return@intercept execute(request)
            val urlString = request.url.buildString()
            val file = File(cacheDir, sha1(urlString) + ".json")

            val canUseCache = file.isFile && (!fresh || urlString in refreshedThisRun)
            if (canUseCache) {
                return@intercept replay(json.decodeFromString(Entry.serializer(), file.readText()))
            }

            if (request.url.host == "api.bgm.tv") {
                bgmThrottle.withLock {
                    val wait = 1200 - (System.currentTimeMillis() - lastBgmRequestAt)
                    if (wait > 0) Thread.sleep(wait) // runTest 会跳过 delay, 只能真睡
                    lastBgmRequestAt = System.currentTimeMillis()
                }
            }

            try {
                val saved = execute(request).save()
                record(
                    file, urlString,
                    Entry(
                        url = urlString,
                        status = saved.response.status.value,
                        contentType = saved.response.headers[HttpHeaders.ContentType],
                        bodyBase64 = Base64.getEncoder().encodeToString(saved.response.bodyAsBytes()),
                    ),
                )
                saved
            } catch (e: ResponseException) {
                // validator 已把 body 读掉, 只录 status —— 真实路径上异常后 body 同样不可再读
                val entry = Entry(url = urlString, status = e.response.status.value)
                record(file, urlString, entry)
                replay(entry) // 统一从重放路径抛, 保证录制与重放行为一致
            }
        }
    }

    private suspend fun replay(entry: Entry): HttpClientCall = replayLock.withLock {
        pendingReplay = entry
        val response = try {
            replayClient.request(HttpRequestBuilder().apply { url(entry.url) })
        } finally {
            pendingReplay = null
        }
        // 对齐生产 client 的 expectSuccess = true 语义
        when (response.status.value) {
            in 300..399 -> throw RedirectResponseException(response, response.bodyAsText())
            in 400..499 -> throw ClientRequestException(response, response.bodyAsText())
            in 500..599 -> throw ServerResponseException(response, response.bodyAsText())
        }
        response.call
    }

    private fun record(file: File, urlString: String, entry: Entry) {
        cacheDir.mkdirs()
        file.writeText(json.encodeToString(Entry.serializer(), entry))
        refreshedThisRun.add(urlString)
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
