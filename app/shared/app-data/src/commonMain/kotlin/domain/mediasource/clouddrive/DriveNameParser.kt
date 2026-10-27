/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.clouddrive

import me.him188.ani.app.domain.mediasource.search.TitleForms
import me.him188.ani.app.domain.mediasource.search.TitleOccurrences
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.topic.isSingleEpisode
import me.him188.ani.datasources.api.topic.titles.RawTitleParser
import me.him188.ani.datasources.api.topic.titles.parse

/**
 * 从网盘里的文件名与文件夹名推断季与集.
 *
 * 网盘文件的命名没有规范, 常见的几种:
 * - 剧集发布: `Title.S01E03.1080p.WEB-DL.mkv`, 季文件夹 `Title.S01` / `Season 2`
 * - 字幕组发布: `[LoliHouse] Title - 03 [WebRip 1080p].mkv`, `[字幕组][Title][03][1080P].mp4`
 * - 手动整理: `第03集.mp4`, `EP03.mp4`, 季文件夹里只放 `03.mp4`
 */
internal object DriveNameParser {
    class ParsedFile(
        /**
         * 文件名里明确写出的季, 如 `S02E03` 的 2. 没写为 null.
         */
        val season: Int?,
        /**
         * 集号. 认不出为 null.
         */
        val episode: EpisodeSort?,
        /**
         * 片头片尾、PV、预告之类的附加视频, 不算正片.
         */
        val isExtra: Boolean,
        /**
         * 集号写在集号的位置上 (`S01E03`、`第3集`、`EP03`、`[03]`、` - 03`); 只是名字里剩下的一个数字时为 false.
         */
        val anchored: Boolean = true,
    )

    /**
     * 去掉条目名里带数字的那几个 ([titlePatterns]) 后认季与集: 「秒速5厘米.2007.BD1080P」的 5 是名字的一部分, 不是集号.
     */
    fun parseFile(fileName: String, titles: List<Regex>): ParsedFile =
        parseFile(titles.fold(fileName) { name, title -> title.replace(name, " ") })

    /** 名字 [names] 里带数字的 (「秒速5厘米」「5 Centimeters per Second」「机动战士高达0079」) 原样出现的样子, 两头不挨着别的数字 */
    fun titlePatterns(names: List<String>): List<Regex> = names
        .filter { name -> name.any(Char::isDigit) && name.any(Char::isLetter) }
        .distinct()
        .mapNotNull { name -> TitleOccurrences.patternOf(name)?.let { Regex("""(?<![0-9])(?:${it.pattern})(?![0-9])""", it.options) } }

    fun parseFile(fileName: String): ParsedFile {
        // 编码、声道里的数字不是集号 (`H.265` `×265` `DDP5.1`)
        val name = CODEC.replace(removeExtension(fileName), " ")
        // 同名文件的副本后缀 (`02(1).mp4` 是第 2 集): 去掉后还认得出集号就按去掉的算, 否则括号里的数字可能就是集号 (`Title (03)`)
        COPY_SUFFIX.find(name)?.let { suffix ->
            parseName(name.removeRange(suffix.range)).takeIf { it.episode != null }?.let { return it }
        }
        return parseName(name)
    }

    private fun parseName(name: String): ParsedFile {
        if (EXTRA.containsMatchIn(name)) return ParsedFile(null, null, isExtra = true)

        SEASON_EPISODE.find(name)?.let { match ->
            return ParsedFile(
                season = match.groupValues[1].toInt(),
                episode = EpisodeSort(match.groupValues[2].toInt()),
                isExtra = false,
            )
        }
        CHINESE_EPISODE.find(name)?.let { match ->
            parseNumber(match.groupValues[1])?.let { return ParsedFile(null, EpisodeSort(it), false) }
        }
        EP_EPISODE.find(name)?.let { match ->
            return ParsedFile(null, episodeSort(match.groupValues[1], match.groupValues[2]), false)
        }
        titleParserEpisode(name)?.let { return ParsedFile(null, it, false) }
        bareNumberEpisode(name)?.let { return ParsedFile(null, it, false, anchored = false) }
        bracketLeadingNumberEpisode(name)?.let { return ParsedFile(null, it, false, anchored = false) }
        return ParsedFile(null, null, false)
    }

