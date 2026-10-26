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
import me.him188.ani.app.domain.mediasource.search.TitleIdentity
import me.him188.ani.app.domain.mediasource.search.TitleIdentity.Naming
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.source.MediaFetchRequest

/**
 * 网盘里一个视频的路径 (文件名、各层文件夹、最外层的分享标题或搜到的文件夹) 点名的是哪一部 ([attribute]), 认集前先看
 * (见 [DriveSubjectMatcher.matchEpisodes]). 从文件往上, 第一个点名了某一部的那一层说了算:
 * - 名字 ([TitleIdentity.namingOf]): 本条目的名字或副标题是这一部, 别的作品的名字或副标题是别的部, 都有的说不清;
 *   名字点名的是系列的第一部、却只写了本季 (第 2 季及以后) 的也算这一部: 后几季常拿第一季的名字加季起名 (「弦音 -风舞高中弓道部- 第二季」);
 *   点名系列以外的作品、同系列别的季与剧场版的不算 ([TitleIdentity.seasonCanOverride]);
 *   点名这一部的名字与外层按年份是同名的别的版本的, 是那一版 ([TitleIdentity.datesOtherVersion]: 「Kanon [2002] / Kanon 06.mkv」);
 *   系列的第一部: 没点名哪一部、写的年份是同系列别的作品的文件夹是那一部 ([TitleIdentity.datesOtherSeriesWork]);
 *   点名这一部而没写年份的, 外层第一个点名这一部又写了年份的写的是别的年份时是别的作品 ([TitleIdentity.datesOtherYearOutside]: 「怪物 / G 怪物 2024 / 怪物.E01.mp4」);
 * - 季与段 ([DriveSubjectSeason]): 只写了本季的是这一部, 外层第一个点名了某一部的名字点名的是系列以外认得的作品时除外
 *   (季号只在这个系列里算数: 「B 第二季 / S02E01.mkv」之于 A 的第二季); 本条目是拆分季的一段时, 写着几段共有的季或拆分季的季名、又写了第几段的
 *   (「S04 Part2」「进击的巨人 S04 最终季 part1」), 本段是这一部, 别的段是别的部. 文件名里的段不认: 常是集名的一部分 (「The Final Chapters (Part 1)」).
 *
 * 点名这一部的那一层与文件之间隔着另一套编号的文件夹时那个名字不算 (说不清): 写了别的季、拆分季共有的季 (「第3季」里袭击篇与反击篇都在)、
 * 本条目集号以外的集号范围 (「1-40」「31-60」), 或写着同系列别的作品的年份 (「吊带袜天使 (2010)」). 这类分享与文件夹按最新一部起名, 里面附着前几部
 * (「…祸进谭… 更新EP48 / 1-40 / EP 01.mp4」「…京都动乱；第二季… / 第一季[24集全] / [01].mp4」). 最外层不算隔开: 标题常写着几季.
 */
