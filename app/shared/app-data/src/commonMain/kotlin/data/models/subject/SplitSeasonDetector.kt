/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.models.subject

import me.him188.ani.app.domain.mediasource.MediaListFilters

/**
 * 从系列主线识别 [SplitSeason]: Bangumi 把一季拆成的几个条目 ("第2部分", "后半", "袭击篇 / 反击篇"...).
 *
 * 上游由 Ani 服务端识别并随条目关系下发; 直连没有服务端, 用系列关系图里的主线条目在本地算, 结果与服务端相同
 * (见 SplitSeasonDetectorTest, 用的是上游记录的服务端输出), 只有长篇连载一处更保守. 规则:
 * - 名字末尾的分段标记 ([MARKER]) 去掉后是季名; 第一段的名字常常就是季名, 没有标记.
 * - 主线上两个条目, 后者第一集的 sort 正好接在前者后面 (前者 [Entry.firstSort] + [Entry.episodeCount]),
 *   季名有相同的, 并且至少一个带分段标记, 就是同一季的相邻两段. "第二季" 这类季度后缀不是分段标记, 所以第二季不会被当成第一季的后半.
 * - 没有分集数据 ([Entry.firstSort] 为 `null`) 的条目接不上, 宁可识别不出来.
 * - 有一段超过 [MAX_PART_EPISODES] 集的整串都不算: 那是长篇连载接着编号的篇章 (航海王 1155 集之后的 "埃鲁巴夫篇"), 不是拆开的一季.
 *   前面一段上千集、中间几十个特别篇, 选择器要合并页比它们加起来还长才认, 新篇章开播头几周整部连载的那一页就对不上了.
 *   也不能只去掉长的那段: 剩下几段的季内序号从篇章开头数, 在整部连载的页面上会选错集.
 * - 本季各段的名字或季名与主线上本季以外的条目同名的整串都不算. 同名按选择器认页名的口径, 去掉标点与空白 ("银魂." 与 "银魂"):
 *   选择器把与季名同名的页面当作本季的合并页, 按季内序号对集且不再按条目名排除, 那个条目的页面就会被当成本季选错集.
 */
object SplitSeasonDetector {
    /** 拆开的一季每段最多这么多集. */
    private const val MAX_PART_EPISODES = 100

    /**
     * 主线上的一个条目.
     *
     * @param names 条目的名字, 第一个是中文名或原名
     * @param firstSort 正片第一集的 sort; 没有分集数据时为 `null`
     * @param episodeCount 正片集数
     * @param inlineSpecialCount 正片中间的特别篇数, 见 [SplitSeason.Part.inlineSpecialCount]
     */
    data class Entry(
        val subjectId: Int,
        val names: List<String>,
        val firstSort: Int?,
        val episodeCount: Int,
        val inlineSpecialCount: Int = 0,
    )

    /**
     * [selfId] 所在的拆分季; 不是拆分季的一段时为 `null`. [mainLine] 是按播出顺序排列的主线条目, 包含 [selfId].
     */
    fun detect(selfId: Int, mainLine: List<Entry>): SplitSeason? {
        val analyzed = mainLine.map { Analyzed(it) }
        val group = groupOf(selfId, analyzed)
            ?.takeIf { parts -> parts.none { it.entry.episodeCount > MAX_PART_EPISODES } }
            ?: return null
        val groupIds = group.mapTo(HashSet()) { it.entry.subjectId }
        val baseNames = group.flatMap { it.baseNames }.distinct()
        val otherNames = analyzed
            .filter { it.entry.subjectId !in groupIds }
            .flatMapTo(HashSet()) { other -> other.entry.names.map { normalize(it) } }
        if ((group.flatMap { it.entry.names } + baseNames).any { normalize(it) in otherNames }) return null
        return SplitSeason(
            parts = group.map { part ->
                SplitSeason.Part(
                    subjectId = part.entry.subjectId,
                    names = part.entry.names,
                    markers = part.markers,
                    firstSort = part.entry.firstSort ?: 0,
                    episodeCount = part.entry.episodeCount,
                    inlineSpecialCount = part.entry.inlineSpecialCount,
                )
            },
            selfIndex = group.indexOfFirst { it.entry.subjectId == selfId },
            baseNames = baseNames,
            otherSeasonNumbers = analyzed
                .filter { it.entry.subjectId !in groupIds }
                .flatMap { other ->
                    other.entry.names.flatMap { SplitSeason.seasonNumbersOf(it) }.ifEmpty { listOf(1) }
                }
                .distinct()
                .sorted(),
        )
    }

