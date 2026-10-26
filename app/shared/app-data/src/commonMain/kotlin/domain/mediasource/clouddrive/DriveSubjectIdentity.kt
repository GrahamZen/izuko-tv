/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.clouddrive

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.him188.ani.app.data.models.subject.SplitSeason
import me.him188.ani.app.data.network.SeriesGraphTable
import me.him188.ani.app.data.network.SeriesGraphTableRepository
import me.him188.ani.app.data.network.SeriesNode
import me.him188.ani.app.domain.mediasource.rss.firstAirDate
import me.him188.ani.app.domain.mediasource.search.AnimeNameIndex
import me.him188.ani.app.domain.mediasource.search.SubjectSearchPlan
import me.him188.ani.app.domain.mediasource.search.TitleForms
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.source.MediaFetchRequest
import kotlin.math.abs

/**
 * 网盘上的一段名字 (分享站的标题、合集与分享里的文件夹名、文件名) 点名的是不是这一部. 自动认出来的地方都按它:
 * 分享站的标题 ([rejectsTitle])、固定合集与播放线索里的文件夹 ([namesOtherWork])、分享与自己网盘里的视频 ([DrivePathAttribution]);
 * 用户添加的分享、手挑的文件夹是用户指定给这个条目的, 不查.
 *
 * 名字取自搜索计划 ([SubjectSearchPlan]), 与网页类数据源认部是同一组: [SubjectSearchPlan.ownNames] 是这一部的;
 * 别的作品是 [SubjectSearchPlan.otherWorkNames], 以及系列关系图里以本条目的主标题开头的 ([SeriesGraphTable.nodesContaining]:
 * 「机动战士高达」→「机动战士高达SEED」「机动战士高达 水星的魔女」) 与顺着前传续集走得到的 (含支线上的剧场版、总集篇);
 * 本条目名字里含着的是系列名 (「死神 千年血战篇」之于它的各部), 谁也不点名. 一段名字里同时有几部的名字时最长的那个说了算:
 * 「机动战士高达SEED」是 SEED; 「XX 第三季 (附第二季)」里有本条目同样长的名字, 不算别的作品.
 * 「系列名 + 副标题」的条目再按副标题认 ([DriveSeriesParts]): 只有这一部的副标题是这一部, 只有别的部的副标题是别的部, 都有的是几部合在一起.
 * 别的部的副标题出现在本条目自己的名字里的不算: 后几季常拿第一季的副标题当系列名 (「ツルネ ―風舞高校弓道部― 第2期」).
 * 系列的第一部的名字每一处后面都接着标题的另一段的 (「Sword Art Online Alicization」, 见 [DriveTitleContinuation]) 是同系列别的作品.
 * 太短的名字 (「AIR」「怪物」) 单独成词时才算点名这一部 ([DriveTitleContinuation.standsAlone]): 「AIR GEAR」「怪物事变」「谷江玲音」里的不算.
 * 系列以外的作品按名字相等认 ([DriveKnownWorks], 全部动画的名字表): 写着别的动画的名字的是那一部 (「一杆青空」之于 AIR),
 * 同时写着本条目或这个系列的是几部的合集 (「咒术回战1-2季全+紫罗兰永恒花园全集」). 同名的别的版本 (旧版、重制) 只能按年份分:
 * 写着那一版开播的年份、没写本条目那年的是那一版 ([datesOtherVersion]). 系列的第一部的名字出现了、写的年份却都差一年以上的也是别的作品
 * ([isOtherYear]: 「红辣椒俱乐部 (2019)」之于 2006 年的红辣椒).
 */
