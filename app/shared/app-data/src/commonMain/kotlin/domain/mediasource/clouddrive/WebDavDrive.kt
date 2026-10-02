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
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.decodeURLPart
import io.ktor.http.decodeURLQueryComponent
import io.ktor.http.encodeURLParameter
import io.ktor.http.encodeURLPathPart
import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import me.him188.ani.utils.xml.Element
import me.him188.ani.utils.xml.Xml
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * WebDAV 服务器 (NAS 的文件共享、AList 等) 当网盘用: 协议的 [CloudDriveProtocol.type] 是 [TYPE], 服务器地址是
 * [DriveHttpConfig.hosts] 的第一个 (如 `http://192.168.1.10:5005/`、`https://nas.example.com/dav/`), 不写 [DriveApiConfig] 的请求模板.
 *
 * - 列目录: `PROPFIND` (`Depth: 1`), 读各项的路径、是不是文件夹、大小、修改时间.
 * - 直链: 文件在服务器上的地址本身; 列目录、播放、取字幕都带账号的 Basic 认证头 ([basicAuthorization]).
 * - 没有按名搜索: 自己网盘的数据源在媒体文件夹的索引里找 (见 [DriveLibraryIndex]). 没有分享、转存、转码、扫码登录.
 *
 * 文件 id 是文件在服务器上的路径 (百分号编码, 文件夹以 `/` 结尾) 再整个按 URL 参数编码一次: 占位地址里的文件 id 不能带 `/`
 * (见 [DrivePlaceholders.fileIdOf]). 路径按段先解码再统一编码 ([canonicalPath]), 各家服务器返回的编码写法不同也对得上.
 */
object WebDav {
    const val TYPE = "webdav"

    /** 服务器地址 (协议的第一个 host); 没写或不是 http(s) 地址时为 null. */
    fun serverUrl(protocol: CloudDriveProtocol): Url? = protocol.http.hosts.firstOrNull()?.let { parseServerUrl(it) }

    /** 用户填的服务器地址; 不是 http(s) 地址时为 null. */
    fun parseServerUrl(text: String): Url? {
        val url = runCatching { Url(text.trim()) }.getOrNull() ?: return null
        if (url.protocol != URLProtocol.HTTP && url.protocol != URLProtocol.HTTPS) return null
        return url.takeIf { it.host.isNotBlank() }
    }

    /** 根文件夹 (服务器地址的路径) 的 id. */
    fun rootIdOf(protocol: CloudDriveProtocol): String = idOf(folderPath(serverUrl(protocol)?.encodedPath.orEmpty()))

    fun idOf(path: String): String = canonicalPath(path).encodeURLParameter()

    fun pathOf(id: String): String = id.decodeURLQueryComponent()

    /** 文件 [id] 的地址 (直链). */
    fun urlOf(protocol: CloudDriveProtocol, id: String): String {
        val server = serverUrl(protocol) ?: throw CloudDriveApiException("WebDAV 服务器地址无效")
        return originOf(server) + pathOf(id)
    }

    /** 文件名 (路径最后一段, 解码后). */
    fun nameOf(id: String): String = decodeSegment(pathOf(id).trimEnd('/').substringAfterLast('/'))

    /** 所在文件夹的 id; 已经是根 (`/`) 时为空. */
    fun parentIdOf(id: String): String {
        val path = pathOf(id).trimEnd('/')
        if (path.isEmpty()) return ""
        return idOf(path.substringBeforeLast('/') + "/")
    }

    /** 文件 [id] 相对服务器地址的路径 (解码后, 界面上显示): 根是 `/`. */
    fun displayPath(protocol: CloudDriveProtocol, id: String): String {
        val root = pathOf(rootIdOf(protocol))
        val path = pathOf(id)
        val relative = if (path.startsWith(root)) path.removePrefix(root) else path.trimStart('/')
        return "/" + relative.trimEnd('/').split('/').filter { it.isNotEmpty() }.joinToString("/") { decodeSegment(it) }
    }

