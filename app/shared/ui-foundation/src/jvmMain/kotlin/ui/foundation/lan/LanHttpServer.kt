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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import me.him188.ani.utils.logging.debug
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 局域网 HTTP 小服务, 给「电视 ⇄ 手机」这类同一 Wi-Fi 下的交互用
 * (设置页「扫码传到手机」导出日志、搜索页「手机扫码输入」、常驻的 Web 控制台).
 *
 * 形态:
 * - 绑 `0.0.0.0` 的**随机空闲端口**: 地址反正是二维码带过去的, 固定端口只会撞车 (要加书签的由调用方给固定端口).
 * - 路径带一段随机 token, 只有 `/<token>/...` 会交给 [handler], 其余一律 404: 只有扫到二维码的人
 *   知道完整地址, 同一局域网里别的设备猜不到. 日志里有用户名/看过的条目, 搜索输入能驱动电视界面,
 *   都不该对全网段裸奔.
 * - [close] 之后链接立刻失效, 正在处理的连接也一并掐断.
 *
 * 不引 ktor-server: 只为「读一个请求、回一页/一个文件」背一整套服务端不划算, 与
 * `TorrentDiagnosticsServer` 同一取舍. 不做 keep-alive / Range / chunked: 每个连接一问一答即关.
 *
 * **处理线程是自己的** ([workers]), 不用共享的 `Dispatchers.IO`: 应用自己搜源、下载、解码图片时 IO 池可能整个被占着,
 * 请求排在后面就是「端口还在听、请求一直不回」. 线程数有上限, 满了当场回 503 并记一笔, 不无限堆线程.
 *
 * **自带诊断** (Web 控制台出过整整 3 分钟不回请求、之后自愈, 当时日志里一行线索都没有):
 * - 处理超过 [SLOW_REQUEST_MILLIS] 的请求记一行 (只记路径, 不记查询串);
 * - 卡住超过 [STUCK_MILLIS] 的请求, 把处理它的线程和主线程的栈打出来 (每个请求一次);
 * - 手机在用的时候每 [PROBE_INTERVAL_MILLIS] 从本机连自己一次 (走接受循环 + 处理线程, 不进 handler), 连不上 / 回得慢
 *   就把接受线程与全部处理线程的栈打出来, 恢复时再记一行. **自测正常而手机连不上 = 问题在网络那一段, 不在应用**;
 * - 有请求的时候每 [SUMMARY_INTERVAL_MILLIS] 一行摘要 (请求数 / 最慢 / 慢请求 / 被拒 / 自测), 出事时对得上从哪一刻开始.
 *
 * @param handler 处理落在 token 之内的请求; 在本服务的处理线程上调用, 可以阻塞 (卡住超过 [STUCK_MILLIS] 会被打栈).
 *   抛异常回 500.
 * @param port 0 = 随机空闲端口 (默认); 要固定地址 (手机加书签) 时给固定值, 被占会抛 [IOException].
 * @param token null = 每次新生成; 要固定地址时由调用方持久化后传入.
 */
