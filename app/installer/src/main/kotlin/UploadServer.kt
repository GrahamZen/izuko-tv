/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package io.github.grahamzen.izuko.installer

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.Locale
import kotlin.concurrent.thread

/**
 * 「用手机传安装包」: 在局域网里起一个小 HTTP 服务, 手机扫码打开网页, 按网页上给的地址在手机上下载安装包再传上来
 * (电视连 GitHub 与镜像都慢、甚至连不上时, 手机的网络往往好得多). 收下的文件经 [UploadedApk] 核对后交给 [InstallFlow.acceptUploaded].
 *
 * 地址带随机令牌, 只有扫到码的人进得来. 只在「用手机传」的窗口开着时运行; 一次只收一个文件, 边收边写盘.
 */
object UploadServer {
    sealed interface Status {
        data object Waiting : Status
        data class Receiving(val received: Long, val total: Long) : Status
        data object Checking : Status
        data class Rejected(val message: String) : Status
        data class Accepted(val version: String) : Status
    }

    /** 起不来: 电视没连局域网, 或端口都开不了. */
    class StartException(val noNetwork: Boolean, message: String) : IOException(message)

    private const val FILE_NAME = "upload.apk"
    private const val PART_NAME = "upload.apk.part"
    private const val FIRST_PORT = 8930
    private const val PORT_ATTEMPTS = 10
    private const val MAX_BYTES = 300L * 1024 * 1024
    private const val MAX_HEAD_BYTES = 16 * 1024
    private const val TOKEN_CHARS = "abcdefghjkmnpqrstuvwxyz23456789"

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    var status: Status = Status.Waiting
        private set

    /** 在主线程回调; 设上时先回一次当前状态. */
    var listener: ((Status) -> Unit)? = null
        set(value) {
            field = value
            value?.invoke(status)
        }

    @Volatile
    private var server: ServerSocket? = null

    @Volatile
    private var uploading: Socket? = null

    @Volatile
    private var token = ""

    /** 手机传来的文件放在下载目录里 (正式版装上来后连目录一起删掉); 下载那边清理旧文件时跳过它们. */
    fun owns(file: File) = file.name == FILE_NAME || file.name == PART_NAME

