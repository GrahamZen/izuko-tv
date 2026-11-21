/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.foundation

import io.ktor.http.Url
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.refTo
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import me.him188.ani.app.domain.foundation.echcurl.CURLE_ECH_REQUIRED
import me.him188.ani.app.domain.foundation.echcurl.CURLE_FAILED_INIT
import me.him188.ani.app.domain.foundation.echcurl.CURLE_OK
import me.him188.ani.app.domain.foundation.echcurl.CURLE_OPERATION_TIMEDOUT
import me.him188.ani.app.domain.foundation.echcurl.CURLE_SSL_CONNECT_ERROR
import me.him188.ani.app.domain.foundation.echcurl.CURLOPT_ACCEPT_ENCODING
import me.him188.ani.app.domain.foundation.echcurl.CURLOPT_CONNECTTIMEOUT_MS
import me.him188.ani.app.domain.foundation.echcurl.CURLOPT_CUSTOMREQUEST
import me.him188.ani.app.domain.foundation.echcurl.CURLOPT_ECH
import me.him188.ani.app.domain.foundation.echcurl.CURLOPT_ERRORBUFFER
import me.him188.ani.app.domain.foundation.echcurl.CURLOPT_FOLLOWLOCATION
import me.him188.ani.app.domain.foundation.echcurl.CURLOPT_FRESH_CONNECT
import me.him188.ani.app.domain.foundation.echcurl.CURLOPT_HTTPGET
import me.him188.ani.app.domain.foundation.echcurl.CURLOPT_HTTPHEADER
import me.him188.ani.app.domain.foundation.echcurl.CURLOPT_HTTP_VERSION
import me.him188.ani.app.domain.foundation.echcurl.CURLOPT_LOW_SPEED_LIMIT
import me.him188.ani.app.domain.foundation.echcurl.CURLOPT_LOW_SPEED_TIME
import me.him188.ani.app.domain.foundation.echcurl.CURLOPT_NOBODY
import me.him188.ani.app.domain.foundation.echcurl.CURLOPT_NOSIGNAL
import me.him188.ani.app.domain.foundation.echcurl.CURLOPT_PROTOCOLS_STR
import me.him188.ani.app.domain.foundation.echcurl.CURLOPT_PROXY
import me.him188.ani.app.domain.foundation.echcurl.CURLOPT_RESOLVE
import me.him188.ani.app.domain.foundation.echcurl.CURLOPT_SSLVERSION
import me.him188.ani.app.domain.foundation.echcurl.CURLOPT_SSL_VERIFYHOST
import me.him188.ani.app.domain.foundation.echcurl.CURLOPT_SSL_VERIFYPEER
import me.him188.ani.app.domain.foundation.echcurl.CURLOPT_URL
import me.him188.ani.app.domain.foundation.echcurl.CURLcode
import me.him188.ani.app.domain.foundation.echcurl.CURLoption
import me.him188.ani.app.domain.foundation.echcurl.CURL_ERROR_SIZE
import me.him188.ani.app.domain.foundation.echcurl.CURL_HTTP_VERSION_1_1
import me.him188.ani.app.domain.foundation.echcurl.CURL_SSLVERSION_TLSv1_3
import me.him188.ani.app.domain.foundation.echcurl.curl_easy_cleanup
import me.him188.ani.app.domain.foundation.echcurl.curl_easy_init
import me.him188.ani.app.domain.foundation.echcurl.curl_easy_perform
import me.him188.ani.app.domain.foundation.echcurl.curl_easy_reset
import me.him188.ani.app.domain.foundation.echcurl.curl_easy_strerror
import me.him188.ani.app.domain.foundation.echcurl.curl_off_t
import me.him188.ani.app.domain.foundation.echcurl.curl_slist
import me.him188.ani.app.domain.foundation.echcurl.curl_slist_append
import me.him188.ani.app.domain.foundation.echcurl.curl_slist_free_all
import me.him188.ani.app.domain.foundation.echcurl.curl_version
import me.him188.ani.app.domain.foundation.echcurl.izk_ech_accepted
import me.him188.ani.app.domain.foundation.echcurl.izk_ech_tls
import me.him188.ani.app.domain.foundation.echcurl.izk_ech_tls_copy_retry_config
import me.him188.ani.app.domain.foundation.echcurl.izk_ech_tls_hostname_verified
import me.him188.ani.app.domain.foundation.echcurl.izk_ech_tls_new
import me.him188.ani.app.domain.foundation.echcurl.izk_ech_tls_release
import me.him188.ani.app.domain.foundation.echcurl.izk_ech_tls_retry_config_len
import me.him188.ani.app.domain.foundation.echcurl.izk_ech_tls_trust_failed
import me.him188.ani.app.domain.foundation.echcurl.izk_global_init
import me.him188.ani.app.domain.foundation.echcurl.izk_install_tls
import me.him188.ani.app.domain.foundation.echcurl.izk_set_body
import me.him188.ani.app.domain.foundation.echcurl.izk_set_callbacks
import me.him188.ani.app.domain.foundation.echcurl.izk_setopt_buffer
import me.him188.ani.app.domain.foundation.echcurl.izk_setopt_long
import me.him188.ani.app.domain.foundation.echcurl.izk_setopt_slist
import me.him188.ani.app.domain.foundation.echcurl.izk_setopt_str
import platform.posix.size_t
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** 一次加密直连请求, 地址已换回真实域名. */
internal class EchCurlRequest(
    val url: Url,
    val method: String,
    val headers: List<Pair<String, String>>,
    val body: ByteArray?,
    /** 建连多久算超时, 也是多久收不到数据算超时; `null` 为不限. */
    val timeoutMillis: Long?,
    /** 图床的请求 (见 [EchSites.isImageHost]): 另一组连接, 读超时有下限. */
    val image: Boolean,
)

