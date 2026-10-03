/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.utils.ktor

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.plugins.BrowserUserAgent
import io.ktor.client.plugins.HttpRedirect
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.plugin
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.HttpSendPipeline
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.serialization.ContentConverter
import io.ktor.serialization.kotlinx.json.json
import io.ktor.util.AttributeKey
import io.ktor.util.pipeline.PipelinePhase
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import me.him188.ani.utils.ktor.HttpLogger.logHttp
import me.him188.ani.utils.logging.Logger
import me.him188.ani.utils.logging.error
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.measureTimedValue

// 根据不同平台，选择相应的 HttpClientEngine
expect fun getPlatformKtorEngine(): HttpClientEngineFactory<*>

/**
 * Note: 尽可能使用 `HttpClientProvider` 来共享 [HttpClient] 实例. 因为每个实例都潜在地会有一个线程池.
 */
fun createDefaultHttpClient(
    clientConfig: HttpClientConfig<*>.() -> Unit = {},
): HttpClient = HttpClient(getPlatformKtorEngine()) {
    install(HttpRequestRetry) {
        maxRetries = 1
        delayMillis { 1000 }
        retryIf { cause, response ->
            // 只重试网络异常
            cause is IOException
        }
    }
    install(HttpCookies)
    install(HttpTimeout) {
        requestTimeoutMillis = 300_000
        connectTimeoutMillis = 30_000
        socketTimeoutMillis = 30_000
    }
    BrowserUserAgent()
    install(ContentNegotiation) {
        val xmlConverter = getXmlConverter()
        json(
            Json {
                ignoreUnknownKeys = true
                isLenient = true
            },
        )
        register(ContentType.Text.Html, xmlConverter)
        register(ContentType.Text.Xml, xmlConverter)
    }
    followRedirects = true
    install(HttpRedirect) {
        checkHttpMethod = false
        allowHttpsDowngrade = true
    }
    expectSuccess = true // All clients actually expect success by default in clientConfig, so we move them here
    clientConfig()
}.also { it.installRawCookieHeader() }

/**
 * 让 [rawCookieHeader] 生效: [HttpCookies] 在发送管线的 State 阶段按它的存储重写 (或去掉) Cookie 头, 这里紧接着在它之后照请求属性写回.
 * (挂成 [HttpSend] 的拦截器不行: 那些在请求管线上, 发送管线在它们之后才跑.)
 */
fun HttpClient.installRawCookieHeader() {
    val phase = PipelinePhase("AniRawCookieHeader")
    sendPipeline.insertPhaseAfter(HttpSendPipeline.State, phase)
    sendPipeline.intercept(phase) {
        context.attributes.getOrNull(RawCookieHeaderAttribute)?.let { context.headers[HttpHeaders.Cookie] = it }
    }
}

/**
 * 见 [rawCookieHeader].
 */
val RawCookieHeaderAttribute = AttributeKey<String>("AniRawCookieHeader")

/**
 * 这个请求带上 [value] 作为 Cookie 头, 原样发出去.
 *
 * [createDefaultHttpClient] 的 client 装了 [HttpCookies]: 请求里手写的 Cookie 头会被它存进 client 共用的存储、再按存储重新拼一遍,
 * 拼出来的不对 (夸克网盘的直链因此一律 412, 缓存下载一直失败). 用这个代替 `header(HttpHeaders.Cookie, …)`:
 * 不经它, 也不进共用的存储.
 */
fun HttpRequestBuilder.rawCookieHeader(value: String) {
    attributes.put(RawCookieHeaderAttribute, value)
}

fun HttpClient.registerLogging(
    logger: Logger = logger("ktor"),
) {
    plugin(HttpSend).intercept { request ->
        val (result, duration) = measureTimedValue {
            kotlin.runCatching { execute(request) }
        }

        logger.logHttp(
            method = request.method,
            url = request.url.toString(),
            isAuthorized = request.headers.contains(HttpHeaders.Authorization),
            responseStatus = result.map { it.response.status },
            duration = duration,
        )
        result.getOrThrow()
    }
}

object HttpLogger {
    fun Logger.logHttp(
        method: HttpMethod,
        url: String,
        isAuthorized: Boolean,
        responseStatus: Result<HttpStatusCode>,
        duration: Duration,
    ) {
        when {
            // 刻意没记录 exception, 因为外面应该会处理
            responseStatus.isFailure -> {
                if (responseStatus.exceptionOrNull() is CancellationException) {
                    warn { buildHttpRequestLog(method, url, isAuthorized, responseStatus, duration) }
                } else {
                    error { buildHttpRequestLog(method, url, isAuthorized, responseStatus, duration) }
                }
            }

            responseStatus.getOrNull()?.isSuccess() == true ->
                info { buildHttpRequestLog(method, url, isAuthorized, responseStatus, duration) }

            else -> warn { buildHttpRequestLog(method, url, isAuthorized, responseStatus, duration) }
        }
    }

    private fun buildHttpRequestLog(
        method: HttpMethod,
        url: String,
        isAuthorized: Boolean,
        responseStatus: Result<HttpStatusCode>,
        duration: Duration,
    ): String {
        val methodStr = method.value.padStart(5, ' ')
        return buildString {
            append(methodStr)
            append(" ")
            append(url)
            append(" ")
            if (isAuthorized) {
                append("[Authorized]")
            }

            append(": ")

            responseStatus.fold(
                onSuccess = {
                    append(it.toString()) // "404 Not Found"
                },
                onFailure = { exception ->
                    when (exception) {
                        is CancellationException -> append("CANCELLED")
                        is IOException -> append("IO_EXCEPTION")
                        else -> append("FAILED")
                    }
                    if (exception !is CancellationException) {
                        // 光一个 "IO_EXCEPTION" 区分不出 DNS 解析失败 / 连接超时 / TLS 失败 / 代理拒绝,
                        // 用户导出的日志里就无从定位, 所以带上原因摘要.
                        append(" (")
                        append(exception.summarizeForLog())
                        append(")")
                    }
                },
            )

            append(" in ")
            append(duration.toString())
        }
    }

    /**
     * `java.net.UnknownHostException: Unable to resolve host "x"`, 必要时附上根因.
     */
    private fun Throwable.summarizeForLog(): String {
        val root = generateSequence(this) { current -> current.cause?.takeIf { it !== current } }.last()
        val self = toString().take(MAX_EXCEPTION_LOG_LENGTH)
        return if (root === this) self else "$self, cause: ${root.toString().take(MAX_EXCEPTION_LOG_LENGTH)}"
    }

    private const val MAX_EXCEPTION_LOG_LENGTH = 200
}


internal expect fun getXmlConverter(): ContentConverter
