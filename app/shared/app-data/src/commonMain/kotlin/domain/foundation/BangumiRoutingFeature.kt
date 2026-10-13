/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.foundation

import io.ktor.client.HttpClient
import io.ktor.client.call.HttpClientCall
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.Sender
import io.ktor.client.plugins.plugin
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.URLBuilder
import io.ktor.http.contentType
import io.ktor.http.takeFrom
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.io.IOException
import me.him188.ani.app.data.models.preference.BangumiMirrorHosts
import me.him188.ani.utils.logging.debug
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock

/**
 * 把发往 bangumi 的请求按 [BangumiRouting] 依次发到各条线路上 (官方、自建、镜像…), 前一条不通用下一条.
 *
 * **为什么做在 HttpClient 这一层, 而不是改各处的 base URL**: 除了 `BangumiApiProvider` 那两个常量,
 * 还有一堆各自写死地址的地方 (`BangumiSummaryService`、`BangumiScheduleSource`、OAuth 的 token 端点、
 * 富文本里的表情图…), 而**图片也走同一个 `ScopedHttpClient`** (Sketch 用的就是它) —— 在这里改写,
 * 一处覆盖 API + 图床 + OAuth 换 token, 新增调用点零配置.
 *
 * ## 什么请求能走哪条线路
 *
 * 由线路的性质决定 (见 [BangumiRoute]), 与是哪一种线路无关:
 * - **带凭证的** (见 [BangumiRoutingFeatureHandler.carriesCredentials]) 不走第三方线路, 除非用户允许
 *   ([BangumiRouting.allowCredentialsViaThirdParty]): 反代能看到经过它的全部内容;
 * - **换 token 与续期** (`/oauth/`) 只走代理得了主站的线路 ([BangumiRoute.servesMainSite]): 第三方镜像把主站挡在反爬验证后面,
 *   发过去只会拿回一张验证网页, 还白把 client_secret 与续期令牌交了出去.
 *
 * 一条也走不了时照常发到官方 (在大陆就是失败) —— 净效果是「只开着镜像时匿名浏览可用、登录与收藏同步不可用」.
 */
val BangumiRoutingFeature = ScopedHttpClientFeatureKey<Boolean>("BangumiRouting")

