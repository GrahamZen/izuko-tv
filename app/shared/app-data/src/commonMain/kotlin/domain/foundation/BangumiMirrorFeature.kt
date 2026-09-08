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
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.plugin
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
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
import kotlin.time.Duration.Companion.minutes

/**
 * 把发往 bangumi 的请求改写到镜像域名上, 并在原站不通时依次回落.
 *
 * **为什么做在 HttpClient 这一层, 而不是改各处的 base URL**: 除了 `BangumiApiProvider` 那两个常量,
 * 还有一堆各自写死地址的地方 (`BangumiSummaryService`、`BangumiScheduleSource`、OAuth 的 token 端点、
 * 富文本里的表情图…), 而**图片也走同一个 `ScopedHttpClient`** (Sketch 用的就是它) —— 在这里改写,
 * 一处覆盖 API + 图床 + OAuth 换 token, 新增调用点零配置.
 *
 * ## 凭证默认不发给第三方镜像
 *
 * 判据见 [BangumiMirrorFeatureHandler.carriesCredentials] (`Authorization` 头, 以及 `/oauth/` 那条
 * 带着授权码与 client_secret 的路径).
 *
 * 带凭证 + 镜像不可信 ⇒ **不改写, 老老实实走直连**(在大陆就是失败): 反代能看到经过它的全部内容.
 * 净效果是"镜像模式下匿名浏览可用、登录与收藏同步不可用" —— 与 Kazumi 的默认做法一致.
 *
 * 可信的镜像 ([BangumiRouting.trusted]) 不受此限: 用户自建的服务器, 或者用户在弹窗里了解风险后
 * 自己允许凭证经过的第三方镜像 (见 `BangumiEndpointSettings.allowCredentialsViaMirror`).
 */
val BangumiMirrorFeature = ScopedHttpClientFeatureKey<Boolean>("BangumiMirror")

/**
 * 一次请求该往哪儿发. 由设置 + 镜像清单算出来, 见 `BangumiEndpointProvider`.
 */
data class BangumiRouting(
    /** 依次尝试的镜像根域名 (已归一化, 形如 `bangumi.pro`). 空 = 只直连. */
    val mirrors: List<String>,
    /** 这些镜像可不可信 (用户自建, 或用户允许凭证经过) —— 只有可信的才允许带着 token 过去. */
    val trusted: Boolean,
    /** `true` = 直连优先, 失败才试镜像; `false` = 直接用镜像 (用户明确指定了自建地址). */
    val preferDirect: Boolean,
    /**
     * 数据里可能出现的镜像根域名. 镜像会把响应里的图片等地址改写成自己的域名, 这些地址随数据存进本地;
     * 请求它们时先换回原站域名, 再按本路由决定打哪儿 —— 不然切回官方之后它们还一直走镜像, 镜像下线就全挂.
     */
    val knownMirrors: List<String> = mirrors,
) {
    companion object {
        /** 只直连. */
        val Direct = BangumiRouting(emptyList(), trusted = true, preferDirect = true)
    }
}

