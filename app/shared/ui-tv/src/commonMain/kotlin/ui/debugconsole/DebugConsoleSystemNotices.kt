/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.debugconsole

import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.him188.ani.app.domain.usecase.GlobalKoin
import me.him188.ani.app.platform.BackgroundRefresh
import me.him188.ani.app.platform.SystemNotification
import me.him188.ani.app.platform.SystemNotifier
import me.him188.ani.app.ui.notice.TvSystemNotices
import me.him188.ani.utils.platform.currentTimeMillis
import kotlin.time.Duration.Companion.minutes

/**
 * 调试接口里的系统通知与后台刷新 (见 TvSystemNotices、BackgroundRefresh):
 * - `api/debug/system-notices`: 权限、设置、前后台、发过的、记着的、后台刷新登记的活、间隔、上次向系统要叫醒、最近几次跑的记录
 * - `api/debug/system-notices/test` (`title`、`body`、`key`): 发一条测试通知; 给了 `key` 时点开它等于处理通知中心里那一条
 * - `api/debug/system-notices/authorize`: 向系统要权限
 * - `api/debug/system-notices/refresh`: 现在把后台刷新的活跑一遍 (原因记成 debug)
 * - `api/debug/system-notices/schedule` (`minutes` 可省): 改「跑完之后最早过多久再叫」并马上向系统要一次
 */
internal object DebugConsoleSystemNotices {
    private fun notifier(): SystemNotifier? = runCatching { GlobalKoin.get<SystemNotifier>() }.getOrNull()

    suspend fun state(): JsonObject {
        val n = notifier()
        return buildJsonObject {
            put("supported", n != null)
            put("started", TvSystemNotices.isStarted)
            put("enabled", TvSystemNotices.enabled.first())
            if (n != null) {
                put("authorization", n.authorization().name)
                put("appActive", n.appActive.value)
            }
            putJsonArray("sent") {
                for (s in TvSystemNotices.sent.value) addJsonObject {
                    put("at", s.atMillis)
                    put("id", s.id)
                    put("title", s.title)
                }
            }
            putJsonArray("records") {
                for (r in TvSystemNotices.recordsSnapshot.takeLast(30)) add((if (r.delivered) "delivered " else "") + r.id)
            }
            putJsonArray("jobs") { BackgroundRefresh.jobNames.forEach { add(it) } }
            BackgroundRefresh.scheduler?.let { s ->
                put("intervalMinutes", s.interval.inWholeMinutes)
                put("lastSubmit", s.lastSubmitText)
            }
            putJsonArray("runs") {
                for (run in BackgroundRefresh.runs.value.take(30)) addJsonObject {
                    put("at", run.atMillis)
                    put("reason", run.reason)
                    put("durationMillis", run.durationMillis)
                    putJsonArray("results") { run.results.forEach { add(it) } }
                }
            }
        }
    }

    /** @return 失败原因; null = 发了 */
    fun test(request: DebugConsoleRequest): String? {
        val n = notifier() ?: return "no system notifier on this platform"
        val now = currentTimeMillis()
        n.post(
            SystemNotification(
                id = "debug-$now",
                title = request.params["title"]?.takeIf { it.isNotBlank() } ?: "Izuko test notification",
                body = request.params["body"]?.takeIf { it.isNotBlank() },
                group = "debug",
                payload = request.params["key"].orEmpty(),
            ),
        )
        return null
    }

    suspend fun authorize(): JsonObject {
        val n = notifier()
        return buildJsonObject {
            put("granted", n?.requestAuthorization() == true)
            n?.let { put("authorization", it.authorization().name) }
        }
    }

    suspend fun refresh(): JsonObject {
        val run = BackgroundRefresh.runAll("debug")
        return buildJsonObject {
            put("durationMillis", run.durationMillis)
            putJsonArray("results") { run.results.forEach { add(it) } }
        }
    }

    fun schedule(request: DebugConsoleRequest): JsonObject {
        val s = BackgroundRefresh.scheduler
        return buildJsonObject {
            if (s == null) {
                put("error", "no background refresh on this platform")
                return@buildJsonObject
            }
            val minutes = request.params["minutes"]?.toLongOrNull()
            val accepted = if (minutes != null) {
                s.setInterval(minutes.coerceIn(1, 24 * 60).minutes)
                s.lastSubmitText?.contains("accepted true") == true
            } else {
                s.schedule()
            }
            put("accepted", accepted)
            put("intervalMinutes", s.interval.inWholeMinutes)
            put("lastSubmit", s.lastSubmitText)
        }
    }
}
