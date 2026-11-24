/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.selector.filter

import me.him188.ani.app.domain.media.selector.MediaSelectorContext
import me.him188.ani.app.domain.mediasource.toSimplifiedChinese

/**
 * BT 发布标题写明的季号与当前条目冲突: 资源是本系列另一季的.
 *
 * BT 站没有条目名, 选择器对 BT 只认标题里完整出现的续作名 (Bangumi 上的「约会大作战 第五季」). 字幕组常写成
 * 「[约会大作战][Date A Live V][01]」「约会大作战5 / デート・ア・ライブ Ⅴ / Date A Live S05」, 第一季的名字又是各季名字的前缀,
 * 这些就被当成第一季的第 01 集 —— 自动选源按分辨率等偏好挑资源时尤其容易挑中 (老番少有 1080p 的单集).
 *
 * 认的季号见 [seasonNumbersOf]; 判断同站点页名 ([SeasonNumberConflict]): 条目名写了季号的照它, 系列第一部照第 1 季;
 * 标题同时写着本季 (跨季合集) 的不排除.
 */
internal object BtTitleSeason {
    fun isOtherSeason(title: String, context: MediaSelectorContext): Boolean {
        val conflict = SeasonNumberConflict.of(context) ?: SeasonNumberConflict.firstWorkOf(context) ?: return false
        return conflict.isOtherSeason(seasonNumbersOf(title, context.subjectInfo?.allNames.orEmpty()))
    }

    /**
     * 标题 [title] 写明的季号:
     * - 任何位置明写的「第五季」「第2期」「Season 5」「5th Season」「S05」「S05E01」;
     * - 紧跟本条目某个名字 ([names]) 的罗马数字 (Ⅱ~Ⅷ, 大写的 II~VIII; 中间可隔空格), 与紧贴中日文名的一位数 (「约会大作战5」).
     *   这两种只在名字后面认: 标题别处的「V」「X」多是别的意思 (「HUNTER X HUNTER」), 隔着空格的数字多是集号.
     *   同一处有更长的名字对得上时按更长的算 (条目名本身带数字的「女神异闻录5」不算第 5 季).
     */
    fun seasonNumbersOf(title: String, names: Collection<String>): Set<Int> {
        val numbers = HashSet<Int>()
        for (pattern in EXPLICIT) {
            pattern.findAll(title).forEach { match -> numberOf(match.groupValues[1])?.let { numbers += it } }
        }
        val text = title.toSimplifiedChinese().lowercase()
        val candidates = names.asSequence().map { it.trim() }.filter { it.length >= 2 }
            .map { it.toSimplifiedChinese() }.distinct().sortedByDescending { it.length }.toList()
        val taken = HashSet<Int>()
        for (name in candidates) {
            val needle = name.lowercase()
            var start = text.indexOf(needle)
            while (start >= 0) {
                if (start !in taken && !continuesLatinWord(name.first(), text.getOrNull(start - 1))) {
                    taken += start
                    numberAfter(title, start + needle.length, name.last())?.let { numbers += it }
                }
                start = text.indexOf(needle, start + 1)
            }
        }
        return numbers
    }

    /** 名字在 [end] 处结束, 后面紧跟的季号; 名字最后一个字是 [last] */
    private fun numberAfter(title: String, end: Int, last: Char): Int? {
        var i = end
        while (i < title.length && (title[i] == ' ' || title[i] == '　')) i++
        val spaced = i > end
        val c = title.getOrNull(i) ?: return null
        FULL_WIDTH_ROMAN[c]?.let { return it }
        if (spaced || !last.isAsciiLetter()) {
            ASCII_ROMAN.matchAt(title, i)?.let { return ASCII_ROMAN_VALUES[it.value] }
        }
        if (!spaced && last.isCjk() && c in '2'..'9' && title.getOrNull(i + 1)?.isDigit() != true) return c - '0'
        return null
    }

    /** 拉丁字母开头的名字前面紧挨着字母时, 是一个更长的词的一截 */
    private fun continuesLatinWord(first: Char, before: Char?): Boolean = first.isAsciiLetter() && before?.isAsciiLetter() == true

    private fun numberOf(raw: String): Int? =
        CHINESE_NUMBERS[raw] ?: raw.map { if (it in '０'..'９') '0' + (it - '０') else it }.joinToString("").toIntOrNull()

    private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'

    /** 汉字与假名: 这类名字后面紧贴的一位数是季号 */
    private fun Char.isCjk(): Boolean = this in '぀'..'ヿ' || this in '一'..'鿿'

    /** 明写季号的几种方式 (同 SplitSeason 认页名的写法, 罗马数字除外), 再加「S05E01」 */
    private val EXPLICIT = listOf(
        Regex("""第\s*([0-9０-９一二三四五六七八九十]+)\s*[季期]"""),
        Regex("""(?:season|シーズン)\s*([0-9]+)""", RegexOption.IGNORE_CASE),
        Regex("""([0-9]+)(?:st|nd|rd|th)\s*season""", RegexOption.IGNORE_CASE),
        Regex("""(?<![a-z0-9])s([0-9]+)(?![a-z0-9])""", RegexOption.IGNORE_CASE),
        Regex("""(?<![a-z0-9])s([0-9]{1,2})e[0-9]{1,3}(?![0-9])""", RegexOption.IGNORE_CASE),
    )

    private val CHINESE_NUMBERS = mapOf(
        "一" to 1, "二" to 2, "三" to 3, "四" to 4, "五" to 5, "六" to 6, "七" to 7, "八" to 8, "九" to 9, "十" to 10,
    )

    private val FULL_WIDTH_ROMAN = mapOf('Ⅱ' to 2, 'Ⅲ' to 3, 'Ⅳ' to 4, 'Ⅴ' to 5, 'Ⅵ' to 6, 'Ⅶ' to 7, 'Ⅷ' to 8)

    /** 大写的 II~VIII, 后面不能接着字母或数字 (「Vol」「V2」不算); 长的在前 */
    private val ASCII_ROMAN = Regex("""(?:VIII|VII|VI|IV|V|III|II)(?![A-Za-z0-9])""")

    private val ASCII_ROMAN_VALUES = mapOf("II" to 2, "III" to 3, "IV" to 4, "V" to 5, "VI" to 6, "VII" to 7, "VIII" to 8)
}
