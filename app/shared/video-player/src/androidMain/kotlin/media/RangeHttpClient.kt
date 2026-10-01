/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.media

import androidx.annotation.OptIn as AndroidxOptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.UnknownHostException
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * 多连接分块下载 ([ParallelRangeDataSource]) 用的 HTTP 客户端.
 *
 * 有的网盘的下载域名每次解析出一组七八个节点 (同一网段), 记录一分钟就换一组; 偶尔一整组都坏了:
 * 连不上, 或者连得上却不回 TLS 握手. 每个节点等满超时, 一组要等一分钟以上, 过一分钟换了一组又马上能连. 所以:
 * - 连接超时短 ([CONNECT_TIMEOUT_MILLIS]), TLS 握手也单独限时 ([HANDSHAKE_TIMEOUT_MILLIS], 见 [HandshakeTimeoutSocketFactory]);
 * - 每次连接只试几个节点, 连不上的记下来跳过, 别的网段排前面, 系统给的这组出过问题就把公共 DNS ([DohResolver]) 给的也拿来 ([FailoverDns]),
 *   由分块下载换节点重试;
 * - 每次解析与新建连接记进日志 (节点、用时、错误), 响应头来得慢与请求失败也记.
 *
 * 客户端按代理配置共用 (连接池跟着共用, 分块请求能复用连接). 只走 HTTP/1.1, 每路分块各占一个连接.
 */
internal object RangeHttpClients {
    private val logger = logger<RangeHttpClients>()

    private const val CONNECT_TIMEOUT_MILLIS = 3_000L
    private const val HANDSHAKE_TIMEOUT_MILLIS = 3_000
    private const val READ_TIMEOUT_MILLIS = 10_000L
    private const val MAX_IDLE_CONNECTIONS = 64
    private const val KEEP_ALIVE_MINUTES = 5L

    /** 同时在等响应头的请求最多几个 (每个主机同样): 多开连接的 48 路分块加上补字体的, 留些余量. */
    private const val MAX_CONCURRENT_REQUESTS = 64

    private val dns = FailoverDns(Dns.SYSTEM, DohResolver::lookup)
    private val clients = ConcurrentHashMap<PlaybackProxyConfig, OkHttpClient>()
    private val direct: OkHttpClient by lazy { build(null) }

    /** 系统默认的证书校验 (同 OkHttp 默认用的那个), 套接字工厂换成握手限时的. */
    private val trustManager: X509TrustManager by lazy {
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).run {
            init(null as KeyStore?)
            trustManagers.filterIsInstance<X509TrustManager>().first()
        }
    }
    private val sslSocketFactory: SSLSocketFactory by lazy {
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }
        HandshakeTimeoutSocketFactory(context.socketFactory, HANDSHAKE_TIMEOUT_MILLIS)
    }

    @AndroidxOptIn(UnstableApi::class)
    fun factory(proxyConfig: PlaybackProxyConfig?, userAgent: String, headers: Map<String, String>): HttpDataSource.Factory {
        val client = if (proxyConfig == null) direct else clients.getOrPut(proxyConfig) { build(proxyConfig) }
        return OkHttpDataSource.Factory(client)
            .setUserAgent(userAgent)
            .setDefaultRequestProperties(headers)
    }

    private fun build(proxyConfig: PlaybackProxyConfig?): OkHttpClient = OkHttpClient.Builder()
        // 只用 HTTP/1.1: 下载节点支持 HTTP/2, 那样几路分块会挤进同一个连接, 而网盘是按连接限速的
        .protocols(listOf(Protocol.HTTP_1_1))
        // media3 的 OkHttp 数据源用 enqueue 发请求, OkHttp 默认每个主机只放 5 个请求同时等响应头. 网盘首字节要两三秒,
        // 十几路分块得分好几波才发得出去, 跳转后要的那个请求还排在刚丢下的旧请求后面 (实测一个几字节的请求等了近 6 秒)
        .dispatcher(
            Dispatcher().apply {
                maxRequests = MAX_CONCURRENT_REQUESTS
                maxRequestsPerHost = MAX_CONCURRENT_REQUESTS
            },
        )
        // 默认只留 5 个空闲连接: 非会员 16 路 (多开时 48 路, 另有补字体的) 分块时多出来的连接下完一块就被关, 下一块又要重新建连握手 (跨洋要好几秒)
        .connectionPool(ConnectionPool(MAX_IDLE_CONNECTIONS, KEEP_ALIVE_MINUTES, TimeUnit.MINUTES))
        .connectTimeout(CONNECT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        .readTimeout(READ_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        .sslSocketFactory(sslSocketFactory, trustManager)
        .dns(dns)
        .eventListenerFactory { ConnectLogger(dns) }
        .apply { proxyConfig?.let { playbackProxy(it) } }
        .build()

    /**
     * 每个请求一个: 记新建连接用了多久、连没连上, 并告诉 [FailoverDns] 哪个节点连不上;
     * 响应头来得慢 ([SLOW_RESPONSE_LOG_MILLIS]) 与请求失败也记 (复用连接时没有建连的那几行).
     */
    private class ConnectLogger(private val dns: FailoverDns) : EventListener() {
        private var callStartedAt = 0L
        private var startedAt = 0L

        override fun callStart(call: Call) {
            callStartedAt = System.nanoTime()
        }

        override fun responseHeadersEnd(call: Call, response: Response) {
            val took = millisSince(callStartedAt)
            if (took >= SLOW_RESPONSE_LOG_MILLIS) {
                logger.info { "Response ${response.code} from ${call.request().url.host} after ${took}ms (${call.request().header("Range")})" }
            }
        }

        override fun callFailed(call: Call, ioe: IOException) {
            // 读的一方不要了而取消的分块 (见 ParallelRangeReader 的 Chunk.cancel), 不是出错
            if (call.isCanceled()) return
            logger.warn { "Request to ${call.request().url.host} (${call.request().header("Range")}) failed after ${millisSince(callStartedAt)}ms: $ioe" }
        }

        override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
            startedAt = System.nanoTime()
        }

        override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) {
            dns.markReachable(inetSocketAddress.address)
            logger.info { "Connected ${call.request().url.host} via ${inetSocketAddress.address?.hostAddress} in ${elapsedMillis()}ms" }
        }

        override fun connectFailed(
            call: Call,
            inetSocketAddress: InetSocketAddress,
            proxy: Proxy,
            protocol: Protocol?,
            ioe: IOException,
        ) {
            dns.markUnreachable(inetSocketAddress.address)
            logger.warn { "Connect to ${call.request().url.host} via ${inetSocketAddress.address?.hostAddress} failed after ${elapsedMillis()}ms: $ioe" }
        }

        private fun elapsedMillis() = millisSince(startedAt)
    }

    private const val SLOW_RESPONSE_LOG_MILLIS = 2_000L

    private fun millisSince(nanos: Long) = (System.nanoTime() - nanos) / 1_000_000
}

