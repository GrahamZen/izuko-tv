/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.clouddrive

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.him188.ani.app.domain.mediasource.directapi.DataNode
import me.him188.ani.app.domain.mediasource.directapi.JsonNode
import me.him188.ani.app.domain.mediasource.directapi.selectByPath
import me.him188.ani.app.domain.mediasource.directapi.stringByPath
import me.him188.ani.utils.ktor.getPlatformKtorEngine
import me.him188.ani.utils.ktor.registerLogging
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import kotlin.concurrent.Volatile
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * 按 [CloudDriveProtocol] 访问一个网盘. 不含任何网盘特有的逻辑: 地址、参数、字段、状态码都来自协议.
 *
 * 带登录态的请求带同一个 Cookie 请求头 (由 [CloudDriveCookieStore] 提供), 响应里服务端改写的登录 Cookie
 * ([DriveHttpConfig.rotatingCookies]) 交还给 [CloudDriveCookieStore.onServerCookies] —— 有的网盘每次请求都会轮换, 不回写的话登录很快失效.
 *
 * 地址带 `{host}` 的请求在 [DriveHttpConfig.hosts] 之间轮换: 同一个网盘的几个等价域名常解析到不同的边缘节点, 个别节点会连不上
 * (TCP 或 TLS 握手超时) 而其他域名正常, 所以连接层失败时换下一个重试, 并记住最后能用的那个. 不带 `{host}` 的请求连接失败时重试一次.
 *
 * @param protocol 当前的协议 (订阅更新后会变, 每次请求都重新读)
 */