    /**
     * 文件夹名里写的季: `S02`, `Season 2`, `第二季`, `2nd Season`, 罗马数字 (`XX IV`, `XX Ⅱ`, 见 [folderRomanSeasons]),
     * 以及紧跟在中文标题后的单个数字 (`葬送的芙莉莲2`).
     */
    fun parseFolderSeason(folderName: String): Int? {
        parseExplicitSeason(folderName, roman = false)?.let { return it }
        folderRomanSeasons(folderName).singleOrNull()?.let { return it }
        FOLDER_SEASON_CODE.find(folderName)?.let { return it.groupValues[1].toInt() }
        TRAILING_DIGIT_AFTER_CJK.find(folderName.trim())?.let { return it.groupValues[1].toInt() }
        return null
    }

    /**
     * 文件夹名或分享标题明确写了、且只写了一个季时返回它 (`S02`, `Season 2`, `第二季`, `2nd Season`, `XX IV`).
     * 写了几个季、季号旁边挨着别的数字 (`Season 1 & 2`, `第三季 01-12`)、只写「第一部」或标题末尾带数字时为 null:
     * 用来跳过明显是别的季的文件夹, 拿不准就当没写.
     */
    fun singleDeclaredSeason(name: String): Int? {
        val matches = SINGLE_SEASON_MARKERS.flatMap { it.findAll(name).toList() }
        val seasons = matches.mapNotNull { parseNumber(it.groupValues[1]) }.toSet() + folderRomanSeasons(name)
        val season = seasons.singleOrNull() ?: return null
        val rest = matches.sortedByDescending { it.range.first }.fold(name) { acc, match -> acc.replaceRange(match.range, " ") }
        return season.takeUnless { JOINED_NUMBER.containsMatchIn(rest) }
    }

    /**
     * 名字里提到的所有季 (`第三季`, `Season 3`, `3rd Season`, `Ⅲ`, `S03`, `S03E01`), 写了几个季时都算 (`S1-S3` 得到 1 与 3).
     * 用来给站点上的分享、BT 发布按季排先后与取舍; 不认标题末尾的数字.
     */
    fun declaredSeasons(name: String): Set<Int> = buildSet {
        for (match in TitleForms.CHINESE_SEASON.findAll(name)) parseNumber(match.groupValues[1])?.let(::add)
        for (match in SEASON_WORD.findAll(name)) add(match.groupValues[1].toInt())
        for (match in TitleForms.ORDINAL_SEASON.findAll(name)) add(match.groupValues[1].toInt())
        for (match in TitleForms.ROMAN_SEASON.findAll(name)) add(ROMAN_NUMERALS.getValue(match.groupValues[1]))
        for (match in SEASON_CODE.findAll(name)) add(match.groupValues[1].toInt())
    }

    /**
     * 条目名里写的季. 只认明确的写法, 不认标题末尾的数字 (条目名里的数字常常就是标题的一部分).
     */
    fun parseSubjectSeason(subjectName: String): Int? = parseExplicitSeason(subjectName)

    private fun parseExplicitSeason(name: String, roman: Boolean = true): Int? {
        TitleForms.CHINESE_SEASON.find(name)?.let { match -> parseNumber(match.groupValues[1])?.let { return it } }
        TitleForms.ENGLISH_SEASON.find(name)?.let { return it.groupValues[1].toInt() }
        TitleForms.ORDINAL_SEASON.find(name)?.let { return it.groupValues[1].toInt() }
        if (roman) TitleForms.ROMAN_SEASON.find(name)?.let { return ROMAN_NUMERALS.getValue(it.groupValues[1]) }
        return null
    }

    /**
     * 文件夹名里用罗马数字写的季. 按季分的文件夹常写成「XX I」「XX II」…「XX V」: 认罗马数字字符 (`Ⅱ`)、ASCII 的 `II` 到 `VI`,
     * 以及名字末尾单独的 `I`. 前面是 Part / Vol / Disc 之类的不算 (`Part II` 是一季的下半).
     * 条目名不用这套: ASCII 的 `V` 在番名里可能就是标题的一部分.
     */
    private fun folderRomanSeasons(name: String): Set<Int> = FOLDER_ROMAN_SEASON.findAll(name)
        .filterNot { NOT_SEASON_BEFORE_ROMAN.containsMatchIn(name.substring(0, it.range.first)) }
        .mapTo(HashSet()) { FOLDER_ROMAN_NUMERALS.getValue(it.value.trim()) }