/**
 * 建 TLS 套接字时先把读超时设成 [timeoutMillis]: OkHttp 的 TLS 握手用的是套接字的读超时 (平时是 [RangeHttpClients] 的 10 秒),
 * 坏掉的节点常常连得上却不回握手, 每个要等 10 秒以上. 握完手 OkHttp 发请求前会把读超时设回它自己的配置.
 */
internal class HandshakeTimeoutSocketFactory(
    private val delegate: SSLSocketFactory,
    private val timeoutMillis: Int,
) : SSLSocketFactory() {
    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites

    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

    override fun createSocket(socket: Socket, host: String, port: Int, autoClose: Boolean): Socket =
        delegate.createSocket(socket, host, port, autoClose).limited()

    override fun createSocket(): Socket = delegate.createSocket().limited()

    override fun createSocket(host: String, port: Int): Socket = delegate.createSocket(host, port).limited()

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        delegate.createSocket(host, port, localHost, localPort).limited()

    override fun createSocket(host: InetAddress, port: Int): Socket = delegate.createSocket(host, port).limited()

    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        delegate.createSocket(address, port, localAddress, localPort).limited()

    private fun Socket.limited(): Socket = apply { soTimeout = timeoutMillis }
}

/**
 * 给下载域名挑节点. 同一组节点在同一网段, 常常一起坏, 所以:
 * - 跳过最近连不上的节点 (记 [forgetAfterMillis], 过后再试);
 * - 系统解析出的这组里有节点连不上过 (或解析失败), 就把 [alternatives] (公共 DNS, 结果留 [alternativesTtlMillis]) 给的也拿来;
 * - 最近没出过问题的网段排前面, 同一档里打乱; 每次只给 [maxAddresses] 个 (一次请求最多等这几个的超时).
 *
 * 都连不上过时照样给系统那组, 让请求自己失败.
 */
