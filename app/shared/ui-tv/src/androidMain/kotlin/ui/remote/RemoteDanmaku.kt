/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

import io.ktor.http.Url
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.him188.ani.app.data.repository.media.MediaSourceSubscriptionRepository
import me.him188.ani.app.domain.danmaku.imported.DanmakuImporter
import me.him188.ani.app.domain.danmaku.imported.DanmakuLinkImportResult
import me.him188.ani.app.domain.danmaku.imported.ImportedDanmakuProvider
import me.him188.ani.app.domain.danmaku.imported.ImportedDanmakuStore
import me.him188.ani.app.domain.danmaku.server.DanmakuServerEntry
import me.him188.ani.app.domain.danmaku.server.DanmakuServerRegistry
import me.him188.ani.app.domain.danmaku.server.DanmakuServerStore
import me.him188.ani.app.domain.danmaku.server.normalizeDanmakuServerUrl
import me.him188.ani.app.ui.foundation.lan.LanHttpRequest
import me.him188.ani.danmaku.api.provider.DanmakuProviderId
import me.him188.ani.danmaku.api.provider.Imported
import me.him188.ani.danmaku.dandanplay.server.DanmakuServerException
import me.him188.ani.danmaku.dandanplay.server.DanmakuServerRouteMissingException
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import org.koin.mp.KoinPlatform
import java.net.URLDecoder
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

/**
 * Web 控制台里的弹幕服务器与导入弹幕:
 * - `api/danmaku/servers…`: 「设置 → 播放与弹幕」的弹幕服务器列表. 手动添加的可以删, 订阅带来的只能停用 (跟着订阅走).
 * - `api/danmaku/import/…`: 播放器标签「弹幕」里的导入, 都是导入到电视上正在播的这一集. 导入完经
 *   `vm.onMatchingDanmakuComplete` 立刻显示, 与手动匹配完成同一个入口; 以后播这一集由 [ImportedDanmakuProvider] 读出来.
 *
 * 文件原文放在请求体里, 只给这一个接口放宽请求体上限 ([maxBodyBytes]).
 */
internal object RemoteDanmaku {
    private val logger = logger<RemoteDanmaku>()

    private const val FILE_PATH = "api/danmaku/import/file"
    private const val MAX_FILE_BYTES = 16L shl 20
    private const val MAX_LABEL_LENGTH = 120
    private val PROBE_TIMEOUT = 12.seconds

    private val registry: DanmakuServerRegistry get() = KoinPlatform.getKoin().get()
    private val store: DanmakuServerStore get() = KoinPlatform.getKoin().get()
    private val subscriptions: MediaSourceSubscriptionRepository get() = KoinPlatform.getKoin().get()
    private val importer: DanmakuImporter get() = KoinPlatform.getKoin().get()
    private val importedStore: ImportedDanmakuStore get() = KoinPlatform.getKoin().get()
    private val importedProvider: ImportedDanmakuProvider get() = KoinPlatform.getKoin().get()

    fun maxBodyBytes(path: String): Long? = if (path == FILE_PATH) MAX_FILE_BYTES else null

    /** 处理 `api/danmaku/` 下的请求; 路径或方法不认识返回 null. */
    fun handle(player: RemotePlayerHandle?, request: LanHttpRequest): JsonObject? {
        val get = request.method == "GET" || request.method == "HEAD"
        val post = request.method == "POST"
        return runCatching {
            when {
                request.path == "api/danmaku/servers" && get -> listServers()
                request.path == "api/danmaku/import/list" && get -> listImports(player)
                !post -> null
                request.path == FILE_PATH -> importFile(player, request)
                else -> {
                    val f = request.formFields()
                    when (request.path) {
                        "api/danmaku/servers/add" -> addServer(f["name"].orEmpty(), f["url"].orEmpty())
                        "api/danmaku/servers/delete" -> deleteServer(f["url"].orEmpty())
                        "api/danmaku/servers/enable" -> setServerEnabled(f["url"].orEmpty(), f["on"] == "1")
                        "api/danmaku/servers/test" -> testServer(f["url"].orEmpty())
                        "api/danmaku/import/link" -> importLink(player, f["url"].orEmpty())
                        "api/danmaku/import/delete" -> deleteImport(player, f["id"].orEmpty())
                        else -> null
                    }
                }
            }
        }.getOrElse {
            logger.warn { "Remote danmaku request failed: ${request.path}: ${it::class.simpleName}" }
            result(false, tr("操作失败：{0}", it.message ?: it::class.simpleName))
        }
    }