class LanHttpServer(
    private val handler: (LanHttpRequest) -> LanHttpResponse,
    port: Int = 0,
    token: String? = null,
) : AutoCloseable {
    val token: String = token ?: generateToken()

    // 接受循环只管收, 不会被处理拖住; 队列留些余量: 手机网页同时有两路轮询 + 提交, 浏览器还会预连接
    // 先开 SO_REUSEADDR 再绑: 「重置地址」关掉旧服务后马上重开同一个固定端口, 不开的话旧连接还挂在 TIME_WAIT 时绑不上,
    // 只能退到随机端口, 手机上存的书签随之失效 (用户日志里连按几次重置, 端口变成 39105 / 38541)
    private val serverSocket = ServerSocket().also { s ->
        try {
            s.reuseAddress = true
            s.bind(InetSocketAddress(port), BACKLOG)
        } catch (e: IOException) {
            runCatching { s.close() }
            throw e
        }
    }

    /** 连过来过的对端 IP: 每个第一次来时记一行 (扫码连不上时分得清「根本没到」还是「从别的网段来的」). */
    private val seenClients: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val clients: MutableSet<Socket> = Collections.synchronizedSet(HashSet())

    val port: Int get() = serverSocket.localPort

    private val threadSeq = AtomicInteger()

    /** 处理连接的线程 (见类注释): 按需开、空闲 30 秒回收; 满了 execute 抛拒绝, 由接受循环回 503. */
    private val workers = ThreadPoolExecutor(0, MAX_WORKERS, 30L, TimeUnit.SECONDS, SynchronousQueue()) { r ->
        Thread(r, "lan-http-${serverSocket.localPort}-${threadSeq.incrementAndGet()}").apply { isDaemon = true }
    }

    /** 一个正在处理的连接; 看门狗据此找卡住的请求. */
    private class InFlight(val id: Long, val acceptedAtNanos: Long) {
        @Volatile
        var thread: Thread? = null

        @Volatile
        var startedAtNanos: Long = 0L

        /** 「方法 路径」(不含 token 与查询串), 读完请求行才知道; 之前是 [READING_TARGET]. */
        @Volatile
        var target: String = READING_TARGET

        @Volatile
        var dumped: Boolean = false
    }

    private val inFlight = ConcurrentHashMap<Long, InFlight>()
    private val requestSeq = AtomicLong()

    @Volatile
    private var acceptThread: Thread? = null

    /** 最近一次落在 token 之内的请求 (= 手机真的在用); 0 = 还没有过. 自测只在手机在用时做. */
    @Volatile
    private var lastRealRequestNanos = 0L

    // 摘要计数: 看门狗每个摘要周期读一次并清零
    private val statRequests = AtomicInteger()
    private val statSlow = AtomicInteger()
    private val statRejected = AtomicInteger()
    private val statMaxMillis = AtomicLong()

    @Volatile
    private var lastRejectLogNanos = 0L

    /** 二维码里放的根地址 (以 `/` 结尾). */
    fun rootUrl(host: String): String = "http://$host:$port/$token/"

    /**
     * 跑接受循环直到 [close]. 调用方取消协程**不会**打断阻塞中的 `accept()`, 必须配合 [close]
     * (它关掉监听 socket, `accept()` 随即抛 [SocketException] 退出循环).
     */
    suspend fun serve() = withContext(Dispatchers.IO) {
        logger.info { "LAN http server listening on port $port" }
        acceptThread = Thread.currentThread()
        val watchdog = Thread({ watchdogLoop() }, "lan-http-$port-watchdog").apply {
            isDaemon = true
            start()
        }
        try {
            while (isActive) {
                val client = try {
                    serverSocket.accept()
                } catch (e: IOException) {
                    // close() 触发的正常退出; 别的原因也没法继续, 一样退出
                    if (!serverSocket.isClosed) logger.warn(e) { "LAN http server accept failed, stopping" }
                    break
                }
                dispatch(client)
            }
        } finally {
            acceptThread = null
            watchdog.interrupt()
            workers.shutdown()
        }
        logger.info { "LAN http server on port $port stopped" }
    }

    override fun close() {
        runCatching { serverSocket.close() }
        val snapshot = synchronized(clients) { clients.toList() }
        snapshot.forEach { runCatching { it.close() } }
        workers.shutdown()
    }

    private fun dispatch(client: Socket) {
        client.inetAddress?.hostAddress?.let { ip ->
            if (seenClients.add(ip)) logger.info { "LAN http port $port: first connection from $ip" }
        }
        val record = InFlight(requestSeq.incrementAndGet(), System.nanoTime())
        clients += client
        inFlight[record.id] = record
        try {
            workers.execute { serveClient(client, record) }
        } catch (e: RejectedExecutionException) {
            // 处理线程全满 (有请求卡住了, 看门狗会打栈) 或服务正在关: 当场回 503, 别让手机干等到超时
            inFlight.remove(record.id)
            clients -= client
            statRejected.incrementAndGet()
            val now = System.nanoTime()
            if (now - lastRejectLogNanos > REJECT_LOG_INTERVAL_NANOS) {
                lastRejectLogNanos = now
                logger.warn { "LAN http server on port $port busy: ${inFlight.size} requests in flight, rejecting connections" }
            }
            runCatching {
                client.use { LanHttpResponse.status(503, "Service Unavailable").writeTo(it.getOutputStream(), headOnly = false) }
            }
        }
    }

    private fun serveClient(client: Socket, record: InFlight) {
        record.thread = Thread.currentThread()
        record.startedAtNanos = System.nanoTime()
        // 线程是现成或现开的, 正常几毫秒; 等了很久 = 整个进程都没抢到 CPU
        val waitedMillis = (record.startedAtNanos - record.acceptedAtNanos) / 1_000_000
        if (waitedMillis > SLOW_START_MILLIS) {
            logger.warn { "LAN http request #${record.id} waited ${waitedMillis}ms for a worker thread" }
        }
        try {
            client.use { handle(it, record) }
        } catch (e: IOException) {
            // 手机那边中途取消是常事, 不值得 warn
            logger.debug { "LAN http request aborted: $e" }
        } catch (e: Exception) {
            // handler 的异常 handle() 已经转成 500; 走到这里的是解析请求时的意外 (比如路径里非法的 %XX).
            // 必须接住: 处理线程上漏出去的异常在 Android 上会带崩整个进程
            logger.warn(e) { "LAN http request failed: ${record.target}" }
        } finally {
            clients -= client
            inFlight.remove(record.id)
            if (record.target != PROBE_TARGET && record.target != READING_TARGET) {
                val tookMillis = (System.nanoTime() - record.acceptedAtNanos) / 1_000_000
                statRequests.incrementAndGet()
                statMaxMillis.accumulateAndGet(tookMillis) { a, b -> maxOf(a, b) }
                if (tookMillis > SLOW_REQUEST_MILLIS) {
                    statSlow.incrementAndGet()
                    logger.info { "LAN http slow request: ${record.target} took ${tookMillis}ms" }
                }
            }
        }
    }

    private fun handle(client: Socket, record: InFlight) {
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
            // token 之外的只有探端口的 (与自测), 路径不进日志
            record.target = if (rawPath == PROBE_PATH) PROBE_TARGET else "$method (outside token)"
            LanHttpResponse.status(404, "Not Found").writeTo(output, headOnly = method == "HEAD")
            return
        }
        // 日志里只记路径, 不记查询串 (里面可能有搜索词)
        record.target = "$method /" + rawPath.removePrefix(prefix)
        lastRealRequestNanos = System.nanoTime()
        if (contentLength > MAX_BODY_BYTES) {
            LanHttpResponse.status(413, "Payload Too Large").writeTo(output, headOnly = false)
            return
        }
        val body = if (contentLength > 0) input.readNBytesCompat(contentLength.toInt()) else ByteArray(0)

        // 路径里非法的 %XX: 回 400, 而不是抛出去由 serveClient 接住后一声不响地断开连接
        val path = try {
            URLDecoder.decode(rawPath.removePrefix(prefix), "UTF-8")
        } catch (e: IllegalArgumentException) {
            LanHttpResponse.status(400, "Bad Request").writeTo(output, headOnly = method == "HEAD")
            return
        }
        val request = LanHttpRequest(
            method = method,
            path = path,
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

    // ============================ 看门狗 ============================

    /** 独立线程 (不跟处理线程抢): 卡住的请求打栈, 手机在用时自测, 定期摘要. 见类注释. */
    private fun watchdogLoop() {
        var lastProbeNanos = 0L
        var lastSummaryNanos = System.nanoTime()
        var probeFailingSinceNanos = 0L
        try {
            while (!serverSocket.isClosed) {
                Thread.sleep(WATCHDOG_TICK_MILLIS)
                val now = System.nanoTime()
                for (r in inFlight.values) {
                    val t = r.thread ?: continue
                    // 还没发请求的空连接 (浏览器预连接) 不算卡住: 读请求有自己的超时, 到点就关
                    if (r.target == READING_TARGET) continue
                    val stuckMillis = (now - r.startedAtNanos) / 1_000_000
                    if (r.dumped || stuckMillis < STUCK_MILLIS) continue
                    r.dumped = true
                    logger.warn {
                        "LAN http request stuck for ${stuckMillis}ms: ${r.target} (in flight: ${inFlight.size})\n" +
                                stackOf(t, t.stackTrace) + mainThreadStack()
                    }
                }
                val active = lastRealRequestNanos != 0L && now - lastRealRequestNanos < ACTIVE_WINDOW_NANOS
                if (active && now - lastProbeNanos >= PROBE_INTERVAL_MILLIS * 1_000_000) {
                    lastProbeNanos = now
                    val problem = selfProbe()
                    if (problem != null && probeFailingSinceNanos == 0L) {
                        probeFailingSinceNanos = now
                        logger.warn {
                            "LAN http self-probe on port $port failed: $problem (in flight: ${inFlight.size})\n" + serverStacks()
                        }
                    } else if (problem == null && probeFailingSinceNanos != 0L) {
                        logger.info {
                            "LAN http self-probe on port $port ok again after ${(now - probeFailingSinceNanos) / 1_000_000_000}s"
                        }
                        probeFailingSinceNanos = 0L
                    }
                }
                if (now - lastSummaryNanos >= SUMMARY_INTERVAL_MILLIS * 1_000_000) {
                    lastSummaryNanos = now
                    val requests = statRequests.getAndSet(0)
                    val slow = statSlow.getAndSet(0)
                    val rejected = statRejected.getAndSet(0)
                    val maxMillis = statMaxMillis.getAndSet(0)
                    if (requests > 0 || rejected > 0) logger.info {
                        "LAN http port $port, last ${SUMMARY_INTERVAL_MILLIS / 60_000} min: $requests requests, " +
                                "max ${maxMillis}ms, slow $slow, rejected $rejected, " +
                                "self-probe ${if (probeFailingSinceNanos == 0L) "ok" else "FAILING"}"
                    }
                }
            }
        } catch (_: InterruptedException) {
            // serve() 退出时打断
        }
    }

    /**
     * 从本机连自己一次 (token 之外的路径, 回 404): 走一遍接受循环 + 处理线程 + socket 读写, 不进 handler.
     * @return 出了什么问题; 正常为 null
     */
    private fun selfProbe(): String? {
        val start = System.nanoTime()
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress("127.0.0.1", port), PROBE_TIMEOUT_MILLIS)
                s.soTimeout = PROBE_TIMEOUT_MILLIS
                val out = s.getOutputStream()
                out.write("HEAD $PROBE_PATH HTTP/1.0\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                out.flush()
                val status = s.getInputStream().readAsciiLine()
                val tookMillis = (System.nanoTime() - start) / 1_000_000
                when {
                    status == null || !status.startsWith("HTTP/1.1 404") -> "unexpected reply after ${tookMillis}ms: $status"
                    tookMillis > PROBE_SLOW_MILLIS -> "slow reply: ${tookMillis}ms"
                    else -> null
                }
            }
        } catch (e: IOException) {
            "${e::class.simpleName} after ${(System.nanoTime() - start) / 1_000_000}ms: ${e.message}"
        }
    }

    /** 接受线程 + 本服务的全部处理线程 + 主线程. */
    private fun serverStacks(): String = buildString {
        val all = Thread.getAllStackTraces()
        acceptThread?.let { t -> append(stackOf(t, all[t] ?: t.stackTrace)).append('\n') }
        val prefix = "lan-http-$port-"
        for ((t, frames) in all) {
            if (t.name.startsWith(prefix) && t !== Thread.currentThread()) append(stackOf(t, frames)).append('\n')
        }
        append(mainThreadStack())
    }

    /** 主线程在干什么: handler 要等主线程的时候 (比如操作播放器), 真正卡住的是它. */
    private fun mainThreadStack(): String {
        val main = Thread.getAllStackTraces().entries.firstOrNull { it.key.name == "main" } ?: return ""
        return "\n" + stackOf(main.key, main.value)
    }

    private fun stackOf(t: Thread, frames: Array<StackTraceElement>): String = buildString {
        append("  thread ").append(t.name).append(" (").append(t.state).append("):")
        for (frame in frames.take(MAX_STACK_FRAMES)) append("\n    at ").append(frame)
    }

    companion object {
        private val logger = logger<LanHttpServer>()

        /** 读请求的超时; 手机扫完码连上来是毫秒级的事, 超过这个数就是有人在拿别的东西探端口. */
        private const val REQUEST_TIMEOUT_MILLIS = 15_000

        /** 请求体上限: 这里只收表单里的一句话. */
        private const val MAX_BODY_BYTES = 64L * 1024

        /** 接受队列长度. */
        private const val BACKLOG = 16

        /** 同时处理的连接上限, 超过回 503. 手机网页正常同时只有两三个. */
        private const val MAX_WORKERS = 16

        private const val SLOW_START_MILLIS = 1_000L
        private const val SLOW_REQUEST_MILLIS = 3_000L
        private const val STUCK_MILLIS = 10_000L
        private const val WATCHDOG_TICK_MILLIS = 5_000L
        private const val PROBE_INTERVAL_MILLIS = 20_000L
        private const val PROBE_TIMEOUT_MILLIS = 3_000
        private const val PROBE_SLOW_MILLIS = 1_000L

        /** 最近这么久内有过 token 之内的请求 = 手机在用. */
        private const val ACTIVE_WINDOW_NANOS = 120_000_000_000L
        private const val SUMMARY_INTERVAL_MILLIS = 300_000L
        private const val REJECT_LOG_INTERVAL_NANOS = 10_000_000_000L
        private const val MAX_STACK_FRAMES = 40

        /** 自测用的路径: 在 token 之外, 回 404, 不计入请求统计. */
        private const val PROBE_PATH = "/__probe"
        private const val PROBE_TARGET = "(self-probe)"

        /**
         * 连上了但还没发请求行: 浏览器常会预先开好连接闲着 (预连接), 等到读请求超时 ([REQUEST_TIMEOUT_MILLIS]) 自然关掉.
         * 这种不算卡住、不算慢请求、不进统计 —— 否则每个闲着的预连接都会打一整段线程栈.
         */
        private const val READING_TARGET = "(reading request)"

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
 * @param writeBody 在处理线程上写响应体; HEAD 请求不会调用它.
 */
class LanHttpResponse(
    val status: Int,
    val reason: String,
    val contentType: String,
    val contentLength: Long,
    val extraHeaders: List<String> = emptyList(),
    /** 默认不让缓存 (页面与接口都是即时状态); 转发的图片给 `private, max-age=…`, 手机重开列表不用再要. */
    val cacheControl: String = "no-store",
    val writeBody: (OutputStream) -> Unit,
) {
    internal fun writeTo(output: OutputStream, headOnly: Boolean) {
        val headers = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
            append("Content-Length: ").append(contentLength).append("\r\n")
            append("Cache-Control: ").append(cacheControl).append("\r\n")
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
            cacheControl: String = "no-store",
        ): LanHttpResponse = LanHttpResponse(status, reason, contentType, body.size.toLong(), extraHeaders, cacheControl) {
            it.write(body)
        }

        fun html(body: String, status: Int = 200, reason: String = "OK"): LanHttpResponse =
            bytes(body.toByteArray(), "text/html; charset=utf-8", status, reason)

        fun status(code: Int, reason: String): LanHttpResponse =
            bytes("$code $reason\n".toByteArray(), "text/plain; charset=utf-8", code, reason)

        /**
         * 把 [file] 当附件下载. Content-Length 按此刻的长度算, 之后只送这么多字节: 文件可能边传边在追加 (日志),
         * 不定长就会跟声明的长度对不上, 浏览器要么截断要么等到超时.
         */
        fun fileSnapshot(file: File, contentType: String): LanHttpResponse {
            val length = file.length()
            return LanHttpResponse(
                status = 200,
                reason = "OK",
                contentType = contentType,
                contentLength = length,
                extraHeaders = listOf("Content-Disposition: attachment; filename=\"${file.name}\""),
            ) { output ->
                file.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var remaining = length
                    while (remaining > 0) {
                        val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        remaining -= read
                    }
                }
            }
        }
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
 * VPN / 隧道 / 移动数据 / Wi-Fi 直连这类网卡 ([isVirtualInterface]) 直接跳过: VPN 的虚拟地址常是 172.19.x / 10.x 这种私网段,
 * 而有的电视的 Wi-Fi 网卡不叫 wlan (排不到前面), 电视一开 VPN 就可能把它写进二维码. IPv6-only 网络不考虑.
 */
fun findLanAddress(): String? {
    val interfaces = try {
        NetworkInterface.getNetworkInterfaces()?.toList()
    } catch (e: SocketException) {
        return null
    } ?: return null
    return interfaces
        .filter { it.isUp && !it.isLoopback && !isVirtualInterface(it.name) }
        .flatMap { nif -> nif.inetAddresses.toList().filterIsInstance<Inet4Address>().map { nif.name to it } }
        .filter { (_, address) -> !address.isLinkLocalAddress && !address.isLoopbackAddress }
        .sortedWith(compareBy({ (_, address) -> !address.isSiteLocalAddress }, { (name, _) -> interfacePriority(name) }))
        .firstOrNull()
        ?.second
        ?.hostAddress
}

/** 日志用: 在用的 IPv4 网卡一览 (`wlan0=192.168.31.174, tun0=172.19.0.1 (skipped)`), 排查扫码连不上时看选的是哪张. */
fun lanInterfacesSummary(): String = runCatching {
    NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { nif ->
            nif.inetAddresses.toList().filterIsInstance<Inet4Address>()
                .map { nif.name + "=" + it.hostAddress + if (isVirtualInterface(nif.name)) " (skipped)" else "" }
        }
        .joinToString(", ")
        .ifEmpty { "none" }
}.getOrElse { "unavailable: $it" }

/** VPN / 隧道 (tun / ppp / WireGuard / IPsec)、移动数据 (rmnet / ccmni)、464XLAT (clat / v4-)、Wi-Fi 直连 (p2p) 等手机连不到的网卡. */
private fun isVirtualInterface(name: String): Boolean =
    VIRTUAL_INTERFACE_PREFIXES.any { name.startsWith(it) }

private val VIRTUAL_INTERFACE_PREFIXES = listOf("tun", "tap", "ppp", "wg", "ipsec", "utun", "clat", "v4-", "dummy", "rmnet", "ccmni", "p2p")

private fun interfacePriority(name: String): Int = when {
    name.startsWith("wlan") -> 0
    name.startsWith("eth") -> 1
    else -> 2
}
