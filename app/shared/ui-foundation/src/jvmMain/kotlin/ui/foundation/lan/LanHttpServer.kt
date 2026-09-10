/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.lan

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.him188.ani.utils.logging.debug
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.Collections

/**
 * 只活一阵子的局域网 HTTP 小服务, 给「电视 ⇄ 手机」这类同一 Wi-Fi 下的一次性交互用
 * (设置页「扫码传到手机」导出日志、搜索页「手机扫码输入」).
 *
 * 形态:
 * - 绑 `0.0.0.0` 的**随机空闲端口**: 地址反正是二维码带过去的, 固定端口只会撞车.
 * - 路径带一段随机 token, 只有 `/<token>/...` 会交给 [handler], 其余一律 404: 只有扫到二维码的人
 *   知道完整地址, 同一局域网里别的设备猜不到. 日志里有用户名/看过的条目, 搜索输入能驱动电视界面,
 *   都不该对全网段裸奔.
 * - [close] 之后链接立刻失效, 正在处理的连接也一并掐断.
 *
 * 不引 ktor-server: 只为「读一个请求、回一页/一个文件」背一整套服务端不划算, 与
 * `TorrentDiagnosticsServer` 同一取舍. 不做 keep-alive / Range / chunked: 每个连接一问一答即关.
 *
 * @param handler 处理落在 token 之内的请求; 在 IO 线程上调用, 可以阻塞. 抛异常回 500.
 * @param port 0 = 随机空闲端口 (默认); 要固定地址 (手机加书签) 时给固定值, 被占会抛 [IOException].
 * @param token null = 每次新生成; 要固定地址时由调用方持久化后传入.
 */
