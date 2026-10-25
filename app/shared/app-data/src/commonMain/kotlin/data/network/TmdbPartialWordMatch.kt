/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.network

/**
 * 判「半截词命中」时条目这一侧的信息, 见 [isTmdbPartialWordHit].
 *
 * @param names 条目自己的各个名字: 原名、中文名、别名.
 * @param date 条目日期 (`YYYY-MM-DD`): 背景图链是最新已播集的日期, 剧照链是开播日; 单集条目两者相同.
 */
internal class TmdbOwnNames(names: Collection<String>, val date: String?) {
    internal val forms: List<String> = names.map(::partialWordForm).filter { it.isNotEmpty() }.distinct()

    companion object {
        fun of(originalName: String, hints: TmdbMatchHints, date: String?) =
            TmdbOwnNames(listOf(originalName, hints.nameCn) + hints.aliases, date)
    }
}

/**
 * 搜索结果是不是**半截词命中**: 查询词在 TMDB 标题里只是某个更长的词的一截, 两边是不同的作品.
 *
 * 标题校验只要求查询的每个分词作为子串出现在标题里, 名字短的条目就会撞上以它开头或结尾的别的作品:
 * `ミニドラ` (小林さんちのメイドラゴンS 的小剧场) 命中「ミニドラマ　ざんねんないきもの事典」,
 * `ガイ` 命中「強殖装甲ガイバー」, `Leg` 命中「The Legend of Korra」, `なりヒロwww` 削出的 `なりヒロ`
 * 命中一部成人片. 这类结果不采纳, 交给后面的档: 正主在 TMDB 上常有同名条目, 只是没有背景图
 * (`ガイ-GUY-`、`ゴールFH`, 由海报兜底接住) 或者是 movie (`アニマ`), 番外由关联回溯接住 (`ミニドラ`).
 *
 * 粘连只看同一类文字: 片假名、平假名、拉丁字母、数字各算一类. 汉字不算 —— 汉字词之间本来就不写分隔.
 *
 * 对应表 2.4 万条自动命中里, 下面几种写法差异都是对的, 不算半截词:
 *  - 查询串去掉空格与标点后在标题里成整词: TMDB 少写了空格 (`エレメンタル ジェレイド` / `エレメンタルジェレイド`);
 *  - 条目自己的某个名字在标题里成整词, 或标题在条目名字里成整词: 削字后的短查询 (`エレメンタル`),
 *    母作标题 (`爆転シュート ベイブレード 2002` 对 `爆転シュートベイブレード`);
 *  - 有一个分词在标题里独立成词 (`NINKU -忍空-` 对 `Ninkuu-忍空-`);
 *  - 结果与条目的日期相差不超过 1 天 (`ぐだぐだオーダー` 对 `年忘れぐだぐだオーダー2016`).
 *
 * @param titles 结果的原名、名字与已取到的别名.
 * @param resultDate 结果的首播日 (tv) 或上映日 (movie).
 */
internal fun isTmdbPartialWordHit(
    query: String,
    titles: List<String>,
    own: TmdbOwnNames,
    resultDate: String?,
): Boolean {
    if (own.date != null && resultDate != null && resultDate.daysFrom(own.date)?.let { it <= 1 } == true) return false
    val titleForms = titles.map(::partialWordForm).filter { it.isNotEmpty() }
    val queryForm = partialWordForm(query)
    if (titleForms.any { it.containsWholeWord(queryForm) }) return false
    if (own.forms.any { name -> titleForms.any { it.containsWholeWord(name) || name.containsWholeWord(it) } }) return false

    val tokens = foldCompatibility(query).lowercase().split(Regex("""[^\p{L}\p{N}]+""")).filter { it.isNotBlank() }
    if (tokens.isEmpty()) return false
    val foldedTitles = titles.map { foldCompatibility(it).lowercase() }
    // 分词只在去掉分隔后才连得上的 (标题原文里找不到) 不判, 当它是整词
    return tokens.all { token ->
        foldedTitles.any { token in it } && foldedTitles.none { it.containsWholeWord(token) }
    }
}

/**
 * 比整词用的形式: 兼容折叠、小写, 只留字母与数字. 另有两处写法统一:
 * `×` 记作 `x` (`OVAL×OVER` / `OVALxOVER`); 词首的长音符是当破折号用的, 去掉 (`十兵衛ちゃん ーラブリー眼帯の秘密`).
 */
private fun partialWordForm(s: String): String {
    val folded = foldCompatibility(s).lowercase()
    return buildString(folded.length) {
        for ((i, ch) in folded.withIndex()) {
            when {
                ch == '×' -> append('x')
                ch == 'ー' && (i == 0 || !folded[i - 1].isLetterOrDigit()) -> Unit
                ch.isLetterOrDigit() -> append(ch)
            }
        }
    }
}

/** [word] 在这里至少有一处出现, 前后都不紧贴同类文字. */
private fun String.containsWholeWord(word: String): Boolean {
    if (word.isEmpty()) return false
    var at = indexOf(word)
    while (at >= 0) {
        val end = at + word.length
        val gluedBefore = at > 0 && this[at - 1].sameWordClass(word.first())
        val gluedAfter = end < length && this[end].sameWordClass(word.last())
        if (!gluedBefore && !gluedAfter) return true
        at = indexOf(word, at + 1)
    }
    return false
}

private fun Char.sameWordClass(other: Char): Boolean {
    val c = wordClass()
    return c != WORD_CLASS_NONE && c == other.wordClass()
}

private fun Char.wordClass(): Int = when {
    this in 'ァ'..'ヺ' || this == 'ー' -> WORD_CLASS_KATAKANA
    this in 'ぁ'..'ゟ' -> WORD_CLASS_HIRAGANA
    isDigit() -> WORD_CLASS_DIGIT
    isLetter() && code < 0x250 -> WORD_CLASS_LATIN
    else -> WORD_CLASS_NONE
}

private const val WORD_CLASS_NONE = 0
private const val WORD_CLASS_KATAKANA = 1
private const val WORD_CLASS_HIRAGANA = 2
private const val WORD_CLASS_LATIN = 3
private const val WORD_CLASS_DIGIT = 4
