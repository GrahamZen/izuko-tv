/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

import androidx.room.immediateTransaction
import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import androidx.sqlite.SQLITE_DATA_BLOB
import androidx.sqlite.SQLITE_DATA_NULL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.him188.ani.app.data.persistent.database.AniDatabase
import me.him188.ani.app.data.repository.media.PlaybackLineageRepository
import me.him188.ani.app.data.repository.media.SelectorMediaSourceEpisodeCacheRepository
import me.him188.ani.app.data.repository.subject.SubjectCollectionRepository
import me.him188.ani.app.domain.foundation.DebugNetworkHooks
import me.him188.ani.app.domain.player.diagnostics.PlayTimeline
import me.him188.ani.app.navigation.AniNavigator
import me.him188.ani.app.navigation.MainScreenPage
import me.him188.ani.app.ui.foundation.lan.LanHttpRequest
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import org.koin.mp.KoinPlatform
import java.net.URLDecoder
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Web 控制台的调试操作 (`api/debug/...`): 测试脚本要的状态与动作直接用接口做, 不必去电视上一层层按; 只在有调试区的包里收
 * (见 [RemoteDebugSettings.enabled]).
 *
 * - `GET api/debug/db?sql=SELECT …`: 只读查库, 给列名与至多 [MAX_ROWS] 行 (blob 列只给字节数).
 * - `POST api/debug/db` `sql=UPDATE …`: 改库, 一条语句, 在一个事务里; 改完通知 Room, 开着的页面跟着变.
 *   用来造「条目 / 分集过期」「缺分集」这类状态, 正式包名也行 (不像 run-as 只能对 debug 包).
 * - `POST api/debug/collections/refresh` `type=WISH|DOING|DONE|ON_HOLD|DROPPED|ALL`: 收藏页下拉刷新的那一步
 *   ([SubjectCollectionRepository.refreshCollectionList]), 返回本地是否已与服务端一致.
 * - `POST api/debug/nav` `page=Exploration|Collection|CacheManagement`: 电视回到主界面并切到这一页 (同侧边栏切页, 不另开一个主页).
 * - `GET / POST api/debug/net/block` `rules=api.bgm.tv,api.bangumi.vip` `hang=20`: 网址里含这些片段的请求直接失败 (同连不上;
 *   `hang` 秒后才失败 = 模拟超时), `rules` 空 = 全部放行, 见 [DebugNetworkHooks]. 只在内存里, 重启就没了.
 * - `POST api/debug/requests/reset` 从现在起记录请求, `GET api/debug/requests[?raw=1]` 按接口数次数 (另给逐条),
 *   `POST api/debug/requests/stop` 停.
 * - `GET api/debug/timeline`: 最近几次开播的时间线 (见 [PlayTimeline], 开了调试开关 `playDiagnostics` 才有): 每次开播一组 `[序号, 距开始的毫秒, 步骤, 说明]`.
 * - `GET api/debug/stacks`: 全部线程的栈 (查开播卡在哪一步), 在 `text` 里.
 * - `POST api/debug/search/clear` `[subject=<条目 id>] [memory=1]`: 清搜索记录, 量第一次搜的耗时用: 网页类数据源的搜索缓存与跨条目共用的
 *   站点回应记忆 (给了 subject 时只清这一部的缓存行); `memory=1` 时连这一部记住的数据源与播放线索一起清 (同第一次看).
 */
internal object RemoteDebugActions {
    private val logger = logger<RemoteDebugActions>()
    // 线程之间空一行, 每一帧另起一行 (api/debug/stacks)
    private val STACK_SEPARATOR = String(charArrayOf('\n', '\n'))
    private val FRAME_PREFIX = String(charArrayOf('\n')) + "    at "
    private val database: AniDatabase get() = KoinPlatform.getKoin().get()
    private val collections: SubjectCollectionRepository get() = KoinPlatform.getKoin().get()
    private val searchCache: SelectorMediaSourceEpisodeCacheRepository get() = KoinPlatform.getKoin().get()
    private val lineages: PlaybackLineageRepository get() = KoinPlatform.getKoin().get()

    private const val MAX_ROWS = 200

