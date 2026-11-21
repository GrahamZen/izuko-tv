/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.clouddrive

import me.him188.ani.app.data.network.SeriesGraphTable
import me.him188.ani.app.data.network.SeriesNode
import me.him188.ani.app.domain.mediasource.search.TitleForms
import me.him188.ani.app.domain.mediasource.toSimplifiedChinese

/**
 * 「系列名 + 副标题」的条目 (「死神 千年血战篇-诀别谭-」「辉夜大小姐想让我告白-初吻不会结束-」) 在系列里怎么叫: 系列名、这一部的副标题、
 * 同系列别的部的副标题. 系列名取自系列关系图: 同系列别的作品的名字与条目名共同的开头, 在两边都是分隔符的地方断开
 * (「死神 千年血战篇」之于「死神 千年血战篇-诀别谭-」).
 *
 * 网盘路径点名了哪一部 ([DrivePathAttribution]) 按副标题认; 分享站按整段字符串找标题, 分享又常换个写法
 * (「死神 千年血战篇  诀别谭+相克谭+死神全集」「辉夜大小姐想让我告白：究极浪漫」), 整个条目名搜不到时单搜副标题 ([keywords]).
 *
 * 「第2部分」「第二季」「Part.2」「完结篇」这类按季、按半写的不算副标题: 网盘上常写成「第二季」, 与条目名对不上.
 *
 * @property keywords 拿去搜的副标题, 按名字的顺序 (中文名、原名、别名), 最多 [MAX_KEYWORDS] 个
 */
