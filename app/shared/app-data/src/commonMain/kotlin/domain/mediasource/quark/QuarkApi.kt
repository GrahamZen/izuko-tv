/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.quark

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.him188.ani.utils.ktor.getPlatformKtorEngine
import me.him188.ani.utils.ktor.registerLogging
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import kotlin.concurrent.Volatile
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * 夸克网盘网页接口.
 *
 * 所有请求带同一个 Cookie 请求头 (由 [QuarkCookieStore] 提供), 响应里服务端下发的 `__puus` / `__pus`
 * 交还给 [QuarkCookieStore.onServerCookies] —— `__puus` 每次请求都会轮换, 不回写的话登录很快失效.
 *
 * 网盘接口 (`/1/clouddrive/...`) 有几个等价的域名, 它们解析到不同的边缘节点. 实测个别节点会连不上
 * (TCP 或 TLS 握手超时) 而其他域名正常, 所以连接层失败时换下一个域名重试, 并记住最后能用的那个.
 */
internal class QuarkApi(
    private val client: HttpClient,
    private val cookies: QuarkCookieStore,
    private val driveHosts: List<String> = DRIVE_HOSTS,
) {
    @Volatile
    private var preferredHostIndex = 0

    suspend fun member(): QuarkMember =
        driveGet(
            "member",
            QuarkMember.serializer(),
        ) {
            parameter("fetch_subscribe", "true")
            parameter("_ch", "home")
            parameter("fetch_identity", "true")
        }

    /**
     * 按文件名搜索整个网盘, 文件与文件夹都会返回. 服务端按子串匹配 (中文) / 按词匹配 (英文).
     */
    suspend fun search(keyword: String, page: Int = 1, pageSize: Int = SEARCH_PAGE_SIZE): QuarkFileList =
        driveGetPage("file/search") {
            parameter("q", keyword)
            parameter("_page", page)
            parameter("_size", pageSize)
            parameter("_fetch_total", 1)
            parameter("_sort", "file_type:desc,updated_at:desc")
        }

    suspend fun listFolder(folderId: String, page: Int = 1, pageSize: Int = LIST_PAGE_SIZE): QuarkFileList =
        driveGetPage("file/sort") {
            parameter("pdir_fid", folderId)
            parameter("_page", page)
            parameter("_size", pageSize)
            parameter("_fetch_total", 1)
            parameter("_sort", "file_type:asc,file_name:asc")
        }

    /**
     * 原文件直链, 有效期约 6 小时. 下载时必须带 Cookie, 否则 CDN 回 412.
     */
    suspend fun downloadUrl(fileId: String): String {
        val body = buildJsonObject { putJsonArray("fids") { add(JsonPrimitive(fileId)) } }
        val items = drivePost("file/download", body, ListSerializer(QuarkDownloadItem.serializer()))
        return items.firstOrNull()?.downloadUrl?.takeIf { it.isNotBlank() }
            ?: throw QuarkApiException("夸克没有返回下载地址")
    }

    /**
     * 转码播放地址. 每一档是否可用看 [QuarkTranscodedVideo.accessible] (非会员只有最低档).
     */
    suspend fun transcodedVideos(fileId: String): List<QuarkTranscodedVideo> {
        val body = buildJsonObject {
            put("fid", fileId)
            put("resolutions", "normal,low,high,super,2k,4k")
            put("supports", "fmp4,m3u8")
        }
        return drivePost("file/v2/play", body, QuarkPlayData.serializer()).videoList
    }

    // region 账号

    /**
     * 当前账号的昵称. 与网盘接口不在同一个域名上.
     */
    suspend fun nickname(): String? {
        val response = requestWithRetry {
            client.get("$ACCOUNT_URL/info") {
                commonHeaders(cookies.get(), userAgent = WEB_USER_AGENT)
                parameter("fr", "pc")
                parameter("platform", "pc")
            }
        }
        handleServerCookies(response)
        if (response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.Forbidden) {
            throw QuarkAuthException()
        }
        val info = json.decodeFromString(QuarkAccountInfo.serializer(), response.bodyAsText())
        return info.data?.nickname?.takeIf { it.isNotBlank() }
    }

    /**
     * 扫码登录第一步: 申请二维码令牌. 返回令牌与这一步下发的 Cookie.
     */
    suspend fun requestQrToken(): QuarkQrToken {
        val response = requestWithRetry {
            client.get("$QR_LOGIN_URL/getTokenForQrcodeLogin") {
                commonHeaders(cookie = null, userAgent = WEB_USER_AGENT)
                parameter("client_id", QR_CLIENT_ID)
                parameter("v", "1.2")
                parameter("request_id", randomRequestId())
            }
        }
        val result = json.decodeFromString(QuarkQrResponse.serializer(), response.bodyAsText())
        val token = result.data?.members?.token
        if (result.status != QR_STATUS_OK || token.isNullOrBlank()) {
            throw QuarkApiException("获取登录二维码失败: ${result.message}")
        }
        return QuarkQrToken(token, parseSetCookies(response))
    }

    /**
     * 扫码登录第二步: 轮询扫码结果.
     */
    suspend fun pollQrToken(token: String): QuarkQrPollResult {
        val response = requestWithRetry {
            client.get("$QR_LOGIN_URL/getServiceTicketByQrcodeToken") {
                commonHeaders(cookie = null, userAgent = WEB_USER_AGENT)
                parameter("client_id", QR_CLIENT_ID)
                parameter("v", "1.2")
                parameter("token", token)
                parameter("request_id", randomRequestId())
            }
        }
        val result = json.decodeFromString(QuarkQrResponse.serializer(), response.bodyAsText())
        val ticket = result.data?.members?.serviceTicket
        return when {
            result.status == QR_STATUS_OK && !ticket.isNullOrBlank() -> QuarkQrPollResult.Confirmed(ticket)
            result.status in QR_STATUS_EXPIRED -> QuarkQrPollResult.Expired
            else -> QuarkQrPollResult.Waiting
        }
    }

    /**
     * 扫码登录第三步: 用 service ticket 换登录 Cookie (`__pus` 等由这一步下发).
     */
    suspend fun exchangeServiceTicket(ticket: String): QuarkTicketExchange {
        val response = requestWithRetry {
            client.get("$ACCOUNT_URL/info") {
                commonHeaders(cookie = null, userAgent = WEB_USER_AGENT)
                parameter("st", ticket)
                parameter("lw", "scan")
            }
        }
        val nickname = runCatching {
            json.decodeFromString(QuarkAccountInfo.serializer(), response.bodyAsText()).data?.nickname
        }.getOrNull()
        return QuarkTicketExchange(parseSetCookies(response), nickname?.takeIf { it.isNotBlank() })
    }

    // endregion

    private suspend fun driveGetPage(
        path: String,
        configure: HttpRequestBuilder.() -> Unit,
    ): QuarkFileList {
        val response = driveRequest(path) { host ->
            client.get("https://$host/1/clouddrive/$path") {
                driveParameters()
                commonHeaders(cookies.get())
                configure()
            }
        }
        val parsed = json.decodeFromString(QuarkResponse.serializer(QuarkListData.serializer()), response)
        return QuarkFileList(parsed.data?.list.orEmpty(), parsed.metadata?.total ?: 0)
    }

    private suspend fun <T> driveGet(
        path: String,
        serializer: KSerializer<T>,
        configure: HttpRequestBuilder.() -> Unit = {},
    ): T {
        val response = driveRequest(path) { host ->
            client.get("https://$host/1/clouddrive/$path") {
                driveParameters()
                commonHeaders(cookies.get())
                configure()
            }
        }
        return json.decodeFromString(QuarkResponse.serializer(serializer), response).data
            ?: throw QuarkApiException("夸克接口 $path 没有返回数据")
    }

    private suspend fun <T> drivePost(path: String, body: JsonObject, serializer: KSerializer<T>): T {
        val response = driveRequest(path) { host ->
            client.post("https://$host/1/clouddrive/$path") {
                driveParameters()
                commonHeaders(cookies.get())
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
        }
        return json.decodeFromString(QuarkResponse.serializer(serializer), response).data
            ?: throw QuarkApiException("夸克接口 $path 没有返回数据")
    }

    /**
     * 按域名轮换发网盘请求, 返回成功响应的正文. 只有连接层失败 (连不上/超时) 才换域名;
     * 服务端正常回了错误就直接按错误处理, 换域名也没用.
     */
    private suspend fun driveRequest(path: String, send: suspend (host: String) -> HttpResponse): String {
        var lastError: Throwable? = null
        val start = preferredHostIndex
        for (offset in driveHosts.indices) {
            val index = (start + offset) % driveHosts.size
            val host = driveHosts[index]
            val response = try {
                send(host)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                logger.warn { "Quark host $host unreachable for $path: $e" }
                lastError = e
                continue
            }
            preferredHostIndex = index
            handleServerCookies(response)
            val text = response.bodyAsText()
            checkResponse(path, response.status, text)
            return text
        }
        throw lastError ?: IOException("No Quark host available")
    }

    private fun checkResponse(path: String, status: HttpStatusCode, text: String) {
        val envelope = runCatching { json.decodeFromString(QuarkEnvelope.serializer(), text) }.getOrNull()
        if (status == HttpStatusCode.Unauthorized || status == HttpStatusCode.Forbidden ||
            envelope?.status == 401 || envelope?.status == 403
        ) {
            throw QuarkAuthException(envelope?.message)
        }
        if (!status.isSuccessOrRedirect() || (envelope != null && envelope.code != 0)) {
            throw QuarkApiException(
                "夸克接口 $path 出错: HTTP ${status.value}, code=${envelope?.code}, ${envelope?.message.orEmpty()}",
            )
        }
    }

    private suspend fun handleServerCookies(response: HttpResponse) {
        val updates = parseSetCookies(response).filterKeys { it in ROTATING_COOKIES }
        if (updates.isNotEmpty()) cookies.onServerCookies(updates)
    }

    private suspend fun requestWithRetry(send: suspend () -> HttpResponse): HttpResponse {
        try {
            return send()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            logger.warn { "Quark request failed, retrying once: $e" }
            return send()
        }
    }

    private fun HttpRequestBuilder.driveParameters() {
        parameter("pr", "ucpro")
        parameter("fr", "pc")
        parameter("uc_param_str", "")
    }

    private fun HttpRequestBuilder.commonHeaders(cookie: String?, userAgent: String = USER_AGENT) {
        header(HttpHeaders.UserAgent, userAgent)
        header(HttpHeaders.Referrer, REFERER)
        header(HttpHeaders.Origin, ORIGIN)
        header(HttpHeaders.Accept, "application/json, text/plain, */*")
        if (!cookie.isNullOrBlank()) header(HttpHeaders.Cookie, cookie)
    }

    private fun HttpStatusCode.isSuccessOrRedirect(): Boolean = value in 200..399

    companion object {
        /**
         * 与夸克电脑客户端一致的 UA. 播放器请求直链时也要用它.
         */
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/140.0.0.0 Safari/537.36 QuarkPC/7.0.0 QuarkCloudDrivePC/7.0.0 " +
                    "quark-cloud-drive/5.0.0 Channel/pckk_other_ch"
        /**
         * 账号与扫码登录接口用普通浏览器 UA: 带客户端 UA 时 `account/info` 只回 `data: null`, 拿不到昵称.
         */
        private const val WEB_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
        const val REFERER = "https://pan.quark.cn/"
        private const val ORIGIN = "https://pan.quark.cn"

        val DRIVE_HOSTS = listOf("drive-pc.quark.cn", "drive-m.quark.cn", "drive.quark.cn")
        private const val ACCOUNT_URL = "https://pan.quark.cn/account"
        private const val QR_LOGIN_URL = "https://uop.quark.cn/cas/ajax"
        private const val QR_CLIENT_ID = "532"

        const val QR_STATUS_OK = 2000000

        /**
         * 二维码失效/被拒. 还在等扫码时是 50004001.
         */
        private val QR_STATUS_EXPIRED = setOf(50004002, 50004003, 50004004)

        /**
         * 服务端会在普通接口的响应里改写的登录 Cookie.
         */
        private val ROTATING_COOKIES = setOf("__puus", "__pus")

        const val SEARCH_PAGE_SIZE = 50
        const val LIST_PAGE_SIZE = 100

        private val logger = logger<QuarkApi>()

        internal val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }

        /**
         * 登录二维码的内容, 由夸克 App 扫描.
         */
        fun qrCodeContent(token: String): String =
            "https://su.quark.cn/4_eMHBJ?token=$token&client_id=$QR_CLIENT_ID&ssb=weblogin&uc_param_str=" +
                    "&uc_biz_str=S%3Acustom%7COPT%3ASAREA%400%7COPT%3AIMMERSIVE%401%7COPT%3ABACK_BTN_STYLE%400"

        /**
         * 夸克专用的 HttpClient.
         *
         * 不用应用共享的 client: 共享 client 装了 `HttpCookies`, 它会把手写的 Cookie 头存进共享的 Cookie 存储再重新拼出来,
         * 与服务端下发的同名 Cookie 叠在一起发出去, 退出登录后也清不掉. 这里不装 Cookie 插件, Cookie 全由 [QuarkCookieStore] 管.
         * 夸克是国内服务, 不走应用设置的代理.
         */
        fun createHttpClient(): HttpClient = HttpClient(getPlatformKtorEngine()) {
            // 接口响应都很小 (实测 1~3 秒回), 超时给短: 连不上的节点常常是握手后不回数据,
            // 按长超时要等四十来秒才换域名, 登录与第一次搜索会被拖住
            install(HttpTimeout) {
                connectTimeoutMillis = 5_000
                socketTimeoutMillis = 8_000
                requestTimeoutMillis = 20_000
            }
            expectSuccess = false
            followRedirects = true
        }.apply {
            registerLogging(logger)
        }

        /**
         * 取出响应里 `Set-Cookie` 的 name=value, 原样保留值 (不做解码).
         */
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

        @OptIn(ExperimentalUuidApi::class)
        private fun randomRequestId(): String = Uuid.random().toString()
    }
}

