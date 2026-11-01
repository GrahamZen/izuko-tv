/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.debugconsole

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.him188.ani.app.data.repository.player.EpisodePlayHistoryRepository
import me.him188.ani.app.domain.usecase.GlobalKoin
import me.him188.ani.app.navigation.AniNavigator
import me.him188.ani.app.navigation.MainScreenPage
import me.him188.ani.app.navigation.NavRoutes
import me.him188.ani.app.navigation.SettingsTab
import me.him188.ani.app.platform.ChoiceSwitch
import me.him188.ani.app.platform.DevSwitches
import me.him188.ani.app.platform.NumberSwitch
import me.him188.ani.app.platform.ToggleSwitch
import me.him188.ani.app.platform.currentAniBuildConfig
import me.him188.ani.app.ui.main.TvUpNextStore
import kotlin.time.Duration.Companion.seconds

/**
 * 调试接口的路由 (主线程上调). GET 与 POST 都收, 参数在查询串或表单里:
 *
 * - `` / `ping` / `api/state`: 版本、当前页面、播放进度
 * - `api/player`: 播放器整份状态 (候选按数据源分组、被排除的附原因); `api/player/select` (id) 选候选;
 *   `api/player/control` (action, ms, v) 播放控制; `api/player/episode` (id) 就地换集; `api/player/full-search` 完整搜索;
 *   `api/player/open` 回到正在播的播放页
 * - `api/nav`: 页面栈; `api/nav/back`; `api/nav/subject` (id) 详情页; `api/nav/episode` (subject, episode) 播放页;
 *   `api/nav/main` (page = Exploration / Collection / CacheManagement); `api/nav/settings` (tab)
 * - `api/history`: 最近看的番; `api/history/play` (id) 接着看 (同主屏「继续观看」); `api/history/open` (id) 详情页
 * - `api/logs` (lines, grep, file): 日志末尾, 纯文本; `api/logs/files`: 有哪些日志文件
 * - `api/screenshot`: 当前画面 PNG
 * - `api/settings`: 调试开关 (`debug` 一组, 同 Web 控制台); `api/settings/debug/set` (key = dev.<名字>, value)
 * - `api/debug/http` / `api/debug/webview` / `api/debug/resolve` / `api/debug/candidates`: 在这台设备上发请求、开网页、解析候选、列一个源的全部候选, 参数见 [DebugConsoleProbes]
 */
internal suspend fun handleDebugConsoleRequest(request: DebugConsoleRequest, config: DebugConsoleConfig): DebugConsoleResponse {
    val nav = DebugConsole.navigator
    return when (request.path) {
        "", "ping", "api/state" -> DebugConsoleResponse.json(
            buildJsonObject {
                put("ok", true)
                put("platform", "ios")
                put("version", currentAniBuildConfig.versionName)
                put("route", nav?.backStack?.lastOrNull()?.let(::routeLabel))
                DebugConsolePlayer.vm?.let {
                    put("subjectId", it.subjectId)
                    put("playback", DebugConsolePlayer.playback(it))
                }
            },
        )

        "api/player" -> DebugConsoleResponse.json(DebugConsolePlayer.state())
        "api/player/select" -> result(DebugConsolePlayer.select(request.params["id"].orEmpty()))
        "api/player/control" -> result(DebugConsolePlayer.control(request))
        "api/player/episode" -> {
            val id = request.int("id") ?: return result("needs id")
            result(DebugConsolePlayer.switchEpisode(id))
        }

        "api/player/full-search" -> {
            val vm = DebugConsolePlayer.vm ?: return result("no playback session")
            vm.setFullMediaSearch(true)
            result(null)
        }

        "api/player/open" -> {
            val session = DebugConsole.playback?.session ?: return result("no playback session")
            navigate(nav) { it.navigateEpisodeDetails(session.subjectId, session.episodeId, force = true) }
        }

        "api/nav" -> DebugConsoleResponse.json(
            buildJsonObject {
                putJsonArray("stack") { nav?.backStack?.forEach { route -> add(routeLabel(route)) } }
            },
        )

        "api/nav/back" -> navigate(nav) { it.popBackStack() }
        "api/nav/subject" -> {
            val id = request.int("id") ?: return result("needs id")
            navigate(nav) { it.navigateSubjectDetails(id, null) }
        }

        "api/nav/episode" -> {
            val subject = request.int("subject") ?: return result("needs subject")
            val episode = request.int("episode") ?: return result("needs episode")
            navigate(nav) { it.navigateEpisodeDetails(subject, episode) }
        }

        "api/nav/main" -> {
            val page = MainScreenPage.entries.firstOrNull { it.name.equals(request.params["page"], ignoreCase = true) }
                ?: MainScreenPage.Exploration
            navigate(nav) { it.popBackOrNavigateToMain(page) }
        }

        "api/nav/settings" -> {
            val tab = SettingsTab.entries.firstOrNull { it.name.equals(request.params["tab"], ignoreCase = true) }
            navigate(nav) { it.navigateSettings(tab) }
        }

        "api/history" -> {
            val history = recentHistory()
            DebugConsoleResponse.json(
                buildJsonObject {
                    putJsonArray("items") {
                        for (h in history) addJsonObject {
                            put("subjectId", h.subjectId)
                            put("subjectName", h.subjectName)
                            put("episodeId", h.episodeId)
                            put("episodeSort", h.episodeSort)
                            put("position", h.positionMillis)
                            put("duration", h.durationMillis)
                        }
                    }
                },
            )
        }

        "api/history/play" -> {
            val id = request.int("id") ?: return result("needs id")
            val record = recentHistory().firstOrNull { it.subjectId == id } ?: return result("not in history")
            val target = TvUpNextStore.resolveTarget(record) ?: return navigate(nav) { it.navigateSubjectDetails(id, null) }
            if (DebugConsolePlayer.vm?.subjectId == id && DebugConsolePlayer.switchEpisode(target.episodeId) == null) {
                result(null)
            } else {
                navigate(nav) { it.navigateEpisodeDetails(id, target.episodeId) }
            }
        }

        "api/history/open" -> {
            val id = request.int("id") ?: return result("needs id")
            navigate(nav) { it.navigateSubjectDetails(id, null) }
        }

        "api/logs" -> logs(request, config)
        "api/logs/files" -> DebugConsoleResponse.json(
            buildJsonObject {
                putJsonArray("files") {
                    for (file in logFiles(config)) addJsonObject {
                        put("name", file.name)
                        put("size", SystemFileSystem.metadataOrNull(file)?.size)
                    }
                }
            },
        )

        "api/screenshot" -> config.screenshotPng()?.let { DebugConsoleResponse(200, "image/png", it) }
            ?: DebugConsoleResponse.text("screenshot failed", 500)

        "api/settings" -> DebugConsoleResponse.json(devSwitches())
        "api/settings/debug/set" -> {
            val key = request.params["key"].orEmpty()
            val switch = DevSwitches.all.firstOrNull { "dev.${it.key}" == key } ?: return result("unknown switch $key")
            result(if (switch.set(request.params["value"].orEmpty())) null else "invalid value")
        }

        "api/debug/http" -> DebugConsoleResponse.json(DebugConsoleProbes.http(request))
        "api/debug/webview" -> DebugConsoleResponse.json(DebugConsoleProbes.webview(request))
        "api/debug/resolve" -> DebugConsoleResponse.json(DebugConsoleProbes.resolve(request))
        "api/debug/candidates" -> DebugConsoleResponse.json(DebugConsoleProbes.candidates(request))

        else -> DebugConsoleResponse.text("unknown path ${request.path}", 404)
    }
}