    // ============================ 弹幕服务器 ============================

    private fun listServers(): JsonObject {
        val items = runBlocking { registry.items() }
        val subs = runBlocking { subscriptions.flow.first() }.associateBy { it.subscriptionId }
        return buildJsonObject {
            put("ok", true)
            putJsonArray("items") {
                for (item in items) addJsonObject {
                    put("name", item.name)
                    put("url", item.baseUrl)
                    // 地址里可能有访问令牌, 列表上只显示域名
                    put("host", runCatching { Url(item.baseUrl).host }.getOrDefault(item.name))
                    put("on", item.enabled)
                    put("manual", item.subscriptionId == null)
                    item.subscriptionId?.let { id ->
                        val sub = subs[id]
                        put("from", sub?.metadata?.name ?: sub?.url?.let { url -> runCatching { Url(url).host }.getOrNull() }.orEmpty())
                    }
                    put("auto", item.autoMatch)
                    put("link", item.linkImport)
                }
            }
        }
    }

    private fun addServer(rawName: String, rawUrl: String): JsonObject {
        val url = normalizeDanmakuServerUrl(rawUrl)
            ?: return result(false, tr("地址要以 http:// 或 https:// 开头；弹弹play 官方已经内置，不用添加"))
        val name = rawName.trim()
        val exists = runBlocking { registry.items() }.any { it.baseUrl == url }
        if (exists) return result(false, tr("这台服务器已经添加过了"))
        runBlocking {
            store.update { it.copy(servers = it.servers + DanmakuServerEntry(name = name, url = url)) }
        }
        logger.info { "Remote control added a danmaku server" }
        return when (val failure = probe(name.ifEmpty { "server" }, url)) {
            null -> result(true, tr("已添加，连接正常"))
            else -> result(true, tr("已添加，但连接测试没有通过：{0}", failure))
        }
    }

    private fun deleteServer(url: String): JsonObject {
        val before = runBlocking { store.current() }
        val remaining = before.servers.filterNot { normalizeDanmakuServerUrl(it.url) == url }
        if (remaining.size == before.servers.size) return result(false, tr("找不到这台服务器"))
        runBlocking { store.update { it.copy(servers = remaining, disabledUrls = it.disabledUrls - url) } }
        return result(true, tr("已删除"))
    }

    private fun setServerEnabled(url: String, on: Boolean): JsonObject {
        runBlocking {
            store.update { it.copy(disabledUrls = if (on) it.disabledUrls - url else it.disabledUrls + url) }
        }
        return result(true, "")
    }

    private fun testServer(url: String): JsonObject {
        val item = runBlocking { registry.items() }.firstOrNull { it.baseUrl == url }
            ?: return result(false, tr("找不到这台服务器"))
        return when (val failure = probe(item.name, item.baseUrl)) {
            null -> result(true, tr("连接正常"))
            else -> result(false, tr("连接测试没有通过：{0}", failure))
        }
    }

    /** @return 失败的说明; 连得上时为 null */
    private fun probe(name: String, baseUrl: String): String? = try {
        val done = runBlocking {
            withTimeoutOrNull(PROBE_TIMEOUT) {
                try {
                    registry.probeProvider(name, baseUrl).probe()
                } catch (_: DanmakuServerRouteMissingException) {
                    // 没有版本接口的服务器不少, 能回 HTTP 就算连上了
                }
            }
        }
        if (done == null) tr("服务器太久没有回应") else null
    } catch (e: CancellationException) {
        throw e
    } catch (e: DanmakuServerException) {
        e.serverMessage ?: tr("连不上服务器")
    }

    // ============================ 导入弹幕 ============================

    private class CurrentEpisode(val handle: RemotePlayerHandle, val subjectId: Int, val episodeId: Int)

