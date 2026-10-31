/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.danmaku.dandanplay.server

import io.ktor.client.plugins.expectSuccess
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.accept
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.io.readByteArray
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.him188.ani.danmaku.api.DanmakuContent
import me.him188.ani.utils.ktor.ScopedHttpClient
import me.him188.ani.utils.ktor.hideUrlInLog
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration

/** 这台服务器没有这个接口 (HTTP 404 / 405 / 501). */
class DanmakuServerRouteMissingException(route: String) : Exception("Danmaku server has no route: $route")

/**
 * 请求弹幕服务器失败: 连不上、HTTP 错误, 或服务器回了 `success: false`.
 *
 * 消息里没有服务器地址: 地址里带着访问令牌, 而这个异常会进日志.
 *
 * @param serverMessage 服务器自己给的错误说明, 可以给用户看
 */
class DanmakuServerException(message: String, val serverMessage: String? = null) : Exception(message)

/**
 * 弹弹 play 兼容接口 (`<baseUrl>/api/v2/...`) 的客户端. 服务器由用户自己填, 所以不带弹弹 play 的 AppId 与签名, 响应也按最宽松的方式解析:
 * 各家实现的字段不尽相同, 这里只取用得到的几个, 其余都可以没有.
 */
internal class DanmakuServerClient(
    private val client: ScopedHttpClient,
    private val name: String,
    private val baseUrl: String,
    private val ioDispatcher: CoroutineContext = Dispatchers.IO,
) {
    suspend fun match(fileName: String, fileSize: Long?, videoDuration: Duration?): ServerMatchResponse {
        val response = request("match", MATCH_TIMEOUT_MILLIS, ServerMatchResponse.serializer(), HttpMethod.Post) {
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("fileName", fileName)
                    fileSize?.let { put("fileSize", it) }
                    videoDuration?.let { put("videoDuration", it.inWholeSeconds) }
                    put("matchMode", "fileNameOnly")
                }.toString(),
            )
        }
        checkSuccess("match", response.success, response.errorMessage)
        return response
    }

    suspend fun searchEpisodes(anime: String, episode: Int): List<ServerAnimeEpisodes> {
        val response = request("search/episodes", SEARCH_TIMEOUT_MILLIS, ServerSearchEpisodes.serializer()) {
            parameter("anime", anime)
            parameter("episode", episode)
        }
        checkSuccess("search/episodes", response.success, response.errorMessage)
        return response.animes
    }

    suspend fun searchAnime(keyword: String): List<ServerAnime> {
        val response = request("search/anime", SEARCH_TIMEOUT_MILLIS, ServerSearchAnime.serializer()) {
            parameter("keyword", keyword)
        }
        checkSuccess("search/anime", response.success, response.errorMessage)
        return response.animes
    }

    suspend fun bangumiEpisodes(bangumiId: String): List<ServerEpisode> {
        val response = request("bangumi/$bangumiId", SEARCH_TIMEOUT_MILLIS, ServerBangumiResponse.serializer()) {}
        checkSuccess("bangumi", response.success, response.errorMessage)
        return response.bangumi?.episodes.orEmpty()
    }

    suspend fun comments(episodeId: Long): List<DanmakuContent> = requestComments("comment/$episodeId") {
        commentParameters()
    }

    /**
     * 这一集有几条弹幕, 用来在同一集的几个来源里挑. 弹弹 play 的格式把条数放在回应最前面
     * (`{"count":24292,"comments":[...`), 读到就断开: 几万条弹幕不下载也不解析. 开头没有条数的服务器才整份读完数一遍.
     *
     * 和 [comments] 发的是同一个请求, 服务器为它准备好的弹幕随后取全文时用得上.
     */
    suspend fun commentCount(episodeId: Long): Int {
        val route = "comment/$episodeId"
        return send(route, COMMENT_TIMEOUT_MILLIS, HttpMethod.Get, { commentParameters() }) { response ->
            val channel = response.bodyAsChannel()
            val head = channel.readRemaining(COUNT_HEAD_BYTES).readByteArray()
            leadingCommentCount(head.decodeToString()) ?: run {
                val text = (head + channel.readRemaining().readByteArray()).decodeToString()
                val list = decode(route, text, ServerCommentList.serializer())
                checkSuccess(route, list.success, list.errorMessage)
                list.count ?: list.comments.size
            }
        }
    }

    private fun HttpRequestBuilder.commentParameters() {
        parameter("withRelated", true)
        parameter("chConvert", 0)
    }

    suspend fun externalComments(url: String): List<DanmakuContent> = requestComments("extcomment") {
        parameter("url", url)
        parameter("chConvert", 0)
    }

    /** 解析在 [ioDispatcher] 上做: 一集几万条弹幕要解好几秒, 别和开播时的选源抢 Default 线程. */
    private suspend fun requestComments(route: String, block: HttpRequestBuilder.() -> Unit): List<DanmakuContent> {
        val text = requestText(route, COMMENT_TIMEOUT_MILLIS, HttpMethod.Get, block)
        return withContext(ioDispatcher) {
            val response = decode(route, text, ServerCommentList.serializer())
            checkSuccess(route, response.success, response.errorMessage)
            response.comments.mapNotNull { it.toContentOrNull() }
        }
    }

    /** 不是所有服务器都有版本接口, 这里只回答「连得上吗」. */
    suspend fun version() {
        val response = request("version", PROBE_TIMEOUT_MILLIS, ServerBaseResponse.serializer()) {}
        checkSuccess("version", response.success, response.errorMessage)
    }

    private fun checkSuccess(route: String, success: Boolean, errorMessage: String?) {
        if (!success) throw DanmakuServerException("$route failed on $name", errorMessage?.takeIf { it.isNotBlank() })
    }

    private fun errorMessageOf(body: String): String? = try {
        danmakuServerJson.decodeFromString(ServerBaseResponse.serializer(), body).errorMessage?.takeIf { it.isNotBlank() }
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private suspend fun <T> request(
        route: String,
        timeoutMillis: Long,
        deserializer: DeserializationStrategy<T>,
        method: HttpMethod = HttpMethod.Get,
        block: HttpRequestBuilder.() -> Unit,
    ): T {
        val text = requestText(route, timeoutMillis, method, block)
        return withContext(ioDispatcher) { decode(route, text, deserializer) }
    }

    private fun <T> decode(route: String, text: String, deserializer: DeserializationStrategy<T>): T = try {
        danmakuServerJson.decodeFromString(deserializer, text)
    } catch (e: SerializationException) {
        throw DanmakuServerException("Unexpected response from $name: $route: ${e::class.simpleName}")
    } catch (e: IllegalArgumentException) {
        throw DanmakuServerException("Unexpected response from $name: $route: ${e::class.simpleName}")
    }

    private suspend fun requestText(
        route: String,
        timeoutMillis: Long,
        method: HttpMethod,
        block: HttpRequestBuilder.() -> Unit,
    ): String = send(route, timeoutMillis, method, block) { it.bodyAsText() }

    /**
     * 发请求, 成功的回应交给 [read] 读; [read] 返回时连接就断开, 没读的部分不再下载.
     * 失败统一换成消息里不带地址的异常.
     */
    private suspend fun <R> send(
        route: String,
        timeoutMillis: Long,
        method: HttpMethod,
        block: HttpRequestBuilder.() -> Unit,
        read: suspend (HttpResponse) -> R,
    ): R = withContext(ioDispatcher) {
        try {
            client.use {
                prepareRequest("$baseUrl/api/v2/$route") {
                    this.method = method
                    expectSuccess = false
                    hideUrlInLog()
                    timeout { requestTimeoutMillis = timeoutMillis }
                    accept(ContentType.Application.Json)
                    block()
                }.execute { response ->
                    if (response.status in MISSING_ROUTE_STATUS) throw DanmakuServerRouteMissingException(route)
                    if (!response.status.isSuccess()) {
                        throw DanmakuServerException(
                            "HTTP ${response.status.value} from $name: $route",
                            errorMessageOf(response.bodyAsText()),
                        )
                    }
                    read(response)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: DanmakuServerRouteMissingException) {
            throw e
        } catch (e: DanmakuServerException) {
            throw e
        } catch (e: Exception) {
            // 网络库的消息带完整地址, 地址里有访问令牌
            throw DanmakuServerException(
                "Request to $name failed: $route: ${e::class.simpleName}: ${e.message?.replace(baseUrl, "<server>")}",
            )
        }
    }

    private companion object {
        const val PROBE_TIMEOUT_MILLIS = 10_000L

        /** 没缓存时服务器要现搜各站点, 实测十几秒 */
        const val SEARCH_TIMEOUT_MILLIS = 30_000L

        /** 库里没有时服务器可能现搜现导入, 御坂默认最多等 60 秒才回 */
        const val MATCH_TIMEOUT_MILLIS = 75_000L
        const val COMMENT_TIMEOUT_MILLIS = 45_000L

        /** 读条数时只读这么多: 条数前面最多还有 `success`、`errorCode` 这几个短字段 */
        const val COUNT_HEAD_BYTES = 512L

        val MISSING_ROUTE_STATUS = setOf(
            HttpStatusCode.NotFound,
            HttpStatusCode.MethodNotAllowed,
            HttpStatusCode.NotImplemented,
        )
    }
}

/** 后面跟着逗号或 `}` 才算读全了数字, 免得开头恰好截在数字中间 */
private val COUNT_FIELD = Regex(""""count"\s*:\s*(\d+)\s*[,}]""")

/**
 * 弹幕回应开头的条数. 只认弹幕列表 (第一个 `[`) 之前的 `count`: 列表里的弹幕内容不算;
 * 开头没有时返回 `null`.
 */
internal fun leadingCommentCount(head: String): Int? {
    val match = COUNT_FIELD.find(head) ?: return null
    val listStart = head.indexOf('[')
    if (listStart in 0..<match.range.first) return null
    return match.groupValues[1].toIntOrNull()
}

internal val danmakuServerJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}

@Serializable
internal class ServerBaseResponse(
    val success: Boolean = true,
    val errorMessage: String? = null,
)

@Serializable
internal class ServerMatchResponse(
    val success: Boolean = true,
    val errorMessage: String? = null,
    val isMatched: Boolean = false,
    val matches: List<ServerMatch> = emptyList(),
)

@Serializable
internal class ServerMatch(
    val episodeId: Long,
    val animeTitle: String? = null,
    val episodeTitle: String? = null,
)

@Serializable
internal class ServerSearchEpisodes(
    val success: Boolean = true,
    val errorMessage: String? = null,
    val animes: List<ServerAnimeEpisodes> = emptyList(),
)

@Serializable
internal class ServerAnimeEpisodes(
    val animeTitle: String? = null,
    val episodes: List<ServerEpisode> = emptyList(),
)

@Serializable
internal class ServerEpisode(
    val episodeId: Long,
    val episodeTitle: String? = null,
    val episodeNumber: String? = null,
    val episodeIndex: Int? = null,
)

@Serializable
internal class ServerSearchAnime(
    val success: Boolean = true,
    val errorMessage: String? = null,
    val animes: List<ServerAnime> = emptyList(),
)

@Serializable
internal class ServerAnime(
    val animeId: Long? = null,
    val bangumiId: String? = null,
    val animeTitle: String? = null,
)

@Serializable
internal class ServerBangumiResponse(
    val success: Boolean = true,
    val errorMessage: String? = null,
    val bangumi: ServerBangumi? = null,
)

@Serializable
internal class ServerBangumi(
    val episodes: List<ServerEpisode> = emptyList(),
)

@Serializable
internal class ServerCommentList(
    val success: Boolean = true,
    val errorMessage: String? = null,
    val count: Int? = null,
    val comments: List<ServerComment> = emptyList(),
)

@Serializable
internal class ServerComment(
    val p: String? = null,
    val m: String? = null,
) {
    fun toContentOrNull(): DanmakuContent? = parseDanmakuAttributes(p ?: return null, m ?: return null)
}