    fun handle(request: LanHttpRequest, navigator: AniNavigator?, scope: CoroutineScope): JsonObject? {
        if (!RemoteDebugSettings.enabled) return result(false, tr("只有 debug 包能用调试操作"))
        val get = request.method == "GET" || request.method == "HEAD"
        val post = request.method == "POST"
        return runCatching {
            when {
                request.path == "api/debug/db" && get -> query(request.queryParam("sql").orEmpty())
                request.path == "api/debug/db" && post -> execute(request.formFields()["sql"].orEmpty())
                request.path == "api/debug/collections/refresh" && post -> refreshCollections(request.formFields()["type"].orEmpty())
                request.path == "api/debug/nav" && post -> navigate(request.formFields()["page"].orEmpty(), navigator, scope)
                request.path == "api/debug/net/block" && get -> blockRules()
                request.path == "api/debug/net/block" && post -> setBlockRules(request.formFields())
                request.path == "api/debug/requests" && get -> requests(raw = request.queryParam("raw") == "1")
                request.path == "api/debug/requests/reset" && post -> DebugNetworkHooks.startRecording().let { result(true, "recording") }
                request.path == "api/debug/requests/stop" && post -> DebugNetworkHooks.stopRecording().let { result(true, "stopped") }
                request.path == "api/debug/search/clear" && post -> clearSearch(request.formFields())
                request.path == "api/debug/timeline" && get -> buildJsonObject { put("ok", true); put("sessions", PlayTimeline.toJson()) }
                request.path == "api/debug/stacks" && get -> buildJsonObject {
                    put("ok", true)
                    put(
                        "text",
                        Thread.getAllStackTraces().entries.sortedBy { it.key.name }.joinToString(STACK_SEPARATOR) { (t, frames) ->
                            "thread ${t.name} (${t.state})" + frames.take(40).joinToString("") { FRAME_PREFIX + it }
                        },
                    )
                }
                request.path.startsWith("api/debug/settings/") -> RemoteDebugSettingsPage.handle(request, navigator)
                else -> null
            }
        }.getOrElse {
            logger.warn(it) { "Remote debug action failed: ${request.method} ${request.path}" }
            result(false, it.message ?: it::class.simpleName.orEmpty())
        }
    }

    private fun query(sql: String): JsonObject {
        if (sql.isBlank()) return result(false, "sql is empty")
        return runBlocking {
            database.useReaderConnection { connection ->
                connection.usePrepared(sql) { statement ->
                    val columns = (0 until statement.getColumnCount()).map { statement.getColumnName(it) }
                    buildJsonObject {
                        put("ok", true)
                        putJsonArray("columns") { columns.forEach { add(it) } }
                        var rows = 0
                        var truncated = false
                        putJsonArray("rows") {
                            while (statement.step()) {
                                if (rows == MAX_ROWS) {
                                    truncated = true
                                    break
                                }
                                rows++
                                addJsonArray {
                                    for (i in columns.indices) {
                                        add(
                                            when (statement.getColumnType(i)) {
                                                SQLITE_DATA_NULL -> JsonNull
                                                SQLITE_DATA_BLOB -> JsonPrimitive("<blob ${statement.getBlob(i).size} bytes>")
                                                else -> JsonPrimitive(statement.getText(i))
                                            },
                                        )
                                    }
                                }
                            }
                        }
                        put("truncated", truncated)
                    }
                }
            }
        }
    }

    private fun execute(sql: String): JsonObject {
        if (sql.isBlank()) return result(false, "sql is empty")
        logger.info { "Remote debug SQL: $sql" }
        runBlocking {
            database.useWriterConnection { connection ->
                connection.immediateTransaction { usePrepared(sql) { it.step() } }
            }
        }
        database.invalidationTracker.refreshAsync()
        return result(true, "ok")
    }

    private fun clearSearch(form: Map<String, String>): JsonObject {
        val subjectId = form["subject"]?.toIntOrNull()
        val memory = form["memory"] == "1"
        if (memory && subjectId == null) return result(false, "memory=1 needs subject")
        if (subjectId == null) {
            execute("DELETE FROM web_search_session_cache")
            searchCache.siteMemory.clear()
        }
        runBlocking {
            if (subjectId != null) searchCache.clearByRequestedSubject(subjectId)
            if (memory && subjectId != null) {
                database.preferredWebMediaSourceDao().deletePreferredMediaSource(subjectId)
                lineages.setLineage(subjectId, null)
            }
        }
        logger.info { "Remote debug: cleared search records${subjectId?.let { " of subject $it" }.orEmpty()}${if (memory) " and its remembered source" else ""}" }
        return result(true, "ok")
    }