class BangumiMirrorFeatureHandler(
    private val routing: Flow<BangumiRouting>,
    /** 请求落到的目标变了时回报: 镜像根域名, `null` = 原站. 见 `BangumiEndpointProvider.reportSettled`. */
    private val onSettled: (mirrorRoot: String?) -> Unit = {},
    /**
     * 确定官方连不上时回报: 同一个请求在官方那一跳**连接失败** (不是 5xx —— 那是官方在回答), 换到镜像成功.
     * 见 `BangumiEndpointProvider.reportOriginUnreachable`.
     */
    private val onOriginUnreachable: () -> Unit = {},
    private val clock: Clock = Clock.System,
) : ScopedHttpClientFeatureHandler<Boolean>(BangumiMirrorFeature) {
    private val logger = logger<BangumiMirrorFeatureHandler>()

    /**
     * 上次成功的目标, `-1` = 原站, `>=0` = 镜像下标; [at] 是换到这个目标的时刻. 路由一变 (改了连接方式、
     * 清单更新) 就作废 —— 从「用镜像」手动改回「官方连不上时用镜像」, 下一个请求就该重新先试官方.
     *
     * 没有它的话, 大陆用户每个请求都要先吃一次直连超时 —— 而直连被阻断时那是十几秒量级.
     *
     * **粘在镜像上的会过期 ([MIRROR_STICKY_MILLIS], 从换过去那一刻算, 期间请求再多也不续), 粘在原站上的不会**:
     * 原站回 5xx 这类临时故障换到镜像之后, 过期了就重新先试直连, 不需要镜像的人能自愈. 原站**连不上**则另有出路:
     * 回报给 [onOriginUnreachable], 「官方连不上时用镜像」据此改成「用镜像」, 路由里从此没有原站.
     */
    private data class Sticky(val signature: Int, val target: Int, val at: Long)

    private val sticky = atomic<Sticky?>(null)

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
        request.headers.contains(HttpHeaders.Authorization) ||
                "oauth" in request.url.pathSegments

    /** @return `null` = 这个请求不该由本特性处理, 调用方照常发 */
    private suspend fun io.ktor.client.plugins.Sender.handle(request: HttpRequestBuilder): HttpClientCall? {
        val routing = routing.first()
        val requestHost = request.url.host
        // 数据里存着的镜像地址 (见 BangumiRouting.knownMirrors) 先换回原站, 下面统一按路由走
        val originalHost = if (BangumiMirrorHosts.isBangumiHost(requestHost)) {
            requestHost
        } else {
            routing.knownMirrors.firstNotNullOfOrNull { BangumiMirrorHosts.originHostOf(requestHost, it) } ?: return null
        }
        request.url.host = originalHost
        if (routing.mirrors.isEmpty()) return null

        if (!routing.trusted && carriesCredentials(request)) {
            logger.debug { "Bangumi request to $originalHost carries credentials, keeping it on the origin" }
            return null
        }

        // -1 表示原站. 用户明确指定自建地址时不再试原站 (他要的就是那个地址)
        val targets = if (routing.preferDirect) {
            listOf(-1) + routing.mirrors.indices
        } else {
            routing.mirrors.indices.toList()
        }
        val signature = routing.hashCode()
        // 还有效的粘性: 原站永久有效, 镜像会过期, 见 Sticky 的文档
        val current = sticky.value?.takeIf {
            it.signature == signature && it.target in targets &&
                    (it.target < 0 || clock.now().toEpochMilliseconds() - it.at < MIRROR_STICKY_MILLIS)
        }
        val startIndex = current?.let { targets.indexOf(it.target) } ?: 0

        var lastCall: HttpClientCall? = null
        var originConnectFailed = false
        for (i in targets.indices) {
            val target = targets[(startIndex + i) % targets.size]
            val host = if (target < 0) {
                originalHost
            } else {
                BangumiMirrorHosts.mirrorHostOf(originalHost, routing.mirrors[target]) ?: continue
            }
            request.url.host = host
            if (lastCall != null) {
                logger.info { "Bangumi: retrying $originalHost on $host" }
            }

            val thisCall = try {
                execute(request)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ClientRequestException) {
                // 4xx 是服务端在回答, 换个镜像也是同一个答案
                throw e
            } catch (_: ResponseException) {
                continue
            } catch (_: IOException) {
                if (target < 0) originConnectFailed = true
                continue
            }
            lastCall = thisCall

            val status = thisCall.response.status.value
            if (target >= 0 && status in 300..399) {
                warnIfMirrorMoved(request, routing.mirrors[target], thisCall)
            }
            if (status < 400) {
                if (sticky.value?.let { it.signature == signature && it.target == target } != true) {
                    logger.info {
                        "Bangumi endpoint settled on ${if (target < 0) "origin" else routing.mirrors[target]}"
                    }
                    onSettled(if (target < 0) null else routing.mirrors[target])
                }
                // 只在换了目标 (或原来那个已过期) 时记时刻: 每次成功都刷新的话, 一直有请求的人永远粘在镜像上
                if (current?.target != target) {
                    sticky.value = Sticky(signature, target, clock.now().toEpochMilliseconds())
                }
                if (target >= 0 && originConnectFailed) {
                    logger.info { "Bangumi origin is unreachable while ${routing.mirrors[target]} works" }
                    onOriginUnreachable()
                }
                return thisCall
            }
            // **4xx = 对方在回答**, 换一家也是同一个答案 (401 没登录 / 404 条目不存在 / 429 限流),
            // 原样交出去, 而且不改粘性 —— 这不是"这家不通"的证据.
            //
            // 判据必须写在这里: [io.ktor.client.plugins.Sender.execute] 在 `HttpSend` 这一层**不会**
            // 为 4xx 抛异常 (校验发生在更外层), 下面那个 ClientRequestException 的 catch 根本轮不到.
            // 漏了这一条的后果是: 未登录时那几个 `/p1/collections/subjects` 的 401 被算成原站不通,
            // 于是一个能直连的用户整个会话被推到第三方反代上, 而那条路按设计不带 token ——
            // 登录之后收藏同步会一直失败 (2026-09-22 Shield 实测).
            if (status < 500) return thisCall
        }
        // 全都不通: 把最后一次的失败交出去, 让上层看到真实的错误
        return lastCall ?: throw IOException(
            "All bangumi endpoints failed for $originalHost. Tried: origin, ${routing.mirrors}",
        )
    }

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

    private companion object {
        /** 粘在镜像上最多多久; 到期后重新先试直连. 见 [Sticky]. */
        val MIRROR_STICKY_MILLIS = 30.minutes.inWholeMilliseconds
    }
}
