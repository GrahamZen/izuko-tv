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
import me.him188.ani.app.data.network.SeriesGraphTable
import me.him188.ani.app.data.network.SeriesGraphTableRepository
import me.him188.ani.app.domain.mediasource.rss.firstAirDate
import me.him188.ani.datasources.api.source.MediaFetchRequest
import kotlin.math.abs

/**
 * 网盘上的名字 (分享站给的标题、文件夹路径) 是不是同系列别的作品. 系列里的作品常以第一部的名字开头
 * (「机动战士高达」→「机动战士高达SEED」「机动战士高达 水星的魔女」), 网盘这边按名字包含来认, 会把它们都当成这一部.
 *
 * - [isOtherWork]: 名字里含有一部已知的别的作品的名字 —— 系列关系图 ([SeriesGraphTable]) 里比本条目的名字长、又含有本条目名字的
 *   原名或中文名 (不是本条目自己的). 名字里同时有本条目同样长的名字时不算 (合集「XX 第三季 (附第二季)」).
 *   系列关系图只收有前传或续作的动画, 只有一部、没有关系的作品认不出.
 * - [isOtherYear]: 只看系列的第一部 (表里没有前传): 标题写了年份, 而且都与开播年份差一年以上. 续作不看年份:
 *   整部的合集常按第一季的年份写. 重制版写的是重制那年, 也不看.
 *
 * 只用在自动认出来的地方 (分享站搜索、固定合集、自己网盘的搜索, 以及由它们记下的播放线索里的文件夹: 自动选错过一次的会被记下);
 * 用户添加的分享、手挑的文件夹是用户指定给这个条目的, 不查.
 */
internal class OtherWorkGuard(
    /** 本条目自己的名字, 已归一化 ([DriveNameParser.normalize]) */
    private val ownNames: List<String>,
    /** 别的作品的名字, 已归一化 */
    internal val otherWorkNames: List<String>,
    /** 本条目是系列的第一部时的开播年份; 续作或不知道时为 null */
    private val firstWorkAirYear: Int?,
    /** 「系列名 + 副标题」的条目按条目名搜不到时的补搜; 不是这种写法时为 null */
    val parts: DriveSharePartSearch? = null,
    /** 系列关系图推出的本条目是第几季 ([seriesSeasonOf]), 认季用 (见 [DriveSubjectSeason]) */
    val seriesSeason: Int? = null,
) {
    /** [name] (标题或路径) 是不是别的作品的. */
    fun isOtherWork(name: String): Boolean {
        if (otherWorkNames.isEmpty()) return false
        val normalized = DriveNameParser.normalize(name)
        return otherWorkNames.any { other ->
            other in normalized && ownNames.none { own -> own.length >= other.length && own in normalized }
        }
    }

    /** 标题 [title] 写的年份是不是别的作品的 (只对系列的第一部). */
    fun isOtherYear(title: String): Boolean {
        val airYear = firstWorkAirYear ?: return false
        if (REMASTER.containsMatchIn(title)) return false
        val years = YEAR.findAll(title).map { it.value.toInt() }.toList()
        return years.isNotEmpty() && years.none { abs(it - airYear) <= 1 }
    }

    /** 分享站的标题: 名字与年份都看. */
    fun rejectsTitle(title: String): Boolean = isOtherWork(title) || isOtherYear(title)

    /** 同一个, 只是不补搜 (对照补搜前后用). */
    internal fun withoutParts(): OtherWorkGuard = OtherWorkGuard(ownNames, otherWorkNames, firstWorkAirYear, seriesSeason = seriesSeason)

    companion object {
        /** 1950 到 2049 年, 前后不挨着别的数字 */
        private val YEAR = Regex("""(?<!\d)(19[5-9]\d|20[0-4]\d)(?!\d)""")
        private val REMASTER = Regex("""重制|重製|修复|修復|remaster""", RegexOption.IGNORE_CASE)

        fun of(request: MediaFetchRequest, table: SeriesGraphTable): OtherWorkGuard {
            val subjectId = request.subjectId.toIntOrNull()
            val names = DriveSubjectMatcher.subjectNamesOf(request)
            val ownNode = subjectId?.let(table::nodeOf)
            val own = (names + listOfNotNull(ownNode?.name, ownNode?.nameCn))
                .map(DriveNameParser::normalize)
                .filter { it.isNotEmpty() }
                .distinct()
            // 用主标题 (去掉季与副标题) 在表里找: 「葬送的芙莉莲 第二季」找的是所有以「葬送的芙莉莲」开头的作品
            val others = names.map(DriveNameParser::baseTitle)
                .filter { DriveNameParser.normalize(it).length >= 2 }
                .distinct()
                .flatMap { base ->
                    val normalizedBase = DriveNameParser.normalize(base)
                    table.nodesContaining(base)
                        .filter { it.id != subjectId }
                        .flatMap { listOf(it.name, it.nameCn) }
                        .map(DriveNameParser::normalize)
                        .filter { it.length > normalizedBase.length && normalizedBase in it && it !in own }
                }
                .distinct()
            val firstWork = subjectId?.let(table::edgesOf)?.prequels?.isEmpty() == true
            val airYear = if (firstWork) {
                request.firstAirDate()?.year ?: ownNode?.airDate?.takeIf { it.isValid }?.year
            } else {
                null
            }
            return OtherWorkGuard(own, others, airYear, DriveSharePartSearch.of(subjectId, table), seriesSeasonOf(subjectId, table))
        }
    }
}

/** 给查询请求算 [OtherWorkGuard]. 没有系列关系图时为 null (什么都不排除). */
internal fun interface OtherWorks {
    suspend fun of(request: MediaFetchRequest): OtherWorkGuard?

    companion object {
        val None = OtherWorks { null }

        /** 从系列关系图算; 同一个条目的几个数据源、几次切集共用, 记最近几个条目的结果. */
        fun of(repository: SeriesGraphTableRepository): OtherWorks {
            val lock = Mutex()
            val cache = LinkedHashMap<List<String>, OtherWorkGuard>()
            return OtherWorks { request ->
                val table = repository.current() ?: return@OtherWorks null
                val key = listOf(table.dump, request.subjectId) + request.subjectNames
                lock.withLock {
                    cache[key] ?: OtherWorkGuard.of(request, table).also {
                        cache[key] = it
                        while (cache.size > CACHE_SIZE) cache.remove(cache.keys.first())
                    }
                }
            }
        }

        private const val CACHE_SIZE = 8
    }
}
