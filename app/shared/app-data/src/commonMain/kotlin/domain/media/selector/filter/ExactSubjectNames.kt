/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.selector.filter

import me.him188.ani.app.domain.mediasource.MediaListFilters
import me.him188.ani.app.domain.mediasource.toSimplifiedChinese

/**
 * 资源的条目名是不是本条目的某个名字 ([names]), 比逐字相等多认几种写法. 只用来定资源是不是「精确匹配」(选源面板上
 * 「条目名不完全一致」的提示、自动选源时精确的优先), 不参与排除.
 *
 * - 发布标题常把几种语言的名字用「/」并列 (「Bleach Sennen Kessenhen / BLEACH 死神 千年血戰篇-禍進譚-」), 分开各自比;
 * - 繁体转成简体再比;
 * - 开头多出的英文词出现在本条目别的名字里时去掉再比 (「BLEACH 死神 千年血战篇」对中文名「死神 千年血战篇」,
 *   「BLEACH」出自日文名);
 * - 季号的写法: 条目名写了季号的 (Bangumi 的「赛博朋克：边缘行者 2」), 页名写同一季、去掉季号后系列名相同的也算
 *   (「赛博朋克：边缘行者 第二季」, 见 [SeasonNumberConflict.subjectSeasonOf] / [SeasonNumberConflict.pageSeasonOf]);
 * - 名字后面跟着首播年份 ([airYear]) 的也算 (站点用年份分开重制版与旧版: 「凉宫春日的忧郁2006」「凉宫春日的忧郁 (2006)」),
 *   年份对不上的 (「凉宫春日的忧郁2009」) 是另一版, 仍是模糊.
 *
 * 去掉这些之后仍要与某个名字整个相等 (忽略空白、标点与大小写, 同 [MediaListFilters.normalizeForCompare]).
 */
internal class ExactSubjectNames(
    names: Collection<String>,
    private val simplify: (String) -> String = { it.toSimplifiedChinese() },
    /** 本条目首播的年份, 不知道时为 null */
    airYear: Int? = null,
) {
    private val keys: Set<String> = names.mapTo(HashSet()) { key(it) }.apply { remove("") }.let { base ->
        if (airYear == null) base else base + base.flatMap { name -> YEAR_SUFFIXES.map { name + airYear + it } }
    }

    /** 本条目写了季号的名字: 去掉季号后的系列名与季号 */
    private val seasonKeys: Set<Pair<String, Int>> = names.mapNotNullTo(HashSet()) { SeasonNumberConflict.subjectSeasonOf(simplify(it)) }

    /** 本条目各名字里的英文词 (小写) */
    private val words: Set<String> = names.flatMapTo(HashSet()) { name ->
        name.split(WHITESPACE).filter { it.matches(ASCII_WORD) }.map { it.lowercase() }
    }

    fun matches(mediaSubjectName: String): Boolean {
        if (keys.isEmpty()) return false
        return mediaSubjectName.split(NAME_SEPARATOR).any { part ->
            withoutLeadingKnownWords(part.trim()).any { name ->
                key(name) in keys || seasonKeys.isNotEmpty() && SeasonNumberConflict.pageSeasonOf(simplify(name)) in seasonKeys
            }
        }
    }

    /** [part] 本身, 以及依次去掉开头一个本条目名字里有的英文词后剩下的 */
    private fun withoutLeadingKnownWords(part: String): Sequence<String> = generateSequence(part) { rest ->
        val first = rest.substringBefore(' ', missingDelimiterValue = "")
        if (first.isNotEmpty() && first.lowercase() in words) rest.substringAfter(' ').trimStart() else null
    }

    private fun key(name: String) = MediaListFilters.normalizeForCompare(simplify(name)).lowercase()

    private companion object {
        val WHITESPACE = Regex("""\s+""")
        val ASCII_WORD = Regex("""[A-Za-z0-9]*[A-Za-z][A-Za-z0-9]*""")
        val NAME_SEPARATOR = Regex("""\s+/\s+|／""")

        /** 年份后面常跟的字 (「2006年版」); 括号与空白比较前就去掉了 */
        val YEAR_SUFFIXES = listOf("", "年", "版", "年版")
    }
}
