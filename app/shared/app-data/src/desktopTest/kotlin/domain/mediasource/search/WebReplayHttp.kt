/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.search

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headers
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.him188.ani.utils.ktor.getPlatformKtorEngine
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * 网页类数据源离线录放 ([WebSearchReplayTest]) 用的站点回应: 每个请求 (方法 + 地址 + 请求体) 一个文件, 放在 [dir] 里.
 * [record] 时没录过的请求现场发出去再存下 (不跟随跳转: 跳转本身也录, 由数据源的客户端照常跟随); 否则回 599, 请求记进 [missing].
 */
internal class WebReplayHttp(private val dir: File, private val record: Boolean) {
    private val json = Json { ignoreUnknownKeys = true }

    private val real by lazy {
        HttpClient(getPlatformKtorEngine()) {
            followRedirects = false
            expectSuccess = false
            install(HttpTimeout) {
                requestTimeoutMillis = 30_000
                connectTimeoutMillis = 15_000
            }
        }
    }

    /** 数据里还没有的请求 (只在不录时记) */
    val missing: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Serializable
    private class Stored(
        val key: String,
        val status: Int,
        val headers: Map<String, List<String>> = emptyMap(),
        val body: String,
    )

    /** 一个数据源用的引擎: 每个请求按 「方法 地址」 记进 [log]. */
    fun engine(log: MutableList<String>): MockEngine = MockEngine { request ->
        val body = request.bodyBytes()
        val key = buildString {
            append(request.method.value).append(' ').append(request.url.toString())
            if (body.isNotEmpty()) append(" #").append(sha1(body))
        }
        synchronized(log) { log += "${request.method.value} ${request.url}" }
        val stored = load(key) ?: if (record) fetchAndStore(key, request, body) else null
        if (stored == null) {
            missing += key
            return@MockEngine respond(ByteArray(0), HttpStatusCode(599, "not recorded"))
        }
        respond(
            Base64.getDecoder().decode(stored.body),
            HttpStatusCode.fromValue(stored.status),
            headers { stored.headers.forEach { (name, values) -> appendAll(name, values) } },
        )
    }

    private fun fileOf(key: String) = File(dir, sha1(key.encodeToByteArray()) + ".json")

    private fun load(key: String): Stored? {
        val file = fileOf(key).takeIf { it.exists() } ?: return null
        return json.decodeFromString(Stored.serializer(), file.readText(Charsets.UTF_8))
    }

    private suspend fun fetchAndStore(key: String, request: HttpRequestData, body: ByteArray): Stored? {
        val response = try {
            real.request(request.url) {
                method = HttpMethod.parse(request.method.value)
                for ((name, values) in request.headers.entries()) {
                    if (name.equals(HttpHeaders.AcceptEncoding, ignoreCase = true)) continue
                    headers.appendAll(name, values)
                }
                if (body.isNotEmpty()) setBody(ByteArrayContent(body, request.body.contentType))
            }
        } catch (e: Exception) {
            println("record failed: $key: $e")
            return null
        }
        val kept = response.headers.entries()
            .filter { (name, _) -> name.lowercase() in KEPT_HEADERS }
            .associate { (name, values) -> name to values }
        val stored = Stored(key, response.status.value, kept, Base64.getEncoder().encodeToString(response.readRawBytes()))
        dir.mkdirs()
        fileOf(key).writeText(json.encodeToString(Stored.serializer(), stored), Charsets.UTF_8)
        return stored
    }

    private fun HttpRequestData.bodyBytes(): ByteArray = when (val content = body) {
        is OutgoingContent.ByteArrayContent -> content.bytes()
        else -> ByteArray(0)
    }

    private companion object {
        val KEPT_HEADERS = setOf("content-type", "location", "set-cookie")

        fun sha1(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
