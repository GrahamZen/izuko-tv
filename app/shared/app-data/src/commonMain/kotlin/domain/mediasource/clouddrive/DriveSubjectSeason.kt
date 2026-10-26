/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.clouddrive

import me.him188.ani.app.data.models.subject.SplitSeason
import me.him188.ani.app.data.models.subject.SplitSeasonDetector
import me.him188.ani.app.data.network.SeriesGraphTable
import me.him188.ani.app.data.network.SeriesNode
import me.him188.ani.app.data.network.toSplitEntry
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.source.MediaFetchRequest

/**
 * 这个条目在网盘上按第几季找. 认部 ([DrivePathAttribution])、认集 ([DriveEpisodeResolver])、列文件夹时跳过别季 ([otherSeasonFolders])
 * 与分享站搜到的分享排先后都按这一份.
 *
 * 季 ([number]):
 * - 条目名写明的 ([declaredIn]), [declared] 为 true: 第 N 季 (N ≥ 2) 时不写季的文件只认系列绝对集号, 见 [DriveEpisodeResolver].
 * - 条目名没写时推出来的, [declared] 为 false: 只去掉写明是别的季的, 不写季的照留 (「进击的巨人 最终季 Part.2 / 01.mp4」).
 *   1. TMDB 对应表里这个条目的集全在同一季, 而且是第 2 季及以后 (「赛博朋克：边缘行者 2」是 S2; 特别篇的 S0 不算).
 *      表里记成第 1 季接着编号的续作很多 (咒术回战第二季是 S1E25 起), 说明不了网盘上写第几季, 不推.
 *   2. 系列关系图推出的季 ([seriesSeasonOf]; 「浪客剑心 京都动乱」TMDB 记成 S1E25 起, 网盘上写「第二季」).
 *      只在条目有 TMDB 逐集对位时用: 按 TMDB 整理的 `S01E25` 先按表认, 推出来的季只管按季写的文件.
 * - 都推不出来时为 null; 系列的第一部 (或只有一部, [firstWork]) 当第一季, 系列里后来的作品不知道是第几季.
 *
 * 拆分季 ([split], 见 [seriesSplitSeasonOf]) 的几段共有一季: 写了这一季的文件夹里几段都在, 分不出是哪一段.
 */
