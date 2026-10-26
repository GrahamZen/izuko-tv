/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.network

import me.him188.ani.app.data.models.subject.SplitSeason
import me.him188.ani.app.data.models.subject.SplitSeasonDetector
import me.him188.ani.app.data.models.subject.SubjectSeriesInfo
import me.him188.ani.app.domain.mediasource.MediaListFilters

/**
 * 系列主线上各部 TV 是第几季, 推得出来的才有 (条目 id → 季).
 *
 * 第一部 TV 是第 1 季 (名字写了季就按写的). 之后每一部: 名字写了季 ([SplitSeason.seasonNumbersOf]) 的按写的;
 * 没写的只在名字是第一部的名字再加一两个字时 (「继 怪怪守护神」「更多 出包王女」「某科学的超电磁炮S」) 算上一季的下一季.
 * 带篇名的 (「鬼灭之刃 无限列车篇」) 站点多按篇名叫, 算第几季各站说法不一, 推到这里就停, 后面的都不推;
 * 名字里没有第一部名字的 (「伪物语」之于「化物语」) 同样停. 与上一部是同一季拆开的两段 ([SplitSeasonDetector]) 时与上一部同季, 不单列.
 * 第一部之后一部都推不出时没有 (只知道第一季是第一季没用).
 */
internal fun SubjectRelationIndex.seriesSeasons(): Map<Int, Int> {
    val nodes = seriesMainNodes.associateBy { it.id }
    val tv = seriesMainSubjectIds.mapNotNull { nodes[it] }.filter { TV_TAG in it.metaTags }
    if (tv.size < 2) return emptyMap()
    val root = tv.first()
    val rootName = MediaListFilters.normalizeForCompare(root.displayName)
    if (rootName.isEmpty()) return emptyMap()
    val result = LinkedHashMap<Int, Int>()
    var season = root.declaredSeason() ?: 1
    result[root.id] = season
    for (index in 1 until tv.size) {
        val node = tv[index]
        if (SplitSeasonDetector.detect(node.id, listOf(tv[index - 1].toSplitEntry(), node.toSplitEntry())) != null) continue
        val next = node.declaredSeason() ?: run {
            val name = MediaListFilters.normalizeForCompare(node.displayName)
            val rest = name.replaceFirst(rootName, "", ignoreCase = true)
            if (rest == name || rest.length !in 1..MAX_SEQUEL_MARK_LENGTH) null else season + 1
        }
        if (next == null || next <= season) break
        season = next
        result[node.id] = season
    }
    return if (result.size < 2) emptyMap() else result
}

/** [seriesSeasons] 推出的「系列名 第N季」: [own] 是本条目的, [others] 是同系列别的各季的, 其中续作的也在 [sequels]. */
internal class SeriesSeasonNames(val own: List<String>, val others: List<String>, val sequels: List<String>) {
    companion object {
        val Empty = SeriesSeasonNames(emptyList(), emptyList(), emptyList())
    }
}

/**
 * 条目 [subjectId] 与同系列别的各季的「系列名 第N季」(系列名取第一部 TV 的中文名). Bangumi 不一定登了这种别名,
 * 站点给后面各季起名却多这样写 (「怪怪守护神第二季」): 有了它们, 本季的页面认得出, 别季的页面也会按季号排除.
 * 第一季只在系列里推出了别的季时才有自己的「第1季」.
 */
internal fun SubjectRelationIndex.seasonNamesOf(subjectId: Int): SeriesSeasonNames {
    val seasons = seriesSeasons()
    val own = seasons[subjectId] ?: return SeriesSeasonNames.Empty
    val nodes = seriesMainNodes.associateBy { it.id }
    val rootName = seriesMainSubjectIds.firstNotNullOfOrNull { id -> nodes[id]?.takeIf { TV_TAG in it.metaTags } }?.displayName
        ?: return SeriesSeasonNames.Empty
    fun nameOf(season: Int) = "$rootName 第${season}季"
    val others = seasons.filter { (id, season) -> id != subjectId && season != own }
    val sequels = sequelSubjects.toSet()
    return SeriesSeasonNames(
        own = listOf(nameOf(own)),
        others = others.values.distinct().map(::nameOf),
        sequels = others.filterKeys { it in sequels }.values.distinct().map(::nameOf),
    )
}

/**
 * 带上 [seasonNamesOf] 推出的名字: 本条目的作 [SubjectSeriesInfo.seasonAliases], 别的季的并进系列名 (续作的也并进续集名).
 * 与本条目某个名字相同的不并 (同 [SubjectSeriesInfo.compute]).
 */
fun SubjectSeriesInfo.withSeasonNames(index: SubjectRelationIndex, subjectId: Int, subjectNames: List<String>): SubjectSeriesInfo {
    val names = index.seasonNamesOf(subjectId)
    if (names.own.isEmpty()) return this
    fun isOwn(name: String) = (subjectNames + names.own).any { MediaListFilters.specialEquals(it, name) }
    return copy(
        seasonAliases = names.own.filter { alias -> subjectNames.none { MediaListFilters.specialEquals(it, alias) } },
        seriesSubjectNamesWithoutSelf = seriesSubjectNamesWithoutSelf + names.others.filterNot(::isOwn),
        sequelSubjectNames = sequelSubjectNames + names.sequels.filterNot(::isOwn),
    )
}

/** 拆分季识别 ([SplitSeasonDetector]) 用的条目. */
internal fun SeriesNode.toSplitEntry() = SplitSeasonDetector.Entry(
    subjectId = id,
    names = listOf(nameCn, name).filter { it.isNotBlank() },
    firstSort = firstSort,
    episodeCount = episodes ?: 0,
    inlineSpecialCount = inlineSpecialCount,
)

private const val TV_TAG = "TV"

/** 续作名字比第一部多出的字不超过这么多时才算「下一季」 (「继」「更多」「S」「II」). */
private const val MAX_SEQUEL_MARK_LENGTH = 2

private val SeriesNode.displayName: String get() = nameCn.ifBlank { name }

private fun SeriesNode.declaredSeason(): Int? =
    listOf(nameCn, name).firstNotNullOfOrNull { SplitSeason.seasonNumbersOf(it).singleOrNull() }
