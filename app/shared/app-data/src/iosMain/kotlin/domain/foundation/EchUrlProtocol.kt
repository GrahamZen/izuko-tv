/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.foundation

import io.ktor.http.URLBuilder
import io.ktor.http.Url
import kotlinx.atomicfu.atomic
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectBase
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Job
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFRunLoopGetCurrent
import platform.CoreFoundation.CFRunLoopPerformBlock
import platform.CoreFoundation.CFRunLoopRef
import platform.CoreFoundation.CFRunLoopWakeUp
import platform.Foundation.CFBridgingRetain
import platform.Foundation.HTTPBody
import platform.Foundation.HTTPBodyStream
import platform.Foundation.HTTPMethod
import platform.Foundation.NSCachedURLResponse
import platform.Foundation.NSData
import platform.Foundation.NSDefaultRunLoopMode
import platform.Foundation.NSError
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSInputStream
import platform.Foundation.NSLocalizedDescriptionKey
import platform.Foundation.NSRunLoop
import platform.Foundation.NSURLCacheStoragePolicy
import platform.Foundation.NSURLErrorDomain
import platform.Foundation.NSURLErrorTimedOut
import platform.Foundation.NSURLProtocol
import platform.Foundation.NSURLProtocolClientProtocol
import platform.Foundation.NSURLProtocolMeta
import platform.Foundation.NSURLRequest
import platform.Foundation.allHTTPHeaderFields
import platform.Foundation.dataWithBytes

/**
 * 把 Darwin 引擎发往加密直连域名 ([EchSites]) 的请求接过来, 换回真实域名交给 [IosEchTransport] (curl) 发; 别的请求不碰.
 * 装在会话的 `protocolClasses` 里 (见 [IosEchTransport.install]), 作用同安卓上的 OkHttp 拦截器: Ktor 看到的是一个普通的回应,
 * 跳转、Cookie、状态码校验都照常由 Ktor 处理.
 *
 * 回应、数据与结束要在调用 [startLoading] 的线程上交给 [client] (NSURLProtocol 的约定): 记下那条线程的 run loop 与模式, 排到上面执行.
 * [stopLoading] 之后不再回调.
 */
@OptIn(BetaInteropApi::class, ExperimentalForeignApi::class)
internal class EchUrlProtocol @ObjCObjectBase.OverrideInit constructor(
    request: NSURLRequest,
    cachedResponse: NSCachedURLResponse?,
    client: NSURLProtocolClientProtocol?,
) : NSURLProtocol(request, cachedResponse, client) {
    private val stopped = atomic(false)
    private var clientLoop: CFRunLoopRef? = null
    private var clientMode: String? = null
    private var job: Job? = null

    override fun startLoading() {
        clientLoop = CFRunLoopGetCurrent()
        clientMode = NSRunLoop.currentRunLoop.currentMode ?: NSDefaultRunLoopMode
        val transport = installedTransport.value
        val echRequest = request.toEchRequest()
        if (transport == null || echRequest == null) {
            val error = echError(-1, "ECH transport is not installed or the address is not an ECH address")
            post { client?.URLProtocol(this, didFailWithError = error) }
            return
        }
        job = transport.start(echRequest, Listener(), { stopped.value }) { failure ->
            if (failure == null) {
                post { client?.URLProtocolDidFinishLoading(this) }
            } else {
                val error = failure.toNSError()
                post { client?.URLProtocol(this, didFailWithError = error) }
            }
        }
    }

    override fun stopLoading() {
        stopped.value = true
        job?.cancel()
    }

    /** 排到 client 的线程上执行; 停了就不再交. */
    private fun post(block: () -> Unit) {
        if (stopped.value) return
        val loop = clientLoop ?: return
        val mode = CFBridgingRetain(clientMode)
        CFRunLoopPerformBlock(loop, mode) { if (!stopped.value) block() }
        CFRelease(mode)
        CFRunLoopWakeUp(loop)
    }

    private inner class Listener : EchCurlListener {
        override fun onResponse(status: Int, headers: List<Pair<String, String>>) {
            val url = request.URL ?: return
            val fields: Map<Any?, Any?> = headers.groupBy({ it.first }, { it.second }).mapValues { it.value.joinToString(", ") }
            val response = NSHTTPURLResponse(uRL = url, statusCode = status.toLong(), HTTPVersion = "HTTP/1.1", headerFields = fields)
            post {
                client?.URLProtocol(
                    this@EchUrlProtocol,
                    didReceiveResponse = response,
                    cacheStoragePolicy = NSURLCacheStoragePolicy.NSURLCacheStorageNotAllowed,
                )
            }
        }

        override fun onData(bytes: ByteArray) {
            val data = bytes.toNSData()
            post { client?.URLProtocol(this@EchUrlProtocol, didLoadData = data) }
        }
    }

    companion object : NSURLProtocolMeta() {
        override fun canInitWithRequest(request: NSURLRequest): Boolean =
            request.URL?.host?.let { EchSites.originHostOf(it) } != null

        override fun canonicalRequestForRequest(request: NSURLRequest): NSURLRequest = request
    }
}