/** 回应按到达顺序交过来, 都在执行请求的线程上. */
internal interface EchCurlListener {
    /** 最终的回应头 (1xx 不算). 正文由 curl 解压, 所以有 Content-Encoding 时它与 Content-Length 都已去掉. */
    fun onResponse(status: Int, headers: List<Pair<String, String>>)

    fun onData(bytes: ByteArray)
}

/** curl 没能完成请求. [code] 是 CURLcode. */
@OptIn(ExperimentalForeignApi::class)
internal class EchCurlException(val code: Int, message: String) : Exception(message) {
    val timedOut: Boolean get() = code == CURLE_OPERATION_TIMEDOUT.toInt()
}

/**
 * iOS 上加密直连的 HTTP 客户端: 静态链进来的 libcurl + BoringSSL (系统的 TLS 不能给别的域名的 ECH 配置).
 *
 * - **解析**: 一律连 [addresses] 里的 Cloudflare 地址 (CURLOPT_RESOLVE), 不查 DNS;
 * - **握手**: TLS 1.3, 要求 ECH, 配置取 [config]. 配置里没有能用的条目、服务器没接受 ECH, 都按失败算, 不退回明文的域名;
 *   ECH 被拒且证书按公共名校验通过时, 用服务器给的新配置当场重试 (最多 [MAX_ECH_RETRIES] 次), 之后的请求也先用它, 直到 [config] 换了一份;
 * - **证书**: 系统的 SecTrust 按真实域名校验 (见 echhttp.def);
 * - **连接**: 接口与图床各一组 curl 句柄, 用完放回、连接留着复用. 只有 HTTP/1.1;
 * - **跳转**: 不跟, 原样交回调用方.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalEncodingApi::class)
internal class EchCurl(
    private val addresses: () -> List<String>,
    private val config: () -> ByteArray,
) {
    private val apiHandles = HandlePool()
    private val imageHandles = HandlePool()

    /** ECH 被拒时服务器给的新配置, 与当时的 [config] ([config] 换了一份就不再用它). */
    private val retryConfig = atomic<Pair<ByteArray, ByteArray>?>(null)

    /** curl 的版本与 TLS 库, 日志用. */
    val version: String by lazy { curl_version()?.toKString().orEmpty() }

    /** 同步发出 [request], 回应交给 [listener]; [cancelled] 为 `true` 时尽快中止 (之后不再回调). 失败抛 [EchCurlException]. */
    fun perform(request: EchCurlRequest, listener: EchCurlListener, cancelled: () -> Boolean) {
        val init = globalInit
        if (init != CURLE_OK) throw EchCurlException(init.toInt(), "curl init failed: ${init.describe()}")
        val pool = if (request.image) imageHandles else apiHandles
        var retries = 0
        while (true) {
            val inUse = configInUse()
            if (!EchConfigList.hasUsableConfig(inUse)) {
                throw EchCurlException(CURLE_ECH_REQUIRED.toInt(), "no usable ECH config")
            }
            val retry = pool.use { easy -> attempt(easy, request, inUse, freshConnect = retries > 0, listener, cancelled) }
                ?: return
            if (retries >= MAX_ECH_RETRIES) throw EchCurlException(CURLE_ECH_REQUIRED.toInt(), "ECH rejected after $retries retries")
            retryConfig.value = config() to retry
            retries++
        }
    }

    /** 当前该用的配置: [config] 还是被拒时的那份就用服务器给的新配置, 换过了就用 [config]. */
    private fun configInUse(): ByteArray {
        val current = config()
        val retry = retryConfig.value ?: return current
        return if (retry.first.contentEquals(current)) retry.second else current
    }

    /** 发一次. 成功返回 `null`; ECH 被拒且拿到了可信的新配置时返回它 (调用方用它重试); 其他失败抛出. */
    private fun attempt(
        easy: COpaquePointer,
        request: EchCurlRequest,
        config: ByteArray,
        freshConnect: Boolean,
        listener: EchCurlListener,
        cancelled: () -> Boolean,
    ): ByteArray? = memScoped {
        val tls = izk_ech_tls_new(request.url.host, 1)
            ?: throw EchCurlException(CURLE_FAILED_INIT.toInt(), "bad host name")
        val transfer = CurlTransfer(easy, listener, cancelled)
        val ref = StableRef.create(transfer)
        var headers: CPointer<curl_slist>? = null
        var resolve: CPointer<curl_slist>? = null
        try {
            val errorBuffer = allocArray<ByteVar>(CURL_ERROR_SIZE)
            errorBuffer[0] = 0
            curl_easy_reset(easy)
            headers = requestHeaders(request)
            resolve = curl_slist_append(null, resolveEntry(request.url))
                ?: throw EchCurlException(CURLE_FAILED_INIT.toInt(), "out of memory")
            setup(easy, request, config, freshConnect, headers, resolve, errorBuffer)
            check("callbacks", izk_set_callbacks(easy, bodyCallback, headerCallback, progressCallback, ref.asCPointer()))
            check("tls", izk_install_tls(easy, tls))

            val result = curl_easy_perform(easy)
            transfer.failure?.let { throw it }
            when {
                result == CURLE_OK -> null
                cancelled() -> throw EchCurlException(result.toInt(), "cancelled")
                transfer.echNotAccepted -> throw EchCurlException(CURLE_ECH_REQUIRED.toInt(), "connection did not use ECH")
                !transfer.responded && (result == CURLE_ECH_REQUIRED || result == CURLE_SSL_CONNECT_ERROR) &&
                    izk_ech_tls_hostname_verified(tls) == 1 -> retryConfigOf(tls)
                    ?: throw failure(result, errorBuffer.toKString(), tls)

                else -> throw failure(result, errorBuffer.toKString(), tls)
            }
        } finally {
            curl_slist_free_all(headers)
            curl_slist_free_all(resolve)
            ref.dispose()
            izk_ech_tls_release(tls)
        }
    }

    private fun setup(
        easy: COpaquePointer,
        request: EchCurlRequest,
        config: ByteArray,
        freshConnect: Boolean,
        headers: CPointer<curl_slist>?,
        resolve: CPointer<curl_slist>,
        errorBuffer: CPointer<ByteVar>,
    ) {
        check("url", izk_setopt_str(easy, CURLOPT_URL, request.url.toString()))
        check("protocols", izk_setopt_str(easy, CURLOPT_PROTOCOLS_STR, "https"))
        // 空字符串 = 不用代理, 也不读环境变量里的代理 (设了代理时调度本来就不走加密直连)
        check("proxy", izk_setopt_str(easy, CURLOPT_PROXY, ""))
        check("nosignal", izk_setopt_long(easy, CURLOPT_NOSIGNAL, 1))
        check("http version", izk_setopt_long(easy, CURLOPT_HTTP_VERSION, CURL_HTTP_VERSION_1_1.toLong()))
        check("redirects", izk_setopt_long(easy, CURLOPT_FOLLOWLOCATION, 0))
        check("verify peer", izk_setopt_long(easy, CURLOPT_SSL_VERIFYPEER, 1))
        check("verify host", izk_setopt_long(easy, CURLOPT_SSL_VERIFYHOST, 2))
        check("tls version", izk_setopt_long(easy, CURLOPT_SSLVERSION, CURL_SSLVERSION_TLSv1_3.toLong()))
        check("ech", izk_setopt_str(easy, CURLOPT_ECH, "ecl:" + Base64.encode(config)))
        check("resolve", izk_setopt_slist(easy, CURLOPT_RESOLVE, resolve))
        check("headers", izk_setopt_slist(easy, CURLOPT_HTTPHEADER, headers))
        // curl 支持的 (gzip / deflate) 都声明并自己解压
        check("encoding", izk_setopt_str(easy, CURLOPT_ACCEPT_ENCODING, ""))
        check("error buffer", izk_setopt_buffer(easy, CURLOPT_ERRORBUFFER, errorBuffer))
        if (freshConnect) check("fresh connect", izk_setopt_long(easy, CURLOPT_FRESH_CONNECT, 1))
        request.timeoutMillis?.let { timeout ->
            val millis = if (request.image) maxOf(timeout, IMAGE_READ_TIMEOUT_MILLIS) else timeout
            check("connect timeout", izk_setopt_long(easy, CURLOPT_CONNECTTIMEOUT_MS, millis))
            // 每秒不到 1 字节、持续这么多秒就算超时, 相当于读超时 (精度 1 秒)
            check("low speed", izk_setopt_long(easy, CURLOPT_LOW_SPEED_LIMIT, 1))
            check("low speed time", izk_setopt_long(easy, CURLOPT_LOW_SPEED_TIME, ((millis + 999) / 1000).coerceAtLeast(1)))
        }
        when (request.method) {
            "GET" -> check("get", izk_setopt_long(easy, CURLOPT_HTTPGET, 1))
            "HEAD" -> check("head", izk_setopt_long(easy, CURLOPT_NOBODY, 1))
            else -> {
                val body = request.body?.takeIf { it.isNotEmpty() }
                check("body", izk_set_body(easy, body?.refTo(0), (body?.size ?: 0).toLong()))
                if (request.method != "POST") check("method", izk_setopt_str(easy, CURLOPT_CUSTOMREQUEST, request.method))
            }
        }
    }

    /** 调用方的请求头, 去掉 curl 自己管的那几个. 有请求体却没给类型时让 curl 别补上表单的类型. */
    private fun requestHeaders(request: EchCurlRequest): CPointer<curl_slist>? {
        var list: CPointer<curl_slist>? = null
        fun add(line: String) {
            list = curl_slist_append(list, line) ?: throw EchCurlException(CURLE_FAILED_INIT.toInt(), "out of memory")
        }
        for ((name, value) in request.headers) {
            if (MANAGED_HEADERS.any { it.equals(name, ignoreCase = true) }) continue
            add("$name: $value")
        }
        add("Expect:")
        if (request.method != "GET" && request.method != "HEAD" && request.headers.none { it.first.equals("Content-Type", ignoreCase = true) }) {
            add("Content-Type:")
        }
        return list
    }

    private fun resolveEntry(url: Url): String {
        val ips = addresses().ifEmpty { throw EchCurlException(CURLE_FAILED_INIT.toInt(), "no ECH address") }
        return "${url.host}:${url.port}:" + ips.joinToString(",") { if (':' in it) "[$it]" else it }
    }

    private fun retryConfigOf(tls: CPointer<izk_ech_tls>): ByteArray? {
        val length = izk_ech_tls_retry_config_len(tls).toInt()
        if (length == 0) return null
        val bytes = ByteArray(length)
        val copied = bytes.usePinned { izk_ech_tls_copy_retry_config(tls, it.addressOf(0).reinterpret(), length.convert()) }
        return bytes.takeIf { copied.toInt() == length }
    }

    private fun failure(result: CURLcode, detail: String, tls: CPointer<izk_ech_tls>): EchCurlException {
        val trust = if (izk_ech_tls_trust_failed(tls) == 1) ", certificate not trusted" else ""
        return EchCurlException(result.toInt(), "curl ${result.toInt()} ${result.describe()}${if (detail.isNotEmpty()) " ($detail)" else ""}$trust")
    }

    private fun check(what: String, result: CURLcode) {
        if (result != CURLE_OK) throw EchCurlException(result.toInt(), "setopt $what: ${result.describe()}")
    }

    /** 空闲的 curl 句柄 (各自留着连接). */
    private class HandlePool {
        private val lock = SynchronizedObject()
        private val idle = ArrayDeque<COpaquePointer>()

        fun <T> use(block: (COpaquePointer) -> T): T {
            val easy = synchronized(lock) { idle.removeLastOrNull() }
                ?: curl_easy_init()
                ?: throw EchCurlException(CURLE_FAILED_INIT.toInt(), "curl_easy_init failed")
            try {
                return block(easy)
            } finally {
                val extra = synchronized(lock) {
                    if (idle.size < MAX_IDLE_HANDLES) {
                        idle.addLast(easy)
                        null
                    } else {
                        easy
                    }
                }
                extra?.let { curl_easy_cleanup(it) }
            }
        }
    }

    private companion object {
        /** ECH 被拒后用服务器给的配置重试几次 (同 ech_http). */
        const val MAX_ECH_RETRIES = 2

        const val MAX_IDLE_HANDLES = 8

        /** 图床请求的读超时下限, 理由同 ConscryptEchTransport. */
        const val IMAGE_READ_TIMEOUT_MILLIS = 6_000L

        /** curl 自己设的请求头, 调用方给的不转. */
        val MANAGED_HEADERS = listOf("Host", "Content-Length", "Accept-Encoding", "Connection", "Transfer-Encoding", "Expect")

        val globalInit: CURLcode by lazy { izk_global_init() }
    }
}

