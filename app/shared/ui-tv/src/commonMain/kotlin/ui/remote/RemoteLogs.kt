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
import me.him188.ani.app.ui.foundation.lan.LanHttpRequest
import me.him188.ani.app.ui.foundation.lan.LanHttpResponse
import me.him188.ani.datasources.api.topic.FileSize.Companion.bytes
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「设置」标签底部的「日志」: 列出电视上的日志文件, 点文件名直接下载到手机 —— 与设置 → 日志 →「扫码传到手机」同一批文件,
 * 手机已经连着 Web 控制台就不必再去电视上开那个弹窗. 目录由 TV 根组合登记 ([TvRemoteControl.logsDirProvider]), 没登记时 (桌面) 不显示.
 */
internal object RemoteLogs {
    /** `api/logs` 列表 (JSON) 与 `api/logs/<文件名>` 下载; 不是 GET / HEAD 时返回 null. */
    fun handle(request: LanHttpRequest): LanHttpResponse? {
        if (request.method != "GET" && request.method != "HEAD") return null
        val dir = TvRemoteControl.logsDirProvider?.invoke()
        if (request.path == "api/logs") {
            return LanHttpResponse.bytes(list(dir).toString().toByteArray(), "application/json; charset=utf-8")
        }
        // 只认目录列表里有的名字, 不拿手机给的字符串去拼路径 (同 LogLanShareServer)
        val name = request.path.removePrefix("api/logs/")
        val file = logFiles(dir).firstOrNull { it.name == name } ?: return LanHttpResponse.status(404, "Not Found")
        return LanHttpResponse.fileSnapshot(file, "text/plain; charset=utf-8")
    }

    private fun logFiles(dir: File?): List<File> =
        dir?.listFiles { f -> f.isFile && f.name.endsWith(".log") }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()

    private fun list(dir: File?): JsonObject {
        val time = SimpleDateFormat("MM-dd HH:mm", Locale.ROOT)
        return buildJsonObject {
            put("ok", true)
            put("supported", dir != null)
            putJsonArray("items") {
                for (f in logFiles(dir)) addJsonObject {
                    put("name", f.name)
                    put("size", f.length().bytes.toString())
                    put("time", time.format(Date(f.lastModified())))
                }
            }
        }
    }
}
