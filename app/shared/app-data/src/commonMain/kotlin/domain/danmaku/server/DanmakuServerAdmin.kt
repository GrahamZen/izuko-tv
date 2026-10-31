/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.danmaku.server

import io.ktor.http.Url
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import me.him188.ani.app.domain.mediasource.subscription.MediaSourceSubscription
import me.him188.ani.danmaku.dandanplay.server.DanmakuServerException
import me.him188.ani.danmaku.dandanplay.server.DanmakuServerRouteMissingException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

/**
 * 应用里管弹幕服务器 (触屏版设置页的「弹幕服务器」弹窗): 列出、添加、删除、启停、测试。规则同 Web 控制台的那一组:
 * 订阅带来的只能停用, 不能删; 添加时地址先规范化 ([normalizeDanmakuServerUrl]), 加上后测一次连接, 测不通也留着 (告诉用户)。
 */
class DanmakuServerAdmin(
    private val registry: DanmakuServerRegistry,
    private val store: DanmakuServerStore,
    /** 数据源订阅 (认出服务器是哪个订阅带来的, 写它的名字) */
    private val subscriptions: Flow<List<MediaSourceSubscription>>,
) {
    /** 列表里的一台. [url] 可能带访问令牌, 界面上只写 [host]; [from] = 带来它的订阅名, 自己加的为 null. */
    data class Server(
        val name: String,
        val url: String,
        val host: String,
        val enabled: Boolean,
        val from: String?,
    ) {
        val manual: Boolean get() = from == null
    }

    /** 测试连接的结果. */
    sealed interface Probe {
        data object Ok : Probe
        data object Timeout : Probe

        /** 连不上; [serverMessage] 是服务器回的说明 (有就显示它). */
        data class Failed(val serverMessage: String?) : Probe
    }

    sealed interface AddResult {
        /** 地址不是 http(s), 或是应用自带的官方接口. */
        data object InvalidUrl : AddResult
        data object Duplicate : AddResult
        data class Added(val probe: Probe) : AddResult
    }

    suspend fun list(): List<Server> {
        val subs = subscriptions.first().associateBy { it.subscriptionId }
        return registry.items().map { item ->
            Server(
                name = item.name,
                url = item.baseUrl,
                host = runCatching { Url(item.baseUrl).host }.getOrDefault(item.name),
                enabled = item.enabled,
                from = item.subscriptionId?.let { id ->
                    val sub = subs[id]
                    sub?.metadata?.name ?: sub?.url?.let { url -> runCatching { Url(url).host }.getOrNull() }.orEmpty()
                },
            )
        }
    }

    suspend fun add(rawName: String, rawUrl: String): AddResult {
        val url = normalizeDanmakuServerUrl(rawUrl) ?: return AddResult.InvalidUrl
        val name = rawName.trim()
        if (registry.items().any { it.baseUrl == url }) return AddResult.Duplicate
        store.update { it.copy(servers = it.servers + DanmakuServerEntry(name = name, url = url)) }
        return AddResult.Added(probe(name.ifEmpty { "server" }, url))
    }

    /** 删掉自己加的那台; 订阅带来的删不了 (返回 false). */
    suspend fun delete(url: String): Boolean {
        val before = store.current()
        val remaining = before.servers.filterNot { normalizeDanmakuServerUrl(it.url) == url }
        if (remaining.size == before.servers.size) return false
        store.update { it.copy(servers = remaining, disabledUrls = it.disabledUrls - url) }
        return true
    }

    suspend fun setEnabled(url: String, on: Boolean) {
        store.update { it.copy(disabledUrls = if (on) it.disabledUrls - url else it.disabledUrls + url) }
    }

    /** 测试一台已有的; 找不到 (刚被删) 时为 null. */
    suspend fun test(url: String): Probe? {
        val item = registry.items().firstOrNull { it.baseUrl == url } ?: return null
        return probe(item.name, item.baseUrl)
    }

    private suspend fun probe(name: String, baseUrl: String): Probe = try {
        val done = withTimeoutOrNull(PROBE_TIMEOUT) {
            try {
                registry.probeProvider(name, baseUrl).probe()
            } catch (_: DanmakuServerRouteMissingException) {
                // 没有版本接口的服务器不少, 能回 HTTP 就算连上了
            }
        }
        if (done == null) Probe.Timeout else Probe.Ok
    } catch (e: CancellationException) {
        throw e
    } catch (e: DanmakuServerException) {
        Probe.Failed(e.serverMessage)
    } catch (_: Exception) {
        // 地址解析不出、连不上等网络错误
        Probe.Failed(null)
    }

    private companion object {
        val PROBE_TIMEOUT = 12.seconds
    }
}
