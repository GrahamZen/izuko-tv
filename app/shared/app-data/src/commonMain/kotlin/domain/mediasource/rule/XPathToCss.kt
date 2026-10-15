/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.rule

/**
 * 把规则里简单的 XPath 换成 CSS 选择器 (规则源只用 CSS). AniBaka 规则里的 XPath 都是「按节点名与 class / id / href 挑元素」,
 * CSS 写得出来. 认得的写法:
 *
 * - 路径: 开头的 `//` (任意位置)、`/` (从根)、`.//` (相对的后代)、`./` 或不带前缀 (相对的子元素); 步与步之间的 `/` (子) 与 `//` (后代);
 *   每步是节点名或 `*`.
 * - 谓词, 可以用 `and` 连接: `@a`、`@a='v'`、`contains(@a, 'v')`、`starts-with(@a, 'v')`、`contains(text(), 'v')` (自身的文字)、
 *   `contains(., 'v')` (含子元素的文字)、序号 `[n]`.
 *
 * 别的写法 (轴、取属性值的 `/@href`、`or`、`|` 等) 返回 `null`.
 */
internal object XPathToCss {
    fun convert(xpath: String): String? {
        val text = xpath.trim()
        if (text.isEmpty() || '|' in text.withoutQuoted()) return null
        val (start, firstCombinator) = when {
            text.startsWith(".//") -> 3 to ""
            text.startsWith("./") -> 2 to "> "
            text.startsWith("//") -> 2 to ""
            text.startsWith("/") -> 1 to ""
            else -> 0 to "> "
        }
        val out = StringBuilder()
        var index = start
        var combinator = firstCombinator
        while (index < text.length) {
            val end = stepEnd(text, index) ?: return null
            out.append(combinator).append(step(text.substring(index, end)) ?: return null)
            index = end
            if (index >= text.length) break
            combinator = if (text.startsWith("//", index)) " " else " > "
            index += if (text.startsWith("//", index)) 2 else 1
            if (index >= text.length) return null
        }
        return out.toString().takeIf { it.isNotBlank() }
    }

    /** 从 [start] 起这一步在哪里结束 (下一个不在方括号与引号里的 `/`, 或末尾). 括号不配对时为 `null`. */
    private fun stepEnd(text: String, start: Int): Int? {
        var depth = 0
        var quote: Char? = null
        for (i in start until text.length) {
            val c = text[i]
            when {
                quote != null -> if (c == quote) quote = null
                c == '\'' || c == '"' -> quote = c
                c == '[' -> depth++
                c == ']' -> if (--depth < 0) return null
                c == '/' && depth == 0 -> return i
            }
        }
        return if (depth == 0 && quote == null) text.length else null
    }

    /** 一步: 节点名 (或 `*`) 加若干谓词. */
    private fun step(step: String): String? {
        val name = NODE_TEST.find(step)?.value ?: return null
        val out = StringBuilder(name)
        var rest = step.substring(name.length).trim()
        while (rest.isNotEmpty()) {
            if (!rest.startsWith("[")) return null
            val close = predicateEnd(rest) ?: return null
            val predicate = rest.substring(1, close).trim()
            for (part in splitAnd(predicate)) out.append(term(part, isWildcard = name == "*") ?: return null)
            rest = rest.substring(close + 1).trim()
        }
        return out.toString()
    }

    /** `[` 开头的谓词的 `]` 在哪里. */
    private fun predicateEnd(text: String): Int? {
        var depth = 0
        var quote: Char? = null
        for ((i, c) in text.withIndex()) {
            when {
                quote != null -> if (c == quote) quote = null
                c == '\'' || c == '"' -> quote = c
                c == '[' -> depth++
                c == ']' -> if (--depth == 0) return i
            }
        }
        return null
    }

    /** 谓词按不在引号与括号里的 ` and ` 拆开. */
    private fun splitAnd(predicate: String): List<String> {
        val terms = mutableListOf<String>()
        var depth = 0
        var quote: Char? = null
        var last = 0
        var i = 0
        while (i < predicate.length) {
            val c = predicate[i]
            when {
                quote != null -> if (c == quote) quote = null
                c == '\'' || c == '"' -> quote = c
                c == '(' -> depth++
                c == ')' -> depth--
                depth == 0 && predicate.startsWith(" and ", i) -> {
                    terms += predicate.substring(last, i).trim()
                    last = i + 5
                    i += 4
                }
            }
            i++
        }
        terms += predicate.substring(last).trim()
        return terms
    }

    private fun term(term: String, isWildcard: Boolean): String? {
        term.toIntOrNull()?.let { position ->
            if (position < 1) return null
            return if (isWildcard) ":nth-child($position)" else ":nth-of-type($position)"
        }
        ATTRIBUTE_EXISTS.matchEntire(term)?.let { return "[${it.groupValues[1]}]" }
        ATTRIBUTE_EQUALS.matchEntire(term)?.let { return "[${it.groupValues[1]}=${cssString(it.groupValues[2])}]" }
        ATTRIBUTE_FUNCTION.matchEntire(term)?.let { match ->
            val operator = if (match.groupValues[1] == "contains") "*=" else "^="
            return "[${match.groupValues[2]}$operator${cssString(match.groupValues[3])}]"
        }
        TEXT_CONTAINS.matchEntire(term)?.let { match ->
            val value = match.groupValues[2]
            // :contains 的参数不带引号, 里面的括号会打乱解析
            if (value.isEmpty() || '(' in value || ')' in value) return null
            return if (match.groupValues[1] == "text()") ":containsOwn($value)" else ":contains($value)"
        }
        return null
    }

    private fun cssString(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /** 去掉引号里的内容, 用来判断表达式本身有没有某个符号. */
    private fun String.withoutQuoted(): String = QUOTED.replace(this, "")

    private val NODE_TEST = Regex("""^(?:\*|[A-Za-z_][A-Za-z0-9_\-]*)""")
    private const val NAME = """[A-Za-z_][A-Za-z0-9_\-:]*"""
    private val QUOTED = Regex("""'[^']*'|"[^"]*"""")
    private val ATTRIBUTE_EXISTS = Regex("""@($NAME)""")
    private val ATTRIBUTE_EQUALS = Regex("""@($NAME)\s*=\s*['"]([^'"]*)['"]""")
    private val ATTRIBUTE_FUNCTION = Regex("""(contains|starts-with)\(\s*@($NAME)\s*,\s*['"]([^'"]*)['"]\s*\)""")
    private val TEXT_CONTAINS = Regex("""contains\(\s*(text\(\)|\.)\s*,\s*['"]([^'"]*)['"]\s*\)""")
}