/** 当前装上的连接层. 协议的实例由系统建, 只能从这里取. */
internal val installedTransport = atomic<IosEchTransport?>(null)

/** 加密直连失败的错误域, 码是 CURLcode (见 [IosEchTransport.recover]). */
internal const val ECH_ERROR_DOMAIN = "IzukoEch"

/** 超时的错误用 NSURL 的超时码 (Darwin 引擎按它报成超时), 其余的用 [ECH_ERROR_DOMAIN], 码是 CURLcode. */
private fun Throwable.toNSError(): NSError {
    val curl = this as? EchCurlException
    return if (curl?.timedOut == true) {
        NSError.errorWithDomain(NSURLErrorDomain, NSURLErrorTimedOut, mapOf<Any?, Any?>(NSLocalizedDescriptionKey to "ECH: $message"))
    } else {
        echError(curl?.code ?: -1, message ?: this::class.simpleName.orEmpty())
    }
}

private fun echError(code: Int, message: String): NSError =
    NSError.errorWithDomain(ECH_ERROR_DOMAIN, code.toLong(), mapOf<Any?, Any?>(NSLocalizedDescriptionKey to "ECH: $message"))

/** 系统交来的请求换成 curl 的请求; 不是加密直连的地址时为 `null`. */
@OptIn(ExperimentalForeignApi::class)
private fun NSURLRequest.toEchRequest(): EchCurlRequest? {
    val url = URL?.absoluteString?.let { runCatching { Url(it) }.getOrNull() } ?: return null
    val origin = EchSites.originHostOf(url.host) ?: return null
    val headers = allHTTPHeaderFields.orEmpty().mapNotNull { (name, value) -> (name as? String)?.let { it to value.toString() } }
    val body = HTTPBody?.toByteArray() ?: HTTPBodyStream?.readFully()
    // Darwin 引擎把套接字超时写在这里; 没设时是系统默认的 60 秒, 设成不限时是一个极大的数
    val timeout = timeoutInterval.takeIf { it > 0 && it < MAX_TIMEOUT_SECONDS }?.let { (it * 1000).toLong() }
    return EchCurlRequest(
        url = URLBuilder(url).apply { host = origin }.build(),
        method = HTTPMethod ?: "GET",
        headers = headers,
        body = body,
        timeoutMillis = timeout,
        image = EchSites.isImageHost(origin),
    )
}

private const val MAX_TIMEOUT_SECONDS = 24 * 3600.0

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    val pointer = bytes ?: return ByteArray(0)
    return if (size == 0) ByteArray(0) else pointer.reinterpret<ByteVar>().readBytes(size)
}

@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.toNSData(): NSData =
    if (isEmpty()) NSData() else usePinned { NSData.dataWithBytes(it.addressOf(0), size.convert()) }

@OptIn(ExperimentalForeignApi::class)
private fun NSInputStream.readFully(): ByteArray {
    val chunks = ArrayList<ByteArray>()
    val buffer = ByteArray(16 * 1024)
    open()
    try {
        while (true) {
            val read = buffer.usePinned { read(it.addressOf(0).reinterpret(), buffer.size.convert()) }
            if (read <= 0) break
            chunks += buffer.copyOf(read.toInt())
        }
    } finally {
        close()
    }
    val result = ByteArray(chunks.sumOf { it.size })
    var offset = 0
    for (chunk in chunks) {
        chunk.copyInto(result, offset)
        offset += chunk.size
    }
    return result
}