/** 一次传输的状态, 由 curl 的回调 (同一线程) 读写. */
@OptIn(ExperimentalForeignApi::class)
private class CurlTransfer(
    private val easy: COpaquePointer,
    private val listener: EchCurlListener,
    val cancelled: () -> Boolean,
) {
    private var status = 0
    private val headers = ArrayList<Pair<String, String>>()
    var responded = false
        private set

    /** 收到回应时连接却没用上 ECH: 中止, 不把回应交出去. */
    var echNotAccepted = false
        private set

    /** 回调里出的错, 传输中止后由 [EchCurl] 抛出. */
    var failure: Throwable? = null

    fun onHeaderLine(line: String): Boolean {
        val text = line.trimEnd('\r', '\n')
        when {
            text.startsWith("HTTP/") -> {
                if (izk_ech_accepted(easy) != 1) {
                    echNotAccepted = true
                    return false
                }
                status = text.split(' ').getOrNull(1)?.toIntOrNull() ?: 0
                headers.clear()
            }

            text.isEmpty() -> if (status >= 200) respond()
            else -> {
                val colon = text.indexOf(':')
                if (colon > 0) headers += text.substring(0, colon).trim() to text.substring(colon + 1).trim()
            }
        }
        return !cancelled()
    }

    fun onBody(bytes: ByteArray): Boolean {
        respond()
        listener.onData(bytes)
        return !cancelled()
    }

    private fun respond() {
        if (responded) return
        responded = true
        val decoded = headers.any { it.first.equals("Content-Encoding", ignoreCase = true) }
        listener.onResponse(
            status,
            headers.filterNot { (name, _) ->
                name.equals("Transfer-Encoding", ignoreCase = true) ||
                    (decoded && (name.equals("Content-Encoding", ignoreCase = true) || name.equals("Content-Length", ignoreCase = true)))
            },
        )
    }
}