internal class CloudDriveApi(
    private val client: HttpClient,
    private val protocol: () -> CloudDriveProtocol,
    private val cookies: CloudDriveCookieStore,
) {
    @Volatile
    private var preferredHostIndex = 0

    // region 账号

    /** 账号档位 (见 [DriveApiConfig.tier]); 协议没有这项时为 null. */
    suspend fun tier(): String? {
        val op = protocol().api.tier ?: return null
        return send("tier", op.request).stringByPath(op.value)
    }

    /** 账号昵称; 协议没有这项或取不到时为 null. */
    suspend fun nickname(): String? {
        val op = protocol().api.nickname ?: return null
        return send("nickname", op.request).stringByPath(op.value)?.takeIf { it.isNotBlank() }
    }

    // endregion

    // region 文件

    /** 按文件名搜索整个网盘的一页, 文件与文件夹都会返回. */
    suspend fun search(keyword: String, page: Int = 1): DriveFileList {
        val op = protocol().api.search ?: throw CloudDriveUnsupportedException("search")
        return listPage("search", op, mapOf("keyword" to keyword.json, "page" to page.json))
    }

    /** 列出文件夹的直接子项的一页. */
    suspend fun listFolder(folderId: String, page: Int = 1): DriveFileList {
        val op = protocol().api.listFolder ?: throw CloudDriveUnsupportedException("listFolder")
        return listPage("listFolder", op, mapOf("folderId" to folderId.json, "page" to page.json))
    }

    /** [listFolder] 每页几个. */
    val listPageSize: Int get() = protocol().api.listFolder?.pageSize ?: DEFAULT_PAGE_SIZE

    /** 原文件直链, 连带文件名与所在文件夹. */
    suspend fun download(fileId: String): DriveDownload =
        downloads(listOf(fileId)).firstOrNull { it.fid == fileId || it.fid.isEmpty() }
            ?: throw CloudDriveApiException("网盘没有返回下载地址")

    /** 一次取几个文件的直链 (见 [download]). 没给出地址的文件不在结果里. */
    suspend fun downloads(fileIds: List<String>): List<DriveDownload> {
        if (fileIds.isEmpty()) return emptyList()
        val p = protocol()
        val op = p.api.download ?: throw CloudDriveUnsupportedException("download")
        val root = send("download", op.request, mapOf("fileIds" to fileIds.json, "fileId" to fileIds.first().json))
        return root.selectByPath(op.items).mapNotNull { item ->
            val url = item.stringByPath(op.url)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            DriveDownload(
                fid = item.stringByPath(p.file.id).orEmpty(),
                fileName = p.file.name.ifBlank { null }?.let { item.stringByPath(it) }.orEmpty(),
                parentFid = p.file.parentId.ifBlank { null }?.let { item.stringByPath(it) }.orEmpty(),
                url = url,
            )
        }
    }

    /** 转码后的播放地址, 各档能不能用见 [DriveTranscodedVideo.accessible]. */
    suspend fun transcodedVideos(fileId: String): List<DriveTranscodedVideo> {
        val op = protocol().api.transcoded ?: throw CloudDriveUnsupportedException("transcoded")
        val root = send("transcoded", op.request, mapOf("fileId" to fileId.json))
        return root.selectByPath(op.items).map { item ->
            DriveTranscodedVideo(
                url = item.stringByPath(op.url),
                height = op.height.ifBlank { null }?.let { item.stringByPath(it)?.toDoubleOrNull()?.toInt() } ?: 0,
                accessible = op.accessible.isBlank() || item.stringByPath(op.accessible) in op.accessibleValues,
            )
        }
    }

    /** 在 [parentId] 下新建文件夹, 返回新文件夹的 id. */
    suspend fun createFolder(name: String, parentId: String): String {
        val op = protocol().api.createFolder ?: throw CloudDriveUnsupportedException("createFolder")
        return send("createFolder", op.request, mapOf("name" to name.json, "parentId" to parentId.json))
            .stringByPath(op.value)?.takeIf { it.isNotEmpty() }
            ?: throw CloudDriveApiException("网盘没有返回新文件夹")
    }

    /**
     * 删除文件. 删除是后台任务, [wait] 时等它完成 (要马上腾出空间时), 否则提交就返回. [fileIds] 为空时什么都不做.
     */
    suspend fun deleteFiles(fileIds: List<String>, wait: Boolean = false) {
        if (fileIds.isEmpty()) return
        val op = protocol().api.deleteFiles ?: throw CloudDriveUnsupportedException("deleteFiles")
        val taskId = send("deleteFiles", op.request, mapOf("fileIds" to fileIds.json)).stringByPath(op.value)
        if (!wait || taskId.isNullOrEmpty()) return
        awaitTask(taskId, "删除")
    }

    // endregion

    // region 分享

    /** 打开分享, 返回之后要带的令牌与分享标题. 不需要登录. */
    suspend fun shareToken(shareId: String, passcode: String): DriveShareToken {
        val op = protocol().api.shareToken ?: throw CloudDriveUnsupportedException("shareToken")
        val root = send("shareToken", op.request, mapOf("shareId" to shareId.json, "passcode" to passcode.json), share = true)
        return DriveShareToken(
            token = op.token.ifBlank { null }?.let { root.stringByPath(it) }.orEmpty(),
            title = op.title.ifBlank { null }?.let { root.stringByPath(it) }.orEmpty(),
        )
    }

    /** 列出分享里一个文件夹的直接子项的一页, 分享的根是 [DriveShareConfig.rootFolderId]. 不需要登录. */
    suspend fun listShareFolder(shareId: String, passcode: String, token: String, folderId: String, page: Int = 1): DriveFileList {
        val op = protocol().api.listShare ?: throw CloudDriveUnsupportedException("listShare")
        val vars = mapOf(
            "shareId" to shareId.json,
            "passcode" to passcode.json,
            "shareToken" to token.json,
            "folderId" to folderId.json,
            "page" to page.json,
        )
        return listPage("listShare", op, vars, share = true)
    }

    /** [listShareFolder] 每页几个. */
    val shareListPageSize: Int get() = protocol().api.listShare?.pageSize ?: DEFAULT_PAGE_SIZE

    /**
     * 把分享里的 [files] 转存到自己网盘的 [toFolderId] 下, 返回转存后的文件 id (转存一个文件时就是它的 id). 需要登录.
     *
     * 转存是后台任务: 提交后按任务 id 轮询, 任务完成时给出新文件的 id.
     */
    suspend fun saveFromShare(shareId: String, passcode: String, token: String, files: List<DriveFile>, toFolderId: String): List<String> {
        val p = protocol()
        val op = p.api.saveFromShare ?: throw CloudDriveUnsupportedException("saveFromShare")
        val vars = mapOf(
            "shareId" to shareId.json,
            "passcode" to passcode.json,
            "shareToken" to token.json,
            "fileIds" to files.map { it.fid }.json,
            "fileTokens" to files.map { it.shareToken }.json,
            "folderId" to toFolderId.json,
            "shareRootId" to p.share.rootFolderId.json,
        )
        val taskId = send("saveFromShare", op.request, vars).stringByPath(op.value)?.takeIf { it.isNotEmpty() }
            ?: throw CloudDriveApiException("网盘没有返回转存任务")
        return awaitTask(taskId, "转存").ifEmpty { throw CloudDriveApiException("转存完成但没有返回新文件") }
    }

    /** 等后台任务完成, 返回它产出的文件 id. */
    private suspend fun awaitTask(taskId: String, action: String): List<String> {
        val op = protocol().api.task ?: throw CloudDriveUnsupportedException("task")
        repeat(op.maxPolls.coerceAtLeast(1)) { retry ->
            val root = send("task", op.request, mapOf("taskId" to taskId.json, "retry" to retry.json))
            val results = op.resultIds.ifBlank { null }?.let { path -> root.selectByPath(path).mapNotNull { it.asStringOrNull() } }.orEmpty()
            if (results.isNotEmpty()) return results
            val status = root.stringByPath(op.status)
            if (status in op.done) return emptyList()
            if (status in op.failed) {
                val message = op.message.ifBlank { null }?.let { root.stringByPath(it) }.orEmpty()
                throw CloudDriveApiException("${action}失败: $message")
            }
            delay(op.intervalMillis)
        }
        throw CloudDriveApiException("${action}超时")
    }

    // endregion

    // region 扫码登录

    /** 扫码登录第一步: 申请二维码令牌. 返回令牌与这一步下发的 Cookie. */
    suspend fun requestQrToken(): DriveQrToken {
        val qr = protocol().login.qr ?: throw CloudDriveUnsupportedException("qr")
        val response = sendRaw("qr", qr.start, emptyMap())
        val root = response.json
        val token = root?.stringByPath(qr.token)
        val statusOk = qr.status.isBlank() || qr.okStatuses.isEmpty() || root?.stringByPath(qr.status) in qr.okStatuses
        if (!statusOk || token.isNullOrBlank()) {
            throw CloudDriveApiException("获取登录二维码失败: ${root?.let { messageOf(it) }.orEmpty()}")
        }
        return DriveQrToken(token, response.setCookies)
    }

    /** 扫码登录第二步: 轮询扫码结果. 没有换票那一步时, 确认那一刻的 Cookie 就是登录 Cookie. */
    suspend fun pollQrToken(token: String): DriveQrPollResult {
        val qr = protocol().login.qr ?: throw CloudDriveUnsupportedException("qr")
        val response = sendRaw("qr", qr.poll, mapOf("token" to token.json))
        val root = response.json ?: return DriveQrPollResult.Waiting
        val status = qr.status.ifBlank { null }?.let { root.stringByPath(it) }
        val ticket = qr.ticket.ifBlank { null }?.let { root.stringByPath(it) }
        return when {
            (qr.confirmedStatuses.isEmpty() || status in qr.confirmedStatuses) && (qr.ticket.isBlank() || !ticket.isNullOrBlank()) &&
                    (qr.confirmedStatuses.isNotEmpty() || !ticket.isNullOrBlank()) ->
                DriveQrPollResult.Confirmed(ticket.orEmpty(), response.setCookies)

            status != null && status in qr.expiredStatuses -> DriveQrPollResult.Expired
            else -> DriveQrPollResult.Waiting
        }
    }

    /** 扫码登录第三步: 用票据换登录 Cookie. 协议没有这一步时返回空. */
    suspend fun exchangeTicket(ticket: String): DriveTicketExchange {
        val qr = protocol().login.qr ?: throw CloudDriveUnsupportedException("qr")
        val exchange = qr.exchange ?: return DriveTicketExchange(emptyMap(), null)
        val response = sendRaw("qr", exchange, mapOf("ticket" to ticket.json))
        val nickname = qr.nickname.ifBlank { null }?.let { response.json?.stringByPath(it) }
        return DriveTicketExchange(response.setCookies, nickname?.takeIf { it.isNotBlank() })
    }

    /** 登录二维码的内容. */
    fun qrCodeContent(token: String): String {
        val qr = protocol().login.qr ?: throw CloudDriveUnsupportedException("qr")
        return DriveTemplate(mapOf("token" to token.json)).text(qr.content)
    }

    // endregion

    private suspend fun listPage(
        operation: String,
        op: DriveListOp,
        vars: Map<String, JsonElement>,
        share: Boolean = false,
    ): DriveFileList {
        val fields = protocol().file
        val root = send(operation, op.request, vars + ("pageSize" to op.pageSize.json), share)
        val files = root.selectByPath(op.items).mapNotNull { parseFile(it, fields) }
        val total = op.total.ifBlank { null }?.let { root.stringByPath(it)?.toDoubleOrNull()?.toInt() }
        return DriveFileList(files, total)
    }

    /**
     * 发一个接口请求, 检查响应并返回解析好的 JSON.
     *
     * @param share 查看分享的接口: 出错一律按 [CloudDriveShareUnavailableException] 报 (分享失效与登录无关)
     */
    private suspend fun send(
        operation: String,
        request: DriveRequest,
        vars: Map<String, JsonElement> = emptyMap(),
        share: Boolean = false,
    ): DataNode {
        // 看分享的请求一律不带账号 Cookie, 也不把它返回的 Cookie 合并进账号 (分享与登录无关)
        val effective = if (share) request.copy(cookie = false) else request
        val response = sendRaw(operation, effective, vars)
        val p = protocol()
        if (effective.cookie) {
            val updates = response.setCookies.filterKeys { it in p.http.rotatingCookies }
            if (updates.isNotEmpty()) cookies.onServerCookies(updates)
        }
        val root = response.json
        val status = response.status
        if (share) {
            if (status !in 200..399 || root == null || !codeOk(root)) {
                throw CloudDriveShareUnavailableException(
                    root?.let { codeOf(it) },
                    "分享不可用 ($operation): HTTP $status, code=${root?.let { codeOf(it) }}, ${root?.let { messageOf(it) }.orEmpty()}",
                )
            }
            return root
        }
        val envelopeStatus = if (request.envelope && p.envelope.status.isNotBlank()) root?.stringByPath(p.envelope.status) else null
        if (status == 401 || status == 403 || (envelopeStatus != null && envelopeStatus in p.envelope.authStatuses)) {
            throw CloudDriveAuthException(root?.let { messageOf(it) }?.takeIf { it.isNotBlank() })
        }
        if (status !in 200..399 || (request.envelope && root != null && !codeOk(root))) {
            val code = root?.let { codeOf(it) }
            throw CloudDriveApiException(
                "网盘接口 $operation 出错: HTTP $status, code=$code, ${root?.let { messageOf(it) }.orEmpty()}",
                code = code,
                isCapacityLimit = code != null && code in p.envelope.capacityLimitCodes,
            )
        }
        return root ?: throw CloudDriveApiException("网盘接口 $operation 没有返回 JSON")
    }

    private fun codeOk(root: DataNode): Boolean {
        val envelope = protocol().envelope
        if (envelope.code.isBlank()) return true
        val code = root.stringByPath(envelope.code) ?: return true
        return code in envelope.okCodes
    }

    private fun codeOf(root: DataNode): String? = protocol().envelope.code.ifBlank { null }?.let { root.stringByPath(it) }

    private fun messageOf(root: DataNode): String? = protocol().envelope.message.ifBlank { null }?.let { root.stringByPath(it) }

    private class RawResponse(val status: Int, val text: String, val setCookies: Map<String, String>) {
        val json: DataNode? by lazy { runCatching { JsonNode(CloudDriveApi.json.parseToJsonElement(text)) }.getOrNull() }
    }

    /**
     * 发请求并读出正文. 地址带 `{host}` 时按域名轮换 (只有连接层失败才换, 服务端正常回了错误就照常返回), 否则连接失败时重试一次.
     */
    private suspend fun sendRaw(operation: String, request: DriveRequest, vars: Map<String, JsonElement>): RawResponse {
        val p = protocol()
        val hosts = p.http.hosts
        val rotating = request.url.contains("{host}") && hosts.isNotEmpty()
        val attempts = if (rotating) hosts.size else 2
        val start = if (rotating) preferredHostIndex.coerceIn(0, hosts.size - 1) else 0
        var lastError: Throwable? = null
        for (offset in 0 until attempts) {
            val index = (start + offset) % attempts
            val host = if (rotating) hosts[index] else null
            val template = DriveTemplate(vars + listOfNotNull(host?.let { "host" to it.json }, "requestId" to randomRequestId().json))
            val response = try {
                execute(p, request, template)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                logger.warn { "Cloud drive ${p.id} request $operation failed${host?.let { " on $it" }.orEmpty()}: $e" }
                lastError = e
                continue
            }
            if (rotating) preferredHostIndex = index
            return RawResponse(response.status.value, response.bodyAsText(), parseSetCookies(response))
        }
        throw lastError ?: IOException("No host available for ${p.id}")
    }

    private suspend fun execute(p: CloudDriveProtocol, request: DriveRequest, template: DriveTemplate): HttpResponse {
        val cookie = if (request.cookie) cookies.get().takeIf { it.isNotBlank() } else null
        return client.request {
            method = HttpMethod.parse(request.method.uppercase())
            url(template.text(request.url))
            timeout {
                connectTimeoutMillis = p.http.connectTimeoutMillis
                socketTimeoutMillis = p.http.socketTimeoutMillis
                requestTimeoutMillis = p.http.requestTimeoutMillis
            }
            if (request.common) p.http.query.forEach { (name, value) -> parameter(name, template.text(value)) }
            request.query.forEach { (name, value) -> parameter(name, template.text(value)) }
            request.userAgent.ifBlank { p.http.userAgent }.takeIf { it.isNotBlank() }?.let { header(HttpHeaders.UserAgent, it) }
            if (request.common) p.http.headers.forEach { (name, value) -> header(name, template.text(value)) }
            request.headers.forEach { (name, value) -> header(name, template.text(value)) }
            cookie?.let { header(HttpHeaders.Cookie, it) }
            request.body?.let { body ->
                contentType(ContentType.Application.Json)
                setBody(template.json(body).toString())
            }
        }
    }

    companion object {
        private val logger = logger<CloudDriveApi>()

        private const val DEFAULT_PAGE_SIZE = 100

        internal val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }

        /** 没有类别字段时按扩展名认视频. */
        private val VIDEO_EXTENSIONS = setOf(
            "mp4", "mkv", "avi", "mov", "flv", "webm", "ts", "m2ts", "mts", "rmvb", "rm", "wmv", "m4v", "3gp", "mpg", "mpeg", "vob",
        )

        /**
         * 网盘专用的 HttpClient.
         *
         * 不用应用共享的 client: 共享 client 装了 `HttpCookies`, 它会把手写的 Cookie 头存进共享的 Cookie 存储再重新拼出来,
         * 与服务端下发的同名 Cookie 叠在一起发出去, 退出登录后也清不掉. 这里不装 Cookie 插件, Cookie 全由 [CloudDriveCookieStore] 管.
         * 不走应用设置的代理. 超时按协议在每个请求上设.
         */
        fun createHttpClient(): HttpClient = HttpClient(getPlatformKtorEngine()) {
            install(HttpTimeout)
            expectSuccess = false
            followRedirects = true
        }.apply {
            registerLogging(logger)
        }

        /** 取出响应里 `Set-Cookie` 的 name=value, 原样保留值 (不做解码). */
        fun parseSetCookies(response: HttpResponse): Map<String, String> =
            parseSetCookieHeaders(response.headers.getAll(HttpHeaders.SetCookie).orEmpty())

        fun parseSetCookieHeaders(headers: List<String>): Map<String, String> = buildMap {
            for (header in headers) {
                val pair = header.substringBefore(';').trim()
                val name = pair.substringBefore('=', missingDelimiterValue = "").trim()
                if (name.isEmpty()) continue
                put(name, pair.substringAfter('='))
            }
        }

        /** 按协议的字段路径读一个文件条目; 没有 id 时为 null. */
        internal fun parseFile(node: DataNode, fields: DriveFileFields): DriveFile? {
            fun value(path: String): String? = path.ifBlank { null }?.let { node.stringByPath(it) }
            val fid = value(fields.id)?.takeIf { it.isNotEmpty() } ?: return null
            val name = value(fields.name).orEmpty()
            val dir = value(fields.isDir)?.let { it in fields.dirValues } ?: false
            val updatedAt = value(fields.updatedAt)?.toDoubleOrNull()?.toLong()?.let { if (fields.updatedAtSeconds) it * 1000 else it } ?: 0
            val isVideo = !dir && if (fields.category.isNotBlank()) {
                value(fields.category) in fields.videoCategories
            } else {
                name.substringAfterLast('.', "").lowercase() in VIDEO_EXTENSIONS
            }
            return DriveFile(
                fid = fid,
                fileName = name,
                parentFid = value(fields.parentId).orEmpty(),
                dir = dir,
                size = value(fields.size)?.toDoubleOrNull()?.toLong() ?: 0,
                updatedAt = updatedAt,
                videoHeight = value(fields.videoHeight)?.toDoubleOrNull()?.toInt() ?: 0,
                isVideo = isVideo,
                shareToken = value(fields.shareToken).orEmpty(),
                durationSeconds = value(fields.duration)?.toDoubleOrNull()?.toInt() ?: 0,
            )
        }

        @OptIn(ExperimentalUuidApi::class)
        private fun randomRequestId(): String = Uuid.random().toString()

        private val String.json: JsonElement get() = JsonPrimitive(this)
        private val Int.json: JsonElement get() = JsonPrimitive(this)
        private val List<String>.json: JsonElement get() = JsonArray(map { JsonPrimitive(it) })
    }
}

