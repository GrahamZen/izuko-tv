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
import me.him188.ani.app.domain.mediasource.rss.firstAirDate
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.source.MediaFetchNameHints
import me.him188.ani.datasources.api.source.MediaFetchRequest
import kotlin.math.abs

/**
 * 网盘上的一段名字 (分享站的标题、合集与分享里的文件夹名、文件名) 点名的是不是这一部. 自动认出来的地方都按它:
 * 分享站的标题 ([rejectsTitle])、固定合集与播放线索里的文件夹 ([namesOtherWork])、分享与自己网盘里的视频 ([DrivePathAttribution]);
 * 用户添加的分享、手挑的文件夹是用户指定给这个条目的, 不查.
 *
 * 名字与网页类数据源认部 ([me.him188.ani.app.domain.mediasource.search.SubjectSearchPlan]) 是同一组: 搜索名与请求带的
 * [MediaFetchNameHints.ownNames] 是这一部的; 别的作品是系列关系图里以本条目的主标题开头的 ([SeriesGraphTable.nodesContaining]:
 * 「机动战士高达」→「机动战士高达SEED」「机动战士高达 水星的魔女」)、顺着前传续集走得到的, 以及 [MediaFetchNameHints.otherWorkNames];
 * 本条目名字里含着的是系列名 (「死神 千年血战篇」之于它的各部), 谁也不点名. 一段名字里同时有几部的名字时最长的那个说了算:
 * 「机动战士高达SEED」是 SEED; 「XX 第三季 (附第二季)」里有本条目同样长的名字, 不算别的作品.
 * 「系列名 + 副标题」的条目再按副标题认 ([DriveSeriesParts]): 只有这一部的副标题是这一部, 只有别的部的副标题是别的部, 都有的是几部合在一起.
 * 别的部的副标题出现在本条目自己的名字里的不算: 后几季常拿第一季的副标题当系列名 (「ツルネ ―風舞高校弓道部― 第2期」).
 * 系列关系图只收有前传或续作的动画, 只有一部、没有关系的作品认不出别的作品.
 */