    /** 开始接收, 返回给手机打开的地址; 已经在运行就返回原来的地址. */
    @Synchronized
    fun start(context: Context): String {
        val app = context.applicationContext
        val host = lanAddress() ?: throw StartException(noNetwork = true, "no LAN address")
        server?.let { if (!it.isClosed) return url(host, it.localPort) }
        val socket = (0 until PORT_ATTEMPTS).firstNotNullOfOrNull { runCatching { ServerSocket(FIRST_PORT + it) }.getOrNull() }
            ?: runCatching { ServerSocket(0) }.getOrElse { throw StartException(noNetwork = false, it.describe()) }
        val random = SecureRandom()
        token = String(CharArray(6) { TOKEN_CHARS[random.nextInt(TOKEN_CHARS.length)] })
        server = socket
        setStatus(Status.Waiting)
        thread(name = "upload-server", isDaemon = true) {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (e: IOException) {
                    break
                }
                thread(name = "upload-client", isDaemon = true) {
                    try {
                        client.use { serve(app, it) }
                    } catch (e: Exception) {
                        InstallFlow.log("Upload connection failed: ${e.describe()}")
                    }
                }
            }
        }
        val address = url(host, socket.localPort)
        InstallFlow.log("Upload server at $address")
        return address
    }

    @Synchronized
    fun stop() {
        server?.let { runCatching { it.close() } }
        server = null
        uploading?.let { runCatching { it.close() } }
        uploading = null
    }

    private fun url(host: String, port: Int) = "http://$host:$port/$token"

    private fun setStatus(value: Status) {
        status = value
        main.post { listener?.invoke(value) }
    }

    /** 局域网里的 IPv4 地址, 无线优先. */
    private fun lanAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { nif -> nif.inetAddresses.toList().filterIsInstance<Inet4Address>().filter { it.isSiteLocalAddress }.map { nif.name to it } }
            .sortedBy { (name, _) -> if (name.startsWith("wlan")) 0 else if (name.startsWith("eth")) 1 else 2 }
            .firstOrNull()?.second?.hostAddress
    }.getOrNull()

    private class Request(val method: String, val path: String, val headers: Map<String, String>)

    private fun serve(context: Context, client: Socket) {
        client.soTimeout = 30_000
        val input = BufferedInputStream(client.getInputStream())
        val output = client.getOutputStream()
        val request = readRequest(input) ?: return
        val base = "/$token"
        val path = request.path.substringBefore('?')
        val route = if (path == base || path.startsWith("$base/")) path.removePrefix(base).trimEnd('/') else null
        when {
            route == null -> respond(output, 404, "text/plain; charset=utf-8", "Not found")
            request.method == "GET" && route.isEmpty() -> respond(output, 200, "text/html; charset=utf-8", page(context))
            request.method == "GET" && route == "/status" -> respond(output, 200, JSON, statusJson())
            request.method == "POST" && route == "/upload" -> receive(context, client, request, input, output)
            else -> respond(output, 404, "text/plain; charset=utf-8", "Not found")
        }
    }

    private fun readRequest(input: InputStream): Request? {
        val head = ByteArrayOutputStream()
        var matched = 0
        val end = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
        while (matched < end.size) {
            val b = input.read()
            if (b < 0 || head.size() >= MAX_HEAD_BYTES) return null
            head.write(b)
            matched = if (b.toByte() == end[matched]) matched + 1 else if (b.toByte() == end[0]) 1 else 0
        }
        val lines = head.toString("UTF-8").split("\r\n")
        val parts = lines.first().split(' ')
        if (parts.size < 2) return null
        val headers = lines.drop(1).mapNotNull { line ->
            val colon = line.indexOf(':')
            if (colon <= 0) null else line.substring(0, colon).trim().lowercase(Locale.ROOT) to line.substring(colon + 1).trim()
        }.toMap()
        return Request(parts[0], parts[1], headers)
    }

    private fun receive(context: Context, client: Socket, request: Request, input: InputStream, output: OutputStream) {
        val length = request.headers["content-length"]?.toLongOrNull()
        if (length == null || length <= 0) return reject(output, 411, context.getString(R.string.upload_reject_incomplete))
        if (length > MAX_BYTES) return reject(output, 413, context.getString(R.string.upload_reject_too_large))
        val dir = InstallFlow.downloadDir(context).apply { mkdirs() }
        val required = (length * 2.5).toLong()
        val available = StatFs(dir.path).availableBytes
        if (available < required) {
            return reject(output, 507, context.getString(R.string.upload_reject_storage, megabytes(required), megabytes(available)))
        }
        synchronized(this) {
            if (uploading != null) return reject(output, 409, context.getString(R.string.upload_reject_busy))
            uploading = client
        }
        val part = File(dir, PART_NAME)
        val file = File(dir, FILE_NAME)
        try {
            if (request.headers["expect"].equals("100-continue", ignoreCase = true)) {
                output.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray())
                output.flush()
            }
            InstallFlow.log("Receiving upload: $length bytes")
            setStatus(Status.Receiving(0, length))
            var received = 0L
            var reported = 0L
            FileOutputStream(part).use { out ->
                val buffer = ByteArray(64 * 1024)
                while (received < length) {
                    val n = input.read(buffer, 0, minOf(buffer.size.toLong(), length - received).toInt())
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    received += n
                    if (received - reported >= 512 * 1024) {
                        reported = received
                        setStatus(Status.Receiving(received, length))
                    }
                }
                out.fd.sync()
            }
            if (received < length) {
                part.delete()
                InstallFlow.log("Upload stopped at $received of $length bytes")
                return reject(output, 400, context.getString(R.string.upload_reject_incomplete))
            }
            file.delete()
            if (!part.renameTo(file)) return reject(output, 500, context.getString(R.string.upload_reject_incomplete))
            setStatus(Status.Checking)
            when (val result = UploadedApk.check(context, file)) {
                is UploadedApk.Result.Rejected -> {
                    file.delete()
                    InstallFlow.log("Upload rejected: ${result.message}")
                    reject(output, 422, result.message)
                }

                is UploadedApk.Result.Ok -> {
                    // 先回给手机再往下走: 电视这边接着会关掉这个窗口、停掉服务
                    respond(output, 200, JSON, """{"ok":true,"version":${jsonString(result.version)}}""")
                    setStatus(Status.Accepted(result.version))
                    InstallFlow.acceptUploaded(context, result.version, file)
                }
            }
        } finally {
            synchronized(this) { if (uploading === client) uploading = null }
            part.delete()
        }
    }

    private fun reject(output: OutputStream, code: Int, message: String) {
        setStatus(Status.Rejected(message))
        respond(output, code, JSON, """{"ok":false,"message":${jsonString(message)}}""")
    }

    private fun statusJson(): String = when (val s = status) {
        Status.Waiting -> """{"state":"waiting"}"""
        is Status.Receiving -> """{"state":"receiving","received":${s.received},"total":${s.total}}"""
        Status.Checking -> """{"state":"checking"}"""
        is Status.Rejected -> """{"state":"rejected","message":${jsonString(s.message)}}"""
        is Status.Accepted -> """{"state":"accepted","version":${jsonString(s.version)}}"""
    }

    private fun respond(output: OutputStream, code: Int, type: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val reason = when (code) {
            200 -> "OK"
            404 -> "Not Found"
            409 -> "Conflict"
            411 -> "Length Required"
            413 -> "Payload Too Large"
            422 -> "Unprocessable Entity"
            507 -> "Insufficient Storage"
            else -> "Error"
        }
        val head = "HTTP/1.1 $code $reason\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n"
        output.write(head.toByteArray(Charsets.UTF_8))
        output.write(bytes)
        output.flush()
    }

    /**
     * 手机打开的网页 (`assets/upload.html`, 文字用电视的语言): 选版本, 一键在手机上下载再传过来, 或者自己下载后选文件.
     * 两个版本查到了的给下载地址、文件名与大小; 没查到的由网页在手机上自己问 GitHub.
     */
    private fun page(context: Context): String {
        val mirrors = InstallFlow.mirrors ?: DownloadMirrors.BUNDLED
        val channel = InstallFlow.channel
        val targets = InstallFlow.channelTargets.toMutableMap()
        InstallFlow.target?.let { if (channel !in targets) targets[channel] = it }
        val pattern = PackageChoice.assetName(BuildConfig.ASSET_PREFIX, "*", InstallFlow.packageSuffixes.firstOrNull() ?: "universal")
        val targetsJson = targets.entries.joinToString(",", "{", "}") { (ch, target) ->
            val file = target.files.first()
            jsonString(ch.name) + ":{" + listOf(
                "file" to jsonString(file.name),
                "size" to (file.size ?: 0L).toString(),
                "version" to jsonString(target.version),
                "sources" to jsonArray(InstallFlow.sourcesOf(file, mirrors)),
            ).joinToString(",") { (k, v) -> "${jsonString(k)}:$v" } + "}"
        }
        val texts = mapOf(
            "probing" to R.string.page_probing,
            "lookup" to R.string.page_lookup,
            "lookup_failed" to R.string.page_lookup_failed,
            "downloading" to R.string.page_downloading,
            "uploading" to R.string.page_uploading,
            "checking" to R.string.page_checking,
            "done" to R.string.page_done,
            "failed" to R.string.page_failed,
            "no_source" to R.string.page_no_source,
            "disconnected" to R.string.page_disconnected,
            "unsupported" to R.string.page_unsupported,
            "no_file" to R.string.page_no_file,
        ).mapValues { context.getString(it.value) }
        val config = "{" + listOf(
            "channel" to jsonString(channel.name),
            "targets" to targetsJson,
            "pattern" to jsonString(pattern),
            "releasePage" to jsonString("https://github.com/${BuildConfig.REPOSITORY}/releases"),
            "releaseLabel" to jsonString(context.getString(R.string.page_release_page)),
            "repo" to jsonString(BuildConfig.REPOSITORY),
            "prefix" to jsonString(BuildConfig.ASSET_PREFIX),
            "suffixes" to jsonArray(InstallFlow.packageSuffixes),
            "mirrors" to jsonArray(mirrors),
            "texts" to texts.entries.joinToString(",", "{", "}") { (k, v) -> "${jsonString(k)}:${jsonString(v)}" },
        ).joinToString(",") { (k, v) -> "${jsonString(k)}:$v" } + "}"
        val device = context.getString(
            R.string.page_device,
            AndroidVersions.name(Build.VERSION.SDK_INT, Build.VERSION.RELEASE),
            deviceAbis().firstOrNull().orEmpty(),
        )
        fun channelTitle(ch: ReleaseChannel, label: Int): String {
            val target = targets[ch] ?: return context.getString(label, "").trim()
            val size = target.files.first().size?.let { " · " + megabytes(it) }.orEmpty()
            return context.getString(label, target.version).trim() + size
        }
        val values = mapOf(
            "lang" to html(Locale.getDefault().language),
            "title" to html(context.getString(R.string.page_title)),
            "device" to html(device),
            "channel_title" to html(context.getString(R.string.page_channel_title)),
            "stable_title" to html(channelTitle(ReleaseChannel.STABLE, R.string.channel_stable)),
            "stable_desc" to html(context.getString(R.string.channel_stable_desc)),
            "test_title" to html(channelTitle(ReleaseChannel.TEST, R.string.channel_test)),
            "test_desc" to html(context.getString(R.string.channel_test_desc)),
            "auto_title" to html(context.getString(R.string.page_auto_title)),
            "auto_desc" to html(context.getString(R.string.page_auto_desc)),
            "auto_button" to html(context.getString(R.string.page_auto_button)),
            "step_download" to html(context.getString(R.string.page_step_download)),
            "step_upload" to html(context.getString(R.string.page_step_upload)),
            "step_check" to html(context.getString(R.string.page_step_check)),
            "manual_title" to html(context.getString(R.string.page_manual_title)),
            "manual_hint" to html(context.getString(R.string.page_manual_hint)),
            "wechat" to html(context.getString(R.string.page_wechat)),
            "manual_step1" to html(context.getString(R.string.page_manual_step1)),
            "version_note" to html(context.getString(R.string.page_version_note)),
            "iphone" to html(context.getString(R.string.page_iphone)),
            "manual_step2" to html(context.getString(R.string.page_manual_step2)),
            "choose" to html(context.getString(R.string.page_choose)),
            "no_file" to html(context.getString(R.string.page_no_file)),
            "upload" to html(context.getString(R.string.page_upload)),
            "footer" to html(context.getString(R.string.page_footer)),
            "config" to config,
        )
        val template = context.assets.open("upload.html").use { it.readBytes().toString(Charsets.UTF_8) }
        return PLACEHOLDER.replace(template) { values[it.groupValues[1]] ?: it.value }
    }

    /** `{{名字}}`. 右花括号也要转义: Android 的正则 (ICU) 不认没转义的 `}`, 桌面 Java 认, JVM 单测测不出来. */
    private val PLACEHOLDER = Regex("""\{\{(\w+)\}\}""")

    private fun jsonArray(values: List<String>) = values.joinToString(",", "[", "]") { jsonString(it) }

    private fun html(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun jsonString(value: String): String = buildString {
        append('"')
        for (c in value) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '<' -> append("\\u003c")
                c < ' ' -> append(String.format(Locale.ROOT, "\\u%04x", c.code))
                else -> append(c)
            }
        }
        append('"')
    }

    private fun megabytes(bytes: Long) = String.format(Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0)

    private const val JSON = "application/json; charset=utf-8"
}
