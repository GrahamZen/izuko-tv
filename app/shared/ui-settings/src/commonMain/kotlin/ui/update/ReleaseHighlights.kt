/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.update

import androidx.compose.runtime.Immutable

/**
 * 一版更新说明里给「本版新功能」页看的部分 (见 `ci-helper/release-template.md`):
 * - 「## 本次更新」一节: 分隔线 `----` 以上是这一版的新功能 ([features]), 以下是小改动与修复 ([minor]);
 *   到下一个标题 (`### 已知问题`、`## 交流群`) 为止. 没有分隔线时整节都算新功能.
 * - 「## 通知」一节 ([notice]): 发版后发现问题时编辑 release 加上的话. 应用启动时取当前版本的说明, 有没看过的通知就单独显示它.
 */
@Immutable
class ReleaseHighlights(
    val version: String,
    val features: List<ReleaseNoteItem>,
    val minor: List<ReleaseNoteItem>,
    val notice: List<ReleaseNoteItem> = emptyList(),
)

/**
 * 说明里的一条. 「标题：说明」写法的拆成两段 ([detail] 是冒号后面那段), 页面上标题醒目、说明压暗;
 * 没有冒号 (或冒号前面太长, 不像标题) 的整条是 [title].
 */
@Immutable
data class ReleaseNoteItem(val title: String, val detail: String?)

/** note 里更新内容那一节的标题, 同 [Changelog] 的截取. */
private const val CHANGES_HEADING = "## 本次更新"

/** 发版后补的通知那一节的标题, 见 [ReleaseHighlights.notice]. */
private const val NOTICE_HEADING = "## 通知"

/** 冒号前面最多这么多字才当成标题拆开; 再长就是一句完整的话里碰巧带了冒号. */
private const val ITEM_TITLE_MAX_LENGTH = 30

/**
 * 从 release body (原文, 或 [Changelog.detailedChanges] 那份) 里取出 [ReleaseHighlights].
 * 新功能、小改动、通知一条都没有时返回 `null`.
 */
fun parseReleaseHighlights(version: String, body: String): ReleaseHighlights? {
    val lines = body.lines()
    val features = mutableListOf<ReleaseNoteItem>()
    val minor = mutableListOf<ReleaseNoteItem>()
    var belowRule = false
    for (line in lines.sectionAfter(CHANGES_HEADING)) {
        val trimmed = line.trim()
        if (trimmed.startsWith("#")) break
        if (trimmed.isThematicBreak()) {
            belowRule = true
            continue
        }
        val text = trimmed.listItemText() ?: continue
        (if (belowRule) minor else features) += releaseNoteItem(text)
    }
    val notice = lines.sectionAfter(NOTICE_HEADING)
        .map { it.trim() }
        .takeWhile { !it.startsWith("## ") && !it.startsWith("# ") }
        .filter { it.isNotEmpty() && !it.startsWith("#") && !it.isComment() && !it.isThematicBreak() }
        // 列表与一段一段的话都认: 写通知时不用记格式
        .map { releaseNoteItem(it.listItemText() ?: it) }
    if (features.isEmpty() && minor.isEmpty() && notice.isEmpty()) return null
    return ReleaseHighlights(version, features, minor, notice)
}

/** [heading] 那一行之后的所有行; 没有这个标题时为空. */
private fun List<String>.sectionAfter(heading: String): List<String> {
    val start = indexOfFirst { it.trim() == heading }
    return if (start < 0) emptyList() else subList(start + 1, size)
}

/** `[//]: # (…)` 与 `<!-- … -->`: 模板里写给发版人看的注释, 不显示. */
private fun String.isComment(): Boolean = startsWith("[//]:") || startsWith("<!--")

internal fun releaseNoteItem(markdown: String): ReleaseNoteItem {
    val text = markdown.stripInlineMarkdown()
    val colon = text.indexOfFirst { it == '：' || it == ':' }
    // 「Web 控制台: …」的英文冒号后面跟空格; 网址里的冒号 (https:) 不算
    val splits = colon in 2..ITEM_TITLE_MAX_LENGTH &&
            (text[colon] == '：' || text.getOrNull(colon + 1) == ' ')
    if (!splits) return ReleaseNoteItem(text, null)
    val detail = text.substring(colon + 1).trim()
    return ReleaseNoteItem(text.substring(0, colon).trim(), detail.ifEmpty { null })
}

