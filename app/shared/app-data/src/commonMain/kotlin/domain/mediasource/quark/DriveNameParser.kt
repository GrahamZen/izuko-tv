/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.quark

import me.him188.ani.app.domain.mediasource.toSimplifiedChinese
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
    )

    fun parseFile(fileName: String): ParsedFile {
        val name = removeExtension(fileName)
        if (EXTRA.containsMatchIn(name)) return ParsedFile(null, null, isExtra = true)

        SEASON_EPISODE.find(name)?.let { match ->
            return ParsedFile(
                season = match.groupValues[1].toInt(),
                episode = episodeSort(match.groupValues[2], match.groupValues[3]),
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
        bareNumberEpisode(name)?.let { return ParsedFile(null, it, false) }
        return ParsedFile(null, null, false)
    }

    /**
     * 文件夹名里写的季: `S02`, `Season 2`, `第二季`, `2nd Season`, 以及紧跟在中文标题后的单个数字 (`葬送的芙莉莲2`).
     */
    fun parseFolderSeason(folderName: String): Int? {
        parseExplicitSeason(folderName)?.let { return it }
        FOLDER_SEASON_CODE.find(folderName)?.let { return it.groupValues[1].toInt() }
        TRAILING_DIGIT_AFTER_CJK.find(folderName.trim())?.let { return it.groupValues[1].toInt() }
        return null
    }

    /**
     * 条目名里写的季. 只认明确的写法, 不认标题末尾的数字 (条目名里的数字常常就是标题的一部分).
     */
    fun parseSubjectSeason(subjectName: String): Int? = parseExplicitSeason(subjectName)

    /**
     * 去掉季标记与副标题后的主标题, 用作搜索关键词. 例如 `无职转生 第三季 ～到了异世界就拿出真本事～` 得到 `无职转生`.
     */
    fun baseTitle(subjectName: String): String {
        var name = subjectName
        for (regex in SEASON_MARKERS) {
            val match = regex.find(name) ?: continue
            val before = name.substring(0, match.range.first).trim()
            name = if (before.length >= 2) before else name.removeRange(match.range)
        }
        // 副标题前面那段太短时 (`Re:ゼロから始める…` 的 `Re`) 不能单独当标题
        val first = SUBTITLE_SEPARATOR.split(name).first()
        val main = if (normalize(first).length >= 3) first else name
        return main.trim().trim(*TRIM_CHARS)
    }

    /**
     * 用于比较的名字: 转简体、转小写, 只留字母数字与汉字假名.
     * `Smoking.Behind.the.Supermarket` 与 `Smoking Behind the Supermarket` 得到同一个结果.
     */
    fun normalize(name: String): String =
        name.toSimplifiedChinese().lowercase().filter { it.isLetterOrDigit() }

    private fun parseExplicitSeason(name: String): Int? {
        CHINESE_SEASON.find(name)?.let { match -> parseNumber(match.groupValues[1])?.let { return it } }
        ENGLISH_SEASON.find(name)?.let { return it.groupValues[1].toInt() }
        ORDINAL_SEASON.find(name)?.let { return it.groupValues[1].toInt() }
        ROMAN_SEASON.find(name)?.let { return ROMAN_NUMERALS.getValue(it.groupValues[1]) }
        return null
    }

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

    private val SEASON_EPISODE =
        Regex("""(?<![A-Za-z0-9])[Ss](\d{1,2})[ ._-]?[Ee][Pp]?(\d{1,4})(?:\.(\d))?(?![0-9])""")
    private val CHINESE_EPISODE = Regex("""第\s*([0-9]{1,4}|[零〇一二两三四五六七八九十]{1,4})\s*[集话話回]""")
    private val EP_EPISODE = Regex("""(?<![A-Za-z])(?:EP|Ep|ep|E)[ ._-]?(\d{1,4})(?:\.(\d))?(?![0-9])""")

    private val EXTRA = Regex(
        """(?<![A-Za-z])(?:NCOP|NCED|OP|ED|PV|CM|MENU|Menu|PREVIEW|Preview|TRAILER|Trailer|Teaser|TEASER|SP|OVA|OAD)[ ._-]?\d{0,2}(?![A-Za-z0-9])""" +
                """|预告|花絮|片头曲?|片尾曲?|特典映像|映像特典""",
    )

    private val CHINESE_SEASON = Regex("""第\s*([0-9]{1,2}|[一二两三四五六七八九十]{1,3})\s*[季期部]""")
    private val ENGLISH_SEASON = Regex("""(?i)(?<![a-z])season[ ._-]*(\d{1,2})(?![0-9])""")
    private val ORDINAL_SEASON = Regex("""(?i)(?<![0-9])(\d{1,2})(?:st|nd|rd|th)[ ._-]*season""")
    private val ROMAN_SEASON = Regex("""(?<![A-Za-z])(Ⅱ|Ⅲ|Ⅳ|Ⅴ|Ⅵ|III|II)(?![A-Za-z])""")
    private val FOLDER_SEASON_CODE = Regex("""(?<![A-Za-z0-9])[Ss](\d{1,2})(?![0-9Ee])""")
    private val TRAILING_DIGIT_AFTER_CJK = Regex("""[一-鿿]\s*([2-9])$""")

    private val SEASON_MARKERS = listOf(CHINESE_SEASON, ENGLISH_SEASON, ORDINAL_SEASON, ROMAN_SEASON)
    private val SUBTITLE_SEPARATOR = Regex("""\s*[～~:：｜|]\s*|\s+[-–—]\s+""")
    private val TRIM_CHARS = charArrayOf(' ', '-', '_', '.', '·', '!', '！', '?', '？')

    private val BRACKETED = Regex("""\[[^\]]*]|【[^】]*】|\([^)]*\)|（[^）]*）""")
    private val STANDALONE_NUMBER = Regex("""(?<![0-9A-Za-z])(\d{1,3})(?:[vV]\d)?(?![0-9A-Za-z])""")
}