    private fun titleParserEpisode(name: String): EpisodeSort? {
        val range = RawTitleParser.getDefault().parse(name).episodeRange ?: return null
        if (!range.isSingleEpisode()) return null
        return range.knownSorts.singleOrNull()?.takeIf { it is EpisodeSort.Normal }
    }

    /**
     * 去掉方括号/圆括号里的标签后, 名字里只剩一个独立数字时把它当集号 (`03`, `Title 03`).
     */
    private fun bareNumberEpisode(name: String): EpisodeSort? {
        val cleaned = BRACKETED.replace(name, " ")
        val numbers = STANDALONE_NUMBER.findAll(cleaned).map { it.groupValues[1] }.toList()
        val candidate = numbers.lastOrNull() ?: return null
        if (numbers.size > 1 && candidate.length < 2) return null
        return EpisodeSort(candidate.toInt())
    }

    /**
     * 集号和标题挤在同一个括号开头 (`[001冒険の終わり][字幕组][2160P].mkv`, `【03 冒险结束】.mp4`): 整个名字都在括号里时
     * [bareNumberEpisode] 什么也剩不下. 只认恰好一个这样的括号.
     */
    private fun bracketLeadingNumberEpisode(name: String): EpisodeSort? =
        BRACKET_LEADING_NUMBER.findAll(name).map { it.groupValues[1] }.toList().singleOrNull()?.let { EpisodeSort(it.toInt()) }

    private fun episodeSort(integer: String, decimal: String): EpisodeSort =
        if (decimal.isEmpty()) EpisodeSort(integer.toInt()) else EpisodeSort("${integer.toInt()}.$decimal")

    private fun removeExtension(fileName: String): String {
        val dot = fileName.lastIndexOf('.')
        if (dot <= 0 || fileName.length - dot > 6) return fileName
        return fileName.substring(0, dot)
    }

    /**
     * 阿拉伯数字或中文数字 (一到九十九).
     */
    fun parseNumber(text: String): Int? {
        text.toIntOrNull()?.let { return it }
        if (text.isEmpty() || text.any { it !in CHINESE_DIGITS && it != '十' }) return null
        val tenIndex = text.indexOf('十')
        if (tenIndex < 0) return text.singleOrNull()?.let { CHINESE_DIGITS.getValue(it) }
        val tens = if (tenIndex == 0) 1 else CHINESE_DIGITS[text[tenIndex - 1]] ?: return null
        val ones = if (tenIndex == text.lastIndex) 0 else CHINESE_DIGITS[text[tenIndex + 1]] ?: return null
        return tens * 10 + ones
    }

    private val CHINESE_DIGITS = mapOf(
        '零' to 0, '〇' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
        '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9,
    )

    private val ROMAN_NUMERALS = mapOf("Ⅱ" to 2, "Ⅲ" to 3, "Ⅳ" to 4, "Ⅴ" to 5, "Ⅵ" to 6, "II" to 2, "III" to 3)

    /** 标准命名 `S06E01`: 后面跟的是画质、集名 (`.4K`、`.1080p`、`.Good.Loser`), 不认小数 (特别篇写 `S00Exx`). */
    private val SEASON_EPISODE =
        Regex("""(?<![A-Za-z0-9])[Ss](\d{1,2})[ ._-]?[Ee][Pp]?(\d{1,4})(?![0-9])""")
    private val CHINESE_EPISODE = Regex("""第\s*([0-9]{1,4}|[零〇一二两三四五六七八九十]{1,4})\s*[集话話回]""")

    /** `EP12.5` 是半集; 小数后面紧跟字母或数字的不是 (`EP01.4K` 是第 1 集). */
    private val EP_EPISODE = Regex("""(?<![A-Za-z])(?:EP|Ep|ep|E)[ ._-]?(\d{1,4})(?:\.(\d)(?![0-9A-Za-z]))?(?![0-9])""")

