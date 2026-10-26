/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.search

import me.him188.ani.app.domain.mediasource.clouddrive.DriveNameParser


/**
 * 系列的第一部的名字后面接着标题的另一段 (「Sword Art Online Alicization」「Attack on Titan The Final Season」「刀剑神域 Extra Edition」):
 * 第一部的名字就是系列名, 这里只是更长的标题的开头, 那个标题是同系列别的作品的. 系列关系图只有别的作品的中文名与原名, 英文名、罗马音名对不上,
 * 靠这个认 (见 [TitleIdentity.namingOf]). 后来的作品的名字本身带着它的那一段, 不用这个 (罗马音写的本段「Zenpen」也会被当成别的标题).
 *
 * 名字后面隔着空格、点、下划线或连字符紧接着一个英文单词才算接着标题; 单词是格式标记 (`BDRip` `Season` `Part`…)、罗马数字的季、
 * 本条目英文名里的词 (「葬送的芙莉莲 Frieren」), 或后面紧跟数字 (`S01E01` `x265`) 时不算. 名字原样找不到 (繁简、全角写法不同) 时也不算.
 * 写着季的 (「我推的孩子.My.Favorite.Idol.s.Children.S01E04」) 也不算: 那是这个系列的第几季, 按季认.
 *
 * @param names 本条目的名字 (原样). 太短、不拿来认名字的也要给: 它们的词也是本条目的词 (「特别的她 FLCL」的 FLCL)
 */
internal class TitleOccurrences(names: List<String>) {
    private val patterns: Map<String, List<Regex>> = names.mapNotNull { name -> patternOf(name)?.let { TitleForms.compact(name) to it } }
        .groupBy({ it.first }, { it.second })

    private val ownWords: Set<String> = names.flatMapTo(HashSet()) { name -> LATIN_WORD.findAll(name).map { it.value.lowercase() } }

    /**
     * [segment] 里名字 [hits] (已归一化, 见 [TitleForms.compact]) 的每一处都接着标题的另一段.
     * 有一处后面是标题的结尾、一处都没原样找到, 或写着季时为 false.
     */
    fun continuesEvery(segment: String, hits: List<String>): Boolean {
        if (DriveNameParser.declaredSeasons(segment).isNotEmpty() || DriveNameParser.parseFile(segment).season != null) return false
        var found = false
        for (hit in hits) {
            for (pattern in patterns[hit].orEmpty()) {
                for (match in pattern.findAll(segment)) {
                    if (!continues(segment, match.range.last + 1)) return false
                    found = true
                }
            }
        }
        return found
    }

    /**
     * 短名字 [name] (已归一化, 见 [TitleIdentity]) 在 [segment] 里单独成词地出现了一处: 前后都不挨着字母或汉字
     * (「一杆青空」「怪物事变」「谷江玲音」「红辣椒俱乐部」都不算), 后面不是「：」接着副标题 (「怪物：丽兹·波顿的故事」),
     * 也不接着标题的另一段英文 (「AIR GEAR」). 挨着数字的算 (「2019怪物」「怪物01」), 挨着的是季、合集、类别这类标记的也算
     * (「虫师第一季」「红辣椒全集」「日漫间谍过家家」). 挨着的是本条目别的名字的也算; 汉字假名写的名字挨着英文字母的也算 (「NANA娜娜」),
     * 英文写的名字挨着汉字的不算 (中文标题里常夹着英文词: 「杀死Air」「Fairy兰丸」).
     */
    fun standsAlone(text: String, name: String): Boolean {
        val segment = patterns.filterKeys { it != name && name !in it }.values.flatten().fold(text) { s, other -> other.replace(s, " ") }
        return standsAloneIn(segment, name)
    }

    private fun standsAloneIn(segment: String, name: String): Boolean = patterns[name].orEmpty().any { pattern ->
        val cjk = name.none { it in 'a'..'z' }
        fun Char?.ends() = this == null || !isLetter() || cjk && isAsciiLetter()
        pattern.findAll(segment).any { match ->
            val before = segment.substring(0, match.range.first)
            val after = segment.substring(match.range.last + 1)
            val beforeEnds = before.lastOrNull().ends() || TAG_BEFORE.containsMatchIn(before)
            val afterEnds = after.firstOrNull().ends() || TAG_AFTER.containsMatchIn(after)
            beforeEnds && afterEnds && !SUBTITLE_AFTER.containsMatchIn(after) && !continues(segment, match.range.last + 1)
        }
    }