internal class DriveSubjectIdentity(
    /** 本条目的名字, 已归一化 ([TitleForms.compact]); 太短、与系列名或别的作品同名的不在里面 */
    private val ownNames: List<String>,
    /** 别的作品的名字, 已归一化 */
    internal val otherWorkNames: List<String>,
    /** 「系列名 + 副标题」的写法; 不是时为 null */
    val parts: DriveSeriesParts? = null,
    /** 系列名, 已归一化: 网盘把几部放在一起时用它起名 */
    private val seriesNames: List<String> = emptyList(),
    /** 同系列里自己的集号与本条目的连续集号重叠的作品的名字 (「死神」2004 年的 TV 版之于千年血战篇各部), 已归一化 */
    private val rootNames: List<String> = emptyList(),
    /** 本条目所在的拆分季 (见 [seriesSplitSeasonOf]); 不是拆分季时为 null */
    val split: SplitSeason? = null,
    /** 系列关系图推出的本条目是第几季 ([seriesSeasonOf]), 认季用 (见 [DriveSubjectSeason]) */
    val seriesSeason: Int? = null,
    /** 本条目的开播年份; 不知道时为 null */
    private val airYear: Int? = null,
    /** 同系列别的作品开播的年份, 与本条目差不到一年的不在里面 */
    private val otherWorkYears: Set<Int> = emptySet(),
    /**
     * 本条目是系列里后来的作品 (有前传, 名字里含着系列名, 或名字写明了第 2 季及以后): 整个系列的合集里它的集与前几部编号相同,
     * 路径认不出是这一部的视频只认集号上的证据 (见 [DriveSubjectMatcher.matchEpisodes]).
     */
    val laterWork: Boolean = false,
    /** 系列关系图里本条目有前传: 别名写的季才算 (见 [DriveSubjectSeason.declaredIn]) */
    val hasPrequel: Boolean = false,
    /** 本条目的全部名字原样出现的样子: 认第一部的名字后面接着的标题 (后来的作品不查), 认 [weakNames] 是不是单独成词 */
    private val titles: DriveTitleContinuation? = null,
    /** 本条目太短的名字 ([TitleForms.isDistinctive]), 已归一化: 单独成词时才算点名这一部 ([DriveTitleContinuation.standsAlone]) */
    private val weakNames: List<String> = emptyList(),
    /** 系列以外认得的别的作品; 没有全部动画的名字索引时为 null */
    private val knownWorks: DriveKnownWorks? = null,
    /** 与本条目同名的别的版本 (旧版、重制) 开播的年份, 与本条目差不到一年的不在里面 */
    private val versionYears: Set<Int> = emptySet(),
) {
    /** 一段名字点名了哪一部. */
    enum class Naming {
        OWN,
        OTHER,

        /** 同时点名了这一部与别的部 (「诀别谭+相克谭+死神全集」) */
        SEVERAL,
        NONE,
    }

    /** 名字 [name] 点名了哪一部, 见类说明. [byYear] 为 false 时不按年份认 (网页站点标的年份常不对). */
    fun namingOf(name: String, byYear: Boolean = true): Naming {
        val normalized = TitleForms.compact(name)
        val ownHits = ownHitsIn(name, normalized)
        val otherHits = otherWorkNames.filter { it in normalized }
        val continued = !laterWork && ownHits.isNotEmpty() && titles?.continuesEvery(name, ownHits) == true
        val own = !continued && ownHits.any { hit -> otherHits.none { it.length > hit.length && hit in it } } ||
                parts?.ownParts?.any { it in normalized } == true
        val other = continued || otherHits.any { hit -> ownHits.none { it.length >= hit.length } } ||
                parts?.otherParts?.any { it in normalized } == true
        if (!other) namingByKnownWorks(name, normalized, ownHits)?.let { return it }
        return when {
            own && other -> Naming.SEVERAL
            own && byYear && (datesOtherVersion(listOf(name)) || isOtherYear(name)) -> Naming.OTHER
            own -> Naming.OWN
            other -> Naming.OTHER
            else -> Naming.NONE
        }
    }

    /**
     * 文件夹路径 [path] (从外到里) 是别的作品的: 从里往外第一个点名了某一部的名字点名的是别的部, 或点名这一部、外层写着同名的别的版本的年份
     * ([datesOtherVersion]). 播放线索、固定合集里的文件夹用.
     */
    fun namesOtherWork(path: List<String>): Boolean {
        val inward = path.asReversed()
        for ((index, segment) in inward.withIndex()) {
            when (namingOf(segment)) {
                Naming.NONE -> continue
                Naming.OWN -> return datesOtherVersion(inward.drop(index))
                Naming.OTHER -> return true
                Naming.SEVERAL -> return false
            }
        }
        return false
    }

    /**
     * 名字 [name] 提到了这个系列: 有系列名时为 true, 只有集号与本条目重叠的作品的名字 ([rootNames]) 时为 false, 都没有时为 null.
     * 跨部连续编号的集号只在点的是系列名时算数, 见 [DrivePathAttribution.continuesSeries].
     */
    fun mentionOf(name: String): Boolean? {
        val normalized = TitleForms.compact(name)
        return when {
            seriesNames.any { it in normalized } -> true
            rootNames.any { it in normalized } -> false
            else -> null
        }
    }

    /**
     * 标题 [title] 写的年份是不是别的作品的: 只看系列的第一部, 标题写了年份而且都与开播年份差一年以上.
     * 续作不看年份 (整部的合集常按第一季的年份写); 重制版写的是重制那年, 也不看.
     */
    fun isOtherYear(title: String): Boolean {
        val airYear = airYear?.takeIf { !laterWork } ?: return false
        if (REMASTER.containsMatchIn(title)) return false
        val years = yearsIn(title)
        return years.isNotEmpty() && years.none { abs(it - airYear) <= 1 }
    }

    /**
     * 文件夹 [folder] 提到了这个系列 ([mentionOf]), 写的年份却是同系列别的作品开播的那一年、不是本条目的 (「吊带袜天使 (2010)」之于 2025 年的新作):
     * 里面多半是那一部, 外层点名这一部的名字不算 (见 [DrivePathAttribution]). 只当隔开、不当别的部: 整个系列的文件夹常写第一季的年份.
     */
    fun datesOtherWork(folder: String): Boolean {
        val airYear = airYear ?: return false
        if (otherWorkYears.isEmpty() || mentionOf(folder) == null) return false
        val years = yearsIn(folder)
        return years.any { it in otherWorkYears } && years.none { abs(it - airYear) <= 1 }
    }

    /**
     * 系列的第一部: 文件夹 [folder] 写的年份都是同系列别的作品开播的那一年、不是本条目的 (「04 初吻不会结束（2023）全4集」之于第一季),
     * 名字没点名哪一部时就是那一部. 后来的作品不看: 整个系列的文件夹常写第一季的年份 (只当隔开, 见 [datesOtherWork]).
     */
    fun datesOtherSeriesWork(folder: String): Boolean {
        val airYear = airYear?.takeIf { !laterWork } ?: return false
        val years = yearsIn(folder)
        return years.isNotEmpty() && years.all { it in otherWorkYears } && years.none { abs(it - airYear) <= 1 }
    }

    /**
     * 路径 [segments] (从里往外) 按年份是同名的别的版本: 名字一样, 只能按年份分版本. 从里往外第一个写着本条目或别的版本开播那年的名字说了算
     * (「Kanon [2002][东映动画] / [ReinForce] Kanon 06.mkv」之于 2006 年版).
     */
    fun datesOtherVersion(segments: List<String>): Boolean {
        val airYear = airYear ?: return false
        if (versionYears.isEmpty()) return false
        for (segment in segments) {
            val years = yearsIn(segment)
            if (years.any { abs(it - airYear) <= 1 }) return false
            if (years.any { it in versionYears }) return true
        }
        return false
    }

    /** 名字里写的开播年份; 日期 (上传、更新的「2025-04-02」「2025.06」) 与发行年份 (「2023 BDRip」) 不算 */
    private fun yearsIn(name: String): List<Int> =
        YEAR.findAll(RELEASE_YEAR.replace(DATE.replace(name, " "), " ")).map { it.value.toInt() }.toList()

    /** 名字 [name] (归一化后是 [normalized]) 里出现的本条目的名字: 太短的要单独成词 */
    private fun ownHitsIn(name: String, normalized: String): List<String> =
        ownNames.filter { it in normalized } + weakNames.filter { it in normalized && titles?.standsAlone(name, it) == true }

    /**
     * 按认得的别的作品 ([knownWorks]) 认: 与本条目、这个系列写在一起的是几部的合集 (「咒术回战1-2季全+紫罗兰永恒花园全集」),
     * 本条目、系列的名字只出现在别的作品的名字里的是那一部 (「Re：从零开始的异世界生活 新编集版」). 名字里没有认得的别的作品时为 null.
     */
    private fun namingByKnownWorks(name: String, normalized: String, ownHits: List<String>): Naming? {
        val known = knownWorks?.otherWorkNamesIn(name).orEmpty()
        if (known.isEmpty()) return null
        val besides = (ownHits + seriesNames + rootNames + parts?.ownParts.orEmpty())
            .any { it in normalized && known.none { work -> it in work } }
        return if (besides) Naming.SEVERAL else Naming.OTHER
    }

    /**
     * 网页站点上的条目名 [title] 不是这一部 ([me.him188.ani.app.domain.mediasource.search.SubjectSearchPlan.identify]):
     * 名字是系列以外认得的别的作品 (「怪物转生」「WWW.迷糊餐厅」), 或只含着本条目太短的名字、没单独成词 (「忧郁的物怪庵」之于物怪、「Fairy」之于 AIR).
     * 同系列的季与拆分季的段照网页自己的认法 (站点常把几段放在一页), 不按年份认 (站点标的年份常不对: 「【2000新番】真实之泪」).
     */
    fun rejectsPageTitle(title: String): Boolean {
        val normalized = TitleForms.compact(title)
        val ownHits = ownHitsIn(title, normalized)
        namingByKnownWorks(title, normalized, ownHits)?.let { return it == Naming.OTHER }
        return ownHits.isEmpty() && weakNames.any { it in normalized }
    }

    /**
     * 分享站的标题是别的作品的: 名字点名了别的部, 年份不对, 或写明是短剧 ([DriveNameParser.WorkKind.SHORT_DRAMA]).
     * 按太短的名字 ([weakNames]) 搜到的 ([keyword]) 还得点名这一部: 「杀死Air（91集）」只是含着这几个字.
     */
    fun rejectsTitle(title: String, keyword: String? = null): Boolean {
        val naming = namingOf(title)
        return naming == Naming.OTHER || naming == Naming.NONE && keyword != null && TitleForms.compact(keyword) in weakNames ||
                isOtherYear(title) || DriveNameParser.WorkKind.SHORT_DRAMA in DriveNameParser.workKindsOf(title)
    }

    companion object {
        /** 1950 到 2049 年, 前后不挨着别的数字 */
        private val YEAR = Regex("""(?<!\d)(19[5-9]\d|20[0-4]\d)(?!\d)""")

        /** 紧挨着发行介质的年份 (「2023 BDRip」「BD 2023」) */
        private val RELEASE_YEAR = Regex(
            """(?<!\d)(?:19|20)\d\d[ ._-]*(?:BD|BDRip|Blu-?Ray|DVD(?:Rip)?|WEB(?:-?DL|Rip)?|Remux|蓝光|原盘)(?![A-Za-z])""" +
                    """|(?<![A-Za-z])(?:BD|BDRip|Blu-?Ray|DVD(?:Rip)?|WEB(?:-?DL|Rip)?|蓝光|原盘)[ ._-]*(?:19|20)\d\d(?!\d)""",
            RegexOption.IGNORE_CASE,
        )

        /** 年月 (日): 月份是 1 到 12, 后面不挨着数字 (「2005-2014」是年份范围) */
        private val DATE = Regex("""(?<!\d)(?:19|20)\d\d\s*[-./年]\s*(?:0?[1-9]|1[0-2])(?!\d)(?:\s*[-./月]\s*(?:0?[1-9]|[12]\d|3[01])(?!\d)\s*日?|\s*月)?""")
        private val REMASTER = Regex("""重制|重製|修复|修復|remaster""", RegexOption.IGNORE_CASE)

        /**
         * 同系列里自己的集号与本条目的系列连续集号重叠的作品的名字, 已归一化 (「死神」2004 年 TV 版的 1~366 集之于相克谭的 27~40):
         * 只点了这些名字的文件夹里, 与本条目连续集号相同的集是那一部的.
         */
        private fun overlappingWorkNames(request: MediaFetchRequest, subjectId: Int?, table: SeriesGraphTable): List<String> {
            val sorts = request.episodes.mapNotNull { (it.sort as? EpisodeSort.Normal)?.number?.toInt() }
            if (subjectId == null || sorts.isEmpty()) return emptyList()
            return DriveSeriesParts.seriesNodesOf(subjectId, table)
                .filter { node ->
                    val first = node.firstSort ?: 1
                    first <= sorts.max() && first + (node.episodes ?: 0) - 1 >= sorts.min()
                }
                .flatMap { listOf(it.name, it.nameCn) }
                .map(TitleForms::compact)
                .filter { it.length >= MIN_SERIES_NAME_LENGTH }
                .distinct()
        }

        /** 系列名归一化后至少几个字 (「死神」) */
        private const val MIN_SERIES_NAME_LENGTH = 2

        /** 短名字归一化后至少几个字 */
        private const val MIN_WEAK_NAME_LENGTH = 2

        /** @param index 全部动画的名字索引, 认系列以外的作品用; 不给时不认 */
        fun of(request: MediaFetchRequest, table: SeriesGraphTable, index: AnimeNameIndex? = null): DriveSubjectIdentity {
            val subjectId = request.subjectId.toIntOrNull()
            val names = DriveSubjectMatcher.subjectNamesOf(request)
            val ownNode = subjectId?.let(table::nodeOf)
            val plan = SubjectSearchPlan.of(request)
            val seriesNames = plan.seriesNames.map(TitleForms::compact).filter { it.isNotEmpty() }
            val ownTitles = plan.ownNames + listOfNotNull(ownNode?.name, ownNode?.nameCn)
            val own = ownTitles
                .map(TitleForms::compact)
                .filter { it.isNotEmpty() }
                .distinct()
            // 用主标题 (去掉季与副标题) 找: 「葬送的芙莉莲 第二季」的别的作品是所有以「葬送的芙莉莲」开头的作品;
            // 再加上同系列 (前传、续集能走到的) 的作品, 本条目名字里含着的 (系列名) 不算
            val bases = names.map(TitleForms::mainTitle).filter { TitleForms.compact(it).length >= 2 }.distinct()
            val startingWithBase = bases.flatMap { base ->
                val normalizedBase = TitleForms.compact(base)
                (table.nodesContaining(base).filter { it.id != subjectId && it.inSeries(table) }.flatMap { listOf(it.name, it.nameCn) } + plan.otherWorkNames)
                    .map(TitleForms::compact)
                    .filter { it.length > normalizedBase.length && normalizedBase in it }
            }
            val inSeries = (subjectId?.let { DriveSeriesParts.seriesNodesOf(it, table) }.orEmpty().flatMap { listOf(it.name, it.nameCn) } + plan.otherWorkNames)
                .map(TitleForms::compact)
                .filter { name -> TitleForms.isDistinctive(name) && own.none { name in it } }
            val others = (startingWithBase + inSeries).filter { it !in own }.distinct()
            val hasPrequel = subjectId?.let(table::edgesOf)?.prequels?.isNotEmpty() == true
            val laterWork = hasPrequel || seriesNames.isNotEmpty() || (DriveSubjectSeason.declaredIn(names, aliases = hasPrequel) ?: 1) >= 2
            // 系列的第一部 (名字就是系列名) 不按别的部的副标题认: 整个系列的合集标题常写着后几部的副标题, 第一部的集也在里面
            val parts = DriveSeriesParts.of(subjectId, table)
                ?.let { parts ->
                    DriveSeriesParts(parts.keywords, parts.seriesNames, parts.ownParts, parts.otherParts.filter { part -> own.none { part in it } })
                }
                ?.takeIf { it.ownParts.isNotEmpty() || laterWork }
            val roots = overlappingWorkNames(request, subjectId, table)
            val series = (parts?.seriesNames.orEmpty() + seriesNames).distinct()
            val notOwn = series + others
            val airYear = request.firstAirDate()?.year ?: ownNode?.airDate?.takeIf { it.isValid }?.year
            val otherWorkYears = subjectId?.let { DriveSeriesParts.seriesNodesOf(it, table) }.orEmpty()
                .mapNotNullTo(HashSet()) { node -> node.airDate.takeIf { it.isValid }?.year }
                .filterTo(HashSet()) { year -> airYear == null || abs(year - airYear) > 1 }
            val (ownNames, weakNames) = own.filter { it !in notOwn }.partition(TitleForms::isDistinctive)
            /** 本条目与同系列的作品 */
            val seriesIds = subjectId?.let { DriveSeriesParts.seriesNodesOf(it, table) }.orEmpty().mapTo(HashSet()) { it.id } +
                    listOfNotNull(subjectId)
            return DriveSubjectIdentity(
                ownNames = ownNames,
                otherWorkNames = others,
                parts = parts,
                seriesNames = series - roots.toSet(),
                rootNames = roots,
                split = seriesSplitSeasonOf(subjectId, table),
                seriesSeason = seriesSeasonOf(subjectId, table),
                airYear = airYear,
                otherWorkYears = otherWorkYears,
                laterWork = laterWork,
                hasPrequel = hasPrequel,
                titles = DriveTitleContinuation(ownTitles),
                weakNames = weakNames.filter { it.length >= MIN_WEAK_NAME_LENGTH && it.any { ch -> !ch.isDigit() } },
                knownWorks = index?.let { names -> DriveKnownWorks(names, seriesIds, own.toSet()) },
                versionYears = index?.let { names ->
                    // 同系列的作品名字一样的是季 (「工作细胞」与「工作细胞！！」归一化后一样), 不是版本
                    ownTitles.flatMap(names::worksNamed)
                        .filter { it.id !in seriesIds }
                        .mapNotNullTo(HashSet()) { node -> node.airDate.takeIf { it.isValid }?.year }
                        .filterTo(HashSet()) { year -> airYear != null && abs(year - airYear) > 1 }
                }.orEmpty(),
            )
        }

        /** 系列关系图里的作品 (有前传或续集): 名字里含着本条目主标题的只认这些, 系列以外的按名字相等认 ([DriveKnownWorks]) */
        private fun SeriesNode.inSeries(table: SeriesGraphTable): Boolean =
            table.edgesOf(id)?.let { it.prequels.isNotEmpty() || it.sequels.isNotEmpty() } == true
    }
}

/** 给查询请求算 [DriveSubjectIdentity]. 没有系列关系图时为 null (路径不查). */
internal fun interface DriveSubjectIdentities {
    suspend fun of(request: MediaFetchRequest): DriveSubjectIdentity?

    companion object {
        val None = DriveSubjectIdentities { null }

        /** 从系列关系图算; 同一个条目的几个数据源、几次切集共用, 记最近几个条目的结果. */
        fun of(repository: SeriesGraphTableRepository): DriveSubjectIdentities {
            val lock = Mutex()
            val cache = LinkedHashMap<List<Any>, DriveSubjectIdentity>()
            return DriveSubjectIdentities { request ->
                val table = repository.current() ?: return@DriveSubjectIdentities null
                val key = listOf(table.dump, table.allAnime, request.subjectId, request.subjectNames, request.nameHints)
                lock.withLock {
                    cache[key] ?: DriveSubjectIdentity.of(request, table, AnimeNameIndex.of(table)).also {
                        cache[key] = it
                        while (cache.size > CACHE_SIZE) cache.remove(cache.keys.first())
                    }
                }
            }
        }

        private const val CACHE_SIZE = 8
    }
}
