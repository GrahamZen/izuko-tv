/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.feedback

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.him188.ani.app.ui.diagnostics.TvPerfDiagnostics

/**
 * 附到反馈的一份性能诊断报告 (见 [TvPerfDiagnostics], 控制台「维护 → 性能诊断」里已有的体检 / 录制):
 * 网页上怎么显示、issue 里写哪一行, 以及报告原文 (放在日志附件开头).
 */
internal class FeedbackPerfReport(
    val id: String,
    /** `health` = 设备体检, 其余 = 录制. */
    val kind: String,
    val seconds: Int,
    /** 录制时停在哪个页面 (导航栈顶). */
    val page: String?,
    /** 保存的时刻, 给人看的 (`MM-dd HH:mm:ss`). */
    val time: String,
    /** 第一条结论; 没发现问题时为 `null`. */
    val headline: String?,
    /** 报告原文 (JSON). */
    val text: String,
) {
    val health: Boolean get() = kind == "health"

    /** issue 里「性能报告」那张表的一行: 键给维护者看 (中文), 值是结论. */
    val row: Pair<String, String>
        get() = (if (health) "设备体检 $time" else "录制 $seconds 秒 · ${page ?: "?"} · $time") to (headline ?: "没有发现问题")

    companion object {
        /** 读编号为 [id] 的报告; 不在了或读不出来为 `null`. */
        fun read(id: String): FeedbackPerfReport? {
            val text = TvPerfDiagnostics.reportFile(id)?.let { runCatching { it.readText() }.getOrNull() } ?: return null
            val report = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
            return of(report, text)
        }

        /** 报告列表里的一份 (不带原文). */
        fun summary(report: JsonObject): FeedbackPerfReport? = of(report, "")

        private fun of(report: JsonObject, text: String): FeedbackPerfReport? {
            val id = report["id"]?.jsonPrimitive?.contentOrNull ?: return null
            return FeedbackPerfReport(
                id = id,
                kind = report["kind"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                seconds = report["seconds"]?.jsonPrimitive?.intOrNull ?: 0,
                page = report["page"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() },
                time = report["time"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                headline = TvPerfDiagnostics.headlineOf(report),
                text = text,
            )
        }
    }
}