    private fun continues(segment: String, end: Int): Boolean {
        val word = FOLLOWING_WORD.find(segment.substring(end))?.groupValues?.get(1) ?: return false
        val lower = word.lowercase()
        return lower !in TAG_WORDS && lower !in ownWords && !ROMAN_SEASON.matches(word)
    }

    internal companion object {
        val TOKEN = Regex("""[\p{L}\p{N}]+""")
        val LATIN_WORD = Regex("""[A-Za-z]+""")

        /** 名字后面紧接着的英文单词: 前面只隔着名字末尾的叹号问号与空格、点、下划线或连字符, 后面不挨着字母与数字 */
        val FOLLOWING_WORD = Regex("""^[!?！？]*(?:[ ._]+|[ ._]*-[ ._]*)([A-Za-z]{2,})(?![A-Za-z0-9])""")

        val ROMAN_SEASON = Regex("""I{1,3}|IV|VI{0,3}|IX|X""")

        /** 名字后面接着「：」与副标题 */
        val SUBTITLE_AFTER = Regex("""^\s*[：:]\s*\p{L}""")

        /** 紧挨在名字后面的季、集数、合集、类别、语言标记 */
        val TAG_AFTER = Regex(
            """^(?:第\s*[0-9一二两三四五六七八九十]{1,3}\s*[季期部集话話]|全\s*[0-9一二两三四五六七八九十]*\s*[集季话話]|[0-9一二两三四五六七八九十]+\s*[集季]""" +
                """|合集|全集|全季|系列|剧场版|劇場版|电影|动画|动漫|番剧|特别篇|总集篇|番外|OVA|OAD|SP|国语|日语|粤语|中字|字幕|高清|蓝光|原盘|完结""" +
                """|典藏|收藏|无删减|未删减|修复|重制|网盘|资源|\u5938\u514B""" +
                // 一个字写成汉字的年份 (「人狼一999」)
                """|[〇零一二][0-9〇零一二三四五六七八九]{3}(?![0-9]))""",
            RegexOption.IGNORE_CASE,
        )

        /** 紧挨在名字前面的类别标记 */
        val TAG_BEFORE = Regex("""(?:日漫|国漫|动漫|动画|新番|旧番|番剧|剧场版|劇場版|电影版|映画)$""")

        /** 跟在番名后面的格式、来源、语言、合集标记: 后面是它们的番名就是标题的结尾 */
        val TAG_WORDS = setOf(
            "season", "seasons", "part", "parts", "cour", "vol", "volume", "disc", "disk",
            "ep", "eps", "episode", "episodes", "sp", "sps", "special", "specials", "ova", "oad", "ona", "oav",
            "tv", "tvrip", "bd", "bds", "bdrip", "bdremux", "bdmv", "bluray", "blu", "web", "webrip", "webdl", "dl", "dvd", "dvdrip",
            "hdtv", "hdrip", "remux", "rip", "uhd", "hdr", "sdr", "hd", "fhd", "hq", "hevc", "avc", "aac", "flac", "dts", "truehd", "atmos", "opus",
            "nf", "amzn", "cr", "dsnp", "baha", "bilibili", "abema",
            "chs", "cht", "chi", "chn", "gb", "big", "jpn", "jp", "jap", "eng", "en", "sc", "tc", "cn",
            "sub", "subs", "subbed", "dub", "dubbed", "dual", "multi", "audio", "raw", "raws",
            "complete", "batch", "fin", "end", "uncensored", "uncut", "remastered", "remaster", "reseed", "repack", "proper", "ver", "version",
            "ncop", "nced", "op", "ed", "pv", "cm", "menu", "menus", "preview", "previews", "trailer", "extras", "bonus",
            "collection", "series", "box", "boxset", "set",
        )

        /**
         * 名字原样出现的样子: 字与字之间的符号、空格可有可无, 不分大小写. 以英文字母开头、结尾的名字两头不能挨着字母
         * (那是更长的单词的一段, 不算出现).
         */
        fun patternOf(name: String): Regex? {
            val tokens = TOKEN.findAll(name).map { Regex.escape(it.value) }.toList()
            if (tokens.isEmpty()) return null
            val before = if (name.trimStart().firstOrNull()?.isAsciiLetter() == true) """(?<![A-Za-z])""" else ""
            val after = if (name.trimEnd().lastOrNull()?.isAsciiLetter() == true) """(?![A-Za-z])""" else ""
            return Regex(before + tokens.joinToString("""[^\p{L}\p{N}]*""") + after, RegexOption.IGNORE_CASE)
        }

        private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'
    }
}
