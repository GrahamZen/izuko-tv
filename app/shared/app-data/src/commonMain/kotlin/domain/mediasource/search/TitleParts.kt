/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.search

/**
 * 一段名字 (网页的页名、分享站的标题、网盘的文件夹名与文件名) 里写的作品名: 去掉推广文案、标签 (年份、季、集数与范围、画质编码、
 * 字幕语言、合集词、分类、字幕组) 后剩下的部分, 归一化 ([TitleForms.compact]) 后拿来与条目、别的作品的名字按相等比.
 *
 * 一段名字可能写着几个作品名: 括号里的 (「Kanon（雪之少女）」「怪物（monster）」「[悠久之翼 ef - a tale of memories 2007]」)、
 * 隔着空格换了文字的 (「怪物 Monster」「水星领航员 暮色 ARIA The CREPUSCOLO」「葬送的芙莉莲 葬送のフリーレン」) 都各算一个.
 * 拆不干净的 (标签认不全) 会留下多余的字, 那就与谁都不相等: 按相等认的地方只多认, 不会认错.
 */
internal object TitleParts {
    /** [text] 写着的作品名 (已归一化, 去重); 一个都拆不出时为空. */
    fun namesIn(text: String): Set<String> = names(text, whole = false)

    /**
     * [text] 写着的作品名的原样写法: 同 [namesIn], 但不去掉季、不按文字切开. 名字本身带着季或几种文字的
     * (「BanG Dream! 2nd Season」「BanG Dream! ガルパ☆ピコ」) 拆开后与谁都不相等, 认别的作品时也按这份比.
     */
    fun wholeNamesIn(text: String): Set<String> = names(text, whole = true)

    private fun names(text: String, whole: Boolean): Set<String> {
        val body = withoutPromotion(text)
        val pieces = ArrayList<String>()
        // 括号里的各算一段, 括号外剩下的也算一段
        val outside = BRACKET.replace(body) { match ->
            pieces += match.groupValues.drop(1).first { it.isNotEmpty() }
            " "
        }
        pieces += outside
        return pieces.asSequence()
            .flatMap { it.split(JOINER) }
            .flatMap { if (whole) listOf(it) else splitByScript(it) }
            .map { TitleForms.compact(withoutTags(it, keepSeasons = whole)) }
            .filter { it.length >= MIN_NAME_LENGTH && it.any { ch -> !ch.isDigit() } }
            .toCollection(LinkedHashSet())
    }

    /** 分享站标题里的推广文案: 「资源名称：」之类的前缀只留后面, 「亮点：」「标签：」之后的介绍整段去掉. */
    private fun withoutPromotion(text: String): String {
        var s = text
        PROMOTION_PREFIX.findAll(s).lastOrNull()?.let { s = s.substring(it.range.last + 1) }
        PROMOTION_TAIL.find(s)?.let { s = s.substring(0, it.range.first) }
        return HASHTAG.replace(s, " ")
    }

    /** 隔着空格、前后文字不同 (英文 / 有假名的 / 只有汉字) 的地方切开 */
    private fun splitByScript(piece: String): List<String> {
        val words = piece.trim().split(SPACES).filter { it.isNotEmpty() }
        if (words.size <= 1) return listOf(piece)
        val out = ArrayList<String>()
        val current = StringBuilder(words[0])
        var script = scriptOf(words[0])
        for (word in words.drop(1)) {
            val next = scriptOf(word)
            if (next != Script.OTHER && script != Script.OTHER && next != script) {
                out += current.toString()
                current.clear()
                current.append(word)
            } else {
                current.append(' ').append(word)
            }
            if (next != Script.OTHER) script = next
        }
        out += current.toString()
        return out
    }

    private enum class Script { LATIN, KANA, HAN, OTHER }

    private fun scriptOf(word: String): Script = when {
        word.any { it in '぀'..'ヿ' } -> Script.KANA
        word.any { it.isCjk() } -> Script.HAN
        word.any { it in 'a'..'z' || it in 'A'..'Z' } -> Script.LATIN
        else -> Script.OTHER
    }

    private fun Char.isCjk() = this in '一'..'鿿' || this in '㐀'..'䶿'

    private fun withoutTags(text: String, keepSeasons: Boolean = false): String {
        var s = text
        for (tag in TAGS) if (!(keepSeasons && tag === SEASON)) s = tag.replace(s, " ")
        return LEADING_INDEX.replace(s.trim(), "")
    }

    private const val MIN_NAME_LENGTH = 2

    private val SPACES = Regex("""\s+""")

    /** 合集标题里连接几部的符号 (「咒术回战1-2季全+影版」「海贼王 / 航海王真人剧」); 两边不隔空格的斜杠是名字的一部分 (「Fate/Zero」) */
    private val JOINER = Regex("""[+＋&＆]|\s/\s""")

    /** 括号: 【】[]《》「」『』（）(); 取括号里的一段 */
    private val BRACKET = Regex("""【([^【】]*)】|\[([^\[\]]*)]|《([^《》]*)》|「([^「」]*)」|『([^『』]*)』|（([^（）]*)）|\(([^()]*)\)""")