/**
 * 模板里的 `{name}` 换成变量的值.
 */
internal class DriveTemplate(private val values: Map<String, JsonElement>) {
    /** 字符串模板: 列表变量用逗号连起来, 认不出的变量原样留着. */
    fun text(template: String): String = VARIABLE.replace(template) { match ->
        values[match.groupValues[1]]?.let { textOf(it) } ?: match.value
    }

    /** JSON 模板: 字符串值整个是一个变量时换成变量的 JSON 值, 否则在字符串里替换. */
    fun json(element: JsonElement): JsonElement = when (element) {
        is JsonPrimitive -> if (element.isString) {
            val whole = VARIABLE.matchEntire(element.content)?.groupValues?.get(1)
            whole?.let { values[it] } ?: JsonPrimitive(text(element.content))
        } else {
            element
        }

        is JsonArray -> JsonArray(element.map { json(it) })
        is JsonObject -> JsonObject(element.mapValues { json(it.value) })
    }

    private fun textOf(value: JsonElement): String = when (value) {
        is JsonPrimitive -> value.content
        is JsonArray -> value.joinToString(",") { textOf(it) }
        is JsonObject -> value.toString()
    }

    private companion object {
        // 右花括号也要转义: 安卓的正则 (ICU) 不认没转义的 `}`, 类初始化就失败
        private val VARIABLE = Regex("""\{([A-Za-z][A-Za-z0-9_]*)\}""")
    }
}