    private val EXTRA = Regex(
        """(?<![A-Za-z])(?:NCOP|NCED|OP|ED|PV|CM|MENU|Menu|PREVIEW|Preview|TRAILER|Trailer|Teaser|TEASER|SP|OVA|OAD)[ ._-]?\d{0,2}(?![A-Za-z0-9])""" +
                """|预告|花絮|片头曲?|片尾曲?|特典映像|映像特典""",
    )


    private val FOLDER_ROMAN_NUMERALS = mapOf(
        "Ⅰ" to 1, "Ⅱ" to 2, "Ⅲ" to 3, "Ⅳ" to 4, "Ⅴ" to 5, "Ⅵ" to 6,
        "I" to 1, "II" to 2, "III" to 3, "IV" to 4, "V" to 5, "VI" to 6,
    )
    private val FOLDER_ROMAN_SEASON = Regex("""(?<![A-Za-z0-9])(?:Ⅰ|Ⅱ|Ⅲ|Ⅳ|Ⅴ|Ⅵ|III|II|IV|VI|V)(?![A-Za-z0-9])|(?<=\s)I\s*$""")
    private val NOT_SEASON_BEFORE_ROMAN = Regex("""(?:part|vol|volume|cour|disc|disk|cd|chapter)[ ._-]*$""", RegexOption.IGNORE_CASE)

    private val FOLDER_SEASON_CODE = Regex("""(?<![A-Za-z0-9])[Ss](\d{1,2})(?![0-9Ee])""")

    /** 标题里的 `Season 3`. 与 [TitleForms.ENGLISH_SEASON] 不同, 不跨过 ` - `: 发布标题里 `2nd Season - 01` 的 01 是集号. */
    private val SEASON_WORD = Regex("""(?i)(?<![a-z])season[ ._]?(\d{1,2})(?![0-9])""")

    /** 标题里的 `S03` 或 `S03E01` 的季. */
    private val SEASON_CODE = Regex("""(?<![A-Za-z0-9])[Ss](\d{1,2})(?=[Ee]\d|[^0-9A-Za-z]|$)""")

    private val SINGLE_SEASON_MARKERS = listOf(
        Regex("""第\s*([0-9]{1,2}|[一二两三四五六七八九十]{1,3})\s*[季期](?!分)"""),
        TitleForms.ENGLISH_SEASON,
        TitleForms.ORDINAL_SEASON,
        FOLDER_SEASON_CODE,
    )

    /** 数字挨着范围或并列的符号: 名字里除了一个季号还写了别的季或集的范围. */
    private val JOINED_NUMBER = Regex("""\d\s*[-~～至到&+、,，和及]|[-~～至到&+、,，和及]\s*\d""")

    private val TRAILING_DIGIT_AFTER_CJK = Regex("""[一-鿿]\s*([2-9])$""")


    /** 视频编码、音频编码与声道 */
    private val CODEC = Regex(
        """(?<![0-9])(?:[HhXx]\.?|×)26[45](?![0-9])""" +
                """|(?<![A-Za-z0-9])(?:AV1|VP9|(?:DDP?|EAC3|AC3|AAC|DTS(?:-HD)?(?:[ .]?MA)?|TrueHD|FLAC|Atmos)[ .]?[2-7]\.[01](?:ch)?|[2-7]\.[01][ .]?ch)(?![0-9])""",
        RegexOption.IGNORE_CASE,
    )
    private val COPY_SUFFIX = Regex("""\s*[(（]\d{1,2}[)）]$""")
    private val BRACKETED = Regex("""\[[^\]]*]|【[^】]*】|\([^)]*\)|（[^）]*）""")
    private val STANDALONE_NUMBER = Regex("""(?<![0-9A-Za-z])(\d{1,3})(?:[vV]\d)?(?![0-9A-Za-z])""")

    /**
     * 括号开头两三位数字, 后面紧跟着标题: 不能是字母数字 (`[1080p]` `[10bit]`)、小数 (`[24.9]` 是两季之间的特别篇, 不是第 24 集),
     * 不能是日期或季 (`[24年10月]`), 也不能是范围 (`[01-12]`); 数字后面得还有字 (光一个 `[12]` 的由前面几条认).
     */
    private val BRACKET_LEADING_NUMBER =
        Regex("""[\[【]\s*(\d{2,3})(?![0-9A-Za-z])(?!\.\d)(?!\s*[年月日季期部\-~～])(?=\s*[^\]】\s])""")
}