/** `----` / `***` / `___` (三个及以上, 可夹空格): markdown 的分隔线. */
private fun String.isThematicBreak(): Boolean {
    val compact = filterNot { it == ' ' }
    return compact.length >= 3 && compact.all { it == compact[0] } && compact[0] in "-*_"
}

/** `- xxx` / `* xxx` / `+ xxx` 的 `xxx`; 不是列表项返回 `null`. */
private fun String.listItemText(): String? {
    if (length < 2 || this[0] !in "-*+" || this[1] != ' ') return null
    return substring(2).trim().ifEmpty { null }
}

private val MARKDOWN_LINK = Regex("""\[([^\]]+)]\([^)]*\)""")
private val MARKDOWN_EMPHASIS = Regex("""\*\*(.+?)\*\*|__(.+?)__""")
private val MARKDOWN_CODE = Regex("""`([^`]+)`""")

/** 页面上逐条画纯文字: 链接只留文字, 粗体与行内代码去掉记号. */
private fun String.stripInlineMarkdown(): String = this
    .replace(MARKDOWN_LINK) { it.groupValues[1] }
    .replace(MARKDOWN_EMPHASIS) { it.groupValues[1].ifEmpty { it.groupValues[2] } }
    .replace(MARKDOWN_CODE) { it.groupValues[1] }
    .trim()

/**
 * [candidate] 是否比 [current] 新. 规则同 [UpdateChecker] 的版本比较: 数字段逐段比, 数字相同时正式版比预发布版新,
 * 两个都是预发布版按后缀的字典序 (`alpha06` < `alpha10`).
 */
fun isNewerVersion(candidate: String, current: String): Boolean {
    if (candidate == current) return false
    val (cNums, cPre) = parseVersion(candidate)
    val (vNums, vPre) = parseVersion(current)
    for (i in 0 until maxOf(cNums.size, vNums.size)) {
        val c = cNums.getOrElse(i) { 0 }
        val v = vNums.getOrElse(i) { 0 }
        if (c != v) return c > v
    }
    if (cPre.isEmpty() && vPre.isNotEmpty()) return true
    if (cPre.isNotEmpty() && vPre.isEmpty()) return false
    return cPre > vPre
}

private fun parseVersion(version: String): Pair<List<Int>, String> {
    val numbers = version.substringBefore('-')
    val pre = if ('-' in version) version.substringAfter('-') else ""
    return numbers.split('.').map { it.toIntOrNull() ?: 0 } to pre
}

/** 版本号的数字部分 (`1.0.6-alpha06` → `1.0.6`). */
private fun baseVersion(version: String): String = version.substringBefore('-')

/**
 * [version] 这一版的「本版新功能」页列哪几版的说明, 新的在前:
 * - 正式版只列它自己: 正式版的 note 是相对上一个正式版的汇总;
 * - 测试版列同一个版本号从 alpha01 起到它为止的全部测试版 (`1.0.6-alpha06` → `1.0.6-alpha01` ~ `alpha06`):
 *   测试版的 note 只写比上一个测试版多了什么, 合起来才是这个版本号到目前为止的全貌.
 */
fun releasesToShow(available: Collection<String>, version: String): List<String> =
    available.filter { isShownWith(it, version) }.distinct().sortedWith(NEWEST_FIRST)

/** [candidate] 的说明是不是在 [version] 的页面上 (见 [releasesToShow]). */
internal fun isShownWith(candidate: String, version: String): Boolean =
    if ('-' !in version) {
        candidate == version
    } else {
        '-' in candidate && baseVersion(candidate) == baseVersion(version) && !isNewerVersion(candidate, version)
    }

private val NEWEST_FIRST = Comparator<String> { a, b -> if (isNewerVersion(a, b)) -1 else if (isNewerVersion(b, a)) 1 else 0 }