/**
 * 网盘登录 Cookie 的读写.
 */
internal interface CloudDriveCookieStore {
    /** 当前的整段 Cookie 请求头, 没登录时为空. */
    suspend fun get(): String

    /** 服务端在响应里改写了这些 Cookie (name 到 value). */
    suspend fun onServerCookies(cookies: Map<String, String>)
}

/**
 * @property code 接口返回的错误码, 没有时为 null
 * @property isCapacityLimit 网盘空间不够 (例如转存时)
 */
open class CloudDriveApiException(
    message: String,
    cause: Throwable? = null,
    open val code: String? = null,
    val isCapacityLimit: Boolean = false,
) : Exception(message, cause)

/** 网盘空间不够, 清空了转存文件夹也放不下 (空间被别的文件占着). */
class CloudDriveCapacityException(message: String) : CloudDriveApiException(message, isCapacityLimit = true)

/** 没登录或登录已失效. */
class CloudDriveAuthException(message: String? = null) : CloudDriveApiException(message ?: "网盘未登录或登录已失效")

/** 分享打不开: 已取消、被封或要提取码. */
class CloudDriveShareUnavailableException(override val code: String?, message: String) : CloudDriveApiException(message)

/** 协议没有这项操作. */
class CloudDriveUnsupportedException(operation: String) : CloudDriveApiException("这个网盘不支持 $operation")

