/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.foundation

import android.net.http.X509TrustManagerExtensions
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.okhttp.OkHttpConfig
import kotlinx.coroutines.runBlocking
import me.him188.ani.app.platform.currentAniBuildConfig
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.conscrypt.Conscrypt
import org.conscrypt.DomainEncryptionMode
import org.conscrypt.NetworkSecurityPolicy
import org.conscrypt.metrics.CertificateTransparencyVerificationReason
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.net.UnknownHostException
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

/**
 * 安卓上的加密直连: 系统自带的 TLS 不支持 ECH, 用随包的 Conscrypt (2.7 起支持) 握手.
 *
 * 加密直连的请求由单独一个 OkHttp 客户端发 (见 [echClient]). 共用的客户端只多装一个拦截器 ([EchForwarder]):
 * 发往加密直连域名 (见 [EchSites]) 的请求换回真实域名转给它, 其他请求原样放行 —— 共用客户端的解析、TLS、证书校验、
 * 连接池一概不碰, 不走加密直连的请求 (直连 bangumi 也在内) 与没有这条线路时一样.
 */
class ConscryptEchTransport(
    private val echConfig: BangumiEchConfig,
    /** 第一次真用到加密直连时才取: 清单一建就会去仓库拉, 用不到加密直连的人不该为它多发请求. */
    addressList: Lazy<BangumiEchAddressList>,
) : BangumiEchTransport {
    private val addressList by addressList

    private val logger = logger<ConscryptEchTransport>()

    /**
     * 随包带了 Conscrypt 的原生库 (arm64 / v7a / x86 / x86_64), 视为可用. 原生库到第一次真走加密直连时才加载 (见 [echClient]):
     * 只连官方的人不为它多花启动时间. 加载失败时加密直连的请求一律失败, 由调度回落到下一条线路.
     */
    override val available: Boolean get() = true

    /**
     * 加密直连的请求都由它发. 第一次用到时才建, 这时才加载 Conscrypt; 建不出来 (原生库加载不了) 记下原因, 之后的请求直接失败.
     *
     * - **解析**: 一律换成 [BangumiEchAddressList] 里的 Cloudflare 地址 (IP 字面量, 不查 DNS) —— 大陆的 DNS 对 bangumi 是污染的,
     *   而转到这里的只有 Cloudflare 上的站 (见 [EchSites]);
     * - **握手**: Conscrypt + 要求 ECH ([EchRequiredTrustManager]). 内层 (加密的) 用真实域名, 外层只露出 ECH 配置里的公共名;
     * - **证书**: OkHttp 默认的校验, 按真实域名. api / next / lain / 主站是同一组地址、同一张证书, OkHttp 照常把它们合到一条 HTTP/2 连接上;
     * - **连接池**: 自己一个. 加密的连接与共用客户端的连接互不复用, 也不占共用连接池的空闲名额; 图床另有一个 (见 [imageClient]);
     * - **跳转**: 不跟, 与共用客户端一样交回 Ktor —— 跳到哪儿由 [BangumiRoutingFeatureHandler] 重新按线路决定.
     */
    private val echClient: Result<OkHttpClient> by lazy {
        runCatching {
            check(Conscrypt.isAvailable()) { "Conscrypt native library is unavailable" }
            val trustManager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                .apply { init(null as KeyStore?) }
                .trustManagers.filterIsInstance<X509TrustManager>().first()
            val conscrypt = SSLContext.getInstance("TLS", Conscrypt.newProvider())
                .apply { init(null, arrayOf(EchRequiredTrustManager(trustManager)), null) }
                .socketFactory
            OkHttpClient.Builder()
                .connectionPool(ConnectionPool())
                .dns(EchDns())
                .sslSocketFactory(EchSocketFactory(conscrypt), trustManager)
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
        }.onFailure { logger.warn(it) { "ECH is unavailable on this device, requests on it fail over to the next route" } }
    }

    /**
     * 图床 ([EchSites.isImageHost]) 的请求: 同 [echClient], 只是连接池另一个 —— 不与接口合到一条 HTTP/2 连接上,
     * 带宽小时详情页的接口不用排在一屏封面后面.
     */
    private val imageClient: Result<OkHttpClient> by lazy { echClient.map { it.newBuilder().connectionPool(ConnectionPool()).build() } }

    /** 外层请求被取消时把转过去的那个也取消; 只有加密直连的请求在等时才有任务. */
    private val cancelWatcher by lazy {
        ScheduledThreadPoolExecutor(1) { Thread(it, "ech-cancel-watcher").apply { isDaemon = true } }
            .apply { removeOnCancelPolicy = true }
    }

    private val selfChecked = AtomicBoolean(false)

    /**
     * debug 包里第一次用到加密直连时自检一次 ([EchSites.selfCheckHosts] 逐个), 结果只记日志: 握手到底用没用上 ECH. 判据是 Cloudflare 的 `/cdn-cgi/trace`
     * (`sni=encrypted` / `sni=plaintext`) —— Conscrypt 没认到要求 ECH 的策略时会退回明文的域名, 请求照样成功, 光看成败测不出来.
     */
    private fun selfCheckOnce(client: OkHttpClient) {
        if (!currentAniBuildConfig.isDebug || !selfChecked.compareAndSet(false, true)) return
        thread(isDaemon = true, name = "ech-self-check") {
            // 包里那份配置可能已经轮换掉了, 先换上最新的再测
            runBlocking { echConfig.refresh() }
            for (host in EchSites.selfCheckHosts) {
                val result = runCatching {
                    client.newCall(Request.Builder().url("https://$host/cdn-cgi/trace").build()).execute().use { response ->
                        response.body?.string().orEmpty().lines()
                            .filter { line -> SELF_CHECK_FIELDS.any { line.startsWith("$it=") } }
                            .joinToString(" ")
                    }
                }
                logger.info { "ECH self-check: ${result.getOrElse { "$host failed (${it::class.simpleName}: ${it.message})" }}" }
            }
        }
    }

    override fun install(config: HttpClientConfig<*>) {
        @Suppress("UNCHECKED_CAST") // engine 块只会作用于实际的引擎配置, 类型在块内判断
        (config as HttpClientConfig<HttpClientEngineConfig>).engine {
            if (this !is OkHttpConfig) return@engine
            addInterceptor(EchForwarder())
        }
    }

    override suspend fun recover(cause: Throwable): Boolean {
        // 配置过期时 Conscrypt 报握手被拒 (SSLException 的子类); 连接被重置、超时这类不是配置的问题, 换配置没用
        if (generateSequence(cause) { it.cause }.none { it is SSLException }) return false
        // 第一次用到时后台那次刷新常常与这次握手同时进行: 它刚换到的新配置也算, 用新配置再试
        return echConfig.refresh(acceptRecentChange = true)
    }

    /** 发往加密直连域名的请求换回真实域名, 交给 [echClient] 发; 其他请求原样放行. */
    private inner class EchForwarder : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val origin = EchSites.originHostOf(request.url.host) ?: return chain.proceed(request)
            val apiClient = echClient.getOrElse { throw IOException("ECH is unavailable", it) }
            echConfig.refreshInBackgroundOnce()
            selfCheckOnce(apiClient)
            val image = EchSites.isImageHost(origin)
            val client = if (image) imageClient.getOrElse { throw IOException("ECH is unavailable", it) } else apiClient
            // 超时照外层的: 调度试线路时缩过的超时 (见 BangumiRoutingFeatureHandler) 在这里也生效; 图床的读超时至少 IMAGE_READ_TIMEOUT_MILLIS
            val readTimeout = chain.readTimeoutMillis().toLong().let { if (image) maxOf(it, IMAGE_READ_TIMEOUT_MILLIS) else it }
            val call = client.newBuilder()
                .connectTimeout(chain.connectTimeoutMillis().toLong(), TimeUnit.MILLISECONDS)
                .readTimeout(readTimeout, TimeUnit.MILLISECONDS)
                .writeTimeout(chain.writeTimeoutMillis().toLong(), TimeUnit.MILLISECONDS)
                .build()
                .newCall(request.newBuilder().url(request.url.newBuilder().host(origin).build()).build())
            return call.executeCancelledWith(chain.call())
        }
    }

    /**
     * 同步发出, [outer] 被取消 (Ktor 取消协程时会取消外层的请求) 时跟着取消. 拦截器里收不到取消的通知, 隔一会儿看一眼;
     * 不跟着取消的话, 划走的海报图还占着外层的并发名额, 一直等到回应.
     */
    private fun Call.executeCancelledWith(outer: Call): Response {
        if (outer.isCanceled()) throw IOException("Canceled")
        val watch = cancelWatcher.scheduleWithFixedDelay(
            { if (outer.isCanceled()) cancel() },
            CANCEL_CHECK_MILLIS, CANCEL_CHECK_MILLIS, TimeUnit.MILLISECONDS,
        )
        try {
            return execute()
        } finally {
            watch.cancel(false)
        }
    }

    /** 每个连接都带上现在的 ECH 配置. OkHttp 只用「包住已连上的套接字」这个入口建 TLS 连接, 其余的照转. */
    private inner class EchSocketFactory(private val delegate: SSLSocketFactory) : SSLSocketFactory() {
        override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket =
            (delegate.createSocket(s, host, port, autoClose) as SSLSocket)
                .also { Conscrypt.setEchConfigList(it, echConfig.current) }

        override fun createSocket(): Socket = delegate.createSocket()
        override fun createSocket(host: String, port: Int): Socket = delegate.createSocket(host, port)
        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
            delegate.createSocket(host, port, localHost, localPort)

        override fun createSocket(host: InetAddress, port: Int): Socket = delegate.createSocket(host, port)
        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
            delegate.createSocket(address, port, localAddress, localPort)

        override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
        override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites
    }

    /** 一律解析成清单里的 Cloudflare 地址. */
    private inner class EchDns : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            addressList.addresses.value
                .mapNotNull { runCatching { InetAddress.getByName(it) }.getOrNull() }
                .ifEmpty { throw UnknownHostException("No ECH address for $hostname") }
    }

    private companion object {
        const val CANCEL_CHECK_MILLIS = 100L

        /**
         * 加密直连上图床请求的读超时下限. 图片请求自己设的是 3 秒 (直连时「连上了却不来数据」多半是假死, 断开换连接重来, 见 ScopedHttpClientHttpStack);
         * 加密直连上一屏封面同走一条连接, 带宽小时一张封面 3 秒还没等到数据是在排队, 掐断重来只会更慢.
         * 不再放得更长: 加密直连还没通过时调度试它的读超时是 5 秒, 这里只多 1 秒, 换线路不会明显变慢.
         */
        const val IMAGE_READ_TIMEOUT_MILLIS = 6_000L

        /** `/cdn-cgi/trace` 里自检要看的几行: 连到哪个站、TLS 版本、HTTP 版本、SNI 加没加密. */
        val SELF_CHECK_FIELDS = listOf("h", "tls", "http", "sni")
    }
}