    private class Analyzed(val entry: Entry) {
        /** 每个名字去掉分段标记后的季名, 与 [Entry.names] 一一对应 */
        val baseNamesByName: List<String>
        val markers: List<String>

        init {
            val bases = ArrayList<String>(entry.names.size)
            val found = ArrayList<String>()
            for (name in entry.names) {
                val match = MARKER.find(name)
                val base = match?.let { name.substring(0, it.range.first).trimEnd(*SEPARATORS) }
                if (match != null && !base.isNullOrBlank()) {
                    bases += base
                    found += match.groupValues[1]
                } else {
                    bases += name
                }
            }
            baseNamesByName = bases
            markers = found.distinct()
        }

        val baseNames: List<String> get() = baseNamesByName.distinct()

        val nextFirstSort: Int?
            get() = entry.firstSort?.takeIf { entry.episodeCount > 0 }?.plus(entry.episodeCount)
    }

    /** [selfId] 所在的那一串相邻分段, 至少两段; 不是拆分季时为 `null`. */
    private fun groupOf(selfId: Int, mainLine: List<Analyzed>): List<Analyzed>? {
        val self = mainLine.firstOrNull { it.entry.subjectId == selfId } ?: return null
        fun next(of: Analyzed): Analyzed? = mainLine.firstOrNull { it !== of && continues(of, it) }
        fun previous(of: Analyzed): Analyzed? = mainLine.firstOrNull { it !== of && continues(it, of) }

        val group = ArrayDeque<Analyzed>().apply { add(self) }
        generateSequence(previous(self)) { previous(it) }
            .takeWhile { it !in group }
            .forEach { group.addFirst(it) }
        generateSequence(next(self)) { next(it) }
            .takeWhile { it !in group }
            .forEach { group.addLast(it) }
        return group.toList().takeIf { it.size >= 2 }
    }

    /** [later] 是不是紧接着 [earlier] 的下一段. */
    private fun continues(earlier: Analyzed, later: Analyzed): Boolean {
        val expectedFirstSort = earlier.nextFirstSort ?: return false
        if (later.entry.firstSort != expectedFirstSort || later.entry.episodeCount <= 0) return false
        if (earlier.markers.isEmpty() && later.markers.isEmpty()) return false
        val earlierBases = earlier.baseNames.toHashSet()
        return later.baseNames.any { it in earlierBases }
    }

    /** 选择器比较页名的口径 ([MediaListFilters.specialEquals]). */
    private fun normalize(name: String): String = MediaListFilters.normalizeForCompare(name).lowercase()

    /** 季名与分段标记之间的空白. 季名自己结尾的 "-" "～" 是名字的一部分 ("86 -不存在的战区-"), 不能去掉. */
    private val SEPARATORS = charArrayOf(' ', '　')

    /**
     * 名字末尾的分段标记: "第2部分" / "第2クール" / "Part 2" / "Part.2" / "后半部分" / "後半クール" / "后半",
     * 以及空格隔开的篇名 "袭击篇" / "襲擊編" / "前編". 季度后缀 ("第二季", "2nd season") 与年份 ("(2021)") 不算.
     */
    private val MARKER = Regex(
        """(第\s*[0-9０-９一二三四五六七八九十]+\s*(?:部分|クール)""" +
                """|[Pp][Aa][Rr][Tt]\.?\s*[0-9]+""" +
                """|(?:后半|後半|前半)(?:部分|クール)?""" +
                """|(?<=[\s　])[一-鿿]{1,4}[篇編])$""",
    )
}