    /** `scheme://host[:port]`, 不带账号. */
    fun originOf(server: Url): String {
        val port = if (server.specifiedPort == 0 || server.specifiedPort == server.protocol.defaultPort) "" else ":${server.specifiedPort}"
        return "${server.protocol.name}://${server.host}$port"
    }

    /** 文件夹的路径以 `/` 结尾 (不带结尾 `/` 的文件夹地址, 有的服务器对 PROPFIND 回重定向). */
    fun folderPath(path: String): String = if (path.endsWith('/')) path else "$path/"

    /** 路径按段解码再编码, 保留开头与结尾的 `/`. */
    fun canonicalPath(path: String): String {
        val absolute = if (path.startsWith('/')) path else "/$path"
        return absolute.split('/').joinToString("/") { segment ->
            if (segment.isEmpty()) segment else decodeSegment(segment).encodeURLPathPart()
        }
    }

    private fun decodeSegment(segment: String): String = runCatching { segment.decodeURLPart() }.getOrDefault(segment)

    @OptIn(ExperimentalEncodingApi::class)
    fun basicAuthorization(username: String, password: String): String =
        "Basic " + Base64.encode("$username:$password".encodeToByteArray())

    /**
     * 读 `PROPFIND` (Depth: 1) 的响应: 文件夹 [folderPath] 的直接子项. 文件夹本身那一项不算. 各家服务器的命名空间前缀
     * (`D:`、`d:`、`lp1:`、不带前缀) 不同, 按本地名认.
     */
    fun parseMultistatus(xml: String, folderPath: String): List<DriveFile> {
        val folder = canonicalPath(folderPath).trimEnd('/')
        val parentId = idOf(folderPath(folderPath))
        val document = Xml.parse(xml)
        return document.getAllElements().filter { localName(it) == "response" }.mapNotNull { response ->
            val href = response.getAllElements().firstOrNull { localName(it) == "href" }?.text()?.trim().orEmpty()
            if (href.isEmpty()) return@mapNotNull null
            val rawPath = pathOfHref(href, folderPath)
            val path = canonicalPath(rawPath)
            if (path.trimEnd('/') == folder) return@mapNotNull null
            val props = okProps(response)
            val dir = props.any { prop -> localName(prop) == "resourcetype" && prop.getAllElements().any { localName(it) == "collection" } } ||
                    (props.none { localName(it) == "resourcetype" } && rawPath.endsWith('/'))
            val name = decodeSegment(path.trimEnd('/').substringAfterLast('/'))
            if (name.isEmpty()) return@mapNotNull null
            DriveFile(
                fid = idOf(if (dir) folderPath(path) else path.trimEnd('/')),
                fileName = name,
                parentFid = parentId,
                dir = dir,
                size = if (dir) 0 else props.firstOrNull { localName(it) == "getcontentlength" }?.text()?.trim()?.toLongOrNull() ?: 0,
                updatedAt = props.firstOrNull { localName(it) == "getlastmodified" }?.text()?.let { parseHttpDate(it) } ?: 0,
                isVideo = !dir && CloudDriveApi.isVideoFileName(name),
            )
        }
    }

    /** 一项响应里状态是 200 的 propstat 里的各个属性 (没写状态的也算). */
    private fun okProps(response: Element): List<Element> =
        response.children().filter { localName(it) == "propstat" }
            .filter { propstat ->
                val status = propstat.children().firstOrNull { localName(it) == "status" }?.text().orEmpty()
                status.isBlank() || status.contains(" 200")
            }
            .flatMap { propstat -> propstat.children().filter { localName(it) == "prop" }.flatMap { it.children() } }

    /** href 可能是完整地址、绝对路径或相对 [folderPath] 的路径. */
    private fun pathOfHref(href: String, folderPath: String): String = when {
        href.startsWith("http://", ignoreCase = true) || href.startsWith("https://", ignoreCase = true) ->
            runCatching { Url(href).encodedPath }.getOrDefault(href)

        href.startsWith('/') -> href
        else -> folderPath(folderPath) + href
    }

