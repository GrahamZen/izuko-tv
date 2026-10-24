/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.clouddrive

import me.him188.ani.app.data.models.subject.SplitSeasonDetector
import me.him188.ani.app.data.network.SeriesGraphTable
import me.him188.ani.app.data.network.SeriesNode
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.source.MediaFetchRequest

/**
 * 网盘上按第几季找这个条目: 认季 ([DriveSubjectMatcher.matchEpisodes])、按季剪枝 ([DriveSubjectMatcher.otherSeasonFolders])
 * 与站点搜到的分享排先后都按它.
 *
 * - 条目名 (中文名、原名、别名) 写明的季, [declared] 为 true. 第 N 季 (N ≥ 2) 时不写季的文件只认系列绝对集号, 见 matchEpisodes 的季规则.
 * - 条目名没写季时推出来的, [declared] 为 false: 只去掉写明是别的季的文件, 不写季的照留 (「进击的巨人 最终季 Part.2 / 01.mp4」).
 *   1. TMDB 对应表里这个条目的集全在同一季, 而且是第 2 季及以后 (「赛博朋克：边缘行者 2」是 S2; 特别篇的 S0 不算).
 *      表里记成第 1 季接着编号的续作很多 (咒术回战第二季是 S1E25 起), 说明不了网盘上写第几季, 不推.
 *   2. 系列关系图推出的季 ([seriesSeasonOf]; 「浪客剑心 京都动乱」TMDB 记成 S1E25 起, 网盘上写「第二季」).
 *      只在条目有 TMDB 逐集对位时用: 按 TMDB 整理的 `S01E25` 先按表认, 推出来的季只管按季写的文件.
 *      TMDB 把条目单列成一部 (本篇从 S1E1 起, [tmdbOwnShow]) 时, 网盘上的「S1」两种意思都有: 按 TMDB 整理的是这一部自己的第一季
 *      (「堀与宫村 -piece- (2023) S01」), 按系列整理的是系列的第一季 (「堀与宫村 S1」与「堀与宫村 S2」并排), 见 matchEpisodes.
 *
 * 都推不出来时没有 ([of] 返回 null), 当第一季或只有一季.
 */
internal class DriveSubjectSeason(
    val number: Int,
    val declared: Boolean,
    /** 季是系列关系图推出来的, 而 TMDB 把这个条目单列成一部 (本篇从 S1E1 起) */
    val tmdbOwnShow: Boolean = false,
) {
    override fun toString(): String = if (declared) "$number" else "$number (inferred)"

    companion object {
        /**
         * @param tmdbEpisodes TMDB (季, 集) → 条目的集 (见 [TmdbEpisodeNumbering]), 没有对应表时为 null
         * @param seriesSeason 系列关系图推出的季 ([seriesSeasonOf])
         */
        fun of(request: MediaFetchRequest, tmdbEpisodes: Map<Pair<Int, Int>, EpisodeSort>?, seriesSeason: Int?): DriveSubjectSeason? {
            DriveSubjectMatcher.subjectNamesOf(request).firstNotNullOfOrNull { DriveNameParser.parseSubjectSeason(it) }
                ?.let { return DriveSubjectSeason(it, declared = true) }
            if (tmdbEpisodes == null) return null
            val tmdbSeason = tmdbEpisodes.keys.mapTo(HashSet()) { it.first }.minus(0).singleOrNull()
            tmdbSeason?.takeIf { it >= 2 }?.let { return DriveSubjectSeason(it, declared = false) }
            val number = seriesSeason?.takeIf { it >= 2 } ?: return null
            return DriveSubjectSeason(number, declared = false, tmdbOwnShow = (1 to 1) in tmdbEpisodes)
        }
    }
}

/**
 * 系列关系图推出的条目 [subjectId] 是第几季: 最近的 TV 前传 (挂着几个时取最晚开播的) 是系列的第一部 (自己没有 TV 前传) 时为 1、
 * 名字写明了第 k 季时为 k, 本条目是它的下一季. 本条目是前传拆出来的后半 ([SplitSeasonDetector]: 「第2部分」「Part.2」, 集号接着编)
 * 时与前传同一季, 不推. 前传的季也得推 (系列里更早还有几部) 时不推: 篇章多的系列 (鬼灭之刃各篇、进击的巨人最终季各段)
 * 按条目数出来的季与网盘上写的对不上. 本条目不是 TV、不在表里、没有 TV 前传时为 null.
 */
internal fun seriesSeasonOf(subjectId: Int?, table: SeriesGraphTable): Int? {
    val self = subjectId?.let(table::nodeOf)?.takeIf { it.isTv } ?: return null
    val prequel = tvPrequelOf(self.id, table) ?: return null
    val prequelSeason = seasonNamedBy(prequel) ?: if (tvPrequelOf(prequel.id, table) == null) 1 else return null
    if (SplitSeasonDetector.detect(self.id, listOf(prequel.toSplitEntry(), self.toSplitEntry())) != null) return null
    return prequelSeason + 1
}

private val SeriesNode.isTv: Boolean get() = "TV" in metaTags

private fun tvPrequelOf(subjectId: Int, table: SeriesGraphTable): SeriesNode? =
    table.edgesOf(subjectId)?.prequels.orEmpty().filter { it.isTv }.maxByOrNull { it.airDate }

private fun seasonNamedBy(node: SeriesNode): Int? =
    listOf(node.nameCn, node.name).firstNotNullOfOrNull { DriveNameParser.parseSubjectSeason(it) }

private fun SeriesNode.toSplitEntry() = SplitSeasonDetector.Entry(
    subjectId = id,
    names = listOf(nameCn, name).filter { it.isNotBlank() },
    firstSort = firstSort,
    episodeCount = episodes ?: 0,
    inlineSpecialCount = inlineSpecialCount,
)
