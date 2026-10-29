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
 *   年份对不上的 (「凉宫春日的忧郁2009」) 是另一版, 仍是模糊;
 * - 名字前面还多出一个别的叫法、后面跟着首播年份的也算 (「命运之夜 Fate/stay night 2006」: 站点把译名写在前面,
 *   Bangumi 上没有这个译名), 见 [prefixedNameWithYear];
 * - 本条目两个名字连写的也算 (「怪物 MONSTER」「替身Another」: 站点把中文名与原名写在一起), 前后不能再多出别的字.
 *
 * 除倒数第二种外, 去掉这些之后仍要与某个名字整个相等 (忽略空白、标点与大小写, 同 [MediaListFilters.normalizeForCompare];
 * 另把 [MediaListFilters] 不认的几种横线、波浪线与装饰符号当作标点, 大写数字「壹贰叁肆」当作「一二三四」, 见 [unify]).
 */
internal class ExactSubjectNames(
    names: Collection<String>,
    private val simplify: (String) -> String = { it.toSimplifiedChinese() },
    /** 本条目首播的年份, 不知道时为 null */
    private val airYear: Int? = null,
) {
    /** 本条目各名字比较用的形式 */
    private val baseKeys: Set<String> = names.mapTo(HashSet()) { key(it) }.apply { remove("") }

    private val keys: Set<String> = baseKeys.let { base ->
        val withYear = if (airYear == null) base else base + base.flatMap { name -> YEAR_SUFFIXES.map { name + airYear + it } }
        // 两个不同的名字连写, 先后都算
        withYear + base.flatMap { first -> base.filter { it != first }.map { first + it } }
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
            } || prefixedNameWithYear(part.trim())
        }
    }

    /**
     * [part] 是「别的叫法 + 本条目某个名字 + 首播年份」. 年份是凭据, 必须对得上; 名字与前面的叫法之间要有空白,
     * 名字至少 [MIN_PREFIXED_NAME] 个字, 免得短名 (「K」) 被前面随便什么词带上认错.
     */
    private fun prefixedNameWithYear(part: String): Boolean {
        val year = airYear ?: return false
        val match = TRAILING_YEAR.matchEntire(part) ?: return false
        if (match.groupValues[2].toInt() != year) return false
        val tokens = match.groupValues[1].trim().split(WHITESPACE)
        return (1 until tokens.size).any { start ->
            val name = key(tokens.subList(start, tokens.size).joinToString(" "))
            name.length >= MIN_PREFIXED_NAME && name in baseKeys
        }
    }

    /** [part] 本身, 以及依次去掉开头一个本条目名字里有的英文词后剩下的 */
    private fun withoutLeadingKnownWords(part: String): Sequence<String> = generateSequence(part) { rest ->
        val first = rest.substringBefore(' ', missingDelimiterValue = "")
        if (first.isNotEmpty() && first.lowercase() in words) rest.substringAfter(' ').trimStart() else null
    }

    private fun key(name: String) = MediaListFilters.normalizeForCompare(unify(simplify(name))).lowercase()

    /** 比较前统一几种同义的写法: [EXTRA_PUNCTUATION] 换成空格, [UPPERCASE_NUMERALS] 换成对应的小写数字 */
    private fun unify(name: String): String = name.replace(EXTRA_PUNCTUATION, " ")
        .map { UPPERCASE_NUMERALS[it] ?: it }.joinToString("")

    private companion object {
        /**
         * [MediaListFilters.normalizeForCompare] 不当标点的符号: 全角与长短不一的横线、波浪线「〜」(U+301C, 与「～」不是同一个字)、
         * 名字里的装饰符号「△」「※」. 站点抄条目名时常换成别的或去掉 (「浪客剑心 －明治剑客浪漫谭－」写作「浪客剑心-明治剑客浪漫谭-」,
         * 「摇曳露营△」写作「摇曳露营」)
         */
        val EXTRA_PUNCTUATION = Regex("""[－‐–―〜△※]""")

        /** 条目名照日文原名用的大写数字 (「炎炎消防队 叁之章」, 原名「参ノ章」), 站点多写作「三之章」 */
        val UPPERCASE_NUMERALS = mapOf('壹' to '一', '贰' to '二', '叁' to '三', '肆' to '四')

        val WHITESPACE = Regex("""\s+""")
        val ASCII_WORD = Regex("""[A-Za-z0-9]*[A-Za-z][A-Za-z0-9]*""")
        val NAME_SEPARATOR = Regex("""\s+/\s+|／""")

        /** 年份后面常跟的字 (「2006年版」); 括号与空白比较前就去掉了 */
        val YEAR_SUFFIXES = listOf("", "年", "版", "年版")

        /** 末尾的年份 (可带括号与「年」「版」), 前面是名字 */
        val TRAILING_YEAR = Regex("""(.+?)[\s(（\[【]*(\d{4})\s*(?:年版|年|版)?[)）\]】]*\s*""")

        const val MIN_PREFIXED_NAME = 4
    }
}