    private fun localName(element: Element): String = element.tagName().substringAfter(':').lowercase()

    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private val RFC1123 = Regex("""(\d{1,2})[ -]([A-Za-z]{3})[ -](\d{2,4}) (\d{2}):(\d{2}):(\d{2})""")
    private val ISO = Regex("""(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2}):(\d{2})""")

    /**
     * `getlastmodified` 的时间 (RFC 1123 `Tue, 14 Oct 2026 08:00:00 GMT`, 少数服务器写 ISO 8601), 按 UTC 算成毫秒时间戳; 认不出为 null.
     */
    fun parseHttpDate(text: String): Long? {
        RFC1123.find(text)?.destructured?.let { (day, month, year, hour, minute, second) ->
            val m = MONTHS.indexOf(month.lowercase()) + 1
            if (m == 0) return null
            val y = year.toInt().let { if (it < 100) it + 2000 else it }
            return epochMillis(y, m, day.toInt(), hour.toInt(), minute.toInt(), second.toInt())
        }
        ISO.find(text)?.destructured?.let { (year, month, day, hour, minute, second) ->
            return epochMillis(year.toInt(), month.toInt(), day.toInt(), hour.toInt(), minute.toInt(), second.toInt())
        }
        return null
    }

    /** 公历日期时间 (UTC) 的毫秒时间戳. */
    private fun epochMillis(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): Long {
        // 从 1970-01-01 起的天数 (Howard Hinnant 的 days_from_civil)
        val y = if (month <= 2) year - 1 else year
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val doy = (153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        val days = era.toLong() * 146097 + doe - 719468
        return ((days * 24 + hour) * 60 + minute) * 60_000L + second * 1000L
    }
}

/** 账号密码 (WebDAV 的 Basic 认证). */
internal class DriveCredentials(val username: String, val password: String) {
    val authorization: String get() = WebDav.basicAuthorization(username, password)
}

/**
 * 按 WebDAV 协议列目录 (见 [WebDav]). 连接失败时重试一次.
 */
internal class WebDavClient(
    private val client: HttpClient,
    private val protocol: () -> CloudDriveProtocol,
    private val credentials: suspend () -> DriveCredentials?,
) {
    /** 文件夹 [folderId] 的直接子项. */
    suspend fun list(folderId: String): List<DriveFile> {
        val p = protocol()
        val server = WebDav.serverUrl(p) ?: throw CloudDriveApiException("WebDAV 服务器地址无效")
        val path = WebDav.folderPath(WebDav.pathOf(folderId))
        val url = WebDav.originOf(server) + path
        val authorization = credentials()?.authorization
        var lastError: IOException? = null
        repeat(2) {
            val response = try {
                client.request {
                    method = PROPFIND
                    url(url)
                    timeout {
                        connectTimeoutMillis = p.http.connectTimeoutMillis
                        socketTimeoutMillis = p.http.socketTimeoutMillis
                        requestTimeoutMillis = p.http.requestTimeoutMillis
                    }
                    header("Depth", "1")
                    p.http.userAgent.takeIf { it.isNotBlank() }?.let { header(HttpHeaders.UserAgent, it) }
                    authorization?.let { header(HttpHeaders.Authorization, it) }
                    contentType(ContentType.Application.Xml)
                    setBody(PROPFIND_BODY)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                logger.warn { "WebDAV ${p.id} PROPFIND failed: $e" }
                lastError = e
                return@repeat
            }
            val status = response.status.value
            if (status == 401 || status == 403) throw CloudDriveAuthException("WebDAV 账号或密码不对 (HTTP $status)")
            if (status !in 200..299) throw CloudDriveApiException("WebDAV 列目录出错: HTTP $status ${WebDav.displayPath(p, folderId)}")
            return WebDav.parseMultistatus(response.bodyAsText(), path)
        }
        throw lastError ?: IOException("WebDAV ${p.id} PROPFIND failed")
    }

    private companion object {
        private val logger = logger<WebDavClient>()

        private val PROPFIND = HttpMethod("PROPFIND")

        private const val PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8"?>""" +
                """<d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/></d:prop></d:propfind>"""
    }
}