class LanHttpServer(
    private val handler: (LanHttpRequest) -> LanHttpResponse,
    port: Int = 0,
    token: String? = null,
) : AutoCloseable {
    val token: String = token ?: generateToken()

    // backlog 给小一点: 对面只有一部手机
    private val serverSocket = ServerSocket(port, 4)

    private val clients: MutableSet<Socket> = Collections.synchronizedSet(HashSet())

    val port: Int get() = serverSocket.localPort

    /** 二维码里放的根地址 (以 `/` 结尾). */
    fun rootUrl(host: String): String = "http://$host:$port/$token/"

    /**
     * 跑接受循环直到 [close]. 调用方取消协程**不会**打断阻塞中的 `accept()`, 必须配合 [close]
     * (它关掉监听 socket, `accept()` 随即抛 [SocketException] 退出循环).
     */
    suspend fun serve() = withContext(Dispatchers.IO) {
        logger.info { "LAN http server listening on port $port" }
        coroutineScope {
            while (isActive) {
                val client = try {
                    serverSocket.accept()
                } catch (e: IOException) {
                    // close() 触发的正常退出; 别的原因也没法继续, 一样退出
                    if (!serverSocket.isClosed) logger.warn(e) { "LAN http server accept failed, stopping" }
                    break
                }
                clients += client
                launch {
                    try {
                        client.use { handle(it) }
                    } catch (e: IOException) {
                        // 手机那边中途取消是常事, 不值得 warn
                        logger.debug { "LAN http request aborted: $e" }
                    } finally {
                        clients -= client
                    }
                }
            }
        }
        logger.info { "LAN http server on port $port stopped" }
    }

    override fun close() {
        runCatching { serverSocket.close() }
        val snapshot = synchronized(clients) { clients.toList() }
        snapshot.forEach { runCatching { it.close() } }
    }

    private fun handle(client: Socket) {
        client.soTimeout = REQUEST_TIMEOUT_MILLIS
        val input = client.getInputStream().buffered()
        val output = client.getOutputStream().buffered()

        val requestLine = input.readAsciiLine() ?: return
        // 把请求头读完 (直到空行) 再回包: 否则我们回完就关连接, 有些客户端会因为发出去的数据没被读
        // 而收到 RST, 把正常响应当成连接被重置. 顺带取 Content-Length, POST 要靠它读 body
        var contentLength = 0L
        while (true) {
            val line = input.readAsciiLine()
            if (line.isNullOrEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0 && line.substring(0, colon).equals("Content-Length", ignoreCase = true)) {
                contentLength = line.substring(colon + 1).trim().toLongOrNull() ?: 0L
            }
        }

        val parts = requestLine.split(' ')
        if (parts.size < 2) {
            LanHttpResponse.status(400, "Bad Request").writeTo(output, headOnly = false)
            return
        }
        val method = parts[0]
        val rawTarget = parts[1]
        val rawPath = rawTarget.substringBefore('?')
        val prefix = "/$token/"
        if (!rawPath.startsWith(prefix)) {
            LanHttpResponse.status(404, "Not Found").writeTo(output, headOnly = method == "HEAD")
            return
        }
        if (contentLength > MAX_BODY_BYTES) {
            LanHttpResponse.status(413, "Payload Too Large").writeTo(output, headOnly = false)
            return
        }
        val body = if (contentLength > 0) input.readNBytesCompat(contentLength.toInt()) else ByteArray(0)

        val request = LanHttpRequest(
            method = method,
            path = URLDecoder.decode(rawPath.removePrefix(prefix), "UTF-8"),
            query = rawTarget.substringAfter('?', ""),
            body = body,
        )
        val response = try {
            handler(request)
        } catch (e: Exception) {
            logger.warn(e) { "LAN http handler failed for ${request.method} ${request.path}" }
            LanHttpResponse.status(500, "Internal Server Error")
        }
        response.writeTo(output, headOnly = method == "HEAD")
    }

    companion object {
        private val logger = logger<LanHttpServer>()

        /** 读请求的超时; 手机扫完码连上来是毫秒级的事, 超过这个数就是有人在拿别的东西探端口. */
        private const val REQUEST_TIMEOUT_MILLIS = 15_000

        /** 请求体上限: 这里只收表单里的一句话. */
        private const val MAX_BODY_BYTES = 64L * 1024

        private const val TOKEN_LENGTH = 10
        private const val TOKEN_ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789" // 去掉 l/o/0/1, 万一要手抄

        /** 10 位随机 token (小写字母+数字, 去掉易混淆的 l/o/0/1). */
        fun generateToken(): String {
            val random = SecureRandom()
            return buildString(TOKEN_LENGTH) {
                repeat(TOKEN_LENGTH) { append(TOKEN_ALPHABET[random.nextInt(TOKEN_ALPHABET.length)]) }
            }
        }

        /** 读一行 (到 `\n`, 去掉 `\r`); 流结束返回 null. 请求头只会是 ASCII, 逐字节当字符. */
        private fun InputStream.readAsciiLine(): String? {
            val sb = StringBuilder()
            while (true) {
                val b = read()
                if (b < 0) return if (sb.isEmpty()) null else sb.toString()
                if (b == '\n'.code) break
                if (b != '\r'.code) sb.append(b.toChar())
                if (sb.length > 8192) throw IOException("Header line too long")
            }
            return sb.toString()
        }

        /** `InputStream.readNBytes(int)` 是 Java 11 的, 低版本 Android 没有. */
        private fun InputStream.readNBytesCompat(n: Int): ByteArray {
            val out = ByteArray(n)
            var off = 0
            while (off < n) {
                val read = read(out, off, n - off)
                if (read < 0) throw IOException("Unexpected end of request body")
                off += read
            }
            return out
        }
    }
}

/**
 * @param path token 之后的路径, 已 URL 解码, 不带开头的 `/` (根就是空串).
 * @param query `?` 之后的原文 (未解码), 没有则空串.
 * @param body 请求体原始字节, GET 为空数组.
 */
