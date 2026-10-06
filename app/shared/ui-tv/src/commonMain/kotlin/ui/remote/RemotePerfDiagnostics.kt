/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.him188.ani.app.ui.diagnostics.TvPerfDiagnostics
import me.him188.ani.app.ui.foundation.lan.LanHttpRequest
import me.him188.ani.app.ui.foundation.lan.LanHttpResponse

/**
 * 「设置 → 维护 → 性能诊断」的接口 (网页见 PERF_SCRIPT, 采集与分析见 [TvPerfDiagnostics]):
 *
 * - `GET api/diag`: 当前状态 (空闲 / 录制中还剩几秒 / 体检中) + 报告列表 (每份带第一条结论).
 * - `POST api/diag/health`: 跑一次设备体检, 返回整份报告.
 * - `POST api/diag/record` (seconds): 开始录制; 期间用户在电视上照常操作.
 * - `POST api/diag/stop`: 提前结束录制 (录到的照样分析).
 * - `GET api/diag/report/<id>`: 整份报告 (JSON, 网页展开详情与下载都用它).
 */
internal object RemotePerfDiagnostics {
    private const val MIN_SECONDS = 5
    private const val MAX_SECONDS = 120
    private val ID = Regex("[0-9a-z-]{1,40}")

    /** 不是本组的路径返回 null. */
    fun handle(request: LanHttpRequest): LanHttpResponse? {
        val path = request.path
        val get = request.method == "GET" || request.method == "HEAD"
        val post = request.method == "POST"
        return when {
            path == "api/diag" && get -> json(state())
            path == "api/diag/health" && post -> json(health())
            path == "api/diag/record" && post -> json(record(request))
            path == "api/diag/stop" && post -> json(stop())
            path.startsWith("api/diag/report/") && get -> report(path.removePrefix("api/diag/report/"))
            else -> null
        }
    }

    private fun state(): JsonObject = buildJsonObject {
        put("ok", true)
        putJsonObject("status") {
            when (val s = TvPerfDiagnostics.status.value) {
                is TvPerfDiagnostics.Status.Recording -> {
                    put("state", "recording")
                    put("seconds", s.seconds)
                    put("remaining", ((s.endsAtMillis - System.currentTimeMillis() + 999) / 1000).coerceAtLeast(0))
                }

                TvPerfDiagnostics.Status.CheckingHealth -> put("state", "checking")
                TvPerfDiagnostics.Status.Idle -> put("state", "idle")
            }
        }
        putJsonArray("reports") {
            for (r in TvPerfDiagnostics.reports()) addJsonObject {
                for (key in listOf("id", "kind", "time", "seconds", "page")) r[key]?.let { put(key, it) }
                put("headline", TvPerfDiagnostics.headlineOf(r))
                r["findings"]?.let { put("findings", it) }
            }
        }
    }

    private fun health(): JsonObject {
        val report = TvPerfDiagnostics.runHealthCheck()
            ?: return result(false, tr("现在不能体检：电视上的 Izuko 还没打开界面，或者正在录制"))
        return buildJsonObject {
            put("ok", true)
            put("report", report)
        }
    }

    private fun record(request: LanHttpRequest): JsonObject {
        val seconds = request.formFields()["seconds"]?.toIntOrNull()?.coerceIn(MIN_SECONDS, MAX_SECONDS)
            ?: return result(false, tr("无效的时长"))
        TvPerfDiagnostics.startRecording(seconds)?.let { return result(false, it) }
        return result(true, tr("开始录制 {0} 秒，请在电视上照常操作要测的界面", seconds))
    }

    private fun stop(): JsonObject {
        TvPerfDiagnostics.stopRecording()
        return result(true, tr("已结束录制，正在分析"))
    }

    private fun report(id: String): LanHttpResponse {
        if (!ID.matches(id)) return LanHttpResponse.status(404, "Not Found")
        val file = TvPerfDiagnostics.reportFile(id) ?: return LanHttpResponse.status(404, "Not Found")
        return LanHttpResponse.fileSnapshot(file, "application/json; charset=utf-8")
    }

    private fun json(obj: JsonObject): LanHttpResponse =
        LanHttpResponse.bytes(obj.toString().toByteArray(), "application/json; charset=utf-8")

    private fun result(ok: Boolean, message: String): JsonObject = buildJsonObject {
        put("ok", ok)
        put("message", message)
    }
}
