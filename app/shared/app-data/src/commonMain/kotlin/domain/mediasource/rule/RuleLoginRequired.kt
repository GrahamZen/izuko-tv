/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.rule

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 取流时站点明确说要登录才能播: 回来的是 JSON, 里面提示类的字段 (`message` / `msg` / `error` / `tip` 等) 写着要登录.
 * 只认 JSON 里的提示字段, 不看网页正文: 网页上到处是「登录」入口, 说明不了这一集要登录.
 */
internal object RuleLoginRequired {
    /** [body] 里有要登录的提示时返回站点的原话, 否则 `null` */
    fun messageOf(body: String): String? {
        val text = body.trim()
        if (!text.startsWith('{')) return null
        val root = runCatching { Json.parseToJsonElement(text) }.getOrNull() ?: return null
        return hints(root, depth = 0).firstOrNull { LOGIN.containsMatchIn(it) }
    }

    private fun hints(element: JsonElement, depth: Int): Sequence<String> = sequence {
        if (depth > MAX_DEPTH) return@sequence
        when (element) {
            is JsonObject -> for ((key, value) in element) {
                if (value is JsonPrimitive && value.isString && key.lowercase().replace("_", "") in HINT_KEYS) yield(value.content)
                else yieldAll(hints(value, depth + 1))
            }

            is JsonArray -> for (value in element) yieldAll(hints(value, depth + 1))
            else -> Unit
        }
    }

    private const val MAX_DEPTH = 4
    private val HINT_KEYS = setOf("message", "msg", "error", "errmsg", "errormsg", "errormessage", "tip", "tips", "reason")
    private val LOGIN = Regex("""登录|登入|登陆|log ?in|sign ?in|unauthori[sz]ed""", RegexOption.IGNORE_CASE)
}
