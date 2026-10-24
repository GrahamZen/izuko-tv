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
import me.him188.ani.app.domain.mediasource.toSimplifiedChinese
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.source.MediaFetchRequest

/**
 * 「系列名 + 副标题」的条目 (「死神 千年血战篇-诀别谭-」「辉夜大小姐想让我告白-初吻不会结束-」) 在分享站上按条目名认上的没覆盖每一集时的补搜.
 * 分享站按整段字符串找标题, 分享又常换个写法 (「死神 千年血战篇  诀别谭+相克谭+死神全集」「辉夜大小姐想让我告白：究极浪漫」),
 * 整个条目名搜不到; 只搜副标题 (条目名去掉系列名剩下的那段) 搜到的几乎都是这一部. 系列名取自系列关系图: 同系列别的作品的名字与条目名
 * 共同的开头, 在两边都是分隔符的地方断开 (「死神 千年血战篇」之于「死神 千年血战篇-诀别谭-」).
 *
 * 宽的词更容易搜到同系列别的部, 播错比搜不到糟, 补搜来的一律要认得出是这一部:
 * - 分享标题 ([acceptsTitle]): 同时有系列名与这一部的副标题 (哪个名字的都行, 繁简、标点不论);
 * - 分享里的文件 ([keeps]): 见那里.
 *
 * 「第2部分」「第二季」「Part.2」「完结篇」这类按季、按半写的不算副标题: 网盘上常写成「第二季」, 与条目名对不上, 不补搜.
 *
 * @property keywords 拿去搜的副标题, 按名字的顺序 (中文名、原名、别名), 最多 [MAX_SEARCHES] 个
 */