    private fun currentEpisode(player: RemotePlayerHandle?): CurrentEpisode? {
        val handle = player ?: return null
        val episodeId = handle.currentEpisodeId ?: return null
        return CurrentEpisode(handle, handle.vm.subjectId, episodeId)
    }

    private fun listImports(player: RemotePlayerHandle?): JsonObject {
        val current = currentEpisode(player) ?: return result(false, tr("电视当前不在播放页"))
        val batches = runBlocking { importedStore.list(current.subjectId, current.episodeId) }
        val canLink = runBlocking { registry.providers() }.any { it.supportsLinkImport }
        return buildJsonObject {
            put("ok", true)
            put("canLink", canLink)
            putJsonArray("items") {
                for (batch in batches) addJsonObject {
                    put("id", batch.id)
                    put("label", batch.label)
                    put("count", batch.count)
                }
            }
        }
    }

    private fun importFile(player: RemotePlayerHandle?, request: LanHttpRequest): JsonObject {
        val current = currentEpisode(player) ?: return result(false, tr("电视当前不在播放页"))
        val name = request.query.split('&')
            .firstOrNull { it.startsWith("name=") }
            ?.let { URLDecoder.decode(it.removePrefix("name="), "UTF-8") }
            .orEmpty()
            .ifBlank { tr("弹幕文件") }
            .take(MAX_LABEL_LENGTH)
        val batch = runBlocking { importer.importFile(current.subjectId, current.episodeId, name, request.body) }
            ?: return result(false, tr("这不是弹幕文件（支持 XML 和弹弹play 的 JSON）"))
        if (batch.count == 0) return result(false, tr("文件里没有能显示的弹幕"))
        showImported(current)
        logger.info { "Remote control imported a danmaku file: ${batch.count} danmaku" }
        return result(true, tr("已导入 {0} 条弹幕", batch.count))
    }

    private fun importLink(player: RemotePlayerHandle?, input: String): JsonObject {
        val current = currentEpisode(player) ?: return result(false, tr("电视当前不在播放页"))
        return when (val r = runBlocking { importer.importLink(current.subjectId, current.episodeId, input) }) {
            is DanmakuLinkImportResult.Success -> {
                showImported(current)
                logger.info { "Remote control imported ${r.batch.count} danmaku by link" }
                result(true, tr("已导入 {0} 条弹幕（来自「{1}」）", r.batch.count, r.serverName))
            }

            DanmakuLinkImportResult.InvalidLink -> result(false, tr("这不是网页链接"))
            DanmakuLinkImportResult.NoServer -> result(false, tr("没有能按链接取弹幕的服务器，先在「设置 → 播放与弹幕」里添加"))
            DanmakuLinkImportResult.Empty -> result(false, tr("服务器没有取到这个链接的弹幕。有的服务器只支持番剧页面的链接，手机分享的短链接也不一定支持，可以换成完整链接试试"))
            DanmakuLinkImportResult.Timeout -> result(false, tr("服务器太久没有回应，请重试"))
            is DanmakuLinkImportResult.Failed -> result(false, tr("服务器没能取到弹幕：{0}", r.message ?: tr("未知错误")))
        }
    }

    private fun deleteImport(player: RemotePlayerHandle?, id: String): JsonObject {
        val current = currentEpisode(player) ?: return result(false, tr("电视当前不在播放页"))
        val removed = runBlocking { importedStore.remove(current.subjectId, current.episodeId, id) }
        if (!removed) return result(false, tr("找不到这批弹幕"))
        showImported(current)
        return result(true, tr("已删除"))
    }

    /** 让电视上正在播的这一集立刻换成最新的导入结果 (删光时那一项弹幕源随之消失). */
    private fun showImported(current: CurrentEpisode) {
        val results = runBlocking { importedProvider.results(current.subjectId, current.episodeId) }
        current.handle.runOnUi { current.handle.vm.onMatchingDanmakuComplete(DanmakuProviderId.Imported, results) }
    }

    private fun result(ok: Boolean, message: String): JsonObject = buildJsonObject {
        put("ok", ok)
        put("message", message)
    }
}
