/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.foundation.diagnosis

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.him188.ani.app.data.network.FeedbackTicketService
import me.him188.ani.app.domain.foundation.BangumiEchAddressList
import me.him188.ani.app.domain.foundation.BangumiEchConfig
import me.him188.ani.app.domain.foundation.BangumiEchTransport
import me.him188.ani.app.domain.foundation.BangumiEndpointProvider
import me.him188.ani.app.domain.foundation.BangumiRoute
import me.him188.ani.app.domain.foundation.BangumiRouteStats
import me.him188.ani.app.domain.foundation.BangumiRouting
import me.him188.ani.app.domain.foundation.ConscryptEchTransport
import me.him188.ani.app.domain.foundation.GitHubFileSources
import me.him188.ani.app.domain.foundation.RepoHostedList
import me.him188.ani.app.domain.foundation.summarizeForLog
import me.him188.ani.app.domain.session.auth.BangumiOAuthRelay
import me.him188.ani.app.domain.settings.ProxyProvider
import me.him188.ani.app.platform.currentAniBuildConfig
import me.him188.ani.app.platform.getAniUserAgent
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Connection
import okhttp3.ConnectionPool
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration.Companion.seconds

/**
 * 网络体检: 把应用常用的网络请求按每条连法各发一遍, 每个请求记下 DNS / 连接 / 握手 / 首字节各花多久、失败在哪一段、为什么.
 *
 * 用单独的 OkHttp 客户端直接按地址发, **不经应用的线路调度、连接池与请求队列** —— 测的是「这条路本身通不通」;
 * 应用里当时的情况 (哪些请求在排队、最近哪条线路失败了多少次) 另从 [BangumiRouteStats] 读.
 * Bangumi 的每条线路都测, 设置里没开的也测 (看换过去能不能用); 设了代理时直连与镜像经代理, 不测加密直连 (同应用).
 *
 * 不带凭证, 只有「登录状态」一个请求带令牌, 只发给能带令牌的线路 (官方、自建、加密直连; 镜像要用户允许过). 报告里不记令牌.
 */