@OptIn(ExperimentalForeignApi::class)
private val headerCallback = staticCFunction { buffer: CPointer<ByteVar>?, size: size_t, count: size_t, data: COpaquePointer? ->
    val length = size * count
    val transfer = data!!.asStableRef<CurlTransfer>().get()
    try {
        val line = buffer?.readBytes(length.toInt())?.decodeToString().orEmpty()
        if (transfer.onHeaderLine(line)) length else 0uL
    } catch (e: Throwable) {
        transfer.failure = e
        0uL
    }
}

@OptIn(ExperimentalForeignApi::class)
private val bodyCallback = staticCFunction { buffer: CPointer<ByteVar>?, size: size_t, count: size_t, data: COpaquePointer? ->
    val length = size * count
    val transfer = data!!.asStableRef<CurlTransfer>().get()
    try {
        if (length == 0uL || buffer == null) return@staticCFunction length
        if (transfer.onBody(buffer.readBytes(length.toInt()))) length else 0uL
    } catch (e: Throwable) {
        transfer.failure = e
        0uL
    }
}

@OptIn(ExperimentalForeignApi::class)
private val progressCallback = staticCFunction { data: COpaquePointer?, _: curl_off_t, _: curl_off_t, _: curl_off_t, _: curl_off_t ->
    if (data!!.asStableRef<CurlTransfer>().get().cancelled()) 1 else 0
}

@OptIn(ExperimentalForeignApi::class)
private fun CURLcode.describe(): String = curl_easy_strerror(this)?.toKString().orEmpty()
