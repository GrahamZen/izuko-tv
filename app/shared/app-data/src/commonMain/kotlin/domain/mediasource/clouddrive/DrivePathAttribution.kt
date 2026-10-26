/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.clouddrive

import me.him188.ani.app.domain.mediasource.clouddrive.DriveSubjectIdentity.Naming
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.source.MediaFetchRequest

/**
 * 网盘里一个视频的路径 (文件名、各层文件夹、最外层的分享标题或搜到的文件夹) 点名的是哪一部 ([attribute]), 认集前先看
 * (见 [DriveSubjectMatcher.matchEpisodes]). 从文件往上, 第一个点名了某一部的那一层说了算:
 * - 名字 ([DriveSubjectIdentity.namingOf]): 本条目的名字或副标题是这一部, 别的作品的名字或副标题是别的部, 都有的说不清;
 *   名字点名的是别的部、却只写了本季 (第 2 季及以后) 的也算这一部: 后几季常拿第一季的名字加季起名 (「弦音 -风舞高中弓道部- 第二季」);
 * - 季与段: 只写了本季的 (条目的季见 [DriveSubjectSeason], 系列的第一部推不出季时当第一季) 是这一部;
 *   本条目是拆分季的一段时, 写着几段共有的季或拆分季的季名、又写了第几段 (「S04 Part2」「进击的巨人 S04 最终季 part1」) 的,
 *   本段是这一部, 别的段是别的部. 文件名里的段不认: 常是集名的一部分 (「The Final Chapters (Part 1)」).
 *
 * 点名这一部的那一层与文件之间隔着另一套编号的文件夹时那个名字不算 (说不清): 写了别的季、拆分季共有的季 (「第3季」里袭击篇与反击篇都在),
 * 或本条目集号以外的集号范围 (「1-40」「31-60」). 这类分享与文件夹按最新一部起名, 里面附着前几部
 * (「…祸进谭… 更新EP48 / 1-40 / EP 01.mp4」「…京都动乱；第二季… / 第一季[24集全] / [01].mp4」). 最外层不算隔开: 标题常写着几季.
 */
