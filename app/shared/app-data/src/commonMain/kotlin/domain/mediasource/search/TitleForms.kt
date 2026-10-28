/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.search

import me.him188.ani.app.domain.mediasource.toSimplifiedChinese

/**
 * 名字在站点上的几种写法. 搜索计划给按标题原样找的站点排关键词 ([SubjectSearchPlan.keywords] 的 [KeywordStyle.literalTitles]),
 * 网盘认名字 (分享标题、文件夹、文件名) 也用同一套.
 */
internal object TitleForms {
    /**
     * 用于比较的写法: 转简体、转小写, 只留字母数字与汉字假名.
     * `Smoking.Behind.the.Supermarket` 与 `Smoking Behind the Supermarket` 得到同一个结果.
     */
    fun compact(name: String): String = name.toSimplifiedChinese().lowercase().filter { it.isLetterOrDigit() }

    /**
     * 比较写法 [compact] 够长, 拿去搜或拿来认名字不会碰上别的词: 至少 3 个字, 只有英文字母数字的至少 5 个 (「Air」是很多词的一部分,
     * 「夜曲」也是别的番的名字).
     */
    fun isDistinctive(compact: String): Boolean =
        compact.length >= if (compact.all { it in 'a'..'z' || it in '0'..'9' }) MIN_LATIN_DISTINCTIVE_LENGTH else MIN_DISTINCTIVE_LENGTH

    private const val MIN_DISTINCTIVE_LENGTH = 3
    private const val MIN_LATIN_DISTINCTIVE_LENGTH = 5

    /**
     * 去掉季标记与副标题后的主标题. 例如 `无职转生 第三季 ～到了异世界就拿出真本事～` 得到 `无职转生`.
     */
    fun mainTitle(name: String): String {
        var title = name
        for (regex in SEASON_MARKERS) {
            val match = regex.find(title) ?: continue
            val before = title.substring(0, match.range.first).trim()
            title = if (before.length >= 2) before else title.removeRange(match.range)
        }
        // 副标题前面那段太短时 (`Re:ゼロから始める…` 的 `Re`) 不能单独当标题
        val first = SUBTITLE_SEPARATOR.split(title).first()
        val main = if (compact(first).length >= 3) first else title
        return main.trim().trim(*TRIM_CHARS)
    }

    /**
     * 装饰用的符号换成空格, 字一个不删: 全角与长破折号 (`浪客剑心 －明治剑客浪漫谭－ 京都动乱`)、紧挨着中日文的半角连字符
     * (`死神 千年血战篇-祸进谭`)、`【】『』「」`. 站点按标题原样找时标题里很少照抄这些符号.
     * 英文里的 `:` `/` `-` 不动 (`Re:Zero`、`Fate/strange Fake`、`Kaguya-sama`): 标题里常原样写着.
     */
    fun withoutDecorations(name: String): String = DECORATION.replace(name, " ").replace(SPACES, " ").trim()

    /** 去掉两边都是中日文字的空格 (「继 怪怪守护神」→「继怪怪守护神」); 英文与中日文之间的空格留着. */
    fun withoutSpacesBetweenCjk(name: String): String = buildString {
        var i = 0
        while (i < name.length) {
            val c = name[i]
            if (c.isWhitespace()) {
                var end = i
                while (end < name.length && name[end].isWhitespace()) end++
                val before = name.getOrNull(i - 1)
                val after = name.getOrNull(end)
                if (before == null || after == null || !before.isCjk() || !after.isCjk()) append(name, i, end)
                i = end
            } else {
                append(c)
                i++
            }
        }
    }

    /** 含有中日文字 (汉字、假名) */
    fun containsCjk(text: String): Boolean = text.any { it.isCjk() }

    private fun Char.isCjk(): Boolean =
        this in '぀'..'ヿ' || this in '㐀'..'䶿' || this in '一'..'鿿' || this in '豈'..'﫿'

    // `第2部分` 是同一季的下半, 不是季
    val CHINESE_SEASON = Regex("""第\s*([0-9]{1,2}|[一二两三四五六七八九十]{1,3})\s*[季期部](?!分)""")
    val ENGLISH_SEASON = Regex("""(?i)(?<![a-z])season[ ._-]*(\d{1,2})(?![0-9])""")
    val ORDINAL_SEASON = Regex("""(?i)(?<![0-9])(\d{1,2})(?:st|nd|rd|th)[ ._-]*season""")
    val ROMAN_SEASON = Regex("""(?<![A-Za-z])(Ⅱ|Ⅲ|Ⅳ|Ⅴ|Ⅵ|III|II)(?![A-Za-z])""")

    private val SEASON_MARKERS = listOf(CHINESE_SEASON, ENGLISH_SEASON, ORDINAL_SEASON, ROMAN_SEASON)
    private val SUBTITLE_SEPARATOR = Regex("""\s*[～~:：｜|]\s*|\s+[-–—]\s+""")
    private val TRIM_CHARS = charArrayOf(' ', '-', '_', '.', '·', '!', '！', '?', '？')
    private val DECORATION = Regex("""[－—–]|(?<=[一-鿿ぁ-ヿ\s])-|-(?=[一-鿿ぁ-ヿ])|[【】『』「」]""")
    private val SPACES = Regex("""\s+""")
}