internal class DrivePathAttribution(
    private val identity: TitleIdentity,
    private val season: DriveSubjectSeason,
    request: MediaFetchRequest,
) {
    enum class Attribution {
        OWN,
        OTHER,

        /** 同时点名了几部、点名这一部的那一层被另一套编号的文件夹隔开, 或哪一部都没点名 */
        UNCLEAR,
    }

    /** 写了本季的名字点名别的部时也算这一部: 本季是第 2 季及以后、又不是拆分季. 第一季不算: 点名的那一部自己也是第一季 (「机动战士高达SEED … S01E01」). */
    private val seasonOverridesName = season.split == null && (season.ownNumber ?: 1) >= 2

    /** 拆分季的季名, 已归一化 */
    private val splitBaseNames = season.split?.baseNames.orEmpty().map(TitleForms::compact).filter { it.length >= 2 }

    /** 本条目的集号所在的范围: 条目内序号与系列连续集号 */
    private val ownRanges: List<IntRange> = request.episodes.filter { it.sort is EpisodeSort.Normal }
        .let { normal -> listOf(normal.mapNotNull { it.ep }, normal.map { it.sort }) }
        .map { sorts -> sorts.mapNotNull { (it as? EpisodeSort.Normal)?.number?.toInt() } }
        .filter { it.isNotEmpty() }
        .map { it.min()..it.max() }

    /** 文件 [fileName] (所在文件夹 [folders], 从外到里) 点名的是哪一部. */
    fun attribute(fileName: String, folders: List<String>): Attribution {
        val segments = listOf(fileName) + folders.asReversed()
        var separated = false
        for ((index, segment) in segments.withIndex()) {
            val numbering = numberingOf(segment, isFile = index == 0)
            val byName = identity.namingOf(segment)
            val naming = when (byName) {
                Naming.NONE -> numbering
                Naming.OTHER, Naming.SEVERAL ->
                    if (numbering == Naming.OWN && seasonOverridesName && identity.seasonCanOverride(segment)) Naming.OWN else byName
                Naming.OWN -> Naming.OWN
            }
            when (naming) {
                Naming.OWN -> return when {
                    separated -> Attribution.UNCLEAR
                    // 只写了季与段: 季号只在这个系列里算数, 外层点名的是系列以外认得的作品时是那一部 (「B 第二季 / S02E01.mkv」)
                    byName == Naming.NONE && namesWorkOutsideSeriesAbove(segments, index) -> Attribution.OTHER
                    identity.datesOtherVersion(segments.drop(index)) -> Attribution.OTHER
                    byName == Naming.OWN && identity.datesOtherYearOutside(segments.drop(index)) -> Attribution.OTHER
                    else -> Attribution.OWN
                }
                Naming.OTHER -> return Attribution.OTHER
                Naming.SEVERAL -> return Attribution.UNCLEAR
                Naming.NONE -> if (index in 1 until segments.lastIndex) {
                    if (identity.datesOtherSeriesWork(segment)) return Attribution.OTHER
                    if (separatesNumbering(segment)) separated = true
                }
            }
        }
        return Attribution.UNCLEAR
    }

    /** [index] 那一层之外第一个点名了某一部的名字点名的是系列以外认得的作品 */
    private fun namesWorkOutsideSeriesAbove(segments: List<String>, index: Int): Boolean =
        segments.drop(index + 1).firstOrNull { identity.namingOf(it) != Naming.NONE }?.let(identity::namesWorkOutsideSeries) == true

    /** 名字没点名哪一部时, 按写的季与段: 只写了本季、本段的算这一部, 写了别的段的算别的部. */
    private fun numberingOf(segment: String, isFile: Boolean): Naming {
        val declared = declaredSeasonsOf(segment, isFile)
        if (season.isOwn(declared)) return Naming.OWN
        val split = season.split ?: return Naming.NONE
        if (isFile || declared.isEmpty() && !namesSplit(segment)) return Naming.NONE
        if (declared.isNotEmpty() && declared != setOfNotNull(season.ownNumber)) return Naming.NONE
        val part = partNumberOf(segment) ?: return Naming.NONE
        return if (part == split.selfIndex + 1) Naming.OWN else Naming.OTHER
    }

    private fun declaredSeasonsOf(segment: String, isFile: Boolean): Set<Int> {
        val declared = DriveNameParser.declaredSeasons(segment)
        return if (isFile) declared + setOfNotNull(DriveNameParser.parseFile(segment).season) else declared
    }

    private fun namesSplit(segment: String): Boolean {
        val normalized = TitleForms.compact(segment)
        return splitBaseNames.any { it in normalized }
    }

    /**
     * 文件夹 [folder] 里是另一套编号: 写了别的季、拆分季共有的季、本条目集号以外的集号范围,
     * 或写着同系列别的作品的年份 ([TitleIdentity.datesOtherWork]).
     */
    private fun separatesNumbering(folder: String): Boolean {
        if (season.separates(DriveNameParser.declaredSeasons(folder)) || identity.datesOtherWork(folder)) return true
        return EPISODE_RANGE.findAll(folder).any { match ->
            val (from, to) = match.destructured.let { (a, b) -> a.toInt() to b.toInt() }
            from < to && !(YEAR.matches(match.groupValues[1]) && YEAR.matches(match.groupValues[2])) &&
                    ownRanges.none { from >= it.first - 1 && to <= it.last + RANGE_SLACK }
        }
    }

    /** 名字里写的拆分季的段 (「第2部分」「Part.2」「第2クール」); 没写或写了几段时为 null. */
    private fun partNumberOf(name: String): Int? =
        PART_NUMBER.findAll(name)
            .mapNotNull { match -> match.groupValues.drop(1).firstOrNull { it.isNotEmpty() }?.let(DriveNameParser::parseNumber) }
            .toSet()
            .singleOrNull()

    private companion object {
        /** 文件夹名里的集号范围 (`1-40`、`27~40`). */
        val EPISODE_RANGE = Regex("""(?<![0-9])(\d{1,4})\s*[-~～至到]\s*(\d{1,4})(?![0-9])""")

        /** 范围两头都是年份 (「2022-2025」) 的不是集号范围 */
        val YEAR = Regex("""19\d\d|20\d\d""")

        /** 集号范围比本条目的集号多出几集也算本条目的 (附带的特别篇、总集篇) */
        const val RANGE_SLACK = 2

        val PART_NUMBER = Regex(
            """第\s*([0-9]{1,2}|[一二两三四五六七八九十]{1,3})\s*(?:部分|クール)""" +
                    """|(?<![A-Za-z])[Pp][Aa][Rr][Tt]\s*\.?\s*([0-9]{1,2})(?![0-9])""" +
                    """|(?<![0-9])([0-9]{1,2})(?:st|nd|rd|th)\s*[Cc]our(?![A-Za-z])""",
        )
    }
}