internal class FailoverDns(
    private val system: Dns,
    private val alternatives: (String) -> List<InetAddress>,
    private val maxAddresses: Int = 3,
    private val forgetAfterMillis: Long = 5 * 60_000L,
    private val alternativesTtlMillis: Long = 60_000L,
    private val now: () -> Long = System::currentTimeMillis,
) : Dns {
    private val unreachableSince = ConcurrentHashMap<InetAddress, Long>()
    private val alternativesCache = ConcurrentHashMap<String, Pair<Long, List<InetAddress>>>()

    override fun lookup(hostname: String): List<InetAddress> {
        val fromSystem = try {
            system.lookup(hostname)
        } catch (e: UnknownHostException) {
            emptyList()
        }
        val troubled = fromSystem.isEmpty() || fromSystem.any { !isUsable(it) }
        val fromPublic = if (troubled) alternativesOf(hostname) else emptyList()
        val usable = (fromSystem + fromPublic).distinct().filter(::isUsable)
        if (usable.isEmpty()) {
            if (fromSystem.isEmpty()) throw UnknownHostException(hostname)
            val picked = fromSystem.shuffled().take(maxAddresses)
            logger.info { "Resolved $hostname via system, all unreachable recently: ${picked.joinToString { it.hostAddress.orEmpty() }}" }
            return picked
        }
        val failingSubnets = unreachableSince.keys.filterNot(::isUsable).mapTo(HashSet(), ::subnetOf)
        val (healthy, failing) = usable.shuffled().partition { subnetOf(it) !in failingSubnets }
        val picked = (healthy + failing).take(maxAddresses)
        logger.info {
            val names = picked.joinToString { if (it in fromSystem) it.hostAddress.orEmpty() else "${it.hostAddress} (public)" }
            "Resolved $hostname: $names (${healthy.size} in healthy subnets, ${failing.size} in failing ones; " +
                    "system gave ${fromSystem.size}, public ${fromPublic.size})"
        }
        return picked
    }

    fun markUnreachable(address: InetAddress?) {
        if (address != null) unreachableSince[address] = now()
    }

    fun markReachable(address: InetAddress?) {
        if (address != null) unreachableSince.remove(address)
    }

    private fun isUsable(address: InetAddress): Boolean {
        val since = unreachableSince[address] ?: return true
        return now() - since > forgetAfterMillis
    }

    private fun alternativesOf(hostname: String): List<InetAddress> {
        alternativesCache[hostname]?.let { (at, addresses) -> if (now() - at < alternativesTtlMillis) return addresses }
        return alternatives(hostname).also { alternativesCache[hostname] = now() to it }
    }

    private companion object {
        private val logger = logger<FailoverDns>()

        /** IPv4 取前三段 (/24), IPv6 取前八个字节 (/64). */
        fun subnetOf(address: InetAddress): String {
            val bytes = address.address
            return bytes.copyOf(if (bytes.size == 4) 3 else 8).joinToString(".")
        }
    }
}

/**
 * 经几家公共 DNS 的 JSON 接口 (DoH) 查 IPv4 地址, 几家一起问, 合并去重. 各家按自己所在的位置给 CDN 节点,
 * 所以常能拿到与系统 DNS 不同的一组. 都失败时返回空.
 */
internal object DohResolver {
    private val logger = logger<DohResolver>()

    private const val TIMEOUT_MILLIS = 4_000L

    private val ENDPOINTS = listOf(
        "https://dns.alidns.com/resolve?type=1&name=",
        "https://cloudflare-dns.com/dns-query?type=A&name=",
        "https://dns.google/resolve?type=A&name=",
    )

    private val ADDRESS = Regex(""""data"\s*:\s*"(\d{1,3}(?:\.\d{1,3}){3})"""")

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            .readTimeout(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            .callTimeout(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            .build()
    }

    fun lookup(hostname: String): List<InetAddress> {
        val startedAt = System.nanoTime()
        val results = ConcurrentHashMap<String, List<String>>()
        val latch = CountDownLatch(ENDPOINTS.size)
        for (endpoint in ENDPOINTS) {
            val request = Request.Builder().url(endpoint + hostname).header("Accept", "application/dns-json").build()
            client.newCall(request).enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        latch.countDown()
                    }

                    override fun onResponse(call: Call, response: Response) {
                        response.use { if (it.isSuccessful) results[endpoint] = parseAddresses(it.body?.string().orEmpty()) }
                        latch.countDown()
                    }
                },
            )
        }
        latch.await(TIMEOUT_MILLIS + 500, TimeUnit.MILLISECONDS)
        val addresses = results.values.flatten().distinct()
        logger.info {
            "Public DNS for $hostname: ${addresses.joinToString()} " +
                    "(${results.size} of ${ENDPOINTS.size} answered in ${(System.nanoTime() - startedAt) / 1_000_000}ms)"
        }
        return addresses.mapNotNull { ip ->
            runCatching { InetAddress.getByAddress(hostname, ip.split('.').map { it.toInt().toByte() }.toByteArray()) }.getOrNull()
        }
    }

    /** DoH JSON 回答里的 IPv4 地址 (`"data":"1.2.3.4"`; CNAME 那几条不是地址, 不算). */
    internal fun parseAddresses(json: String): List<String> =
        ADDRESS.findAll(json).map { it.groupValues[1] }.filter { ip -> ip.split('.').all { it.toInt() in 0..255 } }.toList()
}
