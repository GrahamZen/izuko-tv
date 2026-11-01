/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.debugconsole

import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.json.JsonElement

/** 调试接口收到的一次请求: [path] 是口令段之后的路径 (如 `api/player`), [params] 是查询串与表单合在一起 (表单的同名项优先). */
internal class DebugConsoleRequest(
    val method: String,
    val path: String,
    val params: Map<String, String>,
) {
    fun int(name: String): Int? = params[name]?.trim()?.toIntOrNull()
}

internal class DebugConsoleResponse(val status: Int, val contentType: String, val body: ByteArray) {
    companion object {
        fun json(element: JsonElement, status: Int = 200) =
            DebugConsoleResponse(status, "application/json; charset=utf-8", element.toString().encodeToByteArray())

        fun text(text: String, status: Int = 200) =
            DebugConsoleResponse(status, "text/plain; charset=utf-8", text.encodeToByteArray())
    }
}

/**
 * 解析请求行 (`GET /<口令>/api/player?x=1 HTTP/1.1`) 与表单正文. 路径不在 `/<口令>` 之下返回 null (按 404 回, 不透露有这个服务).
 */
internal fun parseDebugConsoleRequest(requestLine: String, body: String, token: String): DebugConsoleRequest? {
    val parts = requestLine.trim().split(' ')
    if (parts.size < 2) return null
    val target = parts[1]
    val rawPath = target.substringBefore('?').trimStart('/')
    if (rawPath != token && !rawPath.startsWith("$token/")) return null
    val path = rawPath.removePrefix(token).trim('/')
    return DebugConsoleRequest(
        method = parts[0].uppercase(),
        path = decodeFormComponent(path, plusIsSpace = false),
        params = parseForm(target.substringAfter('?', "")) + parseForm(body),
    )
}

/** `a=1&b=%E4%B8%AD` 这种查询串 / 表单正文. */
internal fun parseForm(encoded: String): Map<String, String> =
    encoded.split('&').filter { it.isNotEmpty() }.associate { pair ->
        decodeFormComponent(pair.substringBefore('=')) to decodeFormComponent(pair.substringAfter('=', ""))
    }

/** 百分号解码 (按 UTF-8); [plusIsSpace] 时 `+` 是空格 (表单里是, 路径里不是). 坏掉的 `%xx` 原样保留. */
internal fun decodeFormComponent(s: String, plusIsSpace: Boolean = true): String {
    if ('%' !in s && (!plusIsSpace || '+' !in s)) return s
    val out = Buffer()
    var i = 0
    var runStart = 0
    while (i < s.length) {
        val c = s[i]
        val hi = if (c == '%' && i + 2 <= s.lastIndex) s[i + 1].digitToIntOrNull(16) else null
        val lo = if (hi != null) s[i + 2].digitToIntOrNull(16) else null
        val hex = if (hi != null && lo != null) hi * 16 + lo else null
        if (hex != null || (plusIsSpace && c == '+')) {
            out.writeString(s, runStart, i)
            if (hex != null) {
                out.writeByte(hex.toByte())
                i += 3
            } else {
                out.writeByte(' '.code.toByte())
                i++
            }
            runStart = i
        } else {
            i++
        }
    }
    out.writeString(s, runStart, s.length)
    return out.readString()
}