internal class DriveSeriesParts(
    val keywords: List<String>,
    /** 系列名, 已归一化 ([TitleForms.compact]) */
    val seriesNames: List<String>,
    /** 这一部的副标题, 已归一化 */
    val ownParts: List<String>,
    /** 同系列别的作品的副标题, 已归一化 */
    val otherParts: List<String>,
) {
    /** 站点给的分享标题是不是这一部的: 同时有系列名与这一部的副标题 (「诀别谭+相克谭」这类几部合在一起的也算). */
    fun acceptsTitle(title: String): Boolean {
        val normalized = TitleForms.compact(title)
        return seriesNames.any { it in normalized } && ownParts.any { it in normalized }
    }

    companion object {
        /** 最多搜几个副标题: 这类站连着搜几次就会回 5xx. */
        const val MAX_KEYWORDS = 2

        /** 系列名归一化后至少几个字 (「死神」). */
        private const val MIN_SERIES_LENGTH = 2

        /** 副标题归一化后至少几个字: 「终章」这类两个字的太泛. */
        private const val MIN_PART_LENGTH = 3

        /** 往前传、续集走多远: 长寿系列有几百个条目, 走全了没必要. */
        private const val MAX_SERIES_NODES = 200

        /**
         * 条目 [subjectId] 在系列里的叫法; 本条目与同系列别的作品都没有副标题 (不是「系列名 + 副标题」的写法)、或系列关系图里没有它时为 null.
         * 本条目自己没有副标题 (「JOJO的奇妙冒险 石之海 第2部分」) 时只有别的部的副标题 (「黄金之风」), 没有拿去搜的.
         * 只看表里的中文名与原名 (同系列别的作品在表里也只有这两个名字): 别名多是英文意译, 与「BLEACH」比出来的「Thousand」不是副标题.
         */
        fun of(subjectId: Int?, table: SeriesGraphTable): DriveSeriesParts? {
            val id = subjectId ?: return null
            val node = table.nodeOf(id) ?: return null
            val names = listOf(node.nameCn, node.name).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            if (names.any { RE_EDIT.containsMatchIn(it) }) return null
            val ownNormalized = names.map(TitleForms::compact)
            // 与本条目名字一字不差的 (同一段的剧场版、TV 版) 不是别的部. 只差标点的是别的季 (辉夜第一季与「辉夜…告白?～…」第二季), 照算
            val siblingNames = seriesNodesOf(id, table).flatMap { listOf(it.name, it.nameCn) }
                .filter { it.isNotBlank() && it.trim() !in names }
                .distinct()
            if (siblingNames.isEmpty()) return null
            val siblingNormalized = siblingNames.map(TitleForms::compact)

            val series = LinkedHashSet<String>()
            val own = LinkedHashMap<String, String>()
            val others = LinkedHashSet<String>()
            for (name in names) {
                val cuts = siblingNames.map { it to commonPrefixEnd(name, it) }
                    .filter { (_, cut) -> TitleForms.compact(name.substring(0, cut)).length >= MIN_SERIES_LENGTH }
                for ((sibling, cut) in cuts) {
                    for (piece in piecesOf(sibling.substring(cut))) {
                        val normalized = TitleForms.compact(piece)
                        if (ownNormalized.none { normalized in it }) others += normalized
                    }
                }
                // 别的作品的名字以整个条目名开头 (「Fate/strange Fake -Whispers of Dawn-」) 时条目名不是系列名
                val best = cuts.map { it.second }.filter { TitleForms.compact(name.substring(it)).isNotEmpty() }.maxOrNull() ?: continue
                series += TitleForms.compact(name.substring(0, best))
                for (piece in piecesOf(name.substring(best))) {
                    val normalized = TitleForms.compact(piece)
                    // 同系列别的作品的名字里也有的不算这一部的 (「千年血战篇」)
                    if (siblingNormalized.none { normalized in it }) own.getOrPut(normalized) { piece }
                }
            }
            if (series.isEmpty() || own.isEmpty() && others.isEmpty()) return null
            return DriveSeriesParts(own.values.take(MAX_KEYWORDS), series.toList(), own.keys.toList(), others.toList())
        }

        /** 同系列 (顺着前传、续集能走到的) 别的条目. 比表新的条目为空. */
        internal fun seriesNodesOf(subjectId: Int, table: SeriesGraphTable): List<SeriesNode> {
            val seen = linkedMapOf<Int, SeriesNode>()
            val queue = ArrayDeque(listOf(subjectId))
            val visited = hashSetOf(subjectId)
            while (queue.isNotEmpty() && seen.size < MAX_SERIES_NODES) {
                val edges = table.edgesOf(queue.removeFirst()) ?: continue
                for (node in edges.prequels + edges.sequels) {
                    if (!visited.add(node.id)) continue
                    seen[node.id] = node
                    queue += node.id
                }
            }
            return seen.values.toList()
        }

        /**
         * [name] 与 [other] 共同的开头在 [name] 里的终点 (繁简、大小写不论), 只断在两边都是分隔处的地方; 没有时为 0.
         * 断开处要往回退不止一个字的也算没有: 两个名字是同一个词接着写了不同的东西 (「出包王女Darkness」与「出包王女」、
         * 「響け！ユーフォニアム3」与「…2」), 不是系列名加副标题; 只退一个字的 (「间谍过家家 第2部分」与「…第二季」共有的「第」) 照断.
         */
        internal fun commonPrefixEnd(name: String, other: String): Int {
            var shared = 0
            while (shared < name.length && shared < other.length && fold(name[shared]) == fold(other[shared])) shared++
            var end = shared
            while (end > 0 && !(boundaryAt(name, end) && boundaryAt(other, end))) end--
            return if (shared - end > 1) 0 else end
        }

        private fun boundaryAt(text: String, index: Int): Boolean =
            index >= text.length || isSeparator(text[index]) || isSeparator(text[index - 1])

        private fun fold(char: Char): String = char.toString().toSimplifiedChinese().lowercase()

        private fun isSeparator(char: Char): Boolean = char.isWhitespace() || char in SEPARATORS

        /**
         * 副标题里能单独拿去搜、拿来认的几段: 去掉季、半季、特别篇这类写法, 按标点拆开 (字母数字中间的 `-` 不拆: 「U-20」「1-sinθ」);
         * 汉字假名的一段再按空格拆 (「异界战争 终章」), 英文按空格是一个词组, 不拆. 只有英文字母数字的一段至少 [MIN_LATIN_PART_LENGTH] 个
         * (「piece」这类单词太泛).
         */
        internal fun piecesOf(text: String): List<String> =
            NOT_SUBTITLE.replace(text, " ")
                .split(PIECE_SEPARATOR)
                .flatMap { segment -> if (segment.any(::isCjk)) segment.split(WHITESPACE) else listOf(segment) }
                .map { it.trim() }
                .filter { piece ->
                    val normalized = TitleForms.compact(piece)
                    val min = if (normalized.all { it in 'a'..'z' || it in '0'..'9' }) MIN_LATIN_PART_LENGTH else MIN_PART_LENGTH
                    normalized.length >= min && !piece.all { it.isDigit() }
                }

        /** 只有英文字母数字的副标题至少几个字. */
        private const val MIN_LATIN_PART_LENGTH = 6

        private fun isCjk(char: Char): Boolean = char in '぀'..'ヿ' || char in '㐀'..'鿿' || char in '豈'..'﫿'

        private const val SEPARATORS = "-－―—–‐‑‒−～~〜:：·・･.()（）【】「」『』[]〔〕《》<>/／|｜!！?？,，、。+＋&＆#＃♪☆★※'\"“”‘’"

        private val PIECE_SEPARATOR =
            Regex("""[－―—–‐‑‒−～~〜:：·・･()（）【】「」『』\[\]〔〕《》<>/／|｜!！?？,，、。+＋&＆#＃♪☆★※"“”]+|(?<![A-Za-z0-9])-+|-+(?![A-Za-z0-9])""")

        private val WHITESPACE = Regex("""\s+""")

        /** 季、半季、特别篇、年份这类写法, 不是副标题. 「第二个夏天」不算 (「第几」后面要跟着季、期之类). */
        private val NOT_SUBTITLE = Regex(
            """第\s*[0-9０-９一二两三四五六七八九十百]+\s*(?:季|期|部分|部|クール|シーズン|章|篇|編|编|話|话|集|弾|弹|幕)""" +
                """|(?:part|season|cour|vol)\s*\.?\s*[0-9ivx]+""" +
                """|[0-9]+(?:st|nd|rd|th)\s*(?:season|cour|part|stage|クール|シーズン)""" +
                """|(?:the\s+)?(?:final|last)\s+season|\bthe\s+final\b""" +
                """|(?:ファースト|セカンド|サード|フォース|フィフス|ファイナル)?シーズン\s*[0-9０-９]*|クール|シリーズ|시즌\s*[0-9]*""" +
                """|最终季|最終季|完结篇|完結篇|完結編|前篇|后篇|後篇|前編|後編|前半|后半|後半""" +
                """|特别篇|特別篇|特別編|特别编|总集篇|總集篇|総集編|特別編集版|特别编集版|特別编集版""" +
                """|终章|終章|剧场版|劇場版|\bova\b|\boad\b|\bsp\b""" +
                """|[(（]\s*(?:19|20)\d{2}\s*[)）]""",
            RegexOption.IGNORE_CASE,
        )

        /**
         * 重剪、总集、特别篇这类条目: 用的是正篇某一段的名字 (「航海王：鱼人岛篇特别编辑版」), 按那一段搜到的是正篇,
         * 正篇文件夹里的 `01` 会被当成它的第 1 集. 不按副标题认, 也不单搜副标题.
         */
        private val RE_EDIT = Regex("""特[别別]編[集輯]|特[别別]编[集辑]|[总總総][集]?[篇編编]|特[别別][篇編编]""")
    }
}