internal class DriveSubjectIdentity(
    /** 本条目的名字, 已归一化 ([DriveNameParser.normalize]); 太短、与系列名或别的作品同名的不在里面 */
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
    /** 本条目是系列的第一部时的开播年份; 续作或不知道时为 null */
    private val firstWorkAirYear: Int? = null,
    /**
     * 本条目是系列里后来的作品 (有前传, 名字里含着系列名, 或名字写明了第 2 季及以后): 整个系列的合集里它的集与前几部编号相同,
     * 路径认不出是这一部的视频只认集号上的证据 (见 [DriveSubjectMatcher.matchEpisodes]).
     */
    val laterWork: Boolean = false,
) {
    /** 本条目是拆分季的第几段, 从 1 数 */
    val splitPart: Int? get() = split?.let { it.selfIndex + 1 }

    /** 一段名字点名了哪一部. */
    enum class Naming {
        OWN,
        OTHER,

        /** 同时点名了这一部与别的部 (「诀别谭+相克谭+死神全集」) */
        SEVERAL,
        NONE,
    }

    /** 名字 [name] 点名了哪一部, 见类说明. */
    fun namingOf(name: String): Naming {
        val normalized = DriveNameParser.normalize(name)
        val ownHits = ownNames.filter { it in normalized }
        val otherHits = otherWorkNames.filter { it in normalized }
        val own = ownHits.any { hit -> otherHits.none { it.length > hit.length && hit in it } } ||
                parts?.ownParts?.any { it in normalized } == true
        val other = otherHits.any { hit -> ownHits.none { it.length >= hit.length } } ||
                parts?.otherParts?.any { it in normalized } == true
        return when {
            own && other -> Naming.SEVERAL
            own -> Naming.OWN
            other -> Naming.OTHER
            else -> Naming.NONE
        }
    }

    /** 文件夹路径 [path] (从外到里) 是别的作品的: 从里往外第一个点名了某一部的名字点名的是别的部. 播放线索、固定合集里的文件夹用. */
    fun namesOtherWork(path: List<String>): Boolean =
        path.asReversed().firstNotNullOfOrNull { segment -> namingOf(segment).takeIf { it != Naming.NONE } } == Naming.OTHER

    /**
     * 名字 [name] 提到了这个系列: 有系列名时为 true, 只有集号与本条目重叠的作品的名字 ([rootNames]) 时为 false, 都没有时为 null.
     * 跨部连续编号的集号只在点的是系列名时算数, 见 [DrivePathAttribution.continuesSeries].
     */
    fun mentionOf(name: String): Boolean? {
        val normalized = DriveNameParser.normalize(name)
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
        val airYear = firstWorkAirYear ?: return false
        if (REMASTER.containsMatchIn(title)) return false
        val years = YEAR.findAll(title).map { it.value.toInt() }.toList()
        return years.isNotEmpty() && years.none { abs(it - airYear) <= 1 }
    }

    /** 分享站的标题是别的作品的: 名字点名了别的部, 或年份不对. */
    fun rejectsTitle(title: String): Boolean = namingOf(title) == Naming.OTHER || isOtherYear(title)

    companion object {
        /** 1950 到 2049 年, 前后不挨着别的数字 */
        private val YEAR = Regex("""(?<!\d)(19[5-9]\d|20[0-4]\d)(?!\d)""")
        private val REMASTER = Regex("""重制|重製|修复|修復|remaster""", RegexOption.IGNORE_CASE)

        /** 拿来认路径的作品名归一化后至少几个字; 只有英文字母数字的至少 [MIN_LATIN_NAME_LENGTH] 个 (「Air」是很多词的一部分). */
        private const val MIN_NAME_LENGTH = 3
        private const val MIN_LATIN_NAME_LENGTH = 5

        /** 归一化的作品名够长, 拿来认路径不会碰上别的词. */
        private fun String.isLongEnough(): Boolean =
            length >= if (all { it in 'a'..'z' || it in '0'..'9' }) MIN_LATIN_NAME_LENGTH else MIN_NAME_LENGTH

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
                .map(DriveNameParser::normalize)
                .filter { it.length >= MIN_SERIES_NAME_LENGTH }
                .distinct()
        }

        /** 系列名归一化后至少几个字 (「死神」) */
        private const val MIN_SERIES_NAME_LENGTH = 2

        fun of(request: MediaFetchRequest, table: SeriesGraphTable): DriveSubjectIdentity {
            val subjectId = request.subjectId.toIntOrNull()
            val names = DriveSubjectMatcher.subjectNamesOf(request)
            val ownNode = subjectId?.let(table::nodeOf)
            val hints = request.nameHints
            val seriesNames = hints.seriesNames.map(DriveNameParser::normalize).filter { it.isNotEmpty() }
            val own = (names + hints.ownNames + listOfNotNull(ownNode?.name, ownNode?.nameCn))
                .map(DriveNameParser::normalize)
                .filter { it.isNotEmpty() }
                .distinct()
            // 用主标题 (去掉季与副标题) 找: 「葬送的芙莉莲 第二季」的别的作品是所有以「葬送的芙莉莲」开头的作品;
            // 再加上同系列 (前传、续集能走到的) 的作品, 本条目名字里含着的 (系列名) 不算
            val bases = names.map(DriveNameParser::baseTitle).filter { DriveNameParser.normalize(it).length >= 2 }.distinct()
            val startingWithBase = bases.flatMap { base ->
                val normalizedBase = DriveNameParser.normalize(base)
                (table.nodesContaining(base).filter { it.id != subjectId }.flatMap { listOf(it.name, it.nameCn) } + hints.otherWorkNames)
                    .map(DriveNameParser::normalize)
                    .filter { it.length > normalizedBase.length && normalizedBase in it }
            }
            val inSeries = (subjectId?.let { DriveSeriesParts.seriesNodesOf(it, table) }.orEmpty().flatMap { listOf(it.name, it.nameCn) } + hints.otherWorkNames)
                .map(DriveNameParser::normalize)
                .filter { name -> name.isLongEnough() && own.none { name in it } }
            val others = (startingWithBase + inSeries).filter { it !in own }.distinct()
            val parts = DriveSeriesParts.of(subjectId, table)?.let { parts ->
                DriveSeriesParts(parts.keywords, parts.seriesNames, parts.ownParts, parts.otherParts.filter { part -> own.none { part in it } })
            }
            val roots = overlappingWorkNames(request, subjectId, table)
            val series = (parts?.seriesNames.orEmpty() + seriesNames).distinct()
            val notOwn = series + others
            val edges = subjectId?.let(table::edgesOf)
            val firstWork = edges?.prequels?.isEmpty() == true
            val airYear = if (firstWork) {
                request.firstAirDate()?.year ?: ownNode?.airDate?.takeIf { it.isValid }?.year
            } else {
                null
            }
            val declaredSeason = DriveSubjectSeason.declaredIn(names)
            return DriveSubjectIdentity(
                ownNames = own.filter { it.isLongEnough() && it !in notOwn },
                otherWorkNames = others,
                parts = parts,
                seriesNames = series - roots.toSet(),
                rootNames = roots,
                split = seriesSplitSeasonOf(subjectId, table),
                seriesSeason = seriesSeasonOf(subjectId, table),
                firstWorkAirYear = airYear,
                laterWork = edges?.prequels?.isNotEmpty() == true || seriesNames.isNotEmpty() || (declaredSeason ?: 1) >= 2,
            )
        }
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
                val key = listOf(table.dump, request.subjectId, request.subjectNames, request.nameHints)
                lock.withLock {
                    cache[key] ?: DriveSubjectIdentity.of(request, table).also {
                        cache[key] = it
                        while (cache.size > CACHE_SIZE) cache.remove(cache.keys.first())
                    }
                }
            }
        }

        private const val CACHE_SIZE = 8
    }
}
