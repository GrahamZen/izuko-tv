/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.foundation

import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.darwin.DarwinClientEngineConfig
import io.ktor.client.engine.darwin.DarwinHttpRequestException
import io.ktor.http.Url
import kotlinx.atomicfu.atomic
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import me.him188.ani.app.domain.foundation.echcurl.CURLE_ECH_REQUIRED
import me.him188.ani.app.domain.foundation.echcurl.CURLE_SSL_CONNECT_ERROR
import me.him188.ani.utils.coroutines.IO_
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import kotlin.coroutines.cancellation.CancellationException
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.Platform

/**
 * iOS 上的加密直连: 系统的 TLS 不能给别的域名的 ECH 配置, 用静态链进来的 libcurl + BoringSSL ([EchCurl]).
 *
 * 共用的客户端只多一个会话级的 [EchUrlProtocol]: 发往加密直连域名 (见 [EchSites]) 的请求它接过去交给 curl, 其他请求原样走系统 ——
 * 不走加密直连的请求 (直连 bangumi 也在内) 与没有这条线路时一样.
 *
 * 接口与图床各最多 [MAX_CONCURRENT_REQUESTS] 个请求同时在发 (只有 HTTP/1.1, 一个请求一条连接), 其余的排队.
 */
class IosEchTransport internal constructor(
    private val config: () -> ByteArray,
    private val addresses: () -> List<String>,
    private val refreshConfig: suspend (acceptRecentChange: Boolean) -> Boolean,
    private val onFirstUse: () -> Unit,
) : BangumiEchTransport {
    constructor(
        echConfig: BangumiEchConfig,
        /** 第一次真用到加密直连时才取, 理由同安卓的 `ConscryptEchTransport`. */
        addressList: Lazy<BangumiEchAddressList>,
    ) : this(
        config = { echConfig.current },
        addresses = { addressList.value.addresses.value },
        refreshConfig = { echConfig.refresh(acceptRecentChange = it) },
        onFirstUse = { echConfig.refreshInBackgroundOnce() },
    )

    private val logger = logger<IosEchTransport>()

    internal val curl = EchCurl(addresses, config)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO_)
    private val apiPermits = Semaphore(MAX_CONCURRENT_REQUESTS)
    private val imagePermits = Semaphore(MAX_CONCURRENT_REQUESTS)
    private val announced = atomic(false)
    private val selfChecked = atomic(false)

    /** curl 与 BoringSSL 随包静态链接, 总是有. 真连得上与否到第一次用时才知道, 连不上由调度回落到下一条线路. */
    override val available: Boolean get() = true

    override fun install(config: HttpClientConfig<*>) {
        installedTransport.value = this
        @Suppress("UNCHECKED_CAST") // engine 块只会作用于实际的引擎配置, 类型在块内判断
        (config as HttpClientConfig<HttpClientEngineConfig>).engine {
            if (this !is DarwinClientEngineConfig) return@engine
            configureSession {
                protocolClasses = listOfNotNull(EchUrlProtocol.`class`()) + protocolClasses.orEmpty()
            }
        }
    }

    override suspend fun recover(cause: Throwable): Boolean {
        // curl 已经用服务器给的新配置重试过; 还是握手失败 (配置过期之类) 才经 DoH 换一份. 超时、连接被重置这类换配置没用
        if (generateSequence(cause) { it.cause }.none { it.isEchHandshakeFailure() }) return false
        return refreshConfig(true)
    }

    /** 由 [EchUrlProtocol] 调用: 在后台发出 [request]; 结束时 (没被停掉的话) 调 [finished], 失败时带上原因. */
    internal fun start(
        request: EchCurlRequest,
        listener: EchCurlListener,
        stopped: () -> Boolean,
        finished: (Throwable?) -> Unit,
    ): Job {
        onFirstUse()
        if (announced.compareAndSet(expect = false, update = true)) logger.info { "ECH transport: ${curl.version}" }
        selfCheckOnce()
        return scope.launch {
            val permits = if (request.image) imagePermits else apiPermits
            val failure = try {
                permits.withPermit { curl.perform(request, listener, stopped) }
                null
            } catch (e: CancellationException) {
                e
            } catch (e: Throwable) {
                e
            }
            if (stopped()) return@launch
            if (failure != null && failure !is CancellationException) {
                logger.warn { "ECH ${request.method} ${request.url.host}${request.url.encodedPath} failed: ${failure.message}" }
            }
            finished(failure)
        }
    }

    /**
     * debug 包 (按 Kotlin/Native 产物的编译方式认, iOS 的 AniBuildConfig.isDebug 恒为 false) 里第一次用到时自检一次 ([EchSites.selfCheckHosts] 逐个), 结果只记日志: 判据是 Cloudflare 的 `/cdn-cgi/trace`
     * (`sni=encrypted`). curl 已经要求 ECH, 这里再从服务器那边确认一遍.
     */
    @OptIn(ExperimentalNativeApi::class)
    private fun selfCheckOnce() {
        if (!Platform.isDebugBinary || !selfChecked.compareAndSet(expect = false, update = true)) return
        scope.launch {
            // 包里那份配置可能已经轮换掉了, 先换上最新的再测
            refreshConfig(false)
            for (host in EchSites.selfCheckHosts) {
                val result = runCatching { selfCheck(host) }
                logger.info { "ECH self-check: ${result.getOrElse { "$host failed (${it.message})" }}" }
            }
        }
    }

    /** 经加密直连取 `https://[host]/cdn-cgi/trace`, 返回其中的 h / tls / http / sni 几行. */
    internal fun selfCheck(host: String): String {
        val body = StringBuilder()
        var code = 0
        curl.perform(
            EchCurlRequest(Url("https://$host/cdn-cgi/trace"), "GET", emptyList(), null, SELF_CHECK_TIMEOUT_MILLIS, image = false),
            object : EchCurlListener {
                override fun onResponse(status: Int, headers: List<Pair<String, String>>) {
                    code = status
                }

                override fun onData(bytes: ByteArray) {
                    body.append(bytes.decodeToString())
                }
            },
        ) { false }
        val fields = body.lines().filter { line -> SELF_CHECK_FIELDS.any { line.startsWith("$it=") } }.joinToString(" ")
        return "status=$code $fields"
    }

    private fun Throwable.isEchHandshakeFailure(): Boolean =
        this is DarwinHttpRequestException && origin.domain == ECH_ERROR_DOMAIN && origin.code.toInt() in HANDSHAKE_FAILURES

    @OptIn(ExperimentalForeignApi::class)
    private companion object {
        const val MAX_CONCURRENT_REQUESTS = 6
        const val SELF_CHECK_TIMEOUT_MILLIS = 10_000L

        /** 握手没成 (含 ECH 被拒、配置不能用): 换一份配置值得再试. */
        val HANDSHAKE_FAILURES = setOf(CURLE_SSL_CONNECT_ERROR.toInt(), CURLE_ECH_REQUIRED.toInt())

        /** `/cdn-cgi/trace` 里自检要看的几行: 连到哪个站、TLS 版本、HTTP 版本、SNI 加没加密. */
        val SELF_CHECK_FIELDS = listOf("h", "tls", "http", "sni")
    }
}