/**
 * 夸克登录 Cookie 的读写.
 */
internal interface QuarkCookieStore {
    /**
     * 当前的整段 Cookie 请求头, 没登录时为空.
     */
    suspend fun get(): String

    /**
     * 服务端在响应里改写了这些 Cookie (name 到 value).
     */
    suspend fun onServerCookies(cookies: Map<String, String>)
}

open class QuarkApiException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 没登录或登录已失效.
 */
class QuarkAuthException(message: String? = null) : QuarkApiException(message ?: "夸克网盘未登录或登录已失效")

internal class QuarkQrToken(val token: String, val cookies: Map<String, String>)

internal class QuarkTicketExchange(val cookies: Map<String, String>, val nickname: String?)

internal sealed interface QuarkQrPollResult {
    data object Waiting : QuarkQrPollResult
    data object Expired : QuarkQrPollResult
    class Confirmed(val serviceTicket: String) : QuarkQrPollResult
}

class QuarkFileList(val files: List<QuarkFile>, val total: Int)

@Serializable
class QuarkFile(
    val fid: String,
    @SerialName("file_name") val fileName: String = "",
    @SerialName("pdir_fid") val parentFid: String = "",
    val dir: Boolean = false,
    val size: Long = 0,
    @SerialName("obj_category") val category: String? = null,
    /**
     * 毫秒时间戳.
     */
    @SerialName("updated_at") val updatedAt: Long = 0,
    @SerialName("video_height") val videoHeight: Int = 0,
) {
    val isVideo: Boolean get() = !dir && category == "video"
}