internal class DriveSharePartSearch(
    val keywords: List<String>,
    /** 系列名, 已归一化 ([DriveNameParser.normalize]) */
    private val seriesNames: List<String>,
    /** 这一部的副标题, 已归一化 */
    private val ownParts: List<String>,
    /** 同系列别的作品的副标题, 已归一化 */
    private val otherParts: List<String>,
    /**
     * 这一部名字里写的季 (「我独自升级 第二季 -起于暗影-」的 2), 同系列别的作品都没写这一季时才有: 写了这一季的文件夹就是这一部的
     * (见 [keeps]). 一季拆成两部 (Re:Zero 第三季的袭击篇与反击篇) 时为 null: 「第3季」文件夹里两部都在.
     */
    private val ownSeason: Int? = null,
    /** 系列名里含着的更短的、同系列别的作品的名字 (「死神千年血战篇」里的「死神」, 2004 年的 TV 版), 已归一化 */
    private val rootNames: List<String> = emptyList(),
) {
    /** 站点给的分享标题是不是这一部的: 同时有系列名与这一部的副标题 (「诀别谭+相克谭」这类几部合在一起的也算, 里面的文件再按 [keeps] 认). */
    fun acceptsTitle(title: String): Boolean {
        val normalized = DriveNameParser.normalize(title)
        return seriesNames.any { it in normalized } && ownParts.any { it in normalized }
    }

    /**
     * 补搜来的分享里的文件 [match] 是不是这一部的. 从文件往上 (文件名、各层文件夹、分享标题) 第一个点名了某一部的名字说了算:
     * 只点名这一部的要, 只点名别的部的不要. 点名这一部的那一层 (分享标题或文件夹) 与文件之间隔着写了集号范围或季的文件夹
     * (「…祸进谭… 更新EP48 / 1-40 / EP 01.mp4」「…京都动乱；第二季… / 第一季[24集全] / [01].mp4」) 时那个名字不算: 这类分享与文件夹
     * 按最新一部起名, 里面附着前几部, 那一层是另一套编号. 只写了这一部自己那一季 ([ownSeason]) 的文件夹不算隔开
     * (「我独自升级 第二季」分享里的「《我独自升级 第2季》」).
     * 名字说明不了 (同时点名了几部, 如「诀别谭+相克谭+死神全集」; 或标题被隔开) 时只认集号: 按 TMDB 对应表 ([tmdbEpisodes]) 对上的
     * `S几E几`, 以及文件夹点了系列名、集号是这一部的跨部连续编号又不是这一部本身的序号的 (「死神 千年血战篇 / 27.mkv」之于相克谭);
     * 从 01 编起的分不清是哪一部 (`死神 千年血战篇/01.mp4` 可能是任何一部), 不要.
     */
    fun keeps(match: DriveShareMatch, request: MediaFetchRequest, tmdbEpisodes: Map<Pair<Int, Int>, EpisodeSort>?): Boolean {
        when (namingOf(listOf(match.file.fileName) + match.folders.asReversed() + match.share.siteTitle)) {
            Naming.OWN -> return true
            Naming.OTHER -> return false
            Naming.UNKNOWN -> {}
        }
        val parsed = DriveNameParser.parseFile(match.file.fileName)
        val fileEpisode = (parsed.episode as? EpisodeSort.Normal)?.number?.takeIf { it % 1f == 0f }?.toInt()
        if (tmdbEpisodes != null && parsed.season != null && fileEpisode != null && (parsed.season to fileEpisode) in tmdbEpisodes) {
            return true
        }
        // 跨部连续编号只在离文件最近的、提到这个系列的那一层点的是这一段的系列名时算数: 同一个分享里常附着系列的第一部
        // (「死神系列动漫 TV版 / 1-30 / 027.mkv」是 2004 年 TV 版的第 27 集), 它从 1 编到几百, 与后几部的连续编号对得上
        val seriesNamed = (listOf(match.file.fileName) + match.folders.asReversed()).firstNotNullOfOrNull { segment ->
            val normalized = DriveNameParser.normalize(segment)
            when {
                seriesNames.any { it in normalized } -> true
                rootNames.any { it in normalized } -> false
                else -> null
            }
        } == true
        val ownEps = request.episodes.mapNotNullTo(HashSet()) { it.ep }
        return seriesNamed && request.episodes.any { it.sort == match.episode } && match.episode !in ownEps
    }

    /**
     * 文件 [fileName] 的路径点名的是这一部 (规则同 [keeps] 的名字那一步; [folders] 从外到里, 第一个可以是分享标题).
     * 网盘上把这一部当成单独一部、从它起编季的 (「…祸进谭… / S死神 / S01E41.mkv」, TMDB 记成 S2E41) 按它认, 见 [DriveSubjectMatcher.matchEpisodes].
     */
    fun namesOwnPart(fileName: String, folders: List<String>): Boolean =
        namingOf(listOf(fileName) + folders.asReversed()) == Naming.OWN

    private enum class Naming { OWN, OTHER, UNKNOWN }

    /**
     * 从文件往上 ([segments]: 文件名、各层文件夹, 最后一个是最外层) 第一个点名了某一部的名字: 只点名这一部为 [Naming.OWN],
     * 只点名别的部为 [Naming.OTHER]; 同时点名几部、点名这一部的那一层与文件之间隔着写了集号范围或季的文件夹、或都没点名时为 [Naming.UNKNOWN].
     */
    private fun namingOf(segments: List<String>): Naming {
        var bucketed = false
        for ((index, segment) in segments.withIndex()) {
            val normalized = DriveNameParser.normalize(segment)
            val own = ownParts.any { it in normalized }
            val other = otherParts.any { it in normalized }
            val isFolder = index in 1 until segments.lastIndex
            if (own && other) return Naming.UNKNOWN
            if (other) return Naming.OTHER
            if (own) return if (bucketed) Naming.UNKNOWN else Naming.OWN
            if (isFolder && separatesNumbering(segment)) bucketed = true
        }
        return Naming.UNKNOWN
    }

    /** 文件夹 [folder] 写了集号范围或季 (这一部自己那一季除外): 里面是另一套编号. */
    private fun separatesNumbering(folder: String): Boolean {
        if (EPISODE_RANGE.containsMatchIn(folder)) return true
        val seasons = DriveNameParser.declaredSeasons(folder)
        return seasons.isNotEmpty() && (ownSeason == null || seasons != setOf(ownSeason))
    }

    companion object {
        /** 每个源最多补搜几次: 这类站连着搜几次就会回 5xx. */
        const val MAX_SEARCHES = 2

        /** 系列名归一化后至少几个字 (「死神」). */
        private const val MIN_SERIES_LENGTH = 2

        /** 副标题归一化后至少几个字: 「终章」这类两个字的太泛. */
        private const val MIN_PART_LENGTH = 3

        /** 往前传、续集走多远: 长寿系列有几百个条目, 走全了没必要. */
        private const val MAX_SERIES_NODES = 200

        /** 文件夹名里的集号范围 (`1-40`、`27~40`). */
        internal val EPISODE_RANGE = Regex("""(?<![0-9])\d{1,4}\s*[-~～至到]\s*\d{1,4}(?![0-9])""")

        /**
         * 条目 [subjectId] 的补搜; 不是「系列名 + 副标题」的写法、或系列关系图里没有它时为 null.
         * 只看表里的中文名与原名 (同系列别的作品在表里也只有这两个名字): 别名多是英文意译, 与「BLEACH」比出来的「Thousand」不是副标题.
         */
        fun of(subjectId: Int?, table: SeriesGraphTable): DriveSharePartSearch? {
            val id = subjectId ?: return null
            val node = table.nodeOf(id) ?: return null
            val names = listOf(node.nameCn, node.name).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            if (names.any { RE_EDIT.containsMatchIn(it) }) return null
            val ownNormalized = names.map(DriveNameParser::normalize)
            // 与本条目名字一字不差的 (同一段的剧场版、TV 版) 不是别的部. 只差标点的是别的季 (辉夜第一季与「辉夜…告白?～…」第二季), 照算
            val siblingNames = siblingsOf(id, table).flatMap { listOf(it.name, it.nameCn) }
                .filter { it.isNotBlank() && it.trim() !in names }
                .distinct()
            if (siblingNames.isEmpty()) return null
            val siblingNormalized = siblingNames.map(DriveNameParser::normalize)

            val series = LinkedHashSet<String>()
            val own = LinkedHashMap<String, String>()
            val others = LinkedHashSet<String>()
            for (name in names) {
                val cuts = siblingNames.map { it to commonPrefixEnd(name, it) }
                    .filter { (_, cut) -> DriveNameParser.normalize(name.substring(0, cut)).length >= MIN_SERIES_LENGTH }
                val best = cuts.maxOfOrNull { it.second } ?: continue
                series += DriveNameParser.normalize(name.substring(0, best))
                for (piece in piecesOf(name.substring(best))) {
                    val normalized = DriveNameParser.normalize(piece)
                    // 同系列别的作品的名字里也有的不算这一部的 (「千年血战篇」)
                    if (siblingNormalized.none { normalized in it }) own.putIfAbsent(normalized, piece)
                }
                for ((sibling, cut) in cuts) {
                    for (piece in piecesOf(sibling.substring(cut))) {
                        val normalized = DriveNameParser.normalize(piece)
                        if (ownNormalized.none { normalized in it }) others += normalized
                    }
                }
            }
            if (own.isEmpty() || series.isEmpty()) return null
            // 名字里写的季, 同系列别的作品也写了这一季的 (一季拆成两部) 不算
            val ownSeason = names.flatMapTo(HashSet()) { DriveNameParser.declaredSeasons(it) }.singleOrNull()
                ?.takeIf { season -> siblingNames.none { season in DriveNameParser.declaredSeasons(it) } }
            val roots = siblingNormalized.filter { root -> root.length >= MIN_SERIES_LENGTH && series.any { it.length > root.length && root in it } }
            return DriveSharePartSearch(
                own.values.take(MAX_SEARCHES), series.toList(), own.keys.toList(), others.toList(), ownSeason, roots.distinct(),
            )
        }

        /** 同系列 (顺着前传、续集能走到的) 别的条目. 比表新的条目为空. */
        private fun siblingsOf(subjectId: Int, table: SeriesGraphTable): List<SeriesNode> {
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
                    val normalized = DriveNameParser.normalize(piece)
                    val min = if (normalized.all { it in 'a'..'z' || it in '0'..'9' }) MIN_LATIN_PART_LENGTH else MIN_PART_LENGTH
                    normalized.length >= min && !piece.all { it.isDigit() }
                }

        /** 只有英文字母数字的副标题至少几个字. */
        private const val MIN_LATIN_PART_LENGTH = 6

        private fun isCjk(char: Char): Boolean = char in '぀'..'ヿ' || char in '㐀'..'鿿' || char in '豈'..'﫿'

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
         * 正篇文件夹里的 `01` 会被当成它的第 1 集. 不补搜.
         */
        private val RE_EDIT = Regex("""特[别別]編[集輯]|特[别別]编[集辑]|[总總総][集]?[篇編编]|特[别別][篇編编]""")
    }
}

/** 补搜来的 (见 [DriveSharePartSearch]): 记下后再列时也照补搜的规则认. */
internal fun DriveShareMatch.fromPartSearch(): DriveShareMatch = DriveShareMatch(share, file, folders, episode, partSearch = true)