internal class DrivePathAttribution(
    private val identity: DriveSubjectIdentity,
    request: MediaFetchRequest,
    subjectSeason: DriveSubjectSeason?,
) {
    enum class Attribution {
        OWN,
        OTHER,

        /** 同时点名了几部、点名这一部的那一层被另一套编号的文件夹隔开, 或哪一部都没点名 */
        UNCLEAR,
    }

    /** 本条目的季; 推不出时为 null */
    private val season: Int? = subjectSeason?.number ?: identity.seriesSeason ?: if (identity.laterWork) null else 1

    /**
     * 写了本季的名字点名别的部时也算这一部: 本季是第 2 季及以后、又不是拆分季 (几段共有一季). 第一季不算:
     * 点名的那一部自己也是第一季 (「机动战士高达SEED … S01E01」).
     */
    private val seasonOverridesName = identity.split == null && (season ?: 1) >= 2

    /** 拆分季的季名, 已归一化 */
    private val splitBaseNames = identity.split?.baseNames.orEmpty().map(DriveNameParser::normalize).filter { it.length >= 2 }

    private val normalEpisodes = request.episodes.filter { it.sort is EpisodeSort.Normal }.sortedBy { it.sort }
    private val ownSorts = normalEpisodes.mapTo(HashSet()) { it.sort }
    private val ownEps = normalEpisodes.mapNotNullTo(HashSet()) { it.ep }

    /** 本条目的集号所在的范围: 条目内序号与系列连续集号 */
    private val ownRanges: List<IntRange> = listOf(normalEpisodes.mapNotNull { it.ep }, normalEpisodes.map { it.sort })
        .map { sorts -> sorts.mapNotNull { (it as? EpisodeSort.Normal)?.number?.toInt() } }
        .filter { it.isNotEmpty() }
        .map { it.min()..it.max() }

    /** 文件 [fileName] (所在文件夹 [folders], 从外到里) 点名的是哪一部. */
    fun attribute(fileName: String, folders: List<String>): Attribution {
        val segments = listOf(fileName) + folders.asReversed()
        var separated = false
        for ((index, segment) in segments.withIndex()) {
            val numbering = numberingOf(segment, isFile = index == 0)
            val naming = when (val byName = identity.namingOf(segment)) {
                Naming.NONE -> numbering
                Naming.OTHER, Naming.SEVERAL -> if (numbering == Naming.OWN && seasonOverridesName) Naming.OWN else byName
                Naming.OWN -> Naming.OWN
            }
            when (naming) {
                Naming.OWN -> return if (separated) Attribution.UNCLEAR else Attribution.OWN
                Naming.OTHER -> return Attribution.OTHER
                Naming.SEVERAL -> return Attribution.UNCLEAR
                Naming.NONE -> if (index in 1 until segments.lastIndex && separatesNumbering(segment)) separated = true
            }
        }
        return Attribution.UNCLEAR
    }

    /**
     * 说不清是哪一部的文件, 集号 [episode] 是这一部的跨部连续编号 (系列连续集号: 「死神 千年血战篇 / 27.mkv」之于相克谭),
     * 而且路径里离文件最近、提到这个系列的那一层点的是系列名 ([DriveSubjectIdentity.mentionOf]). 同一个分享里常附着系列的第一部
     * (「死神系列动漫 TV版 / 1-30 / 027.mkv」是 2004 年 TV 版的第 27 集), 它从 1 编到几百, 与后几部的连续编号对得上, 不算.
     * 集号也是这一部的条目内序号时分不清是哪一种编号, 只在所在文件夹按连续编号排 ([continuousFolder]: 里面有只能是连续编号的集号) 时算.
     */
    fun continuesSeries(fileName: String, folders: List<String>, episode: EpisodeSort, continuousFolder: Boolean): Boolean {
        if (episode !in ownSorts || (episode in ownEps && !continuousFolder)) return false
        return (listOf(fileName) + folders.asReversed()).firstNotNullOfOrNull { identity.mentionOf(it) } == true
    }

    /** 集号 [episode] 只能是这一部的跨部连续编号: 是系列连续集号, 不是条目内序号. 文件夹里有这样的集号, 这个文件夹就是按连续编号排的. */
    fun isContinuous(episode: EpisodeSort): Boolean = episode in ownSorts && episode !in ownEps

    /**
     * 本条目是拆分季的一段, 文件按几段共有的那一季编号 (写的季 [fileSeason] 正是本季, 第 [seasonEpisode] 集): 落在本段 (前几段之后的
     * [me.him188.ani.app.data.models.subject.SplitSeason.Part.episodeCount] 集) 里的是本段的哪一集; 不是本段的或认不出时为 null.
     * 对应表把整部算一季接着排时 (Re:Zero 的 S1E26 起) 靠它认按季写的 `S02E05`.
     */
    fun splitSeasonEpisode(fileSeason: Int?, seasonEpisode: Int?): EpisodeSort? {
        val split = identity.split ?: return null
        if (fileSeason == null || seasonEpisode == null || fileSeason != season) return null
        val index = seasonEpisode - split.previousPartsEpisodeCount
        return normalEpisodes.getOrNull(index - 1)?.takeIf { index <= split.self.episodeCount }?.sort
    }

    /** 名字没点名哪一部时, 按写的季与段: 只写了本季、本段的算这一部, 写了别的段的算别的部. */
    private fun numberingOf(segment: String, isFile: Boolean): Naming {
        val declared = declaredSeasonsOf(segment, isFile)
        if (declared.isNotEmpty() && declared != setOfNotNull(season)) return Naming.NONE
        val split = identity.splitPart ?: return if (declared.isNotEmpty()) Naming.OWN else Naming.NONE
        if (isFile || (declared.isEmpty() && !namesSplit(segment))) return Naming.NONE
        val part = partNumberOf(segment) ?: return Naming.NONE
        return if (part == split) Naming.OWN else Naming.OTHER
    }

    private fun declaredSeasonsOf(segment: String, isFile: Boolean): Set<Int> {
        val declared = DriveNameParser.declaredSeasons(segment)
        return if (isFile) declared + setOfNotNull(DriveNameParser.parseFile(segment).season) else declared
    }

    private fun namesSplit(segment: String): Boolean {
        val normalized = DriveNameParser.normalize(segment)
        return splitBaseNames.any { it in normalized }
    }

    /** 文件夹 [folder] 里是另一套编号: 写了别的季、拆分季共有的季, 或本条目集号以外的集号范围. */
    private fun separatesNumbering(folder: String): Boolean {
        val declared = DriveNameParser.declaredSeasons(folder)
        if (declared.isNotEmpty() && (declared != setOfNotNull(season) || identity.split != null)) return true
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
