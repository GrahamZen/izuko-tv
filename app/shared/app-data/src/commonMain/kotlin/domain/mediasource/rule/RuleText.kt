/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.rule

import io.ktor.http.encodeURLParameter
import me.him188.ani.utils.ktor.UrlHelpers
import me.him188.ani.utils.xml.Element

/**
 * 规则里的文本处理: 模板展开、地址补全与规整、HTML 片段.
 */
internal object RuleText {
    // 安卓的正则 (ICU) 不认没转义的 `}`, 花括号一律转义
    private val VARIABLE = Regex("""\{([A-Za-z_][A-Za-z0-9_]*)(?::(raw|url))?\}""")

    /** 默认做 URL 编码的变量. */
    private const val KEYWORD = "keyword"

    /**
     * 展开模板里的 `{名字}` / `{名字:raw}` / `{名字:url}`. 没有这个变量的原样保留.
     */
    fun substitute(template: String, variables: Map<String, String>): String {
        if (template.isEmpty() || '{' !in template) return template
        return VARIABLE.replace(template) { match ->
            val name = match.groupValues[1]
            val value = variables[name] ?: return@replace match.value
            val mode = match.groupValues[2].ifEmpty { if (name == KEYWORD) "url" else "raw" }
            if (mode == "url") value.encodeURLParameter() else value
        }
    }

    /**
     * 以 [base] 为基准把 [ref] 补全成绝对地址. [ref] 本身是绝对地址时原样返回; 补全不了返回 `null`.
     */
    fun resolveUrl(base: String, ref: String): String? {
        val trimmed = ref.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
        if (trimmed.startsWith("javascript:", ignoreCase = true) || trimmed.startsWith("#")) return null
        if (base.isBlank()) return null
        return UrlHelpers.computeAbsoluteUrlOrNull(base, trimmed)
    }

    /**
     * 把地址里不能直接出现的字符 (空格、中文等) 按 UTF-8 百分号编码, 已有的 `%XX` 不动.
     * 站点给的地址常常直接带中文路径, 播放器与 HTTP 客户端要的是编码后的形式.
     */
    fun normalizeUrl(url: String): String {
        val trimmed = url.trim()
        if (trimmed.all { it.code in 0x21..0x7E && it !in UNSAFE_ASCII }) return trimmed
        return buildString(trimmed.length + 16) {
            for (c in trimmed) {
                if (c.code in 0x21..0x7E && c !in UNSAFE_ASCII) {
                    append(c)
                } else {
                    for (byte in c.toString().encodeToByteArray()) {
                        append('%')
                        append(HEX[(byte.toInt() shr 4) and 0xF])
                        append(HEX[byte.toInt() and 0xF])
                    }
                }
            }
        }
    }

    private const val UNSAFE_ASCII = "\"<>\\^`{|}"
    private const val HEX = "0123456789ABCDEF"

    /**
     * 元素的内部 HTML. `<script>` / `<style>` 的内容不算文本 ([Element.text] 取不到), 也用它取.
     */
    fun innerHtml(element: Element): String {
        // Element.toString() 是外层 HTML (公共 API 里没有 html())
        val outer = element.toString()
        val start = outer.indexOf('>') + 1
        val end = outer.lastIndexOf("</")
        return if (start in 1..end) outer.substring(start, end).trim() else ""
    }

    /**
     * JavaScript 的 `unescape`: `%uXXXX` 与 `%XX` 都按字符码还原 (不是 UTF-8 解码).
     */
    fun jsUnescape(value: String): String {
        if ('%' !in value) return value
        val sb = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '%') {
                val unicode = if (value.getOrNull(i + 1) == 'u') hexOrNull(value, i + 2, 4) else null
                if (unicode != null) {
                    sb.append(unicode.toChar())
                    i += 6
                    continue
                }
                val byte = hexOrNull(value, i + 1, 2)
                if (byte != null) {
                    sb.append(byte.toChar())
                    i += 3
                    continue
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    private fun hexOrNull(text: String, start: Int, length: Int): Int? {
        if (start + length > text.length) return null
        var result = 0
        for (i in start until start + length) {
            val digit = text[i].digitToIntOrNull(16) ?: return null
            result = result * 16 + digit
        }
        return result
    }

    /**
     * 从 [start] 之后的第一个 `{` 起取出一个完整的 JSON 对象文本 (按括号配对, 跳过字符串里的括号). 取不到返回 `null`.
     */
    fun jsonObjectAfter(text: String, start: Int): String? {
        val open = text.indexOf('{', start)
        if (open < 0) return null
        var depth = 0
        var inString = false
        var quote = '"'
        var i = open
        while (i < text.length) {
            val c = text[i]
            if (inString) {
                when (c) {
                    '\\' -> i++
                    quote -> inString = false
                }
            } else {
                when (c) {
                    '"', '\'' -> {
                        inString = true
                        quote = c
                    }

                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return text.substring(open, i + 1)
                    }
                }
            }
            i++
        }
        return null
    }

    /**
     * 编译正则. 编译不过时按 JavaScript 的宽松写法再试一次: 不构成量词 (`{n}` `{n,}` `{n,m}`) 的花括号当作字面量
     * (例如 `"key"\s*:\s*{`). 从网页规则搬来的正则常这样写, Java / 安卓 (ICU) 的正则不认.
     */
    fun regex(pattern: String, ignoreCase: Boolean = false): Regex {
        val options = if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
        return try {
            Regex(pattern, options)
        } catch (e: IllegalArgumentException) {
            val lenient = escapeLoneBraces(pattern)
            if (lenient == pattern) throw e
            Regex(lenient, options)
        }
    }

    private fun escapeLoneBraces(pattern: String): String = buildString(pattern.length + 8) {
        var i = 0
        var inClass = false
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                c == '\\' && i + 1 < pattern.length -> {
                    append(c).append(pattern[i + 1])
                    i += 2
                    continue
                }

                inClass -> {
                    if (c == ']') inClass = false
                    append(c)
                }

                c == '[' -> {
                    inClass = true
                    append(c)
                }

                c == '{' -> {
                    val end = pattern.indexOf('}', i)
                    if (end > i && QUANTIFIER_BODY.matches(pattern.substring(i + 1, end))) {
                        append(pattern, i, end + 1)
                        i = end + 1
                        continue
                    }
                    append("\\{")
                }

                c == '}' -> append("\\}")
                else -> append(c)
            }
            i++
        }
    }

    private val QUANTIFIER_BODY = Regex("""\d+(,\d*)?""")

    /** 日志里展示的值: 去掉换行, 截短. */
    fun preview(value: String, max: Int = 120): String {
        val flat = value.replace('\n', ' ').replace('\r', ' ')
        return if (flat.length <= max) flat else flat.take(max) + "…(${flat.length})"
    }
}
