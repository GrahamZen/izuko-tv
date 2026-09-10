/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.datasources.api.util

/**
 * 按名字取正则分组 `(?<name>...)`, Android 8.0 以下也能用.
 *
 * Kotlin 的 `groups[name]` 在 JVM 上调 `java.util.regex.Matcher.start(String)`, 这个方法 Android 8.0 (API 26) 才有,
 * 更老的系统 (Android 7.1 兼容包) 上抛 `NoSuchMethodError` —— 是 Error 不是 Exception, 调用方的 catch 拦不住.
 * 那些系统的正则引擎认识 `(?<name>...)`, 匹配照常, 缺的只是按名字取, 所以在那里改按序号取.
 *
 * 系统支持时仍走 `groups[name]`. 与它一样: 分组没参与匹配返回 `null`, 正则里没有这个名字抛 [IllegalArgumentException].
 *
 * @param regex 产生本结果的正则 (从它的 pattern 里数出分组序号)
 */
fun MatchResult.namedGroup(regex: Regex, name: String): MatchGroup? {
    if (namedGroupApiAvailable) return groups[name]
    val index = namedGroupIndex(regex.pattern, name)
        ?: throw IllegalArgumentException("No group with name <$name>")
    return groups[index]
}

/** 探一次 `groups[name]` 能不能用; 不能用时抛的是 NoSuchMethodError, runCatching 连 Error 一起接住. */
private val namedGroupApiAvailable: Boolean by lazy {
    runCatching { Regex("(?<a>a)").find("a")?.groups?.get("a") }.isSuccess
}

/**
 * [name] 是 [pattern] 里第几个捕获组 (从 1 数); 没有返回 `null`.
 *
 * 按出现顺序数左括号: `(` 与 `(?<名字>` 是捕获组, `(?:` `(?=` `(?<=` `(?<!` 等不是;
 * 跳过转义字符、`\Q...\E` 引用段和字符类 `[...]` 里的括号.
 */
internal fun namedGroupIndex(pattern: String, name: String): Int? {
    var groupIndex = 0
    var classDepth = 0 // 字符类嵌套层数, 如 [a-z&&[^b]]
    var i = 0
    while (i < pattern.length) {
        val c = pattern[i]
        if (c == '\\') {
            if (pattern.getOrNull(i + 1) == 'Q') {
                val end = pattern.indexOf("\\E", startIndex = i + 2)
                i = if (end < 0) pattern.length else end + 2
            } else {
                i += 2
            }
            continue
        }
        if (classDepth > 0) {
            when (c) {
                '[' -> classDepth++
                ']' -> classDepth--
            }
        } else if (c == '[') {
            classDepth = 1
            // 紧跟在 '[' 或 '[^' 后的 ']' 是字面量
            if (pattern.getOrNull(i + 1) == '^') i++
            if (pattern.getOrNull(i + 1) == ']') i++
        } else if (c == '(') {
            if (pattern.getOrNull(i + 1) != '?') {
                groupIndex++
            } else if (pattern.getOrNull(i + 2) == '<' && pattern.getOrNull(i + 3)?.isLetter() == true) {
                groupIndex++
                val end = pattern.indexOf('>', startIndex = i + 3)
                if (end > 0 && pattern.substring(i + 3, end) == name) return groupIndex
            }
        }
        i++
    }
    return null
}
