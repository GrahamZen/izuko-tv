/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.models.subject

/**
 * Bangumi 简介**开头是日文、后面有中文**时 (官网日文原文在前, 中文翻译在后) 去掉开头的日文; 中文在前、只有日文时原样返回.
 * 给电视 hero 用: 那里只放得下几行, 日文在前时看不到中文.
 *
 * 按段 (空行分隔) 判: 有假名的段是日文 —— 日文段落不会一个假名都没有. 中文从第一段「没有假名、有两个以上中文虚词 (的、了、这…)」的段算起,
 * 它前面、最后一段日文后面的段 (中文的短标语、小标题) 一并留下; 没有虚词的段 (日文里没有假名的短句「友達？　恋人？」、
 * 「（以上摘自官网）」、人员表) 不当成中文的开头. 中文之后的内容 (常有日文原文附在最后) 原样留下.
 * 结果开头的「[中文简介]」这种小标题行与只有标点的行去掉.
 */
fun summaryWithoutLeadingJapanese(summary: String): String {
    val lines = summary.replace("\r\n", "\n").split('\n')
    // 每段的起始行号与内容; 只有标点、空白的段不算
    val paragraphs = buildList {
        var start = -1
        for (i in 0..lines.size) {
            val blank = i == lines.size || lines[i].isBlank()
            if (blank && start >= 0) {
                val text = lines.subList(start, i).joinToString("\n")
                if (text.any { it.isLetter() }) add(start to text)
                start = -1
            } else if (!blank && start < 0) {
                start = i
            }
        }
    }
    if (paragraphs.firstOrNull()?.second?.hasKana() != true) return summary
    val chinese = paragraphs.indexOfFirst { (_, text) -> text.looksChinese() }.takeIf { it >= 0 } ?: return summary
    val start = paragraphs.subList(0, chinese).indexOfLast { (_, text) -> text.hasKana() } + 1
    return lines.drop(paragraphs[start].first)
        .dropWhile { line -> line.none { it.isLetter() } || line.isTranslationHeading() }
        .joinToString("\n")
        .trimEnd()
}

/**
 * Bangumi 简介**只有日文**: 有假名, 又没有一段像中文 (判法同 [summaryWithoutLeadingJapanese]). 电视 hero 遇到它先等 TMDB 的中文简介.
 */
fun summaryIsJapaneseOnly(summary: String): Boolean =
    summary.hasKana() && summary.replace("\r\n", "\n").split(BLANK_LINE).none { it.looksChinese() }

private val BLANK_LINE = Regex("""\n\s*\n""")

private fun String.hasKana() = any { c ->
    val code = c.code
    // 中点 (・, U+30FB) 中文译名里也用, 不算假名
    code != 0x30FB && (code in 0x3041..0x309F || code in 0x30A0..0x30FF || code in 0x31F0..0x31FF || code in 0xFF66..0xFF9F)
}

/** 中文常用而日文里很少单独出现的字 (虚词、代词、简繁两种写法). */
private const val CHINESE_FUNCTION_CHARS = "的了是在他她们們这這个個说說着著吗嗎呢吧没沒还還让讓给給从從对對为為与與被把也都就而却卻"

private fun String.looksChinese() = !hasKana() && count { it in CHINESE_FUNCTION_CHARS } >= 2

/** 「[中文简介]」「【中文翻译】」「简介：」这种标在中文段前面的小标题. */
private fun String.isTranslationHeading(): Boolean {
    val text = trim()
    return text.length <= 12 && TRANSLATION_HEADING_WORDS.any { it in text }
}

private val TRANSLATION_HEADING_WORDS = listOf("简介", "簡介", "中文", "翻译", "翻譯", "译文", "譯文")