private fun result(error: String?): DebugConsoleResponse = DebugConsoleResponse.json(
    buildJsonObject {
        put("ok", error == null)
        error?.let { put("message", it) }
    },
    if (error == null) 200 else 400,
)

private inline fun navigate(nav: AniNavigator?, block: (AniNavigator) -> Unit): DebugConsoleResponse {
    nav ?: return result("navigator not ready")
    block(nav)
    return result(null)
}

/** 页面栈里的一项: 类名加参数, 太长的截断 (详情页的占位信息很长). */
private fun routeLabel(route: NavRoutes): String = route.toString().let { if (it.length > ROUTE_LABEL_MAX) it.take(ROUTE_LABEL_MAX) + "…" else it }

private suspend fun recentHistory() = withTimeoutOrNull(5.seconds) {
    GlobalKoin.get<EpisodePlayHistoryRepository>().allHistoriesFlow.first()
}.orEmpty()
    .filter { !it.isDeleted && it.subjectId != null }
    .sortedByDescending { it.versionMillis }
    .distinctBy { it.subjectId }
    .take(HISTORY_MAX)

private fun logFiles(config: DebugConsoleConfig): List<Path> =
    runCatching { SystemFileSystem.list(config.logsDir.path) }.getOrDefault(emptyList())
        .filter { it.name.startsWith("app.") && it.name.endsWith(".log") }
        .sortedBy { it.name }

/** 日志末尾 (最新的一份, 或 `file` 指定的那份): 只读最后 [LOG_TAIL_BYTES], 再按 `grep` 过滤、取最后 `lines` 行. */
private suspend fun logs(request: DebugConsoleRequest, config: DebugConsoleConfig): DebugConsoleResponse {
    config.flushLogs()
    val files = logFiles(config)
    val file = request.params["file"]?.let { name -> files.firstOrNull { it.name == name } } ?: files.lastOrNull()
        ?: return DebugConsoleResponse.text("no log files", 404)
    val lines = (request.int("lines") ?: 200).coerceIn(1, 20_000)
    val grep = request.params["grep"]?.takeIf { it.isNotEmpty() }
    val text = withContext(Dispatchers.Default) {
        val size = SystemFileSystem.metadataOrNull(file)?.size ?: 0L
        SystemFileSystem.source(file).buffered().use { source ->
            source.skip((size - LOG_TAIL_BYTES).coerceAtLeast(0))
            source.readByteArray().decodeToString()
        }
    }
    val selected = text.lineSequence().filter { grep == null || grep in it }.toList().takeLast(lines)
    return DebugConsoleResponse.text(selected.joinToString("\n"))
}

/** 调试开关, 字段同 Web 控制台的 `api/settings` 里 `debug` 那一组. */
private fun devSwitches(): JsonObject = buildJsonObject {
    putJsonArray("debug") {
        for (switch in DevSwitches.all) addJsonObject {
            put("key", "dev.${switch.key}")
            put("title", switch.title)
            switch.description?.let { put("description", it) }
            when (switch) {
                is ToggleSwitch -> {
                    put("editor", "toggle")
                    put("value", switch.value)
                }

                is NumberSwitch -> {
                    put("editor", "number")
                    put("value", switch.value)
                    put("min", switch.min)
                    put("max", switch.max)
                }

                is ChoiceSwitch -> {
                    put("editor", "choice")
                    put("value", switch.value)
                    putJsonArray("choices") { switch.choices.forEach { c -> addJsonObject { put("value", c) } } }
                }
            }
        }
    }
}

private const val ROUTE_LABEL_MAX = 200
private const val HISTORY_MAX = 30
private const val LOG_TAIL_BYTES = 2L * 1024 * 1024