    private val PROMOTION_PREFIX = Regex("""(?:资源名称|资源标题|名称|标题|剧名|片名|电影名称)\s*[：:]""")
    private val PROMOTION_TAIL = Regex("""亮点\s*[：:]|标签\s*[：:]|更新日期|介绍\s*[：:]|简介\s*[：:]|别名\s*[：:]|📜|💾|📁|🏷|⬇""")
    private val HASHTAG = Regex("""#[^#\s]{1,20}#?""")

    /** 作品名前面的序号与画质标记 (`J 4k 间谍过家家`, `01.`, `1.刀剑神域`) */
    private val LEADING_INDEX = Regex("""^(?:[A-Za-z]\s+|\d{1,3}[.、\s]+|[248]k\s+)+""", RegexOption.IGNORE_CASE)

    /** 季、部、段 */
    private val SEASON = Regex(
        """第\s*[0-9一二两三四五六七八九十]{1,3}\s*[季期部]|(?<![a-z])s\d{1,2}(?:\s*[-~～]\s*s?\d{1,2})?(?![0-9])|(?<![a-z])season\s*\d{1,2}(?![0-9])""" +
            """|\d{1,2}(?:st|nd|rd|th)\s*season|全\s*\d{1,2}\s*季|\d{1,2}\s*季全|[0-9一二两三四五]{1,2}\s*[-~～至]\s*[0-9一二两三四五]{1,2}\s*季""",
        RegexOption.IGNORE_CASE,
    )

    private val TAGS = listOf(
        // 年份与年份范围
        Regex("""(?<![0-9])(?:19|20)\d\d(?:\s*[-~～至]\s*(?:19|20)?\d\d)?年?(?![0-9])"""),
        SEASON,
        // 集数与范围
        Regex(
            """全\s*\d{1,4}\s*[集话話]|\d{1,4}\s*[集话話]全|共\s*\d{1,4}\s*[集话話]|更新?至\s*\d{1,4}\s*[集话話]?|(?:第\s*)?\d{1,4}\s*[集话話]""" +
                """|第[一二三四五六七八九十百零]+[集话話]|(?<![0-9])\d{1,4}\s*[-~～]\s*\d{1,4}(?![0-9])|(?<![a-z])ep?\s*\d{1,4}(?![0-9])""" +
                """|\s[-–—]\s*\d{1,4}(?:v\d)?(?![0-9])""",
            RegexOption.IGNORE_CASE,
        ),
        // 画质、来源、编码、音轨
        Regex(
            """(?<![a-z0-9])(?:\d{3,4}[pi]|[248]k|uhd|hdr\d*|sdr|dv|dovi|bd(?:rip|remux|mv)?|blu-?ray|web-?(?:dl|rip)?|hdtv|dvd(?:rip)?|remux|hevc|avc""" +
                """|aac(?:\d\.\d)?|flac(?:x\d)?|ddp?(?:\d\.\d)?|dts(?:-hd)?(?:\s*ma)?|truehd|atmos|opus|\d{1,2}\s*bit|hi10p|ma10p|60fps|\d\.\dch)(?![a-z0-9])""" +
                """|[hx×]\.?26[45](?![0-9])""",
            RegexOption.IGNORE_CASE,
        ),
        // 不会出现在作品名里的字幕语言、合集、收藏说明: 连着别的字也去掉 (「进击的巨人全季4K典藏版」)
        Regex(
            """简繁英?|简日|繁日|简中|繁中|中日双语|中字|日字|中文字幕|内封|内嵌|外挂|硬字幕|日语中字|日语|国语|粤语|全集|合集|全季|典藏版|收藏版|珍藏版""" +
                """|高码率|原盘|已完结|\u5938\u514B网盘资源|网盘资源|\d{1,2}(?:\.\d)?\s*分(?![钟鐘])""",
        ),
        // 可能是作品名里的字的分类、说明词: 只去单独成词的 (《电影少女》《物语系列》不动)
        Regex(
            """(?<![\p{L}\p{N}])(?:日漫|国漫|美漫|动漫|动画|番剧|新番|旧番|老番|日剧|日影|电影|剧集|TV版|动画版|系列|完结|字幕|双语|特效|官方|蓝光|高清|超清|资源|网盘|\u5938\u514B""" +
                """|剧场版|劇場版|影版|特别篇|特別篇|总集篇|番外篇?|特典|映像特典|花絮|附赠|附贈)(?![\p{L}\p{N}])""",
        ),
        // 番剧文件夹里常见的分类目录名
        Regex(
            """(?<![\p{L}\p{N}])(?:specials?|sps?|extras?|scans?|menus?|bonus|fonts?|ost|soundtracks?|cds?|ncop|nced|ova|oad|pv|cm|previews?|trailers?|subs?)(?![\p{L}\p{N}])""",
            RegexOption.IGNORE_CASE,
        ),
        // 字幕组、压制组 (以这些字结尾的一段)
        Regex("""\S*(?:字幕组|字幕組|字幕社|字幕團|字幕团|汉化组|漢化組|壓制組|压制组|奶茶屋)"""),
        // 文件扩展名与符号
        Regex("""\.(?:mkv|mp4|avi|ts|rmvb|flv|m2ts)$""", RegexOption.IGNORE_CASE),
        Regex("""[|丨/\＋+&＆@~～·・.,，、:：;；!！?？\-—_]+"""),
    )
}