@Serializable
class QuarkMember(
    @SerialName("member_type") val memberType: String = "",
)

@Serializable
class QuarkTranscodedVideo(
    val resolution: String = "",
    @SerialName("accessable") val accessible: Boolean = false,
    @SerialName("video_info") val videoInfo: QuarkVideoInfo? = null,
)

@Serializable
class QuarkVideoInfo(
    val url: String? = null,
    val width: Int = 0,
    val height: Int = 0,
)

@Serializable
private class QuarkEnvelope(
    val status: Int = 0,
    val code: Int = 0,
    val message: String = "",
)

@Serializable
private class QuarkResponse<T>(
    val status: Int = 0,
    val code: Int = 0,
    val message: String = "",
    val data: T? = null,
    val metadata: QuarkMetadata? = null,
)

@Serializable
private class QuarkMetadata(
    @SerialName("_total") val total: Int = 0,
)

@Serializable
private class QuarkListData(
    val list: List<QuarkFile> = emptyList(),
)

@Serializable
private class QuarkDownloadItem(
    @SerialName("download_url") val downloadUrl: String? = null,
)

@Serializable
private class QuarkPlayData(
    @SerialName("video_list") val videoList: List<QuarkTranscodedVideo> = emptyList(),
)

@Serializable
private class QuarkAccountInfo(
    val data: QuarkAccountData? = null,
)

@Serializable
private class QuarkAccountData(
    val nickname: String? = null,
)

@Serializable
private class QuarkQrResponse(
    val status: Int = 0,
    val message: String = "",
    val data: QuarkQrData? = null,
)

@Serializable
private class QuarkQrData(
    val members: QuarkQrMembers? = null,
)

@Serializable
private class QuarkQrMembers(
    val token: String? = null,
    @SerialName("service_ticket") val serviceTicket: String? = null,
)