class NetworkDiagnosis(
    private val context: Context,
    private val endpoints: BangumiEndpointProvider,
    private val proxyProvider: ProxyProvider,
    private val echTransport: BangumiEchTransport?,
    private val echConfig: BangumiEchConfig,
    private val echAddresses: BangumiEchAddressList,
    private val stats: BangumiRouteStats,
    /** 登录着时的 Bangumi 令牌; 没登录为 `null`. */
    private val bangumiToken: suspend () -> String?,
) {
    private val logger = logger<NetworkDiagnosis>()

    /** 跑在哪一步, 给界面显示. */
    enum class Step { LISTS, ENVIRONMENT, DNS, ROUTES, SERVICES }

    /** 系统 DNS 的查询不能中断, 超时后让它在这里自己跑完, 不拖住体检. */
    private val dnsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun run(onStep: (Step) -> Unit = {}): NetworkReport {
        val started = System.currentTimeMillis()
        // 先把仓库里的清单 (镜像、加密直连地址、TMDB 入口…) 都拉一遍, 不管多久前拉过: 下面测的就是最新的, 出了问题的人也马上用上
        onStep(Step.LISTS)
        val lists = coroutineScope {
            RepoHostedList.created.map { list -> async { list to (withTimeoutOrNull(LISTS_TIMEOUT) { list.refreshNow() } == true) } }.awaitAll()
        }.map { (list, refreshed) ->
            val state = list.state()
            ListCheck(state.fileName, state.entries, state.bundled, state.updatedAgoMillis?.div(1000), refreshed)
        }.sortedBy { it.file }

        onStep(Step.ENVIRONMENT)
        val proxyConfig = proxyProvider.proxy.first()
        val proxy = proxyConfig?.let { parseProxy(it.url) }
        val proxyAuthorization = proxyConfig?.authorization?.let { "Basic " + Base64.encodeToString("${it.username}:${it.password}".toByteArray(), Base64.NO_WRAP) }
        val ech = (echTransport as? ConscryptEchTransport)?.takeIf { proxyConfig == null }
        val token = bangumiToken()
        val prober = Prober(proxy, proxyAuthorization, ech)
        val routing = endpoints.currentRouting

        onStep(Step.DNS)
        val dns = coroutineScope {
            dnsHosts(routing?.knownRoutes.orEmpty()).map { host -> async { checkDns(host, prober) } }.awaitAll()
        }

        onStep(Step.ROUTES)
        val routesToTest = buildList {
            add(BangumiRoute.Origin)
            routing?.knownRoutes.orEmpty().forEach { if (it !is BangumiRoute.Ech || ech != null) add(it) }
        }.distinct()
        val routes = coroutineScope {
            routesToTest.map { route ->
                async {
                    val credentialsAllowed = !route.thirdParty || routing?.allowCredentialsViaThirdParty == true
                    checkRoute(route, enabled = routing?.routes?.contains(route) == true, prober, token.takeIf { credentialsAllowed })
                }
            }.awaitAll()
        }

        // 加密直连的每个 Cloudflare 地址单独握手一次: 整条线路按清单顺序试, 只看得出最后连上的那个
        val echAddressChecks = if (ech == null) emptyList() else coroutineScope {
            echAddresses.addresses.value.map { address ->
                async {
                    val probed = prober.get("ech-address", ECH_ADDRESS_PROBE_URL, viaEch = true, keepBody = true, echAddress = address)
                    EchAddressCheck(address, probed.result, probed.body?.let(::parseTrace)?.get("sni"))
                }
            }.awaitAll()
        }

        onStep(Step.SERVICES)
        val services = coroutineScope {
            serviceTargets().flatMap { (name, url, cloudflare) ->
                buildList {
                    add(async { prober.get(name, url, viaEch = false) })
                    if (cloudflare && ech != null) add(async { prober.get(name, url, viaEch = true) })
                }
            }.awaitAll()
        }

        val skew = (routes.flatMap { it.requests } + services).firstNotNullOfOrNull { it.serverDate }?.let { (local, server) -> (local - server) / 1000 }
        val environment = environment(proxyConfig?.url, routing, ech != null, token != null, skew)
        val report = NetworkReport(
            environment = environment,
            dns = dns,
            routes = routes.map { it.check },
            echAddressChecks = echAddressChecks,
            lists = lists,
            services = services.map { it.result },
            app = appState(),
            durationMs = System.currentTimeMillis() - started,
        )
        logger.info { "Network diagnosis done in ${report.durationMs}ms" }
        return report
    }

    // ------------------------------------------------------------------ 环境

    private suspend fun environment(
        appProxyUrl: String?,
        routing: BangumiRouting?,
        echUsable: Boolean,
        loggedIn: Boolean,
        clockSkewSeconds: Long?,
    ): NetworkEnvironment {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val network = runCatching { cm?.activeNetwork }.getOrNull()
        val caps = network?.let { runCatching { cm?.getNetworkCapabilities(it) }.getOrNull() }
        val link = network?.let { runCatching { cm?.getLinkProperties(it) }.getOrNull() }
        val transports = buildList {
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true) add("ethernet")
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) add("wifi")
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) add("cellular")
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) add("vpn")
        }
        val privateDns = link?.let {
            if (Build.VERSION.SDK_INT >= 28) {
                if (it.isPrivateDnsActive) it.privateDnsServerName ?: "auto" else "off"
            } else {
                null
            }
        }
        val ech = echConfig.state()
        return NetworkEnvironment(
            connected = network != null,
            transports = transports,
            validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            dnsServers = link?.dnsServers.orEmpty().mapNotNull { it.hostAddress },
            privateDns = privateDns,
            ipv4 = link?.linkAddresses.orEmpty().any { it.address is Inet4Address },
            ipv6 = link?.linkAddresses.orEmpty().any { it.address is Inet6Address && !it.address.isLinkLocalAddress },
            systemProxy = link?.httpProxy?.let { "${it.host}:${it.port}" },
            appProxy = appProxyUrl?.let(::withoutUserInfo),
            routes = routing?.routes.orEmpty().map { it.label },
            settledRoute = endpoints.settledRoute?.label,
            allowCredentialsViaMirror = routing?.allowCredentialsViaThirdParty == true,
            echUsable = echUsable,
            echConfig = EchConfigState(
                bundled = ech.bundled,
                fetchedAgoSeconds = ech.fetchedAgoMillis?.div(1000),
                changedAgoSeconds = ech.changedAgoMillis?.div(1000),
                rejectedRemembered = ech.rejectedRemembered,
            ),
            echAddresses = echAddresses.addresses.value,
            loggedIn = loggedIn,
            clockSkewSeconds = clockSkewSeconds,
        )
    }

    private fun appState(): AppNetworkState {
        val now = System.currentTimeMillis()
        val s = stats.snapshot()
        return AppNetworkState(
            inFlight = s.inFlight.map { AppNetworkState.HostInFlight(it.host, it.count, it.oldestMillis / 1000) },
            routeCounts = s.routes.map { AppNetworkState.RouteCounts(it.route, it.ok, it.failed) },
            recentFailures = s.failures.take(MAX_RECENT_FAILURES).map {
                AppNetworkState.RecentFailure((now - it.at) / 1000, it.route, it.host, it.path, it.elapsedMillis, it.cause)
            },
            allFailedRecently = s.allFailed.size,
        )
    }

    // ------------------------------------------------------------------ 域名解析

    private fun dnsHosts(knownRoutes: List<BangumiRoute>): List<String> = buildList {
        addAll(listOf("api.bgm.tv", "next.bgm.tv", "lain.bgm.tv", "bgm.tv"))
        knownRoutes.filter { it is BangumiRoute.Mirror || it is BangumiRoute.Custom }
            .mapNotNullTo(this) { it.hostFor("api.bgm.tv") }
        add(hostOf(FeedbackTicketService.OFFICIAL_ENDPOINTS.first()))
        add(hostOf(BangumiOAuthRelay.CALLBACK_URL))
        addAll(listOf("crypto.cloudflare.com", "testingcf.jsdelivr.net", "api.github.com", "api.themoviedb.org", "api.tmdb.org"))
        addAll(TMDB_IMAGE_HOSTS)
    }.distinct()

    private suspend fun checkDns(host: String, prober: Prober): DnsCheck {
        val t0 = System.currentTimeMillis()
        val system = dnsScope.async { InetAddress.getAllByName(host).mapNotNull { it.hostAddress } }
        val systemResult = withTimeoutOrNull(DNS_TIMEOUT) { runCatching { system.await() } }
        val systemMs = System.currentTimeMillis() - t0
        val doh = prober.resolveDoh(host)
        return DnsCheck(
            host = host,
            system = systemResult?.getOrNull().orEmpty(),
            systemError = when {
                systemResult == null -> "timeout after ${DNS_TIMEOUT.inWholeSeconds}s"
                else -> systemResult.exceptionOrNull()?.summarizeForLog()
            },
            systemMs = systemMs,
            doh = doh.getOrNull().orEmpty(),
            dohError = doh.exceptionOrNull()?.summarizeForLog(),
        )
    }

    // ------------------------------------------------------------------ Bangumi 线路

    private class RouteOutcome(val check: RouteCheck, val requests: List<Probed>)

    private suspend fun checkRoute(route: BangumiRoute, enabled: Boolean, prober: Prober, token: String?): RouteOutcome {
        val viaEch = route is BangumiRoute.Ech
        val results = ArrayList<Probed>()
        var trace = emptyMap<String, String>()
        var unanswered = 0
        for (r in BANGUMI_REQUESTS) {
            if (r.name == "me" && token == null) continue
            val originHost = r.host
            // 加密直连的客户端按真实域名连 (地址与 ECH 它自己管), 其余线路换成线路自己的域名
            val host = if (viaEch) originHost else route.hostFor(originHost) ?: continue
            val url = "https://$host${r.path}"
            if (unanswered >= SKIP_AFTER_UNANSWERED) {
                results += Probed(ProbeResult(r.name, url, if (viaEch) "ech" else prober.directVia, skipped = true))
                continue
            }
            val headers = if (r.name == "me" && token != null) mapOf("Authorization" to "Bearer $token") else emptyMap()
            val probed = prober.get(r.name, url, viaEch = viaEch, headers = headers, keepBody = r.name == "trace")
            if (r.name == "trace") trace = probed.body?.let(::parseTrace).orEmpty().filterKeys { it in TRACE_KEYS }
            unanswered = if (probed.result.answered) 0 else unanswered + 1
            results += probed
        }
        val kind = when (route) {
            BangumiRoute.Origin -> "origin"
            BangumiRoute.Ech -> "ech"
            is BangumiRoute.Custom -> "custom"
            is BangumiRoute.Mirror -> "mirror"
        }
        return RouteOutcome(RouteCheck(route.label, kind, enabled, results.map { it.result }, trace), results)
    }

    // ------------------------------------------------------------------ 其他服务

    private data class ServiceTarget(val name: String, val url: String, val onCloudflare: Boolean)

    private fun serviceTargets(): List<ServiceTarget> = buildList {
        add(ServiceTarget("tickets", FeedbackTicketService.OFFICIAL_ENDPOINTS.first() + "/", onCloudflare = true))
        add(ServiceTarget("relay", "https://${hostOf(BangumiOAuthRelay.CALLBACK_URL)}/", onCloudflare = true))
        GitHubFileSources.urls(currentAniBuildConfig.projectRepository, "bangumi-mirrors.json").forEach { url ->
            add(ServiceTarget("list:" + hostOf(url), url, onCloudflare = false))
        }
        add(ServiceTarget("github", "https://api.github.com/repos/${currentAniBuildConfig.updateRepository}/releases?per_page=1", onCloudflare = false))
        add(ServiceTarget("tmdb-api", "https://api.themoviedb.org/3/configuration", onCloudflare = false))
        add(ServiceTarget("tmdb-api", "https://api.tmdb.org/3/configuration", onCloudflare = false))
        for (host in TMDB_IMAGE_HOSTS) {
            add(ServiceTarget("tmdb-image:$host", "https://$host/t/p/w300/prHii5ULE8k9onzC3lFcb9RiITl.jpg", onCloudflare = false))
        }
        add(ServiceTarget("doh-alidns", "https://dns.alidns.com/resolve?name=crypto.cloudflare.com&type=65", onCloudflare = false))
    }

    // ------------------------------------------------------------------ 发请求

    /** 一个请求的结果, 连同报告里不放的东西 (回应正文、服务器时间). */
    private class Probed(val result: ProbeResult, val body: String? = null, val serverDate: Pair<Long, Long>? = null)

    private inner class Prober(
        private val proxy: Proxy?,
        private val proxyAuthorization: String?,
        private val ech: ConscryptEchTransport?,
    ) {
        val directVia: String get() = if (proxy != null) "proxy" else "direct"

        private val directBase: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .followRedirects(false)
                .followSslRedirects(false)
                .apply { if (proxy != null) proxy(proxy) }
                .apply {
                    if (proxyAuthorization != null) {
                        proxyAuthenticator { _, response -> response.request.newBuilder().header("Proxy-Authorization", proxyAuthorization).build() }
                    }
                }
                .build()
        }

        /** 每个请求一个新的连接池: 各自从解析、连接、握手走一遍, 量到的是这条路本身. */
        private fun client(viaEch: Boolean, listener: EventListener, echAddress: String?): OkHttpClient {
            val base = if (viaEch) checkNotNull(ech).diagnosticClient(listener, echAddress) else directBase.newBuilder().eventListener(listener).build()
            return base.newBuilder()
                .connectionPool(ConnectionPool())
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()
        }

        suspend fun get(
            name: String,
            url: String,
            viaEch: Boolean,
            headers: Map<String, String> = emptyMap(),
            keepBody: Boolean = false,
            /** 加密直连只连这个地址 (见 [ConscryptEchTransport.diagnosticClient]). */
            echAddress: String? = null,
        ): Probed {
            val first = once(name, url, viaEch, headers, keepBody, echAddress)
            // 加密直连的配置过期时握手被拒: 同应用一样换一份再试一次
            val failure = first.failure
            if (viaEch && failure != null && echTransport?.recover(failure) == true) {
                val retried = once(name, url, viaEch, headers, keepBody, echAddress)
                return Probed(retried.probed.result.copy(echRetried = true), retried.probed.body, retried.probed.serverDate)
            }
            return first.probed
        }

        private suspend fun once(name: String, url: String, viaEch: Boolean, headers: Map<String, String>, keepBody: Boolean, echAddress: String?): Once {
            val via = if (viaEch) "ech" else directVia
            val timing = Timing()
            val request = Request.Builder().url(url)
                .header("User-Agent", getAniUserAgent())
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .build()
            val client = try {
                client(viaEch, timing, echAddress)
            } catch (e: Exception) {
                return Once(Probed(ProbeResult(name, url, via, error = e.summarizeForLog(), failedAt = "setup")), e)
            }
            return try {
                val fetched = client.fetch(request, keepBody)
                val result = timing.result(name, url, via).copy(status = fetched.status, bytes = fetched.bytes)
                Once(Probed(result, fetched.body, fetched.serverDate), null)
            } catch (e: IOException) {
                Once(Probed(timing.result(name, url, via).copy(error = e.summarizeForLog(), failedAt = timing.phase)), e)
            } finally {
                client.connectionPool.evictAll()
            }
        }

        /** 用加密 DNS (阿里, 不行再问 360) 解析 [host] 的 IPv4 与 IPv6 地址. */
        suspend fun resolveDoh(host: String): Result<List<String>> {
            var last: Throwable? = null
            for (template in DOH_TEMPLATES) {
                val answers = ArrayList<String>()
                var ok = false
                for (type in listOf(1, 28)) {
                    val probed = get("doh", template.format(host, type), viaEch = false, headers = mapOf("Accept" to "application/dns-json"), keepBody = true)
                    val body = probed.body
                    if (probed.result.status != 200 || body == null) {
                        last = IOException(probed.result.error ?: "HTTP ${probed.result.status}")
                        continue
                    }
                    ok = true
                    answers += parseDohAnswers(body, type)
                }
                if (ok) return Result.success(answers)
            }
            return Result.failure(last ?: IOException("no DoH endpoint"))
        }
    }

    private class Fetched(val status: Int, val bytes: Long, val body: String?, val serverDate: Pair<Long, Long>?)

    /** 发一次的结果, 连同失败的异常 (加密直连据它判断要不要换配置重试). */
    private class Once(val probed: Probed, val failure: Throwable?)

    /** 异步发, 协程取消时取消请求; 读完整个回应 (图片只数字节, 最多 [MAX_BODY_BYTES]). */
    private suspend fun OkHttpClient.fetch(request: Request, keepBody: Boolean): Fetched = suspendCancellableCoroutine { cont ->
        val call = newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    val fetched = runCatching {
                        response.use { r ->
                            val source = r.body?.source()
                            var bytes = 0L
                            var text: String? = null
                            if (source != null) {
                                if (keepBody) {
                                    source.request(MAX_TEXT_BYTES)
                                    val buffered = source.buffer
                                    text = buffered.clone().readUtf8(minOf(buffered.size, MAX_TEXT_BYTES))
                                }
                                while (bytes < MAX_BODY_BYTES) {
                                    val read = source.read(Buffer(), 8192)
                                    if (read < 0) break
                                    bytes += read
                                }
                            }
                            val serverDate = r.header("Date")?.let(::parseHttpDate)?.let { System.currentTimeMillis() to it }
                            Fetched(r.code, bytes, text, serverDate)
                        }
                    }
                    fetched.fold({ cont.resume(it) }, { cont.resumeWithException(it) })
                }
            },
        )
    }

    /** 记一次请求各段在什么时候结束 (从发起算起), 失败时停在哪一段. */
    private class Timing : EventListener() {
        private val start = System.nanoTime()

        @Volatile
        var phase: String = "dns"
            private set
        private var dnsMs: Long? = null
        private var connectMs: Long? = null
        private var tlsMs: Long? = null
        private var ttfbMs: Long? = null
        private var remote: String? = null
        private var tls: String? = null
        private var protocol: String? = null

        private fun since() = (System.nanoTime() - start) / 1_000_000

        override fun dnsStart(call: Call, domainName: String) {
            phase = "dns"
        }

        override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
            dnsMs = since()
        }

        override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
            phase = "connect"
            remote = inetSocketAddress.address?.hostAddress ?: inetSocketAddress.hostString
        }

        override fun secureConnectStart(call: Call) {
            connectMs = since()
            phase = "tls"
        }

        override fun secureConnectEnd(call: Call, handshake: Handshake?) {
            tlsMs = since()
            tls = handshake?.tlsVersion?.javaName
        }

        override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) {
            if (connectMs == null) connectMs = since()
            this.protocol = protocol?.toString()
        }

        override fun connectionAcquired(call: Call, connection: Connection) {
            protocol = connection.protocol().toString()
            remote = connection.socket().inetAddress?.hostAddress ?: remote
            phase = "request"
        }

        override fun responseHeadersStart(call: Call) {
            phase = "response"
        }

        override fun responseHeadersEnd(call: Call, response: Response) {
            ttfbMs = since()
        }

        fun result(name: String, url: String, via: String) = ProbeResult(
            name = name,
            url = url,
            via = via,
            totalMs = since(),
            dnsMs = dnsMs,
            connectMs = connectMs,
            tlsMs = tlsMs,
            ttfbMs = ttfbMs,
            remote = remote,
            tls = tls,
            protocol = protocol,
        )
    }

    private class BangumiRequest(val name: String, val host: String, val path: String)

    companion object {
        /**
         * 每条线路依次发的请求, 对应应用里常用的操作: 条目详情 (v0 / p1)、分集、角色、制作人员、评论、封面、登录状态.
         * 第一个是 Cloudflare 的 `/cdn-cgi/trace`: 回答里有 `sni=encrypted` 才说明真用上了加密直连.
         */
        private val BANGUMI_REQUESTS = listOf(
            BangumiRequest("trace", "api.bgm.tv", "/cdn-cgi/trace"),
            BangumiRequest("subject-v0", "api.bgm.tv", "/v0/subjects/$SUBJECT"),
            BangumiRequest("episodes", "api.bgm.tv", "/v0/episodes?subject_id=$SUBJECT&limit=1"),
            BangumiRequest("subject", "next.bgm.tv", "/p1/subjects/$SUBJECT"),
            BangumiRequest("characters", "next.bgm.tv", "/p1/subjects/$SUBJECT/characters?limit=1"),
            BangumiRequest("staff", "next.bgm.tv", "/p1/subjects/$SUBJECT/staffs/positions?limit=1"),
            BangumiRequest("comments", "next.bgm.tv", "/p1/subjects/$SUBJECT/comments?limit=1"),
            BangumiRequest("cover", "lain.bgm.tv", "/r/400/pic/cover/l/e5/69/265_Z5Uou.jpg"),
            BangumiRequest("me", "api.bgm.tv", "/v0/me"),
        )

        /**
         * `/cdn-cgi/trace` 里留哪些: 是否加密握手、国家 / 地区、Cloudflare 机房、协议. 不留 `ip` (用户的公网地址, 报告会附到工单里).
         */
        /** 拉清单最多等多久 (各清单并行; 每个入口自己 10 秒超时, 四个入口都卡住时到点放弃, 用缓存的). */
        private val LISTS_TIMEOUT = 25.seconds

        private val TRACE_KEYS = setOf("sni", "loc", "colo", "http", "tls", "warp", "gateway", "kex")

        /** 逐个测加密直连地址时请求的地址: Cloudflare 的检测页, 回答里的 `sni` 说明握手加没加密. */
        private const val ECH_ADDRESS_PROBE_URL = "https://api.bgm.tv/cdn-cgi/trace"

        /**
         * TMDB 图片的各个域名, 都是同一个 BunnyCDN 拉取区 (775336), 图片逐字节一样, 同 `tmdb-image-hosts.json` 的顺序:
         * 在用的两个, 加上替补的官方 `image-bunny.tmdb.org` 与 Bunny 给这个拉取区的默认域名. 用户的清单可能是旧的, 所以这里写死都测.
         */
        private val TMDB_IMAGE_HOSTS = listOf("images.tmdb.org", "image.tmdb.org", "image-bunny.tmdb.org", "tmdb-image-prod.b-cdn.net")

        /** 测哪部作品 (新世纪福音战士: 条目、分集、角色、评论都齐). */
        private const val SUBJECT = 265

        /** 连着这么多个请求都没拿到回应, 这条线路剩下的不发了 (每个都要等满超时). */
        private const val SKIP_AFTER_UNANSWERED = 2

        private const val CONNECT_TIMEOUT_SECONDS = 6L
        private const val READ_TIMEOUT_SECONDS = 8L
        private const val CALL_TIMEOUT_SECONDS = 15L
        private val DNS_TIMEOUT = 5.seconds
        private const val MAX_TEXT_BYTES = 4096L
        private const val MAX_BODY_BYTES = 2L * 1024 * 1024
        private const val MAX_RECENT_FAILURES = 15

        /** `%1$s` = 域名, `%2$d` = 记录类型. 都回 Google 格式的 DNS JSON. */
        private val DOH_TEMPLATES = listOf(
            "https://dns.alidns.com/resolve?name=%1\$s&type=%2\$d",
            "https://doh.360.cn/resolve?name=%1\$s&type=%2\$d",
        )

        private fun hostOf(url: String): String = url.substringAfter("://").substringBefore('/').substringBefore(':')

        /** 代理地址里可能带着账号密码 (`http://user:pass@host:port`), 报告里去掉. */
        internal fun withoutUserInfo(url: String): String = url.replace(Regex("""://[^/@]*@"""), "://")

        /** 应用里代理设置的地址 (`http://` / `socks5://`) 换成 [Proxy]; 认不出来为 `null` (直连). */
        internal fun parseProxy(url: String): Proxy? {
            val m = Regex("""^(https?|socks[45]?h?)://(?:[^@/]*@)?([^:/]+):(\d+)""").find(url.trim()) ?: return null
            val type = if (m.groupValues[1].startsWith("socks")) Proxy.Type.SOCKS else Proxy.Type.HTTP
            return Proxy(type, InetSocketAddress.createUnresolved(m.groupValues[2], m.groupValues[3].toInt()))
        }

        /** Cloudflare `/cdn-cgi/trace` 的回答: 一行一个 `key=value`. */
        internal fun parseTrace(body: String): Map<String, String> =
            body.lineSequence().mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }
                .filter { it.first.isNotEmpty() }
                .toMap()

        /** DoH JSON 回答里 [type] 类型 (1 = A, 28 = AAAA) 的地址. */
        internal fun parseDohAnswers(body: String, type: Int): List<String> = runCatching {
            Json.parseToJsonElement(body).jsonObject["Answer"]?.jsonArray.orEmpty().mapNotNull { answer ->
                val o = answer.jsonObject
                o["data"]?.jsonPrimitive?.contentOrNull?.takeIf { o["type"]?.jsonPrimitive?.intOrNull == type }
            }
        }.getOrDefault(emptyList())

        private fun parseHttpDate(value: String): Long? = runCatching {
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).parse(value)?.time
        }.getOrNull()
    }
}
