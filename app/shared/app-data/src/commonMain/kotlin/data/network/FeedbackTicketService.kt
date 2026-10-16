/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.network

import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.him188.ani.app.data.models.feedback.FeedbackReply
import me.him188.ani.app.domain.foundation.CloudflareServiceTransport
import me.him188.ani.app.platform.DevSwitches
import me.him188.ani.app.platform.currentAniBuildConfig
import me.him188.ani.utils.ktor.ScopedHttpClient
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * 故障工单服务 (Cloudflare Worker, 代码在本仓库 `tools/ticket-worker/`) 的客户端: 开单、传附件、查处理结果、补充说明.
 * 开单与补充时先声明这条要传的附件 ([FileSpec]), 再逐个 [uploadFile].
 * 服务在私有工单仓库开 issue, 开单时给这台电视一个凭证 (token), 之后的操作都带它. 凭证只放请求头 / 请求体:
 * 请求地址会进应用日志, 日志又会打包上传、分享.
 *
 * 服务地址由 [endpoints] 给, 按顺序试 (连不上或服务出错换下一个); 为空时工单没开放. 怎么连见 [CloudflareServiceTransport].
 */
class FeedbackTicketService(
    private val client: () -> ScopedHttpClient,
    private val transport: CloudflareServiceTransport = CloudflareServiceTransport.DirectOnly,
    private val endpoints: () -> List<String>,
) {
    /** 有工单服务可用. */
    val available: Boolean get() = endpoints().isNotEmpty()

    /**
     * 开单要交的内容. 时间段 [windowFrom] / [windowTo] 是带时区的 ISO 时间, 原样写进 issue.
     * [perf] 是附上的性能诊断报告的概要 (每份一行, 写进 issue); [files] 是这条要传的附件.
     */
    class Draft(
        val device: String,
        val summary: String,
        val description: String,
        val env: Map<String, String>,
        val windowFrom: String,
        val windowTo: String,
        val perf: Map<String, String>? = null,
        val files: List<FileSpec> = emptyList(),
    )

    /** 要传的一个附件: [name] 见 [FeedbackUpload.name][me.him188.ani.app.data.models.feedback.FeedbackUpload.name], [size] 字节 (不知道为 0). */
    class FileSpec(val name: String, val size: Long)

    /** 补充说明的结果: 成功时 [prefix] 是这条附件名的前缀 (如 `r2`). */
    class ReplyResult(val outcome: Outcome, val prefix: String?)

    sealed interface CreateResult {
        data class Created(val issue: Int, val token: String) : CreateResult

        /** 这台电视被拉黑了. */
        data object Blocked : CreateResult

        /** 这台电视开着的单太多 ([issues]), 等处理完再提. */
        data class TooManyOpen(val issues: List<Int>) : CreateResult

        data object RateLimited : CreateResult

        /** 内容不合格 ([error] 是服务给的错误码, 如 `description_too_short`、`file_too_large`). */
        data class Rejected(val error: String) : CreateResult

        data object Unreachable : CreateResult

        data object Failed : CreateResult
    }

    /** 传附件与补充说明的结果. */
    enum class Outcome {
        OK,

        /** 附件已经传过 / 工单已经关了. */
        CONFLICT,

        /** 服务不收 (附件没声明过、太大、类型不对; 补充的内容不合格): 再传也一样. */
        REJECTED,
        RATE_LIMITED,
        FAILED,
        UNREACHABLE,
    }

    /** 查一张单; [since] 是上次回来的更新时间, 没变时只回 [Status.unchanged]. */
    class Query(val issue: Int, val token: String, val since: String?)

    /**
     * 一张单的处理结果. [state] 为 `open` / `closed`, 关了时 [reason] 为 `completed` / `not_planned`;
     * [replies]、[apks] 与 [digests] (GitHub 附件的 SHA-256, 按链接) 只在有变化时给 (否则 [unchanged]).
     * [error] 非空时其余都没有: `forbidden` (凭证不对) / `not_found` (单不在了) / `upstream` (这次没查成).
     */
    class Status(
        val issue: Int,
        val state: String?,
        val reason: String?,
        val updated: String?,
        val unchanged: Boolean,
        val replies: List<FeedbackReply>?,
        val apks: List<String>?,
        val digests: Map<String, String>?,
        val error: String?,
    )

    suspend fun create(draft: Draft): CreateResult {
        val body = buildJsonObject {
            put("device", draft.device)
            put("summary", draft.summary)
            put("description", draft.description)
            put("app", currentAniBuildConfig.versionName)
            putJsonObject("env") { for ((k, v) in draft.env) put(k, v) }
            putJsonObject("window") {
                put("from", draft.windowFrom)
                put("to", draft.windowTo)
            }
            draft.perf?.let { perf -> putJsonObject("perf") { for ((k, v) in perf) put(k, v) } }
            putFiles(draft.files)
        }.toString()
        val (status, text) = call(HttpMethod.Post, "/tickets", CREATE_TIMEOUT) {
            contentType(ContentType.Application.Json)
            setBody(body)
        } ?: return CreateResult.Unreachable
        val json = parse(text)
        return when (status) {
            200 -> {
                val issue = json?.get("issue")?.jsonPrimitive?.intOrNull
                val token = json?.get("token")?.jsonPrimitive?.contentOrNull
                if (issue != null && token != null) CreateResult.Created(issue, token) else CreateResult.Failed
            }

            403 -> if (json.error == "blocked") CreateResult.Blocked else CreateResult.Failed
            429 -> if (json.error == "too_many_open") {
                CreateResult.TooManyOpen(json?.get("issues")?.jsonArray?.mapNotNull { it.jsonPrimitive.intOrNull }.orEmpty())
            } else {
                CreateResult.RateLimited
            }

            400 -> CreateResult.Rejected(json.error ?: "bad_request")
            else -> CreateResult.Failed
        }
    }

    /** 传一个声明过的附件 [name] (日志是 gzip, 照片是 JPEG / PNG / WebP). */
    suspend fun uploadFile(issue: Int, token: String, name: String, bytes: ByteArray): Outcome {
        val type = when (name.substringAfterLast('.')) {
            "gz" -> ContentType.Application.GZip
            "png" -> ContentType.Image.PNG
            "webp" -> ContentType("image", "webp")
            else -> ContentType.Image.JPEG
        }
        val (status, _) = call(HttpMethod.Put, "/tickets/$issue/files/$name", UPLOAD_TIMEOUT) {
            header(TICKET_HEADER, token)
            contentType(type)
            setBody(bytes)
        } ?: return Outcome.UNREACHABLE
        return outcomeOf(status)
    }

    /** 用户补充说明 [text] (可以空, 只带附件), 带附件 [files] (名字不带前缀, 服务给). */
    suspend fun reply(issue: Int, token: String, text: String, files: List<FileSpec>): ReplyResult {
        val body = buildJsonObject {
            put("body", text)
            putFiles(files)
        }.toString()
        val (status, answer) = call(HttpMethod.Post, "/tickets/$issue/reply", CREATE_TIMEOUT) {
            header(TICKET_HEADER, token)
            contentType(ContentType.Application.Json)
            setBody(body)
        } ?: return ReplyResult(Outcome.UNREACHABLE, null)
        return ReplyResult(outcomeOf(status), parse(answer)?.string("prefix"))
    }

    private fun JsonObjectBuilder.putFiles(files: List<FileSpec>) {
        putJsonArray("files") {
            for (f in files) addJsonObject {
                put("name", f.name)
                put("size", f.size)
            }
        }
    }

    /** 查处理结果 (一次最多 [MAX_STATUS] 张); 连不上或服务出错为 `null`. */
    suspend fun status(queries: List<Query>): List<Status>? {
        if (queries.isEmpty()) return emptyList()
        val body = buildJsonObject {
            putJsonArray("tickets") {
                for (q in queries.take(MAX_STATUS)) addJsonObject {
                    put("issue", q.issue)
                    put("token", q.token)
                    q.since?.let { put("since", it) }
                }
            }
        }.toString()
        val (status, text) = call(HttpMethod.Post, "/tickets/status", CREATE_TIMEOUT) {
            contentType(ContentType.Application.Json)
            setBody(body)
        } ?: return null
        if (status != 200) return null
        val tickets = parse(text)?.get("tickets") as? JsonArray ?: return null
        return tickets.mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            val issue = o["issue"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            Status(
                issue = issue,
                state = o.string("state"),
                reason = o.string("reason"),
                updated = o.string("updated"),
                unchanged = o["unchanged"]?.jsonPrimitive?.booleanOrNull == true,
                replies = (o["replies"] as? JsonArray)?.mapNotNull { r ->
                    val reply = r as? JsonObject ?: return@mapNotNull null
                    FeedbackReply(
                        fromMaintainer = reply.string("from") == "maintainer",
                        at = reply.string("at").orEmpty(),
                        body = reply.string("body").orEmpty(),
                        files = (reply["files"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
                    )
                },
                apks = (o["apks"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull },
                digests = (o["digests"] as? JsonObject)?.mapNotNull { (url, sha) -> sha.jsonPrimitive.contentOrNull?.let { url to it } }?.toMap(),
                error = o.string("error"),
            )
        }
    }

    private fun outcomeOf(status: Int): Outcome = when (status) {
        200 -> Outcome.OK
        409 -> Outcome.CONFLICT
        429 -> Outcome.RATE_LIMITED
        400, 404, 413 -> Outcome.REJECTED
        else -> Outcome.FAILED
    }

    /**
     * 依次试每个服务地址, 回 (状态码, 回应体). 连不上、超时、服务出错 (5xx) 换下一个; 都不行时回最后一个 5xx, 一个都没连上为 `null`.
     */
    private suspend fun call(
        method: HttpMethod,
        path: String,
        timeout: Duration,
        configure: HttpRequestBuilder.() -> Unit,
    ): Pair<Int, String>? {
        var last: Pair<Int, String>? = null
        for (base in endpoints()) {
            val url = base.trimEnd('/') + path
            val reply = transport.request(url) { candidate ->
                withTimeoutOrNull(timeout) {
                    client().use {
                        val response = request(candidate) {
                            this.method = method
                            expectSuccess = false
                            // 服务只收带这个头的请求 (挡随手乱发的)
                            header("X-Izuko-Client", currentAniBuildConfig.versionName)
                            configure()
                        }
                        response.status.value to response.bodyAsText()
                    }
                }
            } ?: continue
            if (reply.first in 500..599) {
                logger.info { "ticket service $url answered ${reply.first}: ${reply.second.take(200)}, trying next" }
                last = reply
                continue
            }
            if (reply.first != 200) logger.warn { "ticket service $path: ${reply.first} ${reply.second.take(200)}" }
            return reply
        }
        return last
    }

    private fun parse(text: String): JsonObject? = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()

    private val JsonObject?.error: String? get() = this?.string("error")

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    companion object {
        private val logger = logger<FeedbackTicketService>()

        /** 一次查状态最多几张单 (同服务端). */
        const val MAX_STATUS = 10

        /**
         * 正式的工单服务地址, 按顺序试; 空 = 工单没开放 (控制台的提交按钮置灰).
         * workers.dev 在大陆多半直连不通, 由 [CloudflareServiceTransport] 走加密直连.
         */
        val OFFICIAL_ENDPOINTS: List<String> = listOf("https://izuko-tickets.grahamzen.workers.dev")

        /** 现在用的地址: 正式的, 或 debug 包调试开关「故障工单服务」选的本机 (电脑上的 wrangler dev, 经 adb reverse). */
        fun configuredEndpoints(): List<String> =
            if (DevSwitches.ticketService.value == DevSwitches.ticketService.default) OFFICIAL_ENDPOINTS else listOf("http://127.0.0.1:8787")

        private const val TICKET_HEADER = "X-Izuko-Ticket"
        private val CREATE_TIMEOUT = 20.seconds
        private val UPLOAD_TIMEOUT = 60.seconds
    }
}