/**
 * 让 Conscrypt 对这条连接**要求** ECH. Conscrypt 用不用 ECH 看信任管理器上的 `getNetworkSecurityPolicy()` (按方法名反射找),
 * 找不到时用的默认策略对所有域名都是「未知」= 不用 —— 光给 socket 设 ECH 配置不起作用.
 *
 * 要求而不是「能用就用」: 配置被拒时握手失败 (换配置再试, 见 [ConscryptEchTransport.recover]), 不会退回明文的域名.
 *
 * **只实现 [X509TrustManager], 不继承 `X509ExtendedTrustManager`**: Conscrypt 的套接字会把后者包一层 (把引擎版的校验回调转成套接字版),
 * 包过的那层上没有 `getNetworkSecurityPolicy()`, 策略就丢了, ECH 也就不发. 普通的 X509TrustManager 它原样用, 校验证书时按签名找
 * `checkServerTrusted(证书链, 认证类型, 主机名)` 调 (duck typing), 这里交给系统的 [X509TrustManagerExtensions] 按真实域名校验,
 * 与系统 TLS 一样走应用的网络安全配置.
 */
class EchRequiredTrustManager(private val delegate: X509TrustManager) : X509TrustManager {
    private val extensions = X509TrustManagerExtensions(delegate)

    /** 由 Conscrypt 反射调用; release 包的混淆规则里保留了它. */
    fun getNetworkSecurityPolicy(): NetworkSecurityPolicy = RequireEchPolicy

    /** 由 Conscrypt 按签名反射调用 (见类说明); release 包的混淆规则里保留了它. */
    fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, host: String) {
        extensions.checkServerTrusted(chain, authType, host)
    }

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
        delegate.checkClientTrusted(chain, authType)

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) =
        delegate.checkServerTrusted(chain, authType)

    override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers

    private object RequireEchPolicy : NetworkSecurityPolicy {
        override fun isCertificateTransparencyVerificationRequired(hostname: String): Boolean = false
        override fun getCertificateTransparencyVerificationReason(hostname: String): CertificateTransparencyVerificationReason =
            CertificateTransparencyVerificationReason.UNKNOWN

        override fun getDomainEncryptionMode(hostname: String): DomainEncryptionMode = DomainEncryptionMode.REQUIRED
    }
}