internal class DriveQrToken(val token: String, val cookies: Map<String, String>)

internal class DriveTicketExchange(val cookies: Map<String, String>, val nickname: String?)

internal sealed interface DriveQrPollResult {
    data object Waiting : DriveQrPollResult
    data object Expired : DriveQrPollResult

    /** @param cookies 这一步下发的 Cookie */
    class Confirmed(val ticket: String, val cookies: Map<String, String>) : DriveQrPollResult
}

/** @param total 总数, 不知道时为 null */
class DriveFileList(val files: List<DriveFile>, val total: Int?)

/**
 * 网盘或分享里的一个文件或文件夹.
 *
 * @property updatedAt 修改时间, 毫秒时间戳
 * @property shareToken 分享里的文件转存时要带的凭证; 自己网盘的文件为空
 * @property durationSeconds 视频时长 (秒), 网盘没给时为 0
 */
class DriveFile(
    val fid: String,
    val fileName: String = "",
    val parentFid: String = "",
    val dir: Boolean = false,
    val size: Long = 0,
    val updatedAt: Long = 0,
    val videoHeight: Int = 0,
    val isVideo: Boolean = false,
    val shareToken: String = "",
    val durationSeconds: Int = 0,
) {
    /** 放在文件夹 [folderId] 里的同一个文件. */
    fun inFolder(folderId: String): DriveFile =
        DriveFile(fid, fileName, folderId, dir, size, updatedAt, videoHeight, isVideo, shareToken, durationSeconds)
}

class DriveShareToken(val token: String, val title: String)

/** @param height 画面高度, 不知道时为 0 */
class DriveTranscodedVideo(val url: String?, val height: Int, val accessible: Boolean)

/**
 * 一个文件的直链.
 *
 * @param parentFid 所在文件夹的 id, 外挂字幕到这里找
 */
class DriveDownload(val fid: String, val fileName: String, val parentFid: String, val url: String)
