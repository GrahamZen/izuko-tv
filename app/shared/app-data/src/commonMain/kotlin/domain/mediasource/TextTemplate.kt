/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource

import io.ktor.http.encodeURLParameter
import kotlinx.serialization.json.JsonPrimitive

/**
 * 数据源配置里的文本模板 (搜索地址、请求体、标题等): `{名字}` 换成变量值, 各类数据源共用.
 *
 * - `{名字:url}` URL 编码, `{名字:raw}` 原样, `{名字:json}` 按 JSON 字符串转义 (不带引号);
 * - 只写 `{名字}` 时按 [expand] 的 `encodeByDefault` 决定编不编码;
 * - 没有这个变量的 `{...}` 原样保留 (JSON 里本来就有的花括号也不会被当成变量).
 * 一遍展开, 变量值里再出现 `{...}` 不会被二次展开.
 */
internal object TextTemplate {
    // 安卓的正则 (ICU) 不认没转义的 `}`, 花括号一律转义
    private val VARIABLE = Regex("""\{([A-Za-z_][A-Za-z0-9_]*)(?::(raw|url|json))?\}""")

    fun expand(template: String, variables: Map<String, String>, encodeByDefault: (name: String) -> Boolean): String {
        if (template.isEmpty() || '{' !in template) return template
        return VARIABLE.replace(template) { match ->
            val name = match.groupValues[1]
            val value = variables[name] ?: return@replace match.value
            val mode = match.groupValues[2].ifEmpty { if (encodeByDefault(name)) "url" else "raw" }
            when (mode) {
                "url" -> value.encodeURLParameter()
                "json" -> JsonPrimitive(value).toString().removeSurrounding("\"")
                else -> value
            }
        }
    }
}