class LanHttpRequest(
    val method: String,
    val path: String,
    val query: String,
    val body: ByteArray,
) {
    /**
     * 把 `application/x-www-form-urlencoded` 的请求体解成键值对列表 (UTF-8, `+` 当空格), 保留顺序与重名
     * (一组 checkbox 就是同名多值).
     */
    fun formFieldList(): List<Pair<String, String>> {
        val text = body.toString(Charsets.UTF_8)
        if (text.isEmpty()) return emptyList()
        return text.split('&').mapNotNull { pair ->
            if (pair.isEmpty()) return@mapNotNull null
            val key = URLDecoder.decode(pair.substringBefore('='), "UTF-8")
            val value = URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8")
            key to value
        }
    }

    /** [formFieldList] 压成 map, 重名取最后一个. */
    fun formFields(): Map<String, String> = formFieldList().toMap()
}

/**
 * @param contentLength 必须与 [writeBody] 实际写出的字节数一致 —— 这是浏览器判断「下载完整」的唯一依据.
 * @param writeBody 在 IO 线程上写响应体; HEAD 请求不会调用它.
 */
class LanHttpResponse(
    val status: Int,
    val reason: String,
    val contentType: String,
    val contentLength: Long,
    val extraHeaders: List<String> = emptyList(),
    val writeBody: (OutputStream) -> Unit,
) {
    internal fun writeTo(output: OutputStream, headOnly: Boolean) {
        val headers = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
            append("Content-Length: ").append(contentLength).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n")
            for (line in extraHeaders) append(line).append("\r\n")
            append("\r\n")
        }
        output.write(headers.toByteArray(Charsets.ISO_8859_1))
        if (!headOnly) writeBody(output)
        output.flush()
    }

    companion object {
        fun bytes(
            body: ByteArray,
            contentType: String,
            status: Int = 200,
            reason: String = "OK",
            extraHeaders: List<String> = emptyList(),
        ): LanHttpResponse = LanHttpResponse(status, reason, contentType, body.size.toLong(), extraHeaders) {
            it.write(body)
        }

        fun html(body: String, status: Int = 200, reason: String = "OK"): LanHttpResponse =
            bytes(body.toByteArray(), "text/html; charset=utf-8", status, reason)

        fun status(code: Int, reason: String): LanHttpResponse =
            bytes("$code $reason\n".toByteArray(), "text/plain; charset=utf-8", code, reason)
    }
}

/** 最基本的 HTML 转义, 给要回显到页面上的用户输入 / 文件名用. */
fun String.escapeHtml(): String = buildString(length) {
    for (c in this@escapeHtml) {
        when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            else -> append(c)
        }
    }
}

/**
 * 本机在局域网里的 IPv4 地址 (手机要连的那个), 没连局域网时为 null. 会走一遍网卡枚举, 在 IO 线程上调.
 *
 * 优先私网段 (家用路由器分的就是这种) 且 wlan / eth 排前面: 有的盒子还挂着 VPN、热点、虚拟接口
 * 的地址, 拿到那种手机连不上. 链路本地 (169.254.x.x) 是没拿到 DHCP 的兜底地址, 也不算.
 * IPv6-only 网络不考虑.
 */
fun findLanAddress(): String? {
    val interfaces = try {
        NetworkInterface.getNetworkInterfaces()?.toList()
    } catch (e: SocketException) {
        return null
    } ?: return null
    return interfaces
        .filter { it.isUp && !it.isLoopback }
        .flatMap { nif -> nif.inetAddresses.toList().filterIsInstance<Inet4Address>().map { nif.name to it } }
        .filter { (_, address) -> !address.isLinkLocalAddress && !address.isLoopbackAddress }
        .sortedWith(compareBy({ (_, address) -> !address.isSiteLocalAddress }, { (name, _) -> interfacePriority(name) }))
        .firstOrNull()
        ?.second
        ?.hostAddress
}

private fun interfacePriority(name: String): Int = when {
    name.startsWith("wlan") -> 0
    name.startsWith("eth") -> 1
    else -> 2
}
