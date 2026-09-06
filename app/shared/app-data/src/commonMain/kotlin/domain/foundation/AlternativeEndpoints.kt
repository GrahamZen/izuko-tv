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
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.Sender
import io.ktor.client.plugins.plugin
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.encodedPath
import io.ktor.http.takeFrom
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.io.IOException
import me.him188.ani.app.data.models.preference.EndpointSelection
import me.him188.ani.app.data.models.preference.EndpointUrls
import me.him188.ani.app.data.repository.user.Settings
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import kotlin.coroutines.cancellation.CancellationException

/**
 * 一项有好几个可互换入口的服务: 同一份内容, 不同的域名 (如 TMDB 图片的 `image.tmdb.org` 与 `images.tmdb.org`).
 * 某个入口在某些网络下被封时, 换一个就行.
 *
 * 数据里、缓存键里、各处拼地址的常量里一律只写原站 ([canonicalBaseUrl]), 发请求的那一刻才换成实际要打的入口
 * (见 [AlternativeEndpointsFeatureHandler]): 换入口不会让图片缓存作废, 调用方也不用知道有这回事.
 *
 * 入口从哪来: 用户的选择 ([selection]) + 仓库维护的清单 ([list]); 设置页、首次启动引导、网页控制台都按这一个对象
 * 展示与修改. 新加一项服务: 建一个子类, 在 Koin 里注册, 再交给 [AlternativeEndpointsFeatureHandler].
 */
open class AlternativeEndpoints(
    /** 日志里的称呼. */
    val name: String,
    /** 原站的地址前缀, 形如 `https://image.tmdb.org`; 以它开头的请求才归这一项管. */
    val canonicalBaseUrl: String,
    /** 检测连通性时接在每个入口后面 (或换进模板) 的路径: 一个小而稳定的真实资源. */
    val probePath: String,
    val selection: Settings<EndpointSelection>,
    val list: RepoHostedList,
    scope: CoroutineScope,
) {
    /** 仓库维护的候选入口, 按优先级排. */
    val candidates: Flow<List<String>> get() = list.entries

    /**
     * 依次试的入口 (已归一化), 见 [EndpointSelection.baseUrls].
     *
     * **必须是热流**: 每个请求都要 `first()` 它一次. 设置与清单读出来之前是 `null`, 请求先等着 (两者都是本地
     * DataStore, 很快) —— 拿一个占位值顶上的话, 启动时头几个请求会绕过用户的选择.
     */
    val baseUrls: StateFlow<List<String>?> = combine(selection.flow, list.entries) { selection, candidates ->
        selection.baseUrls(candidates)
    }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, null)
}

val AlternativeEndpointsFeature = ScopedHttpClientFeatureKey<Boolean>("AlternativeEndpoints")

/**
 * 把发往 [AlternativeEndpoints.canonicalBaseUrl] 的请求换到实际要打的入口上: 按 [AlternativeEndpoints.baseUrls]
 * 的顺序试, 连不上 (连接失败、握手被重置、超时) 或回 5xx 换下一个, 记住通的那个, 之后的请求从它开始.
 *
 * 回了 4xx 不换: 那是对方在回答 (比如图片不存在), 换一个入口也是同一个答案.
 *
 * 与 [BangumiMirrorFeatureHandler] 是同一个位置 (`HttpSend` 拦截), 但没有凭证那一层顾虑: 归这里管的都是
 * 不带凭证的公开资源.
 */
class AlternativeEndpointsFeatureHandler(
    services: List<AlternativeEndpoints>,
) : ScopedHttpClientFeatureHandler<Boolean>(AlternativeEndpointsFeature) {
    private val logger = logger<AlternativeEndpointsFeatureHandler>()

    private class Service(val endpoints: AlternativeEndpoints) {
        val canonical = Url(endpoints.canonicalBaseUrl)

        /** 上次成功的入口. 入口列表变了 (改了选择、清单更新) 就作废, 重新从头试. */
        val sticky = atomic<Sticky?>(null)

        fun matches(url: URLBuilder): Boolean =
            url.host == canonical.host && url.encodedPath.startsWith(canonical.encodedPath)
    }

    private data class Sticky(val baseUrls: List<String>, val baseUrl: String)

    private val services = services.map(::Service)

    override fun applyToClient(client: HttpClient, value: Boolean) {
        if (!value) return
        client.plugin(HttpSend).intercept { request ->
            handle(request) ?: execute(request)
        }
    }

    /** @return `null` = 这个请求不归本特性管, 调用方照常发 */
    private suspend fun Sender.handle(request: HttpRequestBuilder): HttpClientCall? {
        val service = services.firstOrNull { it.matches(request.url) } ?: return null
        val baseUrls = service.endpoints.baseUrls.filterNotNull().first()
        if (baseUrls.isEmpty()) return null
        // 原站前缀之后的部分 (带查询串), 接到入口后面或换进模板, 见 EndpointUrls.resolve
        val original = request.url.build()
        val path = original.encodedPath.removePrefix(service.canonical.encodedPath) +
                original.encodedQuery.let { if (it.isEmpty()) "" else "?$it" }
        val sticky = service.sticky.value?.takeIf { it.baseUrls == baseUrls }?.baseUrl
        val start = sticky?.let { baseUrls.indexOf(it).coerceAtLeast(0) } ?: 0

        var lastCall: HttpClientCall? = null
        var lastError: IOException? = null
        for (i in baseUrls.indices) {
            val baseUrl = baseUrls[(start + i) % baseUrls.size]
            // 用 Url 版的 takeFrom: 它整个换掉查询参数; 字符串版是往已有的参数上追加, 上一个入口 (模板) 的参数会留到下一个上
            request.url.takeFrom(Url(EndpointUrls.resolve(baseUrl, path)))
            val call = try {
                execute(request)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                logger.info { "${service.endpoints.name}: $baseUrl failed: $e" }
                lastError = e
                continue
            }
            if (call.response.status.value >= 500) {
                lastCall = call
                continue
            }
            val settled = Sticky(baseUrls, baseUrl)
            if (service.sticky.getAndSet(settled) != settled) {
                logger.info { "${service.endpoints.name} endpoint settled on $baseUrl" }
            }
            return call
        }
        // 全都不通: 把最后一次的失败交出去, 让上层看到真实的错误
        return lastCall ?: throw lastError ?: IOException("All ${service.endpoints.name} endpoints failed: $baseUrls")
    }
}