internal class DriveSubjectSeason(
    /** 条目名写明或推出来的季; 推不出时为 null */
    val number: Int?,
    val declared: Boolean = false,
    /** 系列的第一部或只有一部 (系列里没有前传): 推不出季时当第一季 */
    val firstWork: Boolean = true,
    val split: SplitSeason? = null,
    /** 对应表里本条目用到的 TMDB 季 */
    val tmdbSeasons: Set<Int> = emptySet(),
    /**
     * TMDB 把这个后来的作品单列成一部 (本篇从 S1E1 起): 网盘上的「S1」两种意思都有, 按 TMDB 整理的是这一部自己的第一季
     * (「堀与宫村 -piece- (2023) S01」), 按系列整理的是系列的第一季 (「堀与宫村 S1」与「堀与宫村 S2」并排).
     */
    val tmdbOwnShow: Boolean = false,
) {
    /** 认集时比的季: 推不出季的当第一季比 (拆出来的后半常放在第一季的文件夹里从 01 编) */
    val matchingNumber: Int get() = number ?: 1

    /** 认部时本条目的季: 推不出季的, 系列的第一部当第一季, 后来的作品不知道 (写了哪一季都说不清). */
    val ownNumber: Int? get() = number ?: if (firstWork) 1 else null

    /** 只写了 [seasons] 这几季的名字说的是这一部: 正是本季 ([ownNumber]), 又不是拆分季 (几段共有一季, 分不出是哪一段). */
    fun isOwn(seasons: Set<Int>): Boolean = ownNumber != null && seasons == setOf(ownNumber) && split == null

    /** 写了 [seasons] 这几季的文件夹里是另一套编号: 写了别的季 (不是 [ownNumber]), 或拆分季共有的季. */
    fun separates(seasons: Set<Int>): Boolean = seasons.isNotEmpty() && (seasons != setOfNotNull(ownNumber) || split != null)

    /**
     * 一层文件夹里不必往下列的那些: 这一层按季分好了 (至少两个文件夹各只写了一个季、且季不同), 其中季不是这个条目会写的季
     * (本季与对应表里这个条目用到的 TMDB 季: 按 TMDB 整理的网盘写成那一季, 如 TMDB 把整部算一季时的 `S01E78`) 的.
     * 按季分好的文件夹装的就是那一季, 里面没写季的视频最后也会按季去掉. 只有一个文件夹写了季时不跳: 常是上传者的总标题 (「…第二季」),
     * 里面可能连前几季一起放. 名字拿不准的 (见 [DriveNameParser.singleDeclaredSeason]) 当没写季.
     */
    fun otherSeasonFolders(): (List<String>) -> Set<String> {
        val seasons = setOf(matchingNumber) + tmdbSeasons
        return { names ->
            val declared = names.associateWith { DriveNameParser.singleDeclaredSeason(it) }
            if (declared.values.filterNotNull().distinct().size < 2) emptySet()
            else declared.filterValues { it != null && it !in seasons }.keys
        }
    }

    override fun toString(): String = when {
        number == null -> "unknown"
        declared -> "$number"
        else -> "$number (inferred)"
    }

    companion object {
        /**
         * @param tmdbEpisodes TMDB (季, 集) → 条目的集 (见 [TmdbEpisodeNumbering]), 没有对应表时为 null
         * @param identity 系列关系图里的位置 (推出的季、拆分季、是不是后来的作品); 用户指定的文件夹不给
         */
        fun of(request: MediaFetchRequest, tmdbEpisodes: Map<Pair<Int, Int>, EpisodeSort>?, identity: DriveSubjectIdentity?): DriveSubjectSeason {
            val tmdbSeasons = tmdbEpisodes?.keys?.mapTo(HashSet()) { it.first }.orEmpty()
            val laterWork = identity?.laterWork == true
            fun season(number: Int?, declared: Boolean) = DriveSubjectSeason(
                number, declared, firstWork = !laterWork, identity?.split, tmdbSeasons,
                tmdbOwnShow = laterWork && tmdbEpisodes?.containsKey(1 to 1) == true,
            )
            declaredIn(DriveSubjectMatcher.subjectNamesOf(request), aliases = identity?.hasPrequel ?: true)?.let { return season(it, declared = true) }
            if (tmdbEpisodes != null) {
                tmdbSeasons.minus(0).singleOrNull()?.takeIf { it >= 2 }?.let { return season(it, declared = false) }
                identity?.seriesSeason?.takeIf { it >= 2 }?.let { return season(it, declared = false) }
            }
            return season(null, declared = false)
        }

        /**
         * 条目名 [names] (中文名、原名在前, 后面是别名) 写明的季: 中文名或原名写了的按它; 都没写时别名写的季只有一个才算.
         * 别名里的季常是别的说法 (刀剑神域「War of Underworld 2nd Season」是异界战争的第二部分, 另一个别名写「第三季 第3部分」).
         * [aliases] 为 false (系列关系图里没有前传: 第一部或单独一部) 时不看别名: 那里的「第二部」是第二次改编 (「Kanon（第二部 BS-i版）」是 2006 年的版本).
         */
        fun declaredIn(names: List<String>, aliases: Boolean = true): Int? {
            names.take(PRIMARY_NAMES).firstNotNullOfOrNull { DriveNameParser.parseSubjectSeason(it) }?.let { return it }
            if (!aliases) return null
            return names.drop(PRIMARY_NAMES).mapNotNullTo(HashSet()) { DriveNameParser.parseSubjectSeason(it) }.singleOrNull()
        }

        /** 中文名与原名 */
        private const val PRIMARY_NAMES = 2
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

/**
 * 系列关系图里条目 [subjectId] 所在的拆分季 ([SplitSeasonDetector]: 一季拆成「第2部分」「Part.2」, 集号接着编).
 * 不是拆分季、不是 TV、不在表里时为 null.
 */
internal fun seriesSplitSeasonOf(subjectId: Int?, table: SeriesGraphTable): SplitSeason? {
    val self = subjectId?.let(table::nodeOf)?.takeIf { it.isTv } ?: return null
    val before = generateSequence(self) { tvPrequelOf(it.id, table) }.drop(1).take(MAX_SPLIT_NEIGHBORS).toList().asReversed()
    val after = generateSequence(self) { tvSequelOf(it.id, table) }.drop(1).take(MAX_SPLIT_NEIGHBORS).toList()
    return SplitSeasonDetector.detect(self.id, (before + self + after).map { it.toSplitEntry() })
}

/** 找拆分季时前后各看几部 TV. */
private const val MAX_SPLIT_NEIGHBORS = 4

private val SeriesNode.isTv: Boolean get() = "TV" in metaTags

private fun tvPrequelOf(subjectId: Int, table: SeriesGraphTable): SeriesNode? =
    table.edgesOf(subjectId)?.prequels.orEmpty().filter { it.isTv }.maxByOrNull { it.airDate }

private fun tvSequelOf(subjectId: Int, table: SeriesGraphTable): SeriesNode? =
    table.edgesOf(subjectId)?.sequels.orEmpty().filter { it.isTv }.minByOrNull { it.airDate }

private fun seasonNamedBy(node: SeriesNode): Int? =
    listOf(node.nameCn, node.name).firstNotNullOfOrNull { DriveNameParser.parseSubjectSeason(it) }
