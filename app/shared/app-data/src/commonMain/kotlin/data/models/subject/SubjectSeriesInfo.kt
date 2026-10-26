/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.models.subject

import me.him188.ani.app.domain.mediasource.MediaListFilters

data class SubjectSeriesInfo(
//    val subjectId: Int,
//    /**
//     * 系列作品列表. 这可能会包含它自己
//     */
//    val seriesSubjects: List<SubjectCollectionInfo>,
//    /**
//     * 续集列表, 不包含自己
//     */
//    val sequelSubjects: List<SubjectCollectionInfo>,
    /**
     * 此条目是系列中的第几季. 从 1 开始计数.
     */
    val seasonSort: Int,
    /**
     * 所有续集的名称, 用于过滤掉非当前季度的条目
     */
    val sequelSubjectNames: Set<String>,
    /**
     * 系列作品名称, 不包含自己的名称
     */
    val seriesSubjectNamesWithoutSelf: Set<String>,
    /**
     * 当前条目所在的拆分季, 由服务端识别. 不是拆分季的一段时为 `null`.
     */
    val splitSeason: SplitSeason? = null,
    /**
     * 本条目的 sort 是不是全系列的序号 (正片第一集的 sort 接在系列主线上前面各条目的正片之后). 不知道时为 `null`.
     * 不是时 (只在一季里接着编, 如「地错 第四季 深章 灾厄篇」从 12 起) 不能按 sort 认领不带季号的系列页面, 那里的同号集是第一季的.
     */
    val sortsSpanSeries: Boolean? = null,
    /** 系列关系推出的本条目的「系列名 第N季」, 算作条目的别名 (见 `withSeasonNames`); 推不出时为空. */
    val seasonAliases: List<String> = emptyList(),
) {
    companion object {
        fun compute(
            requestingSubject: SubjectCollectionInfo,
        ): SubjectSeriesInfo {
            val sequelSubjectNames = requestingSubject.relations.sequelSubjectNames.toMutableSet().apply {
                removeAll { sequelName ->
                    // 如果续集名称存在于当前名称中, 则删除, 否则可能导致过滤掉当前季度的条目
                    requestingSubject.subjectInfo.allNames.any {
                        MediaListFilters.specialContains(it, sequelName)
                    }
                }
            }
            val seriesSubjectNamesWithoutSelf: Set<String> =
                requestingSubject.relations.seriesMainSubjectNames.toMutableSet().apply {
                    removeAll { seriesName ->
                        requestingSubject.subjectInfo.allNames.any { subjectName ->
                            MediaListFilters.specialEquals(subjectName, seriesName)
                        }
                    }
                }

            val seasonSort = requestingSubject.relations.seriesMainSubjectIds
                .indexOfFirst { it == requestingSubject.subjectId }
                .let { if (it == -1) 1 else it + 1 }
            return SubjectSeriesInfo(
//                subjectId,
//                seriesSubjects,
//                sequelSubjects,
                seasonSort = seasonSort,
                sequelSubjectNames,
                seriesSubjectNamesWithoutSelf,
                splitSeason = requestingSubject.relations.splitSeason,
            )
        }

        val Fallback = SubjectSeriesInfo(
//            emptyList(),
//            emptyList(),
            seasonSort = 1,
            sequelSubjectNames = emptySet(),
            seriesSubjectNamesWithoutSelf = emptySet(),
        )
    }
}

fun SubjectSeriesInfo.toBuilder() = SubjectSeriesInfoBuilder(seasonSort).apply {
    sequel(*sequelSubjectNames.toTypedArray())
    series(*seriesSubjectNamesWithoutSelf.toTypedArray())
    splitSeason = this@toBuilder.splitSeason
    sortsSpanSeries = this@toBuilder.sortsSpanSeries
}

// mainly designed for tests
class SubjectSeriesInfoBuilder(
    /**
     * 此条目是系列中的第几季
     * @see SubjectSeriesInfo.seasonSort
     */
    var seasonSort: Int,
) {
    private val sequelNames = mutableListOf<String>()
    private val seriesNames = mutableListOf<String>()

    /**
     * @see SubjectSeriesInfo.splitSeason
     */
    var splitSeason: SplitSeason? = null

    /**
     * @see SubjectSeriesInfo.sortsSpanSeries
     */
    var sortsSpanSeries: Boolean? = null

    /**
     * 添加一个续集番名.
     * @see SubjectSeriesInfo.sequelSubjectNames
     */
    fun sequel(vararg subjectName: String) {
        sequelNames.addAll(subjectName)
        seriesNames.addAll(subjectName)
    }

    /**
     * 添加一个同系列的番名
     * @see SubjectSeriesInfo.seriesSubjectNamesWithoutSelf
     */
    fun series(vararg subjectName: String) {
        seriesNames.addAll(subjectName)
    }

    fun build() = SubjectSeriesInfo(
        seasonSort, sequelSubjectNames = sequelNames.toSet(), seriesSubjectNamesWithoutSelf = seriesNames.toSet(),
        splitSeason = splitSeason,
        sortsSpanSeries = sortsSpanSeries,
    )
}

inline fun buildSubjectSeriesInfo(
    seasonSort: Int,
    block: SubjectSeriesInfoBuilder.() -> Unit
) =
    SubjectSeriesInfoBuilder(seasonSort).apply(block).build()