class BangumiRoutingFeatureHandler(
    private val routing: Flow<BangumiRouting>,
    /**
     * 请求落到的线路变了时回报 (连同这个请求用的路由). 见 `BangumiEndpointProvider.reportSettled`.
     */
    private val onSettled: (routing: BangumiRouting, route: BangumiRoute) -> Unit = { _, _ -> },
    /**
     * 确定官方连不上时回报 (连同这个请求用的路由与通了的那条线路): 同一个请求在官方那一跳**连接失败**
     * (不是 5xx —— 那是官方在回答), 后面某条线路成功, 而且这个进程里官方在这套路由下从没回应过 (见 [originAnswered]).
     * 见 `BangumiEndpointProvider.reportOriginUnreachable`.
     */
    private val onOriginUnreachable: (routing: BangumiRouting, workingRoute: BangumiRoute) -> Unit = { _, _ -> },
    private val clock: Clock = Clock.System,
) : ScopedHttpClientFeatureHandler<Boolean>(BangumiRoutingFeature) {
    private val logger = logger<BangumiRoutingFeatureHandler>()

    /**
     * 上次成功的线路, [at] 是换到它的时刻. 路由一变 (改了线路开关、清单更新) 就作废 —— 用户重新打开「官方直连」,
     * 下一个请求就该重新先试官方.
     *
     * 没有它的话, 大陆用户每个请求都要先吃一次直连超时 —— 而直连被阻断时那是十几秒量级.
     *
     * 记住多久由线路定 ([BangumiRoute.stickyMillis], 从换过去那一刻算, 期间请求再多也不续): 官方以外的线路会过期,
     * 官方回 5xx、网络抖一下这类临时故障换过去之后, 过期了就重新先试前面的线路, 能直连的人能自愈. 官方**连不上**则另有出路:
     * 回报给 [onOriginUnreachable], 据此关掉「官方直连」, 路由里从此没有官方.
     */
    private data class Sticky(val signature: Int, val route: BangumiRoute, val at: Long)

    private val sticky = atomic<Sticky?>(null)

    /**
     * 这个进程里官方回应过 (任何 HTTP 响应, 5xx 也算) 的那套路由的签名.
     *
     * 回应过之后, 某个请求在官方那一跳又连不上, 多半是网络卡了一下 (启动时请求扎堆常见), 不是官方被阻断:
     * 照常换到后面的线路拿结果、粘性照走, 但**不回报** [onOriginUnreachable] —— 否则一次卡顿就会把「官方直连」
     * 永久关掉, 登录着的人还可能看到误报的「连不上官方」.
     */
    private val originAnswered = atomic<Int?>(null)

    override fun applyToClient(client: HttpClient, value: Boolean) {
        if (!value) return
        client.plugin(HttpSend).intercept { request ->
            handle(request) ?: execute(request)
        }
    }

    /**
     * 这个请求带着不能给第三方看的东西吗.
     *
     * 两类:
     * - **`Authorization` 头**: token 注入在请求管线 (`onRequest`) 完成, 而本拦截器在 `HttpSend`
     *   这一层 —— 也就是注入之后, 所以这里看到的头就是最终要发出去的.
     * - **`/oauth/` 路径**: 换 token 那个 POST **没有** `Authorization` 头, 但它的**表单里带着
     *   授权码与 client_secret** —— 反代拿到这两样就能替用户换出 token 来. 光看头会漏掉它.
     */
    private fun carriesCredentials(request: HttpRequestBuilder): Boolean =
        request.headers.contains(HttpHeaders.Authorization) || isOAuth(request)

    private fun isOAuth(request: HttpRequestBuilder): Boolean = "oauth" in request.url.pathSegments

    /** 这条线路能不能接这个请求. 见本文件开头「什么请求能走哪条线路」. */
    private fun BangumiRouting.accepts(route: BangumiRoute, request: HttpRequestBuilder, originalHost: String): Boolean =
        route.hostFor(originalHost) != null &&
                (!isOAuth(request) || route.servesMainSite) &&
                (!route.thirdParty || allowCredentialsViaThirdParty || !carriesCredentials(request))

    /** @return `null` = 这个请求不该由本特性处理, 调用方照常发 */
    private suspend fun Sender.handle(request: HttpRequestBuilder): HttpClientCall? {
        val routing = routing.first()
        // 数据里存着的线路地址 (见 BangumiRouting.knownRoutes) 先换回原站, 下面统一按路由走
        val originalHost = routing.originHostOf(request.url.host) ?: return null
        request.url.host = originalHost

        val candidates = routing.routes.filter { routing.accepts(it, request, originalHost) }
        if (candidates.isEmpty()) {
            if (isOAuth(request)) {
                logger.debug { "Bangumi OAuth request to $originalHost, no route serves the main site; keeping it on the origin" }
            } else {
                logger.debug { "Bangumi request to $originalHost carries credentials, keeping it on the origin" }
            }
            return null
        }
        if (candidates == listOf(BangumiRoute.Origin)) return null

        val signature = routing.hashCode()
        // 还有效的粘性, 见 Sticky 的文档
        val current = sticky.value?.takeIf { s ->
            s.signature == signature && s.route in candidates &&
                    s.route.stickyMillis.let { it == null || clock.now().toEpochMilliseconds() - s.at < it }
        }
        val startIndex = current?.let { candidates.indexOf(it.route) } ?: 0
        val originalTimeouts = request.getCapabilityOrNull(HttpTimeoutCapability)
            ?.let { Timeouts(it.requestTimeoutMillis, it.connectTimeoutMillis, it.socketTimeoutMillis) }
        var timeoutsChanged = false

        var lastCall: HttpClientCall? = null
        var originConnectFailed = false
        var i = 0
        while (i < candidates.size) {
            val route = candidates[(startIndex + i) % candidates.size]
            // 候选都按 accepts 筛过, 接得了这个域名
            request.url.host = checkNotNull(route.hostFor(originalHost))
            if (i > 0) {
                logger.info { "Bangumi: retrying $originalHost on ${route.label}" }
            }
            // 还不知道这条线路通不通 (没粘在它上面), 后面又还有线路可退时才缩: 最后一站缩了只会让本来能等到的回应超时.
            // 写请求不缩: 超时之后会换线路重发, 发出去了却没等到回应的写入会被写两遍
            if (route.probeWithShortTimeouts && route != current?.route && request.method == HttpMethod.Get &&
                i < candidates.size - 1
            ) {
                request.timeout {
                    requestTimeoutMillis = originalTimeouts?.request
                    connectTimeoutMillis = minOf(originalTimeouts?.connect ?: Long.MAX_VALUE, PROBE_CONNECT_MILLIS)
                    socketTimeoutMillis = minOf(originalTimeouts?.socket ?: Long.MAX_VALUE, PROBE_SOCKET_MILLIS)
                }
                timeoutsChanged = true
            } else if (timeoutsChanged) {
                // 恢复原来的超时 (没设过的交回 HttpTimeout 插件的默认值)
                request.timeout {
                    requestTimeoutMillis = originalTimeouts?.request
                    connectTimeoutMillis = originalTimeouts?.connect
                    socketTimeoutMillis = originalTimeouts?.socket
                }
                timeoutsChanged = false
            }

            val thisCall = try {
                execute(request)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ClientRequestException) {
                // 4xx 是服务端在回答, 换条线路也是同一个答案
                throw e
            } catch (_: ResponseException) {
                i++
                continue
            } catch (e: IOException) {
                if (route == BangumiRoute.Origin) originConnectFailed = true
                i++
                continue
            }
            lastCall = thisCall
            if (route == BangumiRoute.Origin) originAnswered.value = signature

            val status = thisCall.response.status.value
            val root = route.webRoot
            if (root != null && status in 300..399) {
                warnIfMirrorMoved(request, root, thisCall)
            }
            // API 地址回了网页 = 没回答这个请求: 镜像前面挂着反机器人验证页, 状态码照样是 200.
            // 当这条线路不通, 接着试下一条, 也不粘住 —— 否则调用方拿网页去解析, 整个会话停在只回网页的那条上
            if (status < 400 && isApiHost(originalHost) &&
                thisCall.response.contentType()?.match(ContentType.Text.Html) == true
            ) {
                logger.info { "Bangumi: ${route.label} answered an API request with a web page, trying the next route" }
                i++
                continue
            }
            if (status < 400) {
                if (sticky.value?.let { it.signature == signature && it.route == route } != true) {
                    logger.info { "Bangumi endpoint settled on ${route.label}" }
                    onSettled(routing, route)
                }
                // 只在换了线路 (或原来那条已过期) 时记时刻: 每次成功都刷新的话, 一直有请求的人永远粘在镜像上
                if (current?.route != route) {
                    sticky.value = Sticky(signature, route, clock.now().toEpochMilliseconds())
                }
                if (route != BangumiRoute.Origin && originConnectFailed) {
                    if (originAnswered.value == signature) {
                        logger.info { "Bangumi origin failed for $originalHost after answering earlier in this process; not treating it as unreachable" }
                    } else {
                        logger.info { "Bangumi origin is unreachable while ${route.label} works" }
                        onOriginUnreachable(routing, route)
                    }
                }
                return thisCall
            }
            // **4xx = 对方在回答**, 换一条也是同一个答案 (401 没登录 / 404 条目不存在 / 429 限流),
            // 原样交出去, 而且不改粘性 —— 这不是"这条不通"的证据.
            //
            // 判据必须写在这里: [Sender.execute] 在 `HttpSend` 这一层**不会**为 4xx 抛异常 (校验发生在更外层),
            // 上面那个 ClientRequestException 的 catch 根本轮不到. 漏了这一条的后果是: 未登录时那几个
            // `/p1/collections/subjects` 的 401 被算成官方不通, 于是一个能直连的用户整个会话被推到第三方反代上,
            // 而那条路按设计不带 token —— 登录之后收藏同步会一直失败 (2026-09-22 Shield 实测).
            if (status < 500) return thisCall
            i++
        }
        // 全都不通: 把最后一次的失败交出去, 让上层看到真实的错误
        return lastCall ?: throw IOException(
            "All bangumi endpoints failed for $originalHost. Tried: " + candidates.joinToString { it.label },
        )
    }

    /** 只回 JSON 的 API 子域 (不含主站与图床). */
    private fun isApiHost(host: String): Boolean =
        host == "api.${BangumiMirrorHosts.ORIGIN_ROOT}" || host == "next.${BangumiMirrorHosts.ORIGIN_ROOT}"

    /** 已经提示过「搬家了」的镜像, 每个只提示一次. */
    private val warnedMovedMirrors = MutableStateFlow(emptySet<String>())

    /**
     * 镜像把请求跳转到了别的域名 (镜像搬家时常见, 如 `bangumi.pro` → `bangumi.vip`).
     *
     * 匿名请求跟着跳过去照常能用, 但**跨域名跳转时 Ktor 会去掉 `Authorization` 头**, 带凭证的请求
     * 到了新域名上只会收到 401 —— 表现为「允许了登录经过镜像, 收藏却同步不了」, 日志里又只有一串 401.
     * 这里点明原因: 要把清单里的域名换成跳转之后的那个.
     */
    private fun warnIfMirrorMoved(request: HttpRequestBuilder, mirrorRoot: String, call: HttpClientCall) {
        if (!carriesCredentials(request)) return
        val location = call.response.headers[HttpHeaders.Location] ?: return
        // 相对地址按请求地址解析, 与 HttpRedirect 的做法一致
        val newHost = URLBuilder().takeFrom(call.request.url).takeFrom(location).host
        if (newHost == mirrorRoot || newHost.endsWith(".$mirrorRoot")) return
        if (mirrorRoot in warnedMovedMirrors.getAndUpdate { it + mirrorRoot }) return
        logger.warn {
            "Bangumi mirror $mirrorRoot redirects to $newHost; the redirect drops the Authorization header, " +
                    "so requests with credentials will fail there. Update the mirror list to the new domain."
        }
    }

    /** 进入本处理器时请求原本的超时; 试线路时改过之后, 换到下一条要恢复成这个. */
    private class Timeouts(val request: Long?, val connect: Long?, val socket: Long?)

    private companion object {
        /**
         * 还不知道一条线路通不通时, 试它的连接超时. 被墙的常见表现是 DNS 给一个不通的地址、TCP 一直连不上,
         * 默认 30 秒的连接超时会让大陆用户第一次打开白等半分钟 (之后才换线路并关掉「官方直连」).
         * bgm.tv 在 Cloudflare 后面, 正常网络下连接在几百毫秒内完成, 3 秒连不上基本就是不通.
         */
        const val PROBE_CONNECT_MILLIS = 3_000L

        /**
         * 同上, 空闲 (读) 超时. 按域名拦截的另一种表现是 TCP 连上了、TLS 握手没有回音, 这一段由读超时管,
         * 光缩连接超时拦不住. 比连接超时留得宽: 它也管等第一个字节, 官方偶尔慢一两秒不能被当成连不上
         * (那会把能直连的用户永久切走). 只在这条线路还没成功过时用, 成功之后恢复默认.
         */
        const val PROBE_SOCKET_MILLIS = 5_000L
    }
}