    private fun refreshCollections(type: String): JsonObject {
        val collectionType = if (type == "ALL") null else UnifiedCollectionType.entries.firstOrNull { it.name == type }
            ?: return result(false, "unknown type: $type")
        val inSync = runBlocking { collections.refreshCollectionList(collectionType) }
        return buildJsonObject {
            put("ok", true)
            put("inSync", inSync)
        }
    }

    private fun navigate(page: String, navigator: AniNavigator?, scope: CoroutineScope): JsonObject {
        val target = MainScreenPage.entries.firstOrNull { it.name == page } ?: return result(false, "unknown page: $page")
        val nav = navigator ?: return result(false, tr("电视还没准备好"))
        TvRemoteControl.notifyRemoteNavigation()
        // 同侧边栏切页: 弹回已有的主页再切 tab. 用 navigateMain 会再压一个主页进栈, 主页的 ViewModel 跟着多一份,
        // 收藏计数之类的请求就成了两份
        scope.launch(Dispatchers.Main) { nav.popBackOrNavigateToMain(target) }
        return result(true, "ok")
    }

    private fun blockRules(): JsonObject = buildJsonObject {
        put("ok", true)
        putJsonArray("rules") {
            for (rule in DebugNetworkHooks.blockRules) addJsonObject {
                put("pattern", rule.pattern)
                put("hangSeconds", rule.hang.inWholeMilliseconds / 1000.0)
            }
        }
    }

    /** `rules`: 网址里的片段, 逗号或换行分隔, 空 = 全部放行; `hang`: 先等几秒再失败 (默认立刻失败). */
    private fun setBlockRules(form: Map<String, String>): JsonObject {
        val hang = form["hang"]?.toDoubleOrNull()?.takeIf { it > 0 }?.seconds ?: Duration.ZERO
        DebugNetworkHooks.blockRules = form["rules"].orEmpty().split(',', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { DebugNetworkHooks.BlockRule(it, hang) }
        logger.info { "Remote debug block rules: ${DebugNetworkHooks.blockRules}" }
        return blockRules()
    }

    /**
     * 记下的请求按接口归并 (方法 + 主机 + 路径, 数字段换成 `{n}`, 不含查询串), 次数多的在前; [raw] 时另给逐条.
     * 都只算留着的最近那些 (见 [DebugNetworkHooks]), `total` 是记录以来的总数.
     */
    private fun requests(raw: Boolean): JsonObject {
        val (since, total, records) = DebugNetworkHooks.snapshot()
        return buildJsonObject {
            put("ok", true)
            put("recording", since != null)
            since?.let { put("sinceMillis", it) }
            put("total", total)
            put("kept", records.size)
            putJsonArray("endpoints") {
                for ((endpoint, list) in records.groupBy { endpointKey(it.method, it.url) }.entries.sortedByDescending { it.value.size }) {
                    addJsonObject {
                        put("endpoint", endpoint)
                        put("count", list.size)
                        putJsonObject("statuses") {
                            for ((status, count) in list.groupingBy { it.status }.eachCount()) put(status, count)
                        }
                    }
                }
            }
            if (raw) {
                putJsonArray("requests") {
                    for (r in records) addJsonObject {
                        put("at", r.atMillis)
                        put("method", r.method)
                        put("url", r.url)
                        put("status", r.status)
                        put("ms", r.duration.inWholeMilliseconds)
                    }
                }
            }
        }
    }

    private val numberSegment = Regex("""/\d+(?=/|$)""")

    private fun endpointKey(method: String, url: String): String =
        "$method " + url.substringBefore('?').substringAfter("://").replace(numberSegment, "/{n}")

    private fun LanHttpRequest.queryParam(name: String): String? =
        query.split('&').firstOrNull { it.substringBefore('=') == name }
            ?.substringAfter('=', "")
            ?.let { URLDecoder.decode(it, "UTF-8") }

    private fun result(ok: Boolean, message: String): JsonObject = buildJsonObject {
        put("ok", ok)
        put("message", message)
    }
}
